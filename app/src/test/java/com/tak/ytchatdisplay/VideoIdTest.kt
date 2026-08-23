package com.tak.ytchatdisplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoIdTest {

    private val id = "dQw4w9WgXcQ"

    @Test
    fun 各種の共有形式から動画IDを取り出す() {
        assertEquals(id, VideoId.extract("https://www.youtube.com/watch?v=$id"))
        assertEquals(id, VideoId.extract("https://youtu.be/$id?si=abc"))
        assertEquals(id, VideoId.extract("https://www.youtube.com/live/$id?feature=share"))
        assertEquals(id, VideoId.extract("https://m.youtube.com/watch?v=$id&t=90s"))
        assertEquals(id, VideoId.extract("https://www.youtube.com/embed/$id"))
        assertEquals(id, VideoId.extract("https://www.youtube.com/shorts/$id"))
    }

    @Test
    fun 引数が先頭以外にある場合も取り出す() {
        assertEquals(id, VideoId.extract("https://www.youtube.com/watch?app=desktop&v=$id"))
    }

    @Test
    fun 動画IDの直接入力を受け付ける() {
        assertEquals(id, VideoId.extract(id))
    }

    @Test
    fun 共有テキストに紛れたURLも拾う() {
        assertEquals(id, VideoId.extract("この配信みてね\nhttps://youtu.be/$id"))
    }

    @Test
    fun 動画IDを含まない入力は null を返す() {
        assertNull(VideoId.extract("https://www.youtube.com/@usadapekora/live"))
        assertNull(VideoId.extract("ただのテキスト"))
        assertNull(VideoId.extract("   "))
        assertNull(VideoId.extract(null))
    }
}
