# f2-unifiedpush – Device Registration Checklist (0.2.6+)

Step-by-step reference for the on-device UnifiedPush registration flow and
its observable outcomes. All broadcast actions, log lines, and failure
behaviors below were cross-checked against the code in this repository
(`UnifiedPushRegistrar`, `PushController`, `KaiLinkPushReceiver`,
`PushMessageHandler`, `PushNotifier`, `AndroidManifest.xml`), the pinned
connector binary (`org.unifiedpush.android:connector:3.3.5`, constants
verified in the AAR), the UnifiedPush Android specification (AND_3.1.0,
https://unifiedpush.org/developers/spec/android/), and the ntfy Android
distributor (state per `docs/decisions.md` provenance table). **No log
lines are invented**; every quoted string appears verbatim in the source
files named below. If a failure has no current log line, it is marked
`no log line emitted — gap` — these are documentation only. Authorized
exception (0.2.7-phase1 investigation): the registrar-path distributor
gaps (failure modes 1 and 2) received diagnostic-only log lines in
`UnifiedPushRegistrar` (behavior unchanged); those former gaps now carry
exact log lines below.

## Prerequisites (device)

1. The ntfy Android app is installed (it is the UnifiedPush distributor).
2. KaiLink 0.2.6+ is installed (debug or release build).
3. For notifications to render (API 33+): the runtime permission
   `POST_NOTIFICATIONS` was granted (declared in
   `app/src/main/AndroidManifest.xml`; the grant itself is a device
   concern — see failure mode 4).

## Registration flow, step by step

### Step 1 — Trigger

Signing in or restoring a session calls `pushTrigger.tryRegister()` after
a successful login/restore (`LoginViewModel.kt:58` for restore,
`LoginViewModel.kt:89` for sign-in). There is no manual registration
button.

### Step 2 — Distributor resolution (`UnifiedPushRegistrar.tryRegister`)

`UnifiedPush.resolveDefaultDistributor(context)` (connector 3.3.5) is
resolved against the distributors installed on the device:

| Result | KaiLink behavior |
| --- | --- |
| `ResolvedDistributor.Found` | `register()` (Step 3) |
| `ResolvedDistributor.ToSelect` | Deterministically picks the **first** distributor reported by `UnifiedPush.getDistributors(context)`, saves it (`UnifiedPush.saveDistributor`), then `register()`. Documented PoC limitation: no user-facing distributor picker (KDoc of `UnifiedPushRegistrar`). |
| `ResolvedDistributor.NoneAvailable` | `controller.onNoDistributor()` → `PushState.NOT_AVAILABLE`; room list shows "Push: no distributor found". |

Since the authorized diagnostic change (0.2.7-phase1), every branch emits
a DEBUG log line through the `AppGraph.log` seam (see "Exact log lines"):
`Found` names the resolved distributor package in the pre-register line;
`ToSelect` with an **empty** `getDistributors` list logs the no-distributor
line and behaves like `NoneAvailable` (`onNoDistributor()`); `NoneAvailable`
logs its no-distributor line.

### Step 3 — The registration broadcast to the distributor

`register()` first sets `PushState.READY` (room list shows
"Push: registering …"), then calls `UnifiedPush.register(context)`.
Around that call two diagnostic DEBUG lines land in the log: one
immediately before `UnifiedPush.register(context)` naming the resolved
distributor package, one immediately after the call returns. The
connector sends the broadcast action

```
org.unifiedpush.android.distributor.REGISTER
```

to the resolved distributor's package (extras: `token`; on SDK ≥ 34 the
broadcast carries `FLAG_SHARE_IDENTITY`, otherwise a `pi` PendingIntent;
KaiLink sends no `vapid` and no `message` extra). This is the exact
action the receiver path "registers with a distributor" — KaiLink never
talks HTTP to the distributor itself.

### Step 4 — What the ntfy Android app does and shows on success

The ntfy distributor processes the REGISTER broadcast and:

- generates a topic: prefix `up` + 12 random characters
  (e.g. `upQ7Zk2mXw9pL`),
- creates a subscription entry bound to the registering app
  (`upAppId = org.box44.kailink`).

After success the ntfy app's subscription list shows **a new subscription
whose name is the raw topic string** (`up` + 12 characters), with the
registering app's package and a "(UnifiedPush)" label in the
subscription's status line. The generated endpoint URL has the pattern
`<ntfy base URL>/<topic>` (no token query parameter at the studied
ntfy-android state). If no such subscription appears, registration did
not complete (see failure modes).

### Step 5 — The distributor's reply broadcasts (receiver side)

The distributor answers by broadcasting to KaiLink's receiver
(`KaiLinkPushReceiver`, `exported="true"`; manifest intent-filter
declares exactly these four actions):

