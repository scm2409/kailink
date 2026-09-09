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
