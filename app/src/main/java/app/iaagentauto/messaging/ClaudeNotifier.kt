package app.iaagentauto.messaging

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import app.iaagentauto.Conversation
import app.iaagentauto.MainActivity
import app.iaagentauto.R

/**
 * Zeigt den Chat mit Claude als Messaging-Benachrichtigung an. Android Auto liest solche
 * Nachrichten vor und bietet „Antworten“ per Sprache an – die Spracherkennung übernimmt dabei
 * Google Assistant über das Mikrofon des Autos.
 */
object ClaudeNotifier {
    const val CHANNEL_CHAT = "claude_chat"
    const val CHANNEL_WORK = "claude_work"
    const val NOTIFICATION_ID = 1
    const val WORK_NOTIFICATION_ID = 2
    const val KEY_REPLY = "reply_text"
    const val ACTION_REPLY = "app.iaagentauto.REPLY"
    const val ACTION_MARK_READ = "app.iaagentauto.MARK_READ"

    private const val FOLLOW_UP = "Noch eine Frage? Antworte einfach auf diese Nachricht."

    private const val GREETING =
        "Hallo, hier ist Claude. Antworte auf diese Nachricht, um mir eine Frage zu stellen."

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CHAT, "Chat mit Claude", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WORK, "Claude denkt nach", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun me() = Person.Builder().setName("Ich").setKey("me").build()

    private fun claude(context: Context) = Person.Builder()
        .setName("Claude")
        .setKey("claude")
        .setBot(true)
        .setIcon(IconCompat.createWithResource(context, R.drawable.ic_claude))
        .build()

    /** Begrüßung posten, damit man im Auto direkt per „Antworten“ loslegen kann. */
    fun showGreeting(context: Context) = show(context, alert = true, greetingIfEmpty = true)

    /**
     * Zeigt die letzten Nachrichten an. [alert] = true lässt Android Auto die neue
     * Claude-Nachricht ankündigen und vorlesen.
     */
    fun show(context: Context, alert: Boolean, greetingIfEmpty: Boolean = false, followUp: Boolean = false) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED && android.os.Build.VERSION.SDK_INT >= 33
        ) return

        val me = me()
        val claude = claude(context)
        val style = NotificationCompat.MessagingStyle(me)
        // Android Auto liest ALLE Nachrichten vor, die in der Benachrichtigung stehen.
        // Deshalb steht dort immer nur die neueste Antwort von Claude – nicht der Verlauf.
        val now = System.currentTimeMillis()
        val latest = Conversation.turns.value.lastOrNull { !it.fromUser }
        when {
            // Antwort wurde schon von der App vorgelesen – nur kurz zum Weiterfragen einladen.
            followUp -> style.addMessage(FOLLOW_UP, now, claude)
            latest != null -> style.addMessage(latest.text, now, claude)
            greetingIfEmpty -> style.addMessage(GREETING, now, claude)
            else -> return
        }

        // „Antworten“ – muss ohne Benutzeroberfläche funktionieren (Pflicht für Android Auto).
        val replyIntent = Intent(context, ReplyReceiver::class.java).setAction(ACTION_REPLY)
        val replyPending = PendingIntent.getBroadcast(
            context, 1, replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val remoteInput = RemoteInput.Builder(KEY_REPLY).setLabel("Frage an Claude").build()
        val replyAction = NotificationCompat.Action.Builder(
            IconCompat.createWithResource(context, R.drawable.ic_mic), "Antworten", replyPending
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()

        // „Als gelesen markieren“ – ebenfalls Pflicht für Android Auto.
        val readPending = PendingIntent.getBroadcast(
            context, 2,
            Intent(context, ReplyReceiver::class.java).setAction(ACTION_MARK_READ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val readAction = NotificationCompat.Action.Builder(
            IconCompat.createWithResource(context, R.drawable.ic_claude), "Gelesen", readPending
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()

        val openApp = PendingIntent.getActivity(
            context, 3, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_CHAT)
            .setSmallIcon(R.drawable.ic_claude)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp)
            .addAction(replyAction)
            .addInvisibleAction(readAction)
            .setOnlyAlertOnce(!alert)
            .setSilent(!alert)
            .setAutoCancel(false)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Benachrichtigungen nicht erlaubt.
        }
    }

    const val EXPORT_NOTIFICATION_ID = 3

    /** Nach der Fahrt: „Gespräch in Claude übernehmen?“ – nur wenn es Neues gibt. */
    fun showExportReminder(context: Context) {
        val from = app.iaagentauto.ClaudeExport.pendingFrom(context)
        val text = Conversation.transcript(from)
        if (text.isEmpty()) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED && android.os.Build.VERSION.SDK_INT >= 33
        ) return
        // Als übernommen markieren, damit die Erinnerung nicht bei jeder Trennung erneut kommt.
        app.iaagentauto.ClaudeExport.markExported(context)
        val pending = PendingIntent.getActivity(
            context, 4, app.iaagentauto.ClaudeExport.intent(context, text),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val count = Conversation.turns.value.drop(from).count { it.fromUser && !it.isError }
        val n = NotificationCompat.Builder(context, CHANNEL_WORK)
            .setSmallIcon(R.drawable.ic_claude)
            .setContentTitle("Gespräch in Claude übernehmen?")
            .setContentText("$count Frage(n) aus dem Auto – antippen, um sie in deinem Claude-Konto zu speichern.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "$count Frage(n) aus dem Auto. Antippen öffnet die Claude-App mit dem Gesprächsverlauf – " +
                    "so ist er in deinem Claude-Konto gespeichert."))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(EXPORT_NOTIFICATION_ID, n)
        } catch (_: SecurityException) {}
    }

    fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

    fun workNotification(context: Context) = NotificationCompat.Builder(context, CHANNEL_WORK)
        .setSmallIcon(R.drawable.ic_claude)
        .setContentTitle("Claude denkt nach …")
        .setOngoing(true)
        .build()
}
