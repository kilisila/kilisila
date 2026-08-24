package com.tak.ytchatdisplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeTextTest {

    @Test
    fun 秒だけの入力を解釈する() {
        assertEquals(90_000L, TimeText.parse("90"))
        assertEquals(0L, TimeText.parse("0"))
    }

    @Test
    fun 分秒と時分秒を解釈する() {
        assertEquals(754_000L, TimeText.parse("12:34"))
        assertEquals(3_723_000L, TimeText.parse("1:02:03"))
        assertEquals(359_999_000L, TimeText.parse("99:59:59"))
    }

    @Test
    fun 全角コロンと前後の空白を許容する() {
        assertEquals(5_025_000L, TimeText.parse("1：23：45"))
        assertEquals(754_000L, TimeText.parse("  12:34  "))
    }

    @Test
    fun `不正な入力は null を返す`() {
        assertNull(TimeText.parse(""))
        assertNull(TimeText.parse("abc"))
        assertNull(TimeText.parse("12:xx"))
        assertNull(TimeText.parse("1:2:3:4"))
        assertNull(TimeText.parse("-5"))
    }

    @Test
    fun 表示は常に二桁区切りになる() {
        assertEquals("01:23:45", TimeText.format(5_025_000L))
        assertEquals("00:00:00", TimeText.format(0L))
        assertEquals("00:00:59", TimeText.format(59_999L))
    }

    @Test
    fun 負の値は先頭で止める() {
        assertEquals("00:00:00", TimeText.format(-100L))
    }

    @Test
    fun 表示と解釈は往復しても一致する() {
        listOf(0L, 1_000L, 60_000L, 3_599_000L, 3_600_000L, 5_025_000L, 359_999_000L)
            .forEach { assertEquals(it, TimeText.parse(TimeText.format(it))) }
    }
}
