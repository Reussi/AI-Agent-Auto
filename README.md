# IA Agent Auto (Android)

**Dein KI-Sprachassistent fürs Auto mit Claude – optional mit Home Assistant.**
*Your AI voice assistant for the car, powered by your Claude API key – with optional Home Assistant add-on.* ([English below](#english))

> ⚠️ **Entwicklungsversion 0.1.0** – noch nicht im Play Store, Funktionen können sich ändern.

> **Wichtig:** IA Agent Auto funktioniert nur mit Claude. Du brauchst ein Anthropic-Konto mit
> Zugang zur Claude API und einen eigenen API-Schlüssel ([console.anthropic.com](https://console.anthropic.com)).
> Ein claude.ai-Abo (Pro/Max) allein reicht nicht aus.

## Funktionen

- 🎙️ **Fragen per Sprache** – im Auto, am Handy oder über das Widget
- 🔊 **Antworten werden vorgelesen**, Musik wird währenddessen leiser
- 🚗 **Im Auto**: erscheint in Android Auto als Medien-App (Mikrofon/Suche antippen, Frage sprechen)
- 🏠 **Smart Home** (Zusatzfunktion): Home Assistant per Sprache steuern – mit Freigaben pro Bereich
- 🔎 **Websuche** für aktuelle Infos (Wetter, Nachrichten, Öffnungszeiten)
- 📷 **QR-Code-Scan** für den Home-Assistant-Zugangsschlüssel
- 🧭 **Geführte Einrichtung**, Hilfe-Bildschirm, Gesprächsverlauf auf dem Gerät

## Selbst bauen

Voraussetzungen: JDK 17, Android SDK 35 (z. B. über Android Studio).

```bash
./gradlew assembleDebug
```

Die APK liegt danach unter `app/build/outputs/apk/debug/`.
Debug-Builds schalten die Zusatzfunktion Home Assistant automatisch frei.

## Projektaufbau

| Pfad | Inhalt |
|---|---|
| `app/src/main/java/app/iaagentauto/Conversation.kt` | Gespräch mit Claude (Anthropic Java SDK), Werkzeuge für Home Assistant |
| `…/HomeAssistant.kt` | Home-Assistant-REST-API, Freigaben pro Bereich |
| `…/media/ClaudeMediaService.kt` | Android Auto als Medien-App (Spracheingabe → Antwort als Audio) |
| `…/car/` | Template-Bildschirm für Android Auto (nur bei Installation aus Google Play sichtbar) |
| `…/Billing.kt` | Kauf der Zusatzfunktion über Google Play Billing |
| `…/OnboardingActivity.kt`, `SettingsActivity.kt`, `HelpActivity.kt` | Einrichtung, Einstellungen, Hilfe |
| `…/widget/` | Startbildschirm-Widget |

## Datenschutz

API-Schlüssel, Home-Assistant-Token und Gesprächsverlauf bleiben auf dem Gerät.
Fragen werden zur Beantwortung an Anthropic gesendet, Smart-Home-Befehle direkt an das eigene Home Assistant.

## Lizenz

**Source Available – kein Open Source.** Der Code darf angesehen, geprüft und privat ausprobiert,
aber ohne Erlaubnis nicht weiterverbreitet, verkauft oder in andere Produkte übernommen werden.
Details: [LICENSE](LICENSE). Beiträge sind willkommen, siehe [CONTRIBUTING.md](CONTRIBUTING.md).

Claude ist eine Marke von Anthropic, PBC. Home Assistant ist eine Marke der Open Home Foundation.
Android ist eine Marke von Google LLC. Dieses Projekt ist unabhängig und steht in keiner Verbindung zu diesen Unternehmen.

---

## English

IA Agent Auto is a voice assistant for the car. Ask questions by voice and hear the answers through
your car speakers. It works with **Claude** (requires your own Anthropic API key – a claude.ai
Pro/Max subscription alone is not enough) and optionally controls your **Home Assistant**.

- Build: `./gradlew assembleDebug` (JDK 17, Android SDK 35)
- Status: development version 0.1.0
- License: **source-available, not open source** – view and private use only, see [LICENSE](LICENSE)
- Contributions welcome via issues and pull requests, see [CONTRIBUTING.md](CONTRIBUTING.md)

Claude is a trademark of Anthropic, PBC. Home Assistant is a trademark of the Open Home Foundation.
Android is a trademark of Google LLC. This project is independent and not affiliated with them.
