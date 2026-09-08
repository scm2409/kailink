# KaiLink

KaiLink ist ein Proof-of-Concept (PoC) eines Android-Messengers auf Basis von
[Matrix](https://matrix.org/) mit durchgehender Ende-zu-Ende-Verschlüsselung (E2EE),
ohne Google-Dienste und ohne FCM. Benachrichtigungen laufen über
[UnifiedPush](https://unifiedpush.org/).

> **Status: PoC.** Der Code ist als Machbarkeitsnachweis gedacht, nicht als
> produktionsreife Anwendung. Erkenntnisse und offene Punkte stehen in
> [`docs/features/verification.md`](docs/features/verification.md).

## Kernentscheidungen

| Entscheidung | Begründung |
| --- | --- |
| Echtes `matrix-rust-sdk` (Android-Bindings) | Offizielle, rust-basierte Matrix-Implementierung inkl. E2EE (Olm/Megolm), sliding sync, Send-Queue |
| UnifiedPush statt FCM | Keine Abhängigkeit von Google Play Services; dezentraler Push über selbst wählbare Distributoren (z. B. ntfy, NextPush) |
| Jetpack Compose | Von Android offiziell empfohlener UI-Stack |
| Kein Google/FCM-Code im Repo | Projektverfassung, siehe [`docs/project-constitution.md`](docs/project-constitution.md) |

## Projektstruktur

```
app/src/main/kotlin/at/d71/kailink/
├── domain/        Reine Domänenlogik ohne Android-Abhängigkeiten (testbar auf JVM)
│   ├── model/     Session, Room, Message
│   ├── ChannelClient.kt   Kanal-Abstraktion (Matrix-Adapter implementiert sie)
│   ├── SessionStore.kt    Sitzungspersistenz-Vertrag
│   ├── speech/            Nahtstellen für Sprachein-/ausgabe (nur Schnittstellen)
│   └── TimelineReducer.kt Reduziert Chronik-Zwischenstände auf den UI-Zustand
├── data/          Adapter auf echte Abhängigkeiten
│   ├── matrix/    MatrixSdkChannelClient (org.matrix.rustcomponents:sdk-android)
│   ├── push/      UnifiedPush-Adapter, Push-Controller, Push-Empfänger
│   └── session/   Dateibasierte SessionStore-Implementierung
├── ui/            Compose-Oberflächen (Anmeldung, Raumliste, Chronik)
└── di/            Manuelle Abhängigkeitsverdrahtung (AppGraph)
```

Dokumentation (deutsch):

- [`docs/project-constitution.md`](docs/project-constitution.md) – Projektverfassung (Ziele, Grenzen, Grundsätze)
- [`docs/architecture.md`](docs/architecture.md) – Architektur und verifizierte Abhängigkeiten
- [`docs/features/`](docs/features/) – je Feature ein Dokument inkl. [`verification.md`](docs/features/verification.md)
- [`docs/manualtest-protokoll.md`](docs/manualtest-protokoll.md) – Manuelles Testprotokoll (Login/Wiederherstellung, E2EE-Verifizierung, verschlüsselt empfangen/senden, Push)

## Bauen und testen

Voraussetzungen: JDK 21 (via [`mise`](https://mise.jdx.dev/), siehe `mise.toml`)
und ein lokales Android SDK (Pfad wird in `mise.toml` gesetzt; kein
systemweiter Eintrag nötig).

```bash
mise exec -- ./gradlew testDebugUnitTest   # JVM-Tests
mise exec -- ./gradlew assembleDebug       # Debug-APK
```

Artefakt: `app/build/outputs/apk/debug/` (Benennung siehe Projektverfassung).

## Begrenzte Geräteverifikation

In dieser Arbeitsumgebung gibt es kein Gerät/Emulator. Alles, was ohne Gerät
prüfbar war (JVM-Tests, Kompilierung, APK-Erzeugung, Manifest/Badging), ist in
[`docs/features/verification.md`](docs/features/verification.md) mit
tatsächlich beobachteten Ergebnissen dokumentiert. Alles, was ein Gerät
benötigt, ist im manuellen Testprotokoll beschrieben und dort ausdrücklich als
"nicht beobachtet" markiert.
