# KaiLink — Agent Rules

## Toolchain
- JDK 21 is pinned in `mise.toml` (no separate install needed).
- Android SDK lives at `/home/dev/android-sdk`, referenced via `local.properties`.
- `adb` is not on PATH. Use `/home/dev/android-sdk/platform-tools/adb`.
- AVD `kailink-atd35` is API 35 with `atd` image. Boot headless (may be backgrounded):
  `/home/dev/android-sdk/emulator/emulator -avd kailink-atd35 -no-window -no-audio -no-boot-anim`
  then wait for `sys.boot_completed=1` before use.
- Rootless Podman 5.x supplies the E2E containers.

## Build & Test
- Debug APK: `./gradlew assembleDebug` — `--offline` is sufficient.
- JVM checks: `./gradlew testDebugUnitTest assembleDebug --offline`.

## E2E Gate
- Run `scripts/emulator-e2e.sh` only when `emulator-5554` is running.
- The script starts the Conduit homeserver and ntfy gateway as rootless containers, performs `adb reverse` for `tcp:6167` and `tcp:8090`, registers throwaway accounts, builds both APKs offline, and runs the two-account instrumented test (leg 6) plus the fresh-install push test (leg 7: `pm clear` of org.box44.kailink ONLY — the ntfy distributor app is a device precondition and is never touched); do not duplicate any of these steps.
- Credentials are supplied with `-e` arguments or `E2E_ALICE_*` / `E2E_BOB_*` environment variables.
- If the gate fails, diagnose the failure rather than masking it.
- Stage 1 encrypted-room leg 4 builds `scripts/matrix-nio-sender/` as
  `localhost/kailink-matrix-nio-sender:0.26.0`, starts it only after leg 3,
  reverse-tunnels its trigger port 8088, and cleans its per-run state/container
  on exit. The container reaches Conduit as `kailink-e2e-conduit:6167` on the
  shared `kailink-e2e` network; it does not depend on adb reverse for Matrix
   traffic. See `docs/decisions.md` for the pinned package/license and proof
   boundaries. Option C substitutes only Conduit's autonomous push decision
   with a wake payload sent to Alice's actual local pusher; gateway conversion,
   distributor delivery, app parsing, sync, decrypt, and notification remain
   real in-gate. A matrix.org device test is required for autonomous server
   push proof.

## Permissions & External Paths
- Permission allowlist: `/home/dev/.config/opencode/opencode.jsonc` (edit/bash permissions and `external_directory` records for SDK/toolchain paths).
- If a needed external path is blocked, add a record there rather than working around it.

## Library docs
- Context7 pinned IDs: Jetpack Compose `/websites/developer_android_develop_ui_compose`, Gradle `/gradle/gradle`, matrix-rust-sdk `matrix-org/matrix-rust-sdk`.
- Use a pinned ID directly; call `resolve-library-id` only when the ID is unknown, to save quota.
- Use DeepWiki for repository questions — it is free and does not consume Context7 quota.
- Built-in websearch is available when `OPENCODE_ENABLE_EXA=1`.
- Project-specific operational knowledge belongs in this file; skills are only for project-wide recipes.

