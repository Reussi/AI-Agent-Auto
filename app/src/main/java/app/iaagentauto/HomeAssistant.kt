package app.iaagentauto

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Verbindung zu Home Assistant über dessen REST-API (Adresse + langlebiges Zugriffstoken).
 * Claude bekommt darüber Werkzeuge zum Suchen, Abfragen und Schalten von Geräten –
 * aber nur in den Bereichen, die in den Einstellungen freigegeben sind.
 */
object HomeAssistant {

    /** Ein Bereich, den der Nutzer freigeben kann. */
    data class Group(val key: String, val label: String, val hint: String, val domains: List<String>, val defaultOn: Boolean)

    val GROUPS = listOf(
        Group("light", "Licht", "Lampen ein/aus, dimmen, Farbe", listOf("light"), true),
        Group("switch", "Schalter & Steckdosen", "Schalter, Steckdosen, Ventilatoren, Helfer", listOf("switch", "input_boolean", "fan", "button", "input_button"), true),
        Group("climate", "Heizung & Klima", "Temperatur einstellen, Modus wechseln", listOf("climate", "water_heater", "humidifier"), true),
        Group("scene", "Szenen & Skripte", "Szenen aktivieren, Skripte starten", listOf("scene", "script"), true),
        Group("media", "Medien", "Lautstärke, Wiedergabe, Fernseher", listOf("media_player"), true),
        Group("vacuum", "Saugroboter & Rasenmäher", "Starten, stoppen, zur Station", listOf("vacuum", "lawn_mower"), true),
        Group("cover", "Rollos, Tore & Garage", "Öffnen und schließen", listOf("cover", "valve"), false),
        Group("lock", "Türschlösser", "Auf- und zuschließen", listOf("lock"), false),
        Group("alarm", "Alarmanlage", "Scharf- und unscharf schalten", listOf("alarm_control_panel"), false),
    )

    /** Diese Zustände darf Claude immer lesen (nur abfragen, nicht schalten). */
    private val READ_ONLY = setOf(
        "sensor", "binary_sensor", "weather", "person", "device_tracker", "sun", "zone",
        "input_number", "input_select", "input_text", "calendar", "update",
    )

    fun isConfigured(c: Context) = Settings.haUrl(c).isNotEmpty() && Settings.haToken(c).isNotEmpty()

    /** Eingerichtet UND gekauft – nur dann bekommt Claude die Smart-Home-Werkzeuge. */
    fun isActive(c: Context) = isConfigured(c) && Billing.isUnlocked(c)

    fun allowedDomains(c: Context): Set<String> {
        val enabled = Settings.haGroups(c) ?: GROUPS.filter { it.defaultOn }.map { it.key }.toSet()
        return GROUPS.filter { it.key in enabled }.flatMap { it.domains }.toSet()
    }

    /** Adresse vereinheitlichen: Schema ergänzen, Schrägstriche und /lovelace o. Ä. entfernen. */
    fun normalizeUrl(raw: String): String {
        var u = raw.trim().trimEnd('/')
        if (u.isEmpty()) return u
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        val path = Regex("^(https?://[^/]+)(/.*)?$").find(u)
        return path?.groupValues?.get(1) ?: u
    }

    // ---------------------------------------------------------------- HTTP

    private class HaException(message: String) : Exception(message)

