# Feature: Login & Session Restoration

## Interaction Model

- The user enters homeserver URL, account, and password and selects
  "Sign in".
- If a stored session record exists, the app attempts restoration
  **automatically** at startup; the login screen then shows
  "Restoring session …" and jumps straight into the room list.
- Errors (empty fields, failed restoration) are shown as an
  error message in the form; a session that can no longer be
  restored is deleted.

## Implementation (Phase 1)

- Domain: `ChannelClient.login(url, user, password)` /
  `ChannelClient.restore(session)`; `SessionStore` as the persistence contract.
- Adapter: `InMemoryChannelClient` (Phase-1 simulation) creates a session from
  non-empty credentials
  (`@<user>:phase1.local`, random token) and stores it via
  `FileSessionStore`. **No** network connections are established.
- Persistence: `FileSessionStore` (`session.properties`, `java.util.Properties`)
  in the app files directory.

## Phase-2 Outlook

- `MatrixSdkChannelClient` (reference under `app/src/phase2/`) replaces the
  simulation: `ClientBuilder.homeserverUrl(...).sqliteStore(...).build()`,
  `Client.login(...)` or `Client.restoreSession(...)`.
- The crypto store (SQLite) survives restarts → restoration is also
  robust for E2EE.

## PoC Limits

- No password storage, no SSO/OAuth, no QR login.
- `FileSessionStore` stores the token unencrypted in the
  app-private directory (no Keystore) — intentional, noted as a risk in
  verification.md.
- Phase 1: login only validates the fields (simulation), no real accounts.

## Verification

V1: `LoginViewModelChecks` (5 checks) and
`InMemoryChannelClientChecks` (6 checks) — passed 2026-09-08, see
[`verification.md`](verification.md). V2/V3: offline build + badging. V4:
manual protocol, test cases MT-1/MT-2 (**not observed**, no device).
