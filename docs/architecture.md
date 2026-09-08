# Architektur

## 1. Interaktionsmodell (PoC-Szenario)

1. Nutzer:in öffnet KaiLink → Anmeldeoberfläche (Homeserver-URL, Konto, Passwort).
2. Nach erfolgreichem Login: Raumliste; gesperrte/verschlüsselte Räume sind
   gekennzeichnet.
3. Öffnen eines Raums → Chronik (Timeline); Nachrichten senden über Textfeld.
4. Empfangene Nachrichten erscheinen live (Sync) bzw. per Push ausgelöst.
5. Sprachein-/ausgabe sind bewusst **nicht** interaktiv implementiert; die
   Nahtstellen existieren und sind dokumentiert (siehe
   [`features/sprache.md`](features/sprache.md)).

## 2. Schichten

```
┌───────────────────────────────────────────────┐
│ ui/  (Jetpack Compose, ViewModels)            │  ← kennt nur domain
├───────────────────────────────────────────────┤
│ domain/  (Session, Room, Message,             │
│          ChannelClient, SessionStore,         │
│          TimelineReducer, Speech-Nahtstellen) │  ← reines Kotlin, JVM-testbar
├───────────────────────────────────────────────┤
│ data/  (MatrixSdkChannelClient,               │
│         FileSessionStore, UnifiedPushAdapter, │
│         KaiLinkPushReceiver)                  │  ← Android + echte SDKs
├───────────────────────────────────────────────┤
│ Org.matrix.rustcomponents.sdk-android (Rust)  │
│ org.unifiedpush.android:connector             │
└───────────────────────────────────────────────┘
```

- **Abhängigkeitsregel:** Pfeile zeigen nur nach unten. `ui` und `domain`
  kennen die Rust-SDK-Typen nicht; `data` übersetzt SDK-Zwischenstände in
  Domänenereignisse (`ChannelEvent`) bzw. Chronik-Zwischenstände
  (`TimelinePatch`).
- **Verdrahtung:** `KaiLinkApp` erzeugt `AppGraph` (manuelle DI, kein
  Hilt/Dagger — bewusst, um die Kompilierungsfläche des PoC klein zu halten).

## 3. Datenfluss

```
Rust-SDK ──(Listener/Suspend)──▶ MatrixSdkChannelClient
        ──▶ ChannelEvent (RoomsUpdated | TimelineUpdated | …) ── SharedFlow
        ──▶ ViewModel (StateFlow) ──▶ Compose
```

Senden: `TimelineViewModel.sendMessage` → `ChannelClient.sendMessage` →
`Timeline.createMessageContent + send` (Send-Queue des Rust-SDK übernimmt
Wiederholung/E2EE).

Chronik: SDK-`TimelineDiff`-Folge wird in `TimelinePatch`-Sequenz übersetzt
und von der reinen Domänenfunktion `TimelineReducer.apply` auf
`List<Message>` reduziert (deterministisch, JVM-getestet).

## 4. Push-Kette (ohne Google)

```
UnifiedPush-Distributor (z. B. ntfy) ──Broadcast──▶ KaiLinkPushReceiver
   │  (MessagingReceiver des UnifiedPush-Connectors)
   ▼
PushController  ──▶ ① Endpoint als Matrix-Pusher registrieren (setPusher)
                ──▶ ② bei Push-Nachricht: syncOnce() anstoßen
```

- Registrierung: `UnifiedPush.tryPickDistributor(...)` + `register(...)`;
  der Zustand (`PushState`) wird der Anmeldungsoberfläche angezeigt.
- Der Endpoint wird als HTTP-Pusher an den Homeserver gemeldet
  (`PusherKind.Http(HttpPusherData(url, PushFormat.EVENT_ID_ONLY, …))`).
- PoC-Grenze (dokumentiert): Entschlüsselte Push-Nutzdaten werden **nicht**
  in Benachrichtigungen gerendert; Push dient dem Aufwecken/Sync. Siehe
  [`features/push.md`](features/push.md).

## 5. Persistenz

- **Matrix-Sitzung + Krypto-Store:** SQLite über das Rust-SDK
  (`SqliteStoreBuilder`) in `context.filesDir/matrix/store` — dadurch
  überleben Identitäts-/Megolm-Schlüssel Neustarts (Voraussetzung für E2EE).
- **Domänensitzung:** `FileSessionStore` (`session.properties`,
  `java.util.Properties`) im App-Files-Verzeichnis. PoC-Bewusst
  unverschlüsselt auf dem Gerät; für Produktion: EncryptedFile/Keystore
  (offen, siehe verification.md).

