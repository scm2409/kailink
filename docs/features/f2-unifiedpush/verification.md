# f2-unifiedpush – Verifikation

## Beobachtet am 2026-09-08

- `./gradlew testDebugUnitTest assembleDebug` → `BUILD SUCCESSFUL`.
- `./scripts/e2e-local.sh` startete erfolgreich zwei rootless-Podman-Container:
  `docker.io/matrixconduit/matrix-conduit:latest` und
  `docker.io/binwiederhier/ntfy:latest`.
- Beobachtete Ausgaben:
  - `E2E-Smoke bestanden: Matrix-Homeserver und lokales ntfy erreichbar.`
  - `E2E-Registrierung bestanden: zwei disposable Testkonten angelegt.`
- Der Harness prüfte HTTP-Erreichbarkeit des Matrix-
  `/_matrix/client/versions`-Endpoints, die ntfy-HTTP-Erreichbarkeit und die
  Registrierung zweier kurzlebiger Konten.

## Automatisiert bestanden

- JVM-Prüfungen des Push-Zustandsautomaten.
- Konfigurationsprüfung des Standard-Gateways
  `https://ntfy.sh/_matrix/push/v1/notify`.
- Prüfung der Endpoint-Rotation und erneuten Registrierung auf Vertragsebene.
- Start und HTTP-Smoke-Test der lokalen Matrix-/ntfy-Infrastruktur.

## Nicht durch den lokalen Harness abgedeckt

Der Shell-Harness besitzt keine Android-Runtime und keinen UnifiedPush-
Distributor. Deshalb wurden die tatsächliche Connector-Distributor-Auswahl,
Topic-Auslieferung, Matrix-HTTP-Pusher-Registrierung gegen den Testserver,
Push-Wake, SDK-Sync, E2EE-Entschlüsselung und Android-Notification nicht als
lokal bestanden behauptet.

## Manuell auf dem Gerät

Auf Martins GrapheneOS-Gerät sind Distributor-Auswahl, Endpoint-Rotation,
Push-Sync und Notification zu prüfen. Die Runtime-Berechtigung
`POST_NOTIFICATIONS` und die Emoji-Verifikation in Element X sind ebenfalls
manuell. Reale matrix.org-Zugangsdaten werden nicht im Harness verwendet.
