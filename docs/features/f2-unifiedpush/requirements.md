# f2-unifiedpush – requirements.md

Anforderungen an die Push-Kette ohne Google/FCM (Phase 1: Zustandskette
echt, Distributor simuliert; Phase 2: UnifiedPush-Connector).

1. Kein FCM, keine Play Services (G1).
2. Zustandsanzeige in der Raumliste: kein Distributor / Registrierung läuft /
   registriert / fehlgeschlagen (deutsch).
3. Endpoint-Zustellung → Registrierung als Pusher am Kanal
   (`ChannelClient.registerPushEndpoint`).
4. Eingehender Push → `syncOnce()` (Aufwecken + Sync, kein
   Benachrichtigungs-Rendering).
5. Registrierungsverlust/Temp-Unavailable führen in einen definierten
   Zustand; die App bleibt ohne Push voll nutzbar.
