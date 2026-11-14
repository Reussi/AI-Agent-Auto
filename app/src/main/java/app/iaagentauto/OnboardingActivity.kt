package app.iaagentauto

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Einrichtungsassistent – erscheint nur beim ersten Start (bzw. nach dem Abmelden).
 * Jeder Schritt wird über große Tipp-Knöpfe erledigt.
 */
class OnboardingActivity : AppCompatActivity() {

    private enum class Page(val viewId: Int) {
        WELCOME(R.id.pageWelcome),
        METHOD(R.id.pageMethod),
        GUIDE(R.id.pageGuide),
        KEY(R.id.pageKey),
        MODEL(R.id.pageModel),
        PERMISSIONS(R.id.pagePermissions),
        DONE(R.id.pageDone),
    }

    /** Gewählte Anmeldeart bei Anthropic. */
    private enum class Method { GOOGLE, EMAIL, NEW }

    private data class Step(val title: String, val text: String)

    private data class ModelChoice(val viewId: Int, val modelId: String)

    private val modelChoices = listOf(
        ModelChoice(R.id.optFast, "claude-haiku-5-5"),
        ModelChoice(R.id.optBalanced, "claude-sonnet-5-5"),
        ModelChoice(R.id.optBest, "claude-opus-5-5"),
    )

    private var page = Page.WELCOME
    private var method: Method? = null
    private var consoleOpened = false
    private var verifying = false
    private var connected = false
    private var lastTriedKey: String? = null

    private lateinit var primary: MaterialButton
    private lateinit var secondary: MaterialButton
    private lateinit var back: MaterialButton
    private lateinit var stepLabel: TextView
    private lateinit var stepProgress: LinearProgressIndicator
    private lateinit var keyStatusText: TextView
    private lateinit var keyStatusIcon: ImageView
    private lateinit var keyProgress: CircularProgressIndicator
    private lateinit var manualLayout: TextInputLayout
    private lateinit var manualKey: TextInputEditText

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { renderPermissions() }
    private val multiPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { renderPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        val header = findViewById<View>(R.id.header)
        val footer = findViewById<View>(R.id.footer)
        val footerBottom = footer.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            header.updatePadding(top = bars.top)
            footer.updatePadding(bottom = footerBottom + maxOf(bars.bottom, ime.bottom))
            insets
        }

        primary = findViewById(R.id.primary)
        secondary = findViewById(R.id.secondary)
        back = findViewById(R.id.back)
        stepLabel = findViewById(R.id.stepLabel)
        stepProgress = findViewById(R.id.stepProgress)
        keyStatusText = findViewById(R.id.keyStatusText)
        keyStatusIcon = findViewById(R.id.keyStatusIcon)
        keyProgress = findViewById(R.id.keyProgress)
        manualLayout = findViewById(R.id.manualKeyLayout)
        manualKey = findViewById(R.id.manualKey)

        fillTexts()

