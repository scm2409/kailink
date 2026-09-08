package org.box44.kailink.data.push

import org.box44.kailink.domain.model.DeliveryState
import org.box44.kailink.domain.model.Message
import org.box44.kailink.domain.model.MessageDirection
import org.box44.kailink.domain.model.Room

/**
 * Reine Nutzdaten einer Push-Benachrichtigung (keine Android-Typen,
 * JVM-testbar). Das Android-Rendern passiert in [PushNotifier]; diese
 * Klasse entscheidet nur Titel und Text.
 */
data class PushNotificationPayload(
    val roomId: String?,
    val title: String,
    val text: String,
) {
    companion object {

        const val FALLBACK_TITLE = "KaiLink"

        const val UNDECRYPTABLE_TEXT = "(verschlüsselt — kann nicht entschlüsselt werden)"

        const val EMPTY_BODY_TEXT = "(leere Nachricht)"

        /**
         * Wählt aus der zuletzt synchronisierten Raumliste die Nutzdaten für
         * eine Benachrichtigung: der Raum mit der jüngsten eingehenden
         * Nachricht. `null`, wenn es nichts Benachrichtigungswürdiges gibt
         * (leere Liste, keine eingehenden Nachrichten).
         */
        fun fromLatest(rooms: List<Room>): PushNotificationPayload? {
            val candidate = rooms
                .filter { it.lastMessage?.direction == MessageDirection.INCOMING }
                .maxByOrNull { it.lastMessage?.timestampMillis ?: 0L }
            val message = candidate?.lastMessage ?: return null
            return from(candidate, message)
        }

        /** Nutzdaten aus Raum und Nachricht (Vorschau: „Absender: Text"). */
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
