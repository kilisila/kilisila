package com.tak.ytchatdisplay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 履歴の1件分。 */
data class Recent(
    val id: String,
    val title: String,
    val at: Long,
    val replay: Boolean
)

/**
 * 設定値と視聴履歴の保存先。
 */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("ytcd", Context.MODE_PRIVATE)

    /** 文字の拡大率（パーセント）。 */
    var textZoom: Int
        get() = sp.getInt("textZoom", 115)
        set(value) = sp.edit().putInt("textZoom", value).apply()

    /** ダークテーマでチャットを表示するか。 */
    var darkTheme: Boolean
        get() = sp.getBoolean("darkTheme", true)
        set(value) = sp.edit().putBoolean("darkTheme", value).apply()

    /** ヘッダーと入力欄を隠して表示領域を広げるか。 */
    var compact: Boolean
        get() = sp.getBoolean("compact", false)
        set(value) = sp.edit().putBoolean("compact", value).apply()

    /** アーカイブの再生位置を動画ごとに覚えておく。 */
    fun lastPosition(videoId: String): Long = sp.getLong("pos_$videoId", 0L)

    fun setLastPosition(videoId: String, ms: Long) =
        sp.edit().putLong("pos_$videoId", ms).apply()

    fun recents(): List<Recent> {
        val raw = sp.getString("recents", "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Recent(
                    id = o.getString("id"),
                    title = o.optString("title"),
                    at = o.optLong("at"),
                    replay = o.optBoolean("replay", false)
                )
            }
        }.getOrDefault(emptyList())
    }

    fun addRecent(id: String, title: String, replay: Boolean) {
        val current = recents()
        val previous = current.firstOrNull { it.id == id }
        val finalTitle = if (title.isNotBlank()) title else previous?.title.orEmpty()

        val updated = mutableListOf(Recent(id, finalTitle, System.currentTimeMillis(), replay))
        updated.addAll(current.filter { it.id != id })

        val array = JSONArray()
        updated.take(20).forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("title", it.title)
                    .put("at", it.at)
                    .put("replay", it.replay)
            )
        }
        sp.edit().putString("recents", array.toString()).apply()
    }

    fun removeRecent(id: String) {
        val array = JSONArray()
        recents().filter { it.id != id }.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("title", it.title)
                    .put("at", it.at)
                    .put("replay", it.replay)
            )
        }
        sp.edit()
            .putString("recents", array.toString())
            .remove("pos_$id")
            .apply()
    }

    fun clearRecents() = sp.edit().remove("recents").apply()
}