## Project conventions (0.2.9)
- App logging: route all app log output through `DebugLog` (`data/log/`), normally via the `AppGraph.log` seam. Never append credentials or tokens to the log buffer (G7).
- Version self-identification: `KaiLinkApp.onCreate` appends the very first DebugLog line — the `AppIdentity.startupLine(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)` format, e.g. `KaiLink 0.2.9 (versionCode 7)` — before anything else runs; do not append anything before it. The signed-in rooms screen additionally shows a one-line About row (`text_about_rooms`, `KaiLink <VERSION_NAME>`) above the footer. On-device UnifiedPush registration (exact actions, log strings, failure-mode gaps, and the 0.2.8 resolution) is documented in `docs/features/f2-unifiedpush/device-registration-checklist.md`.
- UnifiedPush registrar invariant (0.2.8): every `UnifiedPushRegistrar.tryRegister` branch calls `UnifiedPush.saveDistributor` BEFORE `UnifiedPush.register` — connector 3.3.5 broadcasts REGISTER only to a saved distributor; `ResolvedDistributor.Found` is a resolution, not persistence.
- SDK diagnostics (new in 0.2.5-phase1): the Rust SDK/HTTP client tracing goes to logcat AND rotating files under `cacheDir/matrix/tracing` (path constant `SdkLogTailer.TRACE_DIRECTORY`, prefix/suffix `SdkLogTailer.DEFAULT_PREFIX`/`DEFAULT_SUFFIX`; configured in `KaiLinkApp` via `initPlatform(TracingFileConfiguration)`). `SdkLogTailer` (started in `AppGraph`) tails appended lines into `SdkLogBridge`, which level/target-filters (ERROR/WARN always, sync-relevant INFO, never DEBUG/TRACE) before they reach the bounded `DebugLog` ring buffer. Keep both the file config and the tailer bounded; never loop tailer failures back into the log.
- Sliding sync (new in 0.2.5-phase1): client builds use the SDK-sanctioned discovery `SlidingSyncVersionBuilder.DISCOVER_NATIVE` (`ClientBuilder.build()` → `GET /versions` → `unstable_features["org.matrix.simplified_msc3575"]` → NATIVE; see docs/decisions.md for exact SDK source paths). Only when the discovery build fails is the client rebuilt once with the SDK default `NONE` (Conduit-class servers, discovery transport errors) — never pre-emptively. Live sync must not be disabled or silently swallowed: failures are surfaced (room list error/log), while sign-in, restore, `syncOnceV2`, send log and uploads keep working. The detected mode is part of `Session` (`SlidingSyncMode`) and persists in both session stores; legacy sessions (pre-0.2.5) restore as NONE.
- Notification client version forwarding (new in 0.2.9): `MatrixSdkChannelClient.fetchNotification` maps the active session's `slidingSyncMode` via `domainModeToSdkVersion` and passes it explicitly to the `notificationClientFactory` construction seam (`(Client, SlidingSyncVersion?) -> NotificationClient?`); the FFI `notificationClient` constructor has no version parameter, so the version-less construction fails with `VersionIsMissing` on NATIVE-capable homeservers. Default factory semantics: `NATIVE`/`null` constructs the SDK `NotificationClient` (MultipleProcesses); `NONE` skips construction → room-list fallback. Never construct the notification client version-less on the push path; the seam is the JVM-testability point (`NotificationClientVersionChecks`).
- Version footer: every screen layout includes a version footer TextView (`text_version_<screen>`); screen IDs must stay distinct because all screens are inflated simultaneously in `activity_main`. Set the text from `BuildConfig.VERSION_NAME` (the `buildConfig` build feature is enabled for this).
- Send debug log: the upload target is the first room whose display name contains the standalone word `kail` (case-insensitive word-boundary match — `kailink-*` room names must NOT match; see docs/decisions.md). Uploads go through `ChannelClient.sendFile` → `Timeline.sendFile` (pinned SDK attachment path), never through hand-rolled HTTP. Availability follows the authenticated session (`RoomListUiState.userId`), never the live-sync state.
- UnifiedPush push path (new in 0.2.6-phase1): delivery is UnifiedPush with the ntfy distributor only — no FCM/Google. `KaiLinkPushReceiver` must stay `exported="true"` in the manifest (a distributor broadcasts as another app targeting this package; `exported=false` silently blocks every UnifiedPush message). Endpoint/registration events stay with `PushController`; every rotated endpoint is re-registered as a Matrix pusher.
- Push message semantics (new in 0.2.6-phase1): `PushPayload.parse` decodes the UnifiedPush message bytes, which are the raw notify body the homeserver POSTed to the gateway (`{"notification":{…}}` — ntfy publishes the whole body); a `null` result is a non-Matrix payload → wake-up sync only, never drop the sync.
- Receiver path (new in 0.2.6-phase1): `KaiLinkPushReceiver.onMessage` hands the bytes to `PushMessageHandler` (mutex-serialized, cold-start safe): parse → restore session from `SessionStore` (no session → push dropped) → `syncOnce()` → notification resolution (`MatrixSdkChannelClient.fetchNotification`, SDK `NotificationClient`; room-list fallback `PushNotificationPayload.fromRoom`) → `PushNotifier` render.
- Existing push coverage (0.2.6-phase1): the gate exercises the push chain (C2 pusher registration → C2b Conduit→ntfy publish → C2c app-side `PushPayload.parse` of the real chain); do not add parallel push steps or containers.
- Unit checks: new pure-Kotlin behavior gets JVM checks registered in `AllChecks.runAllChecks` (`app/src/test/kotlin/org/box44/kailink/testing/`).

## Hard Rules
- Never weaken gate checks or test assertions.
- Never edit `local.properties`.
- Never commit build outputs or `oc-proof*` throwaway files.
- Report observed exit codes and output; never claim results from commands that were not run.

## Test discipline (added 10.09.2026, Martin)
- TDD in the practical sense: the e2e test is the FIRST artifact of a feature, not the last. Before OpenCode implements a feature, the test that will prove it exists in red or scaffolded form; implementation then makes it green. Unit checks support e2e tests; they never replace them.
- e2e tests are the primary tests. A feature counts as implemented only when its e2e proof runs in the project gate (scripts/emulator-e2e.sh or a documented successor).
- The gate must cover the chain the user actually uses. When a feature's value crosses an app/process boundary (server, broker, distributor app), the e2e test crosses the same boundary and asserts at the outermost observable effect (e.g. a rendered notification), never only at an internal seam. If the current gate cannot prove that chain, extending the gate is the first step of the feature order.
- Gate changes for coverage are test infrastructure, not project-code edits; keep existing assertions intact (no weakening).
- Every report on a delivery-bound feature states honestly: what the gate proves, what it cannot prove with current infrastructure, and what remains for a device test.
