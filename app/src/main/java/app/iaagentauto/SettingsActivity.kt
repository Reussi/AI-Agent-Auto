package app.iaagentauto

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import app.iaagentauto.messaging.ClaudeNotifier
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Alle Einstellungen der App an einem Ort. */
class SettingsActivity : AppCompatActivity() {

    companion object {
        /** Home-Assistant-Token sind JWTs: drei Base64url-Teile, durch Punkte getrennt. */
        private val TOKEN_REGEX = Regex("[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}")
    }

    private data class ModelOption(val id: String, val label: String)

    private val models = listOf(
        ModelOption("claude-opus-5-5", "Claude Opus 5.5 · beste Antworten"),
        ModelOption("claude-sonnet-5-5", "Claude Sonnet 5.5 · ausgewogen"),
        ModelOption("claude-haiku-5-5", "Claude Haiku 5.5 · am schnellsten"),
    )

    private lateinit var root: View

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            refreshPermissions()
            if (result.values.any { !it }) {
                Snackbar.make(root, "Nicht erteilt – du kannst das in den Systemeinstellungen ändern.", Snackbar.LENGTH_LONG)
                    .setAction("Öffnen") { openAppSettings() }
                    .show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        root = findViewById(R.id.scroll)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        applyInsets()

        setupAccount()
        setupSettings()
        setupHomeAssistant()
        setupPermissions()
        setupAbout()
    }

