# Decisions

- The official Matrix Rust SDK `sdk-android:26.09.08` handles protocol,
  sync, and E2EE.
- UnifiedPush `3.3.5` uses the topic URL as the Matrix `pushkey`; the
  configurable default gateway is
  `https://ntfy.sh/_matrix/push/v1/notify`.
- Sessions are protected in production with AES-256-GCM and a non-exportable
  Android Keystore key; JVM tests use the file test double.
- The Phase-1 version is `0.2.4-phase1` (versionCode 2).

- For Android instrumentation tests, AndroidX Test Runner `1.6.2`,
  AndroidX Test JUnit `1.2.1`, and Test Core `1.6.1` are used. The first
  emulator proof runs via a separate APK installation and
  `am instrument`, because the x86_64 emulator variant does not automatically
  create its own `connectedEmulatorDebugAndroidTest` Gradle task.

- The emulator gate script deliberately terminates the run with exit code 3 when
  the full Matrix/E2EE/UnifiedPush chain is demanded after the green HTTP,
  build, and instrumentation smokes. Without a real test
  configuration channel, controlled E2EE key exchange, and
  distributor proof, a green exit code would be misleading.

- The E2EE types verified against `sdk-android:26.09.08` live in the
  `uniffi.matrix_sdk_crypto` namespace; the optional test configuration sets
  `CollectStrategy.ALL_DEVICES` and `DecryptionSettings(TrustRequirement.UNTRUSTED)`.
  Cross-signing and key backup are not tested or claimed.
  Commit: `8db4c4e` (Chunk B, encrypted second leg).

- UniFFI builders are immutable (source:
  `bindings/matrix-sdk-ffi/src/client_builder.rs` in the matrix-rust-sdk —
  every setter takes `self: Arc<Self>` and returns a NEW builder).
  `MatrixSdkChannelClient.buildClient` ignored the
  return values — the build ran without any config
  (`ClientBuildError: ... must be called`). Fix: chain the return values
  (`builder = builder.homeserverUrl(...)` etc.). The login path
  never worked before, so there is no user-facing behavior change.
  Commit: `d9d4190`.
- Cleartext only in `emulatorDebug`: `network_security_config.xml`
  (`cleartextTrafficPermitted=true` only for `10.0.2.2`, `127.0.0.1`,
  `localhost`, `192.168.42.20`) + manifest overlay with
  `android:networkSecurityConfig`. Proof: `aapt` dump of the
  `app-emulatorDebug.apk` shows `networkSecurityConfig`;
  `app-debug.apk` does not contain it. The `androidTest` manifest has no effect
  (the test APK process follows the app policy).
- Conduit limits: no sliding sync (`VersionIsMissing` at
  `syncService().finish()`) — the E2E uses `syncOnce`; the emulator network is broken
  (`10.0.2.2` unreachable) — the run uses `adb reverse` to `127.0.0.1`.
  Commit: `919de4c` (C2b proof: Conduit→ntfy delivery).
