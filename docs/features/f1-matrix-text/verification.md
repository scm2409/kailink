# f1-matrix-text – Verifikation

## Beobachtet am 2026-09-08

- `./gradlew testDebugUnitTest` → `BUILD SUCCESSFUL`; die JUnit-Aufgabe ist
  aktiv und umfasst die Login-, Restore-, Raumlisten-, Timeline-,
  In-Memory-Testdouble- und Notification-Payload-Prüfungen.
- `./gradlew testDebugUnitTest assembleDebug` → `BUILD SUCCESSFUL`.
- `./scripts/e2e-local.sh` → `E2E-Smoke bestanden: Matrix-Homeserver und
  lokales ntfy erreichbar.` sowie `E2E-Registrierung bestanden: zwei
  disposable Testkonten angelegt.`
- Der E2E-Lauf verwendete rootless Podman mit
  `docker.io/matrixconduit/matrix-conduit:latest` und
  `docker.io/binwiederhier/ntfy:latest`. Die Images wurden erfolgreich gezogen;
  der Matrix-Endpoint `/_matrix/client/versions` und ntfy antworteten.

## Automatisiert bestanden

- Build und normale JUnit-Ausführung.
- Start des lokalen Matrix-Homeservers und Erreichbarkeit per HTTP.
- Start des lokalen ntfy-Dienstes und Erreichbarkeit per HTTP.
- Registrierung zweier kurzlebiger Testkonten über
  `/_matrix/client/v3/register`.
- Reine JVM-Prüfungen der Login-/Restore-Verträge, des Sendens und der
  Timeline-Reduktion gegen den Testdouble.
- Android-Instrumentierung auf dem ATD-Emulator `kailink-atd35` (Android 15,
  x86_64): `LoginInstrumentationTest.packageMetadataAndMatrixOrgPrefill` →
  `OK (1 test)`. Geprüft wurden Application-ID, Versionsname und das sichtbare
  Homeserver-Prefill `https://matrix.org`.

## Nicht durch den E2E-Lauf abgedeckt

Das Skript ist ein Shell-/HTTP-Harness und startet keine Android-Runtime. Daher
wurden Matrix-SDK-Login/Restore, echtes Senden und Empfangen, E2EE-Entschlüsselung
mit dem persistenten Rust-SDK-Krypto-Store, Matrix-Pusher-Registrierung und der
Android-Notification-Pfad nicht als bestanden behauptet. Die vorhandene
Android-Instrumentierung prüft bislang nur Paketmetadaten und das Login-Prefill;
der vollständige Matrix-/E2EE-/Pusher-Lauf gegen den lokalen Homeserver ist
weiterhin offen und nicht als bestanden behauptet.

## Aktueller instrumentierter E2E-Stand

Der Emulatorlauf `kailink-atd35` enthält einen Android-HTTP-Konnektivitätstest
mit `HttpURLConnection`. Er prüft zuerst `http://192.168.42.20:8090` und danach
`http://10.0.2.2:8090`; die Auswahl wird im Instrumentierungs-Log ausgegeben.
Der Lauf wurde mit `OK (2 tests)` beobachtet. Der zweite Test ist ein
Gateway-Smoke, kein Matrix-Login.

Die SDK-Nahtstellen für `createRoom` und `joinRoom` sowie die optionale
Testkonfiguration für `CollectStrategy.ALL_DEVICES` und
`DecryptionSettings(TrustRequirement.UNTRUSTED)` kompilieren gegen
`sdk-android:26.09.08`. Ein echter Zwei-Konten-Matrix-/E2EE-Lauf ist noch nicht
als bestanden markiert: die Instrumentierung erhält derzeit keine live
Wegwerfkonto-Konfiguration und führt daher keinen Login-, Sende-, Empfangs-
oder Klartextvergleich aus.

## Emulator-Gate-Skript

`./scripts/emulator-e2e.sh` führt den lokalen Matrix-/ntfy-HTTP-Smoke, die
JVM-Prüfungen, beide APK-Builds und den direkten AndroidX-
Instrumentierungs-Smoke auf `kailink-atd35` aus. Danach beendet es sich mit
Exit-Code 3 und weist ausdrücklich darauf hin, dass die vollständige
Matrix-/E2EE-/UnifiedPush-Kette noch nicht automatisiert ist. Dieser
Fehlerstatus ist beabsichtigt und verhindert einen falschen E2E-Nachweis.

