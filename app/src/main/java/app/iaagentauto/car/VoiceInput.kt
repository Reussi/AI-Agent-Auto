package app.iaagentauto.car

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.media.CarAudioRecord
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Spracherkennung im Auto.
 *
 * Bevorzugt wird das Mikrofon des Autos (CarAudioRecord, Car-API-Level 5) – dessen Audio wird
 * an die Android-Spracherkennung durchgereicht (ab Android 13). Ältere Kombinationen nutzen
 * das Handy-Mikrofon.
 */
class VoiceInput(
    private val carContext: CarContext,
    private val onPartial: (String) -> Unit,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null
    private var carRecord: CarAudioRecord? = null
    @Volatile private var recording = false
    private var usedCarMic = false

    val isListening: Boolean get() = recognizer != null

    fun start(preferCarMic: Boolean = true) {
        stop()
        if (!SpeechRecognizer.isRecognitionAvailable(carContext)) {
            onError("Auf diesem Handy ist keine Spracherkennung verfügbar.")
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }

        usedCarMic = preferCarMic && canUseCarMic()
        if (usedCarMic) {
            try {
                attachCarMic(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Automikrofon nicht nutzbar, verwende Handy-Mikrofon", e)
                stopCarMic()
                usedCarMic = false
            }
        }

        val r = SpeechRecognizer.createSpeechRecognizer(carContext)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { stopCarMic() }
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                first(partialResults)?.let(onPartial)
            }

            override fun onResults(results: Bundle?) {
                val text = first(results)
                stop()
                if (text.isNullOrBlank()) onError("Ich habe nichts verstanden.") else onResult(text)
            }

            override fun onError(error: Int) {
                val wasCarMic = usedCarMic
                stop()
                // Falls die Erkennung mit dem Automikrofon nicht klappt: einmal mit Handy-Mikro versuchen.
                if (wasCarMic && error in setOf(
                        SpeechRecognizer.ERROR_AUDIO, SpeechRecognizer.ERROR_CLIENT,
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_SERVER
                    )
                ) {
                    start(preferCarMic = false)
                    return
                }
                onError(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                            "Ich habe nichts verstanden."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                            "Mikrofon-Berechtigung fehlt. Bitte in der App auf dem Handy erlauben."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                            "Keine Internetverbindung für die Spracherkennung."
                        else -> "Spracherkennung fehlgeschlagen, Code $error."
                    }
                )
            }
        })
        r.startListening(intent)
    }

    fun stop() {
        stopCarMic()
        recognizer?.let {
            try { it.cancel() } catch (_: Exception) {}
            it.destroy()
        }
        recognizer = null
    }

    private fun canUseCarMic(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && carContext.carAppApiLevel >= 5

    /** Leitet das Auto-Mikrofon per Pipe an die Spracherkennung weiter (Android 13+). */
    private fun attachCarMic(intent: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(carContext, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) throw SecurityException("RECORD_AUDIO fehlt")
        val record = CarAudioRecord.create(carContext)
        val pipe = ParcelFileDescriptor.createPipe()
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, CarAudioRecord.AUDIO_CONTENT_SAMPLING_RATE)

        carRecord = record
        recording = true
        record.startRecording()
        thread(name = "car-mic") {
            val buf = ByteArray(CarAudioRecord.AUDIO_CONTENT_BUFFER_SIZE)
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
                    while (recording) {
                        val n = record.read(buf, 0, buf.size)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Mikrofon-Stream beendet: ${e.message}")
            } finally {
                try { pipe[0].close() } catch (_: Exception) {}
            }
        }
    }

    private fun stopCarMic() {
        recording = false
        carRecord?.let { try { it.stopRecording() } catch (_: Exception) {} }
        carRecord = null
    }

    private fun first(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    companion object { private const val TAG = "VoiceInput" }
}
