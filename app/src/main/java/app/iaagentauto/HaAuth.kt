package app.iaagentauto

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * Anmeldung bei Home Assistant mit Benutzername und Passwort (OAuth2, wie die offizielle App).
 *
 * Home Assistant prüft die App über die [CLIENT_ID]: Diese Seite enthält
 * `<link rel="redirect_uri" href="iaagentauto://ha-auth">` (siehe docs/ha-auth.html im Repository).
 * Nach der Anmeldung leitet Home Assistant mit einem Code zurück zur App; daraus werden ein
 * Zugriffstoken (30 Minuten gültig) und ein Erneuerungstoken (dauerhaft) erzeugt.
 */
object HaAuth {
    const val CLIENT_ID = "https://raw.githubusercontent.com/Reussi/AI-Agent-Auto/main/docs/ha-auth.html"
    const val REDIRECT_URI = "iaagentauto://ha-auth"

    data class Tokens(val access: String, val refresh: String, val expiresAt: Long)

    /** Adresse der Anmeldeseite; [state] schützt vor fremden Rückmeldungen. */
    fun authorizeUrl(baseUrl: String, state: String): Uri = Uri.parse("$baseUrl/auth/authorize").buildUpon()
        .appendQueryParameter("response_type", "code")
        .appendQueryParameter("client_id", CLIENT_ID)
        .appendQueryParameter("redirect_uri", REDIRECT_URI)
        .appendQueryParameter("state", state)
        .build()

    fun newState(): String = UUID.randomUUID().toString()

    /** Tauscht den Code aus der Rückleitung gegen Tokens (läuft im IO-Thread). */
    fun exchangeCode(baseUrl: String, code: String): Tokens {
        val o = post(baseUrl, mapOf("grant_type" to "authorization_code", "code" to code, "client_id" to CLIENT_ID))
        return Tokens(
            o.getString("access_token"),
            o.getString("refresh_token"),
            System.currentTimeMillis() + o.optLong("expires_in", 1800) * 1000,
        )
    }

    /** Erneuert den Zugriff mit dem Erneuerungstoken (läuft im IO-Thread). */
    fun refresh(baseUrl: String, refreshToken: String): Pair<String, Long> {
        val o = post(baseUrl, mapOf("grant_type" to "refresh_token", "refresh_token" to refreshToken, "client_id" to CLIENT_ID))
        return o.getString("access_token") to (System.currentTimeMillis() + o.optLong("expires_in", 1800) * 1000)
    }

    /**
     * Liefert ein gültiges Zugriffstoken: bei Zugangsschlüssel-Verbindung den Schlüssel selbst,
     * bei Anmeldung das aktuelle Zugriffstoken – bei Bedarf vorher erneuert.
     */
    @Synchronized
    fun currentToken(c: Context, baseUrl: String): String {
        if (Settings.haMode(c) != Settings.HA_MODE_LOGIN) return Settings.haToken(c)
        val valid = Settings.haAccessExpiry(c) - 60_000 > System.currentTimeMillis()
        if (valid && Settings.haToken(c).isNotEmpty()) return Settings.haToken(c)
        val (access, expiry) = refresh(baseUrl, Settings.haRefreshToken(c))
        Settings.setHaAccess(c, access, expiry)
        return access
    }

    private fun post(baseUrl: String, form: Map<String, String>): JSONObject {
        val conn = URL("$baseUrl/auth/token").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val body = form.entries.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw java.io.IOException(
                    if (code == 400 || code == 403) "Die Anmeldung ist abgelaufen oder wurde widerrufen – bitte neu anmelden."
                    else "Home Assistant antwortet mit Fehler $code."
                )
            }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
