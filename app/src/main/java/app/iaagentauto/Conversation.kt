package app.iaagentauto

import android.content.Context
import app.iaagentauto.widget.ClaudeWidget
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.OverloadedException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaTool
import com.anthropic.models.beta.messages.BetaToolResultBlockParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaWebSearchTool20250305
import com.anthropic.models.beta.messages.BetaWebSearchTool20260209
import com.anthropic.errors.NotFoundException
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.Date
import java.util.Locale

/**
 * Ein gemeinsamer Gesprächsverlauf für den Auto-Bildschirm, die Messaging-Benachrichtigung
 * und die Handy-App. Alle Anfragen an Claude laufen hierüber.
 */
object Conversation {

    data class Turn(
        val fromUser: Boolean,
        val text: String,
        val isError: Boolean = false,
        val time: Long = System.currentTimeMillis(),
    )

    /** Verlauf für die API: Nutzertext oder vollständige Claude-Antwort (inkl. Thinking-Blöcken). */
    private sealed interface Item {
        data class User(val text: String) : Item
        data class Assistant(val message: BetaMessage) : Item
        /** Antwort aus einem gespeicherten Verlauf (nach Neustart der App). */
        data class AssistantText(val text: String) : Item
        /** Ergebnisse von Werkzeug-Aufrufen (Home Assistant), als Nutzer-Nachricht zurück an Claude. */
        data class ToolResults(val results: List<ToolResult>) : Item
    }

    data class ToolResult(val toolUseId: String, val content: String, val isError: Boolean)

    private var appContext: Context? = null
    private const val FILE = "conversation.json"
    private const val MAX_STORED_TURNS = 300

