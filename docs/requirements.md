# Requirements

KaiLink Phase 1 provides Matrix login and session restore, room list,
timeline, sending and receiving encrypted messages, and UnifiedPush with
a configurable ntfy gateway. The speech seams remain without STT/TTS;
Bluetooth and video calls are out of scope.

JVM-verifiable paths are executed with JUnit. Homeserver, E2EE,
UnifiedPush, and notification behavior are checked via the local E2E script
and on GrapheneOS. Real matrix.org credentials are never used in the
repository.
