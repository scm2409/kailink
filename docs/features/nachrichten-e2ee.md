# Feature: Nachrichten senden/empfangen & E2EE-Ausblick

## Interaktionsmodell (Phase 1, Simulation)

- Senden: Text in das Sendefeld, „Senden" → Nachricht erscheint als
  ausgehende Sprechblase in der Chronik; Sendefeld wird geleert.
- Empfangen: Nachrichten der Demoräume erscheinen beim Öffnen; neue
  „Ereignisse" liefert die In-Memory-Simulation über `TimelineUpdated`.
- **Phase 1 hat keine Verschlüsselung**: das 🔒-Badge der Demoräume ist
  reine Anzeige (`isEncrypted`-Flag), Inhalte liegen im Klartext im
  Speicher.

## Implementierung (Phase 1)

- Senden: `ChannelClient.sendMessage(roomId, body)` → Ablage im
  In-Memory-Speicher (Richtung `OUTGOING`, Zustand `SENT`) →
  `TimelineUpdated`-Ereignis → Chronik rendert.
- `DeliveryState.UNDECRYPTABLE` existiert im Domänenmodell bereits und wird
  von der Chronik rot dargestellt — die Simulation erzeugt ihn nicht;
  Phase 2 liefert echte `UnableToDecrypt`-Fälle.

## E2EE-Ausblick (Phase 2)

- Echtes `matrix-rust-sdk` (Android-Bindings): Megolm/Olm, Send-Queue,
  automatische Schlüsselverwaltung, SQLite-Krypto-Store
  (`context.filesDir/matrix/store`) → Schlüssel überleben Neustarts.
- Übersetzung: SDK-`TimelineDiff` → `TimelinePatch` →
  `TimelineReducer.apply`; `MsgLikeKind.UnableToDecrypt` →
  `DeliveryState.UNDECRYPTABLE`.
- Eine explizite Verifizierungs-UI (SAS/Emoji-Vergleich) ist **nicht**
  geplant; Vertrauen läuft über das Standardverhalten des SDK
  (Cross-Signing). Das manuelle Protokoll von Phase 2 prüft verschlüsselten
  Verkehr zwischen zwei Geräten/Konten.

## PoC-Grenzen

- Keine eigene Verifizierungs-UI, keine Einladungsannahme, keine Räume
  erstellen.
- `EventSendState` wird nicht als Fortschritt gerendert; ausgehende
  Nachrichten werden nach Rückkehr von `send()` als `SENT` geführt.

## Verifikation

V1: `TimelineViewModelChecks` (5, inkl. Raum-Filter und Fehlerpfade) und
`InMemoryChannelClientChecks` (6) — bestanden 2026-09-08, siehe
[`verification.md`](verification.md). V4: manuelles Protokoll, Testfälle
MT-3/MT-4 (**nicht beobachtet**, kein Gerät). E2EE selbst: Phase 2.
