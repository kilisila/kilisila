package com.tak.ytchatdisplay

import java.util.Locale

/**
 * 再生位置の表示と入力解釈。
 */
object TimeText {

    /** ミリ秒を hh:mm:ss 形式にする。 */
    fun format(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0L)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    /**
     * 利用者の入力をミリ秒に変換する。
     * "90"（秒）、"12:34"（分:秒）、"1:02:03"（時:分:秒）に対応する。
     * 解釈できない場合は null。
     */
    fun parse(input: String): Long? {
        val text = input.trim().replace('：', ':')
        if (text.isEmpty()) return null

        val parts = text.split(":")
        if (parts.size > 3) return null

        return runCatching {
            val numbers = parts.map { it.trim().toLong() }
            if (numbers.any { it < 0 }) return null
            when (numbers.size) {
                1 -> numbers[0] * 1000
                2 -> (numbers[0] * 60 + numbers[1]) * 1000
                3 -> (numbers[0] * 3600 + numbers[1] * 60 + numbers[2]) * 1000
                else -> null
            }
        }.getOrNull()
    }
}
