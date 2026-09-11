# Verification (Current State)

> **Important (principle G8):** Only actually observed
> results with dates appear here. If a verification means was missing (e.g. no device), that
> is stated explicitly. Retrospectively added results are marked with a date.

**Observation environment (2026-09-08):** Linux container, JDK 21 (mise),
Android SDK under `/home/dev/android-sdk` (platform android-36, build-tools
35.0.0, platform-tools present), Gradle wrapper 9.1.0 — **all builds and
checks with `--offline`**, i.e. without network access. No device/emulator
available. No credentials in the repo.

**Environment of the Phase-2 observation (2026-09-08, section below):**
same machine, this time **with network access** (Gradle was allowed to
download artifacts). Still no device/emulator, no credentials in the repo.

## Phase 2 — Observations (2026-09-08, online)

### Starting point: build failure due to the package/namespace split

**Observed on 2026-09-08:** `./gradlew testDebugUnitTest assembleDebug`
failed (`:app:compileDebugKotlin FAILED`) with 40 ×
`Unresolved reference 'R'` (among others `MainActivity.kt`, `RoomListAdapter.kt`,
`MessageListAdapter.kt`): the source packages were on `at.d71.kailink`, while
`namespace`/`applicationId` were already `org.box44.kailink` — the generated
R was therefore outside the source packages.

**Fix (observed):** all Kotlin packages/imports (main, phase2,
test), the simulated endpoint URL, and the SDK `appId` were consistently switched to
`org.box44.kailink` or `org-box44-kailink` (git mv, history
preserved); the R imports in the adapters now read `org.box44.kailink.R`.

### Dependency verification (G3, Phase 2)

**Observed on 2026-09-08:** The previously absent artifacts are
integrated and evidenced in the build:

| Artifact | Version | Evidence |
| --- | --- | --- |
| org.matrix.rustcomponents:sdk-android | 26.09.08 | compilation + `libmatrix_sdk_ffi.so` in the APK |
| org.unifiedpush.android:connector | 3.3.5 | compilation + manifest receiver |
| junit:junit | 4.13.2 | `testDebugUnitTest` executed (1 test) |
| kotlinx-coroutines-test | 1.7.3 | test compile classpath |

The SDK AAR APIs were checked before the adapter implementation via
`javap` against the local AARs (among others `Client.login/restoreSession/
syncOnceV2/syncService()/setPusher`, `SyncServiceBuilder.finish()` (suspend),
`Timeline.addListener` (suspend) → `TaskHandle`, `TimelineDiff` variants with
`UInt` indices, `MsgLikeKind.Message(MessageContent)`, `PusherKind.Http`,
`UnifiedPush.register/unregister/resolveDefaultDistributor`,
`MessagingReceiver` abstract methods). Two signatures deviating from the
reference were corrected (`getRoom` nullability,
`RoomMessageEventContentWithoutRelation?`).

### V1 — JVM checks (from Phase 2 on: ordinary JUnit task)

**Observed on 2026-09-08:** The Phase-1 deviation (own
`phase1Checks` task, test tasks disabled) is lifted: `junit:junit`
is a test dependency, the check groups run via
`AllChecksTest.runAllJvmChecks` as a JUnit test. The Phase-1 check groups and
results remain valid unchanged (see section V1 below;
`PushChainChecks` now expects `org-box44-kailink` in the endpoint URL).

- `./gradlew testDebugUnitTest` → `BUILD SUCCESSFUL`;
  JUnit report: `tests="1" failures="0" skipped="0"`;
  check report: `Prüfungen: 39, bestanden: 39, fehlgeschlagen: 0`.
  *(Note, 2026-09-09, language migration to English (G9): the quoted check
  report is the verbatim observed output of 2026-09-08, when the check
  runner still printed German. The runner's strings are now English, so
  current runs print the equivalent `Checks: 39, passed: 39, failed: 0`.)*

### V2 — Compilation & APK (Phase 2)

**Observed on 2026-09-08:**

- `./gradlew testDebugUnitTest assembleDebug` →
  `BUILD SUCCESSFUL in 3s` (43 actionable tasks, incremental).
- Cross-check with a full rerun:
  `./gradlew testDebugUnitTest assembleDebug --rerun-tasks` →
  `BUILD SUCCESSFUL in 6s`.
- Artifact: `app/build/outputs/apk/debug/app-debug.apk`, debug-signed.
- The APK contains the real Rust libraries of the matrix-rust-sdk
  (`lib/arm64-v8a/libmatrix_sdk_ffi.so` among others, ≈ 63 MB for arm64-v8a).

### V3 — Structure check (Phase 2)

**Observed on 2026-09-08** (`aapt2 dump badging`, build-tools 35.0.0):

```
package: name='org.box44.kailink' versionCode='1' versionName='0.2.0-phase1'
  platformBuildVersionCode='36'
launchable-activity: name='org.box44.kailink.MainActivity'
```

- Manifest: `INTERNET` permission; UnifiedPush receiver
  `.data.push.KaiLinkPushReceiver` (`exported=false`) with the
  connector actions `MESSAGE`, `NEW_ENDPOINT`, `REGISTRATION_FAILED`,
  `UNREGISTERED`.
- Wiring (compile level, evidenced by the successful build):
  `AppGraph` creates `MatrixSdkChannelClient` (matrix-rust-sdk,
  SQLite store under `files/matrix/store`) and `UnifiedPushRegistrar`
  as the `PushRegistrationTrigger`.

**Not observed:** functional proof against a real homeserver or
distributor (V4, no device/no credentials in this environment). The
Phase-2 wiring is compiled and contained in the debug APK; a runtime
proof is still pending. `InMemoryChannelClient` and `SimulatedPushTrigger`
remain in the tree as the JVM-checked reference (tests still use them).

## Emulator E2E (Chunk A+B)

**Observed on 2026-09-09:** The emulator E2E state with Chunk A (two
accounts, unencrypted) and Chunk B (encrypted second leg) is
green. `scripts/emulator-e2e.sh` reproduces the run in one command.

## Matrix of Verification Levels (from project-constitution.md)

| Level | Means | This environment |
| --- | --- | --- |
| V1 JVM checks | `./gradlew --offline :app:phase1Checks` | **passed (39/39)** |
| V2 Compile/APK | `./gradlew --offline :app:assembleDebug` | **passed** |
| V3 Structure check | badging/manifest check | **passed** |
| V4 Device | emulator/device | **not available** → manual protocol |

