# f1-matrix-text – tasks.md

Done (Phase 1):

- [x] Domain types Session/Room/Message, `ChannelClient`, `SessionStore`.
- [x] `TimelineReducer` with intermediate states (JVM-checked).
- [x] `InMemoryChannelClient` with demo rooms, login/restore, sending.
- [x] ViewModels login/room list/timeline (StateFlow, JVM-checked).
- [x] UI with framework Views (login, room list, timeline).

Open (Phase 2):

- [x] Adapter swap to `MatrixSdkChannelClient` (matrix-rust-sdk).
      *(2026-09-08: compilable adapter in the build, wired into `AppGraph`;
      runtime against a real homeserver not yet observed.)*
- [ ] E2EE: crypto store, Unable-to-Decrypt representation.
      *(Partial status 2026-09-08: SQLite store configured,
      UTD detection present in the adapter — verification open.)*
- [ ] Compose migration of the screens (ViewModels remain).
