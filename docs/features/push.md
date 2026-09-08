# Feature: Push-Abstraktion (ohne Google/FCM)

## Interaktionsmodell

- Nach Anmeldung zeigt die Raumliste den Push-Zustand; in Phase 1 läuft die
  Kette gegen den dokumentierten Simulator:
  „Push: registriert (UnifiedPush-Simulator)".
- Ein eingehender Push bedeutet immer: Sync anstoßen — Benachrichtigungen
  werden (bewusst, beide Phasen) nicht gerendert.

## Implementierung

- `domain/push/PushState` (`NOT_AVAILABLE`, `READY`, `REGISTERED`,
  `FAILED`) und `PushRegistrationTrigger` sind die Nahtstellen; `ui/`
  sieht nur diese Typen.
- `data/push/PushController` (reines Kotlin, JVM-testbar) ist der
  Zustandsautomat:
  - `onNewEndpoint(url)` → `ChannelClient.registerPushEndpoint(url)`,
    Erfolg → `REGISTERED`, Fehler → `FAILED`,
  - `onMessage()` → `ChannelClient.syncOnce()`,
  - `onDistributorAvailable()`/`onNoDistributor()`/`onRegistrationFailed()`/
    `onUnregistered()` für die Distributor-Lebenszyklen.
- `data/push/SimulatedPushTrigger` (Phase 1): spielt den UnifiedPush-
  Distributor lokal — Distributor vorhanden → Endpoint
  (`https://push.phase1.local/at-d71-kailink/endpoint`) → Registrierung;
  `simulateIncomingPush()` löst den Sync-Pfad aus. Keine Berechtigungen,
  keine Google-Dienste, keine Netzwerkverbindung.

## Phase-2-Ausblick

- `org.unifiedpush.android:connector` (Referenzcode unter
  `app/src/phase2/` vorbereitet): `UnifiedPushRegistrar` als
  `PushRegistrationTrigger`, `KaiLinkPushReceiver` als BroadcastReceiver
  für die Connector-Aktionen (`MESSAGE`, `NEW_ENDPOINT`,
  `REGISTRATION_FAILED`, `UNREGISTERED`, `TEMP_UNAVAILABLE`),
  Manifest-Einträge wieder aufnehmen.
- Der `PushController` bleibt unverändert; nur der Trigger und der
  Empfänger werden getauscht.

## PoC-Grenzen

- Push-Nutzdaten werden nicht entschlüsselt gerendert; Push = Aufwecken +
  Sync (offener Punkt: `NotificationClient`-Verdrahtung in Phase 2).
- Kein Distributor-Auswahl-Dialog; Phase 1 simuliert den Distributor.

## Verifikation

V1: `PushControllerChecks` (4) und `PushChainChecks` (3) — bestanden
2026-09-08, siehe [`verification.md`](verification.md). V4: manuelles
Protokoll, Testfall MT-6 (**nicht beobachtet**, kein Gerät).
