package com.tak.parallaxwallpaper

import android.app.Activity
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/**
 * 奥行きの強さ（0.5〜2 倍）を調整する設定画面。
 * 壁紙の選択画面の「設定」からも、ランチャーのアイコンからも開ける。
 */
class SettingsActivity : Activity() {

    private lateinit var depthPrefs: DepthPrefs
    private lateinit var valueText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        depthPrefs = DepthPrefs(this)
        valueText = findViewById(R.id.depth_value)
        val seekBar: SeekBar = findViewById(R.id.depth_seekbar)
        val applyButton: Button = findViewById(R.id.set_wallpaper_button)

        seekBar.max = ParallaxMath.DEPTH_STEPS
        seekBar.progress = ParallaxMath.depthToProgress(depthPrefs.depth)
        showDepth(depthPrefs.depth)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                val depth = ParallaxMath.progressToDepth(progress)
                showDepth(depth)
                // 保存すると壁紙側が変更通知を受け取り、即座に反映される
                if (fromUser) depthPrefs.depth = depth
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })

        applyButton.setOnClickListener { openWallpaperPicker() }
    }

    private fun showDepth(depth: Float) {
        valueText.text = getString(R.string.depth_value_format, depth)
    }

    /** このライブ壁紙のプレビュー画面を開く。開けない端末では壁紙の一覧を開く。 */
    private fun openWallpaperPicker() {
        val direct = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(this, ParallaxWallpaperService::class.java),
        )
        try {
            startActivity(direct)
            return
        } catch (_: ActivityNotFoundException) {
            // 次の手段を試す
        }
        try {
            startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.picker_not_found, Toast.LENGTH_LONG).show()
        }
    }
}
