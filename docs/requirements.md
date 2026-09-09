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

JVM-verifiable paths are executed with JUnit. Homeserver, E2EE,
UnifiedPush, and notification behavior are checked via the local E2E script
and on GrapheneOS. Real matrix.org credentials are never used in the
repository.
