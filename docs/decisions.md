# Entscheidungen

- Das offizielle Matrix Rust SDK `sdk-android:26.09.08` übernimmt Protokoll,
  Sync und E2EE.
- UnifiedPush `3.3.5` nutzt die Topic-URL als Matrix-`pushkey`; das
  konfigurierbare Standard-Gateway ist
  `https://ntfy.sh/_matrix/push/v1/notify`.
- Sitzungen werden produktiv mit AES-256-GCM und einem nicht exportierbaren
  Android-Keystore-Schlüssel geschützt; JVM-Tests verwenden den Dateitestdouble.
- Die Phase-1-Version lautet `0.2.1-phase1`.

- Für Android-Instrumentierungstests werden AndroidX Test Runner `1.6.2`,
  AndroidX Test JUnit `1.2.1` und Test Core `1.6.1` verwendet. Der erste
  Emulatornachweis läuft per separater APK-Installation und
  `am instrument`, weil die x86_64-Emulatorvariante nicht automatisch eine
  eigene `connectedEmulatorDebugAndroidTest`-Gradle-Aufgabe erzeugt.

- Das Emulator-Gate-Skript beendet den Lauf absichtlich mit Exit-Code 3, wenn
  nach den grünen HTTP-, Build- und Instrumentierungs-Smokes die vollständige
  Matrix-/E2EE-/UnifiedPush-Kette verlangt wird. Ohne echten Test-
  Konfigurationskanal, kontrollierten E2EE-Schlüsselaustausch und
  Distributor-Nachweis wäre ein grüner Exit-Code irreführend.

- Die gegen `sdk-android:26.09.08` verifizierten E2EE-Typen liegen im
  Namespace `uniffi.matrix_sdk_crypto`; die optionale Testkonfiguration setzt
  `CollectStrategy.ALL_DEVICES` und `DecryptionSettings(TrustRequirement.UNTRUSTED)`.
  Cross-Signing und Key-Backup werden nicht getestet oder behauptet.
  Commit: `8db4c4e` (Chunk B, verschlüsselte zweite Leg).

- UniFFI-Builder sind immutable (Quelle:
  `bindings/matrix-sdk-ffi/src/client_builder.rs` im matrix-rust-sdk —
  jeder Setter nimmt `self: Arc<Self>` und gibt einen NEUEN Builder
  zurueck). `MatrixSdkChannelClient.buildClient` ignorierte die
  Rueckgaben — der Build lief ohne jede Config
  (`ClientBuildError: ... must be called`). Fix: Rueckgaben verketten
  (`builder = builder.homeserverUrl(...)` etc.). Der Login-Pfad
  funktionierte zuvor nie, daher keine Nutzerverhaltensänderung.
  Commit: `d9d4190`.
- Cleartext nur in `emulatorDebug`: `network_security_config.xml`
  (`cleartextTrafficPermitted=true` nur fuer `10.0.2.2`, `127.0.0.1`,
  `localhost`, `192.168.42.20`) + Manifest-Overlay mit
  `android:networkSecurityConfig`. Beweis: `aapt`-Dump des
  `app-emulatorDebug.apk` zeigt `networkSecurityConfig`,
  `app-debug.apk` enthaelt es nicht. `androidTest`-Manifest wirkt nicht
  (Test-APK-Prozess folgt der App-Policy).
- Conduit-Grenzen: kein Sliding Sync (`VersionIsMissing` bei
  `syncService().finish()`) — E2E nutzt `syncOnce`; Emulator-Netz defekt
  (`10.0.2.2` unerreichbar) — Lauf nutzt `adb reverse` nach `127.0.0.1`.
  Commit: `919de4c` (C2b-Beweis: Conduit→ntfy-Zustellung).