**Deviation from the standard means:** JUnit is not present in the local
offline cache; the V1 checks therefore run as a dedicated Gradle task
`phase1Checks` (JavaExec over the compiled test classes, own
check runner, report at `app/build/reports/phase1-checks.txt`). The
Gradle test tasks (`testDebugUnitTest`) are deliberately
disabled in Phase 1. Migration to JUnit is in the Phase-2 plan (architecture.md).
*(Superseded on 2026-09-08, see the Phase-2 section above: JUnit 4 is
a test dependency, `testDebugUnitTest` runs and executes the checks;
the `phase1Checks` task was removed.)*

**Update (2026-09-09, language migration to English):** re-observed with
`./gradlew testDebugUnitTest assembleDebug --offline --rerun-tasks` →
`BUILD SUCCESSFUL`; the check report now reads
`Checks: 46, passed: 46, failed: 0` (English runner strings, G9; the count
grew from 39 to 46 because the migration added check cases). The dated
39/39 results below remain the verbatim observations of 2026-09-08.

## 1. Dependency Verification (G3)

**Observed on 2026-09-08 (offline cache audit, `ls`
`~/.gradle/caches/modules-2/files-2.1/…`):**

- Present and used in the build: Gradle 9.1.0, AGP 8.13.2, Kotlin 2.2.21,
  kotlinx-coroutines-android 1.7.3, Android platform 36, build-tools 35.0.0.
- **Not present in the cache** (therefore deliberately not integrated):
  Jetpack Compose (all `androidx.compose.*`), `org.matrix.rustcomponents`,
  `org.unifiedpush.android.connector`, JUnit 4/5, `kotlinx-coroutines-test`,
  AndroidX libraries beyond the framework.
- **Result:** Phase 1 needs only locally present artifacts;
  no stubs in the compiled code (G5: instead, documented
  simulation implementations `InMemoryChannelClient` and
  `SimulatedPushTrigger`).

## 2. V1 — JVM Checks (`phase1Checks`)

**Observed on 2026-09-08:**
`./gradlew --offline :app:phase1Checks` → `BUILD SUCCESSFUL`;
report: `Prüfungen: 39, bestanden: 39, fehlgeschlagen: 0`.
*(Note, 2026-09-09: verbatim observed output of 2026-09-08 in German; the
check runner's strings are now English (G9) — current equivalent:
`Checks: 39, passed: 39, failed: 0`.)*

| Check group | Focus | Result |
| --- | --- | --- |
| `TimelineReducerChecks` (9) | timeline patches → message list (Reset/PushBack/PushFront/Insert/Set/Remove/Pop*/Truncate/Sequence) | passed |
| `FileSessionStoreChecks` (4) | session persistence round-trip, missing file, clear, without refresh token | passed |
| `PushControllerChecks` (4) | endpoint → pusher registration → REGISTERED, push → sync, failures | passed |
| `LoginViewModelChecks` (5) | login success/failure paths, empty fields, restoration incl. deletion | passed |
| `RoomListViewModelChecks` (3) | refresh/sync, RoomsUpdated event, logout | passed |
| `TimelineViewModelChecks` (5) | timeline filter by room ID, subscribe+sync, send/empty text/send failure | passed |
| `InMemoryChannelClientChecks` (6) | channel simulation: login required fields, session storage, send → event, negative cases, logout | passed |
| `PushChainChecks` (3) | SimulatedPushTrigger → REGISTERED → syncOnce → unregister | passed |

Full as-is output: `app/build/reports/phase1-checks.txt`.

## 3. V2 — Compilation & APK

**Observed on 2026-09-08:**

- `./gradlew --offline :app:phase1Checks :app:assembleDebug` →
  `BUILD SUCCESSFUL in 13s` (39 actionable tasks).
- `./gradlew --offline check` → `BUILD SUCCESSFUL in 16s`
  (58 actionable tasks; includes Lint with `abortOnError=false` and the
  check task).
- Repeat after `clean` (full rebuild):
  `./gradlew --offline clean :app:phase1Checks :app:assembleDebug` →
  `BUILD SUCCESSFUL in 6s` (40 actionable tasks, all executed),
  check report again 39/39.
- Artifact: `app/build/outputs/apk/debug/app-debug.apk` (≈ 3.9 MB),
  debug-signed with the local debug keystore. A release build was
  **not** executed (no reason, no release purpose in Phase 1).

## 4. V3 — Structure Check

**Observed on 2026-09-08** (`aapt2 dump badging`, build-tools 35.0.0):

```
package: name='at.d71.kailink' versionCode='1' versionName='0.1.0-phase1'
  compileSdkVersion='36'
minSdkVersion:'28'
targetSdkVersion:'36'
application-label:'KaiLink'
launchable-activity: name='at.d71.kailink.MainActivity'
```

- `applicationId` = `at.d71.kailink` ✓, `minSdkVersion` 28 ✓ (spec),
  `targetSdkVersion` 36 ✓.
- Manifest: only the `INTERNET` permission declared (Phase 1 establishes no
  connections; the permission is reserved for Phase 2); no
  FCM/Google code, no push receiver (Phase 2).

## 5. V4 — Device Verification

**Not possible in this environment (no emulator, no device).** The
written-out test setups are in
[`../manualtest-protokoll.md`](../manualtest-protokoll.md) (MT-1 through MT-8,
incl. the airplane-mode proof that Phase 1 works without network); all
test cases there are to be recorded here as **not observed** until they
have run on a device.

## 6. Known Deviations (Phase 1) and Open Risks

1. **Channel is a simulation** (`InMemoryChannelClient`): login credentials
   are not checked against a homeserver; messages live only in
   memory. E2EE is absent in Phase 1 — preparation and
   reference adapter see [`nachrichten-e2ee.md`](nachrichten-e2ee.md) and
   `app/src/phase2/`.
   *(Superseded on 2026-09-08, Phase 2: `AppGraph` wires
   `MatrixSdkChannelClient` to the real matrix-rust-sdk; the simulation
   remains in tests as the JVM reference. Runtime proof against a real
   homeserver: not observed.)*
