# Requirements

KaiLink Phase 1 provides Matrix login and session restore, room list,
timeline, sending and receiving encrypted messages, and UnifiedPush with
a configurable ntfy gateway. The speech seams remain without STT/TTS;
Bluetooth and video calls are out of scope.

Since 0.2.4-phase1 every screen renders the app version
(`BuildConfig.VERSION_NAME`) as a small footer, and the app keeps an
in-memory debug log (last ~1000 lines) that can be uploaded as a `.txt`
file into the KaiL room via the room list's "Send log" action
(available only while signed in).

Since 0.2.5-phase1 the client performs the SDK-sanctioned sliding sync
discovery at client build time (`SlidingSyncVersionBuilder.DISCOVER_NATIVE`):
on homeservers advertising `org.matrix.simplified_msc3575` (e.g. matrix.org)
live sync via the SDK `SyncService` works natively; on servers without that
capability (e.g. Conduit) the client falls back to the classic `/sync`
session and live sync stays unavailable — the failure must be surfaced,
never disabled or silently swallowed. The detected mode is part of the
persisted `Session`; legacy sessions (pre-0.2.5) restore as NONE. The
"Send log" action only requires an authenticated session, never a live
sync connection. The Rust SDK/HTTP client diagnostics (rotating tracing
files under `cacheDir/matrix/tracing`) are level/target-filtered into the
same bounded debug log, so the upload covers SDK diagnostics too.

JVM-verifiable paths are executed with JUnit. Homeserver, E2EE,
UnifiedPush, and notification behavior are checked via the local E2E script
and on GrapheneOS. Real matrix.org credentials are never used in the
repository.
