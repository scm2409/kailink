# Verifikation (Ist-Stand)

> **Wichtig (Grundsatz G8):** Hier stehen nur tatsächlich beobachtete
> Ergebnisse mit Datum. Wenn ein Prüfmittel fehlte (z. B. kein Gerät), steht
> das ausdrücklich dabei. Nachgetragene Ergebnisse sind mit Datum markiert.

**Umgebung der Beobachtung (2026-09-08):** Linux-Container, JDK 21 (mise),
Android SDK unter `/home/dev/android-sdk` (platform android-36, build-tools
35.0.0, platform-tools vorhanden), Gradle Wrapper 9.1.0 — **alle Builds und
Prüfungen mit `--offline`**, also ohne Netzwerkzugriff. Kein Gerät/Emulator
verfügbar. Keine Zugangsdaten im Repo.

## Matrix der Verifikationsstufen (aus project-constitution.md)

| Stufe | Mittel | Diese Umgebung |
| --- | --- | --- |
| V1 JVM-Prüfungen | `./gradlew --offline :app:phase1Checks` | **bestanden (39/39)** |
| V2 Kompilieren/APK | `./gradlew --offline :app:assembleDebug` | **bestanden** |
| V3 Strukturprüfung | Badging/Manifest-Prüfung | **bestanden** |
| V4 Gerät | Emulator/Gerät | **nicht verfügbar** → manuelles Protokoll |

**Abweichung vom Standardmittel:** JUnit ist im lokalen Offline-Cache nicht
vorhanden; die V1-Prüfungen laufen daher als eigene Gradle-Aufgabe
`phase1Checks` (JavaExec über die kompilierten Test-Klassen, eigener
Prüf-Runner, Bericht unter `app/build/reports/phase1-checks.txt`). Die
Gradle-Test-Aufgaben (`testDebugUnitTest`) sind in Phase 1 bewusst
deaktiviert. Migration auf JUnit steht im Phase-2-Plan (architecture.md).

## 1. Abhängigkeitsverifikation (G3)

**Beobachtet am 2026-09-08 (Offline-Cache-Audit, `ls`
`~/.gradle/caches/modules-2/files-2.1/…`):**

- Vorhanden und im Build benutzt: Gradle 9.1.0, AGP 8.13.2, Kotlin 2.2.21,
  kotlinx-coroutines-android 1.7.3, Android platform 36, build-tools 35.0.0.
- **Nicht vorhanden im Cache** (daher bewusst nicht eingebunden):
  Jetpack Compose (alle `androidx.compose.*`), `org.matrix.rustcomponents`,
  `org.unifiedpush.android.connector`, JUnit 4/5, `kotlinx-coroutines-test`,
  AndroidX-Bibliotheken jenseits des Frameworks.
- **Ergebnis:** Phase 1 benötigt ausschließlich lokal vorhandene Artefakte;
  keine Stubs im kompilierten Code (G5: stattdessen dokumentierte
  Simulations-Implementierungen `InMemoryChannelClient` und
  `SimulatedPushTrigger`).

## 2. V1 — JVM-Prüfungen (`phase1Checks`)

**Beobachtet am 2026-09-08:**
`./gradlew --offline :app:phase1Checks` → `BUILD SUCCESSFUL`;
Bericht: `Prüfungen: 39, bestanden: 39, fehlgeschlagen: 0`.

| Prüfgruppe | Fokus | Ergebnis |
| --- | --- | --- |
| `TimelineReducerChecks` (9) | Chronik-Patches → Nachrichtenliste (Reset/PushBack/PushFront/Insert/Set/Remove/Pop*/Truncate/Sequenz) | bestanden |
| `FileSessionStoreChecks` (4) | Sitzungspersistenz Round-Trip, fehlende Datei, Clear, ohne Refresh-Token | bestanden |
| `PushControllerChecks` (4) | Endpoint → Pusher-Registrierung → REGISTERED, Push → Sync, Fehlschläge | bestanden |
| `LoginViewModelChecks` (5) | Login-Erfolg/Fehlerpfade, leere Felder, Wiederherstellung inkl. Löschen | bestanden |
| `RoomListViewModelChecks` (3) | refresh/Sync, RoomsUpdated-Ereignis, Logout | bestanden |
| `TimelineViewModelChecks` (5) | Chronik-Filter nach Raum-ID, Abo+Sync, Senden/Leertext/Sendefehler | bestanden |
| `InMemoryChannelClientChecks` (6) | Kanal-Simulation: Login-Pflichtfelder, Sitzungsspeicherung, Senden → Ereignis, negative Fälle, Logout | bestanden |
| `PushChainChecks` (3) | SimulatedPushTrigger → REGISTERED → syncOnce → Abmeldung | bestanden |

