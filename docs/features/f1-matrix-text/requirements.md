# f1-matrix-text – requirements.md

Funktionsanforderungen für Textnachrichten über einen Matrix-Kanal
(Phase 1: erfüllt gegen die In-Memory-Simulation; Phase 2: gegen das echte
matrix-rust-sdk, siehe `app/src/phase2/`).

1. Anmeldung mit Homeserver-URL, Konto, Passwort; deutsche Fehlermeldungen
   bei leeren Feldern und Fehlschlägen.
2. Automatische Sitzungswiederherstellung beim Start aus dem `SessionStore`;
   nicht wiederherstellbare Sitzungen werden gelöscht.
3. Raumliste mit Anzeigename, 🔒-Badge (verschlüsselt) und Vorschau der
   letzten Nachricht; Aktualisierung per Sync und Ereignis.
4. Chronik je Raum mit ein-/ausgehender Unterscheidung; Senden mit
   Trimmen, Leerfeld-Schutz und Entwurf-Wiederherstellung bei Fehlern.
5. Alle UI-Texte und Fehlermeldungen deutsch (G9).
