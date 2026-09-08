# Feature: Raumliste & Chronik (Timeline)

## Interaktionsmodell

- Nach dem Login zeigt die App die Raumliste (Anzeigename, "verschlüsselt"-Badge,
  Vorschau der letzten Nachricht). Hintergrundsync aktualisiert sie.
- Antippen eines Raums öffnet die Chronik: Nachrichtenliste (ein-/ausgehend
  visuell getrennt), unten ein Sendefeld.
- Zurück zur Raumliste per System-Zurück.

## Implementierung

- Raumliste: `ChannelClient.rooms()` → `List<Room>` aus `Client.rooms()`;
  `Room.displayName()` (SDK, synchron), `Room.isEncrypted()` (suspend) und die
  letzte Chroniknachricht als Vorschau.
- Chronik: `Room.timeline()` → `Timeline.addListener(TimelineListener)`;
  die `TimelineDiff`-Folge wird von `MatrixSdkChannelClient` in
  `TimelinePatch`-Zwischenstände übersetzt und von `TimelineReducer.apply`
  (reine Domänenfunktion) auf `List<Message>` reduziert.
- Sync: `Client.syncOnceV2(SyncSettingsV2())` für einmalige Aktualisierung
  (z. B. nach Push), `syncService().finish().start()` für Live-Sync im
  Vordergrund.

## PoC-Grenzen

- Kein unbegrenztes Nachladen (Backpagination), keine Medien, keine Antworten/
  Threads, keine Lesebestätigungen.
- Raumliste ohne SDK-`RoomListService`-Live-Listener (siehe architecture.md).

## Verifikation

Stufen V1–V3 siehe [`verification.md`](verification.md) (Abschnitt
Raumliste/Chronik); Reduzier-Logik (`TimelineReducer`) ist mit JVM-Tests
abgedeckt. Geräteprüfung (V4): manuelles Protokoll, Testfälle MT-3/MT-5/MT-6.
