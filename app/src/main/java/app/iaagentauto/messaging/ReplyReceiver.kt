package app.iaagentauto.messaging

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf

/** Empfängt die per Sprache diktierte Antwort aus Android Auto. */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ClaudeNotifier.ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(ClaudeNotifier.KEY_REPLY)?.toString()?.trim()
                if (text.isNullOrEmpty()) return
                val request = OneTimeWorkRequestBuilder<AskWorker>()
                    .setInputData(workDataOf(AskWorker.KEY_TEXT to text))
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()
                WorkManager.getInstance(context).enqueue(request)
            }
            ClaudeNotifier.ACTION_MARK_READ -> {
                // Nachricht bleibt im Verlauf; Benachrichtigung leise aktualisieren.
                ClaudeNotifier.show(context, alert = false, greetingIfEmpty = true)
            }
        }
    }
}
