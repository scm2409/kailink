# Feature: Sending/Receiving Messages & E2EE Outlook

## Interaction Model (Phase 1, Simulation)

- Sending: text into the send field, "Send" → the message appears as
  an outgoing bubble in the timeline; the send field is cleared.
- Receiving: messages of the demo rooms appear when opening; new
  "events" are delivered by the in-memory simulation via `TimelineUpdated`.
- **Phase 1 has no encryption**: the demo rooms' 🔒 badge is
  display-only (the `isEncrypted` flag); content sits in plaintext in
  memory.

## Implementation (Phase 1)

- Sending: `ChannelClient.sendMessage(roomId, body)` → stored in the
  in-memory store (direction `OUTGOING`, state `SENT`) →
  `TimelineUpdated` event → timeline renders.
- `DeliveryState.UNDECRYPTABLE` already exists in the domain model and is
  rendered in red by the timeline — the simulation does not produce it;
  Phase 2 delivers real `UnableToDecrypt` cases.

## E2EE Outlook (Phase 2)

- Real `matrix-rust-sdk` (Android bindings): Megolm/Olm, send queue,
  automatic key management, SQLite crypto store
  (`context.filesDir/matrix/store`) → keys survive restarts.
- Translation: SDK `TimelineDiff` → `TimelinePatch` →
  `TimelineReducer.apply`; `MsgLikeKind.UnableToDecrypt` →
  `DeliveryState.UNDECRYPTABLE`.
- An explicit verification UI (SAS/emoji comparison) is **not**
  planned; trust is handled by the SDK's default behavior
  (cross-signing). The Phase-2 manual protocol checks encrypted
  traffic between two devices/accounts.

## PoC Limits

- No dedicated verification UI, no invitation acceptance, no room
  creation.
- `EventSendState` is not rendered as progress; outgoing
  messages are tracked as `SENT` after `send()` returns.

## Verification

V1: `TimelineViewModelChecks` (5, incl. room filter and error paths) and
`InMemoryChannelClientChecks` (6) — passed 2026-09-08, see
[`verification.md`](verification.md). V4: manual protocol, test cases
MT-3/MT-4 (**not observed**, no device). E2EE itself: Phase 2.
