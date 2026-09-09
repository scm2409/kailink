package org.box44.kailink.data.push

import org.box44.kailink.domain.model.DeliveryState
import org.box44.kailink.domain.model.Message
import org.box44.kailink.domain.model.MessageDirection
import org.box44.kailink.domain.model.Room

/**
 * Pure payload of a push notification (no Android types,
 * JVM-testable). Android rendering happens in [PushNotifier]; this
 * class only decides title and text.
 */
data class PushNotificationPayload(
    val roomId: String?,
    val title: String,
    val text: String,
) {
    companion object {

        const val FALLBACK_TITLE = "KaiLink"

        const val UNDECRYPTABLE_TEXT = "(encrypted — cannot be decrypted)"

        const val EMPTY_BODY_TEXT = "(empty message)"

        /**
         * Picks the payload for a notification from the most recently
         * synchronized room list: the room with the most recent incoming
         * message. `null` if there is nothing worth notifying about
         * (empty list, no incoming messages).
         */
        fun fromLatest(rooms: List<Room>): PushNotificationPayload? {
            val candidate = rooms
                .filter { it.lastMessage?.direction == MessageDirection.INCOMING }
                .maxByOrNull { it.lastMessage?.timestampMillis ?: 0L }
            val message = candidate?.lastMessage ?: return null
            return from(candidate, message)
        }

        /** Payload from room and message (preview: "sender: text"). */
        fun from(room: Room?, message: Message): PushNotificationPayload {
            val title = room?.displayName?.takeIf { it.isNotBlank() }
                ?: room?.id?.takeIf { it.isNotBlank() }
                ?: FALLBACK_TITLE
            val text = when {
                message.state == DeliveryState.UNDECRYPTABLE -> UNDECRYPTABLE_TEXT
                message.body.isBlank() -> EMPTY_BODY_TEXT
                else -> "${senderLabel(message.sender)}: ${message.body}"
            }
            return PushNotificationPayload(
                roomId = room?.id?.takeIf { it.isNotBlank() } ?: message.roomId.ifBlank { null },
                title = title,
                text = text,
            )
        }

        private fun senderLabel(sender: String): String =
            sender.removePrefix("@").substringBefore(':').ifBlank { sender }
    }
}
