# Project Constitution

This constitution binds all contributions to KaiLink. It is deliberately short and
verifiable: every rule is verifiable in the repository or checkable in the manual
test protocol.

## 1. Purpose

KaiLink is a **proof of concept** and demonstrates:

1. Matrix login and session restore on Android without Google services,
2. Room list, timeline, and sending messages,
3. End-to-end encryption (E2EE) with the real `matrix-rust-sdk`,
4. Push delivery via UnifiedPush (no FCM),
5. Clearly defined **seams** for later speech input/output.

**Not a purpose:** production readiness, UI attractiveness, chat history across
device changes, multi-account, voice/video calls.

## 2. Hard principles

- **G1 – No Google services.** No FCM, no Play Services, no
  Google-Maven-specific licensing restrictions in code. Push runs
  exclusively via UnifiedPush.
- **G2 – Real Matrix SDK.** The official `matrix-rust-sdk` (Android bindings)
  is used for Matrix protocol, crypto, and sync. Own
  protocol/crypto implementations are forbidden.
  *Phase-1 clause (2026-09-08):* as long as the SDK artifacts are not available in the local
  offline cache, the app runs against the documented
  in-memory simulation (`InMemoryChannelClient`, principle G5) — without any
  own protocol/crypto logic; G2 takes effect with the integration of the
  reference adapter (`app/src/phase2/`).
- **G3 – Verified dependencies.** A dependency is only
  integrated once the version number and the used API surface have been checked locally
  (Maven metadata and/or unpacking the artifacts).
  Otherwise: documented adapter/stub, marked in the respective feature document.
- **G4 – Domain without Android.** `domain/` must not import Android classes;
  it must be testable on the JVM.
- **G5 – Seams instead of bluff.** For capabilities not yet implemented
  (e.g. speech) there are interfaces plus documented
  no-op implementations. No fake features.
- **G6 – No global installations.** Tools are provided per-user
  (`~/.local`, `mise.toml`, project folder). No
  system-wide package installations.
- **G7 – No credentials in the repo.** Homeserver URLs, credentials, and
  tokens appear at most locally at runtime; the repo contains only
  placeholders.
- **G8 – Verification before assertion.** What is claimed has been observed.
  Results land with date and as-is output in
  [`docs/features/verification.md`](features/verification.md). Deviceless
  checks are explicitly marked as such.
- **G9 – Language is English.** Documentation, UI texts, error messages,
  and code comments are in English; identifiers and commit messages are English.

## 3. Artifact naming

Debug artifacts are named
`kailink-poc-v<version>-debug.apk` (currently: `0.1.0`). Earlier versions
are never silently overwritten.

## 4. Verification levels

| Level | Means | Possible without a device |
| --- | --- | --- |
| V1 | JVM checks (Phase 1: `phase1Checks`, while JUnit is unavailable offline; since 2026-09-08: JUnit test `AllChecksTest` in `testDebugUnitTest`) | yes |
| V2 | Compilation + `assembleDebug` | yes |
| V3 | Structure check (manifest, badging, APK content) | yes |
| V4 | Behavior on device/emulator | no → manual protocol |

A PoC result only counts as "proven" once the lowest possible level for the feature
has passed; everything beyond that is openly named as residual risk (see `docs/features/verification.md`).
