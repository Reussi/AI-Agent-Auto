package app.iaagentauto

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/** Hilfe: So wird die App benutzt – im Auto, am Handy, mit Widget und Smart Home. */
class HelpActivity : AppCompatActivity() {

    private data class Step(val title: String, val text: String)
    private data class Section(val icon: Int, val title: String, val subtitle: String, val steps: List<Step>)

    private val sections = listOf(
        Section(R.drawable.ic_car, "Im Auto", "Fragen stellen, während du fährst", listOf(
            Step("„IA Agent Auto“ öffnen", "In Android Auto auf das App-Symbol tippen. Die App erscheint dort wie ein Musikdienst."),
            Step("Mikrofon bzw. Lupe antippen", "Oben auf dem Bildschirm. Dann deine Frage sprechen."),
            Step("Antwort anhören", "Die Antwort wird sofort über die Autolautsprecher abgespielt, Musik wird leiser."),
            Step("Nächste Frage", "Wieder aufs Mikrofon tippen. Frühere Antworten findest du in der Liste und kannst sie erneut anhören."),
            Step("Per Sprachbefehl (optional)", "„Hey Google, spiele …, auf IA Agent Auto“ – statt „…“ deine Frage."),
        )),
        Section(R.drawable.ic_chat, "Am Handy", "Tippen oder sprechen", listOf(
            Step("Frage eintippen", "Unten auf der Startseite ins Feld „Frage an Claude“ schreiben und auf den Pfeil tippen."),
            Step("Per Sprache fragen", "Auf den runden Mikrofon-Knopf daneben tippen und sprechen."),
            Step("Neues Gespräch", "Auf „Neu“ tippen – Claude vergisst dann das bisherige Gespräch."),
            Step("In Claude übernehmen", "Mit „In Claude“ schickst du das Gespräch an die Claude-App und kannst es dort weiterführen."),
        )),
        Section(R.drawable.ic_widgets, "Widget", "Mit einem Tipp vom Startbildschirm fragen", listOf(
            Step("Widget hinzufügen", "Lange auf eine freie Stelle des Startbildschirms drücken → „Widgets“ → „IA Agent Auto“."),
            Step("Antippen und sprechen", "Die Antwort erscheint in einem kleinen Fenster und wird vorgelesen."),
        )),
        Section(R.drawable.ic_home, "Smart Home", "Zusatzfunktion mit Home Assistant", listOf(
            Step("Freischalten", "Einstellungen → Home Assistant → „Freischalten“ (einmaliger Kauf)."),
            Step("Verbinden", "Im Heim-WLAN den Zugangsschlüssel per „QR-Code scannen“ vom PC-Bildschirm übernehmen – oder einfügen. Die Adresse findet die App automatisch."),
            Step("Bereiche festlegen", "Unter „Was darf Claude steuern?“ auswählen, was geschaltet werden darf."),
            Step("Beispiele", "„Mach das Licht in der Küche an.“ · „Stell die Heizung auf 21 Grad.“ · „Ist ein Fenster offen?“"),
        )),
        Section(R.drawable.ic_help, "Wenn etwas nicht klappt", "Häufige Probleme", listOf(
            Step("App fehlt in Android Auto", "Android-Auto-Einstellungen → „Launcher anpassen“ → „IA Agent Auto“ anhaken. Danach Handy neu mit dem Auto verbinden."),
            Step("Claude antwortet nicht", "Internetverbindung prüfen. Unter Einstellungen → Claude prüfen, ob du angemeldet bist und bei Anthropic Guthaben vorhanden ist."),
            Step("Sprache wird nicht erkannt", "Unter Einstellungen → Allgemein das Mikrofon erlauben. Deutlich und nach dem Signalton sprechen."),
            Step("Home Assistant nicht erreichbar", "Unterwegs muss Home Assistant aus dem Internet erreichbar sein (z. B. Home Assistant Cloud). Eine Adresse wie 192.168… funktioniert nur im Heim-WLAN."),
        )),
        Section(R.drawable.ic_key, "Konto & Kosten", "Was du brauchst", listOf(
            Step("Anthropic-Konto mit API-Zugang", "IA Agent Auto funktioniert nur mit Claude. Nötig ist ein eigener API-Schlüssel von Anthropic – ein claude.ai-Abo (Pro/Max) allein reicht nicht."),
            Step("Kosten", "Die Claude-Nutzung rechnet Anthropic nach Verbrauch direkt mit dir ab, meist wenige Cent pro Gespräch. In der Anthropic Console kannst du ein Limit festlegen."),
            Step("Datenschutz", "Schlüssel und Verlauf bleiben auf deinem Handy. Fragen gehen zur Beantwortung an Anthropic, Smart-Home-Befehle direkt an dein Home Assistant."),
        )),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        val appBar = findViewById<AppBarLayout>(R.id.appBar)
        val content = findViewById<LinearLayout>(R.id.content)
        val basePadding = content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            appBar.updatePadding(top = bars.top, left = bars.left, right = bars.right)
            content.updatePadding(bottom = basePadding + bars.bottom)
            insets
        }

