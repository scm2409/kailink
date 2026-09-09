# Architecture

## 1. Interaction model (Phase-1 scenario)

1. The user opens KaiLink → login screen (homeserver URL, account, password).
2. After a successful login: room list; encrypted rooms are marked.
3. Opening a room → timeline; messages are sent via a text field.
4. Push chain: state display (distributor → endpoint → registered); a
   simulated incoming push triggers a sync.
5. Speech input/output are deliberately **not** implemented interactively; the
   seams exist and are documented (see
   [`features/sprache.md`](features/sprache.md)).

In Phase 1 the channel service runs as an in-memory simulation — **no
network connections** are established. All interfaces already correspond to
the Phase-2 geometry.

## 2. Layers

```
┌──────────────────────────────────────────────────────┐
│ ui/  (framework views + ViewModels, StateFlow)       │  ← knows only domain
├──────────────────────────────────────────────────────┤
│ domain/  (Session, Room, Message, ChannelClient,     │
│          SessionStore, TimelineReducer,              │
│          push seams, speech seams)                   │  ← pure Kotlin, JVM-testable
├──────────────────────────────────────────────────────┤
│ data/  (InMemoryChannelClient, FileSessionStore,     │
│         PushController, SimulatedPushTrigger)        │  ← Android only at the edge
├──────────────────────────────────────────────────────┤
│ Phase 2: MatrixSdkChannelClient (matrix-rust-sdk),   │
│          UnifiedPush connector + receiver, Compose   │
└──────────────────────────────────────────────────────┘
```

- **Dependency rule:** arrows point only downward. `ui` and `domain`
  know no Android and no channel SDK types; `data` translates
  channel-side states into domain events (`ChannelEvent`) and
  timeline patches (`TimelinePatch`).
- **Wiring:** `KaiLinkApp` creates `AppGraph` (manual DI, no
  Hilt/Dagger — deliberate, to keep the PoC's compilation surface small).
- **ViewModels** are pure Kotlin classes with an injectable
  `CoroutineScope` and `StateFlow<UiState>` — therefore JVM-testable and without
  an AndroidX Lifecycle dependency.

## 3. Data flow (Phase 1)

```
UI action (button/text field)
   → ViewModel (StateFlow<UiState>)
   → ChannelClient (InMemoryChannelClient)
   → ChannelEvent (RoomsUpdated | TimelineUpdated | SyncStateChanged | ClientError) ── SharedFlow
   → ViewModel updates UiState
   → Activity renders (adapters/TextViews)
```

Sending: `TimelineViewModel.send()` → `ChannelClient.sendMessage()` →
stored in the in-memory store + `TimelineUpdated` event → the timeline renders
the outgoing message.

Timeline patches: `TimelineReducer.apply(messages, patches)` remains the pure
domain function; Phase 2 feeds it with translations of SDK `TimelineDiff`s,
Phase 1 demonstrates it via the JVM checks.

## 4. Push chain (without Google)

```
Phase 1 (simulation):                 Phase 2 (real):
SimulatedPushTrigger                  UnifiedPush distributor (e.g. ntfy)
   │  tryRegister()                      │  broadcasts (connector actions)
   ▼                                     ▼
PushController  ──▶ ① onNewEndpoint → ChannelClient.registerPushEndpoint(url)
                ──▶ ② onMessage()    → ChannelClient.syncOnce()
   │
   └─ StateFlow<PushState> → room list ("Push: registered (UnifiedPush)")
```

- The state machine (`PushController`) is identical in both phases and
  JVM-tested (`pushControllerChecks`, `pushChainChecks`).
- Phase 1 simulates distributor, endpoint, and push delivery locally
  (`SimulatedPushTrigger`); no notification rendering (documented
  PoC limit, see [`features/push.md`](features/push.md)).

## 5. Persistence

- **Domain session:** `FileSessionStore` (`session.properties`,
  `java.util.Properties`) in the app files directory. Deliberately Phase-1
  unencrypted on the device; for production: EncryptedFile/Keystore
  (open, see verification.md).
- **Crypto store (Phase 2):** SQLite via the Rust SDK in
  `context.filesDir/matrix/store` — survives restarts (prerequisite for E2EE).

## 6. Verified dependencies

**Phase-1 state (offline, 2026-09-08):** Gradle 9.1.0, AGP 8.13.2,
Kotlin 2.2.21, kotlinx-coroutines-android 1.7.3, Android platform 36,
build-tools 35.0.0 — all present in the local Gradle cache and evidenced by a
successful offline build (G3/G8; details in
[`features/verification.md`](features/verification.md)).

**Phase-2 state (online, 2026-09-08):** additionally integrated and evidenced by
`./gradlew testDebugUnitTest assembleDebug` (BUILD SUCCESSFUL):

| Artifact | Version | Role |
| --- | --- | --- |
| org.matrix.rustcomponents:sdk-android | 26.09.08 | real Matrix channel (`MatrixSdkChannelClient`), Rust `libmatrix_sdk_ffi.so` in the APK |
| org.unifiedpush.android:connector | 3.3.5 | UnifiedPush registration + receiver |
| junit:junit | 4.13.2 | JVM checks as an ordinary JUnit task |
| kotlinx-coroutines-test | 1.7.3 | test classpath |

Still not included: Jetpack Compose (screens remain framework views;
ViewModels are unaffected).

## 7. Deliberate PoC limits (as of 2026-09-08)

1. ~~The channel service is an in-memory simulation~~ — since Phase 2
   `AppGraph` wires the real `MatrixSdkChannelClient`; the in-memory variant
   remains as a JVM reference for checks. Runtime against a
   real homeserver has not yet been observed (no device/access).
2. E2EE: the SQLite crypto store is configured, UTD detection in the adapter
   exists; full verification (devices, verification flows) is
   open (see [`features/nachrichten-e2ee.md`](features/nachrichten-e2ee.md)).
3. Persistence of the domain session without the Android Keystore.
4. Push: the UnifiedPush registrar/receiver are wired; notifications
   are not rendered; runtime against a real distributor not observed.
5. No background service (WorkManager) — sync in the foreground or triggered
   via push.
6. Several installed UnifiedPush distributors without user choice: the
   registrar deterministically picks the first reported one (documented
   limit).
7. The speech seams have only no-op implementations.

## 8. Migration path Phase 2

1. `InMemoryChannelClient` → `MatrixSdkChannelClient`; `AppGraph` is the
   only change site. *(done 2026-09-08, including moving the packages
   to `org.box44.kailink`)*
2. `SimulatedPushTrigger` → `UnifiedPushRegistrar` + `KaiLinkPushReceiver`
   (re-add the manifest receiver entries). *(done 2026-09-08)*
3. Framework views → Jetpack Compose (map the screens 1:1 onto `@Composable`;
   ViewModels remain unchanged). *(open)*
4. Integrate JUnit 4/5 + `kotlinx-coroutines-test` and move the checks from
   `phase1Checks` to regular `testDebugUnitTest` tests.
   *(done 2026-09-08: JUnit 4, `AllChecksTest`)*
5. `INTERNET` usage: real homeserver communication; `POST_NOTIFICATIONS`
   for push notifications. *(open; `INTERNET` is declared,
   runtime proof still pending)*
