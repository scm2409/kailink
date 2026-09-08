package at.d71.kailink.data.matrix

import at.d71.kailink.domain.ChannelClient
import at.d71.kailink.domain.ChannelEvent
import at.d71.kailink.domain.ChannelException
import at.d71.kailink.domain.SessionStore
import at.d71.kailink.domain.TimelinePatch
import at.d71.kailink.domain.TimelineReducer
import at.d71.kailink.domain.model.DeliveryState
import at.d71.kailink.domain.model.Message
import at.d71.kailink.domain.model.MessageDirection
import at.d71.kailink.domain.model.Room
import at.d71.kailink.domain.model.Session
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.HttpPusherData
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.PushFormat
import org.matrix.rustcomponents.sdk.PusherIdentifiers
import org.matrix.rustcomponents.sdk.PusherKind
import org.matrix.rustcomponents.sdk.Room as SdkRoom
import org.matrix.rustcomponents.sdk.Session as SdkSession
import org.matrix.rustcomponents.sdk.SlidingSyncVersion
import org.matrix.rustcomponents.sdk.SqliteStoreBuilder
import org.matrix.rustcomponents.sdk.SyncService
import org.matrix.rustcomponents.sdk.SyncSettingsV2
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.TextMessageContent
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.TimelineListener

/**
 * Adapter auf das echte matrix-rust-sdk (org.matrix.rustcomponents:sdk-android).
 *
 * [PHASE 2 — wird in Phase 1 NICHT kompiliert.] Diese Datei liegt außerhalb
 * des kompilierten Quellbaums (app/src/phase2) als Referenzimplementierung;
 * die SDK-Artefakte sind im Offline-Cache dieser Umgebung nicht verfügbar.
 *
 * Übersetzt SDK-Aufrufe/Listener in die Domänennahtstelle [ChannelClient]
 * bzw. in [ChannelEvent]/[TimelinePatch]. Alle von der SDK-Oberfläche
 * abweichenden Verhaltensweisen sind in docs/architecture.md dokumentiert.
 */
