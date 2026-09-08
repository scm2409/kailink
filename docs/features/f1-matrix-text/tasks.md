# f1-matrix-text – tasks.md

Erledigt (Phase 1):

- [x] Domänentypen Session/Room/Message, `ChannelClient`, `SessionStore`.
- [x] `TimelineReducer` mit Zwischenständen (JVM-geprüft).
- [x] `InMemoryChannelClient` mit Demoräumen, Login/Restore, Senden.
- [x] ViewModels Login/Raumliste/Chronik (StateFlow, JVM-geprüft).
- [x] UI mit Framework-Views (Anmeldung, Raumliste, Chronik).

Offen (Phase 2):

- [x] Adaptertausch auf `MatrixSdkChannelClient` (matrix-rust-sdk).
      *(2026-09-08: kompilierfähiger Adapter im Build, in `AppGraph`
      verdrahtet; Laufzeit gegen echten Homeserver noch nicht beobachtet.)*
- [ ] E2EE: Krypto-Store, Unable-to-Decrypt-Darstellung.
      *(Teilstand 2026-09-08: SQLite-Store konfiguriert,
      UTD-Erkennung im Adapter vorhanden — Verifizierung offen.)*
- [ ] Compose-Umstellung der Screens (ViewModels bleiben).