2. **Framework Views instead of Jetpack Compose:** the Compose artifacts are missing from the
   offline cache; the ViewModels are unaffected by this (StateFlow contracts).
   *(unchanged in Phase 2)*
3. **JUnit replaced by `phase1Checks`** (see above); test task activation
   follows in Phase 2. *(Superseded on 2026-09-08: JUnit task active, 39/39.)*
4. **Push without a real distributor:** `SimulatedPushTrigger` drives the
   real `PushController` chain; notifications are not rendered.
   *(Partially superseded on 2026-09-08, Phase 2: `UnifiedPushRegistrar` +
   `KaiLinkPushReceiver` + manifest receiver are wired;
   notification rendering remains a limit. Runtime proof: not
   observed.)*
5. **Token persistence unencrypted** in the app files directory
   (app-private, but without Keystore encryption). *(unchanged)*
6. **Competing agents:** during the write-up, two further
   opencode agents ran in the same repo and overwrote files; they were
   stopped, after which the current state was completely re-verified (this
   report). A commit was deliberately not made.
   *(Phase 2 note, 2026-09-08: during the follow-up pass the commit
   `c8125fd` (among others package migration, adapter wiring) appeared externally;
   it was not changed or written back.)*

## 7. 0.2.4-phase1 — version footer, debug log upload (2026-09-09)

**V1 (JVM):** `./gradlew testDebugUnitTest assembleDebug --offline` →
`BUILD SUCCESSFUL`; JUnit `AllChecksTest` runs all 65 checks (was 39), report
`app/build/reports/phase1-checks.txt`: `Checks: 65, passed: 65, failed: 0`.
New check groups: `debugLogChecks` (ring buffer order/eviction/header/
thread-safety), `sendDebugLogChecks` (KaiL room resolution, `.txt` payload,
logged-out guard, busy-lock, word-boundary negative case for
`kailink-e2e-…` names).

**V2 (build):** `./gradlew :app:assembleEmulatorDebug :app:assembleDebugAndroidTest --offline`
(gate-internal) and final `./gradlew testDebugUnitTest assembleDebug --offline`
→ `BUILD SUCCESSFUL`.

**V3/V4 (emulator runtime smoke, kailink-atd35, Conduit via adb reverse):**

- Login screen: `text_version_login` renders `0.2.4-phase1` at
  `[0,1854][1080,1920]` (bottom of the 1080×1920 display); "Send log" not
  present on the login screen.
- Room list (signed in): `text_version_rooms` renders the version at
  `[0,1860][1080,1920]` (bottom); compact `btn_send_log` ("SEND LOG")
  visible.
- Timeline: `text_version_timeline` renders the version at
  `[0,1860][1080,1920]` (bottom, below the composer).
- Upload: with rooms `KaiL`, `kailink-e2e-a-…`, `kailink-e2e-b-…` present,
  "SEND LOG" delivered the buffer as an `m.room.message` with
  `msgtype m.file`, `filename kailink-debug-log-20260909-180237.txt` into the
  `KaiL` room (verified server-side via `/sync`), `mxc://localhost/…`
  content downloadable via `/_matrix/client/v1/media/download` — the
  downloaded bytes are the timestamped app log lines.
- Heuristic fix observed live: the first runtime smoke (marker "contains
  kail", no word boundary) sent the log into `kailink-e2e-b-…` — fixed to a
  word-boundary match and re-proven against the same room set.

**E2E gate:** `scripts/emulator-e2e.sh` → exit code 0, `OK (2 tests)`
(`MatrixE2eTest#twoAccountTimelineDeliveryUnencrypted`,
`TlsE2eTest#rustlsLoginOverHttpsFailsWithTlsErrorNotInitPanic`).

Residual risk: the debug-log upload is proven once end-to-end on the
emulator (unencrypted room), not continuously by the gate; encrypted-room
delivery goes through the same SDK send queue as Chunk-B messages (also
observed as `m.room.encrypted` in the gate room during the smoke).

## 8. Chunk C — push→notification path (2026-09-09)

**V1 (JVM):** `./gradlew testDebugUnitTest assembleDebug --offline` →
`BUILD SUCCESSFUL`; JUnit `AllChecksTest` runs all 94 checks (was 80 at
the start of this chunk; +14 new), report
`app/build/reports/phase1-checks.txt`: `Checks: 94, passed: 94,
failed: 0`. New check groups: `pushPayloadChecks` (5: wrapped/flat/
order-tolerant parsing, non-Matrix rejection, escaped quotes),
`pushMessageHandlerChecks` (6: cold-start restore→sync→resolve, warm
start, invalid-payload wake-up semantics, dropped push without session,
restore failure, resolver-failure fallback),
`PushNotificationPayloadChecks` +3 (fromRoom room-targeting, fallback,
null cases).

**E2E gate:** `scripts/emulator-e2e.sh` (emulator kailink-atd35,
Conduit + ntfy + nginx-TLS containers, `adb reverse`) — three runs
observed:

1. Run 1: the Gradle build inside the gate died with "Gradle build daemon
   disappeared" (`:app:compileDebugAndroidTestKotlin` /
   `:app:compileEmulatorDebugKotlin` in flight) — infrastructure failure
   (emulator + 3 containers + 3 GiB daemon heap on the 8 GiB box), not a
   code failure; no test ran.
2. Run 2 after freeing memory (`./gradlew --stop`, Kotlin daemon
   stopped): Chunk A + restore + C2 (pusher registration, `GET /pushers`
   with `app_id org.box44.kailink`, `data.url
   http://kailink-e2e-ntfy/_matrix/push/v1/notify`, `event_id_only`) +
   C2b (Conduit→ntfy publish) passed; the new **C2c assertion failed
   correctly**: `C2c: UnifiedPush payload not parseable:
   {\"notification\":{\"event_id\":…,\"room_id\":…}}` — the test had
   captured the JSON-escaped `message` field without unescaping it. The
   app-side parser was right to reject the escaped bytes; the delivered
   payload (seen verbatim in the failure) confirmed the expected wrapped
   shape including `room_id` and `counts.unread`. Fix: single-pass JSON
   unescape of the captured value in the test (same semantics as the
   distributor handing over decoded message bytes). No gate check was
   weakened.
