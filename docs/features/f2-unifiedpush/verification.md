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

## 0.2.9 — push-path notification fetch carries the sliding sync version (2026-09-11)

### Bug and device-log diagnosis (0.2.8)

On the device (0.2.8), the push-path notification resolution failed with
`VersionIsMissing` ("Sliding sync version is missing") while the main
client had detected NATIVE at sign-in. Mechanism (verified against the
SDK sources, see `docs/decisions.md`): the SDK `NotificationClient`
(MultipleProcesses) builds its short-lived notification sliding sync
from the parent client, and that build fails with `VersionIsMissing`
when the client carries no version. The push-path client's version is
set by the FFI restore from the **persisted session record** — so a
fetch construction that does not carry the session's
detected/persisted sliding sync mode runs version-blind and fails
exactly this way. The 0.2.8 construction
(`Client.notificationClient(MultipleProcesses)`, no version input) was
version-blind.

### Strict TDD: RED before the fix

New JVM checks first (`NotificationClientVersionChecks`, registered in
`AllChecks`): the construction seam must receive the active session's
mode mapped to the SDK version. Against the pre-fix wiring (seam
present, forwarding deliberately still `null`):

```
./gradlew testDebugUnitTest --offline
→ BUILD FAILED (task ':app:testDebugUnitTest')
[ERROR]  notification fetch: main client's detected NATIVE mode flows into the
         construction seam — construction seam must receive the NATIVE version
         detected by the main client — expected: NATIVE, actual: null
[ERROR]  notification fetch: main client's NONE mode flows into the construction
         seam as NONE — construction seam must receive NONE for a classic-/sync
         session (no drop to version-less) — expected: NONE, actual: null
Checks: 98, passed: 96, failed: 2
```

The test was then strengthened (no assertions weakened): the NONE check
now uses the NATIVE-capable `https://matrix.org` URL with a NONE session
(URL and mode decorrelated — the version must come from the session's
mode, never the homeserver URL), and a third check pins the
session-less defensive branch (`null` → SDK default semantics, no
crash). Strengthened RED run: same two failures, `Checks: 99, passed:
97, failed: 2`.

### GREEN

`fetchNotification` now maps
`activeSession.slidingSyncMode` → `domainModeToSdkVersion` and forwards
the version into the explicit `notificationClientFactory(Client,
SlidingSyncVersion?)` construction seam. Default factory semantics:
NATIVE (and the unknown/legacy `null`) → standard SDK construction
(the parent client carries the DISCOVER_NATIVE-detected or
restore-set version); NONE → no construction (the notification
sliding sync could never build — `VersionIsMissing` is a guaranteed
failure on sliding-sync-less homeservers), the fetch returns `null`
and the caller falls back to the room list, with an explicit log line
(`NotificationClient skipped: homeserver has no sliding sync (NONE);
room-list fallback`).

```
./gradlew testDebugUnitTest assembleDebug --offline
→ BUILD SUCCESSFUL; Checks: 99, passed: 99, failed: 0
```

### Gate run on 0.2.9 (2026-09-11) and the new Conduit fact

`./scripts/emulator-e2e.sh` (emulator-5554 running, gate containers up)
→ both legs OK: leg 6 `OK (2 tests)` (`Time: 274.812`), leg 7
`OK (1 test)` (`Time: 94.031`). The gate built and installed the 0.2.9
build (`dumpsys package`: `versionCode=7 versionName=0.2.9`); the
self-identifying first DebugLog line observed in logcat:
`I KaiLink : KaiLink 0.2.9 (versionCode 7)`. Leg 7 chain: real UI
sign-in → `Push: registered (UnifiedPush)` → real `up*` endpoint as
Conduit pusher → real event published to the real topic through the
real distributor → **notification rendered** (body asserted in
`dumpsys notification --noredact`).

**Environment change (blind spot):** the current Conduit gate image
advertises `org.matrix.simplified_msc3575: true` in `/versions`
(observed 2026-09-10 and 2026-09-11 on `http://127.0.0.1:6167`) — the
0.2.5-era statement "Conduit does not serve the flag" no longer holds
for the current image. Gate sessions therefore detect **NATIVE**
(logcat: `D KaiLink : Sliding sync version: NATIVE`), and the SDK
notification fetch ran its notification sliding sync against the gate
homeserver (observed span: `get_notification{…} > try_sliding_sync >
sync_once{conn_id="notifications"} … status=200`) — the NATIVE
construction path of the 0.2.9 fix is exercised by the gate through the
real chain. Consequences:

