# f2-unifiedpush – verification.md

- V1: `PushControllerChecks` (4) und `PushChainChecks` (3) — bestanden
  2026-09-08, 39/39 gesamt, siehe `../verification.md`.
- V2/V3: Offline-Build bestanden; Manifest ohne Receiver (Phase 1 bewusst).
- V4: MT-6 — **nicht beobachtet** (kein Gerät).

Phase 2 (2026-09-08, online — siehe `../verification.md`):

- V1: `./gradlew testDebugUnitTest` (JUnit) → 39/39 (Push-Kette weiterhin
  JVM-geprüft; `PushChainChecks` jetzt mit Endpoint
  `https://push.phase1.local/org-box44-kailink/endpoint`).
- V2/V3: `./gradlew testDebugUnitTest assembleDebug` → `BUILD SUCCESSFUL`;
  Manifest enthält `.data.push.KaiLinkPushReceiver` (`exported=false`) mit
  `MESSAGE`, `NEW_ENDPOINT`, `REGISTRATION_FAILED`, `UNREGISTERED`;
  `UnifiedPushRegistrar` (connector 3.3.5) ist `PushRegistrationTrigger`
  in `AppGraph`.
- V4: Laufzeit gegen echten UnifiedPush-Distributor — **nicht beobachtet**
  (kein Gerät).
