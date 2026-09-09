package org.box44.kailink.domain.model

/**
 * Sliding sync mode of a session, mirroring the matrix-rust-sdk
 * `SlidingSyncVersion` values (`NONE`, `NATIVE`). The SDK detects the
 * version against the homeserver at client build time (`/versions`,
 * `org.matrix.simplified_msc3575`) and stores it in the session; the
 * version must survive session persistence, otherwise the `SyncService`
 * ("live sync") fails with "Sliding sync version is missing"
 * (docs/decisions.md, 0.2.5-phase1).
 */
enum class SlidingSyncMode { NONE, NATIVE }

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
    val slidingSyncMode: SlidingSyncMode = SlidingSyncMode.NONE,
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
