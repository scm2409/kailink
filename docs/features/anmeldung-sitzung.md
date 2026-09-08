# Feature: Anmeldung & Sitzungswiederherstellung

## Interaktionsmodell

- Nutzer:in trägt Homeserver-URL, Konto und Passwort ein und wählt
  "Anmelden".
- Bei vorhandenem gespeichertem Sitzungsdatensatz versucht die App beim Start
  **automatisch** die Wiederherstellung; die Anmeldeoberfläche zeigt dann
  "Sitzung wird wiederhergestellt …" und springt direkt in die Raumliste.
- Fehler (leere Felder, fehlgeschlagene Wiederherstellung) werden als
  deutsche Fehlermeldung im Formular angezeigt; eine nicht mehr
  wiederherstellbare Sitzung wird gelöscht.

## Implementierung (Phase 1)

- Domäne: `ChannelClient.login(url, user, password)` /
  `ChannelClient.restore(session)`; `SessionStore` als Persistenzvertrag.
- Adapter: `InMemoryChannelClient` (Phase-1-Simulation) erzeugt aus
  nicht-leeren Anmeldedaten eine Session
  (`@<benutzer>:phase1.local`, Zufalls-Token) und sichert sie via
  `FileSessionStore`. Es werden **keine** Netzwerkverbindungen aufgebaut.
- Persistenz: `FileSessionStore` (`session.properties`, `java.util.Properties`)
  im App-Files-Verzeichnis.

## Phase-2-Ausblick

- `MatrixSdkChannelClient` (Referenz unter `app/src/phase2/`) ersetzt die
  Simulation: `ClientBuilder.homeserverUrl(...).sqliteStore(...).build()`,
  `Client.login(...)` bzw. `Client.restoreSession(...)`.
- Krypto-Store (SQLite) überlebt Neustarts → Wiederherstellung auch für
  E2EE belastbar.

## PoC-Grenzen

- Kein Passwort-Speichern, kein SSO/OAuth, kein QR-Login.
- `FileSessionStore` speichert das Token unverschlüsselt im
  App-privaten Verzeichnis (kein Keystore) — bewusst, in verification.md
  als Risiko notiert.
- Phase 1: Anmeldung prüft nur die Felder (Simulation), keine echten Konten.

## Verifikation

V1: `LoginViewModelChecks` (5 Prüfungen) und
`InMemoryChannelClientChecks` (6 Prüfungen) — bestanden 2026-09-08, siehe
[`verification.md`](verification.md). V2/V3: Offline-Build + Badging. V4:
manuelles Protokoll, Testfälle MT-1/MT-2 (**nicht beobachtet**, kein Gerät).
