# Projektverfassung (Project Constitution)

Diese Verfassung bindet alle Beiträge an KaiLink. Sie ist bewusst kurz und
prüfbar: Jede Regel ist im Repository verifizierbar oder im manuellen
Testprotokoll abprüfbar.

## 1. Zweck

KaiLink ist ein **Proof-of-Concept** und zeigt:

1. Matrix-Login und Sitzungswiederherstellung auf Android ohne Google-Dienste,
2. Raumliste, Chronik (Timeline) und Senden von Nachrichten,
3. Ende-zu-Ende-Verschlüsselung (E2EE) mit dem echten `matrix-rust-sdk`,
4. Push-Zustellung über UnifiedPush (kein FCM),
5. klar definierte **Nahtstellen** für spätere Sprachein-/ausgabe.

**Nicht Zweck:** Produktionsreife, Attraktivität der UI, Chatverläufe über
Gerätewechsel hinweg, Multi-Account, Voice-/Videoanrufe.

## 2. Harte Grundsätze

- **G1 – Keine Google-Dienste.** Kein FCM, keine Play Services, keine
  Google-Maven-spezifischen Lizenzbeschänkungen im Code. Push läuft
  ausschließlich über UnifiedPush.
- **G2 – Echtes Matrix-SDK.** Für Matrix-Protokoll, Krypto und Sync wird das
  offizielle `matrix-rust-sdk` (Android-Bindings) benutzt. Eigene
  Protokoll-/Krypto-Implementierungen sind verboten.
  *Phase-1-Klausel (2026-09-08):* solange die SDK-Artefakte im lokalen
  Offline-Cache nicht verfügbar sind, läuft die App gegen die dokumentierte
  In-Memory-Simulation (`InMemoryChannelClient`, Grundsatz G5) — ohne jede
  eigene Protokoll-/Krypto-Logik; G2 tritt mit Einbindung des
  Referenzadapters (`app/src/phase2/`) in Kraft.
- **G3 – Verifizierte Abhängigkeiten.** Eine Abhängigkeit wird erst
  eingebunden, wenn Versionsnummer und benutzte API-Oberfläche lokal
  (Maven-Metadaten und/oder Entpacken der Artefakte) geprüft wurden.
  Andernfalls: dokumentierter Adapter/Stub, im jeweiligen Feature-Dokument
  markiert.
- **G4 – Domäne ohne Android.** `domain/` darf keine Android-Klassen
  importieren; sie muss auf der JVM testbar sein.
- **G5 – Nahtstellen statt Bluff.** Für noch nicht implementierte Fähigkeiten
  (z. B. Sprache) gibt es Schnittstellen plus dokumentierte
  No-Op-Implementierungen. Keine Schein-Features.
- **G6 – Keine globalen Installationen.** Werkzeuge werden per-User
  (`~/.local`, `mise.toml`, Projektordner) bereitgestellt. Keine
  systemweiten Paketinstallationen.
- **G7 – Keine Zugangsdaten im Repo.** Homeserver-URLs, Zugangsdaten und
  Tokens erscheinen höchstens lokal zur Laufzeit; das Repo enthält nur
  Platzhalter.
- **G8 – Verifikation vor Behauptung.** Was behauptet wird, wurde beobachtet.
  Ergebnisse landen mit Datum und Ist-Ausgabe in
  [`docs/features/verification.md`](features/verification.md). Gerätelose
  Prüfungen werden explizit als solche markiert.
- **G9 – Sprache der Dokumentation ist Deutsch.** Code, Identifikatoren und
  Commit-Messages sind englisch.

## 3. Artefakt-Benennung

Debug-Artefakte werden benannt als
`kailink-poc-v<version>-debug.apk` (aktuell: `0.1.0`). Frühere Versionen
werden nie stillschweigend überschrieben.

## 4. Verifikationsstufen

| Stufe | Mittel | Ohne Gerät möglich |
| --- | --- | --- |
| V1 | JVM-Prüfungen (Phase 1: `phase1Checks`, solange JUnit offline nicht verfügbar; seit 2026-09-08: JUnit-Test `AllChecksTest` in `testDebugUnitTest`) | ja |
| V2 | Kompilierung + `assembleDebug` | ja |
| V3 | Strukturprüfung (Manifest, Badging, APK-Inhalt) | ja |
| V4 | Verhalten auf Gerät/Emulator | nein → manuelles Protokoll |

Ein PoC-Ergebnis gilt erst als "nachgewiesen", wenn die für das Feature
niedrigste mögliche Stufe bestanden ist; alles darüber hinaus wird offen
als Restrisiko benannt (siehe `docs/features/verification.md`).
