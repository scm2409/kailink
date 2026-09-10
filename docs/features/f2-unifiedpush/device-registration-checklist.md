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
| `ResolvedDistributor.Found` | `UnifiedPush.saveDistributor(context, resolved.packageName)` **first** (a resolution is not connector-store persistence; `register` broadcasts only to a saved distributor), then `register()` (Step 3). |
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
build/sign-in smoke, **and since 0.2.8 also leg 7** (`FreshInstallPushE2eTest`,
see the section below): the on-device distributor registration on a
genuinely fresh KaiLink install (`pm clear org.box44.kailink` only — the
ntfy distributor app and its state are never touched), the app's real
`up*` endpoint registered as a Conduit pusher, and the rendered
notification as the outermost observable effect of a real push through
the distributor.

What the gate still **cannot** prove: the Conduit→gateway hop for the
app's **own** pusher (its registered `data.url` is
`https://ntfy.sh/_matrix/push/v1/notify` by default, so a
Conduit-initiated push would go to public ntfy.sh; leg 7's delivery leg
publishes the identical gateway bytes directly to the real topic) and
any behavior of physical GMS-less hardware (device-manual checks,
`docs/manualtest-protokoll.md`, MT-5/MT-6/MT-8). This checklist exists
so those manual steps remain checkable line by line.

## Superseded run finding (2026-09-10, morning) — corrected below

The following earlier finding was **wrong and is superseded** by the
corrected finding below. It is kept here (history, marked as superseded
so the wrong claim is never re-cited):

> The pinned Gradle dependency `org.matrix.rustcomponents:sdk-android:26.09.08`
> was inspected from the Gradle cache at the AAR level. An `unzip` listing of
> the AAR showed **zero entries under `lib/`** — the AAR contains no native
> `.so` libraries at all, and in particular **no x86_64 libraries**. Therefore
> the prescribed x86_64 debug/emulator build cannot be evaluated or produced
> from this pinned SDK.

**Correction (2026-09-10, coordinator correction, verified in this run):**
the earlier inspection queried the **wrong archive path**. Android AARs
store native libraries under `jni/<abi>/`, not `lib/` — a grep for `lib/`
misses them. The actual AAR (Gradle cache path
`org.matrix.rustcomponents/sdk-android/26.09.08/3525685e…/sdk-android-26.09.08.aar`)
contains exactly the expected native libraries:

```
jni/arm64-v8a/libmatrix_sdk_ffi.so   (63,378,440 bytes)
jni/armeabi-v7a/libmatrix_sdk_ffi.so (41,642,748 bytes)
jni/x86/libmatrix_sdk_ffi.so         (73,037,112 bytes)
jni/x86_64/libmatrix_sdk_ffi.so      (69,989,968 bytes)
```

The x86_64 emulator build therefore works with the pinned SDK, unchanged.
`./gradlew :app:assembleEmulatorDebug --offline` produces
`app/build/outputs/apk/emulatorDebug/app-emulatorDebug.apk` containing
`lib/x86_64/libmatrix_sdk_ffi.so` (69,989,968 bytes); the gate's
`app-emulatorDebug.apk` installs with `primaryCpuAbi=x86_64` on the
kailink-atd35 AVD. The earlier "no-matching-ABIs" installation failure
came from installing the arm64-only `debug` APK by mistake.

## Observed run (2026-09-10, afternoon) — registration classified, chain proven

Setup: x86_64 AVD `kailink-atd35` (API 35, `emulator-5554`), only
`app-emulatorDebug.apk` installed (versionName 0.2.7-phase1),
ntfy Android 1.25.2 (`io.heckel.ntfy`, versionCode 63) installed with
`POST_NOTIFICATIONS` granted to both apps, Conduit/ntfy/nginx-TLS gate
containers running, `adb reverse` for tcp:6167/tcp:8090/tcp:8443.

**Classification of the observed break: case (c)** — both diagnostic
lines (`calling UnifiedPush.register`, `UnifiedPush.register returned`)
appear, distributor `io.heckel.ntfy`, but no `up*` endpoint and no
`up*` pusher. Root cause found **between KaiLink and the distributor**:
the REGISTER broadcast is never sent at all.

