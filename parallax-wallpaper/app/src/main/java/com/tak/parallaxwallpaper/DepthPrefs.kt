package com.tak.parallaxwallpaper

import android.content.Context
import android.content.SharedPreferences

/** 奥行きの強さを保存・読み出しする。 */
class DepthPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var depth: Float
        get() = ParallaxMath.clampDepth(prefs.getFloat(KEY_DEPTH, ParallaxMath.DEPTH_DEFAULT))
        set(value) {
            prefs.edit().putFloat(KEY_DEPTH, ParallaxMath.clampDepth(value)).apply()
        }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        private const val FILE_NAME = "parallax_settings"
        const val KEY_DEPTH = "depth"
    }
}
