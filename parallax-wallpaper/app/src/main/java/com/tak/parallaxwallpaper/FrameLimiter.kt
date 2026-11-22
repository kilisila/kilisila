package com.tak.parallaxwallpaper

/**
 * 画面の更新頻度（60Hz、120Hz、144Hz など）にかかわらず、目標の描画頻度に間引く。
 *
 * 次の描画予定時刻を一定間隔で進める方式のため、更新頻度が目標の整数倍でない画面でも
 * 平均すると目標の頻度に近づく。
 *
 * @param targetFps 目標の描画頻度
 * @param slackNanos 予定時刻より少し早く来たフレームも描画対象とする許容幅
 */
class FrameLimiter(
    targetFps: Int,
    private val slackNanos: Long = 3_000_000L,
) {
    val periodNanos: Long = 1_000_000_000L / targetFps.coerceAtLeast(1)

    private var nextFrameNanos = 0L
    private var started = false

    fun reset() {
        started = false
    }

    /** このフレームを描画すべきかを判定する。 */
    fun shouldDraw(frameTimeNanos: Long): Boolean {
        // 初回、または 1 周期以上遅れた場合は予定を立て直す
        if (!started || frameTimeNanos - nextFrameNanos > periodNanos) {
            started = true
            nextFrameNanos = frameTimeNanos + periodNanos
            return true
        }
        if (frameTimeNanos >= nextFrameNanos - slackNanos) {
            nextFrameNanos += periodNanos
            return true
        }
        return false
    }
}