Derzeit nicht implementiert sind ein test-only Konfigurationskanal für zwei
Wegwerfkonten und Raum-IDs, ein kontrollierter E2EE-Schlüsselaustausch für
den Instrumentierungslauf sowie die automatisierte Einrichtung eines
UnifiedPush-Distributors (ntfy-App). Ein direkter Broadcast an den als nicht
exportiert markierten Receiver wäre kein Nachweis der Distributor-Stufe und
wird deshalb nicht als Push-Erfolg gewertet.

## Manuell auf dem Gerät

Martin prüft auf GrapheneOS Login gegen `https://matrix.org`, Restore nach
Prozessneustart, E2EE-Raum und Empfang, UnifiedPush-Distributor/Endpoint,
Push-Sync und Notification. Die Emoji-Verifikation erfolgt in Element X.

## Zwei-Konten-Matrix-E2E (Chunk A + B) — GRUEN am 2026-09-08

Reproduzierbar via `./scripts/emulator-e2e.sh` (Conduit+ntfy, adb reverse,
Konten, beide APKs, `am instrument` — ein Befehl, Exit 0).

- Test: `org.box44.kailink.MatrixE2eTest#twoAccountTimelineDeliveryUnencrypted`
  (Alice = SUT ueber `MatrixSdkChannelClient`, Bob = roher SDK-Client
  `RawBobClient`; Homeserver `http://127.0.0.1:6167` via `adb reverse`).
- Ergebnis (Gate-Lauf): `Time: 152.644` / `OK (1 test)`.
- Chunk A (unverschluesselt): beidseitiger Login, `createRoom` mit
  Bob-Einladung, Bob-Join, Bob-Send, Alice-Timeline-Polling mit hartem
  Assert `body == gesendet && state == SENT` — bestanden.
- Restore-Leg: `dispose` (ohne `logout`), neu konstruieren, `restore` aus
  `FileSessionStore`, `syncOnce`, `rooms()` enthaelt den Raum —
  `Restore-Leg ok` (Logcat).
- Chunk B (verschluesselt, `encrypted=true`, Alice mit `E2eeTestConfig`
  = `CollectStrategy.ALL_DEVICES` + `DecryptionSettings(UNTRUSTED)`):
  gleicher Ablauf, hartes Assert `body`-Gleichheit + `SENT`
  (kein UNDECRYPTABLE-Platzhalter) — `E2E B ok` (Logcat).
  Bob-Join VOR Timeline-Abo, je 2 `syncOnce`-Runden nach Join und Send
  (Room-Key-Sharing braucht mehrere Durchlaeufe).
- Beweis-Auszug Logcat: `Alice hat empfangen: id=$0rgHhcbkk... state=SENT`,
  `E2E B ok: id=$dBEGG8MPre... state=SENT`.
- Container-Digests im Gate-Log:
  ntfy `sha256:6ef4b819f722fccdc036af611c4774cfdc2de821ab74fdd48bbf4c9d6f8973da`,
  conduit `sha256:b0d24248e94f944ca49f90f10c429e3d65f4472bdde25661ecea9840134fb133`.

## Bekannte Grenzen (Conduit)

- Der `SyncService` (`syncService().finish()`) braucht serverseitiges
  Sliding Sync; Conduit meldet `VersionIsMissing`. Der E2E-Test treibt Sync
  und Send-Queue-Flush daher ueber `syncOnce` (`syncOnceV2`) beidseitig;
  produktiv bleibt `startLiveSync` verdrahtet.
- Emulator-Netz defekt (leere Routentabelle, `10.0.2.2` unerreichbar:
  `Failed to connect`). Der Lauf nutzt `adb reverse tcp:6167/tcp:8090`
  nach `127.0.0.1` (nach Emulator-Neustart neu setzen, Schritt [3/6]).
- Aeltere Harness-Aussage („Gateway-Probe gruen") war falsch gelesen:
  `probeGateway()` loggte nur `reachable=none`; der Cleartext-Ban
  (targetSdk 36, API 35 — Loopback-Exempt erst ab API 37) blockte HTTP,
  bis die `emulatorDebug`-Netzwerkconfig kam.