- Android TLS false positive (`InvalidCertificate(Revoked)` for valid
  certificates, e.g. matrix.org): root cause is the rustls-platform-verifier
  Android path (vendored `rustls-tls/.../CertificateVerifier.kt`) letting
  Android fetch OCSP/CRL revocation data over the network. Since Let's Encrypt
  ended OCSP (August 2025), certificates only carry a CRL distribution point
  served over cleartext HTTP, which the Android network stack blocks; the
  failing check reported valid certificates as revoked
  (matrix-rust-sdk issue #6319, rustls-platform-verifier #221, fixed upstream
  by matrix-rust-sdk PR #6323 by switching Android to a webpki verifier with
  no network revocation checks). Choice: the pinned `sdk-android:26.09.08` is
  the newest release and provably does not contain #6323 (no
  `webpki-roots`/`rustls-native-certs` symbols in `libmatrix_sdk_ffi.so`), so
  instead of an impossible upgrade we ported the fix to the only seam we
  control: the vendored Kotlin verifier never fetches revocation data over the
  network and only honors server-stapled OCSP responses. All other validation
  (trust anchors, chain, signatures, validity, EKU, hostname in Rust) is
  preserved; this matches the upstream post-#6323 validation strength.
   `MatrixSdkChannelClient` additionally maps TLS/certificate failures to a
   readable user message ("Secure connection to the homeserver failed
   (certificate error). Check the server address.") while raw reqwest/hyper
   details go only to the log — extending the error mapping from `4bdba22`
   without replacing it.

- Debug log upload (0.2.4-phase1): the file goes through the pinned SDK's
  attachment path — `Timeline.sendFile(UploadParameters, FileInfo)` followed
  by `SendAttachmentJoinHandle.join()` (verified against the vendored
  `sdk-android:26.09.08` bindings). `UploadSource.Data` carries the ~1000-line
  buffer in memory as a `text/plain` `.txt`; the send queue encrypts it for
  E2EE rooms like any message. If the target room is not open, a temporary
  `Room.timeline()` is created and closed after `join()`.
- KaiL room resolution is deliberately a heuristic, not configuration: the
  upload target is the first room whose display name contains the standalone
  word `kail` (case-insensitive word-boundary match). The boundary is
  essential: room names like `kailink-e2e-a-…` (E2E gate) must not match —
  observed during the 0.2.4-phase1 runtime smoke test. Without a matching
  room the action surfaces a readable error ("No KaiL room found …"). No
  server-side search, no room picker (PoC).
- The in-app debug log (`DebugLog`, `data/log/`) is a synchronized ring
  buffer of ~1000 timestamped lines. `AppGraph.log` is the single app-log
  seam: everything that reaches `Log.d("KaiLink", …)` also lands in the
  buffer. It must never receive credentials or tokens (G7).
- The version footer renders `BuildConfig.VERSION_NAME` (build feature
  `buildConfig` is now explicitly enabled). All screens are inflated
  simultaneously inside `activity_main`, so per-screen footer views use
  distinct IDs (`text_version_login` / `text_version_rooms` /
  `text_version_timeline`).

## 0.2.7-phase1: version self-identification

- The very first `DebugLog` line after app start is the app
  self-identification from BuildConfig:
  `KaiLink <versionName> (versionCode <versionCode>)` —
  `KaiLinkApp.onCreate` appends it (and mirrors it to logcat) before
  anything else runs, so every "Send log" dump starts with the version.
  The format lives in the pure `data/log/AppIdentity` (JVM-checked:
  format + first-line-in-buffer containment, 96/96 checks).
- The signed-in rooms screen keeps the footer and adds a minimal
  one-line About row (`text_about_rooms`, `KaiLink <VERSION_NAME>`)
  directly above the footer.
- Version: `0.2.7-phase1` (versionCode 5).
- The on-device UnifiedPush registration flow is documented in
  `docs/features/f2-unifiedpush/device-registration-checklist.md`
  (cross-checked against the pinned connector 3.3.5 AAR constants, the
  UnifiedPush spec AND_3.1.0, and the ntfy distributor). Two honest
  findings from that cross-check, deliberately not fixed in code:
  the spec action `org.unifiedpush.android.connector.TEMP_UNAVAILABLE`
  is **not** declared in KaiLink's receiver intent-filter (a
  temporary-unavailable event is silently ignored), and an action named
  `POST_ENDPOINTS` does not exist anywhere in the connector/spec/ntfy —
  the endpoint-delivery action is `NEW_ENDPOINT`; if it is never
  delivered, the push state stays READY ("Push: registering …") and
  **no log line is emitted** (all documented as gaps).

- Authorized diagnostic-only change (0.2.7-phase1 UnifiedPush emulator
  investigation): `UnifiedPushRegistrar` now takes the standard
  `onLog: (String) -> Unit = {}` seam (wired to `AppGraph.log` in
  `AppGraph`, like `PushController`) and emits four DEBUG lines — before
  `UnifiedPush.register(context)` with the resolved distributor package
  name, after the call returns, and a no-distributor line for both
  `NoneAvailable` and `ToSelect`-with-empty-list. Registration behavior,
  control flow, and dependencies are unchanged; the log lines are not
  credentials (G7). Exact strings live in
  `docs/features/f2-unifiedpush/device-registration-checklist.md`; this
  closes the checklist's former "no distributor / unnamed distributor"
  log gaps. The `TEMP_UNAVAILABLE` and missing-`POST_ENDPOINTS` gaps
  above remain deliberately unfixed.

## 0.2.5-phase1: native sliding sync discovery (live-sync fix)

