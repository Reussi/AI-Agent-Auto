package app.iaagentauto.messaging

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.iaagentauto.Conversation
import app.iaagentauto.Settings
import app.iaagentauto.Speaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Schickt die Frage an Claude und zeigt die Antwort als neue Nachricht an. */
class AskWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_TEXT = "text"
    }

    override suspend fun doWork(): Result {
        val text = inputData.getString(KEY_TEXT) ?: return Result.failure()
        val ctx = applicationContext

        val answer = Conversation.ask(ctx, text)

        if (Settings.autoRead(ctx) && CarState.isInCar(ctx)) {
            // Antwort direkt über die Autolautsprecher vorlesen – ohne Rückfrage von Android Auto.
            speak(ctx, answer.text)
            // Danach nur eine kurze Nachricht, damit man per „Antworten“ weiterfragen kann.
            ClaudeNotifier.show(ctx, alert = true, followUp = true)
        } else {
            ClaudeNotifier.show(ctx, alert = true)
        }
        return if (answer.isError) Result.failure() else Result.success()
    }

    /** Liest [text] vor und wartet, bis die Ausgabe fertig ist. */
    private suspend fun speak(ctx: Context, text: String) {
        val maxMillis = 20_000L + text.length * 120L
        withContext(Dispatchers.Main) {
            val speaker = Speaker(ctx)
            try {
                withTimeoutOrNull(maxMillis) {
                    suspendCancellableCoroutine { cont ->
                        speaker.speak(text) { if (cont.isActive) cont.resume(Unit) }
                        cont.invokeOnCancellation { speaker.stop() }
                    }
                }
            } finally {
                speaker.shutdown()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val n = ClaudeNotifier.workNotification(applicationContext)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(ClaudeNotifier.WORK_NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(ClaudeNotifier.WORK_NOTIFICATION_ID, n)
        }
    }
}
