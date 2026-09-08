# Entscheidungen

- Das offizielle Matrix Rust SDK `sdk-android:26.09.08` übernimmt Protokoll,
  Sync und E2EE.
- UnifiedPush `3.3.5` nutzt die Topic-URL als Matrix-`pushkey`; das
  konfigurierbare Standard-Gateway ist
  `https://ntfy.sh/_matrix/push/v1/notify`.
- Sitzungen werden produktiv mit AES-256-GCM und einem nicht exportierbaren
  Android-Keystore-Schlüssel geschützt; JVM-Tests verwenden den Dateitestdouble.
- Die Phase-1-Version lautet `0.2.0-phase1`.