Root cause (0.2.4-phase1 behavior): the FFI `ClientBuilder` of the pinned
`sdk-android:26.09.08` defaults `sliding_sync_version_builder` to
`SlidingSyncVersionBuilder::None`, so no version discovery happens and the
built client carries `Version::None`. The SDK `SyncService` ("live sync")
builds its `SlidingSync` from the client version and fails for `Version::None`
with `VersionIsMissing` ("Sliding sync version is missing") — even
on homeservers that support native sliding sync such as matrix.org. `syncOnceV2`
(classic `/sync`) kept working, which is why the PoC appeared functional.

Fix (SDK-sanctioned, no workaround): `MatrixSdkChannelClient.buildClient`
first builds with `SlidingSyncVersionBuilder.DISCOVER_NATIVE`. During
`ClientBuilder.build()` this performs a `GET /_matrix/client/versions`
request and selects `Version::Native` iff the response's
`unstable_features` contain `"org.matrix.simplified_msc3575": true`
(ruma `FeatureFlag::Msc4186`); otherwise the build fails with
`VersionBuilderError::NativeVersionIsUnset` (UniFFI:
`ClientBuildException.SlidingSyncVersion`). Only after a failed discovery
build is the client rebuilt once with the SDK default
`SlidingSyncVersionBuilder.NONE` (classic `/sync` semantics; covers both a
capability-less homeserver such as Conduit in the E2E gate and discovery
transport errors) — never pre-emptively. Live sync is never disabled or
silently swallowed: on NONE sessions `startLiveSync` still runs and its
failure is surfaced (room list error + app log), while sign-in, restore,
`syncOnceV2`, send log and file uploads keep working. "Send log"
availability follows the authenticated session (`RoomListUiState.userId`),
never the live-sync state. The detected mode is stored in `Session`
(`SlidingSyncMode`, persisted in both session stores; legacy sessions
restore as NONE) and is passed back on restore, because the FFI restore
path sets the client's sliding sync version from the `Session` record —
without it a restored NATIVE session would degrade to NONE again.

Live verification (2026-09-09): `GET https://matrix.org/_matrix/client/versions`
returns `unstable_features["org.matrix.simplified_msc3575"] = true`
(native discovery succeeds there); Conduit does not serve the flag, which
is the only reason the E2E gate's Conduit session falls back to NONE.

Verification sources for `sdk-android:26.09.08` (artifact not git-tagged
upstream; verified against current upstream main at review time plus the
vendored AAR classes):
- `bindings/matrix-sdk-ffi/src/client_builder.rs` — `ClientBuilder::new()`
  defaults `sliding_sync_version_builder: SlidingSyncVersionBuilder::None`;
  `build()` maps `DiscoverNative` to the SDK `VersionBuilder::DiscoverNative`;
  `ClientBuildError::SlidingSyncVersion(VersionBuilderError)`.
- `crates/matrix-sdk/src/client/builder/mod.rs` — core `ClientBuilder::build()`
  performs `get_supported_versions(&homeserver, &http_client)` (GET `/versions`)
  only when `VersionBuilder::needs_get_supported_versions()` (DiscoverNative).
- `crates/matrix-sdk/src/sliding_sync/client.rs` — `VersionBuilder::build`:
  `Version::Native` iff `supported.features.contains(&FeatureFlag::Msc4186)`,
  else `VersionBuilderError::NativeVersionIsUnset`; `MissingVersionsResponse`
  when the `/versions` response is absent.
- ruma `crates/ruma-common/src/api/metadata.rs` —
  `FeatureFlag::Msc4186` is `#[ruma_enum(rename = "org.matrix.simplified_msc3575")]`.
- `crates/matrix-sdk/src/sliding_sync/builder.rs` + `error.rs` —
  `SlidingSyncBuilder::build` fails for `Version::None` with
  `VersionIsMissing` ("Sliding sync version is missing").
- `bindings/matrix-sdk-ffi/src/client.rs` — `restore_session_with` sets the
  client version from `session.sliding_sync_version`
  (`self.inner.set_sliding_sync_version(...)`).
- `bindings/matrix-sdk-ffi/src/platform/mod.rs` — tracing file layer:
  hourly rotation (`Rotation::HOURLY`), format
  `{timestamp} {LEVEL} {target}: {message} | {file}:{line}`, defaults 10 MiB
  total / 7 days (KaiLink: 2 MiB).