- KaiLink side (connector 3.3.5 `DBStore` inspection on device):
  `registrations` has the row (`instance=default`, empty message/vapid),
  `keys` has the WebPush keys, but `distributors` and `tokens` are
  **empty**. In connector 3.3.5, `UnifiedPush.register(context)` builds
  the broadcast from the token set returned by
  `DBStore.RegistrationsStore.set(...)` — and `set()` only creates
  tokens for distributors **already present in the connector's
  `distributors` table**. With that table empty, `register()` returns
  silently without sending any broadcast (exactly what the logs show).
  The connector KDoc for `register` states: *"saveDistributor must be
  called before this function."*
- `UnifiedPushRegistrar.tryRegister` calls `UnifiedPush.saveDistributor`
  only in the `ToSelect` branch, **not** in the `Found` branch
  (`UnifiedPushRegistrar.kt:32` — `Found` goes straight to
  `register(resolved.packageName)`). On a fresh install with the single
  distributor ntfy, `resolveDefaultDistributor` resolves `Found` (via
  the OS `unifiedpush://link` resolution), so nothing is ever saved and
  the broadcast never fires. This matches failure mode 5's shape
  (state stuck at `READY`/"Push: registering …"), but the gap is in the
  registrar's `Found` branch, not in broadcast delivery.

**Distributor server determination (case (c) requirement):** the ntfy
Android app's saved `DefaultBaseURL` (read-only inspection of
`/data/data/io.heckel.ntfy/shared_prefs/MainPreferences.xml`) is
`http://127.0.0.1:8090` — the **Podman ntfy container** via the gate's
`adb reverse` tunnel. **Not ntfy.sh.** A root-shell `REGISTER` probe to
`io.heckel.ntfy` created the `up*` subscription with
`baseUrl=http://127.0.0.1:8090` and sent `NEW_ENDPOINT`
(`http://127.0.0.1:8090/up…?up=1`) — the distributor itself works.

**Additional environment fact (delivery leg):** the ntfy distributor
gates its subscriber connections on
`ConnectivityManager.activeNetwork != null`
(`SubscriberService.reallyRefreshConnections()`; notification shows
"Waiting for network"). The kailink-atd35 AVD booted with **no active
default network** ("Active default network: none") — the known
"emulator network broken" condition the gate tunnels around. KaiLink's
loopback sockets work without a default network, but the ntfy app does
not connect. Provisioning the emulator's virtual AP
(`adb shell cmd wifi connect-network AndroidWifi open` →
`Active default network: 100`, IP 10.0.2.16) makes the ntfy app listen
("Listening for incoming notifications", established connections to
127.0.0.1:8090). Gate-infrastructure provisioning, no project change.

**Proof that everything after the missing `saveDistributor` works
(diagnostic device-state injection, not a code fix):** inserting the
`distributors` row exactly as `saveDistributor` would
(`INSERT INTO distributors (distributor, fallback_from, ack,
date_insertion) VALUES ('io.heckel.ntfy', NULL, 0, <ms>)` in
`/data/data/org.box44.kailink/databases/unifiedpush-connector`) and
restarting the app produces the full chain:

- `REGISTER received for app org.box44.kailink (connectorToken=…)` —
  AND_3.1.0 shared-identity path (`Package name retrieved with shared
  identity`).
- ntfy subscription `up3QmdfC4EJh51` created on
  `http://127.0.0.1:8090` (Podman server).
- `NEW_ENDPOINT http://127.0.0.1:8090/up3QmdfC4EJh51?up=1`.
- KaiLink logs `UnifiedPush endpoint registered as Matrix pusher
  (gateway: https://ntfy.sh/_matrix/push/v1/notify)` and
  `Push endpoint registered as Matrix pusher`; Conduit `GET /pushers`
  then shows the run pusher with
  `pushkey: http://127.0.0.1:8090/up3QmdfC4EJh51?up=1`.
