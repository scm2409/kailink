# Feature: Raumliste & Chronik (Timeline)

## Interaktionsmodell

- Nach dem Login zeigt die App die Raumliste (Anzeigename, 🔒-Badge bei
  verschlüsselten Räumen, Vorschau der letzten Nachricht).
- Antippen eines Raums öffnet die Chronik: Nachrichtenliste (ein-/ausgehend
  visuell getrennt), unten ein Sendefeld; „Zurück" oder System-Zurück führt
  zur Raumliste.
- Senden leert das Feld; Sendefehler stellen den Entwurf wieder her.

## Implementierung (Phase 1)

- Raumliste: `ChannelClient.rooms()` → `List<Room>` aus der
  In-Memory-Simulation (zwei Demoräume, „Projektkanal Phase 1" mit
  `isEncrypted = true`); Aktualisierung über `ChannelEvent.RoomsUpdated`
  sowie direkt nach `syncOnce`/`refresh()`.
- Chronik: `ChannelClient.openTimeline(roomId)` abonniert; Updates kommen
  als `ChannelEvent.TimelineUpdated` (gefiltert nach Raum-ID im
  ViewModel). Nachrichten werden im In-Memory-Speicher geführt.
- Reduzier-Logik: `TimelineReducer.apply` (reine Domänenfunktion) ist die
  feste Schnittstelle für Chronik-Zwischenstände — in Phase 1 durch die
  JVM-Prüfungen abgedeckt, in Phase 2 vom SDK-Adapter gefüttert.
- UI: Framework-Views (`ListView` + Adapter) statt Compose; die ViewModels
  sind UI-unabhängig und für Compose wiederverwendbar.

## Phase-2-Ausblick

- `Client.rooms()` (SDK), `Room.timeline()` + `TimelineListener`: die
  `TimelineDiff`-Folge wird in `TimelinePatch` übersetzt und an
  `TimelineReducer` gegeben; Senden über die Send-Queue des SDK
  (Verschlüsselung + Wiederholung inklusive).

## PoC-Grenzen

- Keine echte Netzwerkkommunikation; Inhalte leben nur im Speicher.
- Kein unbegrenztes Nachladen (Backpagination), keine Medien, keine
  Antworten/Threads, keine Lesebestätigungen.

## Verifikation

V1: `RoomListViewModelChecks` (3), `TimelineViewModelChecks` (5) und
`InMemoryChannelClientChecks` (6) — bestanden 2026-09-08, siehe
[`verification.md`](verification.md). V4: manuelles Protokoll, Testfälle
MT-3/MT-4/MT-5 (**nicht beobachtet**, kein Gerät).
