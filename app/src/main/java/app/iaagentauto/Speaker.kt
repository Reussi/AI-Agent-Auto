package app.iaagentauto

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Sprachausgabe (im Auto über die Autolautsprecher) (Musik wird währenddessen leiser gestellt). */
class Speaker(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .build()

    private var ready = false
    private var pending: Pair<String, () -> Unit>? = null
    private var onDone: (() -> Unit)? = null

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        main.post {
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                val lang = Locale.getDefault().takeIf { it.language == "de" } ?: Locale.GERMANY
                tts.language = lang
                tts.setAudioAttributes(attrs)
            }
            pending?.let { (t, d) -> pending = null; speak(t, d) }
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = main.post { finished() }.let { }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = main.post { finished() }.let { }
            override fun onStop(utteranceId: String?, interrupted: Boolean) = main.post { finished() }.let { }
        })
    }

    val isSpeaking: Boolean get() = onDone != null

    fun speak(text: String, done: () -> Unit) {
        if (!ready) { pending = text to done; return }
        stop()
        onDone = done
        audio.requestAudioFocus(focus)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "claude-${System.nanoTime()}")
    }

    /** Bricht die Ausgabe ab, ohne den Abschluss-Callback auszulösen. */
    fun stop() {
        onDone = null
        pending = null
        if (ready) tts.stop()
        audio.abandonAudioFocusRequest(focus)
    }

    private fun finished() {
        val cb = onDone ?: return
        onDone = null
        audio.abandonAudioFocusRequest(focus)
        cb()
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }
}
