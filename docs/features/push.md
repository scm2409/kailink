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
  manifest entry `exported=false`.
- Registration path: `resolveDefaultDistributor` → present: `register()`;
  none installed: `NOT_AVAILABLE`; several without a selection: deterministically
  the first reported distributor (documented PoC limit, a user-friendly
  selection would be the connector's LinkActivity).
- `PushController` remained unchanged; only trigger and receiver were
  swapped. Observed state: compiles, JVM checks 39/39; runtime
  against a real distributor not observed.

## PoC Limits

- Push payloads are not rendered in decrypted form; push = wake up +
  sync (open item: `NotificationClient` wiring in Phase 2).
- No distributor selection dialog; Phase 1 simulates the distributor.

## Verification

V1: `PushControllerChecks` (4) and `PushChainChecks` (3) — passed
2026-09-08, see [`verification.md`](verification.md). V4: manual
protocol, test case MT-6 (**not observed**, no device).
