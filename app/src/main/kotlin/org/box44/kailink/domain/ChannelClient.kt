package org.box44.kailink.domain

import org.box44.kailink.domain.model.Message
import org.box44.kailink.domain.model.Room
import org.box44.kailink.domain.model.Session
import kotlinx.coroutines.flow.Flow

/** Error with a user-facing message from the channel layer. */
class ChannelException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Events reported by a [ChannelClient] to the UI layer. */
sealed interface ChannelEvent {
    data class RoomsUpdated(val rooms: List<Room>) : ChannelEvent
    data class TimelineUpdated(val roomId: String, val messages: List<Message>) : ChannelEvent
    data class SyncStateChanged(val running: Boolean) : ChannelEvent
    data class ClientError(val message: String) : ChannelEvent
}

/**
 * Seam between UI/logic and a messaging service (Matrix).
 *
 * Implementations: `MatrixSdkChannelClient` (real matrix-rust-sdk,
 * production-like) and `FakeChannelClient` (JVM tests).
 * The interface is deliberately free of Android and SDK types (G4).
 */
interface ChannelClient {
    /** Event stream; emitted on an arbitrary thread. */
    val events: Flow<ChannelEvent>

    /** Active session or null if not signed in. */
    val activeSession: Session?

    /** New login (replaces an existing session). */
    suspend fun login(homeserverUrl: String, username: String, password: String): Session

    /** Restore from a stored [Session]. */
    suspend fun restore(session: Session): Session

    /** One-shot sync with the server. */
    suspend fun syncOnce()

    /** Starts foreground live sync (idempotent). */
    suspend fun startLiveSync()

    /** Stops live sync (idempotent). */
    suspend fun stopLiveSync()

    /** Current room list. */
    suspend fun rooms(): List<Room>

    /** Creates a room (optional invites, optional E2EE) and returns the room ID. */
    suspend fun createRoom(name: String, inviteUserIds: List<String>, encrypted: Boolean): String

    /** Joins a room via its room ID. */
    suspend fun joinRoom(roomId: String)

    /** Subscribes to a room's timeline (idempotent). */
    suspend fun openTimeline(roomId: String)

    /** Releases a timeline subscription. */
    suspend fun closeTimeline(roomId: String)

    /** Sends a text message (SDK send queue). */
    suspend fun sendMessage(roomId: String, body: String)

    /**
     * Sends a file attachment (e.g. the debug log as `.txt`) through the
     * channel's media upload + send path. The file content is held in
     * memory ([content]); small files only.
     */
    suspend fun sendFile(
        roomId: String,
        fileName: String,
        mimeType: String,
        content: ByteArray,
        caption: String?,
    )

    /** Registers a UnifiedPush endpoint as a Matrix pusher. */
    suspend fun registerPushEndpoint(endpointUrl: String)

    /** Local sign-out (PoC: device stays active server-side). */
    suspend fun logout()

    /** Releases all SDK resources. */
    fun dispose()
}
