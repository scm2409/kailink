# Feature: Push Abstraction (without Google/FCM)

## Interaction Model

- After login the room list shows the push state; in Phase 1 the chain
  runs against the documented simulator:
  "Push: registered (UnifiedPush)".
- An incoming push always means: trigger a sync — notifications
  are (intentionally, in both phases) not rendered.

## Implementation

- `domain/push/PushState` (`NOT_AVAILABLE`, `READY`, `REGISTERED`,
  `FAILED`) and `PushRegistrationTrigger` are the seams; `ui/`
  sees only these types.
- `data/push/PushController` (pure Kotlin, JVM-testable) is the
  state machine:
  - `onNewEndpoint(url)` → `ChannelClient.registerPushEndpoint(url)`,
    success → `REGISTERED`, failure → `FAILED`,
  - `onMessage()` → `ChannelClient.syncOnce()`,
  - `onDistributorAvailable()`/`onNoDistributor()`/`onRegistrationFailed()`/
    `onUnregistered()` for the distributor lifecycles.
- `data/push/SimulatedPushTrigger` (Phase 1): plays the local
  UnifiedPush distributor — distributor present → endpoint
  (`https://push.phase1.local/org-box44-kailink/endpoint`) → registration;
  `simulateIncomingPush()` triggers the sync path. No permissions,
  no Google services, no network connection.

## Phase-2 Wiring (since 2026-09-08)

- `org.unifiedpush.android:connector` 3.3.5 is integrated:
  `UnifiedPushRegistrar` (in `data/push/`) is wired into
  `AppGraph` as the `PushRegistrationTrigger`,
  `KaiLinkPushReceiver` is the BroadcastReceiver for the connector actions
  (`MESSAGE`, `NEW_ENDPOINT`, `REGISTRATION_FAILED`, `UNREGISTERED`),
  manifest entry `exported=true` (since 2026-09-09; the distributor —
  e.g. the ntfy app — delivers the broadcasts as another app targeting
  this package, so an `exported=false` receiver never receives anything;
  same contract as the connector docs and the Element X receiver
  manifest).
- Registration path: `resolveDefaultDistributor` → present: `register()`;
  none installed: `NOT_AVAILABLE`; several without a selection: deterministically
  the first reported distributor (documented PoC limit, a user-friendly
  selection would be the connector's LinkActivity).
- `PushController` handles the endpoint/failure lifecycles unchanged;
  only trigger and receiver were swapped.

## Push→Notification Path (Chunk C, 2026-09-09)

The real app-side path (patterns from the reference corpora, no code
copied — see docs/decisions.md for provenance and licenses):

1. **Parse** — `data/push/PushPayload`: the UnifiedPush message bytes are
   the body the homeserver POSTed to the push gateway (ntfy publishes the
   entire notify body to the topic, so the app receives
   `{"notification":{"event_id":…,"room_id":…,"counts":{…},…}}` —
   `event_id_only` pusher format means no message content). Pure,
   JVM-checked, order-independent scanner; `null` for foreign payloads.
2. **Session** — `data/push/PushMessageHandler` (pure Kotlin,
   JVM-tested): a push normally wakes a dead process, so the handler
   restores the persisted session (`SessionStore` →
   `ChannelClient.restore`) before doing anything; without any session
   the push is dropped (no sync, no notification).
3. **Sync** — `ChannelClient.syncOnce()`; a non-Matrix payload only
   downgrades to this wake-up semantics (never drops the sync).
4. **Resolve** — `MatrixSdkChannelClient.fetchNotification(roomId,
   eventId)` via the pinned SDK's `NotificationClient`
   (`NotificationProcessSetup.MultipleProcesses`, created lazily, closed
   with the client; `getNotification(roomId, eventId)` →
   `NotificationStatus.Event(item)` → title from
   `item.roomInfo.displayName`, body from the decrypted
   `MessageType.Text/Notice/Emote` (or the undecryptable placeholder),
   undecryptable events map to `DeliveryState.UNDECRYPTABLE`). On
   failure/filtered events the handler falls back to the room list
   (`PushNotificationPayload.fromRoom(rooms, payload.roomId)` — only the
   pushed room notifies, never another room; without a room ID it
   degrades to `fromLatest`).
5. **Render** — `PushNotifier` (unchanged): channel, POST_NOTIFICATIONS
   check (API 33+), per-room notification ID.

`PushController.onMessage` (sync only, no notification) remains the
simulated-path seam (`SimulatedPushTrigger`); the receiver now routes
real pushes through `PushMessageHandler`.

## PoC Limits

- No distributor selection dialog; Phase 1 simulates the distributor.
- Only one session is supported: the push path resolves it from the
  session store (no `clientSecret`/multi-account routing as in Element X).
- Payload parser is a field scanner, not a full JSON parser (no extra
  dependency in the offline build); webpush payload encryption
  (`PushMessage.decrypted`) is accepted but not verified.
- `rooms()` (SDK client) carries no `lastMessage`, so the room-list
  fallback normally yields no notification content — the real content
  comes from the `NotificationClient`; the fallback exists for
  reference-channel/JVM behavior.

## Verification

V1: `PushControllerChecks`, `PushChainChecks`, `PushPayloadChecks`,
`PushMessageHandlerChecks` and the `PushNotificationPayload` group
(fromRoom cases) — passed 2026-09-09, see
[`verification.md`](verification.md). V4: manual protocol, test case
MT-6 (**not observed**, no device).
