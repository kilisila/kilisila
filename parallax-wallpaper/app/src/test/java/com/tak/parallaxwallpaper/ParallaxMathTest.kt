package com.tak.parallaxwallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParallaxMathTest {

    private val eps = 1e-4f

    @Test
    fun 各レイヤーの最大移動量は画面に対する指定の割合になる() {
        assertEquals(54f, ParallaxMath.layerOffset(1f, ParallaxMath.BACKGROUND_FACTOR, 1f, 1080, 1f), eps)
        assertEquals(129.6f, ParallaxMath.layerOffset(1f, ParallaxMath.GLOW_FACTOR, 1f, 1080, 1f), eps)
        assertEquals(270f, ParallaxMath.layerOffset(1f, ParallaxMath.FOREGROUND_FACTOR, 1f, 1080, 1f), eps)
    }

    @Test
    fun 前景は背景と逆方向に動く() {
        val bg = ParallaxMath.layerOffset(0.5f, ParallaxMath.BACKGROUND_FACTOR, ParallaxMath.BACKGROUND_DIRECTION, 1000, 1f)
        val fg = ParallaxMath.layerOffset(0.5f, ParallaxMath.FOREGROUND_FACTOR, ParallaxMath.FOREGROUND_DIRECTION, 1000, 1f)
        assertTrue(bg > 0f)
        assertTrue(fg < 0f)
    }

    @Test
    fun 傾きと奥行きは範囲内に丸められる() {
        assertEquals(500f, ParallaxMath.layerOffset(5f, 0.25f, 1f, 1000, 9f), eps)
        assertEquals(-125f, ParallaxMath.layerOffset(-3f, 0.25f, 1f, 1000, 0.1f), eps)
        assertEquals(ParallaxMath.DEPTH_DEFAULT, ParallaxMath.clampDepth(Float.NaN), eps)
    }

    @Test
    fun 背景の拡大率は最大奥行きでも隙間が出ない() {
        val scale = ParallaxMath.overscanScale(ParallaxMath.BACKGROUND_FACTOR)
        val maxShift = ParallaxMath.layerOffset(1f, ParallaxMath.BACKGROUND_FACTOR, 1f, 1000, ParallaxMath.DEPTH_MAX)
        // 片側のはみ出し量が最大移動量以上であること
        assertTrue((scale - 1f) * 1000f / 2f >= maxShift - eps)
    }

    @Test
    fun 明滅は70から100パーセントを4秒周期で往復する() {
        assertEquals(0.70f, ParallaxMath.breathingAlpha(0L), eps)
        assertEquals(1.00f, ParallaxMath.breathingAlpha(2_000L), eps)
        assertEquals(0.85f, ParallaxMath.breathingAlpha(1_000L), eps)
        assertEquals(0.70f, ParallaxMath.breathingAlpha(4_000L), eps)
        assertEquals(ParallaxMath.breathingAlpha(1_234L), ParallaxMath.breathingAlpha(9_234L), eps)
        for (t in 0L..8_000L step 37L) {
            val a = ParallaxMath.breathingAlpha(t)
            assertTrue(a in 0.70f - eps..1.00f + eps)
        }
    }

    @Test
    fun スライダー位置と倍率は相互に変換できる() {
        assertEquals(0.5f, ParallaxMath.progressToDepth(0), eps)
        assertEquals(2.0f, ParallaxMath.progressToDepth(ParallaxMath.DEPTH_STEPS), eps)
        assertEquals(50, ParallaxMath.depthToProgress(1.0f))
        for (p in 0..ParallaxMath.DEPTH_STEPS) {
            assertEquals(p, ParallaxMath.depthToProgress(ParallaxMath.progressToDepth(p)))
        }
    }

    @Test
    fun 角度の正規化() {
        assertEquals(0.5f, ParallaxMath.normalizeAngle(0.1f, 0.2f), eps)
        assertEquals(-1f, ParallaxMath.normalizeAngle(-1f, 0.2f), eps)
        assertEquals(0f, ParallaxMath.normalizeAngle(0.1f, 0f), eps)
    }
}
