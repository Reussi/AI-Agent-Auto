package app.iaagentauto.messaging

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.iaagentauto.Settings
import java.util.concurrent.TimeUnit

/**
 * Sobald sich das Handy per Bluetooth verbindet (die meisten Autos tun das auch bei
 * Android Auto per Kabel), wird kurz darauf geprüft, ob Android Auto läuft.
 * Wenn ja, meldet sich Claude mit einer Nachricht – man kann direkt per Sprache antworten.
 */
class CarConnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Settings.apiKey(context).isEmpty()) return
        if (intent.action == android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED) {
            // Fahrt vorbei: anbieten, das Gespräch in die Claude-App zu übernehmen.
            ClaudeNotifier.showExportReminder(context)
        }
        // Keine automatische Begrüßung mehr: Claude wird im Auto direkt über den
        // Sprechen-Bildschirm bedient und meldet sich nicht von selbst.
        if (true) return
        // Mehrere Versuche, weil Android Auto nach dem Bluetooth-Verbindungsaufbau etwas braucht.
        val wm = WorkManager.getInstance(context)
        val chain = listOf(20L, 60L, 120L).map { delay ->
            OneTimeWorkRequestBuilder<CarGreetingWorker>()
                .setInitialDelay(delay, TimeUnit.SECONDS)
                .build()
        }
        wm.beginUniqueWork("car-greeting", ExistingWorkPolicy.REPLACE, chain.first())
            .then(chain[1]).then(chain[2]).enqueue()
    }
}

class CarGreetingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val now = System.currentTimeMillis()
        // Höchstens alle 30 Minuten begrüßen.
        if (now - Settings.lastGreeting(ctx) < 30 * 60_000L) return Result.success()
        if (CarState.isInCar(ctx)) {
            Settings.setLastGreeting(ctx, now)
            ClaudeNotifier.showGreeting(ctx)
        }
        return Result.success()
    }
}
