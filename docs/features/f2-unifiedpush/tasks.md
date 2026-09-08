# f2-unifiedpush – tasks.md

Erledigt (Phase 1):

- [x] `PushState`/`PushRegistrationTrigger`-Nahtstellen in der Domäne.
- [x] `PushController`-Zustandsautomat (JVM-geprüft).
- [x] `SimulatedPushTrigger` inkl. Push→Sync-Pfad (JVM-geprüft).
- [x] Anzeige des Push-Zustands in der Raumliste.

Offen (Phase 2):

- [x] UnifiedPush-Connector einbinden (Trigger + Receiver + Manifest).
      *(2026-09-08: `UnifiedPushRegistrar`, `KaiLinkPushReceiver`,
      Manifest-Eintrag; kompiliert, Tests 39/39.)*
- [ ] NotificationClient-Verdrahtung (Benachrichtigungen) – optional.