- Room list reaches **"Push: registered (UnifiedPush)"**
  (`PushState.REGISTERED`) — checklist Step 7 criterion met.

**Real push delivery (outermost observable effect):** a Bob message sent
through Conduit (`kailink-push-task3` room), with the gateway notify
body (the exact `{"notification":{…}}` shape the homeserver POSTs, same
as gate step C2b) published to the real `up*` topic on the Podman ntfy:
ntfy → UnifiedPush MESSAGE → `KaiLinkPushReceiver` (connector WebPush
decrypt attempt, plaintext fallback, expected for this chain) →
`PushMessageHandler` (parse → session restore → `syncOnce()` →
notification resolution) → **rendered KaiLink notification** on channel
"Push messages": title `kailink-push-task3`, text
`kailink_bob: Task 3 push delivery probe`. A malformed event id
(delivery test with a truncated `event_id`, no leading `$`) was also
handled correctly: `Notification fetch failed:
msg=leading sigil is incorrect or missing, details=MissingLeadingSigil`
— resolution failure surfaced, app stayed up, wake-up sync ran.

**Configuration gap (reported, NOT changed):** KaiLink registers the
pusher with `data.url = https://ntfy.sh/_matrix/push/v1/notify`
(`PushConfiguration.DEFAULT_GATEWAY_URL`) while the distributor's
endpoint is the local Podman server. A Conduit-initiated push for the
**app's own pusher** would go to public ntfy.sh (where nobody listens
for the local topic) instead of the Podman ntfy. The gate covers this
hop only for its synthetic test pusher (`e2e.pusher_gateway
http://kailink-e2e-ntfy`). The delivery leg above therefore used the
Podman ntfy topic directly (identical gateway bytes) — every hop of the
chain is proven by gate + this run, except Conduit→(app's own pusher
pointing at ntfy.sh), which cannot deliver in this environment by
design of the current default.

**Fix ownership:** the one-line registrar fix (call
`UnifiedPush.saveDistributor` in the `Found` branch before
`register`) and any gateway decision are project-owner decisions; no
source, build config, dependencies, or gate behavior was changed for
these findings.

## Resolution (0.2.8, 2026-09-10): the registrar gap is fixed and gate-proven

The `Fix ownership` note above was acted on: the **registrar fix** is
implemented (the **gateway decision is not** — see below).

- `UnifiedPushRegistrar.tryRegister` (`Found` branch) now calls
  `UnifiedPush.saveDistributor(context, resolved.packageName)` before
  `register(resolved.packageName)`. The connector KDoc requires it
  ("saveDistributor must be called before this function"); connector
  3.3.5's `register` broadcasts only to a distributor **saved in its
  store** (`getDistributor(context, store, ack=false)`) and returns
  silently otherwise — exactly the case-(c) break classified in the
  2026-09-10 runs above. The reference connector flow
  (`tryUseDefaultDistributor`) does the same
  (`saveDistributor(context, it)` → register). The `ToSelect` branch
  already saved; its behavior is unchanged.
- Proven by gate leg 7 (`FreshInstallPushE2eTest`, TDD red → green;
  full evidence in `docs/features/verification.md` §12): after
  `pm clear org.box44.kailink` (ntfy state untouched), a real UI
  sign-in ends with the app's real `up*` endpoint as a Conduit pusher
  (`http://127.0.0.1:8090/upYCCyckyIljkU?up=1`, app_id
  `org.box44.kailink`) and the rendered KaiLink notification for a real
  event id published through the real distributor.
- The **gateway configuration gap stays open by owner decision**:
  `PushConfiguration.DEFAULT_GATEWAY_URL` remains
  `https://ntfy.sh/_matrix/push/v1/notify`, so leg 7 cannot prove
  Conduit→gateway for the app's own pusher in this environment (it
  publishes the identical gateway bytes to the real topic instead).
- Version bumped to `0.2.8` (versionCode 6); the first DebugLog line
  remains the BuildConfig identity (`KaiLink 0.2.8 (versionCode 6)`).
