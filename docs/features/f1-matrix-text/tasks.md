# f1-matrix-text – tasks.md

Erledigt (Phase 1):

- [x] Domänentypen Session/Room/Message, `ChannelClient`, `SessionStore`.
- [x] `TimelineReducer` mit Zwischenständen (JVM-geprüft).
- [x] `InMemoryChannelClient` mit Demoräumen, Login/Restore, Senden.
- [x] ViewModels Login/Raumliste/Chronik (StateFlow, JVM-geprüft).
- [x] UI mit Framework-Views (Anmeldung, Raumliste, Chronik).

Offen (Phase 2):

- [ ] Adaptertausch auf `MatrixSdkChannelClient` (matrix-rust-sdk).
- [ ] E2EE: Krypto-Store, Unable-to-Decrypt-Darstellung.
- [ ] Compose-Umstellung der Screens (ViewModels bleiben).
