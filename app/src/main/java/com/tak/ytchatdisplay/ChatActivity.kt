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

        /**
         * アーカイブのチャットリプレイは、ポップアウト単体でも iframe + embed_domain
         * の自前構成でも YouTube 側が受け付けないことが実機検証で判明した
         * （継続トークンが必要な live_chat_replay エンドポイントを使っており、
         * 動画IDだけからは組み立てられない）。視聴ページ自体を読み込み、
         * チャット欄以外を非表示にする方式に切り替える。
         *
         * 視聴ページのレイアウトは Shadow DOM の中にあることが多く、外側から
         * ID名を決め打ちした <style> では届かないおそれがある。そのため、
         * 実在が確認できている ytd-live-chat-frame と movie_player の2要素を
         * 起点に祖先をたどり、兄弟要素へ直接 inline style を当てる
         * （呼び出し側で var f = REPLAY_ISOLATE_JS; f() として使う関数式）。
         */
        private const val REPLAY_ISOLATE_JS = """
            function(){
              function important(el, props) {
                for (var k in props) { el.style.setProperty(k, props[k], 'important'); }
              }
              var chat = document.querySelector('ytd-live-chat-frame');
              var player = document.getElementById('movie_player');
              if (!chat) return false;

              important(chat, {
                position: 'fixed', top: '0', left: '0', right: '0', bottom: '0',
                width: '100%', height: '100%', 'z-index': '2147483647',
                background: '#0b0b0f'
              });

              var node = chat;
              while (node && node !== document.documentElement) {
                var parent = node.parentElement;
                if (!parent) break;
                Array.prototype.forEach.call(parent.children, function (sibling) {
                  if (sibling === node) return;
                  if (player && sibling.contains(player)) {
                    // 動画プレイヤーを含む枝は display:none にすると
                    // 自動一時停止されるおそれがあるため、極小・透明化に留める
                    important(sibling, {
                      position: 'fixed', top: '0', left: '0',
                      width: '1px', height: '1px', opacity: '0',
                      overflow: 'hidden', 'pointer-events': 'none', 'z-index': '-1'
                    });
                  } else {
                    sibling.style.setProperty('display', 'none', 'important');
                  }
                });
                important(parent, { margin: '0', padding: '0' });
                node = parent;
              }
              document.documentElement.style.setProperty('background', '#0b0b0f', 'important');
              document.body.style.setProperty('background', '#0b0b0f', 'important');
              return true;
            }
        """
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
                binding.loading.visibility = View.GONE
                prefs.addRecent(videoId, cleanTitle(view.title), replayMode)

                if (replayMode) {
                    // 視聴ページを読み込んでいるので、チャット欄以外を隠し、
                    // 動画は消音・非表示のまま自前で操作する
                    setupReplayWatchPage()
                }
                applyCss()
                pageReady = true

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
        if (replayMode) {
            // JS からの seekTo()/playVideo() 呼び出しを自動再生ブロックさせない
            binding.webView.settings.mediaPlaybackRequiresUserGesture = false
            binding.webView.loadUrl("https://www.youtube.com/watch?v=$videoId")
        } else {
            binding.webView.settings.mediaPlaybackRequiresUserGesture = true
            val dark = if (prefs.darkTheme) "&dark_theme=1" else ""
            binding.webView.loadUrl(
                "https://www.youtube.com/live_chat?is_popout=1&v=$videoId$dark"
            )
        }
    }

    /**
     * 視聴ページのうち、チャット欄以外を隠し、動画プレイヤーを消音する。
     * DOM構造の変化に備え、要素が見つかるまで一定間隔で再試行する。
     */
    private fun setupReplayWatchPage() {
        val script = """
            (function(){
              var isolate = $REPLAY_ISOLATE_JS;
              var tries = 0;
              var timer = setInterval(function(){
                tries++;
                var isolated = false;
                try { isolated = isolate(); } catch (e) {}
                var p = document.getElementById('movie_player');
                if (p) {
                  try {
                    if (typeof p.mute === 'function') p.mute();
                    if (typeof p.pauseVideo === 'function') p.pauseVideo();
                  } catch (e) {}
                }
                if ((isolated && p) || tries > 30) clearInterval(timer);
              }, 300);
            })();
        """.trimIndent()
        binding.webView.evaluateJavascript(script, null)
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

        val cssJson = JSONObject.quote(css)

        // ライブはチャットページそのものがトップ文書。アーカイブは視聴ページに
        // 埋め込まれた同一オリジンの iframe の中にチャットがあるため、
        // そちらの文書を探して注入する（読み込みが遅れることがあるので少し待つ）。
        val script = if (replayMode) {
            """
            (function(){
              var tries = 0;
              var timer = setInterval(function(){
                tries++;
                var frame = document.querySelector('ytd-live-chat-frame iframe');
                var doc = frame && frame.contentDocument;
                if (doc && doc.documentElement) {
                  var s = doc.getElementById('ytcd-style');
                  if (!s) { s = doc.createElement('style'); s.id = 'ytcd-style'; doc.documentElement.appendChild(s); }
                  s.textContent = $cssJson;
                  clearInterval(timer);
                }
                if (tries > 20) clearInterval(timer);
              }, 300);
            })();
            """.trimIndent()
        } else {
            """
            (function(){
              var s = document.getElementById('ytcd-style');
              if (!s) { s = document.createElement('style'); s.id = 'ytcd-style'; document.documentElement.appendChild(s); }
              s.textContent = $cssJson;
            })();
            """.trimIndent()
        }

        binding.webView.evaluateJavascript(script, null)
    }

    /**
     * チャットのリプレイ表示は、視聴ページに埋め込まれた本物の動画プレイヤーの
     * 再生位置を見て進む。ここでは仮想再生時計の値に合わせて、消音・非表示に
     * した本物のプレイヤーを直接操作する（毎回シークすると引っかかるため、
     * 大きくずれている時だけ補正する）。
     */
    private fun pushPlayerTime(positionMs: Long, playing: Boolean) {
        if (!replayMode || !pageReady) return

        val seconds = positionMs / 1000.0

        val script = """
            (function(){
              var p = document.getElementById('movie_player');
              if (!p) return;
              try {
                if (typeof p.mute === 'function') p.mute();
                var current = (typeof p.getCurrentTime === 'function') ? p.getCurrentTime() : null;
                if (current === null || Math.abs(current - $seconds) > 1.5) {
                  if (typeof p.seekTo === 'function') p.seekTo($seconds, true);
                }
                if ($playing) {
                  if (typeof p.playVideo === 'function') p.playVideo();
                } else {
                  if (typeof p.pauseVideo === 'function') p.pauseVideo();
                }
              } catch (e) {}
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
        val modeChanged = !initial && enabled != replayMode
        replayMode = enabled
        binding.replayPanel.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.btnReplay.alpha = if (enabled) 1f else 0.45f

        if (enabled) {
            // 未設定なら前回の位置から再開できるようにする
            if (initial || replay.positionMs == 0L) {
                replay.seekTo(prefs.lastPosition(videoId))
            }
            binding.clock.text = TimeText.format(replay.positionMs)
        } else {
            replay.pause()
        }

        if (modeChanged) {
            // ライブはポップアウト直読み、アーカイブは視聴ページ読み込みと構成が
            // 異なるため、切り替え時は読み込み直す
            load()
        } else {
            pushPlayerTime(replay.positionMs, replay.playing)
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
        url.contains("youtube.com/live_chat", ignoreCase = true) ||
            url.contains("youtube.com/watch", ignoreCase = true)

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