Vollständige Ist-Ausgabe: `app/build/reports/phase1-checks.txt`.

## 3. V2 — Kompilierung & APK

**Beobachtet am 2026-09-08:**

- `./gradlew --offline :app:phase1Checks :app:assembleDebug` →
  `BUILD SUCCESSFUL in 13s` (39 actionable tasks).
- `./gradlew --offline check` → `BUILD SUCCESSFUL in 16s`
  (58 actionable tasks; enthält Lint mit `abortOnError=false` und die
  Prüf-Aufgabe).
- Wiederholung nach `clean` (vollständiger Neuaufbau):
  `./gradlew --offline clean :app:phase1Checks :app:assembleDebug` →
  `BUILD SUCCESSFUL in 6s` (40 actionable tasks, alle ausgeführt),
  Prüfbericht erneut 39/39.
- Artefakt: `app/build/outputs/apk/debug/app-debug.apk` (≈ 3,9 MB),
  debug-signiert mit dem lokalen Debug-Keystore. Ein Release-Build wurde
  **nicht** ausgeführt (kein Anlass, kein Release-Zweck in Phase 1).

## 4. V3 — Strukturprüfung

**Beobachtet am 2026-09-08** (`aapt2 dump badging`, build-tools 35.0.0):

```
package: name='at.d71.kailink' versionCode='1' versionName='0.1.0-phase1'
  compileSdkVersion='36'
minSdkVersion:'28'
targetSdkVersion:'36'
application-label:'KaiLink'
launchable-activity: name='at.d71.kailink.MainActivity'
```

- `applicationId` = `at.d71.kailink` ✓, `minSdkVersion` 28 ✓ (Vorgabe),
  `targetSdkVersion` 36 ✓.
- Manifest: nur `INTERNET`-Berechtigung deklariert (Phase 1 baut keine
  Verbindungen auf; Berechtigung ist für Phase 2 reserviert); kein
  FCM-/Google-Code, kein Push-Receiver (Phase 2).

## 5. V4 — Geräteverifikation

**Nicht möglich in dieser Umgebung (kein Emulator, kein Gerät).** Die
verschriftlichten Versuchsanordnungen stehen in
[`../manualtest-protokoll.md`](../manualtest-protokoll.md) (MT-1 bis MT-8,
inkl. Flugmodus-Nachweis, dass Phase 1 ohne Netz funktioniert); alle
dortigen Testfälle sind hier als **nicht beobachtet** zu führen, bis sie
auf einem Gerät gelaufen sind.

## 6. Bekannte Abweichungen (Phase 1) und offene Risiken

1. **Kanal ist eine Simulation** (`InMemoryChannelClient`): Anmeldedaten
   werden nicht gegen einen Homeserver geprüft; Nachrichten liegen nur im
   Speicher. E2EE ist in Phase 1 nicht vorhanden — Vorbereitung und
   Referenzadapter siehe [`nachrichten-e2ee.md`](nachrichten-e2ee.md) und
   `app/src/phase2/`.
2. **Framework-Views statt Jetpack Compose:** Compose-Artefakte fehlen im
   Offline-Cache; ViewModels sind davon unberührt (StateFlow-Verträge).
3. **JUnit ersetzt durch `phase1Checks`** (siehe oben); Test-Task-Aktivierung
   folgt in Phase 2.
4. **Push ohne echten Distributor:** `SimulatedPushTrigger` treibt die
   echte `PushController`-Kette; Benachrichtigungen werden nicht gerendert.
5. **Token-Persistenz unverschlüsselt** im App-Files-Verzeichnis
   (App-privat, aber ohne Keystore-Verschlüsselung).
6. **Konkurrierende Agents:** während der Erstellung liefen zwei weitere
   opencode-Agenten im selben Repo und überschrieben Dateien; sie wurden
   angehalten, danach wurde der Ist-Stand komplett neu verifiziert (dieser
   Bericht). Ein Commit wurde bewusst nicht durchgeführt.