    private fun applyInsets() {
        val appBar = findViewById<AppBarLayout>(R.id.appBar)
        val content = findViewById<View>(R.id.content)
        val basePadding = content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            appBar.updatePadding(top = bars.top, left = bars.left, right = bars.right)
            root.updatePadding(left = bars.left, right = bars.right)
            content.updatePadding(bottom = basePadding + maxOf(bars.bottom, ime.bottom))
            insets
        }
    }

    private fun setChip(chip: Chip, text: String, ok: Boolean) {
        chip.text = text
        chip.chipBackgroundColor = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (ok) R.color.success_container else R.color.bubble_claude))
        chip.setTextColor(ContextCompat.getColor(this, if (ok) R.color.success else R.color.on_surface_variant))
        chip.chipStrokeWidth = 0f
    }

    // ---------------------------------------------------------------- Konto

    private fun setupAccount() {
        findViewById<TextView>(R.id.keyMasked).text = mask(Settings.apiKey(this))
        setChip(findViewById(R.id.statusChip), "Angemeldet", true)
        findViewById<MaterialButton>(R.id.logout).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Abmelden?")
                .setMessage("Der API-Schlüssel wird von diesem Handy gelöscht. Danach kannst du dich jederzeit neu verbinden.")
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Abmelden") { _, _ ->
                    Settings.setApiKey(this, "")
                    Conversation.reset()
                    ClaudeNotifier.cancel(this)
                    startActivity(Intent(this, OnboardingActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    finish()
                }
                .show()
        }
    }

    private fun mask(key: String) =
        if (key.length > 14) key.take(7) + "••••••" + key.takeLast(4) else "••••••"

    // ---------------------------------------------------------------- Claude

    private fun setupSettings() {
        val modelView = findViewById<MaterialAutoCompleteTextView>(R.id.model)
        val current = Settings.model(this)
        val options = if (models.any { it.id == current }) models else models + ModelOption(current, current)
        modelView.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, options.map { it.label }))
        modelView.setText(options.first { it.id == current }.label, false)
        modelView.setOnItemClickListener { _, _, position, _ ->
            Settings.setModel(this, options[position].id)
            Snackbar.make(root, "Modell gewechselt", Snackbar.LENGTH_SHORT).show()
        }
        bindSwitch(R.id.autoListen, Settings.autoListen(this)) { Settings.setAutoListen(this, it) }
        bindSwitch(R.id.autoRead, Settings.autoRead(this)) { Settings.setAutoRead(this, it) }
        bindSwitch(R.id.webSearch, Settings.webSearch(this)) { Settings.setWebSearch(this, it) }
    }

    private fun bindSwitch(id: Int, value: Boolean, onChange: (Boolean) -> Unit) {
        findViewById<MaterialSwitch>(id).apply {
            isChecked = value
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
    }

    // ---------------------------------------------------------------- Home Assistant

    private lateinit var haUrl: TextInputEditText
    private lateinit var haToken: TextInputEditText
    private lateinit var haStatus: TextView

    private fun setupHomeAssistant() {
        haUrl = findViewById(R.id.haUrl)
        haToken = findViewById(R.id.haToken)
        haStatus = findViewById(R.id.haStatus)
        haUrl.setText(Settings.haUrl(this))
        haToken.setText(Settings.haToken(this))

        findViewById<MaterialButton>(R.id.haScan).setOnClickListener { scanQr() }
        findViewById<MaterialButton>(R.id.haPaste).setOnClickListener {
            val text = try {
                getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            } catch (_: Exception) { null }
            if (text.isNullOrBlank()) {
                Snackbar.make(root, "Die Zwischenablage ist leer.", Snackbar.LENGTH_SHORT).show()
            } else {
                haToken.setText(text.trim())
            }
        }

        val steps = findViewById<LinearLayout>(R.id.haHelpSteps)
        listOf(
            "Home Assistant öffnen" to "Im Browser oder in der Home-Assistant-App, am besten am PC.",
            "Auf dein Profil tippen" to "Ganz unten links in der Seitenleiste (dein Name bzw. Anfangsbuchstabe).",
            "Reiter „Sicherheit“ wählen" to "Oben auf der Profilseite.",
            "„Token erstellen“ tippen" to "Ganz unten bei „Langlebige Zugriffstoken“. Als Namen z. B. „IA Agent Auto“ eingeben.",
            "QR-Code scannen oder kopieren" to "Home Assistant zeigt den neuen Token nur einmal an – meist auch als QR-Code. " +
                "Tippe hier auf „QR-Code scannen“ und richte die Kamera auf den Bildschirm am PC. " +
                "Ohne QR-Code: Token kopieren, hierher wechseln und auf „Einfügen“ tippen.",
            "Adresse (optional)" to "Leer lassen: Die App findet Home Assistant im WLAN automatisch und übernimmt die " +
                "Internet-Adresse aus Home Assistant für unterwegs. Dafür bei der Einrichtung im Heim-WLAN sein. " +
                "Alternativ selbst eintragen, z. B. https://…ui.nabu.casa (Home Assistant Cloud).",
        ).forEachIndexed { i, (title, text) ->
            val row = layoutInflater.inflate(R.layout.item_numbered, steps, false)
            row.findViewById<TextView>(R.id.num).text = (i + 1).toString()
            row.findViewById<TextView>(R.id.numTitle).text = title
            row.findViewById<TextView>(R.id.numText).text = text
            steps.addView(row)
        }
        findViewById<MaterialButton>(R.id.haHelp).setOnClickListener { steps.isVisible = !steps.isVisible }

        findViewById<MaterialButton>(R.id.haConnect).setOnClickListener { connectHomeAssistant() }
        findViewById<MaterialButton>(R.id.haDisconnect).setOnClickListener {
            Settings.setHa(this, "", "")
            haToken.setText("")
            haStatus.isVisible = false
            renderHa()
        }

        // Bereiche als Schalter
        val groups = findViewById<LinearLayout>(R.id.haGroups)
        val enabled = (Settings.haGroups(this)
            ?: HomeAssistant.GROUPS.filter { it.defaultOn }.map { it.key }.toSet()).toMutableSet()
        HomeAssistant.GROUPS.forEach { g ->
            val sw = MaterialSwitch(this).apply {
                text = "${g.label}\n${g.hint}"
                textSize = 15f
                isChecked = g.key in enabled
                setPadding(0, 8, 0, 8)
                setOnCheckedChangeListener { _, on ->
                    if (on) enabled.add(g.key) else enabled.remove(g.key)
                    Settings.setHaGroups(this@SettingsActivity, enabled.toSet())
                }
            }
            groups.addView(sw, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        renderHa()
        setupPurchase()
    }

    // ---------------------------------------------------------------- Kauf der Zusatzfunktion

    private fun setupPurchase() {
        val buy = findViewById<MaterialButton>(R.id.haBuy)
        val status = findViewById<TextView>(R.id.haBuyStatus)
        buy.setOnClickListener {
            if (!Billing.buy(this)) {
                status.isVisible = true
                status.text = "Der Kauf konnte nicht gestartet werden. Bitte später erneut versuchen."
            }
        }
        findViewById<MaterialButton>(R.id.haRestore).setOnClickListener {
            Billing.refresh()
            Snackbar.make(root, "Käufe werden bei Google Play geprüft …", Snackbar.LENGTH_SHORT).show()
        }
        lifecycleScope.launch {
            Billing.state.collect { state ->
                val unlocked = state is Billing.State.Unlocked || Billing.isUnlocked(this@SettingsActivity)
                findViewById<View>(R.id.haLocked).isVisible = !unlocked
                findViewById<View>(R.id.haForm).isVisible = unlocked
                when (state) {
                    is Billing.State.Available -> {
                        buy.isEnabled = true
                        buy.text = "Für ${state.price} freischalten"
                        status.isVisible = false
                    }
                    Billing.State.Loading -> {
                        buy.isEnabled = false
                        buy.text = "Preis wird geladen …"
                    }
                    Billing.State.Pending -> {
                        buy.isEnabled = false
                        buy.text = "Zahlung wird bearbeitet …"
                        status.isVisible = true
                        status.text = "Sobald Google Play die Zahlung bestätigt, wird die Funktion automatisch freigeschaltet."
                    }
                    is Billing.State.Unavailable -> {
                        buy.isEnabled = false
                        buy.text = "Freischalten"
                        status.isVisible = true
                        status.text = state.reason
                    }
                    Billing.State.Unlocked -> renderHa()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        Billing.refresh()
    }

    /** Scannt einen QR-Code (Zugangsschlüssel oder Adresse) mit Googles Scanner – ohne Kamera-Berechtigung. */
    private fun scanQr() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { code -> handleScanned(code.rawValue.orEmpty()) }
            .addOnFailureListener {
                Snackbar.make(root, "Scanner nicht verfügbar. Bitte Google-Play-Dienste aktualisieren oder „Einfügen“ nutzen.",
                    Snackbar.LENGTH_LONG).show()
            }
    }

    private fun handleScanned(raw: String) {
        val text = raw.trim()
        val token = TOKEN_REGEX.find(text)?.value
        val url = Regex("^https?://\\S+$").find(text)?.value
        when {
            token != null -> {
                haToken.setText(token)
                Snackbar.make(root, "Zugangsschlüssel übernommen ✓", Snackbar.LENGTH_SHORT).show()
                // Gleich verbinden – die Adresse wird bei Bedarf automatisch im WLAN gesucht.
                connectHomeAssistant()
            }
            url != null -> {
                haUrl.setText(HomeAssistant.normalizeUrl(url))
                Snackbar.make(root, "Adresse übernommen ✓", Snackbar.LENGTH_SHORT).show()
            }
            else -> Snackbar.make(root, "Dieser QR-Code enthält keinen Home-Assistant-Zugangsschlüssel.", Snackbar.LENGTH_LONG).show()
        }
    }

    private fun renderHa() {
        val configured = HomeAssistant.isConfigured(this)
        val unlocked = Billing.isUnlocked(this)
        setChip(findViewById(R.id.haChip),
            when {
                !unlocked -> "Zusatzfunktion"
                configured -> "Verbunden"
                else -> "Nicht eingerichtet"
            }, unlocked && configured)
        findViewById<View>(R.id.haDisconnect).isVisible = configured
        findViewById<MaterialButton>(R.id.haConnect).text = if (configured) "Erneut prüfen & speichern" else "Verbinden"
    }

    private fun connectHomeAssistant() {
        val tokenLayout = findViewById<TextInputLayout>(R.id.haTokenLayout)
        val typedUrl = HomeAssistant.normalizeUrl(haUrl.text?.toString().orEmpty())
        val token = haToken.text?.toString()?.trim().orEmpty()
        tokenLayout.error = if (token.isEmpty()) "Bitte QR-Code scannen oder den Zugangsschlüssel einfügen." else null
        if (token.isEmpty()) return

        val progress = findViewById<LinearProgressIndicator>(R.id.haProgress)
        val button = findViewById<MaterialButton>(R.id.haConnect)
        progress.isVisible = true
        button.isEnabled = false
        haStatus.isVisible = true
        haStatus.setTextColor(ContextCompat.getColor(this, R.color.on_surface_variant))
        haStatus.text = if (typedUrl.isEmpty()) "Suche Home Assistant in deinem WLAN …" else "Verbinde …"

        lifecycleScope.launch {
            // 1) Kandidaten sammeln: eingetragene Adresse, sonst automatisch im WLAN suchen
            val candidates = mutableListOf<String>()
            var announcedExternal: String? = null
            if (typedUrl.isNotEmpty()) candidates.add(typedUrl)
            if (typedUrl.isEmpty()) {
                val found = HaDiscovery.discover(this@SettingsActivity)
                found.forEach { candidates.addAll(it.localUrls) }
                announcedExternal = found.firstNotNullOfOrNull { it.externalUrl }
                if (announcedExternal != null) candidates.add(0, announcedExternal)
            }

            // 2) Erste funktionierende Adresse finden
            var working: String? = null
            var lastError: String? = null
            withContext(Dispatchers.IO) {
                for (u in candidates.distinct()) {
                    val err = HomeAssistant.test(u, token)
                    if (err == null) { working = u; break } else lastError = err
                }
            }

            progress.isVisible = false
            button.isEnabled = true
            val ok = working
            if (ok == null) {
                haStatus.setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.error))
                haStatus.text = when {
                    candidates.isEmpty() ->
                        "Home Assistant wurde im WLAN nicht gefunden. Bist du im selben WLAN? " +
                            "Alternativ die Adresse oben eintragen (z. B. https://…ui.nabu.casa)."
                    else -> "Verbindung fehlgeschlagen: $lastError"
                }
                renderHa()
                return@launch
            }

            // 3) Adressen aus Home Assistant lesen: extern für unterwegs, intern als Ersatz zu Hause
            val (external, internal) = withContext(Dispatchers.IO) { HomeAssistant.fetchUrls(ok, token) }
            val ext = external ?: announcedExternal
            val isLocal = Regex("^https?://(\\[|10\\.|192\\.168\\.|172\\.(1[6-9]|2\\d|3[01])\\.|[^/]*\\.local)").containsMatchIn(ok)
            val primary = when {
                ext != null && ext != ok && withContext(Dispatchers.IO) { HomeAssistant.test(ext, token) } == null -> ext
                else -> ok
            }
            val local = listOfNotNull(if (isLocal) ok else null, internal).firstOrNull { it != primary }.orEmpty()

            Settings.setHa(this@SettingsActivity, primary, token, local)
            haUrl.setText(primary)
            haStatus.setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.success))
            val primaryIsLocal = primary == ok && isLocal
            haStatus.text = buildString {
                append("✓ Verbunden.\n")
                if (!primaryIsLocal) append("Unterwegs über: $primary\n")
                if (local.isNotEmpty()) append("Zu Hause über: $local\n")
                if (primaryIsLocal) {
                    haStatus.setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.on_surface_variant))
                    append("Zu Hause über: $primary\n")
                    append("⚠ Für unterwegs ist keine Internet-Adresse eingerichtet. In Home Assistant unter " +
                        "Einstellungen → System → Netzwerk eine externe Adresse (oder Home Assistant Cloud) einrichten " +
                        "und hier erneut verbinden.")
                } else {
                    append("Frag Claude im Auto z. B.: „Mach das Licht im Wohnzimmer an.“")
                }
            }
            renderHa()
        }
    }

    // ---------------------------------------------------------------- Berechtigungen

    private fun setupPermissions() {
        bindPermission(R.id.permMic, R.drawable.ic_mic, "Mikrofon")
        bindPermission(R.id.permNotif, R.drawable.ic_notifications, "Benachrichtigungen & Auto-Erkennung")
    }

    private fun bindPermission(id: Int, icon: Int, title: String) {
        val row = findViewById<View>(id)
        row.findViewById<ImageView>(R.id.permIcon).setImageResource(icon)
        row.findViewById<TextView>(R.id.permTitle).text = title
        row.findViewById<MaterialButton>(R.id.permButton).setOnClickListener { requestPermissions() }
    }

    private fun refreshPermissions() {
        renderPermission(R.id.permMic, has(Manifest.permission.RECORD_AUDIO), "Für die Spracheingabe")
        val notif = (Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS)) &&
            (Build.VERSION.SDK_INT < 31 || has(Manifest.permission.BLUETOOTH_CONNECT))
        renderPermission(R.id.permNotif, notif, "Claude meldet sich, sobald du ins Auto steigst")
    }

    private fun renderPermission(id: Int, granted: Boolean, purpose: String) {
        val row = findViewById<View>(id)
        val status = row.findViewById<TextView>(R.id.permStatus)
        status.text = if (granted) "Erlaubt" else purpose
        status.setTextColor(ContextCompat.getColor(this, if (granted) R.color.success else R.color.on_surface_variant))
        row.findViewById<MaterialButton>(R.id.permButton).isVisible = !granted
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        val perms = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun openAppSettings() {
        startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    // ---------------------------------------------------------------- Über

    private fun setupAbout() {
        findViewById<TextView>(R.id.version).text =
            "IA Agent Auto · Version ${BuildConfig.VERSION_NAME} (Entwicklungsversion)\n" + InstallSource.describe(this)
        findViewById<MaterialButton>(R.id.clearHistory).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Verlauf löschen?")
                .setMessage("Alle bisherigen Fragen und Antworten werden von diesem Handy gelöscht.")
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Löschen") { _, _ ->
                    Conversation.reset()
                    ClaudeNotifier.cancel(this)
                    Snackbar.make(root, "Verlauf gelöscht", Snackbar.LENGTH_SHORT).show()
                }
                .show()
        }
    }
}
