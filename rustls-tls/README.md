# rustls-tls

Vendored Android component of [rustls-platform-verifier](https://github.com/rustls/rustls-platform-verifier)
(0.6.2, the version linked into `org.matrix.rustcomponents:sdk-android:26.09.08`).

The native library `libmatrix_sdk_ffi.so` verifies server certificates on
Android through JNI calls into `org.rustls.platformverifier.CertificateVerifier`.
Without this class in the app class path the TLS stack cannot verify any
server certificate.

To avoid the distribution mess of the upstream AAR
(download a Rust crate, then search for it using Gradle and use it as a local
Maven repository), the single `CertificateVerifier.kt` class the AAR contained
is part of our sources, in the style of
[element-hq/element-x-android PR 6610](https://github.com/element-hq/element-x-android/pull/6610)
(vendored at `element-hq/element-x-android@cbd135e484ba1079fc2b3a651ff3508f8fab5871`).

- The Kotlin file stays byte-identical to the vendored source EXCEPT for one
  local port: the network-based OCSP/CRL revocation fetch in
  `CertificateVerifier.verifyCertificateChain` was removed (matrix-rust-sdk
  PR #6323, fixing matrix-rust-sdk #6319 /
  [rustls-platform-verifier#221](https://github.com/rustls/rustls-platform-verifier/issues/221)):
  Android fetched revocation data over the network (CRLs are served over
  cleartext HTTP, which the Android network stack blocks) and reported valid
  certificates as `InvalidCertificate(Revoked)`. Revocation is now only checked
  from a server-stapled OCSP response, never over the network; all other
  validation (trust anchors, chain, signatures, validity, EKU, hostname in
  Rust) is preserved. Revert this port when #221 is fixed and released.
- The `BuildConfig.TEST` flag (always `false` here) replaces the one the
  original AAR generated.
- The file is MIT licensed (Copyright (c) 2022 1Password), see its header.

When this file is updated, record the new upstream version/commit here.