    private fun request(url: String, token: String, method: String, path: String, body: String? = null): String {
        val conn = URL(url + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 8_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            when (code) {
                in 200..299 -> return text
                401, 403 -> throw HaException("Der Zugangsschlüssel wird von Home Assistant abgelehnt.")
                404 -> throw HaException("Nicht gefunden (404).")
                400 -> throw HaException("Home Assistant hat die Anfrage abgelehnt: ${text.take(200)}")
                else -> throw HaException("Home Assistant antwortet mit Fehler $code.")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is HaException -> e.message ?: "Fehler"
        is java.net.UnknownHostException -> "Die Adresse ist nicht erreichbar (unbekannter Name). Ist Home Assistant von unterwegs erreichbar?"
        is java.net.SocketTimeoutException, is java.net.ConnectException ->
            "Home Assistant antwortet nicht. Ist die Adresse richtig und von hier aus erreichbar?"
        is javax.net.ssl.SSLException -> "Sichere Verbindung fehlgeschlagen (Zertifikat). Prüfe http:// bzw. https://."
        else -> e.message ?: e.javaClass.simpleName
    }

    /** Verbindung prüfen. Gibt null bei Erfolg zurück, sonst eine Fehlermeldung. */
    fun test(url: String, token: String): String? = try {
        val r = request(url, token, "GET", "/api/")
        if (r.contains("API running", ignoreCase = true)) null
        else "Unerwartete Antwort – ist das wirklich die Adresse von Home Assistant?"
    } catch (e: Exception) {
        describe(e)
    }

    // ---------------------------------------------------------------- Werkzeuge für Claude

    private fun domainOf(entityId: String) = entityId.substringBefore('.', "")

    private fun line(o: JSONObject): String {
        val attrs = o.optJSONObject("attributes") ?: JSONObject()
        val name = attrs.optString("friendly_name", "")
        val unit = attrs.optString("unit_of_measurement", "")
        val state = o.optString("state") + if (unit.isNotEmpty()) " $unit" else ""
        return "${o.optString("entity_id")} | $name | $state"
    }

    /** Geräte suchen (Name oder ID enthält [query]); ohne query alle steuerbaren Geräte. */
    fun findEntities(c: Context, query: String?): Pair<String, Boolean> = try {
        val allowed = allowedDomains(c)
        val readable = allowed + READ_ONLY
        val arr = JSONArray(request(Settings.haUrl(c), Settings.haToken(c), "GET", "/api/states"))
        val q = query?.trim()?.lowercase().orEmpty()
        val words = q.split(Regex("\\s+")).filter { it.length > 1 }
        val matches = (0 until arr.length()).map { arr.getJSONObject(it) }.filter { o ->
            val id = o.optString("entity_id")
            val d = domainOf(id)
            if (d !in readable) return@filter false
            if (q.isEmpty()) return@filter d in allowed
            val hay = (id + " " + (o.optJSONObject("attributes")?.optString("friendly_name") ?: "")).lowercase()
            words.all { hay.contains(it) } || hay.contains(q)
        }
        if (matches.isEmpty()) {
            "Keine passenden Geräte gefunden. Steuerbar sind nur diese Bereiche: ${allowed.sorted().joinToString()}." to false
        } else {
            val shown = matches.take(120)
            val more = if (matches.size > shown.size) "\n… und ${matches.size - shown.size} weitere – Suche genauer eingrenzen." else ""
            ("entity_id | Name | Zustand\n" + shown.joinToString("\n") { line(it) } + more +
                "\n\nSteuerbare Bereiche: ${allowed.sorted().joinToString()}") to false
        }
    } catch (e: Exception) {
        "Home Assistant nicht erreichbar: ${describe(e)}" to true
    }

    fun getState(c: Context, entityId: String): Pair<String, Boolean> = try {
        val d = domainOf(entityId)
        if (d !in allowedDomains(c) + READ_ONLY) {
            "Der Bereich „$d“ ist in den Einstellungen der App nicht freigegeben." to true
        } else {
            val o = JSONObject(request(Settings.haUrl(c), Settings.haToken(c), "GET", "/api/states/$entityId"))
            val attrs = o.optJSONObject("attributes")?.toString()?.take(1500) ?: "{}"
            "${line(o)}\nAttribute: $attrs" to false
        }
    } catch (e: Exception) {
        "Abfrage fehlgeschlagen: ${describe(e)}" to true
    }

    fun callService(c: Context, domain: String, service: String, entityId: String, data: Map<*, *>?): Pair<String, Boolean> {
        val allowed = allowedDomains(c)
        val entityDomain = domainOf(entityId)
        if (entityDomain !in allowed || domain !in allowed) {
            return ("Nicht erlaubt: Der Bereich „$entityDomain“ ist in den Einstellungen der App nicht zum Steuern " +
                "freigegeben. Der Nutzer kann ihn unter Einstellungen → Home Assistant freischalten.") to true
        }
        if (domain != entityDomain) {
            return "Die Aktion $domain.$service passt nicht zum Gerät $entityId (Bereich $entityDomain)." to true
        }
        if (!Regex("^[a-z_]+$").matches(service)) return "Ungültiger Aktionsname." to true
        return try {
            val body = JSONObject(data?.mapKeys { it.key.toString() } ?: emptyMap<String, Any?>())
            body.put("entity_id", entityId)
            request(Settings.haUrl(c), Settings.haToken(c), "POST", "/api/services/$domain/$service", body.toString())
            // Neuen Zustand zur Bestätigung mitliefern.
            val after = try {
                Thread.sleep(600)
                line(JSONObject(request(Settings.haUrl(c), Settings.haToken(c), "GET", "/api/states/$entityId")))
            } catch (_: Exception) { "" }
            "Ausgeführt: $domain.$service für $entityId. Neuer Zustand: $after" to false
        } catch (e: Exception) {
            "Aktion fehlgeschlagen: ${describe(e)}" to true
        }
    }
}
