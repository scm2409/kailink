# Feature: Nachrichten senden/empfangen & E2EE

## Interaktionsmodell

- Senden: Text in das Sendefeld, Enter/„Senden" → Nachricht erscheint als
  ausgehende Nachricht in der Chronik.
- Empfangen: eingehende Nachrichten erscheinen live (Vordergrund-Sync) bzw.
  nach Push-ausgelöstem Sync.
- Verschlüsselte Räume sind in der Raumliste markiert. Nachrichten, die nicht
  entschlüsselt werden konnten, erscheinen als „(verschlüsselt — kann nicht
  entschlüsselt werden)" (`DeliveryState.UNDECRYPTABLE`).

## Implementierung

- Senden: `Timeline.createMessageContent(MessageType.Text(TextMessageContent(body, null)))`
  → `Timeline.send(content)`. Die Send-Queue und Verschlüsselung des
  Rust-SDK übernehmen Zustellung, Wiederholung und Megolm-Verschlüsselung.
- Empfangen: Chronik-Listener (siehe raumliste-chronik.md). `MsgLikeKind.UnableToDecrypt`
  wird auf `UNDECRYPTABLE` abgebildet, sonst auf Text-Inhalt (`MessageContent.getMsgType()`
  als `MessageType.Text` → `TextMessageContent.getBody()`).
- E2EE-Gerüst: SQLite-Krypto-Store (Schlüsselpersistenz), automatische
  Geräte-/Schlüsselverwaltung des Rust-SDK. Eine explizite
  Verifizierungs-UI (SAS/Emoji-Vergleich) ist im PoC **nicht** gebaut;
  Vertrauen läuft über das Standardverhalten des SDK
  (`autoEnableCrossSigning(true)` …). Das Protokoll (MT-4) prüft, dass
  verschlüsselter Verkehr zwischen zwei Geräten/Konten funktioniert.

## PoC-Grenzen

- Keine eigene Verifizierungs-UI, keine Einladungsannahme, keine Räume
  erstellen.
- `EventSendState` wird nicht als Fortschritt gerendert; ausgehende
  Nachrichten werden nach Rückkehr von `send()` als `SENT` geführt.

## Verifikation

Stufen V1–V3 siehe [`verification.md`](verification.md) (Abschnitt
Nachrichten/E2EE). Geräteprüfung (V4): manuelles Protokoll, Testfälle MT-4/MT-5/MT-6.
