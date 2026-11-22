package com.tak.parallaxwallpaper

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 3 枚のレイヤーとドロップシャドウを Canvas に描画する。
 *
 * 画像は画面サイズに合わせて一度だけ拡大縮小しておき、毎フレームは位置をずらして
 * 描くだけにすることで、描画の負荷を抑えている。
 */
class ParallaxRenderer(private val resources: Resources) {

    /** 拡大縮小済みのレイヤー。left/top は移動量 0 のときの描画位置。 */
    private class Layer(val bitmap: Bitmap, val left: Float, val top: Float)

    private var background: Layer? = null
    private var glow: Layer? = null
    private var foreground: Layer? = null

    /** 前景の不透明部分をぼかした影（ALPHA_8）と、前景の左上を基準にした描画範囲。 */
    private var shadow: Bitmap? = null
    private val shadowRect = RectF()
    private val tmpRect = RectF()

    private var width = 0
    private var height = 0

    private val layerPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        alpha = SHADOW_ALPHA
    }

    val isReady: Boolean get() = width > 0 && height > 0

    /** 画面サイズに合わせて画像を読み込む。サイズが同じなら何もしない。 */
    fun prepare(newWidth: Int, newHeight: Int) {
        if (newWidth <= 0 || newHeight <= 0) return
        if (newWidth == width && newHeight == height && background != null) return
        release()
        width = newWidth
        height = newHeight

        // 背景は不透明なので、動かしても端に隙間が出ないよう大きめに描く。
        // 光と前景は透過画像のため、画面と同じ大きさで描く。
        background = loadLayer(R.drawable.background, ParallaxMath.overscanScale(ParallaxMath.BACKGROUND_FACTOR))
        glow = loadLayer(R.drawable.glow, 1f)
        foreground = loadLayer(R.drawable.foreground, 1f)
        foreground?.let { buildShadow(it.bitmap) }
    }

    /** 保持している画像をすべて解放する。 */
    fun release() {
        background?.bitmap?.recycle()
        glow?.bitmap?.recycle()
        foreground?.bitmap?.recycle()
        shadow?.recycle()
        background = null
        glow = null
        foreground = null
        shadow = null
        width = 0
        height = 0
    }

    /**
     * 1 フレームを描画する。
     *
     * @param tiltX 左右の傾き（-1〜1）
     * @param tiltY 上下の傾き（-1〜1）
     * @param depth 奥行きの強さ
     * @param glowAlpha 光のレイヤーの不透明度（0〜1）
     */
    fun draw(canvas: Canvas, tiltX: Float, tiltY: Float, depth: Float, glowAlpha: Float) {
        canvas.drawColor(Color.BLACK)
        if (!isReady) return

        background?.let { layer ->
            val dx = ParallaxMath.layerOffset(tiltX, ParallaxMath.BACKGROUND_FACTOR, ParallaxMath.BACKGROUND_DIRECTION, width, depth)
            val dy = ParallaxMath.layerOffset(tiltY, ParallaxMath.BACKGROUND_FACTOR, ParallaxMath.BACKGROUND_DIRECTION, height, depth)
            canvas.drawBitmap(layer.bitmap, layer.left + dx, layer.top + dy, layerPaint)
        }

        glow?.let { layer ->
            val dx = ParallaxMath.layerOffset(tiltX, ParallaxMath.GLOW_FACTOR, ParallaxMath.GLOW_DIRECTION, width, depth)
            val dy = ParallaxMath.layerOffset(tiltY, ParallaxMath.GLOW_FACTOR, ParallaxMath.GLOW_DIRECTION, height, depth)
            glowPaint.alpha = ParallaxMath.toPaintAlpha(glowAlpha)
            canvas.drawBitmap(layer.bitmap, layer.left + dx, layer.top + dy, glowPaint)
        }

        foreground?.let { layer ->
            val dx = ParallaxMath.layerOffset(tiltX, ParallaxMath.FOREGROUND_FACTOR, ParallaxMath.FOREGROUND_DIRECTION, width, depth)
            val dy = ParallaxMath.layerOffset(tiltY, ParallaxMath.FOREGROUND_FACTOR, ParallaxMath.FOREGROUND_DIRECTION, height, depth)

            // 影は前景より小さく動かす。前景との間にずれが生まれ、浮いているように見える。
            // さらに傾きに応じて前景とのずれを少し広げ、立体感を強める。
            shadow?.let { shadowBitmap ->
                val sx = dx * SHADOW_FOLLOW + tiltX * width * SHADOW_TILT_SHIFT * ParallaxMath.clampDepth(depth)
                val sy = dy * SHADOW_FOLLOW + tiltY * height * SHADOW_TILT_SHIFT * ParallaxMath.clampDepth(depth) +
                    height * SHADOW_DROP
                tmpRect.set(shadowRect)
                tmpRect.offset(layer.left + sx, layer.top + sy)
                canvas.drawBitmap(shadowBitmap, null, tmpRect, shadowPaint)
            }

            canvas.drawBitmap(layer.bitmap, layer.left + dx, layer.top + dy, layerPaint)
        }
    }

    /**
     * 画像を読み込み、画面全体を覆う大きさ（中央合わせで切り抜き）に拡大縮小する。
     *
     * res/drawable に置いた画像は、そのまま読むと端末の画素密度に応じて自動で拡大され、
     * メモリを大きく消費する。inScaled = false で自動拡大を止め、こちらで寸法を決める。
     */
    private fun loadLayer(resId: Int, scale: Float): Layer? {
        val targetW = ceil(width * scale).toInt()
        val targetH = ceil(height * scale).toInt()

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            inScaled = false
        }
        BitmapFactory.decodeResource(resources, resId, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetW, targetH)
        }
        val decoded = BitmapFactory.decodeResource(resources, resId, options) ?: return null

        val fit = max(targetW.toFloat() / decoded.width, targetH.toFloat() / decoded.height)
        val scaledW = max(1, (decoded.width * fit).roundToInt())
        val scaledH = max(1, (decoded.height * fit).roundToInt())
        val scaled = if (scaledW == decoded.width && scaledH == decoded.height) {
            decoded
        } else {
            Bitmap.createScaledBitmap(decoded, scaledW, scaledH, true).also {
                if (it !== decoded) decoded.recycle()
            }
        }

        // 画面中央に合わせる
        val left = (width - scaledW) / 2f
        val top = (height - scaledH) / 2f
        return Layer(scaled, left, top)
    }

    /** 必要な寸法を下回らない範囲で、読み込み時の間引き率（2 の累乗）を求める。 */
    private fun sampleSize(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Int {
        var sample = 1
        while (srcW / (sample * 2) >= dstW && srcH / (sample * 2) >= dstH) {
            sample *= 2
        }
        return sample
    }

    /**
     * 前景の不透明部分からぼかした影を作る。
     *
     * 縮小してからぼかし、描画時に引き伸ばすことで、処理を軽くしつつ柔らかい影にする。
     * ぼかし処理は読み込み時に一度だけ行い、毎フレームは描くだけにする。
     */
    private fun buildShadow(source: Bitmap) {
        val smallW = max(1, source.width / SHADOW_DOWNSCALE)
        val smallH = max(1, source.height / SHADOW_DOWNSCALE)
        val small = Bitmap.createScaledBitmap(source, smallW, smallH, true)

        val blurRadius = max(1f, width * SHADOW_BLUR / SHADOW_DOWNSCALE)
        val blurPaint = Paint().apply {
            maskFilter = BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL)
        }
        val offset = IntArray(2)
        val alpha = small.extractAlpha(blurPaint, offset)
        if (small !== source) small.recycle()

        val ratioX = source.width.toFloat() / smallW
        val ratioY = source.height.toFloat() / smallH
        shadowRect.set(
            offset[0] * ratioX,
            offset[1] * ratioY,
            (offset[0] + alpha.width) * ratioX,
            (offset[1] + alpha.height) * ratioY,
        )
        shadow = alpha
    }

    companion object {
        /** 影の濃さ（0〜255）。 */
        private const val SHADOW_ALPHA = 120
        /** 影のぼかし半径。画面幅に対する割合。 */
        private const val SHADOW_BLUR = 0.035f
        /** 影を作るときの縮小率。 */
        private const val SHADOW_DOWNSCALE = 4
        /** 前景の移動量に対する、影の移動量の割合。 */
        private const val SHADOW_FOLLOW = 0.6f
        /** 傾きに応じて影を追加でずらす量。画面の幅・高さに対する割合。 */
        private const val SHADOW_TILT_SHIFT = 0.02f
        /** 影を常に下へずらす量。画面の高さに対する割合。 */
        private const val SHADOW_DROP = 0.02f
    }
}
