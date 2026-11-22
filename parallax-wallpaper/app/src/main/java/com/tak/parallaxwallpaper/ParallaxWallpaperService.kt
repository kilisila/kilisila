package com.tak.parallaxwallpaper

import android.content.SharedPreferences
import android.graphics.Canvas
import android.os.Build
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceHolder
import android.view.WindowManager

/**
 * 3D パララックス効果を持つライブ壁紙。
 *
 * すべての処理（センサー通知、Choreographer、Engine のコールバック）はメインスレッドで動く。
 */
class ParallaxWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = ParallaxEngine()

    private inner class ParallaxEngine :
        Engine(),
        Choreographer.FrameCallback,
        SharedPreferences.OnSharedPreferenceChangeListener {

        private val renderer = ParallaxRenderer(resources)
        private val tiltTracker = TiltTracker(this@ParallaxWallpaperService)
        private val depthPrefs = DepthPrefs(this@ParallaxWallpaperService)
        private val frameLimiter = FrameLimiter(TARGET_FPS)

        // 傾きの平滑化。細かな揺れを除き、ぎこちない動きを防ぐ。
        private val smoothX = LowPassFilter(SMOOTHING_SEC)
        private val smoothY = LowPassFilter(SMOOTHING_SEC)

        // 中立位置の緩やかな追従。同じ傾きのまま持ち続けると、徐々にその姿勢が中立になる。
        private val centerX = LowPassFilter(RECENTER_SEC)
        private val centerY = LowPassFilter(RECENTER_SEC)

        private var depth = ParallaxMath.DEPTH_DEFAULT
        private var running = false
        private var lastFrameNanos = 0L
        private val startMillis = SystemClock.uptimeMillis()

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
            depth = depthPrefs.depth
            depthPrefs.registerListener(this)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            requestFrameRate(holder)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            renderer.prepare(width, height)
            tiltTracker.displayRotation = currentRotation()
            drawFrame(smoothX.value, smoothY.value)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) startAnimation() else stopAnimation()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            stopAnimation()
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            stopAnimation()
            depthPrefs.unregisterListener(this)
            renderer.release()
            super.onDestroy()
        }

        override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
            if (key == null || key == DepthPrefs.KEY_DEPTH) {
                depth = depthPrefs.depth
            }
        }

        /** 表示されたときに、センサーの登録と描画ループを開始する。 */
        private fun startAnimation() {
            if (running) return
            running = true
            tiltTracker.displayRotation = currentRotation()
            if (!tiltTracker.start()) {
                Log.w(TAG, "回転ベクトルセンサーを利用できないため、傾きによる移動は無効です")
            }
            smoothX.reset(0f)
            smoothY.reset(0f)
            centerX.reset(0f)
            centerY.reset(0f)
            frameLimiter.reset()
            lastFrameNanos = 0L
            Choreographer.getInstance().postFrameCallback(this)
        }

        /** 非表示になったときに、電池節約のためセンサーの登録を解除し、描画を止める。 */
        private fun stopAnimation() {
            if (!running) return
            running = false
            Choreographer.getInstance().removeFrameCallback(this)
            tiltTracker.stop()
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            // 次のフレームを先に予約しておく
            Choreographer.getInstance().postFrameCallback(this)
            if (!frameLimiter.shouldDraw(frameTimeNanos)) return

            val dtSec = if (lastFrameNanos == 0L) {
                0f
            } else {
                ((frameTimeNanos - lastFrameNanos) / 1_000_000_000.0).toFloat().coerceIn(0f, MAX_DT_SEC)
            }
            lastFrameNanos = frameTimeNanos

            val rawX = tiltTracker.tiltXRad
            val rawY = tiltTracker.tiltYRad
            val cx = centerX.update(rawX, dtSec)
            val cy = centerY.update(rawY, dtSec)
            val targetX = ParallaxMath.normalizeAngle(rawX - cx, MAX_TILT_RAD)
            val targetY = ParallaxMath.normalizeAngle(rawY - cy, MAX_TILT_RAD)
            val x = smoothX.update(targetX, dtSec)
            val y = smoothY.update(targetY, dtSec)

            drawFrame(x, y)
        }

        private fun drawFrame(tiltX: Float, tiltY: Float) {
            if (!renderer.isReady) return
            val holder = surfaceHolder
            val canvas = lockCanvas(holder) ?: return
            try {
                val elapsed = SystemClock.uptimeMillis() - startMillis
                renderer.draw(canvas, tiltX, tiltY, depth, ParallaxMath.breathingAlpha(elapsed))
            } finally {
                try {
                    holder.unlockCanvasAndPost(canvas)
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "描画結果の反映に失敗しました", e)
                }
            }
        }

        /** 可能ならハードウェア描画の Canvas を使い、使えない場合はソフトウェア描画に切り替える。 */
        private fun lockCanvas(holder: SurfaceHolder): Canvas? {
            return try {
                holder.lockHardwareCanvas()
            } catch (e: Exception) {
                try {
                    holder.lockCanvas()
                } catch (e2: Exception) {
                    Log.w(TAG, "Canvas を取得できませんでした", e2)
                    null
                }
            }
        }

        /** 画面側に 60Hz での更新を希望として伝える（Android 11 以降）。強制ではない。 */
        private fun requestFrameRate(holder: SurfaceHolder) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
            try {
                holder.surface.setFrameRate(TARGET_FPS.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            } catch (e: Exception) {
                Log.w(TAG, "描画頻度の指定に失敗しました", e)
            }
        }

        private fun currentRotation(): Int {
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    displayContext?.display?.rotation ?: Surface.ROTATION_0
                } else {
                    @Suppress("DEPRECATION")
                    (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
                }
            } catch (e: Exception) {
                Surface.ROTATION_0
            }
        }
    }

    companion object {
        private const val TAG = "ParallaxWallpaper"

        private const val TARGET_FPS = 60

        /** 傾きの平滑化の時定数（秒）。大きいほど滑らかで、反応は遅くなる。 */
        private const val SMOOTHING_SEC = 0.12f

        /** 中立位置が現在の姿勢へ追従する時定数（秒）。 */
        private const val RECENTER_SEC = 6f

        /** この角度（約 20 度）傾けたときに移動量が最大になる。 */
        private const val MAX_TILT_RAD = 0.35f

        /** 復帰直後などで経過時間が極端に大きくなった場合の上限（秒）。 */
        private const val MAX_DT_SEC = 0.1f
    }
}
