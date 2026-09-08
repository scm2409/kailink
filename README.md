# KaiLink

KaiLink ist ein Proof-of-Concept (PoC) eines Android-Messengers auf Basis von
[Matrix](https://matrix.org/). Phase 1 liefert ein **vollständig offline
baubares Fundament**: Domänenschicht mit Nahtstellen, Anmeldung mit
Sitzungswiederherstellung, Raumliste, Chronik mit Senden, eine
Push-Abstraktion mit Zustandsautomat sowie geprüfte JVM-Verifikation.
Benachrichtigungen sind später über [UnifiedPush](https://unifiedpush.org/)
angebunden (ohne Google/FCM).

> **Status: Phase 1 (PoC).** Der Kanaldienst läuft in Phase 1 als
> dokumentierte In-Memory-Simulation (`InMemoryChannelClient`); der echte
> Matrix-Adapter (`matrix-rust-sdk`) ist als Phase-2-Artefakt vorbereitet
> (Referenz unter `app/src/phase2/`, siehe
> [`docs/architecture.md`](docs/architecture.md)).

## Kernentscheidungen

| Entscheidung | Begründung |
| --- | --- |
| Phase 1: In-Memory-Kanal hinter `ChannelClient` | Die Bedingungen dieser Umgebung erlauben keinen Netzwerkzugriff; die Schnittstelle bleibt identisch, der Adapter ist austauschbar (G5) |
| Phase 2: echtes `matrix-rust-sdk` | Offizielle Rust-Implementierung inkl. E2EE (Olm/Megolm); Referenzadapter liegt unter `app/src/phase2/` |
| Push-Abstraktion (`PushController` + `PushRegistrationTrigger`) | Zustandsautomat ist JVM-getestet; Phase 2 tauscht nur den Trigger gegen den UnifiedPush-Connector |
| Phase 1: Android-Framework-Views | Jetpack-Compose-Artefakte sind im lokalen Offline-Cache nicht vorhanden; die UI-Schicht ist so geschnitten, dass Phase 2 auf Compose umstellen kann |
| Kein Google/FCM-Code im Repo | Projektverfassung, siehe [`docs/project-constitution.md`](docs/project-constitution.md) |

## Offline-Build (diese Umgebung)

Voraussetzungen: JDK 21 (bereit über `mise.toml`), Android SDK unter
`/home/dev/android-sdk` (eingetragen in `local.properties`, nicht im Repo),
Gradle Wrapper 9.1.0, AGP 8.13.2, Kotlin 2.2.21 — alle Artefakte ausschließlich
aus dem lokalen Gradle-Cache:

```bash
./gradlew --offline :app:phase1Checks :app:assembleDebug   # JVM-Prüfungen + Debug-APK
./gradlew --offline check                                  # inkl. Lint (abortOnError=false)
```

Ergebnis: `app/build/outputs/apk/debug/app-debug.apk`
(`at.d71.kailink`, versionName `0.1.0-phase1`, minSdk 28, targetSdk 36).
Die JVM-Prüfungen laufen über die eigene Aufgabe `phase1Checks`, weil JUnit
im lokalen Cache nicht verfügbar ist; der Prüfbericht liegt unter
`app/build/reports/phase1-checks.txt` (39/39 bestanden, Stand 2026-09-08,
siehe [`docs/features/verification.md`](docs/features/verification.md)).

## Projektstruktur

```
app/src/main/kotlin/at/d71/kailink/
├── domain/          Reine Domänenlogik ohne Android-Abhängigkeiten (JVM-testbar)
│   ├── model/       Session, Room, Message
│   ├── ChannelClient.kt    Kanal-Nahtstelle (In-Memory-Adapter implementiert sie)
│   ├── SessionStore.kt     Sitzungspersistenz-Vertrag
│   ├── TimelineReducer.kt  Reduziert Chronik-Patches auf den UI-Zustand
│   ├── push/        Push-Nahtstellen (PushState, PushRegistrationTrigger)
│   └── speech/      Sprach-Nahtstellen (STT/TTS, No-Op-Implementierung)
├── data/
│   ├── channel/     InMemoryChannelClient (Phase-1-Kanalsimulation)
│   ├── push/        PushController (Zustandsautomat), SimulatedPushTrigger
│   └── session/     FileSessionStore (Properties-Datei, App-privat)
├── di/              AppGraph (manuelle Verdrahtung)
├── ui/              Framework-Views + ViewModels (Login, Raumliste, Chronik)
└── MainActivity.kt  Eine Activity, drei umgeschaltete Screens
app/src/phase2/      Nicht kompilierte Referenz: MatrixSdkChannelClient
app/src/test/        Phase-1-JVM-Prüfungen (phase1Checks)
docs/                Deutsche Dokumentation (Verfassung, Architektur, Protokolle)
```

## Dokumentation

- [`docs/project-constitution.md`](docs/project-constitution.md) — Grundsätze G1–G9
- [`docs/architecture.md`](docs/architecture.md) — Schichten, Datenfluss, Phase-2-Migrationspfad
- [`docs/features/verification.md`](docs/features/verification.md) — beobachtete Ergebnisse (G8)
- [`docs/manualtest-protokoll.md`](docs/manualtest-protokoll.md) — manuelle Geräteprüfungen (V4)
- `docs/features/*.md` — Feature-Dokumente (Anmeldung, Raumliste/Chronik, Push, Sprache, E2EE-Ausblick)
