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

## Nicht durch den E2E-Lauf abgedeckt

Das Skript ist ein Shell-/HTTP-Harness und startet keine Android-Runtime. Daher
wurden Matrix-SDK-Login/Restore, echtes Senden und Empfangen, E2EE-Entschlüsselung
mit dem persistenten Rust-SDK-Krypto-Store, Matrix-Pusher-Registrierung und der
Android-Notification-Pfad nicht als bestanden behauptet. Diese Pfade sind auf
GrapheneOS beziehungsweise durch einen künftigen instrumentierten Android-
Lauf zu prüfen.

## Manuell auf dem Gerät

Martin prüft auf GrapheneOS Login gegen `https://matrix.org`, Restore nach
Prozessneustart, E2EE-Raum und Empfang, UnifiedPush-Distributor/Endpoint,
Push-Sync und Notification. Die Emoji-Verifikation erfolgt in Element X.
