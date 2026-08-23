package com.tak.ytchatdisplay

/**
 * 各種 YouTube URL から動画IDを取り出すユーティリティ。
 */
object VideoId {

    private val ID_ONLY = Regex("""^[A-Za-z0-9_-]{11}$""")

    private val PATTERNS = listOf(
        Regex("""[?&]v=([A-Za-z0-9_-]{11})"""),
        Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
        Regex("""youtube\.com/live/([A-Za-z0-9_-]{11})"""),
        Regex("""youtube\.com/embed/([A-Za-z0-9_-]{11})"""),
        Regex("""youtube\.com/shorts/([A-Za-z0-9_-]{11})""")
    )

    /**
     * 入力文字列から動画IDを抽出する。見つからない場合は null。
     * URL のほか、11文字のID直接入力、共有テキストに紛れたURLにも対応する。
     */
    fun extract(input: String?): String? {
        val s = input?.trim().orEmpty()
        if (s.isEmpty()) return null

        if (ID_ONLY.matches(s)) return s

        for (p in PATTERNS) {
            val m = p.find(s)
            if (m != null) return m.groupValues[1]
        }
        return null
    }
}
