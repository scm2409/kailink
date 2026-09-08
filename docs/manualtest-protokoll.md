# Manuelles Testprotokoll (V4, Gerät/Emulator)

**App:** KaiLink PoC, Debug-Build `kailink-poc-v0.1.0-debug.apk`
**Vorbereitung (einmalig):**

1. Zwei Matrix-Konten auf demselben Homeserver (im Folgenden **A** und **B**).
2. Gerät/Emulator mit installierter App; **kein** Google-Konto nötig.
3. Für Push: UnifiedPush-Distributor installieren (empfohlen: ntfy aus F-Droid).
4. Protokollführer notiert Datum, Geräte-ID (Einstellungen → Über) und
   beobachtete Abweichungen. **Jeder Testfall braucht Ergebnis
   „bestanden/fehlgeschlagen" + Beobachtung.**

> Alles, was hier nur „beschrieben" ist, gilt in `verification.md` als
> **nicht beobachtet**, bis das Protokoll mit Ergebnissen ausgefüllt wurde.

---

## MT-1 Matrix-Login (Konto A)

1. KaiLink starten → Anmeldeoberfläche erscheint.
2. Homeserver-URL `https://<hs>`, Konto A, Passwort eingeben → „Anmelden".
3. **Erwartung:** Fehlermeldungen bei leeren Feldern (deutsch); bei korrekten
   Daten Wechsel in die Raumliste; Raumliste zeigt A' Räume; kein Absturz.
4. **Abbruchfall:** absichtlich falsches Passwort → deutsche Fehlermeldung im
   Formular, App stürzt nicht ab.

## MT-2 Sitzungswiederherstellung

1. MT-1 erfolgreich; App über System-Zurück beenden; Gerät sperren/entlassen.
2. KaiLink erneut starten.
3. **Erwartung:** Kein erneutes Passwortabfragen — automatische
   Wiederherstellung springt direkt in die Raumliste; die Raumliste zeigt
   denselben Kontonamen (Raumliste/Zähler plausibel).
4. **E2EE-Voraussetzung prüfen:** nach Wiederherstellung später MT-4 erneut
   ausführen (sicherstellt, dass der SQLite-Krypto-Store überlebt hat).

## MT-3 Raumliste & Chronik öffnen

1. In der Raumliste einen (vorzugsweise verschlüsselten) Raum antippen.
2. **Erwartung:** Chronik öffnet; vorhandene Nachrichten erscheinen
   (entweder Inhalt oder „verschlüsselt — kann nicht entschlüsselt werden");
   verschlüsselte Räume tragen das „🔒"-Badge in der Liste.
3. Zurück zur Raumliste; erneut in denselben Raum: Chronik-Konsistenz.

## MT-4 E2EE-Verifizierung (Geräte-/Schlüssel-Vertrauen)

> Der PoC baut **keine** eigene SAS-Verifizierungs-UI. Das Vertrauen läuft
> über Cross-Signing/Standardverhalten des Rust-SDK. Dieser Testfall prüft
> das beobachtbare Endergebnis.

1. Konto B (anderes Gerät oder Webclient) antwortet in einem verschlüsselten
   Raum an Konto A.
2. **Erwartung A:** B' Nachrichten erscheinen lesbar (SDK hat Schlüssel
   verteilt/verwendet); **keine** `UNDECRYPTABLE`-Einträge für B' neue
   Nachrichten.
3. **Erwartung B:** B sieht A' Antwort ebenfalls lesbar (kein
   Entschlüsselungsfehler beim Gegenüber).
4. **Beobachtung dokumentieren:** Datum/Uhrzeit, Raum-ID, ob
   „verschlüsselt — kann nicht entschlüsselt werden" auftauchte.

## MT-5 Verschlüsselt senden

1. In verschlüsseltem Raum aus MT-3/MT-4 eine Textnachricht senden.
2. **Erwartung A:** Nachricht erscheint sofort als ausgehende Nachricht.
3. **Erwartung B:** Auf B-Gerät/Webclient erscheint dieselbe Nachricht
   (lesbar, E2EE-Raum). Am Webclient optional prüfen: Ereignis ist vom Typ
   `m.room.encrypted` (Server sieht Klartext nicht).
4. **Abbruchfall:** Flugmodus → Senden → Fehlerzustand sichtbar; App stürzt
   nicht ab; nach Netzrückkehr erneut senden (Send-Queue des SDK).

## MT-6 Verschlüsselt empfangen (Live-Sync)

1. App im Vordergrund im Raum; B sendet Nachricht.
2. **Erwartung:** Nachricht erscheint innerhalb weniger Sekunden (Live-Sync).
3. Chronik verlassen, B sendet erneut, Chronik wieder öffnen → Nachricht
   nachgeholt (`syncOnce` beim Öffnen).

## MT-7 UnifiedPush-Registrierung

1. ntfy (Distributor) installieren; KaiLink starten, MT-1 ausführen.
2. **Erwartung:** Raumliste zeigt Push-Zustand; nach Zustimmung im
   Auswahl-Dialog → „Push registriert".
3. Andernfalls (kein Distributor): Zustand „kein Distributor" — App bleibt
   nutzbar.

## MT-8 Push-Empfang → Sync

1. KaiLink in Hintergrund; B sendet Nachricht in denselben Raum.
2. **Erwartung (PoC-Grenze bewusst):** Distributor stellt Push zu
   (Benachrichtigungs-/Verbindungsindikator des Distributors); beim nächsten
   Öffnen von KaiLink ist die Nachricht da (Push stößt Sync an).
3. **Abbruchfall:** Distributor deaktivieren → App bleibt über manuelles
   Öffnen nutzbar.

---

## Ergebnisvermerk

| Testfall | Datum | Ergebnis | Beobachtung |
| --- | --- | --- | --- |
| MT-1 | – | nicht beobachtet | – |
| MT-2 | – | nicht beobachtet | – |
| MT-3 | – | nicht beobachtet | – |
| MT-4 | – | nicht beobachtet | – |
| MT-5 | – | nicht beobachtet | – |
| MT-6 | – | nicht beobachtet | – |
| MT-7 | – | nicht beobachtet | – |
| MT-8 | – | nicht beobachtet | – |