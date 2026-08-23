package com.tak.ytchatdisplay

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tak.ytchatdisplay.databinding.ActivityMainBinding
import com.tak.ytchatdisplay.databinding.ItemRecentBinding

/**
 * 配信の指定画面。共有インテント、貼り付け、履歴からチャット画面を開く。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        binding.modeGroup.check(R.id.modeLive)

        binding.btnOpen.setOnClickListener {
            openFromText(binding.input.text.toString(), selectedReplayMode())
        }

        binding.input.setOnEditorActionListener { _, actionId, _ ->
            // 同じ操作で二重に開かないよう、実行系の操作だけを拾う
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                openFromText(binding.input.text.toString(), selectedReplayMode())
                true
            } else {
                false
            }
        }

        binding.btnPaste.setOnClickListener {
            val text = readClipboard()
            if (text.isBlank()) {
                toast(getString(R.string.err_clipboard_empty))
            } else {
                binding.input.setText(text)
                openFromText(text, selectedReplayMode())
            }
        }

        binding.btnClearRecents.setOnClickListener {
            prefs.clearRecents()
            renderRecents()
        }

        // 画面回転で再生成されたときに、共有インテントを二重処理しない
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        this.intent = intent
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        renderRecents()
    }

    private fun selectedReplayMode(): Boolean =
        binding.modeGroup.checkedButtonId == R.id.modeReplay

    /** 共有インテントで飛んできた場合は、そのままチャット画面へ進む。 */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        if (intent.type != "text/plain") return

        val shared = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val id = VideoId.extract(shared)

        if (id != null) {
            // 共有元がライブかアーカイブかは判別できないため、
            // 過去に同じ配信を開いていればその設定を引き継ぐ
            val known = prefs.recents().firstOrNull { it.id == id }
            open(id, known?.replay ?: false)
        } else {
            binding.input.setText(shared)
            toast(getString(R.string.err_no_id))
        }
    }

    private fun openFromText(text: String, replay: Boolean) {
        val id = VideoId.extract(text)
        if (id == null) {
            toast(getString(R.string.err_no_id))
            return
        }
        open(id, replay)
    }

    private fun open(id: String, replay: Boolean) {
        startActivity(
            Intent(this, ChatActivity::class.java)
                .putExtra(ChatActivity.EXTRA_VIDEO_ID, id)
                .putExtra(ChatActivity.EXTRA_REPLAY, replay)
        )
    }

    private fun renderRecents() {
        val list: LinearLayout = binding.recentList
        list.removeAllViews()

        val recents = prefs.recents()
        binding.recentEmpty.visibility = if (recents.isEmpty()) View.VISIBLE else View.GONE
        binding.btnClearRecents.visibility = if (recents.isEmpty()) View.GONE else View.VISIBLE

        recents.forEach { recent ->
            val row = ItemRecentBinding.inflate(layoutInflater, list, false)

            row.title.text = recent.title.ifBlank { recent.id }
            row.badge.setText(if (recent.replay) R.string.badge_replay else R.string.badge_live)
            row.subtitle.text = getString(
                R.string.recent_subtitle,
                recent.id,
                relativeTime(recent.at)
            )

            row.root.setOnClickListener { open(recent.id, recent.replay) }
            row.btnRemove.setOnClickListener {
                prefs.removeRecent(recent.id)
                renderRecents()
            }

            list.addView(row.root)
        }
    }

    private fun relativeTime(at: Long): CharSequence {
        if (at <= 0L) return ""
        return DateUtils.getRelativeTimeSpanString(
            at,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS
        )
    }

    private fun readClipboard(): String {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return ""
        if (clip.itemCount == 0) return ""
        return clip.getItemAt(0).coerceToText(this).toString()
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
