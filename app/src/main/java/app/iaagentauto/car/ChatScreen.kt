package app.iaagentauto.car

import android.Manifest
import android.content.pm.PackageManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.iaagentauto.Conversation
import app.iaagentauto.R
import app.iaagentauto.Settings
import app.iaagentauto.Speaker
import kotlinx.coroutines.launch

/**
 * Der Bildschirm im Auto: „Sprechen“ drücken, Frage stellen, Claude antwortet per Sprachausgabe.
 *
 * Die Zeilentitel bleiben immer gleich, nur deren Text ändert sich – so zählt Android Auto
 * die Aktualisierungen als „Refresh“ und nicht als neue Schritte (sonst greift das Schrittlimit).
 */
class ChatScreen(carContext: CarContext) : Screen(carContext) {

    private enum class State { IDLE, LISTENING, THINKING, SPEAKING }

    private var state = State.IDLE
    private var status = "Tippe auf „Sprechen“ und stell deine Frage."
    private var partial = ""

    private val speaker = Speaker(carContext)
    private val voice = VoiceInput(
        carContext,
        onPartial = { partial = it; invalidate() },
        onResult = { text -> partial = ""; send(text) },
        onError = { msg -> partial = ""; setState(State.IDLE, msg) },
    )

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // Beim Öffnen im Auto direkt zuhören – einfach losreden, ohne zu tippen.
                if (Settings.apiKey(carContext).isNotEmpty() && Settings.autoListen(carContext) &&
                    state == State.IDLE
                ) {
                    startListening()
                }
            }

            override fun onDestroy(owner: LifecycleOwner) {
                voice.stop()
                speaker.shutdown()
            }

            override fun onStop(owner: LifecycleOwner) {
                // App im Auto verlassen: nicht weiter zuhören oder sprechen.
                voice.stop()
                speaker.stop()
                if (state != State.THINKING) setState(State.IDLE, "Tippe auf „Sprechen“ und stell deine Frage.")
            }
        })
        // Änderungen aus dem Messaging-Modus oder der Handy-App übernehmen.
        lifecycleScope.launch { Conversation.turns.collect { invalidate() } }

        if (Settings.apiKey(carContext).isEmpty()) {
            status = "Kein API-Schlüssel. Bitte im Stand in der App auf dem Handy eintragen."
        }
    }

    private fun setState(s: State, msg: String) {
        state = s
        status = msg
        invalidate()
    }

    private fun onTalkPressed() {
        when (state) {
            State.LISTENING -> { voice.stop(); setState(State.IDLE, "Zuhören abgebrochen.") }
            State.THINKING -> Unit // Antwort kommt gleich
            State.SPEAKING, State.IDLE -> {
                speaker.stop()
                startListening()
            }
        }
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(carContext, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            carContext.requestPermissions(listOf(Manifest.permission.RECORD_AUDIO)) { granted, _ ->
                if (Manifest.permission.RECORD_AUDIO in granted) startListening()
                else setState(State.IDLE, "Ohne Mikrofon-Berechtigung kann ich nicht zuhören.")
            }
            return
        }
        partial = ""
        setState(State.LISTENING, "Ich höre zu …")
        voice.start()
    }

    private fun send(text: String) {
        setState(State.THINKING, "Claude denkt nach …")
        lifecycleScope.launch {
            val answer = Conversation.ask(carContext, text)
            setState(State.SPEAKING, if (answer.isError) "Fehler" else "Claude spricht …")
            speaker.speak(answer.text) {
                if (!answer.isError && Settings.autoListen(carContext)) {
                    startListening()
                } else {
                    setState(State.IDLE, "Tippe auf „Sprechen“ für die nächste Frage.")
                }
            }
        }
    }

    private fun onNewConversation() {
        voice.stop()
        speaker.stop()
        Conversation.reset()
        setState(State.IDLE, "Neues Gespräch. Tippe auf „Sprechen“.")
    }

    override fun onGetTemplate(): Template {
        val turns = Conversation.turns.value
        val lastUser = turns.lastOrNull { it.fromUser }?.text
        val lastClaude = turns.lastOrNull { !it.fromUser }?.text

        val youText = when {
            state == State.LISTENING && partial.isNotBlank() -> partial
            lastUser != null -> lastUser
            else -> "–"
        }

        val pane = Pane.Builder()
            .addRow(Row.Builder().setTitle("Status").addText(status).build())
            .addRow(Row.Builder().setTitle("Du").addText(youText).build())
            .addRow(Row.Builder().setTitle("Claude").addText(lastClaude ?: "–").build())
            .addAction(
                Action.Builder()
                    .setTitle("Sprechen")
                    .setIcon(icon(R.drawable.ic_mic))
                    .setBackgroundColor(CarColor.PRIMARY)
                    .setOnClickListener { onTalkPressed() }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("Neu")
                    .setIcon(icon(R.drawable.ic_refresh))
                    .setOnClickListener { onNewConversation() }
                    .build()
            )
            .build()

        return PaneTemplate.Builder(pane)
            .setTitle("Claude")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }

    private fun icon(res: Int) =
        CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()
}
