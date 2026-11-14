package app.iaagentauto

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Übergibt den Gesprächsverlauf an die Claude-App. Dort entsteht daraus ein neuer Chat
 * in deinem Claude-Konto. (Ein direktes Speichern in claude.ai ist über die API nicht möglich.)
 */
object ClaudeExport {
    const val CLAUDE_PACKAGE = "com.anthropic.claude"

    fun isClaudeInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(CLAUDE_PACKAGE, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    /** Intent zum Teilen – direkt an die Claude-App, sonst Auswahl-Dialog. */
    fun intent(context: Context, text: String): Intent {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Gespräch aus IA Agent Auto")
            .putExtra(Intent.EXTRA_TEXT, text)
        return if (isClaudeInstalled(context)) {
            send.setPackage(CLAUDE_PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent.createChooser(send, "Gespräch teilen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** Ab hier beginnt der noch nicht übernommene Teil des Verlaufs. */
    fun pendingFrom(context: Context): Int {
        val size = Conversation.turns.value.size
        val from = Settings.exportedCount(context)
        return if (from in 0..size) from else 0
    }

    fun markExported(context: Context) = Settings.setExportedCount(context, Conversation.turns.value.size)
}
