# Manuelles Testprotokoll (V4, Gerät/Emulator)

**App:** KaiLink Phase 1, Debug-Build `app-debug.apk`
(`applicationId at.d71.kailink`, versionName `0.1.0-phase1`, minSdk 28).

**Vorbereitung (einmalig):**

1. Gerät/Emulator mit Android 9+ (API 28+); **kein** Google-Konto nötig.
2. Installation: `adb install app/build/outputs/apk/debug/app-debug.apk`.
3. **Phase-1-Besonderheit:** der Kanaldienst ist eine In-Memory-Simulation.
   Anmeldedaten werden **nicht** gegen einen echten Homeserver geprüft;
   gültig ist jede nicht-leere Kombination. Es werden keine
   Netzwerkverbindungen aufgebaut.
4. Protokollführer notiert Datum, Geräte-ID und beobachtete Abweichungen.
   **Jeder Testfall braucht Ergebnis „bestanden/fehlgeschlagen" +
   Beobachtung.**

> Alles, was hier nur „beschrieben" ist, gilt in
> [`features/verification.md`](features/verification.md) als
> **nicht beobachtet**, bis das Protokoll mit Ergebnissen ausgefüllt wurde.

---

## MT-1 Anmeldung (Kanalsimulation)

1. KaiLink starten → Anmeldeoberfläche erscheint (Titel „KaiLink").
2. Alle Felder leer lassen → „Anmelden".
3. **Erwartung:** deutsche Fehlermeldung „Bitte alle Felder ausfüllen.",
   kein Login-Versuch, kein Absturz.
4. Homeserver-URL `https://phase1.local`, Benutzername `alice`, Passwort
   `geheim` → „Anmelden".
5. **Erwartung:** Button zeigt „Bitte warten …" mit Fortschrittsanzeige,
   danach Wechsel in die Raumliste; Kopfzeile zeigt „Angemeldet:
   @alice:phase1.local"; zwei Demoräume erscheinen („Projektkanal Phase 1"
   mit 🔒, „Notizen" ohne Badge).

## MT-2 Sitzungswiederherstellung

1. MT-1 erfolgreich; App über System-Zurück beenden; Gerät sperren/entlassen.
2. KaiLink erneut starten.
3. **Erwartung:** keine erneute Passwortabfrage — die Anmeldeoberfläche zeigt
   „Sitzung wird wiederhergestellt …" und springt direkt in die Raumliste;
   dieselbe Nutzerkennung wie in MT-1.
4. **Abbruchfall (optional):** `session.properties` vor dem Start beschädigen
   (z. B. leere Datei unter
   `/data/data/at.d71.kailink/files/kailink/`, nur mit Debug-Build
   `run-as` prüfbar) → Wiederherstellung schlägt fehl, deutsche
   Fehlermeldung, Sitzungsdatei wird gelöscht, Anmeldung bleibt nutzbar.

## MT-3 Raumliste & Chronik öffnen

1. In der Raumliste „Projektkanal Phase 1" antippen.
2. **Erwartung:** Chronik öffnet; zwei Begrüßungsnachrichten erscheinen;
   Kopfbereich zeigt den Raumnamen und eine „Zurück"-Schaltfläche.
3. Zurück zur Raumliste; erneut in denselben Raum: Chronik bleibt
   konsistent (Nachrichten doppelt so oft, keine Duplikate im Inhalt).

## MT-4 Senden

1. Im geöffneten Raum eine Textnachricht eingeben und „Senden" tippen.
2. **Erwartung:** Nachricht erscheint sofort als ausgehende Sprechblase
   (grüner Hintergrund), das Sendefeld wird geleert.
3. **Abbruchfall:** nur Leerzeichen eingeben → „Senden" hat keine Wirkung
   (kein Leerzeichen-Leereintrag in der Chronik).
4. Raumliste öffnen: die gesendete Nachricht erscheint als Vorschau unter
   dem Raumnamen.

## MT-5 Live-Sync-Ereignis

1. In der Chronik „Aktualisieren" ist nur in der Raumliste sichtbar; dort
   „Aktualisieren" tippen.
2. **Erwartung:** keine Fehlermeldung; Raumliste bleibt stabil (Sync läuft
   gegen die Simulation, Start des Live-Sync ändert die Liste nicht
   unerwartet).

## MT-6 Push-Kette (Simulator)

1. Nach Anmeldung in der Raumliste die Push-Zeile beobachten.
2. **Erwartung:** „Push: registriert (UnifiedPush-Simulator)" — der
   simulierte Distributor liefert Endpoint und Registrierung
   (`PushState.REGISTERED`); kein Dialog, kein Google-Dienst.
3. Ohne Anmeldung (frische Installation, vor MT-1): nach Registrierungs-
   versuch wäre der Zustand „kein Distributor"/„fehlgeschlagen" möglich —
   App bleibt in jedem Fall nutzbar.

## MT-7 Rotation/Neustart der Activity

1. Gerät drehen (Activity-Zerstörung erzwingen).
2. **Erwartung:** App stürzt nicht ab; die jeweils sichtbare Oberfläche
   erscheint erneut; in Phase 1 ist der UI-Zustand der Chronik nach dem
   Neuaufbau erneut geladen (Simulation startet neu, Demoräume vorhanden).

## MT-8 Negative Prüfung: keine Netzwerkzugriffe

1. Flugmodus aktivieren, App komplett neu starten, MT-1 bis MT-4
   wiederholen.
2. **Erwartung:** identisches Verhalten wie online (Phase 1 baut bewusst
   keine Verbindungen auf); keine Abstürze.

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
