# Disclaimer

KaiLink is vibecoded (AI-assisted). It is a personal project of the
repository owner and is not intended as a general-purpose Matrix client,
but it may still serve as a starting point or provide helpful code for
someone building their own client.

# KaiLink

KaiLink is a proof of concept (PoC) of an Android messenger based on
[Matrix](https://matrix.org/). Phase 1 delivered a **foundation that can be
built completely offline**: domain layer with seams, login with
session restore, room list, timeline with sending, a
push abstraction with a state machine, and verified JVM verification.

> **Status: Phase 2 (as of 2026-09-09).** The real Matrix adapter
> (`MatrixSdkChannelClient`, matrix-rust-sdk 26.09.08) and the
> [UnifiedPush](https://unifiedpush.org/) integration (`UnifiedPushRegistrar` +
> `KaiLinkPushReceiver`, connector 3.3.5, without Google/FCM) are compiled
> and wired in `AppGraph`; `testDebugUnitTest assembleDebug` is green.
> Runtime against a real homeserver/distributor is exercised by the local
> E2E gate (`scripts/emulator-e2e.sh`) — see
> [`docs/architecture.md`](docs/architecture.md).

## Core decisions

| Decision | Rationale |
| --- | --- |
| Phase 1: in-memory channel behind `ChannelClient` | The conditions of the first phase did not allow network access; the interface stayed identical and the adapter was swappable (G5) |
| Phase 2: real `matrix-rust-sdk` | Official Rust implementation including E2EE (Olm/Megolm); `MatrixSdkChannelClient` has been in the build and wired since 2026-09-08 |
| Push abstraction (`PushController` + `PushRegistrationTrigger`) | The state machine is JVM-tested; Phase 2 swaps only the trigger for the UnifiedPush connector |
| Android framework views | Jetpack Compose artifacts are not available in the local offline cache; the UI layer is cut so that Phase 2 can switch to Compose |
| No Google/FCM code in the repo | Project constitution, see [`docs/project-constitution.md`](docs/project-constitution.md) |

## Build (this environment)

Prerequisites: JDK 21 (provided via `mise.toml`), Android SDK under
`/home/dev/android-sdk` (recorded in `local.properties`, not in the repo),
Gradle wrapper 9.1.0, AGP 8.13.2, Kotlin 2.2.21.

```bash
./gradlew testDebugUnitTest assembleDebug   # JVM checks (JUnit) + debug APK
./gradlew check                             # including lint (abortOnError=false)
```

Observed on 2026-09-10: `BUILD SUCCESSFUL`; JUnit report `tests="1"
failures="0"` (the test runs all 96 check-group checks), check report
under `app/build/reports/phase1-checks.txt` (96/96 passed), see
[`docs/features/verification.md`](docs/features/verification.md).
Result: `app/build/outputs/apk/debug/app-debug.apk`
(`org.box44.kailink`, versionName `0.2.7-phase1`, minSdk 28, targetSdk 36,
including `libmatrix_sdk_ffi.so` from the matrix-rust-sdk).

Since 0.2.4-phase1 every screen shows the version as a small footer, and the
room list offers a compact **Send log** action (signed-in only) that uploads
the in-app debug log (ring buffer, ~1000 lines) as a `.txt` file into the
KaiL room via the SDK attachment path (`Timeline.sendFile`). Since
0.2.7-phase1 the very first debug-log line after app start identifies the
app from BuildConfig (`KaiLink 0.2.7-phase1 (versionCode 5)`), and the
signed-in rooms screen shows a one-line About row (`KaiLink 0.2.7-phase1`)
in addition to the footer. The on-device UnifiedPush registration flow
(with the exact broadcast actions, log lines, and failure modes) is
documented in
[`docs/features/f2-unifiedpush/device-registration-checklist.md`](docs/features/f2-unifiedpush/device-registration-checklist.md).

## Project structure

```
app/src/main/kotlin/org/box44/kailink/
├── domain/          Pure domain logic without Android dependencies (JVM-testable)
│   ├── model/       Session, Room, Message
│   ├── ChannelClient.kt    Channel seam (adapters implement it)
│   ├── SessionStore.kt     Session persistence contract
│   ├── TimelineReducer.kt  Reduces timeline patches to the UI state
│   ├── push/        Push seams (PushState, PushRegistrationTrigger)
│   └── speech/      Speech seams (STT/TTS, no-op implementation)
├── data/
│   ├── channel/     InMemoryChannelClient (JVM reference for checks)
│   ├── matrix/      MatrixSdkChannelClient (matrix-rust-sdk, production)
│   ├── push/        PushController, UnifiedPushRegistrar, KaiLinkPushReceiver,
│   │                SimulatedPushTrigger (JVM reference)
│   ├── log/          DebugLog (in-app ring buffer, ~1000 lines)
│   └── session/     FileSessionStore (properties file, app-private)
├── di/              AppGraph (manual wiring: Matrix + UnifiedPush)
├── ui/              Framework views + ViewModels (login, room list, timeline)
└── MainActivity.kt  One activity, three switched screens
app/src/phase2/      Source path of the Matrix adapter (included in main)
app/src/test/        JVM checks (JUnit: AllChecksTest → 39 checks)
docs/                Project documentation (constitution, architecture, protocols)
```

## Documentation

- [`docs/project-constitution.md`](docs/project-constitution.md) — principles G1–G9
- [`docs/architecture.md`](docs/architecture.md) — layers, data flow, Phase-2 migration path
- [`docs/features/verification.md`](docs/features/verification.md) — observed results (G8)
- [`docs/manualtest-protokoll.md`](docs/manualtest-protokoll.md) — manual device checks (V4)
- `docs/features/*.md` — feature documents (login, room list/timeline, push, speech, E2EE outlook)
