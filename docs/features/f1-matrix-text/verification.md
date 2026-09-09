# f1-matrix-text – Verification

## Observed on 2026-09-08

- `./gradlew testDebugUnitTest` → `BUILD SUCCESSFUL`; the JUnit task is
  active and covers the login, restore, room list, timeline,
  in-memory test double and notification payload checks.
- `./gradlew testDebugUnitTest assembleDebug` → `BUILD SUCCESSFUL`.
- `./scripts/e2e-local.sh` → `E2E-Smoke bestanden: Matrix-Homeserver und
  lokales ntfy erreichbar.` as well as `E2E-Registrierung bestanden: zwei
  disposable Testkonten angelegt.`
  *(Note, 2026-09-09, language migration to English (G9): verbatim observed
  output of 2026-09-08, when the script still printed German. The script's
  strings are now English, so current runs print `E2E smoke passed: Matrix
  homeserver and local ntfy reachable.` and `E2E registration passed: two
  disposable test accounts created.`)*
- The E2E run used rootless Podman with
  `docker.io/matrixconduit/matrix-conduit:latest` and
  `docker.io/binwiederhier/ntfy:latest`. The images were pulled successfully;
  the Matrix endpoint `/_matrix/client/versions` and ntfy responded.

## Passed Automatically

- Build and normal JUnit execution.
- Start of the local Matrix homeserver and reachability via HTTP.
- Start of the local ntfy service and reachability via HTTP.
- Registration of two short-lived test accounts via
  `/_matrix/client/v3/register`.
- Pure JVM checks of the login/restore contracts, of sending, and of the
  timeline reduction against the test double.
- Android instrumentation on the ATD emulator `kailink-atd35` (Android 15,
  x86_64): `LoginInstrumentationTest.packageMetadataAndMatrixOrgPrefill` →
  `OK (1 test)`. Checked were the application ID, version name, and the visible
  homeserver prefill `https://matrix.org`.

## Not Covered by the E2E Run

The script is a shell/HTTP harness and does not start an Android runtime. Therefore
Matrix SDK login/restore, real sending and receiving, E2EE decryption
with the persistent Rust SDK crypto store, Matrix pusher registration, and the
Android notification path were not claimed as passed. The existing
Android instrumentation so far checks only package metadata and the login prefill;
the full Matrix/E2EE/pusher run against the local homeserver remains
open and is not claimed as passed.

## Current Instrumented E2E State

The emulator run `kailink-atd35` contains an Android HTTP connectivity test
with `HttpURLConnection`. It first checks `http://192.168.42.20:8090` and then
`http://10.0.2.2:8090`; the choice is printed in the instrumentation log.
The run was observed with `OK (2 tests)`. The second test is a
gateway smoke, not a Matrix login.

The SDK seams for `createRoom` and `joinRoom` as well as the optional
test configuration for `CollectStrategy.ALL_DEVICES` and
`DecryptionSettings(TrustRequirement.UNTRUSTED)` compile against
`sdk-android:26.09.08`. A real two-account Matrix/E2EE run is not yet
marked as passed: the instrumentation currently receives no live
disposable-account configuration and therefore performs no login, send, receive,
or plaintext comparison.

## Emulator Gate Script

`./scripts/emulator-e2e.sh` runs the local Matrix/ntfy HTTP smoke, the
JVM checks, both APK builds, and the direct AndroidX
instrumentation smoke on `kailink-atd35`. Afterwards it exits with
exit code 3 and explicitly points out that the full
Matrix/E2EE/UnifiedPush chain is not yet automated. This
failure status is intentional and prevents a false E2E proof.

Currently not implemented are a test-only configuration channel for two
disposable accounts and room IDs, a controlled E2EE key exchange for
the instrumentation run, and the automated setup of a
UnifiedPush distributor (ntfy app). A direct broadcast to the receiver marked as not
exported would not be proof of the distributor stage and
is therefore not counted as push success.

## Manually on the Device

Martin checks on GrapheneOS login against `https://matrix.org`, restore after
process restart, E2EE room and receiving, UnifiedPush distributor/endpoint,
push sync, and notification. Emoji verification takes place in Element X.

## Two-Account Matrix E2E (Chunk A + B) — GREEN on 2026-09-08

Reproducible via `./scripts/emulator-e2e.sh` (Conduit+ntfy, adb reverse,
accounts, both APKs, `am instrument` — one command, exit 0).

- Test: `org.box44.kailink.MatrixE2eTest#twoAccountTimelineDeliveryUnencrypted`
  (Alice = SUT via `MatrixSdkChannelClient`, Bob = raw SDK client
  `RawBobClient`; homeserver `http://127.0.0.1:6167` via `adb reverse`).
- Result (gate run): `Time: 152.644` / `OK (1 test)`.
- Chunk A (unencrypted): login on both sides, `createRoom` with
  Bob invitation, Bob join, Bob send, Alice timeline polling with hard
  assert `body == sent && state == SENT` — passed.
- Restore leg: `dispose` (without `logout`), re-construct, `restore` from
  `FileSessionStore`, `syncOnce`, `rooms()` contains the room —
  `Restore-Leg ok` (Logcat).
- Chunk B (encrypted, `encrypted=true`, Alice with `E2eeTestConfig`
  = `CollectStrategy.ALL_DEVICES` + `DecryptionSettings(UNTRUSTED)`):
  same flow, hard assert `body` equality + `SENT`
  (no UNDECRYPTABLE placeholder) — `E2E B ok` (Logcat).
  Bob join BEFORE the timeline subscription, 2 `syncOnce` rounds each after join and send
  (room key sharing needs multiple passes).
- Proof excerpt from Logcat (gate run of 2026-09-08, verbatim; the test now
  logs the English equivalent `Alice has received: id=… state=SENT`):
  `Alice hat empfangen: id=$0rgHhcbkk... state=SENT`,
  `E2E B ok: id=$dBEGG8MPre... state=SENT`.
- Container digests in the gate log:
  ntfy `sha256:6ef4b819f722fccdc036af611c4774cfdc2de821ab74fdd48bbf4c9d6f8973da`,
  conduit `sha256:b0d24248e94f944ca49f90f10c429e3d65f4472bdde25661ecea9840134fb133`.

## Known Limits (Conduit)

- The `SyncService` (`syncService().finish()`) needs server-side
  sliding sync; Conduit reports `VersionIsMissing`. The E2E test therefore drives sync
  and send-queue flush via `syncOnce` (`syncOnceV2`) on both sides;
  in production `startLiveSync` remains wired.
- Emulator network broken (empty route table, `10.0.2.2` unreachable:
  `Failed to connect`). The run uses `adb reverse tcp:6167/tcp:8090`
  to `127.0.0.1` (re-set after an emulator restart, step [3/6]).
- The earlier harness statement ("gateway probe green") was misread:
  `probeGateway()` only logged `reachable=none`; the cleartext ban
  (targetSdk 36, API 35 — loopback exempt only from API 37) blocked HTTP
  until the `emulatorDebug` network config arrived.
