# f2-unifiedpush – design.md

- `domain/push/PushState`, `domain/push/PushRegistrationTrigger` –
  Nahtstellen (reines Kotlin, von `ui/` benutzbar).
- `data/push/PushController` – Zustandsautomat, JVM-testbar; delegiert an
  `ChannelClient` (Endpoint-Registrierung, Sync).
- `data/push/SimulatedPushTrigger` – Phase-1-Ersatz für den Distributor:
  vorhanden → Endpoint → Registrierung; `simulateIncomingPush()` für den
  Sync-Pfad.
- Phase 2: `UnifiedPushRegistrar` + `KaiLinkPushReceiver`
  (BroadcastReceiver, `exported=false`) + Manifest-Einträge; seit
  2026-09-08 implementiert unter `app/src/main/kotlin/org/box44/kailink/
  data/push/` und in `AppGraph` verdrahtet. Nur Trigger/Empfänger wurden
  getauscht.
