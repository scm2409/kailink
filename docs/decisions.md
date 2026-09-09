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
