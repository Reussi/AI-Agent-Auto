package app.iaagentauto.widget

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.iaagentauto.Conversation
import app.iaagentauto.R
import app.iaagentauto.Settings
import app.iaagentauto.Speaker
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Kleines Dialogfenster, das vom Widget geöffnet wird: Spracheingabe → Claude → Antwort
 * wird angezeigt und vorgelesen. Nutzt die Spracheingabe des Systems (Google), daher ist
 * keine eigene Mikrofon-Berechtigung nötig.
 */
class VoiceActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var question: TextView
    private lateinit var answer: TextView
    private lateinit var again: Button
    private lateinit var speaker: Speaker
    private var busy = false

    private val recognize =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val text = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (result.resultCode != RESULT_OK || text.isNullOrBlank()) {
                // Abgebrochen oder nichts erkannt: Fenster nur offen lassen, wenn schon etwas da ist.
                if (answer.text.isNullOrBlank()) finish() else status.text = "Nichts erkannt."
                return@registerForActivityResult
            }
            ask(text)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice)
        setFinishOnTouchOutside(true)

        status = findViewById(R.id.voiceStatus)
        question = findViewById(R.id.voiceQuestion)
        answer = findViewById(R.id.voiceAnswer)
        answer.movementMethod = ScrollingMovementMethod()
        again = findViewById(R.id.voiceAgain)
        speaker = Speaker(this)

        findViewById<Button>(R.id.voiceClose).setOnClickListener { finish() }
        again.setOnClickListener { speaker.stop(); listen() }

        if (Settings.apiKey(this).isEmpty()) {
            status.text = "Kein API-Schlüssel"
            answer.text = "Bitte öffne zuerst die App „IA Agent Auto“ und trage deinen API-Schlüssel ein."
            again.visibility = View.GONE
            return
        }
        if (savedInstanceState == null) listen()
    }

    private fun listen() {
        if (busy) return
        status.text = "Ich höre zu …"
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Frag Claude …")
        }
        try {
            recognize.launch(intent)
        } catch (_: ActivityNotFoundException) {
            status.text = "Keine Spracheingabe gefunden"
            answer.text = "Auf diesem Handy ist keine Spracheingabe installiert (z. B. die Google-App)."
        }
    }

    private fun ask(text: String) {
        busy = true
        again.isEnabled = false
        question.text = "„$text“"
        answer.text = ""
        status.text = "Claude denkt nach …"
        lifecycleScope.launch {
            val turn = Conversation.ask(applicationContext, text)
            busy = false
            again.isEnabled = true
            status.text = if (turn.isError) "Fehler" else "Claude"
            answer.text = turn.text
            speaker.speak(turn.text) {
                if (!turn.isError && Settings.autoListen(this@VoiceActivity) && !isFinishing) listen()
            }
        }
    }

    override fun onDestroy() {
        speaker.shutdown()
        super.onDestroy()
    }
}
