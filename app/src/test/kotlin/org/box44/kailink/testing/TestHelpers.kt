package org.box44.kailink.testing

import org.box44.kailink.domain.ChannelClient
import org.box44.kailink.domain.ChannelEvent
import org.box44.kailink.domain.SessionStore
import org.box44.kailink.domain.model.DeliveryState
import org.box44.kailink.domain.model.Message
import org.box44.kailink.domain.model.MessageDirection
import org.box44.kailink.domain.model.Room
import org.box44.kailink.domain.model.Session
import org.box44.kailink.domain.push.PushRegistrationTrigger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

val TEST_SESSION = Session(
    userId = "@alice:example.org",
    deviceId = "DEVICE1",
    homeserverUrl = "https://matrix.example.org",
    accessToken = "token-123",
    refreshToken = null,
)

val TEST_ROOM = Room(
    id = "!room1:example.org",
    displayName = "Test room",
    isEncrypted = true,
    lastMessage = null,
)

fun testMessage(id: String, roomId: String = TEST_ROOM.id): Message = Message(
    id = id,
    roomId = roomId,
    sender = "@bob:example.org",
    body = "hello-$id",
    direction = MessageDirection.INCOMING,
    state = DeliveryState.SENT,
    timestampMillis = 42L,
)

/**
 * Fake implementation for JVM checks: counts calls and allows emitting
 * domain events manually.
 */
class FakeChannelClient : ChannelClient {

    private val _events = MutableSharedFlow<ChannelEvent>(extraBufferCapacity = 128)
    override val events: Flow<ChannelEvent> = _events

    override var activeSession: Session? = null
        private set

    var loginCalls = 0
    var restoreCalls = 0
    var syncOnceCalls = 0
    var startLiveSyncCalls = 0
    var logoutCalls = 0
    val openTimelineCalls = mutableListOf<String>()
    val sendMessageCalls = mutableListOf<Pair<String, String>>()
    val sendFileCalls = mutableListOf<SentFile>()
    val registerEndpointCalls = mutableListOf<String>()

    var loginBehavior: (String, String, String) -> Session = { url, user, _ ->
        Session(
            userId = if (user.contains('@')) user else "@$user:$url",
            deviceId = "DEVICE1",
            homeserverUrl = url,
            accessToken = "fresh-token",
            refreshToken = null,
        )
    }
    var restoreBehavior: (Session) -> Session = { it }
    var roomsBehavior: () -> List<Room> = { listOf(TEST_ROOM) }
    var sendMessageBehavior: (String, String) -> Unit = { _, _ -> }
    var registerPushEndpointBehavior: (String) -> Unit = {}
    var startLiveSyncBehavior: () -> Unit = {}

    override suspend fun login(homeserverUrl: String, username: String, password: String): Session {
        loginCalls++
        val session = loginBehavior(homeserverUrl, username, password)
        activeSession = session
        return session
    }

    override suspend fun restore(session: Session): Session {
        restoreCalls++
        val restored = restoreBehavior(session)
        activeSession = restored
        return restored
    }

    override suspend fun syncOnce() {
        syncOnceCalls++
    }

    override suspend fun startLiveSync() {
        startLiveSyncCalls++
        startLiveSyncBehavior()
    }

    override suspend fun stopLiveSync() = Unit

    override suspend fun rooms(): List<Room> = roomsBehavior()

    override suspend fun createRoom(name: String, inviteUserIds: List<String>, encrypted: Boolean): String = TEST_ROOM.id

    override suspend fun joinRoom(roomId: String) = Unit

    override suspend fun openTimeline(roomId: String) {
        openTimelineCalls.add(roomId)
    }

    override suspend fun closeTimeline(roomId: String) = Unit

    override suspend fun sendMessage(roomId: String, body: String) {
        sendMessageCalls.add(roomId to body)
        sendMessageBehavior(roomId, body)
    }

    override suspend fun sendFile(
        roomId: String,
        fileName: String,
        mimeType: String,
        content: ByteArray,
        caption: String?,
    ) {
        sendFileCalls.add(SentFile(roomId, fileName, mimeType, content, caption))
    }

    override suspend fun registerPushEndpoint(endpointUrl: String) {
        registerEndpointCalls.add(endpointUrl)
        registerPushEndpointBehavior(endpointUrl)
    }

    override suspend fun logout() {
        logoutCalls++
        activeSession = null
    }

    override fun dispose() = Unit

    suspend fun emit(event: ChannelEvent) {
        _events.emit(event)
    }
}

/** Recorded file send of [FakeChannelClient]. */
data class SentFile(
    val roomId: String,
    val fileName: String,
    val mimeType: String,
    val content: ByteArray,
    val caption: String?,
)

class FakeSessionStore(initial: Session? = null) : SessionStore {

    private var stored: Session? = initial
    var saveCalls = 0
    var clearCalls = 0

    override fun load(): Session? = stored

    override fun save(session: Session) {
        saveCalls++
        stored = session
    }

    override fun clear() {
        clearCalls++
        stored = null
    }
}

class RecordingPushTrigger : PushRegistrationTrigger {
    var registrations = 0
    override fun tryRegister() {
        registrations++
    }
}
