package app.iaagentauto

import android.content.Context
import android.os.Build

/**
 * Zeigt, ob Android die App als „aus Google Play installiert“ führt. Nur dann zeigt
 * Android Auto (ab Version 17.7) den Sprechen-Bildschirm an.
 */
object InstallSource {
    private const val PLAY = "com.android.vending"

    fun isPlayMarked(c: Context): Boolean = installer(c) == PLAY

    private fun installer(c: Context): String? = try {
        if (Build.VERSION.SDK_INT >= 30) {
            c.packageManager.getInstallSourceInfo(c.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            c.packageManager.getInstallerPackageName(c.packageName)
        }
    } catch (_: Exception) { null }

    fun describe(c: Context): String = if (isPlayMarked(c)) {
        "Installiert als „aus Google Play“ ✓ – Android Auto zeigt „IA Agent Auto“ an."
    } else {
        "Installiert aus anderer Quelle (${installer(c) ?: "unbekannt"}) – Android Auto zeigt " +
            "„IA Agent Auto“ so nicht an. Installiere die App über UnknownAuto (siehe Anleitung)."
    }
}