3. Run 3 (final): **exit code 0, `OK (2 tests)`** (`MatrixE2eTest#
   twoAccountTimelineDeliveryUnencrypted` incl. the C2c leg,
   `TlsE2eTest#rustlsLoginOverHttpsFailsWithTlsErrorNotInitPanic`),
   `Time: 274.766`. C2c evidence (logcat `KaiLinkE2E`):
   `C2c ok: app-side payload parses (roomId=!7PSZ…, eventId=$KJg4…,
   unread=2)` — the app-side `PushPayload` parser understands the
   payload of the real Conduit→ntfy→UnifiedPush chain.

**V2/V3 (build + structure):** final `./gradlew testDebugUnitTest
assembleDebug --offline` → `BUILD SUCCESSFUL` (80 tasks). Staged APKs
(`dist/`, untracked): `kailink-0.2.6-phase1-arm64-debug.apk`
(77,623,264 bytes, SHA-256 `c1cfb8747e6428b85ebda2499c8cf5614210bdba68e8816abc3b42ab8c801b8f`)
and `kailink-0.2.6-phase1-x86_64-emulatorDebug.apk`
(84,186,311 bytes). `aapt2 dump badging` on the staged arm64 APK:
`package: name='org.box44.kailink' versionCode='4'
versionName='0.2.6-phase1'`, and the manifest dump shows
`KaiLinkPushReceiver` with `exported=true` (was `exported=false`) and
the connector actions — the structure fix that lets an ntfy
distributor actually deliver UnifiedPush broadcasts to the app.

**Not observed:** a real notification render on screen (needs the ntfy
distributor app installed on the emulator/device — V4, manual protocol
MT-6).

## 9. 0.2.7-phase1 — version self-identification, registration checklist (2026-09-10)

**V1 (JVM):** `./gradlew testDebugUnitTest assembleDebug --offline` →
`BUILD SUCCESSFUL`; JUnit `AllChecksTest` runs all 96 checks (was 94;
+2 new in `debugLogChecks`), report
`app/build/reports/phase1-checks.txt`: `Checks: 96, passed: 96,
failed: 0`. New checks: the `AppIdentity.startupLine` format
(`KaiLink <versionName> (versionCode <versionCode>)`) and that the
**first DebugLog line after app start contains the version string**.

**V2/V3 (build + structure):** `./gradlew assembleDebug --offline` →
`BUILD SUCCESSFUL`; `aapt2 dump badging`:
`package: name='org.box44.kailink' versionCode='5'
versionName='0.2.7-phase1'`.

**E2E gate:** `scripts/emulator-e2e.sh` (emulator-5554 kailink-atd35
booted, Conduit + ntfy + nginx-TLS containers, `adb reverse`) — observed
twice on 2026-09-10, both **exit code 0, `OK (2 tests)`**
(`MatrixE2eTest#twoAccountTimelineDeliveryUnencrypted`,
`TlsE2eTest#rustlsLoginOverHttpsFailsWithTlsErrorNotInitPanic`,
`Time: 274.813` on the first run); the gate built both APKs offline
against the new version.

**Emulator runtime smoke (2026-09-10, kailink-atd35):** fresh start of
the installed app (`am force-stop` + `am start`, `logcat -c` before)
— the very first `KaiLink` line is the self-identification:

```
09-10 08:39:03.400  2439  2439 I KaiLink : KaiLink 0.2.7-phase1 (versionCode 5)
```

**Not observed:** the ntfy-side registration display (V4, manual — see
`docs/features/f2-unifiedpush/device-registration-checklist.md`).

## 10. 0.2.7-phase1 Task 3 — on-device distributor chain (2026-09-10): first run **STOPPED / NOT PASSED**, investigation run (afternoon) classified the break and proved the chain

**First run — status: STOPPED before the trigger/notification assertion.** The required
distributor registration step failed; no rendered-notification assertion was
made. The incomplete test scaffolding for this task
(`app/src/androidTest/kotlin/org/box44/kailink/OnDevicePushE2eTest.kt`,
`app/src/androidTest/kotlin/org/box44/kailink/NotificationCaptureService.kt`,
plus the test-manifest listener entry) was removed on 2026-09-10 — it was
unfinished (literal `...` placeholders in the delivery leg) and must not
remain. The Task 1/2 changes (version self-identification, registration
checklist) are unaffected.

**Setup on 2026-09-10:** the ntfy Android 1.25.2 APK (binwiederhier/ntfy-android)
was provisioned temporarily outside the repo. Observed:

- ntfy Android 1.25.2 install succeeded on the emulator (kailink-atd35).
- `POST_NOTIFICATIONS` was granted to KaiLink.
- Real KaiLink UI sign-in succeeded (login screen → rooms screen).
- **Precise observed failure:** the required distributor registration step
  then failed — no new `up*` UnifiedPush endpoint appeared in Conduit
  `GET /pushers`. The only observed pusher was a stale synthetic
  `kailink-e2e-*` endpoint left by the existing server-side C2 test —
  i.e. the REGISTER → ntfy distributor → NEW_ENDPOINT → pusher chain did
  not produce a run-specific `up*` pushkey.

**Consequence:** Task 3 stopped at this step, before the Bob-send →
distributor → notification trigger leg. No notification capture or rendered-shade
assertion was made. The prior gate sections (8 and 9 above) prove the
server-side and app-side payload parsing chain only; what remains unproven
is the real distributor round-trip on device (V4 — see
`docs/features/f2-unifiedpush/device-registration-checklist.md`).
**Not observed with current infrastructure:** any on-device proof that the
ntfy distributor app registers a KaiLink subscription and renders a
notification. No credentials, tokens, or personal data were recorded in
this report.

## 11. 0.2.7-phase1 Task 3 — investigation run (2026-09-10, afternoon): classification (c), distributor chain proven on device, gate passed

Coordinator correction applied and verified: the pinned
`org.matrix.rustcomponents:sdk-android:26.09.08` AAR **does** contain
`jni/x86_64/libmatrix_sdk_ffi.so` (69,989,968 bytes); the morning finding
"zero entries under `lib/`" was an inspection of the **wrong archive
path** (`lib/` instead of `jni/`), and the earlier no-matching-ABIs
installation failure came from installing the arm64-only `debug` APK by
mistake. Build config and ABI configuration unchanged.

