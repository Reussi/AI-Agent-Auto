package app.iaagentauto

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import app.iaagentauto.messaging.ClaudeNotifier
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    /** Auswahl im Modell-Menü. */
    private data class ModelOption(val id: String, val label: String)

    private val models = listOf(
        ModelOption("claude-opus-5-5", "Claude Opus 5.5 · beste Antworten"),
        ModelOption("claude-sonnet-5-5", "Claude Sonnet 5.5 · ausgewogen"),
        ModelOption("claude-haiku-5-5", "Claude Haiku 5.5 · am schnellsten"),
    )

    private lateinit var root: View
    private lateinit var statusChip: Chip
    private lateinit var keyMasked: TextView
    private lateinit var chat: LinearLayout
    private lateinit var chatEmpty: View
    private lateinit var chatProgress: View
    private lateinit var question: TextInputEditText
    private lateinit var questionLayout: TextInputLayout
    private lateinit var voiceButton: MaterialButton

    private val speechLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (result.resultCode == RESULT_OK && !text.isNullOrBlank()) send(text)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Settings.apiKey(this).isEmpty()) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.scroll)
        applyInsets()

        setupChat()
        findViewById<MaterialButton>(R.id.openHelp).setOnClickListener {
            startActivity(Intent(this, HelpActivity::class.java))
        }
        // Sprachbefehl „Spiele … auf IA Agent Auto“ am Handy: als Frage stellen.
        if (intent?.action == android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH &&
            savedInstanceState == null
        ) {
            intent.getStringExtra(android.app.SearchManager.QUERY)?.takeIf { it.isNotBlank() }?.let { send(it) }
        }
        findViewById<MaterialButton>(R.id.openSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }


    }

    /** Inhalte nicht unter Status- und Navigationsleiste zeichnen (Android 15 „Edge-to-Edge“). */
    private fun applyInsets() {
        val appBar = findViewById<AppBarLayout>(R.id.appBar)
        val content = findViewById<View>(R.id.content)
        val basePadding = content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            appBar.updatePadding(top = bars.top, left = bars.left, right = bars.right)
            root.updatePadding(left = bars.left, right = bars.right)
            content.updatePadding(bottom = basePadding + maxOf(bars.bottom, ime.bottom))
            insets
        }
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- Anleitung

    // ---------------------------------------------------------------- Gespräch

    private fun setupChat() {
        chat = findViewById(R.id.chat)
        chatEmpty = findViewById(R.id.chatEmpty)
        chatProgress = findViewById(R.id.chatProgress)
        question = findViewById(R.id.question)
        questionLayout = findViewById(R.id.questionLayout)
        voiceButton = findViewById(R.id.voice)

        questionLayout.setEndIconOnClickListener { sendTyped() }
        question.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false
        }
        voiceButton.setOnClickListener {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Frag Claude …")
            }
            try { speechLauncher.launch(intent) } catch (_: ActivityNotFoundException) {
                Snackbar.make(root, "Keine Spracheingabe auf diesem Handy gefunden.", Snackbar.LENGTH_LONG).show()
            }
        }
        findViewById<MaterialButton>(R.id.export).setOnClickListener {
            val text = Conversation.transcript()
            if (text.isEmpty()) {
                Snackbar.make(root, "Noch kein Gespräch zum Übernehmen.", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!ClaudeExport.isClaudeInstalled(this)) {
                Snackbar.make(root, "Claude-App nicht gefunden – wähle, wohin das Gespräch soll.", Snackbar.LENGTH_LONG).show()
            }
            ClaudeExport.markExported(this)
            startActivity(ClaudeExport.intent(this, text))
        }
        findViewById<MaterialButton>(R.id.reset).setOnClickListener {
            Conversation.reset()
            ClaudeNotifier.cancel(this)
        }

        lifecycleScope.launch { Conversation.turns.collect { renderChat(it) } }
        lifecycleScope.launch {
            Conversation.busy.collect { busy ->
                chatProgress.isVisible = busy
                questionLayout.isEndIconVisible = !busy
            }
        }
    }

    private fun sendTyped() {
        val text = question.text?.toString().orEmpty()
        if (text.isBlank() || Conversation.busy.value) return
        question.setText("")
        hideKeyboard()
        send(text)
    }

    private fun send(text: String) {
        lifecycleScope.launch { Conversation.ask(applicationContext, text) }
    }

    private fun renderChat(turns: List<Conversation.Turn>) {
        chat.removeAllViews()
        chatEmpty.isVisible = turns.isEmpty()
        val dp = resources.displayMetrics.density
        turns.takeLast(20).forEach { t ->
            val bubble = TextView(this).apply {
                text = t.text
                setTextIsSelectable(true)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                setTextColor(ContextCompat.getColor(context,
                    when {
                        t.isError -> R.color.error
                        t.fromUser -> R.color.on_primary_container
                        else -> R.color.on_surface
                    }))
                setBackgroundResource(if (t.fromUser) R.drawable.bubble_user else R.drawable.bubble_claude)
                setPadding((14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt())
                maxWidth = (resources.displayMetrics.widthPixels * 0.72).toInt()
            }
            chat.addView(bubble, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = if (t.fromUser) Gravity.END else Gravity.START
                topMargin = (8 * dp).toInt()
            })
        }
    }

    // ---------------------------------------------------------------- Hilfen

    private fun hideKeyboard() {
        currentFocus?.clearFocus()
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(root.windowToken, 0)
    }

    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: ActivityNotFoundException) {}
    }

    private fun openAppSettings() {
        startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }
}
