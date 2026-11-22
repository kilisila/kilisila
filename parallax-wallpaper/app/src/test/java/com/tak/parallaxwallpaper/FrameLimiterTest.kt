package com.tak.parallaxwallpaper

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameLimiterTest {

    /** 指定した更新頻度の画面で 1 秒間に描画される回数を数える。 */
    private fun drawsPerSecond(displayHz: Double): Int {
        val limiter = FrameLimiter(60)
        val interval = 1_000_000_000.0 / displayHz
        var count = 0
        var i = 0
        while (true) {
            val t = (i * interval).toLong()
            if (t >= 1_000_000_000L) break
            if (limiter.shouldDraw(t)) count++
            i++
        }
        return count
    }

    @Test
    fun 画面60Hzでは毎フレーム描画する() {
        assertEquals(60.0, drawsPerSecond(60.0).toDouble(), 1.0)
    }

    @Test
    fun 画面120Hzでは60回に間引く() {
        assertEquals(60.0, drawsPerSecond(120.0).toDouble(), 1.0)
    }

    @Test
    fun 画面144Hzでも約60回に間引く() {
        assertEquals(60.0, drawsPerSecond(144.0).toDouble(), 2.0)
    }

    @Test
    fun 大きく遅れた後は予定を立て直す() {
        val limiter = FrameLimiter(60)
        limiter.shouldDraw(0L)
        // 1 秒止まった後でも、まとめて描画せず 1 回だけ描画する
        assertEquals(true, limiter.shouldDraw(1_000_000_000L))
        assertEquals(false, limiter.shouldDraw(1_000_000_000L + 8_000_000L))
    }
}