## 6. Verifizierte Abhängigkeiten (Stand: 2026-09-08)

Alle Versionen und die im Code benutzten API-Signaturen wurden lokal geprüft
(Maven-Metadaten + Entpacken der AARs + `javap`). Das erfüllt Grundsatz G3
und macht Stubs entbehrlich — **keine** der Kernabhängigkeiten ist ein Stub.

| Artefakt | Version | Quelle | Benutzte, verifizierte API |
| --- | --- | --- | --- |
| `org.matrix.rustcomponents:sdk-android` | `26.09.08` | Maven Central | `ClientBuilder.homeserverUrl/sqliteStore/build`, `ClientInterface.login/restoreSession/session/rooms/syncOnceV2/syncService/setPusher`, `Room.id/displayName/isEncrypted/timeline`, `Timeline.addListener/createMessageContent/send`, `TimelineListener.onUpdate(List<TimelineDiff>)`, `TimelineDiff.{Append,Reset,Insert,Set,PushBack,PushFront,Remove,PopBack,PopFront,Clear,Truncate}`, `EventTimelineItem.{getSender,getContent,getTimestamp,getEventOrTransactionId,getLocalSendState}`, `TimelineItemContent$MsgLike`, `MsgLikeContent.getKind`, `MsgLikeKind$Message/UnableToDecrypt`, `MessageContent.{getMsgType,getBody}`, `MessageType$Text(TextMessageContent)`, `TextMessageContent(String, FormattedBody?)`, `Session(7×)`, `SqliteStoreBuilder(String, String)`, `PusherIdentifiers(pushkey, appId)`, `PusherKind$Http(HttpPusherData)`, `HttpPusherData(url, PushFormat, defaultPayload)`, `PushFormat.EVENT_ID_ONLY`, `SyncServiceBuilder.finish`, `SyncService.{start,stop}` |
| `org.unifiedpush.android:connector` | `3.3.5` | Maven Central | `MessagingReceiver.{onMessage,onNewEndpoint,onRegistrationFailed,onUnregistered,onTempUnavailable}`, `UnifiedPush.{tryPickDistributor,register,unregister,getDistributors}`, `PushMessage`, `PushEndpoint`, Konstanten `ACTION_MESSAGE/NEW_ENDPOINT/REGISTRATION_FAILED/UNREGISTERED/TEMP_UNAVAILABLE` |
| Jetpack Compose BOM | `2025.11.00` | Google Maven | `material3`, `foundation`, `ui` |
| AGP | `8.13.2` | Google Maven | `com.android.application` |
| Kotlin / Compose-Plugin | `2.4.20` | Maven Central | Kotlin 2.x-Compose-Compiler-Plugin |
| Gradle | `8.14.3` (Wrapper) | services.gradle.org | Wrapper-Distribution |
| `kotlinx-coroutines` | `1.11.0` | Maven Central | core/android/test |
| `androidx.core:core-ktx` | `1.17.0` | Google Maven | – |
| `androidx.activity:activity-compose` | `1.12.4` | Google Maven | `ComponentActivity` |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | `2.9.4` | Google Maven | `viewModel()` |
| `junit:junit` | `4.13.2` | Maven Central | JVM-Tests |

**Hinweis zur Versionierung:** Die jeweils neuesten androidx-Versionen
(core-ktx 1.19.0, activity-compose 1.13.0, lifecycle 2.11.0, ui 1.12.0)
verlangen AGP ≥ 9.1 und compileSdk ≥ 37 (im ersten Build-Versuch am
2026-09-08 beobachtet und dokumentiert). KaiLink bleibt bei dem mit AGP
8.13.2 + compileSdk 36 verträglichen Stand (siehe oben); ein Upgrade auf
AGP 9.x ist ein eigener Schritt, kein PoC-Ziel.

## 7. Bewusste PoC-Grenzen

1. Persistenz der Domänensitzung ohne Android-Keystore (G3-Grenze dokumentiert).
2. Push-Inhalte werden nicht entschlüsselt gerendert (s. o.).
3. Kein Background-Sync-Dienst (WorkManager) — Sync nur im App-Vordergrund
   und durch Push ausgelöst.
4. Kein Room-List-Live-Listener des SDK (`RoomListService`) — Raumliste wird
   aus `Client.rooms()` nach Sync aktualisiert (einfacher, für PoC ausreichend).
5. Sprach-Nahtstellen haben nur No-Op-Implementierungen.
