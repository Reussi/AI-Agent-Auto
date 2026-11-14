package app.iaagentauto.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import app.iaagentauto.R
import app.iaagentauto.Settings

/** Widget für den Startbildschirm: Antippen startet die Spracheingabe für Claude. */
class ClaudeWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, views(context)) }
    }

    companion object {
        /** Alle Widgets aktualisieren, z. B. nach einer neuen Antwort. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ClaudeWidget::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context))
        }

        private fun views(context: Context): RemoteViews {
            val open = PendingIntent.getActivity(
                context, 10,
                Intent(context, VoiceActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return RemoteViews(context.packageName, R.layout.widget_claude).apply {
                setTextViewText(
                    R.id.widgetAnswer,
                    Settings.lastAnswer(context).ifBlank { "Antippen und sprechen" }
                )
                setOnClickPendingIntent(R.id.widgetRoot, open)
                setOnClickPendingIntent(R.id.widgetMic, open)
            }
        }
    }
}