        content.addView(storyView())
        sections.forEach { section -> content.addView(sectionView(section, first = false)) }

        // Am Ende: direkt zu den Einstellungen
        content.addView((layoutInflater.inflate(R.layout.item_tonal_button, content, false) as MaterialButton).apply {
            text = "Zu den Einstellungen"
            setIconResource(R.drawable.ic_settings)
            setOnClickListener { startActivity(Intent(this@HelpActivity, SettingsActivity::class.java)) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
    }

    /** Erste Karte: warum es die App gibt. */
    private fun storyView(): View {
        val card = MaterialCardView(this, null, com.google.android.material.R.attr.materialCardViewFilledStyle).apply {
            setCardBackgroundColor(getColor(R.color.primary_container))
            radius = dp(20).toFloat()
            setContentPadding(dp(18), dp(16), dp(18), dp(16))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        head.addView(ImageView(this).apply { setImageResource(R.drawable.app_logo) }, LinearLayout.LayoutParams(dp(40), dp(40)))
        head.addView(TextView(this).apply {
            text = getString(R.string.app_story_title)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.on_primary_container))
            setPadding(dp(12), 0, 0, 0)
        })
        col.addView(head)
        col.addView(TextView(this).apply {
            text = getString(R.string.app_story)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(getColor(R.color.on_primary_container))
            setPadding(0, dp(10), 0, 0)
        })
        card.addView(col)
        return card.also {
            it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4); bottomMargin = dp(4)
            }
        }
    }

    private fun sectionView(section: Section, first: Boolean): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Überschrift wie in den Einstellungen
        val header = layoutInflater.inflate(R.layout.item_feature, wrap, false)
        header.findViewById<ImageView>(R.id.featureIcon).setImageResource(section.icon)
        header.findViewById<TextView>(R.id.featureTitle).apply {
            text = section.title
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        header.findViewById<TextView>(R.id.featureText).text = section.subtitle
        (header.layoutParams as? ViewGroup.MarginLayoutParams)
        wrap.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = if (first) dp(4) else dp(16)
            bottomMargin = dp(4)
        })

        // Karte mit nummerierten Schritten
        val card = MaterialCardView(this, null, com.google.android.material.R.attr.materialCardViewFilledStyle).apply {
            setCardBackgroundColor(getColor(R.color.card))
            radius = dp(20).toFloat()
            setContentPadding(dp(18), dp(10), dp(18), dp(10))
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        section.steps.forEachIndexed { i, step ->
            val row = layoutInflater.inflate(R.layout.item_numbered, list, false)
            row.findViewById<TextView>(R.id.num).text = (i + 1).toString()
            row.findViewById<TextView>(R.id.numTitle).text = step.title
            row.findViewById<TextView>(R.id.numText).text = step.text
            list.addView(row)
        }
        card.addView(list)
        wrap.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(6)
        })
        return wrap
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
