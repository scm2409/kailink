# f2-unifiedpush – design.md

- `domain/push/PushState`, `domain/push/PushRegistrationTrigger` –
  seams (pure Kotlin, usable from `ui/`).
- `data/push/PushController` – state machine, JVM-testable; delegates to
  `ChannelClient` (endpoint registration, sync).
- `data/push/SimulatedPushTrigger` – Phase-1 stand-in for the distributor:
  present → endpoint → registration; `simulateIncomingPush()` for the
  sync path.
- Phase 2: `UnifiedPushRegistrar` + `KaiLinkPushReceiver`
  (BroadcastReceiver, `exported=true`) + manifest entries; since
  2026-09-08 implemented under `app/src/main/kotlin/org/box44/kailink/
  data/push/` and wired into `AppGraph`. Only trigger/receiver were
  swapped; since 2026-09-09 (Chunk C) `onMessage` runs
  `PushMessageHandler`: `PushPayload.parse(message.content)` →
  cold-start session restore → `syncOnce()` → notification resolution
  (SDK `NotificationClient` via `MatrixSdkChannelClient.
  fetchNotification`, room-list fallback
  `PushNotificationPayload.fromRoom`) → `PushNotifier`.