- The **NONE branch of the 0.2.9 fix (skip construction → room-list
  fallback) is no longer exercised by the gate** — a sliding-sync-less
  session no longer occurs there. It is covered by the JVM checks
  (wiring) only; end-to-end NONE behavior needs a sliding-sync-less
  homeserver.
- The NONE *builder* fallback (discovery transport failure → NONE) is
  still exercised by the TLS leg, but no session with NONE ever runs a
  push fetch in the gate.

### Required 0.2.9 device retest (not covered locally)

1. matrix.org (NATIVE) push → notification with SDK-resolved content
   (title = room name, body = "sender: text") and **no**
   `VersionIsMissing` in the Send-log dump — proves the actual bug fix
   on the real server.
2. If available: a push on a sliding-sync-less homeserver (Conduit-class)
   → `NotificationClient skipped: homeserver has no sliding sync (NONE);
   room-list fallback` in the Send-log dump, no notification content
   from the SDK path, app stays up (wake-up sync ran).
3. A session whose stored mode is stale-NONE on a capable server
   renders via the room-list fallback only (no SDK content) — see the
   follow-up note in `docs/decisions.md` (owner decision: re-detect the
   mode at restore).

## Verified gate run (2026-09-11) — 0.2.9, all 3 legs passed

**Observed on 2026-09-11:** `scripts/emulator-e2e.sh` ran once on 0.2.9
and passed all 3 legs: two-account E2E (2 instrumentation tests,
`Time: 274.778`) and fresh-install push E2E (1 instrumentation test,
`Time: 93.922`) — total measured instrumentation time 368.700 seconds;
overall gate **exit code 0**.

**Run conditions (documented):** emulator booted with
`emulator -avd kailink-atd35 -no-window -no-audio -no-boot-anim -no-snapshot`;
AndroidWifi connected; `pm path io.heckel.ntfy` succeeded; host
available memory was 6216 MiB.

**Earlier leg-6 failures (classification):** environmental (overlapping
emulator instances and host memory pressure), not demonstrated code
failures.

**Honest limitation:** this gate proves the Conduit plus
fresh-install/device push chain including the rendered notification, but
does **not** prove the matrix.org native rendering path; a device test
remains required.

## 0.2.10 diagnostics and gate record (2026-09-11)

The 0.2.10 fix closes three diagnostic defects in the push path: parse
failures now include a redacted reason and shape summary; warm/cold mode
source and persisted-vs-in-memory mismatches are visible; and room-list
fallback render/suppress decisions include the room ID, unread count,
encrypted flag, and suppression reason without content or credentials. Cold
restore now reconciles the persisted mode with the mode freshly detected by
`DISCOVER_NATIVE`, then persists the detected result before notification
resolution. The diagnostics and reconciliation checks were written RED
against the pre-fix behavior and are green in the final JVM/build run.

The final gate log at `/tmp/opencode/kailink-0.2.10/gate-run-final.log`
records successful installs, leg 6 `OK (2 tests)` at `Time: 274.726`, and leg
7 `OK (1 test)` at `Time: 93.896`; the gate exit code was 0. This proves the
Conduit/ntfy/distributor/device push chain and rendered notification. It does
not prove encrypted decryption, SAS verification, or matrix.org behavior.
`InvalidSignature` was observed in encrypted experimentation and is
explicitly out of scope for 0.2.10, not a claimed fix.

The planned encrypted extension is one small matrix-nio[e2e]-only Python
harness: Stage 1 creates/joins an encrypted room on fresh Conduit, sends, and
asserts KaiLink decrypts the unverified message; Stage 2 calls
`Sas.get_decimals()` while KaiLink displays/confirms decimal SAS and the gate
compares codes. No matrix-commander or custom Rust client will be used. The
pinned AAR binding name must be verified later, and the harness license must
be pinned and recorded when built.

On 2026-09-11 the main coder model switched to Luna (high): the GLM
reasoning knob was unavailable, this was a T2 practice win, and Martin pushed
for the quality pass.

**Agent-room requirement:** agent rooms are always required to be E2EE. Martin
decided that an unencrypted agent room must later produce a visible warning;
that warning is explicitly deferred and is not implemented in 0.2.10.
