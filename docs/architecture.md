# Architektur

## 1. Interaktionsmodell (Phase-1-Szenario)

1. Nutzer:in öffnet KaiLink → Anmeldeoberfläche (Homeserver-URL, Konto, Passwort).
2. Nach erfolgreichem Login: Raumliste; verschlüsselte Räume sind gekennzeichnet.
3. Öffnen eines Raums → Chronik (Timeline); Nachrichten senden über Textfeld.
4. Push-Kette: Zustandsanzeige (Distributor → Endpoint → registriert); ein
   simulierter eingehender Push stößt einen Sync an.
5. Sprachein-/ausgabe sind bewusst **nicht** interaktiv implementiert; die
   Nahtstellen existieren und sind dokumentiert (siehe
   [`features/sprache.md`](features/sprache.md)).

In Phase 1 läuft der Kanaldienst als In-Memory-Simulation — es werden **keine
Netzwerkverbindungen** aufgebaut. Sämtliche Schnittstellen entsprechen bereits
der Phase-2-Geometrie.

## 2. Schichten

```
┌──────────────────────────────────────────────────────┐
│ ui/  (Framework-Views + ViewModels, StateFlow)       │  ← kennt nur domain
├──────────────────────────────────────────────────────┤
│ domain/  (Session, Room, Message, ChannelClient,     │
│          SessionStore, TimelineReducer,              │
│          Push-Nahtstellen, Speech-Nahtstellen)       │  ← reines Kotlin, JVM-testbar
├──────────────────────────────────────────────────────┤
│ data/  (InMemoryChannelClient, FileSessionStore,     │
│         PushController, SimulatedPushTrigger)        │  ← Android nur am Rand
├──────────────────────────────────────────────────────┤
│ Phase 2: MatrixSdkChannelClient (matrix-rust-sdk),   │
│          UnifiedPush-Connector + Receiver, Compose   │
└──────────────────────────────────────────────────────┘
```

- **Abhängigkeitsregel:** Pfeile zeigen nur nach unten. `ui` und `domain`
  kennen keine Android- und keine Kanal-SDK-Typen; `data` übersetzt
  kanalseitige Zustände in Domänenereignisse (`ChannelEvent`) bzw.
  Chronik-Patches (`TimelinePatch`).
- **Verdrahtung:** `KaiLinkApp` erzeugt `AppGraph` (manuelle DI, kein
  Hilt/Dagger — bewusst, um die Kompilierungsfläche des PoC klein zu halten).
- **ViewModels** sind reine Kotlin-Klassen mit injizierbarem
  `CoroutineScope` und `StateFlow<UiState>` — dadurch JVM-testbar und ohne
  AndroidX-Lifecycle-Abhängigkeit.

## 3. Datenfluss (Phase 1)

```
UI-Aktion (Button/Textfeld)
   → ViewModel (StateFlow<UiState>)
   → ChannelClient (InMemoryChannelClient)
   → ChannelEvent (RoomsUpdated | TimelineUpdated | SyncStateChanged | ClientError) ── SharedFlow
   → ViewModel aktualisiert UiState
   → Activity rendert (Adapter/TextViews)
```

Senden: `TimelineViewModel.send()` → `ChannelClient.sendMessage()` →
Ablage im In-Memory-Speicher + `TimelineUpdated`-Ereignis → Chronik rendert
die ausgehende Nachricht.

Chronik-Patches: `TimelineReducer.apply(messages, patches)` bleibt die reine
Domänenfunktion; Phase 2 füttert sie mit SDK-`TimelineDiff`-Übersetzungen,
Phase 1 demonstriert sie über die JVM-Prüfungen.

## 4. Push-Kette (ohne Google)

```
Phase 1 (Simulation):                Phase 2 (echt):
SimulatedPushTrigger                 UnifiedPush-Distributor (z. B. ntfy)
   │  tryRegister()                     │  Broadcast (Connector-Aktionen)
   ▼                                    ▼
PushController  ──▶ ① onNewEndpoint → ChannelClient.registerPushEndpoint(url)
                ──▶ ② onMessage()    → ChannelClient.syncOnce()
   │
   └─ StateFlow<PushState> → Raumliste ("Push: registriert (UnifiedPush-Simulator)")
```

- Der Zustandsautomat (`PushController`) ist identisch in beiden Phasen und
  JVM-getestet (`pushControllerChecks`, `pushChainChecks`).
- Phase 1 simuliert Distributor, Endpoint und Push-Zustellung lokal
  (`SimulatedPushTrigger`); kein Benachrichtigungs-Rendering (dokumentierte
  PoC-Grenze, siehe [`features/push.md`](features/push.md)).

