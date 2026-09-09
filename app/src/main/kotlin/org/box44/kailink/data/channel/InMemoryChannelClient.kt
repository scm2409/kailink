package org.box44.kailink.data.channel

import org.box44.kailink.domain.ChannelClient
import org.box44.kailink.domain.ChannelEvent
import org.box44.kailink.domain.ChannelException
import org.box44.kailink.domain.SessionStore
import org.box44.kailink.domain.model.DeliveryState
import org.box44.kailink.domain.model.Message
import org.box44.kailink.domain.model.MessageDirection
import org.box44.kailink.domain.model.Room
import org.box44.kailink.domain.model.Session
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Phase-1 implementation of the [ChannelClient] seam (principle G5).
 *
 * Simulates a channel service entirely in memory: login, session restore,
 * room list, timeline and sending run without a network. In Phase 2 it is
 * replaced by `MatrixSdkChannelClient`
 * (reference implementation under `app/src/phase2/`).
 */
class InMemoryChannelClient(
    private val sessionStore: SessionStore,
) : ChannelClient {

    private val _events = MutableSharedFlow<ChannelEvent>(extraBufferCapacity = 128)
    override val events: Flow<ChannelEvent> = _events.asSharedFlow()

    @Volatile
    override var activeSession: Session? = null
        private set

    @Volatile
    private var liveSyncRunning: Boolean = false

    @Volatile
    var registeredEndpoint: String? = null
        private set

    private val rooms = LinkedHashMap<String, ChannelRoom>()

    private val openTimelines = ConcurrentHashMap.newKeySet<String>()

    init {
        seedDemoRooms()
    }

    override suspend fun login(homeserverUrl: String, username: String, password: String): Session {
        if (homeserverUrl.isBlank() || username.isBlank() || password.isBlank()) {
            throw ChannelException("Please fill in all sign-in fields.")
        }
        closeExisting()
        val session = Session(
            userId = "@${username.trim()}:phase1.local",
            deviceId = "PHASE1-DEVICE",
            homeserverUrl = homeserverUrl.trim(),
            accessToken = "phase1-${UUID.randomUUID()}",
            refreshToken = null,
        )
        sessionStore.save(session)
        activeSession = session
        emitRooms()
        return session
    }

    override suspend fun restore(session: Session): Session {
        closeExisting()
        sessionStore.save(session)
        activeSession = session
        emitRooms()
        return session
    }

    override suspend fun syncOnce() {
        requireSession()
        emitRooms()
        openTimelines.toList().forEach { emitTimeline(it) }
    }

    override suspend fun startLiveSync() {
        requireSession()
        liveSyncRunning = true
        _events.tryEmit(ChannelEvent.SyncStateChanged(true))
    }

    override suspend fun stopLiveSync() {
        liveSyncRunning = false
        _events.tryEmit(ChannelEvent.SyncStateChanged(false))
    }

    override suspend fun rooms(): List<Room> {
        requireSession()
        return synchronized(rooms) { rooms.values.map { it.toRoom() } }
    }

    override suspend fun createRoom(name: String, inviteUserIds: List<String>, encrypted: Boolean): String {
        requireSession()
        val displayName = name.trim()
        if (displayName.isEmpty()) throw ChannelException("Empty room name")
        val roomId = "!phase1-${UUID.randomUUID()}:phase1.local"
        synchronized(rooms) {
            rooms[roomId] = ChannelRoom(
                id = roomId,
                displayName = displayName,
                isEncrypted = encrypted,
            )
        }
        emitRooms()
        return roomId
    }

    override suspend fun joinRoom(roomId: String) {
        requireSession()
        room(roomId)
    }

    override suspend fun openTimeline(roomId: String) {
        requireSession()
        room(roomId)
        openTimelines.add(roomId)
        emitTimeline(roomId)
    }

    override suspend fun closeTimeline(roomId: String) {
        openTimelines.remove(roomId)
    }

    override suspend fun sendMessage(roomId: String, body: String) {
        val session = requireSession()
        val trimmed = body.trim()
        if (trimmed.isEmpty()) throw ChannelException("Empty message")
        val room = room(roomId)
        val message = Message(
            id = "local-${UUID.randomUUID()}",
            roomId = roomId,
            sender = session.userId,
            body = trimmed,
            direction = MessageDirection.OUTGOING,
            state = DeliveryState.SENT,
            timestampMillis = System.currentTimeMillis(),
        )
        synchronized(room.messages) {
            room.messages.add(message)
        }
        emitRooms()
        emitTimeline(roomId)
    }

    override suspend fun sendFile(
        roomId: String,
        fileName: String,
        mimeType: String,
        content: ByteArray,
        caption: String?,
    ) {
        requireSession()
        room(roomId)
        // G5: no bluff — the in-memory reference channel has no media upload.
        throw ChannelException("Sending files is not supported by the in-memory reference channel.")
    }

    override suspend fun registerPushEndpoint(endpointUrl: String) {
        requireSession()
        registeredEndpoint = endpointUrl
    }

    override suspend fun logout() {
        sessionStore.clear()
        closeExisting()
    }

    override fun dispose() {
        closeExisting()
    }

    private fun requireSession(): Session =
        activeSession ?: throw ChannelException("No active session")

    private fun room(roomId: String): ChannelRoom =
        rooms[roomId] ?: throw ChannelException("Unknown room: $roomId")

    private fun closeExisting() {
        activeSession = null
        liveSyncRunning = false
        openTimelines.clear()
        registeredEndpoint = null
    }

    private fun emitRooms() {
        val snapshot = synchronized(rooms) { rooms.values.map { it.toRoom() } }
        _events.tryEmit(ChannelEvent.RoomsUpdated(snapshot))
    }

    private fun emitTimeline(roomId: String) {
        val room = room(roomId)
        val snapshot = synchronized(room.messages) { room.messages.toList() }
        _events.tryEmit(ChannelEvent.TimelineUpdated(roomId, snapshot))
    }

    private fun seedDemoRooms() {
        val projectRoom = ChannelRoom(
            id = "!phase1-project:phase1.local",
            displayName = "Project Channel Phase 1",
            isEncrypted = true,
        )
        projectRoom.messages.add(
            Message(
                id = "seed-1",
                roomId = projectRoom.id,
                sender = "@d71:phase1.local",
                body = "Welcome to KaiLink Phase 1. This channel is a simulation.",
                direction = MessageDirection.INCOMING,
                state = DeliveryState.SENT,
                timestampMillis = 1_000L,
            ),
        )
        projectRoom.messages.add(
            Message(
                id = "seed-2",
                roomId = projectRoom.id,
                sender = "@d71:phase1.local",
                body = "Rooms, timeline and sending run entirely in memory in Phase 1.",
                direction = MessageDirection.INCOMING,
                state = DeliveryState.SENT,
                timestampMillis = 2_000L,
            ),
        )
        val notesRoom = ChannelRoom(
            id = "!phase1-notes:phase1.local",
            displayName = "Notes",
            isEncrypted = false,
        )
        notesRoom.messages.add(
            Message(
                id = "seed-3",
                roomId = notesRoom.id,
                sender = "@d71:phase1.local",
                body = "First note: check the login and push behavior in the manual protocol.",
                direction = MessageDirection.INCOMING,
                state = DeliveryState.SENT,
                timestampMillis = 3_000L,
            ),
        )
        rooms[projectRoom.id] = projectRoom
        rooms[notesRoom.id] = notesRoom
    }

    private class ChannelRoom(
        val id: String,
        val displayName: String,
        val isEncrypted: Boolean,
    ) {
        val messages: MutableList<Message> = mutableListOf()

        fun toRoom(): Room = Room(
            id = id,
            displayName = displayName,
            isEncrypted = isEncrypted,
            lastMessage = messages.lastOrNull(),
        )
    }
}
