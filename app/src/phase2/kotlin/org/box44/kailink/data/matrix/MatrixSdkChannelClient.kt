package org.box44.kailink.data.matrix

import org.box44.kailink.domain.ChannelClient
import org.box44.kailink.domain.ChannelEvent
import org.box44.kailink.domain.ChannelException
import org.box44.kailink.domain.SessionStore
import org.box44.kailink.domain.TimelinePatch
import org.box44.kailink.domain.TimelineReducer
import org.box44.kailink.domain.model.DeliveryState
import org.box44.kailink.domain.model.Message
import org.box44.kailink.domain.model.MessageDirection
import org.box44.kailink.domain.model.Room
import org.box44.kailink.domain.model.Session
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
import org.matrix.rustcomponents.sdk.CreateRoomParameters
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.HttpPusherData
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.PushFormat
import org.matrix.rustcomponents.sdk.PusherIdentifiers
import org.matrix.rustcomponents.sdk.PusherKind
import org.matrix.rustcomponents.sdk.RoomPreset
import org.matrix.rustcomponents.sdk.RoomVisibility
import org.matrix.rustcomponents.sdk.Room as SdkRoom
import org.matrix.rustcomponents.sdk.Session as SdkSession
import org.matrix.rustcomponents.sdk.SlidingSyncVersion
import org.matrix.rustcomponents.sdk.SqliteStoreBuilder
import org.matrix.rustcomponents.sdk.SyncService
import org.matrix.rustcomponents.sdk.SyncSettingsV2
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.TextMessageContent
import uniffi.matrix_sdk_crypto.CollectStrategy
import uniffi.matrix_sdk_crypto.DecryptionSettings
import uniffi.matrix_sdk_crypto.TrustRequirement
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.TimelineListener
import org.box44.kailink.domain.push.PushConfiguration

/**
 * Adapter onto the real matrix-rust-sdk (org.matrix.rustcomponents:sdk-android).
 *
 * Since Phase 2 this class is part of the production build and wired in
 * `AppGraph`. It translates SDK calls/listeners into the domain seam
 * [ChannelClient] and into [ChannelEvent]/[TimelinePatch].
 * Deviating behavior compared to the Phase-1 simulation is documented in
 * docs/architecture.md.
 */
