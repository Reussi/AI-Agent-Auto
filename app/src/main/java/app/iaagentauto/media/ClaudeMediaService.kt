package app.iaagentauto.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import app.iaagentauto.Conversation
import app.iaagentauto.MainActivity
import app.iaagentauto.R
import app.iaagentauto.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * Claude als „Medien-App“ in Android Auto. Android Auto lässt selbst installierte Medien-Apps zu.
 *
 * Bedienung im Auto: IA Agent Auto öffnen → Mikrofon/Suche antippen → Frage sprechen.
 * Android Auto erkennt die Sprache und übergibt den Text (onPlayFromSearch); die Antwort wird
 * per Sprachausgabe erzeugt und wie ein Titel abgespielt – ohne Rückfrage.
 * Auch möglich: „Hey Google, spiele <Frage> auf IA Agent Auto“.
 */
class ClaudeMediaService : MediaBrowserServiceCompat() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var session: MediaSessionCompat
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var pendingSpeech: String? = null
    private var player: MediaPlayer? = null
    private var askJob: Job? = null
    private var lastAnswer: String = ""

    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS) stopPlayback()
            }
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        lastAnswer = Settings.lastAnswer(this)

        session = MediaSessionCompat(this, "ClaudeAuto").apply {
            setCallback(callback)
            setSessionActivity(PendingIntent.getActivity(
                this@ClaudeMediaService, 0, Intent(this@ClaudeMediaService, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE))
            isActive = true
        }
        sessionToken = session.sessionToken
        setState(PlaybackStateCompat.STATE_NONE, "Tippe auf das Mikrofon und stell deine Frage.")

        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts.language = Locale.getDefault().takeIf { it.language == "de" } ?: Locale.GERMANY
            }
            pendingSpeech?.let { pendingSpeech = null; speak(it) }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        stopPlayback()
        tts.shutdown()
        session.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------- Inhalte für Android Auto

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        val extras = Bundle().apply {
            // Suche/Spracheingabe in Android Auto einschalten.
            putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
            putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1) // Liste
        }
        return BrowserRoot(ROOT, extras)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        if (parentId != ROOT) { result.sendResult(mutableListOf()); return }
        val items = mutableListOf(
            item(ID_HELP, "Frage stellen", "Mikrofon antippen und lossprechen"),
        )
        if (lastAnswer.isNotBlank()) items.add(item(ID_LAST, "Letzte Antwort nochmal hören", lastAnswer.take(80)))
        // Die letzten Fragen zum erneuten Anhören
        val turns = Conversation.turns.value
        turns.indices.reversed().filter { turns[it].fromUser }.take(8).forEach { i ->
            val answer = turns.getOrNull(i + 1)?.takeIf { !it.fromUser }?.text ?: return@forEach
            items.add(item("$ID_TURN$i", turns[i].text.take(60), answer.take(80)))
        }
        result.sendResult(items)
    }

    override fun onSearch(query: String, extras: Bundle?, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        // Eine Frage ist keine Suche – direkt beantworten.
        result.sendResult(mutableListOf(item(ID_LAST, "Claude antwortet …", query)))
        ask(query)
    }

    private fun item(id: String, title: String, subtitle: String) = MediaBrowserCompat.MediaItem(
        MediaDescriptionCompat.Builder()
            .setMediaId(id).setTitle(title).setSubtitle(subtitle)
            .setIconUri(android.net.Uri.parse("android.resource://$packageName/${R.mipmap.ic_launcher}"))
            .build(),
        MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
    )

    // ------------------------------------------------------------- Steuerung

    private val callback = object : MediaSessionCompat.Callback() {
        override fun onPlayFromSearch(query: String?, extras: Bundle?) {
            if (query.isNullOrBlank()) replay() else ask(query)
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            when {
                mediaId == ID_HELP -> speak(HELP, title = "So funktioniert’s")
                mediaId == ID_LAST -> replay()
                mediaId?.startsWith(ID_TURN) == true -> {
                    val i = mediaId.removePrefix(ID_TURN).toIntOrNull() ?: return
                    val answer = Conversation.turns.value.getOrNull(i + 1)?.text ?: return
                    speak(answer)
                }
            }
        }

        override fun onPlay() = replay()
        override fun onPause() = stopPlayback()
        override fun onStop() = stopPlayback()
    }

    private fun replay() {
        if (lastAnswer.isBlank()) speak(HELP, title = "So funktioniert’s") else speak(lastAnswer)
    }

    private var lastQuestion = ""
    private var lastQuestionAt = 0L

    private fun ask(question: String) {
        // Android Auto ruft bei einer Spracheingabe teils onSearch UND onPlayFromSearch auf.
        val now = System.currentTimeMillis()
        if (question.equals(lastQuestion, ignoreCase = true) && now - lastQuestionAt < 10_000) return
        lastQuestion = question
        lastQuestionAt = now
        stopPlayback()
        askJob?.cancel()
        setMetadata("Claude denkt nach …", question)
        setState(PlaybackStateCompat.STATE_BUFFERING, null)
        startAsForeground("Claude denkt nach …", question)
        askJob = scope.launch {
            val answer = Conversation.ask(applicationContext, question)
            if (!answer.isError) lastAnswer = answer.text
            notifyChildrenChanged(ROOT)
            speak(answer.text, subtitle = question)
        }
    }

    // ------------------------------------------------------------- Sprachausgabe & Wiedergabe

    /** Erzeugt aus [text] eine Audiodatei und spielt sie ab (wie ein Musiktitel). */
    private fun speak(text: String, title: String = "Claude", subtitle: String? = null) {
        if (!ttsReady) { pendingSpeech = text; return }
        stopPlayback()
        setMetadata(title, subtitle ?: text.take(120))
        setState(PlaybackStateCompat.STATE_BUFFERING, null)
        val file = File(cacheDir, "antwort.wav")
        val id = "claude-${System.nanoTime()}"
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == id) scope.launch { play(file, title, subtitle ?: text) }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                scope.launch { setState(PlaybackStateCompat.STATE_ERROR, "Sprachausgabe fehlgeschlagen.") }
            }
        })
        tts.synthesizeToFile(text, Bundle(), file, id)
    }

    private fun play(file: File, title: String, subtitle: String) {
        audio.requestAudioFocus(focus)
        startAsForeground(title, subtitle)
        player = MediaPlayer().apply {
            setAudioAttributes(attrs)
            setDataSource(file.absolutePath)
            setOnCompletionListener { stopPlayback() }
            setOnErrorListener { _, _, _ -> stopPlayback(); true }
            prepare()
            start()
        }
        setState(PlaybackStateCompat.STATE_PLAYING, null)
    }

    private fun stopPlayback() {
        player?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        player = null
        audio.abandonAudioFocusRequest(focus)
        setState(PlaybackStateCompat.STATE_PAUSED, null)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
    }

    private fun setState(state: Int, error: String?) {
        val b = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_STOP or PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH or PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
            )
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
        if (state == PlaybackStateCompat.STATE_ERROR && error != null) {
            b.setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, error)
        }
        session.setPlaybackState(b.build())
    }

    private fun setMetadata(title: String, subtitle: String) {
        session.setMetadata(MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, subtitle)
            .build())
    }

    // ------------------------------------------------------------- Vordergrund (für Wiedergabe)

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Claude spricht", NotificationManager.IMPORTANCE_LOW))
    }

    private fun startAsForeground(title: String, text: String) {
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_claude)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(MediaStyle().setMediaSession(session.sessionToken))
            .setOngoing(true)
            .build()
        try {
            startService(Intent(this, ClaudeMediaService::class.java))
            if (Build.VERSION.SDK_INT >= 29) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        } catch (_: Exception) {
            // Darf Android den Dienst gerade nicht in den Vordergrund holen, läuft die Wiedergabe
            // trotzdem, solange Android Auto mit dem Dienst verbunden ist.
        }
    }

    companion object {
        private const val ROOT = "root"
        private const val ID_HELP = "help"
        private const val ID_LAST = "last"
        private const val ID_TURN = "turn:"
        private const val CHANNEL = "claude_media"
        private const val NOTIFICATION_ID = 7
        private const val HELP = "Tippe oben auf das Mikrofon oder die Lupe und stell deine Frage. " +
            "Du kannst auch sagen: Hey Google, spiele, und dann deine Frage, auf IA Agent Auto."
    }
}
