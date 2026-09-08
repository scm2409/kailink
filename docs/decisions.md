# Entscheidungen

- Das offizielle Matrix Rust SDK `sdk-android:26.09.08` übernimmt Protokoll,
  Sync und E2EE.
- UnifiedPush `3.3.5` nutzt die Topic-URL als Matrix-`pushkey`; das
  konfigurierbare Standard-Gateway ist
  `https://ntfy.sh/_matrix/push/v1/notify`.
- Sitzungen werden produktiv mit AES-256-GCM und einem nicht exportierbaren
  Android-Keystore-Schlüssel geschützt; JVM-Tests verwenden den Dateitestdouble.
- Die Phase-1-Version lautet `0.2.0-phase1`.

- Für Android-Instrumentierungstests werden AndroidX Test Runner `1.6.2`,
  AndroidX Test JUnit `1.2.1` und Test Core `1.6.1` verwendet. Der erste
  Emulatornachweis läuft per separater APK-Installation und
  `am instrument`, weil die x86_64-Emulatorvariante nicht automatisch eine
  eigene `connectedEmulatorDebugAndroidTest`-Gradle-Aufgabe erzeugt.
