# f1-matrix-text – design.md

Involved components (dependency direction downward only, see
`docs/architecture.md`):

- `domain/ChannelClient` + `ChannelEvent` – channel seam (pure Kotlin).
- `data/channel/InMemoryChannelClient` – Phase-1 adapter: session, rooms,
  timeline and sending in memory, events via SharedFlow.
- `domain/TimelineReducer` – pure function for timeline patches (Phase 2:
  translation of the SDK `TimelineDiff` sequence).
- `ui/login/LoginViewModel`, `ui/rooms/RoomListViewModel`,
  `ui/timeline/TimelineViewModel` – StateFlow UiStates, injectable scope.
- `MainActivity` – framework Views, three switched screens.

Phase-2 swap: only the adapter in `AppGraph` changes
(`MatrixSdkChannelClient`, reference under `app/src/phase2/`).
