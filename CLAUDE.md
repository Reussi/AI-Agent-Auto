# Projekt-Notizen für Claude – IA Agent Auto

Diese Datei hält Ziel, Entscheidungen und Rahmenbedingungen des Projekts fest.
Bitte bei jeder Änderung beachten und bei neuen Entscheidungen aktualisieren.
Kommunikation mit dem Projektinhaber (GitHub: Reussi) auf **Deutsch**.

## Grundregel
- Informationen, die der Inhaber zum Projekt gibt (Ziele, Beschreibungen, Hinweise), fließen **immer
  sowohl in GitHub (README, store-beschreibung.md, diese Datei) als auch in die App selbst** ein
  (Texte in `app/src/main/res/values/strings.xml`, Hilfe, Einstellungen „Über die App“, Willkommensseite).

## Ziel und Motivation
- Der Inhaber nutzt Claude bereits, um sein Smart Home (Home Assistant) zu steuern.
  **Unterwegs im Auto war das nicht möglich** – diese App schließt die Lücke:
  Claude per Sprache im Auto nutzen und optional das Zuhause über Home Assistant steuern.
- Die App soll später **kostenpflichtig im Google Play Store** angeboten werden; eine iPhone-/CarPlay-Version ist
  angedacht (eigene Swift-App, CarPlay-Kategorie „Voice-based conversational apps“ ab iOS 26.4, Apple-Freigabe nötig).

## Produkt
- Name: **IA Agent Auto**, Paket/applicationId `app.iaagentauto`, Version **0.1.0** (Entwicklungsversion).
  versionCode muss bei jedem Build steigen.
- **Claude ist Voraussetzung**: eigenes Anthropic-Konto mit API-Zugang + API-Schlüssel (claude.ai-Abo reicht nicht).
  Dieser Hinweis steht im Store-Text, in der README, auf der Willkommensseite und in den Einstellungen.
- **Home Assistant ist eine optionale Zusatzfunktion**: Einmalkauf **3,00 €**, Play-Billing-Produkt-ID
  `home_assistant_addon` (Billing Library 8.3). Debug-Builds sind automatisch freigeschaltet.
- Modell standardmäßig `claude-opus-5-5`, effort `low`, serverseitige Fallbacks; Websuche-Tool; HA-Werkzeuge
  `ha_find_entities`, `ha_get_state`, `ha_call_service` mit Freigaben pro Bereich (Schlösser/Tore/Alarm aus).

## Android Auto – wichtige Erkenntnis
- Android Auto (ab 17.7, getestet mit 18.1 auf Samsung A55/Android 16) zeigt **Template-Apps (Car App Library)
  nur bei Installation aus Google Play** an; „Unbekannte Quellen“ hilft dafür nicht.
- Der Inhaber möchte **keine Hilfs-Apps** (UnknownAuto/Shizuku) installieren.
- Deshalb läuft die App in Android Auto aktuell als **Medien-App** (`media/ClaudeMediaService.kt`,
  automotive_app_desc: `media`): Mikrofon/Suche antippen → Frage → Antwort wird abgespielt. Im Auto noch ungetestet.
- Der Messaging-Weg (Benachrichtigungen) ist abgeschaltet – der Inhaber möchte nicht von Claude angeschrieben werden.
- Fremde KI-Chat-Apps sind laut Fachpresse in Android Auto im Play Store derzeit nicht zugelassen (nur Gemini).

## Marken und Rechtliches
- „Claude“, „Android Auto“, „Home Assistant“ und Google-Logos **nicht im App-Namen und nicht als Logo**.
  Im Store-Text sachlich erwähnen, mit Hinweis „unabhängiges Produkt, keine Verbindung zu …“.
  „Android Auto“ nicht in Werbetexten (Google verlangt Genehmigung).
- App-Symbol: Anthropic-Orange (#D97757), weißes „C“, weißes Mikrofon, goldenes „G“ (Verlauf),
  Haus auf Home-Assistant-Blau (#18BCF2). Keine echten Fremdlogos, kein mehrfarbiges Google-„G“.
- Lizenz: **Source Available („nur ansehen“)**, siehe LICENSE. Rechtliche Prüfung durch Anwalt empfohlen.
- Echte Verknüpfung mit dem claude.ai-Chatverlauf ist technisch nicht möglich (keine API);
  vorhanden: Übergabe an die Claude-App, Erinnerung nach der Fahrt. Idee: Ablage in Google Drive.

## Entwicklung
- Bauen: `./gradlew assembleDebug lintDebug` (JDK 17, Android SDK 35, Kotlin 2.2.10, AGP 8.7.3).
- Commits: Autor `Reussi <70755996+Reussi@users.noreply.github.com>`, Texte auf Deutsch.
- Niemals einchecken: API-Schlüssel, HA-Token, Keystores, `local.properties`, APKs.
- Offen: eigener Signaturschlüssel + AAB für Play Store, Datenschutzerklärung/Impressum,
  Test der Medien-App im Auto, ggf. Google-Drive-Ablage, iOS/CarPlay-Version.