**Setup:** x86_64 AVD kailink-atd35 (API 35, emulator-5554), only the
rebuilt `app-emulatorDebug.apk` (versionCode 5, contains
`lib/x86_64/libmatrix_sdk_ffi.so`), ntfy Android 1.25.2
(`io.heckel.ntfy`) with `POST_NOTIFICATIONS` granted to both apps, gate
containers running, `adb reverse` 6167/8090/8443. Real KaiLink UI
sign-in against `http://127.0.0.1:6167`.

**Observed (details and log lines in
`docs/features/f2-unifiedpush/device-registration-checklist.md` and
`docs/features/f2-unifiedpush/verification.md`):**

- **Classification: case (c)** — both diagnostic register lines appear
  (restore and sign-in paths), but no `up*` endpoint and no `up*`
  pusher. Root cause: connector 3.3.5 sends REGISTER only for
  distributors already stored in its connector DB, and
  `UnifiedPushRegistrar` never calls `UnifiedPush.saveDistributor` in
  the `Found` branch (`UnifiedPushRegistrar.kt:32`) — the broadcast is
  never sent, `register()` returns silently, the state stays at
  "Push: registering …".
- **Distributor server: the Podman ntfy (http://127.0.0.1:8090), NOT
  ntfy.sh** (read-only inspection of the ntfy app's saved
  `DefaultBaseURL`; a shell REGISTER probe proved the distributor
  works end to end).
- **Chain proven after a diagnostic device-state injection** (inserting
  the `distributors` row exactly as `saveDistributor` would — no code
  change): REGISTER (shared identity) → `up*` subscription on the
  Podman server → NEW_ENDPOINT → KaiLink pusher at Conduit
  (`pushkey http://127.0.0.1:8090/up3QmdfC4EJh51?up=1`) → room list
  **"Push: registered (UnifiedPush)"** → real Bob message delivered as
  the gateway notify body → **rendered KaiLink notification** (channel
  "Push messages", `kailink-push-task3`, `kailink_bob: Task 3 push
  delivery probe`). Malformed-payload case handled per spec.
- **Environment note:** the ntfy distributor needs an active default
  network before it opens subscriber connections; the AVD booted with
  none. Provisioning the emulator's virtual AP
  (`cmd wifi connect-network AndroidWifi open` →
  `Active default network: 100`) is part of the run recipe.

**Full Task 3 gate (unchanged):** `./scripts/emulator-e2e.sh` →
**exit code 0, `OK (2 tests)`, `Time: 274.752`**
(`MatrixE2eTest#twoAccountTimelineDeliveryUnencrypted` with C2/C2b/C2c
evidence in logcat `KaiLinkE2E`,
`TlsE2eTest#rustlsLoginOverHttpsFailsWithTlsErrorNotInitPanic`).

**JVM checks:** `./gradlew testDebugUnitTest assembleDebug --offline` →
`BUILD SUCCESSFUL`; `Checks: 96, passed: 96, failed: 0`.

**Not proven with current infrastructure:** Conduit→push delivery for
the **app's own pusher** (its gateway URL defaults to
`https://ntfy.sh/_matrix/push/v1/notify`, so a Conduit-initiated push
for the app's own registration would leave for public ntfy.sh); the
`saveDistributor` registrar fix is left for the project owner. No
source code, build configuration, tests, or dependencies were modified
for these findings; no credentials, tokens, or personal data recorded.

## 12. 0.2.8 — fresh-install push E2E (gate leg 7): red first, then the `saveDistributor` fix turns it green (2026-09-10)

TDD order kept: the new E2E leg was written and run against the **unfixed**
0.2.7-phase1 code first, recorded the exact red failure at the
endpoint/pusher hop, and only then was the one-line registrar fix applied.

**Red gate run (current code at the time, before any production change):**
`./scripts/emulator-e2e.sh`, leg 6 green (`OK (2 tests)`), leg 7 red:

```
java.lang.AssertionError: fresh-install: no up=1 endpoint registered as Matrix pusher on Conduit after real UI sign-in (endpoint/pusher hop) — the REGISTER broadcast to the distributor never fired
	at org.box44.kailink.FreshInstallPushE2eTest.pollUntil(FreshInstallPushE2eTest.kt:219)
```
`Time: 93.66`, `Tests run: 1, Failures: 1`. The real UI sign-in had
completed (`fresh-install: UI sign-in finished (rooms screen visible, push
state: Push: registering …)`); the /pushers poll showed only leg 6's
synthetic `kailink-e2e-…` pusher, never an `up*?up=1` pusher. Logcat
evidence of the silent no-op (red):

```
D KaiLink : UnifiedPush registration: calling UnifiedPush.register (distributor: io.heckel.ntfy)
D KaiLink : UnifiedPush registration: UnifiedPush.register returned (distributor: io.heckel.ntfy)   (+53 ms, no distributor reply)
```

**Fix (production, one branch):** `UnifiedPushRegistrar.tryRegister`
`Found` branch now calls `UnifiedPush.saveDistributor(context,
resolved.packageName)` **before** `UnifiedPush.register(context)` —
matching the reference connector flow (`tryUseDefaultDistributor`:
`saveDistributor(context, it)` then register) and the connector KDoc
("saveDistributor must be called before this function"). In connector
3.3.5 `register` reads only the saved distributor from its store
(`getDistributor(context, store, ack=false)`) and returns silently when
none is saved; `Found` is a resolution, not persistence. The `ToSelect`
branch already saved and is unchanged.

**Version:** `0.2.8` (versionCode 6); first DebugLog line remains the
BuildConfig self-identification, now `KaiLink 0.2.8 (versionCode 6)`.

**Green gate run (full, after the fix):** `./scripts/emulator-e2e.sh` →
**exit code 0** (script `GATE_STATUS`, worst-leg semantics):
leg 6 `OK (2 tests)` (`Time: 274.796`), leg 7 `OK (1 test)`
(`Time: 94.006`). Leg 7 chain observed in logcat (`KaiLinkE2E` /
`KaiLink`):

- real UI sign-in → rooms screen; `Push: registering …` during registration;
- `UnifiedPush registration: calling UnifiedPush.register (distributor: io.heckel.ntfy)` → 20 ms later `register returned`, **then the distributor replies**: `UnifiedPush endpoint registered as Matrix pusher (gateway: https://ntfy.sh/_matrix/push/v1/notify)` + `Push endpoint registered as Matrix pusher`;
- `fresh-install: endpoint registered as pusher: http://127.0.0.1:8090/upYCCyckyIljkU?up=1` (the app's real up* endpoint as pushkey on Conduit, app_id `org.box44.kailink`);
- `fresh-install: Bob has sent: fresh-install-1789061625929 (event $M1DXb0-RiYi-XDaQQouvIvDDNlOQbrloJLD0vpQF5wk)` — real event id;
- notify body published to the real topic: `fresh-install: notify body published to http://127.0.0.1:8090/upYCCyckyIljkU?up=1 (existing chain bytes)`;
- **outermost observable effect:** `fresh-install ok: notification rendered` (41 ms after the publish; `dumpsys notification --noredact` contains the KaiLink notification with the sent body).

**Installed version on the emulator:** `versionCode=6 versionName=0.2.8`;
identity line in logcat: `I KaiLink : KaiLink 0.2.8 (versionCode 6)`.

**JVM checks:** `./gradlew testDebugUnitTest assembleDebug
assembleEmulatorDebug --offline` → `BUILD SUCCESSFUL` (28 executed/91
up-to-date in the fix run); `Checks: 96, passed: 96, failed: 0`.

**What leg 7 proves / what it cannot prove (honesty):** proven — the
on-device distributor registration on a genuinely fresh KaiLink install
(`pm clear org.box44.kailink` only; ntfy distributor state untouched),
the real `up*` endpoint as a Conduit pusher for the app's own pusher,
and the full delivery→rendering path through the real ntfy distributor
into `KaiLinkPushReceiver` → `PushMessageHandler` → `PushNotifier` with
gateway-identical bytes. Not proven: the **Conduit→gateway hop for the
app's own pusher** — the registered `data.url` remains
`https://ntfy.sh/_matrix/push/v1/notify` (`PushConfiguration.DEFAULT_GATEWAY_URL`),
so a Conduit-initiated push for the app's own registration would leave
for public ntfy.sh where nobody listens for the local topic. The gate
proves that hop only for its synthetic leg-6 pusher; the delivery leg of
leg 7 publishes the exact notify body to the real topic (the same bytes
Conduit posts, gate step C2b). Changing the default gateway URL is a
project-owner decision and was **not** made here. A physical-device run
remains the check for real GMS-less hardware behavior.

## 13. 0.2.9 — push-path notification fetch carries the session's sliding sync version (2026-09-11)

TDD order kept: the JVM checks for the new contract
(`NotificationClientVersionChecks`) ran RED against the pre-fix wiring
before the forwarding fix was implemented.

**Bug (0.2.8, device log):** the push-path notification resolution
failed with `VersionIsMissing` ("Sliding sync version is missing") on a
device whose main client had detected NATIVE. The 0.2.8 construction of
the SDK `NotificationClient` was version-blind; the SDK builds the
notification sliding sync from the parent client and fails for a
version-less/`NONE` client — the push-path client's version is
restore-set from the persisted session record (mechanism and SDK source
paths in `docs/decisions.md`).

**RED run** (`./gradlew testDebugUnitTest --offline` → `BUILD FAILED`,
task `:app:testDebugUnitTest`; report
`app/build/reports/phase1-checks.txt`):

```
[ERROR]  notification fetch: main client's detected NATIVE mode flows into the
         construction seam — construction seam must receive the NATIVE version
         detected by the main client — expected: NATIVE, actual: null
[ERROR]  notification fetch: main client's NONE mode flows into the construction
         seam as NONE — construction seam must receive NONE for a classic-/sync
         session (no drop to version-less) — expected: NONE, actual: null
Checks: 98, passed: 96, failed: 2
```

Test strengthening before the fix (no assertions weakened): NONE check
decorrelated from the URL (`https://matrix.org` + NONE session), plus a
session-less defensive check (`null` version → SDK default semantics).
Strengthened RED: same two failures (`Checks: 99, passed: 97, failed:
2`).

**Fix:** `MatrixSdkChannelClient.fetchNotification` maps
`activeSession.slidingSyncMode` → `domainModeToSdkVersion` and forwards
the version into the explicit `notificationClientFactory(Client,
SlidingSyncVersion?)` seam. Default factory: NATIVE/`null` → standard
SDK construction (parent client carries the DISCOVER_NATIVE-detected or
restore-set version — "SDK DISCOVER_NATIVE semantics where supported");
NONE → no construction, fetch returns `null` → room-list fallback with
an explicit log line (Conduit/fallback behavior preserved; the doomed
`VersionIsMissing` call is replaced by a deterministic skip).

**GREEN runs:**

- `./gradlew testDebugUnitTest --offline` → `BUILD SUCCESSFUL`;
  `Checks: 99, passed: 99, failed: 0`.
- `./gradlew testDebugUnitTest assembleDebug --offline` → `BUILD
  SUCCESSFUL` (80 tasks: 3 executed, 77 up-to-date).

**Version:** `0.2.9` (versionCode 7); first DebugLog line stays the
BuildConfig self-identification (`KaiLinkApp.onCreate` →
`AppIdentity.startupLine`), now `KaiLink 0.2.9 (versionCode 7)`;
`aapt2 dump badging` of the rebuilt APK:
`versionCode='7' versionName='0.2.9'`.

**Gate run on 0.2.9:** `./scripts/emulator-e2e.sh` → both legs OK
(leg 6 `OK (2 tests)`, `Time: 274.812`; leg 7 `OK (1 test)`,
`Time: 94.031`); installed build on the emulator:
`versionCode=7 versionName=0.2.9`; identity line in logcat:
`I KaiLink : KaiLink 0.2.9 (versionCode 7)`. Leg 7: real UI sign-in →
`Push: registered (UnifiedPush)` → real `up*` pusher on Conduit → real
event through the real distributor → **notification rendered** (body
asserted in `dumpsys notification --noredact`). The SDK notification
fetch ran its notification sliding sync against the gate homeserver
(observed span: `get_notification{…} > try_sliding_sync >
sync_once{conn_id="notifications"} … status=200`) — the NATIVE
construction path of the fix is exercised through the real chain.

**What the gate proves / what it cannot prove (honesty):** proven — the
NATIVE construction path end-to-end on the gate, both JVM-mapped modes
(NATIVE/NONE) reaching the construction seam, the defensive `null`
branch, and the preserved build/sign-in/push chain (all existing
assertions intact). Not provable with current infrastructure — the
**NONE fetch branch end-to-end**: the current Conduit gate image now
advertises `org.matrix.simplified_msc3575` in `/versions` (observed
2026-09-10/11), so gate sessions detect NATIVE and a sliding-sync-less
session no longer occurs in the gate (the 0.2.5-era "Conduit does not
serve the flag" statement is outdated for the current image). The NONE
branch is covered by the JVM wiring checks; its end-to-end behavior
(skip → room-list fallback, explicit log line) requires a
sliding-sync-less homeserver. **Required 0.2.9 device retest** (not
covered locally): matrix.org push → SDK-resolved notification content
with no `VersionIsMissing` in the Send-log dump; optionally a
Conduit-class homeserver for the NONE path. Follow-up (owner decision,
not implemented): re-detect the sliding sync mode at restore for
stale-NONE session records on capable servers (details in
`docs/decisions.md`). No credentials, tokens, or personal data were
recorded.

## 13. 0.2.9 — explicit sliding sync version forwarding to the push-path `NotificationClient`: red first, then the forwarding fix turns it green (2026-09-11)

**Bug (0.2.8 device evidence):** the push-path notification resolution
(`MatrixSdkChannelClient.fetchNotification`) constructed the Rust SDK
`NotificationClient` (`NotificationProcessSetup.MultipleProcesses`)
**without carrying the main client's detected/persisted sliding sync
version**. On matrix.org the main client detects NATIVE via
`SlidingSyncVersionBuilder.DISCOVER_NATIVE`, but the version-less
notification construction builds its short-lived notification sliding sync
from the parent client and fails with `VersionIsMissing` ("Sliding sync
version is missing") — the notification fetch died before any payload
mapping, leaving only the room-list fallback. The FFI
`notificationClient` constructor takes no version parameter, so the fix
carries the version as an explicit app-side input.

**TDD order kept:** the seam checks
(`app/src/test/kotlin/org/box44/kailink/testing/NotificationClientVersionChecks.kt`,
registered in `AllChecks.runAllChecks`) assert the version flow into the
new `notificationClientFactory` construction seam
(`suspend (Client, SlidingSyncVersion?) -> NotificationClient?`).

**Red run (reproduced 2026-09-11):** with the 0.2.8-style wiring
(seam present, version not forwarded — construction invoked with `null`):
`./gradlew :app:testDebugUnitTest --rerun --offline` → `BUILD FAILED`,
`Checks: 99, passed: 97, failed: 2`:

```
AFFECTED: notification fetch: main client's detected NATIVE mode flows into the construction seam — construction seam must receive the NATIVE version detected by the main client — expected: NATIVE, actual: null
AFFECTED: notification fetch: main client's NONE mode flows into the construction seam as NONE — construction seam must receive NONE for a classic-/sync session (no drop to version-less) — expected: NONE, actual: null
```

**Fix (production):** `fetchNotification` maps the active session's mode
(`activeSession?.slidingSyncMode` → `domainModeToSdkVersion`) and passes
it to `obtainNotificationClient` → `notificationClientFactory`. Default
factory semantics per version: `NATIVE`/`null` constructs the SDK
`NotificationClient` (MultipleProcesses — the push process has no
`SyncService`); `NONE` skips construction (the notification sliding sync
could never build on a Conduit-class homeserver), logs the skip, returns
`null` → the caller falls back to the room-list payload (unchanged
behavior). The session-less defensive state maps to `null` (SDK default
semantics, no crash). A recording-factory check proves the version comes
from the session's mode, never the homeserver URL (matrix.org URL paired
with a NONE session yields `NONE` at the seam).

**Version:** `0.2.9` (versionCode 7); first DebugLog line remains the
BuildConfig self-identification, now `KaiLink 0.2.9 (versionCode 7)`.

**Green run:** `./gradlew testDebugUnitTest assembleDebug --offline` →
`BUILD SUCCESSFUL`; `Checks: 99, passed: 99, failed: 0`
(`app/build/reports/phase1-checks.txt`).

**What the gate proves / what it cannot prove (honesty):** the E2E gate
(`scripts/emulator-e2e.sh`) is unchanged; the current Conduit gate image
advertises `org.matrix.simplified_msc3575: true` (observed 2026-09-10/11
on `http://127.0.0.1:6167`), so gate sessions detect NATIVE and the
NATIVE construction path of the fix is exercised end-to-end through the
real chain (leg 6/7, notification rendered — see the 0.2.9 section in
`docs/features/f2-unifiedpush/verification.md`). What the gate cannot
prove: the NONE skip branch (a sliding-sync-less session no longer occurs
there — the JVM checks prove the seam contract at the wiring level), and
the real push path against a sliding-sync-capable homeserver (e.g.
matrix.org) — a physical-device run remains the check that
`VersionIsMissing` no longer fires on-device. (Correction 2026-09-11:
this paragraph previously claimed gate sessions are `NONE`;
contradicted by the observed `/versions` flag.)

**Gate status (2026-09-11, latest verification attempt):** blocked by
unavailable emulator — `emulator-5554` was offline, so the pure gate
verification failed at its precondition check (exit 2,
`Emulator not reachable: emulator-5554`) before any containers or tests
ran; no gate check, assertion, or emulator setup was changed. The
earlier "Gate run on 0.2.9 (2026-09-11)" records above stand as
recorded.

## 14. 0.2.9 — verified emulator E2E gate run (2026-09-11)

**Observed on 2026-09-11:** `scripts/emulator-e2e.sh` ran once on 0.2.9
and passed all 3 legs: two-account E2E (2 instrumentation tests,
`Time: 274.778`) and fresh-install push E2E (1 instrumentation test,
`Time: 93.922`) — total measured instrumentation time 368.700 seconds;
overall gate **exit code 0**.

**Run conditions (documented):** emulator booted with
`emulator -avd kailink-atd35 -no-window -no-audio -no-boot-anim -no-snapshot`;
AndroidWifi connected; `pm path io.heckel.ntfy` succeeded (distributor
app present as a device precondition); host available memory was
6216 MiB.

**Earlier leg-6 failures (classification):** the earlier leg-6 failures
were environmental (overlapping emulator instances and host memory
pressure), not demonstrated code failures.

**What the gate proves / what it cannot prove (honesty):** this gate
proves the Conduit plus fresh-install/device push chain including the
rendered notification. It does **not** prove the matrix.org native
rendering path; a device test remains required.

## 15. 0.2.10 — push diagnostics and restore-mode reconciliation (2026-09-11)

**Defects found and fixed:** the push handler previously logged only a bare
parse failure, did not identify whether its sliding-sync mode came from the
persisted session or the warm in-memory session, and did not expose why the
room-list fallback rendered or suppressed a notification. Cold restore also
passed a stale persisted mode back into the SDK after `DISCOVER_NATIVE` had
re-detected the homeserver capability, so the session could not self-heal.
KaiLink 0.2.10 now emits bounded, redacted parse-shape diagnostics; records
mode source, mismatch, restore reconciliation, and fallback outcome; and uses
the last server-verified discovery result in both directions (`NONE` to
`NATIVE` and `NATIVE` to `NONE`) before restoring and persisting the session.
No credentials, tokens, URLs, message bodies, or room display names are
written to the debug log.

**TDD evidence:** the two new JVM check groups were written RED against the
pre-0.2.10 behavior and registered in `AllChecks.runAllChecks`: parse-failure
shape/redaction checks and push mode-source/fallback/reconciliation checks.
The final run was green: `./gradlew testDebugUnitTest assembleDebug
--offline` completed with `BUILD SUCCESSFUL`; the phase-1 report recorded all
registered checks passing (including the new diagnostics checks).

**Final emulator gate evidence:** the existing final run is recorded in
`/tmp/opencode/kailink-0.2.10/gate-run-final.log`. Its observable final lines
are leg 6 `Time: 274.726`, `OK (2 tests)`, then leg 7
`Time: 93.896`, `OK (1 test)`, with successful streamed installs and overall
gate exit code 0. The gate crossed the Conduit, ntfy, distributor, Android
emulator, and rendered-notification boundaries. The log does not contain a
package dump proving the installed version, so this document does not infer
one from the gate log; the source build version is separately `0.2.10`,
versionCode 8.

**Encrypted-gate blind spot:** this gate proves the unencrypted two-account
timeline path and the real UnifiedPush notification path, but it does not
prove encrypted-message decryption or interactive verification. An
`InvalidSignature` was observed during encrypted experimentation; it is
recorded as an observed protocol/test-environment issue, not claimed as a
0.2.10 fix and remains out of scope for this delivery.

**Future encrypted gate plan:** build one small matrix-nio[e2e]-only Python
test harness for both stages. Stage 1 will create a fresh Conduit account,
create or join an encrypted room, send an encrypted message, and assert that
KaiLink decrypts and displays the unverified message. Stage 2 will use
`Sas.get_decimals()` in that same harness while the KaiLink debug flow
displays and confirms the decimal SAS; the gate will compare the codes. The
harness will not use matrix-commander or a custom Rust client. Before it is
implemented, verify the pinned AAR binding name and pin/record the harness
license. Until then, encrypted rendering and SAS remain device-test work.

**Process record:** on 2026-09-11 the main coder model switched to Luna
(high). Rationale: the GLM reasoning knob was unavailable, this was a T2
practice win, and Martin requested the quality push.

**Agent-room requirement:** Martin's decision is that agent rooms are always
E2EE. A later release must visibly warn when an agent room is unencrypted;
0.2.10 records the requirement but intentionally does not implement that
warning or alter room creation.

## 16. 0.2.11 — restored room-list startup fix (2026-09-11)

**Bug and red evidence:** the preserved regression in
`app/src/test/kotlin/org/box44/kailink/testing/RoomListViewModelChecks.kt`
created a restored session whose client already contained 12 rooms. The
pre-fix `RoomListViewModel` constructor called `refresh()`, causing an
unexpected sync before reading rooms. The focused run reported:
`Checks: 114, passed: 113, failed: 1`, with
`restored session shows 12 already-loaded rooms without activity` failing:
`restored room list does not sync — expected: 0, actual: 1`.

**Root cause and fix:** startup had no cache-read path. `init` entered the
sync/live-sync path, while `RoomListUiState` was updated from
`RoomsUpdated` or after refresh. The fix removes the constructor's implicit
`refresh()` and adds `loadCachedRooms()`, which reads `channelClient.rooms()`
and publishes the snapshot directly to `RoomListUiState`. Explicit
`refresh()` still performs one-shot sync and live-sync startup, so refresh
behavior and all existing assertions remain intact. This is the small
equivalent of Element X's replayed room-summary design in
`features/home/impl/src/main/kotlin/io/element/android/features/home/impl/datasource/RoomListDataSource.kt`,
backed by `RoomSummaryListProcessor` in
`libraries/matrix/impl/src/main/kotlin/io/element/android/libraries/matrix/impl/roomlist/RoomSummaryListProcessor.kt`.

**Green JVM evidence:**
`./gradlew --offline :app:testDebugUnitTest --tests org.box44.kailink.testing.AllChecksTest`
completed with `BUILD SUCCESSFUL`; the check runner completed all 114 checks
without failure. The focused regression now exposes all 12 cached rooms with
zero `syncOnce()` and zero message sends.

**Full E2E gate:** `./scripts/emulator-e2e.sh` completed with exit code 0.
The unchanged three-leg gate built and installed the emulator and test APKs,
then recorded:

- Leg 6: `OK (2 tests)`, `Time: 274.764` (two-account timeline plus TLS path).
- Leg 7: `OK (1 test)`, `Time: 93.929` (fresh-install UnifiedPush endpoint,
  pusher, and rendered notification).

The gate proves the existing Conduit, ntfy, distributor, Android, and rendered
notification boundaries. It does not specifically prove process-restart
restoration with preloaded room state; that behavior is covered by the JVM
regression and remains a device-test scope gap. No cleanup or retry was needed.

**Version and artifact:** source version is `0.2.11`, versionCode `9`.
`./gradlew --offline :app:assembleDebug` completed with `BUILD SUCCESSFUL`.
The arm64 debug APK is
`app/build/outputs/apk/debug/app-debug.apk`, size `77656148` bytes, SHA-256
`43c5c8696bc2968c384bcad52c00c78888955fd6f644ea0fb619e558317cf449`.
