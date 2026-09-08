# Verifikation (Ist-Stand)

> **Wichtig (Grundsatz G8):** Hier stehen nur tatsächlich beobachtete
> Ergebnisse mit Datum. Wenn ein Prüfmittel fehlte (z. B. kein Gerät), steht
> das ausdrücklich dabei. Nachgetragene Ergebnisse sind mit Datum markiert.

**Umgebung der Beobachtung:** Linux-Container, JDK 21 (mise), Android SDK
per-User unter `~/.local/share/android-sdk` (cmdline-tools 16111833, platform
android-36, build-tools 36.0.0, platform-tools 37.0.1), Gradle 8.14.3 Wrapper,
kein Gerät/Emulator verfügbar.

## Matrix der Verifikationsstufen (aus project-constitution.md)

| Stufe | Mittel | Diese Umgebung |
| --- | --- | --- |
| V1 JVM-Tests | `./gradlew testDebugUnitTest` | verfügbar |
| V2 Kompilieren/APK | `./gradlew assembleDebug` | verfügbar |
| V3 Strukturprüfung | Manifest/Badging/APK-Inhalt | verfügbar |
| V4 Gerät | Emulator/Gerät | **nicht verfügbar** → manuelles Protokoll |

## 1. Abhängigkeitsverifikation (G3)

**Beobachtet am 2026-09-08:**

- Maven-Metadaten abgerufen (HTTP 200):
  - `org.matrix.rustcomponents:sdk-android` → Release `26.09.08`
  - `org.unifiedpush.android:connector` → Release `3.3.5`
  - Compose BOM `2026.08.00`, AGP `8.13.2`, Kotlin `2.4.20`
- AARs heruntergeladen und entpackt; API-Oberfläche per `javap` geprüft
  (Details der benutzten Signaturen: [`../architecture.md`](../architecture.md)):
  - matrix-sdk AAR: 10 304 178 Byte, `classes.jar` mit 4 147 Klassen,
    native `jni/*.so` vorhanden.
  - unifiedpush AAR: 168 684 Byte, `MessagingReceiver`/`UnifiedPush`/
    `PushMessage`/`PushEndpoint` und Connector-Aktionen bestätigt.
- **Ergebnis:** Beide Kernabhängigkeiten sind echt einbindbar; **keine Stubs
  nötig.**

## 2. V1 — JVM-Tests

**(Ergebnis nach Testlauf eintragen — siehe Protokollauszug unten.)**

| Testklasse | Fokus | Ergebnis |
| --- | --- | --- |
| `TimelineReducerTest` | Chronik-Patches → Nachrichtenliste | *(offen)* |
| `LoginViewModelTest` | Login-Fehlerpfad, Sitzungswiederherstellung | *(offen)* |
| `RoomListViewModelTest` | Ereignis→UI-Zustand, Sync-Auslösung | *(offen)* |
| `TimelineViewModelTest` | Chronik-Empfang, Senden delegiert | *(offen)* |
| `PushControllerTest` | Endpoint → Pusher-Registrierung, Push → Sync | *(offen)* |
| `FileSessionStoreTest` | Sitzungspersistenz Round-Trip | *(offen)* |

## 3. V2 — Kompilierung & APK

**(Ergebnis nach Build eintragen.)**

- `./gradlew assembleDebug`: *(offen)*
- `./gradlew testDebugUnitTest`: *(offen)*

## 4. V3 — Strukturprüfung

**(Ergebnis nach APK-Prüfung eintragen.)**

- Manifest: `applicationId`/`package` = `at.d71.kailink`, `minSdkVersion` ≥ 28,
  Push-Receiver deklariert, `POST_NOTIFICATIONS`+`INTERNET`: *(offen)*
- Kein FCM-/Google-Code im APK (AAR-Liste prüfen): *(offen)*

## 5. V4 — Geräteverifikation

**Nicht möglich in dieser Umgebung (kein Emulator, kein Gerät).** Die
verschriftlichten Versuchsanordnungen stehen in
[`../manualtest-protokoll.md`](../manualtest-protokoll.md); alle dortigen
Testfälle sind hier als **nicht beobachtet** zu führen, bis sie auf einem
Gerät gelaufen sind.

## 6. Offene Risiken / Blocker

1. **Kein Gerät:** Das E2EE-Verhalten im Feld (Schlüsselaustausch,
   Unable-to-Decrypt-Pfad, Cross-Signing) ist nur über die SDK-Tests des
   Herstellers und das manuelle Protokoll abzudecken.
2. **`SqliteStoreBuilder(path, null)`:** Signatur verifiziert; Laufzeitverhalten
   ohne Passphrase nicht geräteverifiziert.
3. **Push-Inhalte:** `NotificationClient` des SDK ist nicht verdrahtet —
   Push zeigt keine entschlüsselten Vorschauen (siehe features/push.md).
4. **Token-Persistenz unverschlüsselt** im App-Files-Verzeichnis
   (App-privat, aber ohne Keystore-Verschlüsselung).
5. **Distributor-Abhängigkeit:** Ohne installierten UnifiedPush-Distributor
   (z. B. ntfy) läuft Push nicht — konfigurierbar im Protokoll MT-7.