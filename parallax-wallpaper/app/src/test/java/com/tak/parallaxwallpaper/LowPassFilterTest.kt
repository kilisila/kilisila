package com.tak.parallaxwallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

class LowPassFilterTest {

    @Test
    fun 最初の入力はそのまま採用される() {
        val f = LowPassFilter(0.1f)
        assertEquals(3f, f.update(3f, 0.016f), 1e-6f)
    }

    @Test
    fun 時定数だけ経過すると約63パーセント追従する() {
        val f = LowPassFilter(0.1f)
        f.reset(0f)
        assertEquals(1f - exp(-1f), f.update(1f, 0.1f), 1e-5f)
    }

    @Test
    fun 更新間隔が異なっても同じ時間で同じ値になる() {
        val a = LowPassFilter(0.12f).apply { reset(0f) }
        val b = LowPassFilter(0.12f).apply { reset(0f) }
        repeat(60) { a.update(1f, 1f / 60f) }
        repeat(144) { b.update(1f, 1f / 144f) }
        assertEquals(a.value, b.value, 1e-4f)
    }

    @Test
    fun 急な変化を滑らかにし目標を超えない() {
        val f = LowPassFilter(0.12f).apply { reset(0f) }
        var prev = 0f
        repeat(120) {
            val v = f.update(1f, 1f / 60f)
            assertTrue(v >= prev && v <= 1f)
            prev = v
        }
        assertTrue(prev > 0.99f)
    }

    @Test
    fun 経過時間0では値が変わらない() {
        val f = LowPassFilter(0.1f).apply { reset(2f) }
        assertEquals(2f, f.update(10f, 0f), 1e-6f)
    }
}