        primary.setOnClickListener { onPrimary() }
        secondary.setOnClickListener { onSecondary() }
        back.setOnClickListener { goBack() }
        findViewById<MaterialButton>(R.id.reopenConsole).setOnClickListener { tryClipboard(userInitiated = true) }
        findViewById<MaterialButton>(R.id.openMail).setOnClickListener { openMailApp() }
        findViewById<View>(R.id.methodGoogle).setOnClickListener { chooseMethod(Method.GOOGLE) }
        findViewById<View>(R.id.methodEmail).setOnClickListener { chooseMethod(Method.EMAIL) }
        findViewById<View>(R.id.methodNew).setOnClickListener { chooseMethod(Method.NEW) }
        findViewById<View>(R.id.methodKey).setOnClickListener { method = null; show(Page.KEY) }
        manualKey.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { onPrimary(); true } else false
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page == Page.WELCOME) finish() else goBack()
            }
        })

        modelChoices.forEach { choice ->
            findViewById<View>(choice.viewId).setOnClickListener {
                Settings.setModel(this, choice.modelId)
                renderModels()
            }
        }
        findViewById<View>(R.id.optMic).setOnClickListener {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        findViewById<View>(R.id.optNotif).setOnClickListener {
            val perms = buildList {
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (perms.isNotEmpty()) multiPermissionLauncher.launch(perms.toTypedArray())
        }

        savedInstanceState?.let {
            page = Page.entries[it.getInt("page")]
            method = it.getInt("method", -1).takeIf { m -> m >= 0 }?.let { m -> Method.entries[m] }
            method?.let { m -> renderGuide(m) }
            consoleOpened = it.getBoolean("console")
            connected = Settings.apiKey(this).isNotEmpty()
        }
        show(page, animate = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("page", page.ordinal)
        outState.putBoolean("console", consoleOpened)
        outState.putInt("method", method?.ordinal ?: -1)
    }

    override fun onResume() {
        super.onResume()
        // Zurück aus der Anthropic Console → direkt zum nächsten Schritt.
        if (page == Page.GUIDE && consoleOpened) show(Page.KEY)
        if (page == Page.PERMISSIONS) renderPermissions()
    }

    /** Zwischenablage ist erst lesbar, wenn das Fenster den Fokus hat. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && page == Page.KEY && !connected && !verifying) tryClipboard(userInitiated = false)
    }

    // ------------------------------------------------------------- Seiten

    private fun show(target: Page, animate: Boolean = true) {
        page = target
        Page.entries.forEach { p ->
            val v = findViewById<View>(p.viewId)
            val visible = p == target
            if (visible && animate && !v.isVisible) {
                v.alpha = 0f; v.translationX = 40f
                v.isVisible = true
                v.animate().alpha(1f).translationX(0f).setDuration(220).start()
            } else {
                v.isVisible = visible
            }
        }
        hideKeyboard()

        val step = target.ordinal
        back.visibility = if (target == Page.WELCOME || target == Page.DONE) View.INVISIBLE else View.VISIBLE
        stepLabel.text = if (target == Page.WELCOME) "" else if (target == Page.DONE) "Fertig" else "Schritt $step von 5"
        stepProgress.isVisible = target != Page.WELCOME
        stepProgress.setProgressCompat(step, animate)

        when (target) {
            Page.WELCOME -> footer("Einrichtung starten", null)
            Page.METHOD -> { footer(null, null) }
            Page.GUIDE -> footer(
                if (method == Method.NEW) "Registrierung öffnen" else "Anmeldeseite öffnen",
                "Andere Anmeldeart wählen", R.drawable.ic_open
            )
            Page.KEY -> renderKeyPage()
            Page.MODEL -> { renderModels(); footer("Weiter", null) }
            Page.PERMISSIONS -> { renderPermissions(); footer("Weiter", "Später erledigen") }
            Page.DONE -> {
                footer("Los geht’s", null)
                findViewById<View>(R.id.doneIcon).apply {
                    scaleX = 0.3f; scaleY = 0.3f; alpha = 0f
                    animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(400).start()
                }
            }
        }
    }

    private fun footer(primaryText: String?, secondaryText: String?, icon: Int? = null) {
        primary.visibility = if (primaryText == null) View.INVISIBLE else View.VISIBLE
        primary.text = primaryText.orEmpty()
        primary.icon = icon?.let { ContextCompat.getDrawable(this, it) }
        primary.isEnabled = true
        secondary.text = secondaryText.orEmpty()
        secondary.visibility = if (secondaryText == null) View.INVISIBLE else View.VISIBLE
    }

    private fun goBack() {
        val prev = when (page) {
            Page.WELCOME, Page.DONE -> return
            Page.METHOD -> Page.WELCOME
            Page.GUIDE -> Page.METHOD
            Page.KEY -> if (method == null) Page.METHOD else Page.GUIDE
            Page.MODEL -> Page.KEY
            Page.PERMISSIONS -> Page.MODEL
        }
        if (prev == Page.GUIDE || prev == Page.METHOD) consoleOpened = false
        show(prev)
    }

    private fun onPrimary() {
        when (page) {
            Page.WELCOME -> show(Page.METHOD)
            Page.METHOD -> Unit
            Page.GUIDE -> openConsole()
            Page.KEY -> when {
                connected -> show(Page.MODEL)
                manualLayout.isVisible -> verify(manualKey.text?.toString().orEmpty())
                else -> openConsole()
            }
            Page.MODEL -> show(Page.PERMISSIONS)
            Page.PERMISSIONS -> show(Page.DONE)
            Page.DONE -> {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }
    }

    private fun onSecondary() {
        when (page) {
            Page.GUIDE -> show(Page.METHOD)
            Page.KEY -> {
                manualLayout.isVisible = !manualLayout.isVisible
                if (manualLayout.isVisible) {
                    manualKey.requestFocus()
                    getSystemService(InputMethodManager::class.java).showSoftInput(manualKey, 0)
                }
                renderKeyPage()
            }
            Page.PERMISSIONS -> show(Page.DONE)
            else -> Unit
        }
    }

    // ------------------------------------------------------------- Konto / Schlüssel

    private fun openConsole() {
        consoleOpened = true
        val url = Uri.parse("https://console.anthropic.com/settings/keys")
        val colors = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(ContextCompat.getColor(this, R.color.background))
            .build()
        try {
            CustomTabsIntent.Builder().setDefaultColorSchemeParams(colors).setShowTitle(true).build()
                .launchUrl(this, url)
        } catch (_: Exception) {
            try { startActivity(Intent(Intent.ACTION_VIEW, url)) } catch (_: Exception) {}
        }
    }

    private fun renderKeyPage() {
        if (connected) {
            setKeyStatus("Verbunden! Claude ist bereit.", R.drawable.ic_check_circle, R.color.success)
            footer("Weiter", null)
            manualLayout.isVisible = false
            return
        }
        footer(
            if (manualLayout.isVisible) "Verbinden" else "Schlüssel erstellen",
            if (manualLayout.isVisible) "Doch automatisch übernehmen" else "Schlüssel von Hand eingeben",
            if (manualLayout.isVisible) null else R.drawable.ic_open,
        )
        primary.isEnabled = !verifying
    }

    private fun setKeyStatus(text: String, icon: Int?, tint: Int) {
        keyStatusText.text = text
        keyProgress.isVisible = icon == null
        keyStatusIcon.isVisible = icon != null
        icon?.let {
            keyStatusIcon.setImageResource(it)
            keyStatusIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, tint))
        }
    }

    private fun tryClipboard(userInitiated: Boolean) {
        val text = try {
            getSystemService(ClipboardManager::class.java).primaryClip
                ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        } catch (_: Exception) { null }
        val key = text?.let { KEY_REGEX.find(it)?.value }
        when {
            key != null && (userInitiated || key != lastTriedKey) -> verify(key)
            key == null && userInitiated -> setKeyStatus(
                "Noch kein Schlüssel kopiert. Tippe in der Console beim neuen Schlüssel auf „Copy“ und komm zurück.",
                R.drawable.ic_paste, R.color.error
            )
        }
    }

    private fun verify(raw: String) {
        val key = KEY_REGEX.find(raw)?.value ?: raw.trim()
        if (!key.startsWith("sk-ant-")) {
            setKeyStatus("Das ist kein gültiger Schlüssel – er beginnt immer mit „sk-ant-“.", R.drawable.ic_key, R.color.error)
            return
        }
        lastTriedKey = key
        verifying = true
        setKeyStatus("Verbinde mit deinem Konto …", null, 0)
        renderKeyPage()

        lifecycleScope.launch {
            val result = Conversation.verifyKey(key, Settings.model(this@OnboardingActivity))
            verifying = false
            when (result) {
                is Conversation.KeyCheck.Ok -> {
                    Settings.setApiKey(this@OnboardingActivity, key)
                    connected = true
                    clearClipboardIfKey(key)
                    renderKeyPage()
                    delay(900)
                    if (page == Page.KEY) show(Page.MODEL)
                }
                is Conversation.KeyCheck.Failed -> {
                    setKeyStatus(result.message, R.drawable.ic_key, R.color.error)
                    renderKeyPage()
                }
            }
        }
    }

    /** Schlüssel aus der Zwischenablage entfernen, damit andere Apps ihn nicht lesen. */
    private fun clearClipboardIfKey(key: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        try {
            val cm = getSystemService(ClipboardManager::class.java)
            if (cm.primaryClip?.getItemAt(0)?.text?.toString()?.contains(key) == true) cm.clearPrimaryClip()
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------- Anmeldeart & Anleitung

    private fun chooseMethod(m: Method) {
        method = m
        renderGuide(m)
        show(Page.GUIDE)
    }

    private fun renderGuide(m: Method) {
        val (icon, title) = when (m) {
            Method.GOOGLE -> R.drawable.ic_google_g to "So meldest du dich mit Google an"
            Method.EMAIL -> R.drawable.ic_mail to "So meldest du dich per E-Mail an"
            Method.NEW -> R.drawable.ic_person_add to "So erstellst du dein Konto"
        }
        findViewById<ImageView>(R.id.guideIcon).setImageResource(icon)
        findViewById<TextView>(R.id.guideTitle).text = title
        findViewById<View>(R.id.openMail).isVisible = m == Method.EMAIL

        val back = Step("Oben links auf ✕ tippen",
            "Damit kommst du zurück in die App. Hier geht es direkt mit dem letzten Schritt weiter.")
        val steps = when (m) {
            Method.GOOGLE -> listOf(
                Step("„Anmeldeseite öffnen“ tippen", "Die Anmeldeseite von Anthropic öffnet sich in einem Fenster innerhalb der App."),
                Step("„Continue with Google“ tippen", "Auf Deutsch heißt der Knopf ggf. „Mit Google fortfahren“."),
                Step("Dein Google-Konto auswählen", "Tippe auf dein Konto in der Liste – oder gib E-Mail-Adresse und Passwort ein – und bestätige mit „Weiter“."),
                Step("Nur beim ersten Mal: Angaben ergänzen", "Namen eingeben und die Nutzungsbedingungen bestätigen. Fragen zur Nutzung kannst du z. B. mit „Personal“ (privat) beantworten."),
                back,
            )
            Method.EMAIL -> listOf(
                Step("„Anmeldeseite öffnen“ tippen", "Die Anmeldeseite von Anthropic öffnet sich in einem Fenster innerhalb der App."),
                Step("E-Mail-Adresse eingeben", "In das Feld „Email“ eintragen und „Continue with email“ tippen."),
                Step("Postfach öffnen", "Anthropic schickt dir eine E-Mail mit einem Anmeldelink oder Code. Tippe dafür auf „E-Mail-App öffnen“ (unten)."),
                Step("Link antippen oder Code eingeben", "Einen Code trägst du auf der Anmeldeseite ein. Ein Link öffnet sich evtl. im normalen Browser – das ist in Ordnung: Erstelle den Schlüssel dann einfach dort."),
                Step("Nur beim ersten Mal: Angaben ergänzen", "Namen eingeben und die Nutzungsbedingungen bestätigen."),
                back,
            )
            Method.NEW -> listOf(
                Step("„Registrierung öffnen“ tippen", "Die Seite von Anthropic öffnet sich in einem Fenster innerhalb der App."),
                Step("Google oder E-Mail wählen", "„Continue with Google“ ist am schnellsten. Alternativ E-Mail-Adresse eingeben und den Link aus der E-Mail antippen."),
                Step("Angaben ergänzen", "Namen eingeben und die Nutzungsbedingungen bestätigen."),
                Step("Guthaben aufladen", "Im Menü „Billing“ (Abrechnung) eine Zahlungsart hinterlegen und Guthaben kaufen, z. B. 5 US-Dollar. Ohne Guthaben kann Claude nicht antworten."),
                back,
            )
        }
        fillSteps(R.id.guideSteps, steps)
        findViewById<TextView>(R.id.guideTip).text = when (m) {
            Method.GOOGLE -> "Tipp: Du bleibst angemeldet. Im nächsten Schritt öffnet sich die Seite deshalb ohne erneute Anmeldung."
            Method.EMAIL -> "Keine E-Mail bekommen? Warte eine Minute und schau im Spam-Ordner nach."
            Method.NEW -> "Ein Gespräch im Auto kostet meist nur wenige Cent. In der Abrechnung kannst du ein monatliches Limit festlegen."
        }
    }

    private fun fillSteps(containerId: Int, steps: List<Step>) {
        val container = findViewById<android.widget.LinearLayout>(containerId)
        container.removeAllViews()
        steps.forEachIndexed { i, step ->
            val row = layoutInflater.inflate(R.layout.item_numbered, container, false)
            row.findViewById<TextView>(R.id.num).text = (i + 1).toString()
            row.findViewById<TextView>(R.id.numTitle).text = step.title
            row.findViewById<TextView>(R.id.numText).text = step.text
            container.addView(row)
        }
    }

    private fun openMailApp() {
        try {
            startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_EMAIL)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            setKeyStatus("Keine E-Mail-App gefunden.", R.drawable.ic_mail, R.color.error)
        }
    }

    // ------------------------------------------------------------- Auswahl-Karten

    private fun renderModels() {
        val current = Settings.model(this)
        modelChoices.forEach { setSelected(it.viewId, it.modelId == current) }
    }

    private fun renderPermissions() {
        val mic = has(Manifest.permission.RECORD_AUDIO)
        val notif = (Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS)) &&
            (Build.VERSION.SDK_INT < 31 || has(Manifest.permission.BLUETOOTH_CONNECT))
        setOption(R.id.optMic, R.drawable.ic_mic, "Mikrofon",
            if (mic) "Erlaubt" else "Damit Claude dich im Auto hört – zum Erlauben tippen")
        setOption(R.id.optNotif, R.drawable.ic_notifications, "Benachrichtigungen & Auto-Erkennung",
            if (notif) "Erlaubt" else "Damit Claude sich meldet, sobald du ins Auto steigst – zum Erlauben tippen")
        setSelected(R.id.optMic, mic)
        setSelected(R.id.optNotif, notif)
        if (page == Page.PERMISSIONS) primary.text = if (mic && notif) "Weiter" else "Weiter ohne"
        if (page == Page.PERMISSIONS && mic && notif) secondary.visibility = View.INVISIBLE
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun setOption(id: Int, icon: Int, title: String, text: String) {
        val card = findViewById<View>(id)
        card.findViewById<ImageView>(R.id.optionIcon).setImageResource(icon)
        card.findViewById<TextView>(R.id.optionTitle).text = title
        card.findViewById<TextView>(R.id.optionText).text = text
    }

    private fun setSelected(id: Int, selected: Boolean) {
        val card = findViewById<MaterialCardView>(id)
        val primaryColor = ContextCompat.getColor(this, R.color.primary)
        card.strokeColor = if (selected) primaryColor else ContextCompat.getColor(this, R.color.outline)
        card.strokeWidth = ((if (selected) 2.5f else 1f) * resources.displayMetrics.density).toInt()
        val check = card.findViewById<ImageView>(R.id.optionCheck)
        check.setImageResource(if (selected) R.drawable.ic_check_circle else R.drawable.ic_radio_off)
        check.imageTintList = ColorStateList.valueOf(
            if (selected) primaryColor else ContextCompat.getColor(this, R.color.outline)
        )
        card.isSelected = selected
    }

    // ------------------------------------------------------------- Texte

    private fun fillTexts() {
        feature(R.id.welcome1, R.drawable.ic_car, "Im Auto sprechen", "„Sprechen“ tippen, Frage stellen, Antwort hören.")
        feature(R.id.welcome2, R.drawable.ic_chat, "Meldet sich von selbst", "Steigst du ins Auto, schickt Claude eine Nachricht – einfach per Sprache antworten.")
        feature(R.id.welcome3, R.drawable.ic_widgets, "Widget fürs Handy", "Mit einem Tipp auf dem Startbildschirm fragen.")

        setOption(R.id.methodGoogle, R.drawable.ic_google_g, "Mit Google",
            "Am schnellsten – mit deinem Google-Konto anmelden")
        setOption(R.id.methodEmail, R.drawable.ic_mail, "Mit E-Mail-Adresse",
            "Du bekommst einen Anmeldelink per E-Mail")
        setOption(R.id.methodNew, R.drawable.ic_person_add, "Ich habe noch kein Konto",
            "Kostenlos registrieren – wir zeigen dir jeden Schritt")
        setOption(R.id.methodKey, R.drawable.ic_key, "Ich habe schon einen Schlüssel",
            "Direkt zum Verbinden springen")
        listOf(R.id.methodGoogle, R.id.methodEmail, R.id.methodNew, R.id.methodKey).forEach {
            findViewById<View>(it).findViewById<ImageView>(R.id.optionCheck).apply {
                setImageResource(R.drawable.ic_chevron)
                imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this@OnboardingActivity, R.color.primary))
            }
        }

        fillSteps(R.id.keySteps, listOf(
            Step("„Schlüssel erstellen“ tippen", "Unten auf den Knopf tippen. Die Seite „API Keys“ öffnet sich – du bist dort schon angemeldet."),
            Step("„Create Key“ tippen", "Der Knopf ist oben rechts auf der Seite (auf Deutsch ggf. „Schlüssel erstellen“)."),
            Step("Namen eingeben", "Zum Beispiel „Auto“, dann mit „Add“ bzw. „Create Key“ bestätigen."),
            Step("„Copy“ tippen", "Der Schlüssel wird nur ein einziges Mal angezeigt – deshalb gleich kopieren."),
            Step("Oben links auf ✕ tippen", "Du bist zurück in der App. Sie erkennt den Schlüssel automatisch und verbindet sich."),
        ))

        setOption(R.id.optFast, R.drawable.ic_bolt, "Schnell", "Claude Haiku 5.5 – sehr kurze Wartezeit, am günstigsten")
        setOption(R.id.optBalanced, R.drawable.ic_balance, "Ausgewogen", "Claude Sonnet 5.5 – schnell und klug")
        setOption(R.id.optBest, R.drawable.ic_star, "Beste Antworten", "Claude Opus 5.5 – am gründlichsten (empfohlen)")

        feature(R.id.done1, R.drawable.ic_car, "Im Auto", "Claude meldet sich per Nachricht – „Antworten“ sagen und Frage stellen.")
        feature(R.id.done2, R.drawable.ic_widgets, "Zu Hause", "Widget auf den Startbildschirm legen oder in der App tippen.")
    }

    private fun feature(id: Int, icon: Int, title: String, text: String) {
        val v = findViewById<View>(id)
        v.findViewById<ImageView>(R.id.featureIcon).setImageResource(icon)
        v.findViewById<TextView>(R.id.featureTitle).text = title
        v.findViewById<TextView>(R.id.featureText).text = text
    }

    private fun hideKeyboard() {
        currentFocus?.let {
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0)
            it.clearFocus()
        }
    }

    companion object {
        private val KEY_REGEX = Regex("sk-ant-[A-Za-z0-9_\\-]{20,}")
    }
}
