# Verifikation (Ist-Stand)

> **Wichtig (Grundsatz G8):** Hier stehen nur tatsächlich beobachtete
> Ergebnisse mit Datum. Wenn ein Prüfmittel fehlte (z. B. kein Gerät), steht
> das ausdrücklich dabei. Nachgetragene Ergebnisse sind mit Datum markiert.

**Umgebung der Beobachtung (2026-09-08):** Linux-Container, JDK 21 (mise),
Android SDK unter `/home/dev/android-sdk` (platform android-36, build-tools
35.0.0, platform-tools vorhanden), Gradle Wrapper 9.1.0 — **alle Builds und
Prüfungen mit `--offline`**, also ohne Netzwerkzugriff. Kein Gerät/Emulator
verfügbar. Keine Zugangsdaten im Repo.

**Umgebung der Phase-2-Beobachtung (2026-09-08, nachfolgender Abschnitt):**
dieselbe Maschine, diesmal **mit Netzwerkzugriff** (Gradle durfte Artefakte
nachladen). Weiterhin kein Gerät/Emulator, keine Zugangsdaten im Repo.

## Phase 2 — Beobachtungen (2026-09-08, online)

### Ausgangslage: Buildfehler durch Paket-/Namespace-Spalte

**Beobachtet am 2026-09-08:** `./gradlew testDebugUnitTest assembleDebug`
brach ab (`:app:compileDebugKotlin FAILED`) mit 40 ×
`Unresolved reference 'R'` (u. a. `MainActivity.kt`, `RoomListAdapter.kt`,
`MessageListAdapter.kt`): Quellpakete lagen auf `at.d71.kailink`, während
`namespace`/`applicationId` bereits `org.box44.kailink` waren — das generierte
R lag damit außerhalb der Quellpakete.

**Behebung (beobachtet):** sämtliche Kotlin-Pakete/-Imports (main, phase2,
test), die simulierte Endpoint-URL und die SDK-`appId` konsequent auf
`org.box44.kailink` bzw. `org-box44-kailink` umgestellt (git mv, Historie
erhalten); R-Importe in den Adaptern lauten jetzt `org.box44.kailink.R`.

### Abhängigkeitsverifikation (G3, Phase 2)

**Beobachtet am 2026-09-08:** Die zuvor nicht vorhandenen Artefakte sind
eingebunden und im Build belegt:

| Artefakt | Version | Nachweis |
| --- | --- | --- |
| org.matrix.rustcomponents:sdk-android | 26.09.08 | Kompilierung + `libmatrix_sdk_ffi.so` im APK |
| org.unifiedpush.android:connector | 3.3.5 | Kompilierung + Manifest-Receiver |
| junit:junit | 4.13.2 | `testDebugUnitTest` ausgeführt (1 Test) |
| kotlinx-coroutines-test | 1.7.3 | Test-Compile-Klassepfad |

Die SDK-AAR-APIs wurden vor der Adapter-Implementierung per
`javap` gegen die lokalen AARs geprüft (u. a. `Client.login/restoreSession/
syncOnceV2/syncService()/setPusher`, `SyncServiceBuilder.finish()` (suspend),
`Timeline.addListener` (suspend) → `TaskHandle`, `TimelineDiff`-Varianten mit
`UInt`-Indizes, `MsgLikeKind.Message(MessageContent)`, `PusherKind.Http`,
`UnifiedPush.register/unregister/resolveDefaultDistributor`,
`MessagingReceiver`-Abstract-Methoden). Zwei vom Referenzstand abweichende
Signaturen wurden korrigiert (`getRoom`-Nullbarkeit,
`RoomMessageEventContentWithoutRelation?`).

### V1 — JVM-Prüfungen (ab Phase 2: gewöhnliche JUnit-Aufgabe)

**Beobachtet am 2026-09-08:** Die Phase-1-Abweichung (eigene
`phase1Checks`-Aufgabe, Test-Tasks deaktiviert) ist aufgehoben: `junit:junit`
ist Testabhängigkeit, die Prüfgruppen laufen über
`AllChecksTest.runAllJvmChecks` als JUnit-Test. Die Phase-1-Prüfgruppen und
Ergebnisse sind unverändert gültig (siehe Abschnitt V1 unten;
`PushChainChecks` erwartet jetzt `org-box44-kailink` in der Endpoint-URL).

- `./gradlew testDebugUnitTest` → `BUILD SUCCESSFUL`;
  JUnit-Bericht: `tests="1" failures="0" skipped="0"`;
  Prüfbericht: `Prüfungen: 39, bestanden: 39, fehlgeschlagen: 0`.