| Action | Receiver callback | Consequence in KaiLink |
| --- | --- | --- |
| `org.unifiedpush.android.connector.NEW_ENDPOINT` | `onNewEndpoint` (goAsync-guarded) → `PushController.onNewEndpoint(endpoint.url)` | see Step 6 |
| `org.unifiedpush.android.connector.REGISTRATION_FAILED` | `onRegistrationFailed(reason)` → `PushController.onRegistrationFailed(reason.name)` | see failure mode 3 |
| `org.unifiedpush.android.connector.UNREGISTERED` | `onUnregistered` → `PushController.onUnregistered()` | see failure modes |
| `org.unifiedpush.android.connector.MESSAGE` | `onMessage` (goAsync-guarded) → `PushMessageHandler.handle(message.content)` → `PushNotifier` | the push→notification path |

Note: the connector also defines `org.unifiedpush.android.connector.TEMP_UNAVAILABLE`
(present in the pinned 3.3.5 AAR and in spec AND_3.1.0, which requires the
app to declare it), but KaiLink's manifest does **not** declare this action
and `onTempUnavailable` is not overridden — a temporary-unavailable event
is silently ignored. no log line emitted — gap.

### Step 6 — Endpoint → Matrix pusher registration

For every delivered (possibly rotated) endpoint,
`PushController.onNewEndpoint` calls
`ChannelClient.registerPushEndpoint(endpointUrl)`
(`MatrixSdkChannelClient`): the endpoint URL becomes the `pushkey` and
the push gateway URL (default `https://ntfy.sh/_matrix/push/v1/notify`,
`PushConfiguration`) becomes the `HttpPusherData.url`, pusher format
`event_id_only`, app id `org.box44.kailink` (SDK `setPusher`). Every
rotated endpoint is re-registered the same way.

### Step 7 — Success criteria on the device

- KaiLink room list: push state ends at **"Push: registered (UnifiedPush)"**
  (`PushState.REGISTERED`).
- ntfy app: subscription `upXXXXXXXXXXXX` exists and shows the KaiLink
  app id with the "(UnifiedPush)" label.
- A later Matrix message produces a KaiLink notification
  (channel "Push messages").

## Exact log lines (where each lands)

App log lines go through the single seam `AppGraph.log` →
`DebugLog.append` **and** `Log.d("KaiLink", …)`, so they appear in both
logcat and the "Send log" dump. Exception: `KaiLinkPushReceiver` uses
`android.util.Log` directly — those lines are **logcat-only** and never
appear in the DebugLog buffer.

| Exact string | Source | Where |
| --- | --- | --- |
| `UnifiedPush registration: calling UnifiedPush.register (distributor: <packageName>)` | `UnifiedPushRegistrar.register` — immediately before `UnifiedPush.register(context)`; `<packageName>` is `ResolvedDistributor.Found.packageName` or the auto-picked distributor (ToSelect branch) | DebugLog + logcat |
| `UnifiedPush registration: UnifiedPush.register returned (distributor: <packageName>)` | `UnifiedPushRegistrar.register` — immediately after `UnifiedPush.register(context)` returns (before any distributor reply broadcast) | DebugLog + logcat |
| `UnifiedPush registration: no distributor found (NoneAvailable)` | `UnifiedPushRegistrar.tryRegister` (`ResolvedDistributor.NoneAvailable`) | DebugLog + logcat |
| `UnifiedPush registration: no distributor found (ToSelect, distributor list empty)` | `UnifiedPushRegistrar.tryRegister` (`ResolvedDistributor.ToSelect` and `UnifiedPush.getDistributors(context)` empty) | DebugLog + logcat |
| `Push endpoint registered as Matrix pusher` | `PushController.onNewEndpoint` (success after `registerPushEndpoint`) | DebugLog + logcat |
| `UnifiedPush endpoint registered as Matrix pusher (gateway: $gatewayUrl)` | `MatrixSdkChannelClient.registerPushEndpoint` (success) | DebugLog + logcat |
| `Pusher registration failed: ${t.message}` | `PushController.onNewEndpoint` (catch) — `t.message` wraps `MatrixSdkChannelClient`'s `ChannelException("Pusher registration failed: …")` | DebugLog + logcat |
| `Push registration failed: ${reason ?: "unknown"}` | `PushController.onRegistrationFailed` — `reason` is the connector `FailedReason` name: `INTERNAL_ERROR`, `NETWORK`, `ACTION_REQUIRED`, or `VAPID_REQUIRED` | DebugLog + logcat |
| `Push registration revoked by the distributor` | `PushController.onUnregistered` (distributor sent `UNREGISTERED`) | DebugLog + logcat |
| `Push: not a Matrix push payload — wake-up sync only` | `PushMessageHandler.handle` (`PushPayload.parse` returned `null`) | DebugLog + logcat |
| `Push dropped: no active or stored session` | `PushMessageHandler.ensureSession` (no active session, nothing stored) | DebugLog + logcat |
| `Push cold start: session restored (${stored.userId})` | `PushMessageHandler.ensureSession` (session restored for the push) | DebugLog + logcat |
| `Push session restore failed: ${t.message}` | `PushMessageHandler.ensureSession` (restore threw) | DebugLog + logcat |
| `Push sync failed: ${t.message}` | `PushMessageHandler.handle` (`syncOnce()` threw); also `PushController.onMessage` in the **simulated** path only | DebugLog + logcat |
| `Notification resolution failed: ${it.message}` | `PushMessageHandler.handle` (SDK `NotificationClient` fetch threw) | DebugLog + logcat |
| `Push room list failed: ${it.message}` | `PushMessageHandler.fallbackFromRooms` (room list fetch threw) | DebugLog + logcat |
| `Push handling failed: ${t.message}` | `KaiLinkPushReceiver.onMessage` catch — `Log.w` with tag `KaiLinkPush`; **logcat only, not in "Send log"** | logcat only |
| `Push triggered: sync executed` | `PushController.onMessage` — **simulated path only** (`SimulatedPushTrigger`/JVM checks), never on the device registration flow | DebugLog + logcat |

