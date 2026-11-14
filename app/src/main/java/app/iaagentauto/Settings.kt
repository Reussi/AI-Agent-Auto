package app.iaagentauto

import android.content.Context

/** Einfache Einstellungen, privat im App-Speicher abgelegt. */
object Settings {
    private const val PREFS = "claude_auto"
    const val DEFAULT_MODEL = "claude-opus-5-5"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun apiKey(c: Context): String = prefs(c).getString("api_key", "")!!.trim()
    fun setApiKey(c: Context, v: String) = prefs(c).edit().putString("api_key", v.trim()).apply()

    fun model(c: Context): String = prefs(c).getString("model", DEFAULT_MODEL)!!.ifBlank { DEFAULT_MODEL }
    fun setModel(c: Context, v: String) = prefs(c).edit().putString("model", v.trim()).apply()

    /** Nach der gesprochenen Antwort automatisch wieder zuhören (nur im Auto-Bildschirm). */
    fun autoListen(c: Context): Boolean = prefs(c).getBoolean("auto_listen", true)
    fun setAutoListen(c: Context, v: Boolean) = prefs(c).edit().putBoolean("auto_listen", v).apply()

    /** Antworten im Auto sofort selbst vorlesen (ohne Rückfrage von Android Auto). */
    fun autoRead(c: Context): Boolean = prefs(c).getBoolean("auto_read", true)
    fun setAutoRead(c: Context, v: Boolean) = prefs(c).edit().putBoolean("auto_read", v).apply()

    fun webSearch(c: Context): Boolean = prefs(c).getBoolean("web_search", true)
    fun setWebSearch(c: Context, v: Boolean) = prefs(c).edit().putBoolean("web_search", v).apply()

    /** Letzte Antwort von Claude – wird im Widget angezeigt. */
    fun lastAnswer(c: Context): String = prefs(c).getString("last_answer", "")!!
    fun setLastAnswer(c: Context, v: String) = prefs(c).edit().putString("last_answer", v).apply()

    /** Bis zu welcher Nachricht der Verlauf schon in die Claude-App übernommen wurde. */
    fun exportedCount(c: Context): Int = prefs(c).getInt("exported_count", 0)
    fun setExportedCount(c: Context, v: Int) = prefs(c).edit().putInt("exported_count", v).apply()

    /** Letzte automatische Begrüßung im Auto. */
    fun lastGreeting(c: Context): Long = prefs(c).getLong("last_greeting", 0L)
    fun setLastGreeting(c: Context, v: Long) = prefs(c).edit().putLong("last_greeting", v).apply()

    /** Zeitpunkt, zu dem Android Auto den Sprechen-Bildschirm zuletzt geöffnet hat (0 = nie). */
    fun lastCarSession(c: Context): Long = prefs(c).getLong("last_car_session", 0L)
    fun setLastCarSession(c: Context, v: Long) = prefs(c).edit().putLong("last_car_session", v).apply()

    // ---------------------------------------------------------------- Home Assistant
    fun haUrl(c: Context): String = prefs(c).getString("ha_url", "")!!
    fun haToken(c: Context): String = prefs(c).getString("ha_token", "")!!
    fun setHa(c: Context, url: String, token: String) =
        prefs(c).edit().putString("ha_url", url).putString("ha_token", token.trim()).apply()

    /** Zusatzfunktion Home Assistant gekauft (Zwischenspeicher; Google Play ist maßgeblich). */
    fun haUnlocked(c: Context): Boolean = prefs(c).getBoolean("ha_unlocked", false)
    fun setHaUnlocked(c: Context, v: Boolean) = prefs(c).edit().putBoolean("ha_unlocked", v).apply()

    /** Freigegebene Bereiche (null = Voreinstellung). */
    fun haGroups(c: Context): Set<String>? = prefs(c).getStringSet("ha_groups", null)?.toSet()
    fun setHaGroups(c: Context, v: Set<String>) = prefs(c).edit().putStringSet("ha_groups", v).apply()
}
