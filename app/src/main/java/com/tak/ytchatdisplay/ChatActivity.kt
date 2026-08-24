package com.tak.ytchatdisplay

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tak.ytchatdisplay.databinding.ActivityChatBinding
import org.json.JSONObject

/**
 * YouTube のポップアウトチャットを全画面で表示する。
 *
 * ライブ配信では YouTube 側が自動で更新するため、表示調整のみを行う。
 * アーカイブでは再生位置に連動する仕組みが必要なため、仮想再生時計の値を
 * チャット側へ送り込むことで表示を進める。
 */
class ChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_ID = "video_id"
        const val EXTRA_REPLAY = "replay"

        private const val MIN_ZOOM = 70
        private const val MAX_ZOOM = 220
        private const val ZOOM_STEP = 15

        private const val NUDGE_SMALL_MS = 10_000L
        private const val NUDGE_LARGE_MS = 60_000L

        /**
         * 携帯端末の利用者エージェント文字列だと簡易版へ誘導されることがあるため、
         * デスクトップ相当を名乗ってポップアウトチャットを取得する。
         */
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/125.0.0.0 Safari/537.36"
    }

    private lateinit var binding: ActivityChatBinding
    private lateinit var prefs: Prefs
    private lateinit var replay: ReplaySync

    private var videoId: String = ""
    private var replayMode: Boolean = false
    private var pageReady: Boolean = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        videoId = intent.getStringExtra(EXTRA_VIDEO_ID).orEmpty()
        replayMode = intent.getBooleanExtra(EXTRA_REPLAY, false)

        if (videoId.isBlank()) {
            Toast.makeText(this, R.string.err_no_id, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // 手元で眺め続ける用途なので、表示中は画面を消灯させない
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.root.keepScreenOn = true

        replay = ReplaySync { positionMs, playing ->
            pushPlayerTime(positionMs, playing)
            binding.clock.text = TimeText.format(positionMs)
            binding.btnPlayPause.text =
                getString(if (playing) R.string.symbol_pause else R.string.symbol_play)
        }

        setupWebView()
        setupBar()
        setupReplayPanel()

        applyReplayMode(replayMode, initial = true)
        load()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) binding.webView.goBack() else finish()
            }
        })
    }

    // ---------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(binding.webView, true)

        with(binding.webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = DESKTOP_UA
            // ポップアウトチャットは元々細い作りなので、端末幅でそのまま組ませる
            useWideViewPort = false
            loadWithOverviewMode = false
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = prefs.textZoom
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(true)
        }

        binding.webView.setBackgroundColor(0xFF0B0B0F.toInt())
        binding.webView.isVerticalScrollBarEnabled = false

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                return if (isChatUrl(url)) {
                    false
                } else {
                    openExternally(url)
                    true
                }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                pageReady = false
                binding.loading.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                binding.loading.visibility = View.GONE
                applyCss()
                prefs.addRecent(videoId, cleanTitle(view.title), replayMode)

                if (replayMode) {
                    // 読み込み直後に現在位置を一度伝えて、表示を合わせる
                    pushPlayerTime(replay.positionMs, replay.playing)
                }
            }
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            // チャット内のリンクは新規ウィンドウ扱いになるため、外部ブラウザへ渡す
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message
            ): Boolean {
                val temp = WebView(view.context)
                temp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        openExternally(request.url.toString())
                        view.destroy()
                        return true
                    }
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = temp
                resultMsg.sendToTarget()
                return true
            }
        }
    }

    private fun load() {
        val dark = if (prefs.darkTheme) "&dark_theme=1" else ""
        binding.webView.loadUrl(
            "https://www.youtube.com/live_chat?is_popout=1&v=$videoId$dark"
        )
    }

    /**
     * 読みやすさのための追加スタイルを流し込む。
     * 該当する要素が無い場合は何も起きないため、YouTube 側の変更でも壊れにくい。
     */
    private fun applyCss() {
        val css = buildString {
            append("yt-live-chat-text-message-renderer{padding-top:3px !important;padding-bottom:3px !important;}")
            append("yt-live-chat-item-list-renderer #items{padding-bottom:96px !important;}")
            append("::-webkit-scrollbar{width:0px;}")
            if (prefs.compact) {
                append("yt-live-chat-header-renderer{display:none !important;}")
                append("yt-live-chat-message-input-renderer{display:none !important;}")
            }
        }

        val script = "(function(){" +
            "var s=document.getElementById('ytcd-style');" +
            "if(!s){s=document.createElement('style');s.id='ytcd-style';" +
            "document.documentElement.appendChild(s);}" +
            "s.textContent=" + JSONObject.quote(css) + ";" +
            "})();"

        binding.webView.evaluateJavascript(script, null)
    }

    /**
     * チャットのリプレイ表示は、動画プレイヤーから送られる再生位置の通知を見て進む。
     * ここでは同じ形式の通知を自前で送り、テレビ側の再生位置に合わせて表示させる。
     */
    private fun pushPlayerTime(positionMs: Long, playing: Boolean) {
        if (!replayMode || !pageReady) return

        val seconds = positionMs / 1000.0
        val state = if (playing) 1 else 2

        val script = """
            (function(){
              var payload = {"event":"infoDelivery",
                             "info":{"currentTime":$seconds,"playerState":$state},
                             "id":1};
              try { window.postMessage(JSON.stringify(payload), "*"); } catch (e) {}
              try { window.postMessage(payload, "*"); } catch (e) {}
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(script, null)
    }

    // -------------------------------------------------------------------- バー

    private fun setupBar() {
        binding.btnZoomOut.setOnClickListener { changeZoom(-ZOOM_STEP) }
        binding.btnZoomIn.setOnClickListener { changeZoom(ZOOM_STEP) }

        binding.btnReload.setOnClickListener { load() }

        binding.btnDark.setOnClickListener {
            prefs.darkTheme = !prefs.darkTheme
            load()
        }

        binding.btnCompact.setOnClickListener {
            prefs.compact = !prefs.compact
            applyCss()
            toast(
                getString(
                    if (prefs.compact) R.string.msg_compact_on else R.string.msg_compact_off
                )
            )
        }

        binding.btnReplay.setOnClickListener { applyReplayMode(!replayMode, initial = false) }

        binding.btnBrowser.setOnClickListener {
            openExternally("https://www.youtube.com/watch?v=$videoId")
        }

        binding.btnHideBar.setOnClickListener { showControls(false) }
        binding.grip.setOnClickListener { showControls(true) }
    }

    private fun changeZoom(delta: Int) {
        val next = (prefs.textZoom + delta).coerceIn(MIN_ZOOM, MAX_ZOOM)
        prefs.textZoom = next
        binding.webView.settings.textZoom = next
        toast(getString(R.string.msg_zoom, next))
    }

    private fun showControls(visible: Boolean) {
        binding.controls.visibility = if (visible) View.VISIBLE else View.GONE
        binding.grip.visibility = if (visible) View.GONE else View.VISIBLE
        applyImmersiveMode(!visible)
    }

    private fun applyImmersiveMode(on: Boolean) {
        val controller = WindowCompat.getInsetsController(window, binding.root)
        if (on) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ------------------------------------------------------- アーカイブ操作盤

    private fun setupReplayPanel() {
        binding.btnPlayPause.setOnClickListener { replay.toggle() }

        binding.btnBackLarge.setOnClickListener { replay.nudge(-NUDGE_LARGE_MS) }
        binding.btnBackSmall.setOnClickListener { replay.nudge(-NUDGE_SMALL_MS) }
        binding.btnFwdSmall.setOnClickListener { replay.nudge(NUDGE_SMALL_MS) }
        binding.btnFwdLarge.setOnClickListener { replay.nudge(NUDGE_LARGE_MS) }

        binding.btnSetTime.setOnClickListener { askForTime() }
        binding.clock.setOnClickListener { askForTime() }
    }

    private fun applyReplayMode(enabled: Boolean, initial: Boolean) {
        replayMode = enabled
        binding.replayPanel.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.btnReplay.alpha = if (enabled) 1f else 0.45f

        if (enabled) {
            // 未設定なら前回の位置から再開できるようにする
            if (initial || replay.positionMs == 0L) {
                replay.seekTo(prefs.lastPosition(videoId))
            }
            binding.clock.text = TimeText.format(replay.positionMs)
            pushPlayerTime(replay.positionMs, replay.playing)
        } else {
            replay.pause()
        }

        if (!initial) {
            toast(
                getString(
                    if (enabled) R.string.msg_replay_on else R.string.msg_replay_off
                )
            )
        }
    }

    private fun askForTime() {
        val wasPlaying = replay.playing
        replay.pause()

        val padding = (20 * resources.displayMetrics.density).toInt()
        val field = EditText(this).apply {
            setText(TimeText.format(replay.positionMs))
            hint = getString(R.string.time_hint)
            inputType = EditorInfo.TYPE_CLASS_TEXT
            setSelectAllOnFocus(true)
        }
        val container = FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(field)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.time_dialog_title)
            .setMessage(R.string.time_dialog_message)
            .setView(container)
            .setNegativeButton(R.string.btn_cancel) { _, _ ->
                if (wasPlaying) replay.play()
            }
            .setPositiveButton(R.string.btn_apply) { _, _ ->
                val parsed = TimeText.parse(field.text.toString())
                if (parsed == null) {
                    toast(getString(R.string.err_bad_time))
                } else {
                    replay.seekTo(parsed)
                }
                replay.play()
            }
            .show()
    }

    // ------------------------------------------------------------------ 補助

    private fun isChatUrl(url: String): Boolean =
        url.contains("youtube.com/live_chat", ignoreCase = true)

    private fun openExternally(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            toast(getString(R.string.err_no_browser))
        }
    }

    private fun cleanTitle(raw: String?): String =
        raw.orEmpty().removeSuffix(" - YouTube").trim()

    private fun toast(text: String) =
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------ ライフサイクル

    override fun onPause() {
        super.onPause()
        binding.webView.onPause()
        if (replayMode) prefs.setLastPosition(videoId, replay.positionMs)
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
    }

    override fun onDestroy() {
        replay.release()
        binding.webView.destroy()
        super.onDestroy()
    }
}