## 5. Persistenz

- **Domänensitzung:** `FileSessionStore` (`session.properties`,
  `java.util.Properties`) im App-Files-Verzeichnis. Phase-1-bewusst
  unverschlüsselt auf dem Gerät; für Produktion: EncryptedFile/Keystore
  (offen, siehe verification.md).
- **Krypto-Store (Phase 2):** SQLite über das Rust-SDK in
  `context.filesDir/matrix/store` — überlebt Neustarts (Voraussetzung E2EE).

## 6. Verifizierte Abhängigkeiten

**Phase-1-Stand (Offline, 2026-09-08):** Gradle 9.1.0, AGP 8.13.2,
Kotlin 2.2.21, kotlinx-coroutines-android 1.7.3, Android platform 36,
build-tools 35.0.0 — alle im lokalen Gradle-Cache vorhanden und durch einen
erfolgreichen Offline-Build belegt (G3/G8; Details in
[`features/verification.md`](features/verification.md)).

**Phase-2-Stand (online, 2026-09-08):** zusätzlich eingebunden und durch
`./gradlew testDebugUnitTest assembleDebug` (BUILD SUCCESSFUL) belegt:

| Artefakt | Version | Rolle |
| --- | --- | --- |
| org.matrix.rustcomponents:sdk-android | 26.09.08 | echter Matrix-Kanal (`MatrixSdkChannelClient`), Rust-`libmatrix_sdk_ffi.so` im APK |
| org.unifiedpush.android:connector | 3.3.5 | UnifiedPush-Registrierung + Receiver |
| junit:junit | 4.13.2 | JVM-Prüfungen als gewöhnliche JUnit-Aufgabe |
| kotlinx-coroutines-test | 1.7.3 | Test-Klassepfad |

Weiterhin nicht enthalten: Jetpack Compose (Screens bleiben Framework-Views;
ViewModels sind davon unberührt).

## 7. Bewusste PoC-Grenzen (Stand 2026-09-08)

1. ~~Kanaldienst ist eine In-Memory-Simulation~~ — seit Phase 2 verdrahtet
   `AppGraph` den echten `MatrixSdkChannelClient`; die In-Memory-Variante
   bleibt als JVM-Referenz für Prüfungen erhalten. Laufzeit gegen einen
   echten Homeserver ist noch nicht beobachtet (kein Gerät/Zugang).
2. E2EE: SQLite-Krypto-Store ist konfiguriert, UTD-Erkennung im Adapter
   vorhanden; vollständige Verifizierung (Geräte, Verifizierungsabläufe)
   offen (siehe [`features/nachrichten-e2ee.md`](features/nachrichten-e2ee.md)).
3. Persistenz der Domänensitzung ohne Android-Keystore.
4. Push: UnifiedPush-Registrar/Receiver sind verdrahtet; Benachrichtigungen
   werden nicht gerendert; Laufzeit gegen echten Distributor nicht beobachtet.
5. Kein Hintergrund-Dienst (WorkManager) — Sync im Vordergrund bzw. per
   Push ausgelöst.
6. Mehrere installierte UnifiedPush-Distributoren ohne Nutzerwahl: der
   Registrar wählt deterministisch den ersten gemeldeten (dokumentierte
   Grenze).
7. Sprach-Nahtstellen haben nur No-Op-Implementierungen.

## 8. Migrationspfad Phase 2

1. `InMemoryChannelClient` → `MatrixSdkChannelClient`; `AppGraph` ist die
   einzige Änderungsstelle. *(erledigt 2026-09-08, inkl. Umzug der Pakete
   nach `org.box44.kailink`)*
2. `SimulatedPushTrigger` → `UnifiedPushRegistrar` + `KaiLinkPushReceiver`
   (Manifest-Receiver-Einträge wieder aufnehmen). *(erledigt 2026-09-08)*
3. Framework-Views → Jetpack Compose (Screens 1:1 auf `@Composable` abbilden;
   ViewModels bleiben unverändert). *(offen)*
4. JUnit 4/5 + `kotlinx-coroutines-test` einbinden und die Prüfungen von
   `phase1Checks` auf reguläre `testDebugUnitTest`-Tests umstellen.
   *(erledigt 2026-09-08: JUnit 4, `AllChecksTest`)*
5. `INTERNET`-Nutzung: echte Homeserver-Kommunikation; `POST_NOTIFICATIONS`
   für Push-Benachrichtigungen. *(offen; `INTERNET` ist deklariert,
   Laufzeitnachweis steht aus)*
