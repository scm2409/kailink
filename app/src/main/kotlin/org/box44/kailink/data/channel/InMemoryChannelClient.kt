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
 * Phase-1-Implementierung der [ChannelClient]-Nahtstelle (Grundsatz G5).
 *
 * Simuliert einen Kanaldienst vollständig im Speicher: Login, Sitzungs-
 * wiederherstellung, Raumliste, Chronik und Senden laufen ohne Netzwerk.
 * In Phase 2 wird sie durch `MatrixSdkChannelClient` ersetzt
 * (Referenzimplementierung unter `app/src/phase2/`).
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
            throw ChannelException("Bitte alle Anmeldefelder ausfüllen.")
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
        if (trimmed.isEmpty()) throw ChannelException("Leere Nachricht")
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
        activeSession ?: throw ChannelException("Keine aktive Sitzung")

    private fun room(roomId: String): ChannelRoom =
        rooms[roomId] ?: throw ChannelException("Unbekannter Raum: $roomId")

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
            id = "!phase1-projekt:phase1.local",
            displayName = "Projektkanal Phase 1",
            isEncrypted = true,
        )
        projectRoom.messages.add(
            Message(
                id = "seed-1",
                roomId = projectRoom.id,
                sender = "@d71:phase1.local",
                body = "Willkommen bei KaiLink Phase 1. Dieser Kanal ist eine Simulation.",
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
                body = "Räume, Chronik und Senden laufen in Phase 1 komplett im Speicher.",
                direction = MessageDirection.INCOMING,
                state = DeliveryState.SENT,
                timestampMillis = 2_000L,
            ),
        )
        val notesRoom = ChannelRoom(
            id = "!phase1-notizen:phase1.local",
            displayName = "Notizen",
            isEncrypted = false,
        )
        notesRoom.messages.add(
            Message(
                id = "seed-3",
                roomId = notesRoom.id,
                sender = "@d71:phase1.local",
                body = "Erste Notiz: Anmelde- und Push-Verhalten im manuellen Protokoll prüfen.",
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
