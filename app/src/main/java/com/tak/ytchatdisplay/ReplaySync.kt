package com.tak.ytchatdisplay

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * アーカイブのチャットリプレイを動かすための仮想再生時計。
 *
 * 実際の動画はテレビ側で再生されているため、こちらは経過時間だけを自前で数え、
 * その値を定期的にチャット側へ渡すことで表示位置を進める。
 */
class ReplaySync(
    private val intervalMs: Long = 500L,
    private val onTick: (positionMs: Long, playing: Boolean) -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())

    /** 停止中の基準位置。 */
    private var baseMs = 0L

    /** 再生を始めた時点の端末時刻。 */
    private var startedAt = 0L

    var playing = false
        private set

    val positionMs: Long
        get() = if (playing) baseMs + (SystemClock.elapsedRealtime() - startedAt) else baseMs

    fun play() {
        if (playing) return
        baseMs = positionMs
        startedAt = SystemClock.elapsedRealtime()
        playing = true
        onTick(positionMs, true)
        schedule()
    }

    fun pause() {
        if (!playing) return
        baseMs = positionMs
        playing = false
        handler.removeCallbacksAndMessages(null)
        onTick(baseMs, false)
    }

    fun toggle() = if (playing) pause() else play()

    fun seekTo(ms: Long) {
        baseMs = ms.coerceAtLeast(0L)
        startedAt = SystemClock.elapsedRealtime()
        onTick(baseMs, playing)
    }

    /** 現在位置からの相対移動。テレビとのずれを詰めるために使う。 */
    fun nudge(deltaMs: Long) = seekTo(positionMs + deltaMs)

    fun release() {
        // playing を倒す前に現在位置を確定させる（順序を逆にすると位置が巻き戻る）
        baseMs = positionMs
        playing = false
        handler.removeCallbacksAndMessages(null)
    }

    private fun schedule() {
        handler.postDelayed({
            if (playing) {
                onTick(positionMs, true)
                schedule()
            }
        }, intervalMs)
    }
}
