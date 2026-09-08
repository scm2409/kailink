# f1-matrix-text – design.md

Beteiligte Komponenten (Abhängigkeitsrichtung nur nach unten, siehe
`docs/architecture.md`):

- `domain/ChannelClient` + `ChannelEvent` – Kanal-Nahtstelle (reines Kotlin).
- `data/channel/InMemoryChannelClient` – Phase-1-Adapter: Sitzung, Räume,
  Chronik und Senden im Speicher, Ereignisse über SharedFlow.
- `domain/TimelineReducer` – reine Funktion für Chronik-Patches (Phase 2:
  Übersetzung der SDK-`TimelineDiff`-Folge).
- `ui/login/LoginViewModel`, `ui/rooms/RoomListViewModel`,
  `ui/timeline/TimelineViewModel` – StateFlow-UiStates, injizierbarer Scope.
- `MainActivity` – Framework-Views, drei umgeschaltete Screens.

Phase-2-Tausch: nur der Adapter in `AppGraph` ändert sich
(`MatrixSdkChannelClient`, Referenz unter `app/src/phase2/`).