## Failure modes (cross-checked)

### 1. No distributor installed

`UnifiedPush.resolveDefaultDistributor` returns `NoneAvailable` →
`PushController.onNoDistributor()` → `PushState.NOT_AVAILABLE`. The room
list shows "Push: no distributor found"; the app remains fully usable.

Log line (exact, since the authorized 0.2.7-phase1 diagnostic change):

```
UnifiedPush registration: no distributor found (NoneAvailable)
```

### 2. Another distributor selected (not ntfy)

Three sub-cases:

- KaiLink auto-picks: with several distributors installed and none chosen,
  the registrar deterministically saves and uses the first reported
  distributor (`ResolvedDistributor.ToSelect` branch). If that is not
  ntfy (e.g. NextPush), the chain still works — endpoint and pusher log
  lines appear as usual. Since the authorized 0.2.7-phase1 diagnostic
  change, the chosen distributor is named in the pre-register line
  (`UnifiedPush registration: calling UnifiedPush.register
  (distributor: <packageName>)`).
- The user changes the default distributor later: the connector delivers
  `UNREGISTERED` (→ `Push registration revoked by the distributor`) and
  endpoint events from the new distributor arrive after the next
  registration.
- `ResolvedDistributor.ToSelect` is returned but
  `UnifiedPush.getDistributors(context)` is empty (distributor uninstalled
  between resolution queries, or the connector's cached state disagrees
  with the package manager): behaves like `NoneAvailable`
  (`onNoDistributor()` → `PushState.NOT_AVAILABLE`). Log line (exact):
  `UnifiedPush registration: no distributor found (ToSelect, distributor list empty)`.

Former gap (no line names the selected distributor) — closed for the
auto-pick path by the pre-register line above; the later distributor
change (second sub-case) still has no distributor-specific log line of
its own, only the generic `UNREGISTERED` handling.

### 3. Registration rejected by the distributor

The distributor broadcasts `REGISTRATION_FAILED` with a `reason` extra
(one of `INTERNAL_ERROR`, `NETWORK`, `ACTION_REQUIRED`, `VAPID_REQUIRED`)
→ `PushController.onRegistrationFailed` → `PushState.FAILED`. Log line
(exact):

```
Push registration failed: <REASON>
```

The room list shows "Push: registration failed". A pusher-side failure
later in the chain (`registerPushEndpoint` throws) logs
`Pusher registration failed: ${t.message}` instead.

### 4. Notification permission missing (`POST_NOTIFICATIONS` not granted, API 33+)

The whole push chain still runs (parse → session → sync → resolution),
but `PushNotifier.show` returns silently before `notify()` — no
notification is rendered, nothing crashes.

no log line emitted — gap

### 5. "POST_ENDPOINTS" not delivered

Cross-check result: **an action named `POST_ENDPOINTS` does not exist**
in the pinned connector 3.3.5, in the UnifiedPush spec (AND_3.1.0), or in
the ntfy distributor. The endpoint-delivery broadcast is
`org.unifiedpush.android.connector.NEW_ENDPOINT`. If that broadcast is
never delivered after registration (distributor dead, broadcast lost):

- no callback fires (`onNewEndpoint` is never called), so the pusher is
  never registered and the endpoint never reaches the homeserver;
- the push state stays at `READY` — the room list keeps showing
  "Push: registering …" indefinitely;
- nothing is written to the log.

no log line emitted — gap

### Observed additional gaps (same investigation)

- `TEMP_UNAVAILABLE` (push server temporarily down) is not declared in
  the manifest intent-filter and `onTempUnavailable` is not overridden:
  the event is silently ignored; the UI keeps the last state.
  no log line emitted — gap
- Receiver-level failures log to logcat only
  (`Push handling failed: ${t.message}`) and are missing from the
  "Send log" dump (see table above) — a diagnosability gap, not a
  functional one.

## What the local gate proves — and what it cannot

The E2E gate (`scripts/emulator-e2e.sh`) proves the server side of the
chain (pusher registration against Conduit, Conduit→ntfy publish, and
`PushPayload.parse` of the real message bytes — steps C2/C2b/C2c) plus
build/sign-in smoke. It **cannot** prove the on-device distributor
registration (no distributor is installed on the emulator): distributor
selection, the `REGISTER` broadcast, the ntfy subscription display, and
the rendered notification remain device-manual checks
(`docs/manualtest-protokoll.md`, MT-5/MT-6/MT-8). This checklist exists
so those manual steps are checkable line by line.
