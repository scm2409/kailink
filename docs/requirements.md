# Anforderungen

KaiLink Phase 1 bietet Matrix-Login und Sitzungswiederherstellung, Raumliste,
Chronik, Senden und Empfang verschlüsselter Nachrichten sowie UnifiedPush mit
konfigurierbarem ntfy-Gateway. Die Sprach-Nahtstellen bleiben ohne STT/TTS;
Bluetooth und Videoanrufe sind außerhalb des Umfangs.

JVM-prüfbare Pfade werden mit JUnit ausgeführt. Homeserver-, E2EE-,
UnifiedPush- und Benachrichtigungsverhalten werden über das lokale E2E-Skript
und auf GrapheneOS geprüft. Reale matrix.org-Zugangsdaten werden niemals im
Repository verwendet.