    /** Gespeicherten Verlauf laden – einmal beim App-Start. */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val restored = try {
            val arr = org.json.JSONArray(java.io.File(context.filesDir, FILE).readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Turn(o.getBoolean("u"), o.getString("t"), o.optBoolean("e"), o.optLong("ts"))
            }
        } catch (_: Exception) { emptyList() }
        _turns.value = restored
        synchronized(history) {
            history.clear()
            restored.filter { !it.isError }.forEach {
                history.add(if (it.fromUser) Item.User(it.text) else Item.AssistantText(it.text))
            }
        }
    }

    private fun setTurns(list: List<Turn>) {
        val trimmed = list.takeLast(MAX_STORED_TURNS)
        _turns.value = trimmed
        val ctx = appContext ?: return
        try {
            val arr = org.json.JSONArray()
            trimmed.forEach {
                arr.put(org.json.JSONObject().put("u", it.fromUser).put("t", it.text)
                    .put("e", it.isError).put("ts", it.time))
            }
            java.io.File(ctx.filesDir, FILE).writeText(arr.toString())
        } catch (_: Exception) {}
    }

    /** Verlauf als lesbarer Text, z. B. zum Übernehmen in die Claude-App. */
    fun transcript(from: Int = 0): String {
        val fmt = SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.GERMAN)
        val list = _turns.value.drop(from).filter { !it.isError }
        if (list.isEmpty()) return ""
        val first = list.first().time.takeIf { it > 0 } ?: System.currentTimeMillis()
        return buildString {
            append("Gesprächsverlauf aus „IA Agent Auto“ (Sprachgespräch im Auto), ")
            append(fmt.format(Date(first))).append(":\n\n")
            list.forEach { append(if (it.fromUser) "Ich: " else "Claude: ").append(it.text).append("\n\n") }
        }.trim()
    }

    private val history = mutableListOf<Item>()
    private val _turns = MutableStateFlow<List<Turn>>(emptyList())
    val turns: StateFlow<List<Turn>> = _turns

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val mutex = Mutex()
    private var client: AnthropicClient? = null
    private var clientKey: String? = null

    /** Modelle, die den serverseitigen Fallback bei Ablehnungen unterstützen. */
    private val FALLBACK_MODELS = setOf(
        "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5", "claude-fable-5-2", "claude-fable-5-1"
    )

    sealed interface KeyCheck {
        data object Ok : KeyCheck
        data class Failed(val message: String) : KeyCheck
    }

    /**
     * Prüft beim Anmelden, ob der Schlüssel gültig ist und Zugriff auf das Modell hat.
     * Kostet nichts – es wird nur die Modellinfo abgefragt, keine Nachricht erzeugt.
     */
    suspend fun verifyKey(key: String, model: String): KeyCheck = withContext(Dispatchers.IO) {
        try {
            val probe = AnthropicOkHttpClient.builder()
                .apiKey(key)
                .timeout(Duration.ofSeconds(20))
                .maxRetries(1)
                .build()
            try {
                probe.models().retrieve(model)
            } finally {
                probe.close()
            }
            KeyCheck.Ok
        } catch (e: UnauthorizedException) {
            KeyCheck.Failed("Dieser Schlüssel ist ungültig oder wurde gelöscht.")
        } catch (e: PermissionDeniedException) {
            KeyCheck.Failed("Der Schlüssel hat keinen Zugriff auf die API.")
        } catch (e: NotFoundException) {
            KeyCheck.Failed("Das Modell „$model“ ist mit diesem Schlüssel nicht verfügbar.")
        } catch (e: AnthropicServiceException) {
            KeyCheck.Failed("Anmeldung fehlgeschlagen (Code ${e.statusCode()}).")
        } catch (e: AnthropicIoException) {
            KeyCheck.Failed("Keine Internetverbindung. Bitte versuch es erneut.")
        } catch (e: Exception) {
            KeyCheck.Failed("Anmeldung fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Maximal so viele Nachrichten (Nutzer + Claude) an die API schicken, damit es schnell bleibt. */
    private const val MAX_HISTORY_ITEMS = 30

    private fun systemPrompt(): String {
        val now = SimpleDateFormat("EEEE, d. MMMM yyyy, HH:mm", Locale.GERMAN).format(Date())
        return """
            Du bist Claude und sprichst mit jemandem, der gerade Auto fährt. Deine Antworten werden
            per Sprachausgabe vorgelesen, und die Eingaben stammen aus einer Spracherkennung
            (sie können also Erkennungsfehler enthalten – interpretiere sie wohlwollend).

            Regeln für deine Antworten:
            - Antworte auf Deutsch, außer der Nutzer spricht eine andere Sprache.
            - Kurz und gesprochen: in der Regel 1 bis 4 Sätze. Nur wenn ausdrücklich nach Details
              gefragt wird, darfst du länger antworten.
            - Kein Markdown, keine Aufzählungszeichen, keine Tabellen, keine Emojis, keine URLs,
              keine Quellenangaben in Klammern. Schreibe so, wie man es laut sagen würde.
            - Schreibe Zahlen, Uhrzeiten und Einheiten so, dass sie gut vorgelesen werden können.
            - Die Sicherheit beim Fahren geht vor: Fordere nie dazu auf, aufs Handy zu schauen oder
              etwas zu tippen.
            - Wenn du aktuelle Informationen brauchst (Nachrichten, Wetter, Öffnungszeiten usw.)
              und die Websuche verfügbar ist, nutze sie und fasse das Ergebnis knapp zusammen.

            Aktuelles Datum und Uhrzeit: $now.
        """.trimIndent() + haPrompt()
    }

    private fun haPrompt(): String {
        val ctx = appContext ?: return ""
        if (!HomeAssistant.isActive(ctx)) return ""
        return """


            Smart Home: Du kannst das Home Assistant des Nutzers über die Werkzeuge ha_find_entities,
            ha_get_state und ha_call_service abfragen und steuern.
            - Suche zuerst mit ha_find_entities nach dem passenden Gerät (z. B. „Wohnzimmer“, „Garage“),
              dann schalte es mit ha_call_service. Rate keine entity_id.
            - Bestätige danach in einem kurzen Satz, was du getan hast.
            - Bei Türschlössern, Garagentoren, Toren und der Alarmanlage frage vorher ausdrücklich nach
              („Soll ich das Garagentor wirklich öffnen?“) und handle erst nach einem klaren Ja.
            - Ist ein Bereich nicht freigegeben, sag kurz, dass er in der App unter Einstellungen →
              Home Assistant freigeschaltet werden kann.
        """.trimEnd()
    }

    private fun prop(type: String, description: String) =
        JsonValue.from(mapOf("type" to type, "description" to description))

    private fun haTools(): List<BetaTool> = listOf(
        BetaTool.builder()
            .name("ha_find_entities")
            .description("Sucht Geräte und Sensoren im Home Assistant des Nutzers. Liefert entity_id, Namen und " +
                "aktuellen Zustand. Ohne query: alle steuerbaren Geräte. Mit query: Suche in Name und ID (z. B. \"küche licht\").")
            .inputSchema(BetaTool.InputSchema.builder()
                .properties(BetaTool.InputSchema.Properties.builder()
                    .putAdditionalProperty("query", prop("string", "Suchbegriff, z. B. Raum oder Gerätename"))
                    .build())
                .build())
            .build(),
        BetaTool.builder()
            .name("ha_get_state")
            .description("Fragt den genauen Zustand und alle Attribute eines Geräts oder Sensors ab.")
            .inputSchema(BetaTool.InputSchema.builder()
                .properties(BetaTool.InputSchema.Properties.builder()
                    .putAdditionalProperty("entity_id", prop("string", "z. B. sensor.wohnzimmer_temperatur"))
                    .build())
                .required(listOf("entity_id"))
                .build())
            .build(),
        BetaTool.builder()
            .name("ha_call_service")
            .description("Führt eine Aktion in Home Assistant aus. Beispiele: light.turn_on (data: brightness_pct, " +
                "color_name), light.turn_off, switch.toggle, climate.set_temperature (data: temperature), " +
                "cover.open_cover / cover.close_cover, scene.turn_on, script.turn_on, lock.lock / lock.unlock, " +
                "media_player.volume_set (data: volume_level 0–1), vacuum.start.")
            .inputSchema(BetaTool.InputSchema.builder()
                .properties(BetaTool.InputSchema.Properties.builder()
                    .putAdditionalProperty("domain", prop("string", "Bereich, muss zur entity_id passen, z. B. light"))
                    .putAdditionalProperty("service", prop("string", "Aktion, z. B. turn_on"))
                    .putAdditionalProperty("entity_id", prop("string", "Gerät, z. B. light.kueche"))
                    .putAdditionalProperty("data", prop("object", "Optionale Zusatzwerte, z. B. {\"brightness_pct\": 50}"))
                    .build())
                .required(listOf("domain", "service", "entity_id"))
                .build())
            .build(),
    )

    /** Führt einen Werkzeug-Aufruf von Claude aus (läuft im IO-Thread). */
    private fun runTool(context: Context, name: String, input: Map<*, *>): Pair<String, Boolean> = when (name) {
        "ha_find_entities" -> HomeAssistant.findEntities(context, input["query"] as? String)
        "ha_get_state" -> HomeAssistant.getState(context, input["entity_id"] as? String ?: "")
        "ha_call_service" -> HomeAssistant.callService(
            context,
            input["domain"] as? String ?: "",
            input["service"] as? String ?: "",
            input["entity_id"] as? String ?: "",
            input["data"] as? Map<*, *>,
        )
        else -> "Unbekanntes Werkzeug: $name" to true
    }

    private fun clientFor(key: String): AnthropicClient {
        val existing = client
        if (existing != null && clientKey == key) return existing
        existing?.close()
        return AnthropicOkHttpClient.builder()
            .apiKey(key)
            .timeout(Duration.ofSeconds(90))
            .maxRetries(2)
            .build()
            .also { client = it; clientKey = key }
    }

    fun reset() {
        synchronized(history) { history.clear() }
        setTurns(emptyList())
        appContext?.let { Settings.setExportedCount(it, 0) }
    }

    /**
     * Schickt [userText] an Claude und liefert die (vorlesbare) Antwort zurück.
     * Fehler werden als lesbarer Text zurückgegeben, damit sie im Auto vorgelesen werden können.
     */
    suspend fun ask(context: Context, userText: String): Turn = mutex.withLock {
        val text = userText.trim()
        if (text.isEmpty()) return@withLock Turn(false, "Ich habe nichts verstanden.", isError = true)

        setTurns(_turns.value + Turn(true, text))
        val key = Settings.apiKey(context)
        if (key.isEmpty()) {
            return@withLock errorTurn("Es ist noch kein API-Schlüssel eingetragen. Bitte öffne die App auf dem Handy, wenn du nicht fährst.")
        }

        synchronized(history) { history.add(Item.User(text)) }
        _busy.value = true
        try {
            val answer = withContext(Dispatchers.IO) { callClaude(context, key) }
            val turn = Turn(false, answer)
            setTurns(_turns.value + turn)
            Settings.setLastAnswer(context, answer)
            ClaudeWidget.refresh(context.applicationContext)
            turn
        } catch (e: Exception) {
            // Fehlgeschlagene Frage wieder aus dem API-Verlauf entfernen, damit er gültig bleibt.
            synchronized(history) {
                val i = history.indexOfLast { it is Item.User }
                if (i >= 0) while (history.size > i) history.removeAt(history.size - 1)
            }
            errorTurn(describe(e))
        } finally {
            _busy.value = false
        }
    }

    private fun errorTurn(msg: String): Turn {
        val t = Turn(false, msg, isError = true)
        setTurns(_turns.value + t)
        return t
    }

    private fun describe(e: Exception): String = when (e) {
        is UnauthorizedException -> "Der API-Schlüssel ist ungültig. Bitte prüfe ihn in der App."
        is PermissionDeniedException -> "Der API-Schlüssel hat keinen Zugriff auf dieses Modell."
        is RateLimitException -> "Zu viele Anfragen gerade. Versuch es gleich noch einmal."
        is OverloadedException -> "Claude ist gerade überlastet. Versuch es gleich noch einmal."
        is AnthropicServiceException -> "Fehler vom Server, Code ${e.statusCode()}."
        is AnthropicIoException -> "Keine Verbindung zum Internet oder zu Claude."
        else -> "Unerwarteter Fehler: ${e.message ?: e.javaClass.simpleName}"
    }

    private fun buildParams(context: Context, items: List<Item>): MessageCreateParams {
        val b = MessageCreateParams.builder()
            .model(Settings.model(context))
            .maxTokens(8000L)
            .system(systemPrompt())
            // Niedriger Aufwand = schnelle Antworten, wichtig für Sprachdialoge.
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
        val model = Settings.model(context)
        if (model in FALLBACK_MODELS) {
            // Falls eine Anfrage von Sicherheitsfiltern abgelehnt wird, beantwortet ein
            // anderes Modell sie automatisch (serverseitiger Fallback).
            b.addBeta("server-side-fallback-2026-07-01").fallbacksDefault()
        }
        if (HomeAssistant.isActive(context)) haTools().forEach { b.addTool(it) }
        if (Settings.webSearch(context)) {
            if (model.startsWith("claude-haiku")) {
                b.addTool(BetaWebSearchTool20250305.builder().maxUses(3L).build())
            } else {
                b.addTool(BetaWebSearchTool20260209.builder().maxUses(3L).build())
            }
        }
        for (item in items) {
            when (item) {
                is Item.User -> b.addUserMessage(item.text)
                is Item.Assistant -> b.addMessage(item.message)
                is Item.AssistantText -> b.addAssistantMessage(item.text)
                is Item.ToolResults -> b.addUserMessageOfContentBlockParams(item.results.map {
                    BetaContentBlockParam.ofToolResult(
                        BetaToolResultBlockParam.builder()
                            .toolUseId(it.toolUseId).content(it.content).isError(it.isError).build()
                    )
                })
            }
        }
        return b.build()
    }

    /** Läuft im IO-Thread. */
    private fun callClaude(context: Context, key: String): String {
        val api = clientFor(key)
        val parts = mutableListOf<String>()
        // Schleife für Werkzeuge (Home Assistant) und pause_turn (lange Websuchen).
        repeat(10) {
            val items = synchronized(history) {
                trimHistory()
                history.toList()
            }
            val msg = api.beta().messages().create(buildParams(context, items))
            synchronized(history) { history.add(Item.Assistant(msg)) }

            val stop = msg.stopReason().orElse(null)
            if (stop == BetaStopReason.REFUSAL) return "Dazu kann ich leider nichts sagen."
            val text = msg.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("").trim()
            if (text.isNotEmpty()) parts.add(text)

            when (stop) {
                BetaStopReason.TOOL_USE -> {
                    val results = msg.content().mapNotNull { it.toolUse().orElse(null) }.map { call ->
                        val input = try {
                            call._input().convert(Map::class.java) ?: emptyMap<String, Any?>()
                        } catch (_: Exception) { emptyMap<String, Any?>() }
                        val (out, isError) = try {
                            runTool(context, call.name(), input)
                        } catch (e: Exception) {
                            "Fehler: ${e.message}" to true
                        }
                        ToolResult(call.id(), out, isError)
                    }
                    synchronized(history) { history.add(Item.ToolResults(results)) }
                }
                BetaStopReason.PAUSE_TURN -> Unit
                else -> return clean(parts.joinToString(" ")).ifBlank { "Darauf habe ich keine Antwort." }
            }
        }
        return clean(parts.joinToString(" ")).ifBlank { "Das hat zu lange gedauert." }
    }

    /** Ältere Nachrichten verwerfen; der Verlauf muss mit einer Nutzernachricht beginnen. */
    private fun trimHistory() {
        while (history.size > MAX_HISTORY_ITEMS) history.removeAt(0)
        while (history.isNotEmpty() && history.first() !is Item.User) history.removeAt(0)
    }

    /** Falls doch Markdown durchrutscht: für die Sprachausgabe entfernen. */
    private fun clean(s: String): String = s
        .replace(Regex("\\*\\*|__|`|#+ "), "")
        .replace(Regex("(?m)^\\s*[-*•]\\s+"), "")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}
