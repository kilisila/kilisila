package com.tak.parallaxwallpaper

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * パララックス表示に関わる計算をまとめたもの。Android API に依存しないため単体テストできる。
 */
object ParallaxMath {

    /** 傾きが最大（±1）のときの移動量。画面の幅・高さに対する割合。 */
    const val BACKGROUND_FACTOR = 0.05f
    const val GLOW_FACTOR = 0.12f
    const val FOREGROUND_FACTOR = 0.25f

    /** 移動の向き。前景は背景と逆方向に動かして奥行きを強調する。 */
    const val BACKGROUND_DIRECTION = 1f
    const val GLOW_DIRECTION = 1f
    const val FOREGROUND_DIRECTION = -1f

    /** 奥行きの強さ（設定画面のスライダーで調整する倍率）。 */
    const val DEPTH_MIN = 0.5f
    const val DEPTH_MAX = 2.0f
    const val DEPTH_DEFAULT = 1.0f

    /** スライダーの分解能。0.01 倍刻みとする。 */
    const val DEPTH_STEPS = 150

    /** 光のレイヤーの明滅。不透明度 70〜100% を 4 秒周期で往復する。 */
    const val BREATH_PERIOD_MS = 4_000L
    const val BREATH_MIN_ALPHA = 0.70f
    const val BREATH_MAX_ALPHA = 1.00f

    fun clampDepth(depth: Float): Float =
        if (depth.isNaN()) DEPTH_DEFAULT else depth.coerceIn(DEPTH_MIN, DEPTH_MAX)

    /**
     * レイヤーの移動量（ピクセル）を求める。
     *
     * @param tilt 正規化した傾き（-1〜1）
     * @param factor レイヤーごとの移動割合
     * @param direction 移動の向き（1 または -1）
     * @param extent 画面の幅または高さ（ピクセル）
     * @param depth 奥行きの強さ
     */
    fun layerOffset(tilt: Float, factor: Float, direction: Float, extent: Int, depth: Float): Float =
        tilt.coerceIn(-1f, 1f) * factor * direction * extent * clampDepth(depth)

    /**
     * 不透明なレイヤーを動かしても画面端に隙間が出ないよう、画面より大きく描くための拡大率。
     * 奥行きの強さが最大のときでも隙間が出ない値を返す。
     */
    fun overscanScale(factor: Float): Float = 1f + 2f * factor * DEPTH_MAX

    /** 角度（ラジアン）を、最大角度で正規化した -1〜1 の値に変換する。 */
    fun normalizeAngle(angleRad: Float, maxAngleRad: Float): Float {
        if (maxAngleRad <= 0f || angleRad.isNaN()) return 0f
        return (angleRad / maxAngleRad).coerceIn(-1f, 1f)
    }

    /**
     * 呼吸のような明滅の不透明度（0〜1）。余弦波で滑らかに往復させる。
     * 経過時間 0 で最小値、周期の半分で最大値となる。
     */
    fun breathingAlpha(
        elapsedMs: Long,
        periodMs: Long = BREATH_PERIOD_MS,
        minAlpha: Float = BREATH_MIN_ALPHA,
        maxAlpha: Float = BREATH_MAX_ALPHA,
    ): Float {
        if (periodMs <= 0L) return maxAlpha
        val phase = Math.floorMod(elapsedMs, periodMs).toDouble() / periodMs
        val wave = (0.5 - 0.5 * cos(2.0 * PI * phase)).toFloat()
        return minAlpha + (maxAlpha - minAlpha) * wave
    }

    /** 0〜1 の不透明度を Paint 用の 0〜255 に変換する。 */
    fun toPaintAlpha(alpha: Float): Int = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()

    /** スライダーの位置（0〜DEPTH_STEPS）を奥行きの倍率に変換する。 */
    fun progressToDepth(progress: Int): Float {
        val p = progress.coerceIn(0, DEPTH_STEPS)
        return DEPTH_MIN + (DEPTH_MAX - DEPTH_MIN) * p / DEPTH_STEPS
    }

    /** 奥行きの倍率をスライダーの位置に変換する。 */
    fun depthToProgress(depth: Float): Int =
        ((clampDepth(depth) - DEPTH_MIN) / (DEPTH_MAX - DEPTH_MIN) * DEPTH_STEPS).roundToInt()
}