Library documentation provenance for this decision (exact pinned URLs):
- Context7 pinned IDs (AGENTS.md "Library docs"):
  `https://context7.com/websites/developer_android_develop_ui_compose`,
  `https://context7.com/gradle/gradle`,
  `https://context7.com/matrix-org/matrix-rust-sdk`.
- DeepWiki: `https://deepwiki.com/matrix-org/matrix-rust-sdk`
  (FFI default value, discovery flow, `VersionIsMissing` origin).

SDK diagnostics (0.2.5-phase1): `KaiLinkApp.initPlatform` now also passes a
`TracingFileConfiguration` (dir `SdkLogTailer.TRACE_DIRECTORY` under
`cacheDir`, prefix/suffix `kailink-sdk`/`.log`, 2 MiB total, 7 days). The
Rust file layer (see `bindings/matrix-sdk-ffi/src/platform/mod.rs`) writes
hourly-rotated files; `SdkLogTailer` (AppGraph, 5 s cadence) tails appended
bytes with per-file offsets into `SdkLogBridge`, which keeps ERROR/WARN
always, sync-relevant INFO targets (`matrix_sdk::client`,
`matrix_sdk::sliding_sync`, `matrix_sdk::http_client`), never
DEBUG/TRACE, truncates to 400 chars, and appends into the bounded
`DebugLog` ring buffer — so "Send log" dumps contain SDK/HTTP diagnostics
without credentials (G7).

## Chunk C (2026-09-09): real push→notification path, UnifiedPush/ntfy app side

Gap before this chunk: `KaiLinkPushReceiver.onMessage` ignored the
UnifiedPush message bytes, could not restore a session on a cold process
(`MatrixSdkChannelClient.syncOnce()` throws `No active session` — the
normal push case wakes a dead process), never rendered notification
content (`rooms()` carries `lastMessage = null`, so the payload was
always `null`), and the manifest receiver was `exported=false` — a
distributor delivers its broadcasts as another app targeting this
package, so with `exported=false` **no** UnifiedPush broadcast could
ever arrive.

Implementation (patterns studied from the reference corpora; **no code
copied**, see provenance below):
- Manifest: `KaiLinkPushReceiver` `exported=true`
  (`tools:ignore="ExportedReceiver"`) — the connector docs
  (`MessagingReceiver` KDoc) and Element X's
  `libraries/pushproviders/unifiedpush/src/main/AndroidManifest.xml`
  both require it.
- `data/push/PushPayload` — parses the UnifiedPush message bytes. The
  bytes are the body the homeserver POSTed to the push gateway: ntfy's
  Matrix gateway publishes the **entire notify body** to the topic
  (`server_matrix.go`, `newRequestFromMatrixJSON` reuses the original
  body), i.e. `{"notification":{"event_id":…,"room_id":…,"counts":{…}}}`
  — the same shape Element X's `UnifiedPushParser`/
  `PushDataUnifiedPush` decode. Field scanner instead of a JSON
  dependency (offline build); accepts the flat form defensively.
- `data/push/PushMessageHandler` — cold-start-safe orchestrator
  (mutex-serialized): parse → ensure session (`SessionStore.load` →
  `ChannelClient.restore`; no session → push dropped) → `syncOnce()` →
  notification resolution → payload. Non-Matrix payload = wake-up sync
  only. Mirrors Element X's receiver→parser→`PushHandler` flow and its
  "unable to retrieve session" behavior.
- `MatrixSdkChannelClient.fetchNotification(roomId, eventId)` — the
  pinned `sdk-android:26.09.08` ships `NotificationClient`
  (`Client.notificationClient(NotificationProcessSetup.
  MultipleProcesses)` — no `SyncService` needed in the push process;
  `SingleProcess` requires one) and
  `getNotification(roomId, eventId) → NotificationStatus`
  (`Event(NotificationItem)` / `EventFilteredOut` / `EventNotFound` /
  `EventRedacted`). Item → title (`roomInfo.displayName`), body
  (`TimelineEvent.content()` → `MessageLikeEventContent.RoomMessage`
  → `MessageType.Text/Notice/Emote` body; `RoomEncrypted` →
  undecryptable placeholder), sender, timestamp. This is the
  "NotificationClient wiring" open item of
  `docs/features/f2-unifiedpush/tasks.md`. Objects are disposed
  (`destroy()`) after mapping; the client is closed with the SDK client.
- `PushNotificationPayload.fromRoom(rooms, roomId)` — room-targeted
  selection: only the pushed room notifies; without a usable room ID it
  degrades to `fromLatest` (a wrong-room notification is worse than
  none).
