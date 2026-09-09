package org.box44.kailink.domain.model

/**
 * Persistent Matrix session (domain model).
 * The 1:1 translation to the SDK session happens in the adapter (data/matrix).
 */
data class Session(
    val userId: String,
    val deviceId: String,
    val homeserverUrl: String,
    val accessToken: String,
    val refreshToken: String?,
)

/** Summary of a room for the room list. */
data class Room(
    val id: String,
    val displayName: String,
    val isEncrypted: Boolean,
    val lastMessage: Message?,
)

/** Direction of a message relative to the own session. */
enum class MessageDirection { INCOMING, OUTGOING }

/** State of a message from the app's point of view. */
enum class DeliveryState {
    /** successfully sent or received */
    SENT,

    /** received but not decryptable (E2EE) */
    UNDECRYPTABLE,
}

/** A timeline message in domain representation. */
data class Message(
    /** Event or transaction ID (locally stable identifier) */
    val id: String,
    val roomId: String,
    val sender: String,
    val body: String,
    val direction: MessageDirection,
    val state: DeliveryState,
    val timestampMillis: Long,
)
