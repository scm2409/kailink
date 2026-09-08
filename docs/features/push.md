# Feature: Push über UnifiedPush (ohne Google/FCM)

## Interaktionsmodell

- Nach Anmeldung versucht KaiLink, einen UnifiedPush-Distributor zu finden
  (`UnifiedPush.tryPickDistributor`). Ist keiner installiert, zeigt die
  Raumliste den Zustand „Push: kein Distributor" — die App funktioniert
  trotzdem (nur ohne Hintergrund-Push).
- Ist ein Distributor vorhanden, registriert sich KaiLink; der neue Endpoint
  wird automatisch als Matrix-Pusher am Homeserver gemeldet.
- Push-Nachrichten des Distributors empfangt `KaiLinkPushReceiver`
  (MessagingReceiver des Connectors) und stößt einen Sync an.

## Implementierung

- `org.unifiedpush.android:connector:3.3.5` (echte, lokal verifizierte
  Abhängigkeit — kein Stub).
- `data/push/UnifiedPushRegistrar` (Android): `tryPickDistributor` →
  `UnifiedPush.register(context, instance="")`; Zustand als
  `StateFlow<PushState>`.
- `data/push/PushController` (JVM-testbar): entkoppelt Empfänger-Callbacks vom
  `ChannelClient` — `onNewEndpoint(url)` → `client.registerPushEndpoint(url)`
  (`setPusher` mit `PusherKind.Http(HttpPusherData(url, EVENT_ID_ONLY, null))`),
  `onMessage()` → `client.syncOnce()`.
- `KaiLinkPushReceiver` (BroadcastReceiver, `exported=false`) filtert die
  Connector-Aktionen (`MESSAGE`, `NEW_ENDPOINT`, `REGISTRATION_FAILED`,
  `UNREGISTERED`, `TEMP_UNAVAILABLE`) und delegiert an den `PushController`.

## PoC-Grenzen

- Push-Nutzdaten (verschlüsselte Push-Gateway-Payload) werden **nicht** zur
  Benachrichtigung entschlüsselt; Push = Aufwecken + Sync. Der Nutzer sieht
  Benachrichtigungen erst beim Öffnen der App bzw. über den Live-Sync.
  (Offener Punkt: `NotificationClient`-Verdrahtung, siehe verification.md.)
- Kein Distributor-Auswahl-Dialog; erster gefundener Distributor wird benutzt
  (LinkActivity des Connectors regelt die Auswahl, falls nötig).

## Verifikation

Stufen V1–V3 siehe [`verification.md`](verification.md) (Abschnitt Push);
`PushController`-Logik ist JVM-getestet. Geräteprüfung (V4): manuelles
Protokoll, Testfälle MT-7/MT-8 (Distributor ntfy + Homeserver-Pusher).
