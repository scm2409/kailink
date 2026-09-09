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
