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
import org.box44.kailink.domain.model.SlidingSyncMode
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuildException
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.CreateRoomParameters
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.HttpPusherData
import org.matrix.rustcomponents.sdk.MessageLikeEventContent
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.NotificationClient
import org.matrix.rustcomponents.sdk.NotificationEvent
import org.matrix.rustcomponents.sdk.NotificationItem
import org.matrix.rustcomponents.sdk.NotificationProcessSetup
import org.matrix.rustcomponents.sdk.NotificationStatus
import org.matrix.rustcomponents.sdk.PushFormat
import org.matrix.rustcomponents.sdk.PusherIdentifiers
import org.matrix.rustcomponents.sdk.PusherKind
import org.matrix.rustcomponents.sdk.RoomPreset
import org.matrix.rustcomponents.sdk.RoomVisibility
import org.matrix.rustcomponents.sdk.Room as SdkRoom
import org.matrix.rustcomponents.sdk.Session as SdkSession
import org.matrix.rustcomponents.sdk.SlidingSyncVersion
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.SqliteStoreBuilder
import org.matrix.rustcomponents.sdk.SyncService
import org.matrix.rustcomponents.sdk.SyncSettingsV2
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.TextMessageContent
import org.matrix.rustcomponents.sdk.TimelineEvent
import org.matrix.rustcomponents.sdk.TimelineEventContent
import org.matrix.rustcomponents.sdk.UploadParameters
import org.matrix.rustcomponents.sdk.UploadSource
import org.matrix.rustcomponents.sdk.FileInfo
import org.box44.kailink.data.push.PushNotificationPayload
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
    /**
     * Construction seam of the SDK `NotificationClient` for the push path
     * (`fetchNotification`). The sliding sync version is an EXPLICIT input
     * and carries the active session's detected/persisted mode
     * (`domainModeToSdkVersion(activeSession.slidingSyncMode)`, 0.2.9 fix):
     * the SDK `NotificationClient` (MultipleProcesses) builds its
     * short-lived notification sliding sync from the parent client, and
     * `SlidingSyncBuilder::build` fails with `VersionIsMissing` ("Sliding
     * sync version is missing") when the client carries no version — the
     * 0.2.8 device failure. The default construction therefore encodes the
     * SDK semantics per version: NATIVE (or an unknown/legacy null) uses
     * the standard construction whose parent client carries the
     * DISCOVER_NATIVE-detected (or restore-set) version on capable
     * homeservers; NONE (no sliding sync on the homeserver, e.g. Conduit)
     * skips the construction — the notification sliding sync could never
     * build, so the fetch returns `null` and the caller falls back to the
     * room list (unchanged Conduit/fallback behavior).
     */
    private val notificationClientFactory: suspend (Client, SlidingSyncVersion?) -> NotificationClient? =
        { client, version ->
            when (version) {
                SlidingSyncVersion.NATIVE, null ->
                    client.notificationClient(NotificationProcessSetup.MultipleProcesses)
                SlidingSyncVersion.NONE -> null
            }
        },
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

    /** SDK notification client for the push path (lazily created, closed with the client). */
    @Volatile
    private var notificationClient: NotificationClient? = null

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
        // Sliding sync discovery (docs/decisions.md, 0.2.5-phase1): the FFI
        // ClientBuilder defaults to `SlidingSyncVersionBuilder.None`, which
        // makes every `SyncService` ("live sync") fail with
        // "Sliding sync version is missing" — even on homeservers that
        // support native sliding sync (e.g. matrix.org). The SDK-sanctioned
        // path is `DiscoverNative`: `ClientBuilder.build()` fetches
        // `GET /versions` and selects NATIVE iff the server advertises
        // `org.matrix.simplified_msc3575` (FeatureFlag::Msc4186).
        // Servers without that flag (e.g. Conduit in the E2E gate) make the
        // build fail with ClientBuildException.SlidingSyncVersion; in that
        // case we rebuild with the SDK default (NONE) so sign-in, restore,
        // and syncOnceV2 (classic /sync) keep working — live sync stays
        // unavailable there, exactly as in 0.2.4-phase1.
        return try {
            buildClientWithSlidingSync(homeserverUrl, firstChoiceVersionBuilder)
        } catch (first: Throwable) {
            // The discovery build can fail for two reasons: the homeserver
            // does not support native sliding sync
            // (ClientBuildException.SlidingSyncVersion, e.g. Conduit in the
            // E2E gate), or one of the discovery requests itself failed
            // (network/TLS during well-known or /versions — e.g. the
            // self-signed TLS gate). In both cases the client is rebuilt
            // once with the SDK default ([fallbackVersionBuilder]): sign-in,
            // restore and syncOnceV2 (classic /sync) then behave exactly as
            // in 0.2.4-phase1; healthy servers keep native live sync. The
            // failure is logged, never silenced.
            onLog(
                if (isSlidingSyncVersionBuildFailure(first)) {
                    // Server reachable, but no native sliding sync capability:
                    // Conduit-class homeserver — the fallback is the SDK's
                    // documented path for such servers (live sync then stays
                    // unavailable, as in 0.2.4-phase1).
                    "Homeserver without native sliding sync " +
                        "(${first.message}); retrying with classic /sync"
                } else {
                    "Sliding sync discovery failed (${first.message}); retrying without sliding sync"
                },
            )
            try {
                buildClientWithSlidingSync(homeserverUrl, fallbackVersionBuilder)
            } catch (second: Throwable) {
                throw ChannelException(readableFailure(CONNECT_FAILED_LABEL, second), second)
            }
        }
    }

    private suspend fun buildClientWithSlidingSync(
        homeserverUrl: String,
        versionBuilder: SlidingSyncVersionBuilder,
    ): Client {
        // UniFFI builders are immutable (Rust: self: Arc<Self> -> Arc<Self>):
        // every setter returns a NEW builder; the return value must be
        // chained, otherwise the setting is lost.
        var builder = ClientBuilder()
        builder = builder.homeserverUrl(homeserverUrl)
        builder = builder.slidingSyncVersionBuilder(versionBuilder)
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
            val client = builder.build()
            onLog("Sliding sync version: ${client.slidingSyncVersion()}")
            client
        } finally {
            runCatching { builder.close() }
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

    // ------------------------------------------------------------------ File upload

    override suspend fun sendFile(
        roomId: String,
        fileName: String,
        mimeType: String,
        content: ByteArray,
        caption: String?,
    ) {
        val c = client ?: throw ChannelException("No active session")
        val room: SdkRoom = try {
            c.getRoom(roomId)
        } catch (t: Throwable) {
            throw ChannelException("Room not found: $roomId", t)
        } ?: throw ChannelException("Room not found: $roomId")
        // Reuse the live timeline subscription when the room is open so the
        // attachment appears in the subscribed timeline; otherwise a
        // temporary timeline is created and closed after the send.
        val temporaryTimeline = timelines[roomId] == null
        val timeline: Timeline = if (temporaryTimeline) {
            try {
                room.timeline()
            } catch (t: Throwable) {
                throw ChannelException("Could not open timeline for upload: $roomId", t)
            }
        } else {
            timelines[roomId]!!.timeline
        }
        try {
            val parameters = UploadParameters(
                source = UploadSource.Data(content, fileName),
                caption = caption,
                formattedCaption = null,
                mentions = null,
                inReplyTo = null,
                extraContentJson = null,
            )
            val fileInfo = FileInfo(
                mimetype = mimeType,
                size = content.size.toULong(),
                thumbnailInfo = null,
                thumbnailSource = null,
            )
            val handle = timeline.sendFile(parameters, fileInfo)
            try {
                handle.join()
            } finally {
                runCatching { handle.close() }
            }
            onLog("File sent: $fileName (${content.size} bytes) to $roomId")
        } catch (t: Throwable) {
            if (t is ChannelException) throw t
            throw ChannelException("File upload failed: ${t.message ?: "unknown error"}", t)
        } finally {
            if (temporaryTimeline) {
                runCatching { timeline.close() }
            }
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

    /**
     * SDK-sanctioned notification resolution for the push path
     * (Element X pattern: `NotificationClient.getNotification(roomId,
     * eventId)`). Returns `null` when there is no session, no ids, the
     * event was filtered/redacted/not found, or the fetch failed — the
     * caller ([org.box44.kailink.data.push.PushMessageHandler]) then falls
     * back to the room list.
     */
    suspend fun fetchNotification(roomId: String?, eventId: String?): PushNotificationPayload? {
        if (roomId.isNullOrBlank() || eventId.isNullOrBlank()) return null
        val c = client ?: return null
        // 0.2.9 fix: the construction seam receives the active session's
        // detected/persisted sliding sync mode, mapped 1:1 to the SDK
        // version. The FFI notification client has no version parameter, so
        // this explicit carrier is the only app-side control over which
        // sliding sync version the notification fetch runs with (see the
        // `notificationClientFactory` KDoc for the per-version semantics).
        val version = activeSession?.slidingSyncMode
            ?.let(MatrixSdkChannelClient::domainModeToSdkVersion)
        val nc = obtainNotificationClient(c, version) ?: return null
        val status = try {
            nc.getNotification(roomId, eventId)
        } catch (t: Throwable) {
            onLog("Notification fetch failed: ${t.message}")
            return null
        }
        return try {
            when (status) {
                is NotificationStatus.Event -> notificationPayload(status.item, roomId)
                else -> null
            }
        } catch (t: Throwable) {
            onLog("Could not map notification: ${t.message}")
            null
        } finally {
            if (status is NotificationStatus.Event) runCatching { status.item.destroy() }
            runCatching { status.destroy() }
        }
    }

    private suspend fun obtainNotificationClient(
        c: Client,
        version: SlidingSyncVersion?,
    ): NotificationClient? {
        notificationClient?.let { return it }
        return try {
            // MultipleProcesses: the push process has no SyncService
            // (SingleProcess requires one) — the dedicated push-process setup.
            val constructed = notificationClientFactory(c, version)
            if (constructed == null && version == SlidingSyncVersion.NONE) {
                onLog(
                    "NotificationClient skipped: homeserver has no sliding sync (NONE); " +
                        "room-list fallback",
                )
            }
            notificationClient = constructed
            constructed
        } catch (t: Throwable) {
            onLog("NotificationClient unavailable: ${t.message}")
            null
        }
    }

    private fun notificationPayload(item: NotificationItem, roomId: String): PushNotificationPayload? {
        val event = item.event as? NotificationEvent.Timeline ?: return null
        val body = notificationBody(event.event)
        val roomDisplayName = item.roomInfo.displayName?.takeIf { it.isNotBlank() }
        val room = Room(
            id = roomId,
            displayName = roomDisplayName ?: roomId,
            isEncrypted = item.roomInfo.isEncrypted == true,
            lastMessage = null,
        )
        val message = Message(
            id = eventIdOf(event.event) ?: "",
            roomId = roomId,
            sender = event.event.senderId(),
            body = body.orEmpty(),
            direction = MessageDirection.INCOMING,
            state = if (body == null) DeliveryState.UNDECRYPTABLE else DeliveryState.SENT,
            timestampMillis = event.event.timestamp().toLong(),
        )
        return PushNotificationPayload.from(room, message)
    }

    private fun notificationBody(event: TimelineEvent): String? = when (val content = event.content()) {
        is TimelineEventContent.MessageLike -> when (val like = content.content) {
            is MessageLikeEventContent.RoomMessage -> when (val type = like.messageType) {
                is MessageType.Text -> type.content.body
                is MessageType.Notice -> type.content.body
                is MessageType.Emote -> type.content.body
                else -> null
            }
            is MessageLikeEventContent.RoomEncrypted -> UNDECRYPTABLE_PLACEHOLDER
            else -> null
        }
        else -> null
    }

    private fun eventIdOf(event: TimelineEvent): String? = runCatching { event.eventId() }.getOrNull()

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
        notificationClient?.let { runCatching { it.destroy() } }
        notificationClient = null
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
        // The detected sliding sync version must survive persistence:
        // the FFI restore path (restoreSessionWith) sets the client's
        // version from this field (docs/decisions.md, 0.2.5-phase1).
        slidingSyncMode = MatrixSdkChannelClient.sdkVersionToDomainMode(sdk.slidingSyncVersion),
    )

    private fun toSdkSession(session: Session): SdkSession = SdkSession(
        accessToken = session.accessToken,
        refreshToken = session.refreshToken,
        userId = session.userId,
        deviceId = session.deviceId,
        homeserverUrl = session.homeserverUrl,
        oauthData = null,
        slidingSyncVersion = MatrixSdkChannelClient.domainModeToSdkVersion(session.slidingSyncMode),
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

        /**
         * SDK-sanctioned sliding sync discovery (0.2.5-phase1):
         * `SlidingSyncVersionBuilder.DiscoverNative` makes
         * `ClientBuilder.build()` fetch `GET /versions` and select
         * `Version::Native` iff the response's `unstable_features` contain
         * `org.matrix.simplified_msc3575` (`FeatureFlag::Msc4186`),
         * otherwise the build fails with
         * `ClientBuildError::SlidingSyncVersion(NativeVersionIsUnset)`
         * (crates/matrix-sdk/src/sliding_sync/client.rs in the pinned SDK).
         */
        internal val firstChoiceVersionBuilder: SlidingSyncVersionBuilder =
            SlidingSyncVersionBuilder.DISCOVER_NATIVE

        /**
         * Fallback when the discovery build (first attempt) failed: always
         * the SDK default NONE. Two failure classes reach it: a server
         * without native sliding sync
         * (`ClientBuildException.SlidingSyncVersion`/`NativeVersionIsUnset`)
         * and discovery-transport failures (network/TLS during the
         * well-known or `/versions` request of the discovery build). The
         * second build then carries the exact pre-0.2.5 semantics (no
         * discovery traffic); its error — if any — is surfaced.
         */
        internal val fallbackVersionBuilder: SlidingSyncVersionBuilder =
            SlidingSyncVersionBuilder.NONE

        /**
         * True if the error chain contains the SDK's sliding sync version
         * build error (`ClientBuildError::SlidingSyncVersion`, e.g.
         * `NativeVersionIsUnset`): the homeserver does not advertise native
         * sliding sync via `/versions` (`org.matrix.simplified_msc3575`).
         */
        internal fun isSlidingSyncVersionBuildFailure(t: Throwable): Boolean {
            var current: Throwable? = t
            while (current != null) {
                if (current is ClientBuildException.SlidingSyncVersion) return true
                current = current.cause
            }
            return false
        }

        internal fun sdkVersionToDomainMode(version: SlidingSyncVersion): SlidingSyncMode = when (version) {
            SlidingSyncVersion.NATIVE -> SlidingSyncMode.NATIVE
            SlidingSyncVersion.NONE -> SlidingSyncMode.NONE
        }

        internal fun domainModeToSdkVersion(mode: SlidingSyncMode): SlidingSyncVersion = when (mode) {
            SlidingSyncMode.NATIVE -> SlidingSyncVersion.NATIVE
            SlidingSyncMode.NONE -> SlidingSyncVersion.NONE
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
