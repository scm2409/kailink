# f1-matrix-text – requirements.md

Functional requirements for text messages over a Matrix channel
(Phase 1: fulfilled against the in-memory simulation; Phase 2: against the real
matrix-rust-sdk, see `app/src/phase2/`).

1. Login with homeserver URL, account, password; English error messages
   for empty fields and failures.
2. Automatic session restoration at startup from the `SessionStore`;
   sessions that cannot be restored are deleted.
3. Room list with display name, 🔒 badge (encrypted) and preview of the
   last message; updates via sync and event.
4. Timeline per room with incoming/outgoing distinction; sending with
   trimming, empty-field protection and draft restoration on errors.
5. All UI texts and error messages in English (G9).