class MatrixSdkChannelClient(
    private val sessionStore: SessionStore,
    private val storeDir: File,
    private val cacheDir: File,
    private val scope: CoroutineScope,
    private val appId: String = PushConfiguration.DEFAULT_APP_ID,
    private val gatewayUrl: String = PushConfiguration.DEFAULT_GATEWAY_URL,
    private val e2eeTestConfig: E2eeTestConfig? = null,
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
            throw ChannelException(readableFailure(LOGIN_FAILED_LABEL, t), t)
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
            throw ChannelException(readableFailure(RESTORE_FAILED_LABEL, t), t)
        }
        return adoptClient(c, source = "Restore")
    }

    private suspend fun adoptClient(c: Client, source: String): Session {
        val sdkSession = c.session()
        val session = toDomainSession(sdkSession)
        sessionStore.save(session)
        activeSession = session
        client = c
        onLog("$source succeeded: ${session.userId} @ ${session.homeserverUrl}")
        emitRooms()
        return session
    }

    private suspend fun buildClient(homeserverUrl: String): Client {
        storeDir.mkdirs()
        cacheDir.mkdirs()
        // UniFFI builders are immutable (Rust: self: Arc<Self> -> Arc<Self>):
        // every setter returns a NEW builder; the return value must be
        // chained, otherwise the setting is lost.
        var builder = ClientBuilder()
        builder = builder.homeserverUrl(homeserverUrl)
        builder = builder.sqliteStore(
            SqliteStoreBuilder(
                storeDir.resolve("state.sqlite").absolutePath,
                cacheDir.absolutePath,
            ),
        )
        if (e2eeTestConfig?.allowUntrustedDevices == true) {
            builder = builder.roomKeyRecipientStrategy(CollectStrategy.ALL_DEVICES)
            builder = builder.decryptionSettings(DecryptionSettings(TrustRequirement.UNTRUSTED))
        }
        return try {
            builder.build()
        } catch (t: Throwable) {
            throw ChannelException(readableFailure(CONNECT_FAILED_LABEL, t), t)
        }
    }

    /**
     * Replaces raw library messages with readable errors. The
     * rustls-platform-verifier panic ("Expect rustls-platform-verifier to be
     * initialized" / "Failed to initialize rustls platform verifier") is
     * passed through UniFFI as an InternalException/ClientException with
     * exactly that text — it means a broken platform initialization and must
     * not be shown to the user as a raw panic.
     *
     * TLS/certificate failures (rustls `InvalidCertificate(...)`, handshake
     * failures) get a readable user message; the raw reqwest/hyper/rustls
     * details are only written to the app log ([onLog]). The cause chain stays
     * attached to the exception for diagnostics.
     */
    internal fun readableFailure(context: String, t: Throwable): String {
        if (isPlatformVerifierInitFailure(t)) return TLS_INIT_FAILED_MESSAGE
        if (isTlsCertificateFailure(t)) {
            onLog("TLS/certificate failure during $context: ${throwableChainText(t)}")
            return TLS_CERT_FAILED_MESSAGE
        }
        return "$context: ${t.message ?: "unknown error"}"
    }

    private fun throwableChainText(t: Throwable): String = buildString {
        var current: Throwable? = t
        while (current != null) {
            if (isNotEmpty()) append(" <- ")
            append(current.javaClass.name).append(": ").append(current.message)
            current = current.cause
        }
    }

    // ------------------------------------------------------------------ Sync

    override suspend fun syncOnce() {
        val c = client ?: throw ChannelException("No active session")
        c.syncOnceV2(SyncSettingsV2())
        emitRooms()
    }

    override suspend fun startLiveSync() {
        if (syncService != null) return
        val c = client ?: throw ChannelException("No active session")
        val service = try {
            c.syncService().finish()
        } catch (t: Throwable) {
            throw ChannelException("Could not start live sync: ${t.message ?: "unknown error"}", t)
        }
        service.start()
        syncService = service
        _events.emit(ChannelEvent.SyncStateChanged(true))
        onLog("Live sync started")
    }

    override suspend fun stopLiveSync() {
        val service = syncService ?: return
        syncService = null
        runCatching { service.stop() }
        _events.emit(ChannelEvent.SyncStateChanged(false))
        onLog("Live sync stopped")
    }

    // ------------------------------------------------------------------ Rooms & Timeline

    override suspend fun rooms(): List<Room> {
        val c = client ?: throw ChannelException("No active session")
        return c.rooms().map { sdkRoom ->
            Room(
                id = sdkRoom.id(),
                displayName = sdkRoom.displayName().orEmpty().ifBlank { "(unnamed room)" },
                isEncrypted = runCatching { sdkRoom.isEncrypted() }.getOrNull() == true,
                lastMessage = null,
            )
        }.also {
            onLog("Rooms loaded: ${it.size}")
        }
    }

    override suspend fun createRoom(name: String, inviteUserIds: List<String>, encrypted: Boolean): String {
        val c = client ?: throw ChannelException("No active session")
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw ChannelException("Empty room name")
        val parameters = CreateRoomParameters(
            name = trimmed,
            isEncrypted = encrypted,
            visibility = RoomVisibility.Private,
            preset = RoomPreset.PRIVATE_CHAT,
            invite = inviteUserIds,
        )
        return try {
            val roomId = c.createRoom(parameters)
            onLog("Room created: $roomId")
            emitRooms()
            roomId
        } catch (t: Throwable) {
            throw ChannelException("Room creation failed: ${t.message ?: "unknown error"}", t)
        }
    }

    override suspend fun joinRoom(roomId: String) {
        val c = client ?: throw ChannelException("No active session")
        try {
            c.joinRoomById(roomId)
            onLog("Room joined: $roomId")
            emitRooms()
        } catch (t: Throwable) {
            throw ChannelException("Room join failed: ${t.message ?: "unknown error"}", t)
        }
    }

    override suspend fun openTimeline(roomId: String) {
        val c = client ?: throw ChannelException("No active session")
        if (timelines.containsKey(roomId)) return
        val room: SdkRoom? = try {
            c.getRoom(roomId)
        } catch (t: Throwable) {
            throw ChannelException("Room not found: $roomId", t)
        }
        val timeline = room?.timeline()
            ?: throw ChannelException("Room not found: $roomId")
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
                        onLog("Timeline update failed: ${t.message}")
                    }
                }
            }
        }
        val handle = try {
            timeline.addListener(listener)
        } catch (t: Throwable) {
            runCatching { timeline.close() }
            throw ChannelException("Could not open timeline: ${t.message ?: "unknown error"}", t)
        }
        timelines[roomId] = TimelineSubscription(timeline, listener, handle)
        onLog("Timeline subscribed: $roomId")
    }

    override suspend fun closeTimeline(roomId: String) {
        timelines.remove(roomId)?.let { subscription ->
            runCatching { subscription.handle?.close() }
            runCatching { subscription.timeline.close() }
            onLog("Timeline unsubscribed: $roomId")
        }
    }

    override suspend fun sendMessage(roomId: String, body: String) {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) throw ChannelException("Empty message")
        val subscription = timelines[roomId]
        val timeline: Timeline = subscription?.timeline ?: run {
            openTimeline(roomId)
            timelines[roomId]?.timeline ?: throw ChannelException("Timeline not open: $roomId")
        }
        try {
            val content = timeline.createMessageContent(MessageType.Text(TextMessageContent(trimmed, null)))
                ?: throw ChannelException("Could not create message content")
            timeline.send(content)
            onLog("Message handed to send queue: $roomId")
        } catch (t: Throwable) {
            throw ChannelException("Send failed: ${t.message ?: "unknown error"}", t)
        }
    }

    // ------------------------------------------------------------------ Push

    override suspend fun registerPushEndpoint(endpointUrl: String) {
        val c = client ?: throw ChannelException("No active session")
        try {
            // Push gateway separation (docs/features/push.md): the UnifiedPush
            // endpoint is the `pushkey`; the HttpPusherData points to the
            // Matrix push gateway ([gatewayUrl], default: ntfy), which
            // translates the Matrix push message for the distributor.
            val identifiers = PusherIdentifiers(pushkey = endpointUrl, appId = appId)
            val data = HttpPusherData(gatewayUrl, PushFormat.EVENT_ID_ONLY, null)
            c.setPusher(
                identifiers,
                PusherKind.Http(data),
                APP_DISPLAY_NAME,
                DEVICE_DISPLAY_NAME,
                PROFILE_TAG,
                LANG,
                true,
            )
            onLog("UnifiedPush endpoint registered as Matrix pusher (gateway: $gatewayUrl)")
        } catch (t: Throwable) {
            throw ChannelException("Pusher registration failed: ${t.message ?: "unknown error"}", t)
        }
    }

    // ------------------------------------------------------------------ Lifecycle

    override suspend fun logout() {
        sessionStore.clear()
        closeExisting()
        onLog("Signed out locally")
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
            .onFailure { _events.emit(ChannelEvent.ClientError(it.message ?: "Could not load rooms")) }
    }

    // ------------------------------------------------------------------ Mapping SDK → Domain

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

    private fun toMessage(item: TimelineItem): Message? {
        return try {
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
            onLog("Could not map timeline item: ${t.message}")
            null
        }
    }

    companion object {
        private const val DEVICE_NAME = "KaiLink"
        private const val APP_DISPLAY_NAME = "KaiLink"
        private const val DEVICE_DISPLAY_NAME = "KaiLink Android"
        private const val PROFILE_TAG = "kailink"
        private const val LANG = "en"
        private const val UNDECRYPTABLE_PLACEHOLDER = "(encrypted — cannot be decrypted)"
        private const val PLATFORM_VERIFIER_MARKER = "rustls-platform-verifier"
        internal const val TLS_INIT_FAILED_MESSAGE =
            "Could not initialize the secure connection (TLS verification). " +
                "Please update or reinstall the app."

        // Markers for TLS/certificate failures in the SDK/UniFFI error chain
        // (rustls: "invalid peer certificate: ...", UniFFI details:
        // "InvalidCertificate(...)", reqwest/hyper connect errors).
        // Mirrors the whitelist in TlsE2eTest.
        internal val TLS_CERT_MARKERS = listOf(
            "invalid peer certificate",
            "invalidcertificate",
            "certificate",
            "handshake",
            "hostname",
            "tls",
        )

        internal const val TLS_CERT_FAILED_MESSAGE =
            "Secure connection to the homeserver failed (certificate error). " +
                "Check the server address."

        internal fun isPlatformVerifierInitFailure(t: Throwable): Boolean {
            var current: Throwable? = t
            while (current != null) {
                if (current.message?.contains(PLATFORM_VERIFIER_MARKER) == true) return true
                current = current.cause
            }
            return false
        }

        internal fun isTlsCertificateFailure(t: Throwable): Boolean {
            var current: Throwable? = t
            while (current != null) {
                val message = current.message?.lowercase()
                if (message != null && TLS_CERT_MARKERS.any { message.contains(it) }) return true
                current = current.cause
            }
            return false
        }

        private const val LOGIN_FAILED_LABEL = "Sign-in failed"
        private const val RESTORE_FAILED_LABEL = "Could not restore session"
        private const val CONNECT_FAILED_LABEL = "Could not connect to homeserver"
    }
}
