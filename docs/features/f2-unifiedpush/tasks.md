# f2-unifiedpush – tasks.md

Done (Phase 1):

- [x] `PushState`/`PushRegistrationTrigger` seams in the domain.
- [x] `PushController` state machine (JVM-checked).
- [x] `SimulatedPushTrigger` incl. push→sync path (JVM-checked).
- [x] Display of the push state in the room list.

Open (Phase 2):

- [x] Integrate the UnifiedPush connector (trigger + receiver + manifest).
      *(2026-09-08: `UnifiedPushRegistrar`, `KaiLinkPushReceiver`,
      manifest entry; compiles, tests 39/39.)*
- [x] NotificationClient wiring (notifications).
      *(2026-09-09, Chunk C: `PushPayload` parser +
      `PushMessageHandler` with cold-start session restore;
      `MatrixSdkChannelClient.fetchNotification` via the SDK
      `NotificationClient`; receiver `exported=true`; JVM checks 94/94.)*