- E2E gate extension (C2c): after the proven Conduit→ntfy publish
  (C2b), the cached ntfy `message` field IS the bytes the receiver
  would get — `MatrixE2eTest` now parses it with `PushPayload.parse`
  and asserts `room_id == roomId`, proving the app-side parser against
  the real chain.

Reference corpora provenance (read-only studies, exact state used):

| Reference | URL | HEAD (checked out) | Last commit (UTC-offset) | License (LICENSE file) |
| --- | --- | --- | --- | --- |
| Element X Android | `https://github.com/element-hq/element-x-android` | `c623105da65bedc92b5783cb690add22fe0ba6e2` | 2026-09-09T18:40:16+02:00 | **AGPL-3.0** (`LICENSE`) + separate `LICENSE-COMMERCIAL` (Element commercial licensees only) |
| UnifiedPush android-connector | `https://github.com/UnifiedPush/android-connector` | `14427332043a46eb285676c97637f8842814a686` | 2025-07-01T08:25:09+02:00 | Apache-2.0 (Copyright 2021 Simon Gougeon) |
| ntfy Android | `https://github.com/binwiederhier/ntfy-android` | `51730a0f06cebfad59f1b7bc0cb6d5c47082b032` | 2026-07-09T22:45:41+02:00 | Apache-2.0 |

Because Element X Android is **AGPL-3.0**, only its architecture and
behavior were used as reference (receiver→parser→handler flow, payload
shape, `NotificationClient` usage, `exported` contract); no AGPL code
was copied into KaiLink. The UnifiedPush connector is already a binary
dependency (`org.unifiedpush.android:connector:3.3.5`, Apache-2.0);
ntfy-side message semantics were verified against ntfy-android's
distributor code (`up/Distributor.sendMessage` sends the raw message
bytes; `msg/NotificationDispatcher` → `decodeBytesMessage`) and the
ntfy server behavior above. FCM/Google remains absent (project
constitution); the existing E2E script was preserved unchanged.

## 0.2.8: UnifiedPushRegistrar `Found` branch saves the distributor before registering

- **Decision:** `UnifiedPushRegistrar.tryRegister` calls
  `UnifiedPush.saveDistributor(context, packageName)` in the
  `ResolvedDistributor.Found` branch **before**
  `UnifiedPush.register(context)`. All registration branches now follow
  the same invariant: save, then register.
- **Why:** in the pinned connector (`org.unifiedpush.android:connector:3.3.5`),
  `register` broadcasts the REGISTER action only for a distributor
  **already saved in the connector's store** (`getDistributor(context,
  store, ack=false)`; KDoc: "saveDistributor must be called before this
  function"). `resolveDefaultDistributor` returning `Found` is a
  resolution (deeplink `unifiedpush://link` or single-distributor
  fallback), not persistence. On a fresh install the connector DB is
  empty, so the old code's `register()` returned silently — no REGISTER
  broadcast, no endpoint, state stuck at READY ("Push: registering …").
  Red-gate evidence (2026-09-10, leg 7 against unfixed 0.2.7-phase1
  code): the two diagnostic register lines 53 ms apart, then the 90 s
  endpoint/pusher poll failure; no `up*?up=1` pusher ever appeared.
  The fix matches the reference connector flow
  (`tryUseDefaultDistributor`: `saveDistributor(context, it)` →
  register). Proven green by the same gate leg after the fix (real UI
  sign-in → real `up*` pusher on Conduit → rendered notification).
- **Not decided (owner decision, unchanged):** the registered pusher
  gateway URL stays `https://ntfy.sh/_matrix/push/v1/notify`
  (`PushConfiguration.DEFAULT_GATEWAY_URL`). Conduit→gateway delivery
  for the app's own pusher therefore remains unproven in the local
  environment; the gate leg proves the app-side chain up to the
  outermost observable effect (rendered notification) by publishing the
  gateway-identical bytes to the real `up*` topic.
- **Version:** `0.2.8` (versionCode 6). The first DebugLog line stays
  the BuildConfig self-identification — now emitted as
  `KaiLink 0.2.8 (versionCode 6)`; format and "very first line" rule
  unchanged (`data/log/AppIdentity`).
