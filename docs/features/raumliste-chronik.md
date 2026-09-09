# Feature: Room List & Timeline

## Interaction Model

- After login the app shows the room list (display name, 🔒 badge for
  encrypted rooms, preview of the last message).
- Tapping a room opens the timeline: message list (incoming/outgoing
  visually separated), a send field at the bottom; "Back" or system back
  returns to the room list.
- Sending clears the field; a send error restores the draft.

## Implementation (Phase 1)

- Room list: `ChannelClient.rooms()` → `List<Room>` from the
  in-memory simulation (two demo rooms, "Project Channel Phase 1" with
  `isEncrypted = true`); updates via `ChannelEvent.RoomsUpdated`
  and directly after `syncOnce`/`refresh()`.
- Timeline: subscription via `ChannelClient.openTimeline(roomId)`; updates arrive
  as `ChannelEvent.TimelineUpdated` (filtered by room ID in the
  ViewModel). Messages are kept in the in-memory store.
- Reducer logic: `TimelineReducer.apply` (pure domain function) is the
  fixed interface for timeline intermediate states — covered in Phase 1 by
  the JVM checks, fed in Phase 2 by the SDK adapter.
- UI: framework Views (`ListView` + adapter) instead of Compose; the ViewModels
  are UI-independent and reusable for Compose.

## Phase-2 Outlook

- `Client.rooms()` (SDK), `Room.timeline()` + `TimelineListener`: the
  `TimelineDiff` sequence is translated into `TimelinePatch` and passed to
  `TimelineReducer`; sending via the SDK's send queue
  (encryption + retry included).

## PoC Limits

- No real network communication; content lives only in memory.
- No unlimited paging (back-pagination), no media, no
  replies/threads, no read receipts.

## Verification

V1: `RoomListViewModelChecks` (3), `TimelineViewModelChecks` (5) and
`InMemoryChannelClientChecks` (6) — passed 2026-09-08, see
[`verification.md`](verification.md). V4: manual protocol, test cases
MT-3/MT-4/MT-5 (**not observed**, no device).
