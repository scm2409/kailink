# f2-unifiedpush – Verification

## Observed on 2026-09-08

- `./gradlew testDebugUnitTest assembleDebug` → `BUILD SUCCESSFUL`.
- `./scripts/e2e-local.sh` successfully started two rootless Podman containers:
  `docker.io/matrixconduit/matrix-conduit:latest` and
  `docker.io/binwiederhier/ntfy:latest`.
- Observed output (2026-09-08, verbatim; since the language migration to
  English (G9) the script prints `E2E smoke passed: Matrix homeserver and
  local ntfy reachable.` and `E2E registration passed: two disposable test
  accounts created.`):
  - `E2E-Smoke bestanden: Matrix-Homeserver und lokales ntfy erreichbar.`
  - `E2E-Registrierung bestanden: zwei disposable Testkonten angelegt.`
- The harness checked HTTP reachability of the Matrix
  `/_matrix/client/versions` endpoint, ntfy HTTP reachability, and the
  registration of two short-lived accounts.

## Passed Automatically

- JVM checks of the push state machine.
- Configuration check of the default gateway
  `https://ntfy.sh/_matrix/push/v1/notify`.
- Check of endpoint rotation and re-registration at the contract level.
- Start and HTTP smoke test of the local Matrix/ntfy infrastructure.

## Not Covered by the Local Harness

The shell harness has no Android runtime and no UnifiedPush
distributor. Therefore the actual connector distributor selection,
topic delivery, Matrix HTTP pusher registration against the test server,
push wake, SDK sync, E2EE decryption, and Android notification were not
claimed as locally passed.

## Manually on the Device

On Martin's GrapheneOS device, distributor selection, endpoint rotation,
push sync, and notification are to be checked. The runtime permission
`POST_NOTIFICATIONS` and emoji verification in Element X are also
manual. Real matrix.org credentials are not used in the harness.