- **Gate:** `scripts/emulator-e2e.sh` gained leg 7
  (`FreshInstallPushE2eTest#freshInstallUiSignInRegistersEndpointPusherAndRendersNotification`):
  `pm clear org.box44.kailink` ONLY (the ntfy distributor app and its
  state are never touched), distributor-presence precondition check,
  re-grant of `POST_NOTIFICATIONS` after `pm clear`, emulator virtual-AP
  provisioning for the ntfy subscriber service (2026-09-10 checklist
  finding), and worst-leg exit semantics (`run_leg`/`GATE_STATUS` —
  `am instrument` exits 0 even on test failures, so the gate must
  aggregate). Existing leg 6 assertions unchanged.

## 0.2.9: the push-path notification fetch carries the session's sliding sync version (VersionIsMissing fix)

- **Bug (0.2.8 device log):** on the device, the push-path notification
  resolution failed with `VersionIsMissing` ("Sliding sync version is
  missing") while the main client had detected NATIVE at sign-in
  (sign-in log line `Sliding sync version: NATIVE`). The 0.2.8
  construction of the SDK `NotificationClient`
  (`Client.notificationClient(NotificationProcessSetup.MultipleProcesses)`)
  took no version input — version-blind.
- **Mechanism (SDK sources, pinned `sdk-android:26.09.08` era):**
  - FFI `Client::notification_client` (bindings/matrix-sdk-ffi/src/
    client.rs) wraps the whole inner client into the
    `matrix_sdk_ui::NotificationClient`; there is **no** version
    parameter on the FFI construction and **no** public
    `set_sliding_sync_version` on the Kotlin `Client` (verified by
    `javap` of the pinned AAR: only
    `notificationClient(NotificationProcessSetup)` and the getter
    `slidingSyncVersion()`).
  - `matrix_sdk_ui::notification_client` builds the short-lived
    notification sliding sync from that parent client
    (`Client::sliding_sync(CONNECTION_ID)` →
    `SlidingSyncBuilder::build`), which fails with `VersionIsMissing`
    for a client carrying `Version::None`
    (crates/matrix-sdk/src/sliding_sync/builder.rs + error.rs).
  - The push-path client's version is set by the FFI restore from the
    persisted session record
    (`restore_session` → `restore_session_with` →
    `self.inner.set_sliding_sync_version(session.sliding_sync_version)`),
    or by the DISCOVER_NATIVE build at login. So the fetch is
    version-correct only if the session's detected/persisted mode is
    carried into the construction — a version-blind construction runs
    with whatever the push-path client happens to carry and fails with
    `VersionIsMissing` for a NONE-mode session on a capable homeserver.
- **Decision:** `MatrixSdkChannelClient.fetchNotification` maps the
  active session's mode
  (`domainModeToSdkVersion(activeSession.slidingSyncMode)`) and passes
  it as an explicit input into the new construction seam
  `notificationClientFactory: suspend (Client, SlidingSyncVersion?) ->
  NotificationClient?` (JVM-testable; tested by
  `NotificationClientVersionChecks`, registered in `AllChecks`). The
  default factory encodes the SDK semantics per version:
  - `NATIVE` (and the defensive `null` for a session-less state):
    standard construction — the parent client carries the
    DISCOVER_NATIVE-detected (login) or restore-set version, so the
    notification sliding sync builds on capable homeservers ("SDK
    DISCOVER_NATIVE semantics where supported").
  - `NONE`: no construction — on a sliding-sync-less homeserver the
    notification sliding sync could never build
    (`VersionIsMissing` is a guaranteed failure), so the fetch returns
    `null` deterministically and `PushMessageHandler` falls back to the
    room list; `obtainNotificationClient` logs
    `NotificationClient skipped: homeserver has no sliding sync (NONE);
    room-list fallback`. Observable Conduit/fallback behavior is
    unchanged (the pre-0.2.9 path reached the same `null` via the
    failing `get_notification` call, minus the doomed SDK call).
- **TDD evidence:** RED first against the pre-fix wiring (seam present,
  forwarding `null`): the two mode-flow checks failed with exactly
  `expected: NATIVE, actual: null` / `expected: NONE, actual: null`
  (`Checks: 98, passed: 96, failed: 2`); after strengthening (URL/mode
  decorrelation + session-less defensive check, no assertions
  weakened) the same two failures remained. GREEN:
  `Checks: 99, passed: 99, failed: 0` (full output in
  `docs/features/verification.md` §13 and
  `docs/features/f2-unifiedpush/verification.md`).
- **Gate environment change (2026-09-10/11 observed):** the current
  Conduit gate image advertises `org.matrix.simplified_msc3575: true`
  in `GET /_matrix/client/versions` (checked on
  `http://127.0.0.1:6167`) — the 0.2.5-phase1 statement "Conduit does
  not serve the flag" no longer holds for the current image. Gate
  sessions detect NATIVE (`Sliding sync version: NATIVE` in logcat for
  legs 6 and 7), the SDK notification fetch ran its notification
  sliding sync against the gate homeserver
  (`get_notification{…} > try_sliding_sync >
  sync_once{conn_id="notifications"}` → HTTP 200), and leg 7 rendered
  the notification on the 0.2.9 build. **Blind spot:** the NONE branch
  of the fix (skip → room-list fallback) is therefore no longer
  exercised end-to-end by the gate (a sliding-sync-less session no
  longer occurs there); the NONE *builder* fallback is still exercised
  by the TLS leg via a discovery transport failure, but no NONE session
  ever runs a push fetch in the gate. The NONE branch is proven at the
  wiring level by the JVM checks only.
- **Version:** `0.2.9` (versionCode 7). The first DebugLog line remains
  the BuildConfig self-identification — now
  `KaiLink 0.2.9 (versionCode 7)`; format and "very first line" rule
  unchanged (`data/log/AppIdentity`).
- **Required 0.2.9 device retest (gate cannot cover):** (1) matrix.org
  push → notification with SDK-resolved content and no
  `VersionIsMissing` in the Send-log dump; (2) optionally a
  sliding-sync-less homeserver → the skip log line and unchanged
  fallback behavior. **Follow-up (owner decision, not implemented
  here):** a session whose stored mode is stale-NONE on a capable
  homeserver (e.g. signed in during a failed discovery fallback)
  degrades to the room-list fallback forever, because the FFI restore
  overwrites the built client's version with the record's version and
  the pinned SDK exposes no version setter; the candidate fix is
  re-detecting the mode at restore (DISCOVER_NATIVE probe when the
  record says NONE) — a separate, decision-gated change.

## 0.2.9: explicit sliding sync version forwarding to the push-path `NotificationClient`

- **Decision:** `MatrixSdkChannelClient.fetchNotification` maps the active
  session's detected/persisted sliding sync mode
  (`activeSession?.slidingSyncMode`) 1:1 to the SDK version via
  `domainModeToSdkVersion` and passes it as an EXPLICIT input to the new
  `notificationClientFactory` construction seam
  (`suspend (Client, SlidingSyncVersion?) -> NotificationClient?`). The
  default factory encodes the SDK semantics per version: `NATIVE` (or an
  unknown/legacy `null`) constructs the SDK `NotificationClient`
  (`NotificationProcessSetup.MultipleProcesses`, the dedicated push-process
  setup — the push process has no `SyncService`); `NONE` skips the
  construction entirely, so the fetch returns `null` and the caller
  (`PushMessageHandler`) falls back to the room-list payload (unchanged
  Conduit/fallback behavior).
- **Why:** the SDK `NotificationClient` (MultipleProcesses) builds its
  short-lived notification sliding sync from the parent client, and
  `SlidingSyncBuilder::build` fails with `VersionIsMissing` ("Sliding sync
  version is missing") when the parent client carries no sliding sync
  version. The FFI `notificationClient` constructor takes no version
  parameter, so the app-side control is *which* parent client the
  notification fetch runs against: the 0.2.8 device failure against
  matrix.org showed the version-less construction failing
  (`NotificationClient unavailable` in the push path) while the main
  client had detected NATIVE via `SlidingSyncVersionBuilder.DISCOVER_NATIVE`
  — the notification fetch must run with that detected version. On
  Conduit-class homeservers (`NONE`) the notification sliding sync could
  never build, so constructing it (and re-throwing per push) was pure
  failure overhead; skipping construction makes the room-list fallback the
  first-class path there.
- **Testability seam:** `notificationClientFactory` is a constructor
  parameter with the SDK-default semantics as its default value. The JVM
  checks (`NotificationClientVersionChecks`, registered in
  `AllChecks.runAllChecks`) inject a recording factory and assert the
  version flow: NATIVE session mode → `SlidingSyncVersion.NATIVE` at the
  seam, NONE session mode → `SlidingSyncVersion.NONE` (homeserver URL
  deliberately decorrelated: matrix.org URL with NONE session proves the
  version comes from the session's mode, never from the URL), session-less
  defensive state → `null` (SDK default semantics, no crash). The Rust FFI
  `Client` cannot be built on the JVM; `Client(NoHandle)` is the
  handle-less non-null construction token, never touching FFI because the
  injected factory does not use it. Active-session state is set via
  reflection (test-only; no production hook added).
- **Version:** `0.2.9` (versionCode 7). The first DebugLog line stays the
  BuildConfig self-identification — now emitted as
  `KaiLink 0.2.9 (versionCode 7)`; format and "very first line" rule
  unchanged (`data/log/AppIdentity`).
- **Gate:** unchanged — `scripts/emulator-e2e.sh` legs 6/7 exercise the
  push chain against the current Conduit gate image, which advertises
  `org.matrix.simplified_msc3575: true` (observed 2026-09-10/11 on
  `http://127.0.0.1:6167`), so gate sessions detect NATIVE and the NATIVE
  construction path of the fix is exercised end-to-end through the real
  chain (leg 7's fetch ran the notification sliding sync against the gate
  homeserver and rendered the notification — see the 0.2.9 section in
  `docs/features/f2-unifiedpush/verification.md`). What the gate cannot
  prove: the NONE skip branch (a sliding-sync-less session no longer
  occurs there — wiring proven by the JVM checks only), and the real push
  path against a sliding-sync-capable homeserver (e.g. matrix.org), which
  remains a physical-device check for the actual `VersionIsMissing` fix
  on-device. (Correction 2026-09-11: this bullet previously claimed gate
  sessions are `NONE`; contradicted by the observed `/versions` flag.)

## 0.2.10: diagnostics and restore-time sliding-sync reconciliation

- **Decision:** keep the parser and notification semantics unchanged, but make
  failures observable and safe to share. A failed push parse reports only a
  reason, byte length, JSON validity, bounded top-level key names, and
  notification-key presence. The push handler reports persisted versus
  in-memory sliding-sync mode, mismatches, and room-list fallback outcome.
  Values, tokens, URLs, display names, and message bodies remain excluded
  from `DebugLog`.
- **Defect/fix:** restore previously rebuilt a client with discovery, then
  restored the stale session mode into the SDK. Restore now passes the
  re-detected mode and persists that server-verified result before callers
  resolve notifications. This self-heals stale records in both directions;
  the detection result, not the homeserver URL or a special case, wins.
- **TDD and verification:** `PushPayloadDiagnosticsChecks` and
  `PushModeDiagnosticsChecks` were written RED against the pre-0.2.10
  behavior, registered in `AllChecks`, and are green in the final offline JVM
  run. The final emulator gate log records successful installs, leg 6
  `OK (2 tests)` (`Time: 274.726`), leg 7 `OK (1 test)` (`Time: 93.896`),
  and exit code 0. Existing gate assertions and logs were not weakened or
  committed.
- **Encrypted scope:** the gate's encrypted-message proof is a blind spot;
  it proves the unencrypted timeline and UnifiedPush rendered-notification
  chain, not decryption or SAS. `InvalidSignature` was observed during
  encrypted experimentation and is recorded as out of scope for 0.2.10,
  rather than treated as a fixed defect.
- **Future encrypted gate:** use one small matrix-nio[e2e]-only Python harness
  for both stages. Stage 1: a fresh Conduit account creates/joins an
  encrypted room, sends, and asserts KaiLink decrypts the unverified message.
  Stage 2: the same harness uses `Sas.get_decimals()` while the KaiLink debug
  flow displays/confirms decimal SAS and the gate compares the codes. Do not
  use matrix-commander or a custom Rust client. Verify the pinned AAR binding
  name later; when the harness is built, pin and record its license.
- **Version/process record:** versionName `0.2.10`, versionCode 8. On
  2026-09-11 the main coder model switched to Luna (high) because the GLM
  reasoning knob was unavailable; the rationale also records a T2 practice
  win and Martin's quality push.
- **Agent-room policy (Martin):** agent rooms are always required to be E2EE.
  If an agent room is later found to be unencrypted, KaiLink must visibly warn
  the user. That warning is a later-release requirement, not a 0.2.10
  implementation; this release does not add the warning or change room
  creation behavior.
