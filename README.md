# KaiLink

KaiLink ist ein Proof-of-Concept (PoC) eines Android-Messengers auf Basis von
[Matrix](https://matrix.org/). Phase 1 lieferte ein **vollständig offline
baubares Fundament**: Domänenschicht mit Nahtstellen, Anmeldung mit
Sitzungswiederherstellung, Raumliste, Chronik mit Senden, eine
Push-Abstraktion mit Zustandsautomat sowie geprüfte JVM-Verifikation.

> **Status: Phase 2 (Stand 2026-09-08).** Der echte Matrix-Adapter
> (`MatrixSdkChannelClient`, matrix-rust-sdk 26.09.08) und die
> [UnifiedPush](https://unifiedpush.org/)-Anbindung (`UnifiedPushRegistrar` +
> `KaiLinkPushReceiver`, connector 3.3.5, ohne Google/FCM) sind kompiliert
> und in `AppGraph` verdrahtet; `testDebugUnitTest assembleDebug` ist grün.
> Laufzeit gegen echten Homeserver/Distributor ist noch nicht beobachtet
> (kein Gerät in dieser Umgebung) — siehe
> [`docs/architecture.md`](docs/architecture.md).

## Kernentscheidungen

| Entscheidung | Begründung |
| --- | --- |
| Phase 1: In-Memory-Kanal hinter `ChannelClient` | Die Bedingungen der ersten Phase erlaubten keinen Netzwerkzugriff; die Schnittstelle blieb identisch, der Adapter war austauschbar (G5) |
| Phase 2: echtes `matrix-rust-sdk` | Offizielle Rust-Implementierung inkl. E2EE (Olm/Megolm); `MatrixSdkChannelClient` ist seit 2026-09-08 im Build und verdrahtet |
| Push-Abstraktion (`PushController` + `PushRegistrationTrigger`) | Zustandsautomat ist JVM-getestet; Phase 2 tauscht nur den Trigger gegen den UnifiedPush-Connector |
| Android-Framework-Views | Jetpack-Compose-Artefakte sind im lokalen Offline-Cache nicht vorhanden; die UI-Schicht ist so geschnitten, dass Phase 2 auf Compose umstellen kann |
| Kein Google/FCM-Code im Repo | Projektverfassung, siehe [`docs/project-constitution.md`](docs/project-constitution.md) |

## Build (diese Umgebung)

Voraussetzungen: JDK 21 (bereit über `mise.toml`), Android SDK unter
`/home/dev/android-sdk` (eingetragen in `local.properties`, nicht im Repo),
Gradle Wrapper 9.1.0, AGP 8.13.2, Kotlin 2.2.21.

```bash
./gradlew testDebugUnitTest assembleDebug   # JVM-Prüfungen (JUnit) + Debug-APK
./gradlew check                             # inkl. Lint (abortOnError=false)
```

Beobachtet am 2026-09-08: `BUILD SUCCESSFUL`; JUnit-Bericht `tests="1"
failures="0"` (der Test führt alle 39 Prüfgruppen-Checks aus), Prüfbericht
unter `app/build/reports/phase1-checks.txt` (39/39 bestanden), siehe
[`docs/features/verification.md`](docs/features/verification.md).
Ergebnis: `app/build/outputs/apk/debug/app-debug.apk`
(`org.box44.kailink`, versionName `0.2.0-phase1`, minSdk 28, targetSdk 36,
inkl. `libmatrix_sdk_ffi.so` des matrix-rust-sdk).

## Projektstruktur

```
app/src/main/kotlin/org/box44/kailink/
├── domain/          Reine Domänenlogik ohne Android-Abhängigkeiten (JVM-testbar)
│   ├── model/       Session, Room, Message
│   ├── ChannelClient.kt    Kanal-Nahtstelle (Adapter implementieren sie)
│   ├── SessionStore.kt     Sitzungspersistenz-Vertrag
│   ├── TimelineReducer.kt  Reduziert Chronik-Patches auf den UI-Zustand
│   ├── push/        Push-Nahtstellen (PushState, PushRegistrationTrigger)
│   └── speech/      Sprach-Nahtstellen (STT/TTS, No-Op-Implementierung)
├── data/
│   ├── channel/     InMemoryChannelClient (JVM-Referenz für Prüfungen)
│   ├── matrix/      MatrixSdkChannelClient (matrix-rust-sdk, produktiv)
│   ├── push/        PushController, UnifiedPushRegistrar, KaiLinkPushReceiver,
│   │                SimulatedPushTrigger (JVM-Referenz)
│   └── session/     FileSessionStore (Properties-Datei, App-privat)
├── di/              AppGraph (manuelle Verdrahtung: Matrix + UnifiedPush)
├── ui/              Framework-Views + ViewModels (Login, Raumliste, Chronik)
└── MainActivity.kt  Eine Activity, drei umgeschaltete Screens
app/src/phase2/      Quellpfad des Matrix-Adapters (in main eingebunden)
app/src/test/        JVM-Prüfungen (JUnit: AllChecksTest → 39 Checks)
docs/                Deutsche Dokumentation (Verfassung, Architektur, Protokolle)
```

## Dokumentation

- [`docs/project-constitution.md`](docs/project-constitution.md) — Grundsätze G1–G9
- [`docs/architecture.md`](docs/architecture.md) — Schichten, Datenfluss, Phase-2-Migrationspfad
- [`docs/features/verification.md`](docs/features/verification.md) — beobachtete Ergebnisse (G8)
- [`docs/manualtest-protokoll.md`](docs/manualtest-protokoll.md) — manuelle Geräteprüfungen (V4)
- `docs/features/*.md` — Feature-Dokumente (Anmeldung, Raumliste/Chronik, Push, Sprache, E2EE-Ausblick)
