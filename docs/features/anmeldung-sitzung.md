# Feature: Anmeldung & Sitzungswiederherstellung

## Interaktionsmodell

- Nutzer:in trägt Homeserver-URL, Matrix-Konto und Passwort ein und wählt
  "Anmelden".
- Bei vorhandenem gespeichertem Sitzungsdatensatz versucht die App beim Start
  **automatisch** die Wiederherstellung; die Anmeldeoberfläche zeigt dann
  "Sitzung wird wiederhergestellt …" und springt direkt in die Raumliste.
- Fehler (falsches Passwort, unbekannter Homeserver, keine Verbindung) werden
  als deutsche Fehlermeldung im Formular angezeigt.

## Implementierung

- Domäne: `ChannelClient.login(url, user, password)` /
  `ChannelClient.restore(session)`; `SessionStore` als Persistenzvertrag.
- Adapter: `MatrixSdkChannelClient` nutzt
  `ClientBuilder.homeserverUrl(...).sqliteStore(SqliteStoreBuilder(...)).build()`,
  dann `Client.login(username, password, "KaiLink", null)` bzw.
  `Client.restoreSession(sdkSession)`; die SDK-`Session` wird in die
  Domänen-`Session` übersetzt und via `FileSessionStore` gesichert.
- Krypto-Store (SQLite) liegt im App-Files-Verzeichnis → Identität überlebt
  Neustarts; die Wiederherstellung ist damit auch für E2EE belastbar.

## PoC-Grenzen

- Kein Passwort-Speichern, kein SSO/OAuth, kein QR-Login.
- `FileSessionStore` speichert das Token unverschlüsselt im
  App-privaten Verzeichnis (kein Keystore) — bewusst, in verification.md
  als Risiko notiert.
- Beim Logout wird das Gerät **nicht** serverseitig deaktiviert
  (Rust-SDK-Logout ist im PoC nicht verdrahtet).

## Verifikation

Stufen V1–V3 siehe [`verification.md`](verification.md) (Abschnitt Anmeldung).
Geräteprüfung (V4): manuelles Protokoll, Testfälle MT-1/MT-2.
