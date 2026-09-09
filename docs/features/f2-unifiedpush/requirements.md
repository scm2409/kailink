# f2-unifiedpush – requirements.md

Requirements for the push chain without Google/FCM (Phase 1: state chain
real, distributor simulated; Phase 2: UnifiedPush connector).

1. No FCM, no Play Services (G1).
2. State display in the room list: no distributor / registration running /
   registered / failed (in English).
3. Endpoint delivery → registration as a pusher at the channel
   (`ChannelClient.registerPushEndpoint`).
4. Incoming push → `syncOnce()` (wake up + sync, no
   notification rendering).
5. Loss of registration/temp-unavailable lead into a defined
   state; the app remains fully usable without push.