class MatrixSdkChannelClient(
    private val sessionStore: SessionStore,
    private val storeDir: File,
    private val cacheDir: File,
    private val scope: CoroutineScope,
    private val appId: String = DEFAULT_APP_ID,
    private val onLog: (String) -> Unit = {},
) : ChannelClient {

    private val _events = MutableSharedFlow<ChannelEvent>(
        extraBufferCapacity = 128,
    )
    override val events: Flow<ChannelEvent> = _events.asSharedFlow()

    @Volatile
    override var activeSession: Session? = null
        private set

    @Volatile
    private var client: Client? = null

    @Volatile
    private var syncService: SyncService? = null

    private val timelines = ConcurrentHashMap<String, TimelineSubscription>()

    private class TimelineSubscription internal constructor(
        val timeline: Timeline,
        val listener: TimelineListener,
        val handle: TaskHandle?,
    )

    // ------------------------------------------------------------------ Login/Restore

    override suspend fun login(homeserverUrl: String, username: String, password: String): Session {
        closeExisting()
        val c = buildClient(homeserverUrl.trim())
        try {
            c.login(username.trim(), password, DEVICE_NAME, null)
        } catch (t: Throwable) {
            runCatching { c.close() }
            throw ChannelException("Anmeldung fehlgeschlagen: ${t.message ?: "unbekannter Fehler"}", t)
        }
        return adoptClient(c, source = "Login")
    }

    override suspend fun restore(session: Session): Session {
        closeExisting()
        val c = buildClient(session.homeserverUrl)
        try {
            c.restoreSession(toSdkSession(session))
        } catch (t: Throwable) {
            runCatching { c.close() }
            sessionStore.clear()
            throw ChannelException("Sitzung konnte nicht wiederhergestellt werden: ${t.message ?: "unbekannter Fehler"}", t)
        }
        return adoptClient(c, source = "Wiederherstellung")
    }

    private suspend fun adoptClient(c: Client, source: String): Session {
        val sdkSession = c.session()
        val session = toDomainSession(sdkSession)
        sessionStore.save(session)
        activeSession = session
        client = c
        onLog("$source erfolgreich: ${session.userId} @ ${session.homeserverUrl}")
        emitRooms()
        return session
    }

    private suspend fun buildClient(homeserverUrl: String): Client {
        storeDir.mkdirs()
        cacheDir.mkdirs()
        val builder = ClientBuilder()
        builder.homeserverUrl(homeserverUrl)
        builder.sqliteStore(
            SqliteStoreBuilder(
                storeDir.resolve("state.sqlite").absolutePath,
                cacheDir.absolutePath,
            ),
        )
        return try {
            builder.build()
        } catch (t: Throwable) {
            throw ChannelException("Verbindung zum Homeserver nicht möglich: ${t.message ?: "unbekannter Fehler"}", t)
        }
    }

    // ------------------------------------------------------------------ Sync

    override suspend fun syncOnce() {
        val c = client ?: throw ChannelException("Keine aktive Sitzung")
        c.syncOnceV2(SyncSettingsV2())
        emitRooms()
    }

    override suspend fun startLiveSync() {
        if (syncService != null) return
        val c = client ?: throw ChannelException("Keine aktive Sitzung")
        val service = try {
            c.syncService().finish()
        } catch (t: Throwable) {
            throw ChannelException("Live-Sync konnte nicht gestartet werden: ${t.message ?: "unbekannter Fehler"}", t)
        }
        service.start()
        syncService = service
        _events.emit(ChannelEvent.SyncStateChanged(true))
        onLog("Live-Sync gestartet")
    }

    override suspend fun stopLiveSync() {
        val service = syncService ?: return
        syncService = null
        runCatching { service.stop() }
        _events.emit(ChannelEvent.SyncStateChanged(false))
        onLog("Live-Sync gestoppt")
    }

    // ------------------------------------------------------------------ Räume & Chronik

    override suspend fun rooms(): List<Room> {
        val c = client ?: throw ChannelException("Keine aktive Sitzung")
        return c.rooms().map { sdkRoom ->
            Room(
                id = sdkRoom.id(),
                displayName = sdkRoom.displayName().orEmpty().ifBlank { "(unbenannter Raum)" },
                isEncrypted = runCatching { sdkRoom.isEncrypted() }.getOrNull() == true,
                lastMessage = null,
            )
        }.also {
            onLog("Räume geladen: ${it.size}")
        }
    }

    override suspend fun openTimeline(roomId: String) {
        val c = client ?: throw ChannelException("Keine aktive Sitzung")
        if (timelines.containsKey(roomId)) return
        val room: SdkRoom = c.getRoom(roomId)
            ?: throw ChannelException("Raum nicht gefunden: $roomId")
        val timeline = room.timeline()
        val messages = mutableListOf<Message>()
        val listener = object : TimelineListener {
            override fun onUpdate(diffs: List<TimelineDiff>) {
                scope.launch(Dispatchers.Default) {
                    try {
                        val patches = diffs.mapNotNull(::toPatch)
                        val updated = TimelineReducer.apply(messages, patches)
                        messages.clear()
                        messages.addAll(updated)
                        _events.emit(ChannelEvent.TimelineUpdated(roomId, messages.toList()))
                    } catch (t: Throwable) {
                        onLog("Chronik-Update fehlgeschlagen: ${t.message}")
                    }
                }
            }
        }
        val handle = try {
            timeline.addListener(listener)
        } catch (t: Throwable) {
            runCatching { timeline.close() }
            throw ChannelException("Chronik konnte nicht geöffnet werden: ${t.message ?: "unbekannter Fehler"}", t)
        }
        timelines[roomId] = TimelineSubscription(timeline, listener, handle)
        onLog("Chronik abonniert: $roomId")
    }

    override suspend fun closeTimeline(roomId: String) {
        timelines.remove(roomId)?.let { subscription ->
            runCatching { subscription.handle?.close() }
            runCatching { subscription.timeline.close() }
            onLog("Chronik abbestellt: $roomId")
        }
    }

    override suspend fun sendMessage(roomId: String, body: String) {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) throw ChannelException("Leere Nachricht")
        val subscription = timelines[roomId]
        val timeline: Timeline = subscription?.timeline ?: run {
            openTimeline(roomId)
            timelines[roomId]?.timeline ?: throw ChannelException("Chronik nicht geöffnet: $roomId")
        }
        try {
            val content = timeline.createMessageContent(MessageType.Text(TextMessageContent(trimmed, null)))
                ?: throw ChannelException("Nachrichteninhalt konnte nicht erzeugt werden")
            timeline.send(content)
            onLog("Nachricht an Send-Queue übergeben: $roomId")
        } catch (t: Throwable) {
            throw ChannelException("Senden fehlgeschlagen: ${t.message ?: "unbekannter Fehler"}", t)
        }
    }

    // ------------------------------------------------------------------ Push

    override suspend fun registerPushEndpoint(endpointUrl: String) {
        val c = client ?: throw ChannelException("Keine aktive Sitzung")
        try {
            val identifiers = PusherIdentifiers(pushkey = endpointUrl, appId = appId)
            val data = HttpPusherData(endpointUrl, PushFormat.EVENT_ID_ONLY, null)
            c.setPusher(
                identifiers,
                PusherKind.Http(data),
                APP_DISPLAY_NAME,
                DEVICE_DISPLAY_NAME,
                PROFILE_TAG,
                LANG,
                true,
            )
            onLog("UnifiedPush-Endpoint als Matrix-Pusher registriert")
        } catch (t: Throwable) {
            throw ChannelException("Pusher-Registrierung fehlgeschlagen: ${t.message ?: "unbekannter Fehler"}", t)
        }
    }

    // ------------------------------------------------------------------ Lebenszyklus

    override suspend fun logout() {
        sessionStore.clear()
        closeExisting()
        onLog("Lokal abgemeldet")
    }

    override fun dispose() {
        closeExisting()
    }

    private fun closeExisting() {
        timelines.values.forEach { subscription ->
            runCatching { subscription.handle?.close() }
            runCatching { subscription.timeline.close() }
        }
        timelines.clear()
        syncService?.let { service ->
            runCatching { service.close() }
        }
        syncService = null
        client?.let { runCatching { it.close() } }
        client = null
        activeSession = null
    }

    private suspend fun emitRooms() {
        runCatching { rooms() }
            .onSuccess { _events.emit(ChannelEvent.RoomsUpdated(it)) }
            .onFailure { _events.emit(ChannelEvent.ClientError(it.message ?: "Räume konnten nicht geladen werden")) }
    }

    // ------------------------------------------------------------------ Abbildung SDK → Domäne

    private fun toDomainSession(sdk: SdkSession): Session = Session(
        userId = sdk.userId,
        deviceId = sdk.deviceId,
        homeserverUrl = sdk.homeserverUrl,
        accessToken = sdk.accessToken,
        refreshToken = sdk.refreshToken,
    )

    private fun toSdkSession(session: Session): SdkSession = SdkSession(
        accessToken = session.accessToken,
        refreshToken = session.refreshToken,
        userId = session.userId,
        deviceId = session.deviceId,
        homeserverUrl = session.homeserverUrl,
        oauthData = null,
        slidingSyncVersion = SlidingSyncVersion.NONE,
    )

    private fun toPatch(diff: TimelineDiff): TimelinePatch? = when (diff) {
        is TimelineDiff.Append -> TimelinePatch.Reset(diff.values.mapNotNull(::toMessage))
        is TimelineDiff.Reset -> TimelinePatch.Reset(diff.values.mapNotNull(::toMessage))
        is TimelineDiff.Clear -> TimelinePatch.Clear
        is TimelineDiff.PushBack -> diff.value?.let { TimelinePatch.PushBack(toMessage(it) ?: return null) }
        is TimelineDiff.PushFront -> diff.value?.let { TimelinePatch.PushFront(toMessage(it) ?: return null) }
        is TimelineDiff.Insert -> diff.value?.let { TimelinePatch.Insert(diff.index.toInt(), toMessage(it) ?: return null) }
        is TimelineDiff.Set -> diff.value?.let { TimelinePatch.Set(diff.index.toInt(), toMessage(it) ?: return null) }
        is TimelineDiff.Remove -> TimelinePatch.Remove(diff.index.toInt())
        is TimelineDiff.PopBack -> TimelinePatch.PopBack
        is TimelineDiff.PopFront -> TimelinePatch.PopFront
        is TimelineDiff.Truncate -> TimelinePatch.Truncate(diff.length.toInt())
    }

    private fun toMessage(item: TimelineItem): Message? = try {
        val event = item.asEvent() ?: return null
        val sender = event.sender ?: return null
        val id = when (val idValue = event.eventOrTransactionId) {
            is EventOrTransactionId.EventId -> idValue.eventId
            is EventOrTransactionId.TransactionId -> idValue.transactionId
            else -> return null
        }
        val content = event.content ?: return null
        val msgLike: TimelineItemContent.MsgLike = content as? TimelineItemContent.MsgLike ?: return null
        when (val kind = msgLike.content.kind) {
            is MsgLikeKind.Message -> {
                val body = kind.content.body
                if (body.isNullOrBlank()) return null
                Message(
                    id = id,
                    roomId = "",
                    sender = sender,
                    body = body,
                    direction = if (sender == activeSession?.userId) MessageDirection.OUTGOING else MessageDirection.INCOMING,
                    state = DeliveryState.SENT,
                    timestampMillis = event.timestamp.toLong(),
                )
            }
            is MsgLikeKind.UnableToDecrypt -> Message(
                id = id,
                roomId = "",
                sender = sender,
                body = UNDECRYPTABLE_PLACEHOLDER,
                direction = if (sender == activeSession?.userId) MessageDirection.OUTGOING else MessageDirection.INCOMING,
                state = DeliveryState.UNDECRYPTABLE,
                timestampMillis = event.timestamp.toLong(),
            )
            is MsgLikeKind.Redacted -> null
            else -> null
        }
    } catch (t: Throwable) {
        onLog("Chronikelement konnte nicht übersetzt werden: ${t.message}")
        null
    }

    companion object {
        const val DEFAULT_APP_ID = "at.d71.kailink"
        private const val DEVICE_NAME = "KaiLink"
        private const val APP_DISPLAY_NAME = "KaiLink"
        private const val DEVICE_DISPLAY_NAME = "KaiLink Android"
        private const val PROFILE_TAG = "kailink"
        private const val LANG = "de"
        private const val UNDECRYPTABLE_PLACEHOLDER = "(verschlüsselt — kann nicht entschlüsselt werden)"
    }
}