### V2 — Kompilierung & APK (Phase 2)

**Beobachtet am 2026-09-08:**

- `./gradlew testDebugUnitTest assembleDebug` →
  `BUILD SUCCESSFUL in 3s` (43 actionable tasks, inkrementell).
- Kontrolle mit vollständigem Neulauf:
  `./gradlew testDebugUnitTest assembleDebug --rerun-tasks` →
  `BUILD SUCCESSFUL in 6s`.
- Artefakt: `app/build/outputs/apk/debug/app-debug.apk`, debug-signiert.
- Das APK enthält die echten Rust-Bibliotheken des matrix-rust-sdk
  (`lib/arm64-v8a/libmatrix_sdk_ffi.so` u. a., ≈ 63 MB für arm64-v8a).

### V3 — Strukturprüfung (Phase 2)

**Beobachtet am 2026-09-08** (`aapt2 dump badging`, build-tools 35.0.0):

```
package: name='org.box44.kailink' versionCode='1' versionName='0.2.0-phase1'
  platformBuildVersionCode='36'
launchable-activity: name='org.box44.kailink.MainActivity'
```

- Manifest: `INTERNET`-Berechtigung; UnifiedPush-Receiver
  `.data.push.KaiLinkPushReceiver` (`exported=false`) mit den
  Connector-Aktionen `MESSAGE`, `NEW_ENDPOINT`, `REGISTRATION_FAILED`,
  `UNREGISTERED`.
- Verdrahtung (Kompilierungsebene, belegt durch erfolgreichen Build):
  `AppGraph` erzeugt `MatrixSdkChannelClient` (matrix-rust-sdk,
  SQLite-Store unter `files/matrix/store`) und `UnifiedPushRegistrar`
  als `PushRegistrationTrigger`.

**Nicht beobachtet:** Funktionsnachweis gegen einen echten Homeserver bzw.
Distributor (V4, kein Gerät/keine Zugangsdaten in dieser Umgebung). Die
Phase-2-Verdrahtung ist kompiliert und im Debug-APK enthalten; ein Laufzeit-
nachweis steht aus. `InMemoryChannelClient` und `SimulatedPushTrigger`
bleiben als JVM-geprüfte Referenz im Baum (Tests nutzen sie weiterhin).

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
*(Abgelöst am 2026-09-08, siehe Phase-2-Abschnitt oben: JUnit 4 ist
Testabhängigkeit, `testDebugUnitTest` läuft und führt die Prüfungen aus;
die `phase1Checks`-Aufgabe wurde entfernt.)*

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
   *(Abgelöst am 2026-09-08, Phase 2: `AppGraph` verdrahtet
   `MatrixSdkChannelClient` auf das echte matrix-rust-sdk; die Simulation
   bleibt als JVM-Referenz in Tests. Laufzeitnachweis gegen einen echten
   Homeserver: nicht beobachtet.)*
2. **Framework-Views statt Jetpack Compose:** Compose-Artefakte fehlen im
   Offline-Cache; ViewModels sind davon unberührt (StateFlow-Verträge).
   *(unverändert in Phase 2)*
3. **JUnit ersetzt durch `phase1Checks`** (siehe oben); Test-Task-Aktivierung
   folgt in Phase 2. *(Abgelöst am 2026-09-08: JUnit-Aufgabe aktiv, 39/39.)*
4. **Push ohne echten Distributor:** `SimulatedPushTrigger` treibt die
   echte `PushController`-Kette; Benachrichtigungen werden nicht gerendert.
   *(Teilweise abgelöst am 2026-09-08, Phase 2: `UnifiedPushRegistrar` +
   `KaiLinkPushReceiver` + Manifest-Receiver sind verdrahtet;
   Benachrichtigungs-Rendering bleibt Grenze. Laufzeitnachweis: nicht
   beobachtet.)*
5. **Token-Persistenz unverschlüsselt** im App-Files-Verzeichnis
   (App-privat, aber ohne Keystore-Verschlüsselung). *(unverändert)*
6. **Konkurrierende Agents:** während der Erstellung liefen zwei weitere
   opencode-Agenten im selben Repo und überschrieben Dateien; sie wurden
   angehalten, danach wurde der Ist-Stand komplett neu verifiziert (dieser
   Bericht). Ein Commit wurde bewusst nicht durchgeführt.
   *(Hinweis Phase 2, 2026-09-08: während des Folge-Passes erschien extern
   der Commit `c8125fd` (u. a. Paketmigration, Adapterverdrahtung); er wurde
   nicht verändert oder zurückgeschrieben.)*
