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

## Superseded run finding (2026-09-10, morning) — corrected below

Diagnostic run: the pinned Gradle dependency
`org.matrix.rustcomponents:sdk-android:26.09.08` was inspected from the
Gradle cache at the AAR level. The `unzip` listing showed **zero entries
under `lib/`** — the AAR has no native `.so` libraries, including no
x86_64 libraries. Consequently the prescribed x86_64 debug/emulator build
cannot be evaluated or produced from this pinned SDK, and the
ABI/emulator installation, logcat, and gate steps were **intentionally
not run**.

**No ABI switch, no AVD switch, and no fallback implementation was made.**
The next approach is left for the project owner to decide. No verification
assertions above were changed or weakened; no source code, build
configuration, tests, or dependencies were modified for this finding.

**Correction (2026-09-10, afternoon):** the above inspection queried the
**wrong archive path** — Android AARs keep native libraries under
`jni/<abi>/`, not `lib/`. The AAR **does** contain
`jni/x86_64/libmatrix_sdk_ffi.so` (69,989,968 bytes, plus arm64-v8a,
armeabi-v7a, x86), the `emulatorDebug` build type with `abiFilters
x86_64` already exists in `app/build.gradle.kts`, and the gate installs
`app-emulatorDebug.apk`. The earlier no-matching-ABIs failure came from
installing the arm64-only `debug` APK by mistake. The finding above is
withdrawn; the corrected facts and the actual run results are in the
section below.

## Observed run (2026-09-10, afternoon) — Task 3 investigation: classification (c), chain proven, gate passed

Setup: x86_64 AVD `kailink-atd35` (API 35, `emulator-5554`), only the
rebuilt `emulatorDebug` APK installed, ntfy Android 1.25.2
(`io.heckel.ntfy` versionCode 63) with `POST_NOTIFICATIONS` granted to
both apps, gate containers (Conduit/ntfy/nginx-TLS) running, `adb
reverse` 6167/8090/8443. Real KaiLink UI sign-in against
`http://127.0.0.1:6167`.

- **Classification: case (c)** of the checklist table — both diagnostic
  lines (`UnifiedPush registration: calling UnifiedPush.register
  (distributor: io.heckel.ntfy)` / `… returned (distributor:
  io.heckel.ntfy)`) appear on restore and sign-in paths, but no `up*`
  endpoint and no `up*` pusher. Root cause: connector 3.3.5 sends the
  REGISTER broadcast only for distributors present in its `distributors`
  table (`DBStore.RegistrationsStore.set`), and `UnifiedPushRegistrar`
  never calls `UnifiedPush.saveDistributor` in the `Found` branch
  (`UnifiedPushRegistrar.kt:32`) — so the broadcast is never sent and
  `register()` returns silently. State stays at "Push: registering …".
- **Distributor server (case (c) requirement):** the ntfy app's saved
  `DefaultBaseURL` is `http://127.0.0.1:8090` — the **Podman ntfy
  container, not ntfy.sh**. A root-shell REGISTER probe to the
  distributor created an `up*` subscription on that server and sent
  `NEW_ENDPOINT` — the distributor itself works.
- **Chain proof (diagnostic device-state injection, not a code change):**
  inserting the `distributors` row exactly as `saveDistributor` would and
  restarting produced REGISTER (AND_3.1.0 shared identity) → `up*`
  subscription on the Podman server → NEW_ENDPOINT → KaiLink pusher at
  Conduit (`pushkey: http://127.0.0.1:8090/up3QmdfC4EJh51?up=1`) → room
  list **"Push: registered (UnifiedPush)"**. A real Bob message
  (Conduit) delivered as the gateway notify body to the real `up*` topic
  produced the **rendered KaiLink notification** (channel "Push
  messages", title `kailink-push-task3`, text
  `kailink_bob: Task 3 push delivery probe`). A malformed `event_id`
  payload was handled per spec (resolution failure logged, wake-up sync
  ran, app stayed up).
- **Environment fact:** the ntfy distributor opens subscriber
  connections only when the system has an active default network; the
  AVD booted with none ("Waiting for network"). Provisioning the
  emulator's virtual AP (`cmd wifi connect-network AndroidWifi open` →
  `Active default network: 100`) is part of the run recipe
  (gate-infrastructure provisioning only).

**Full Task 3 gate (unchanged, all existing assertions preserved):**
`./scripts/emulator-e2e.sh` → **exit code 0, `OK (2 tests)`,
`Time: 274.752`** — `MatrixE2eTest#twoAccountTimelineDeliveryUnencrypted`
(with observed C2 pusher registration
`http://127.0.0.1:8090/kailink-e2e-90a09e2a`, C2b real Conduit→ntfy
publish response, `C2c ok: app-side payload parses (roomId=…,
eventId=$E97eNWu4A4tRh3BQcJMqmpdoWzYDNLLUDySt5YhbKwk, unread=2)`) and
`TlsE2eTest#rustlsLoginOverHttpsFailsWithTlsErrorNotInitPanic`.

**JVM checks:** `./gradlew testDebugUnitTest assembleDebug --offline` →
`BUILD SUCCESSFUL`, `Checks: 96, passed: 96, failed: 0`
(`app/build/reports/phase1-checks.txt`).

**Not proven with current infrastructure:** Conduit→push delivery for the
**app's own pusher** — KaiLink registers its pusher with the default
gateway `https://ntfy.sh/_matrix/push/v1/notify` (`PushConfiguration`),
so a Conduit-initiated push for the app's own registration would go to
public ntfy.sh instead of the local topic; the delivery leg above used
the Podman ntfy topic with identical gateway bytes (the gate proves the
Conduit→ntfy hop for its synthetic pusher). The `saveDistributor` fix
itself is a one-line registrar change left for the project owner — no
source code, build configuration, tests, or dependencies were modified
for these findings. No credentials, tokens, or personal data were
recorded.
