package org.box44.kailink.testing

import org.box44.kailink.data.push.PushNotificationPayload
import org.box44.kailink.domain.model.DeliveryState
import org.box44.kailink.domain.model.Message
import org.box44.kailink.domain.model.MessageDirection
import org.box44.kailink.domain.model.Room

fun pushNotificationPayloadChecks() {

    fun room(id: String, name: String, message: Message?): Room =
        Room(id = id, displayName = name, isEncrypted = false, lastMessage = message)

    fun message(id: String, roomId: String, sender: String, body: String, direction: MessageDirection, timestamp: Long, state: DeliveryState = DeliveryState.SENT): Message =
        Message(
            id = id,
            roomId = roomId,
            sender = sender,
            body = body,
            direction = direction,
            state = state,
            timestampMillis = timestamp,
        )

    Checks.check("fromLatest picks the room with the most recent incoming message") {
        val older = room(
            "!room-old:example.org",
            "Old room",
            message("m1", "!room-old:example.org", "@bob:example.org", "older", MessageDirection.INCOMING, 100L),
        )
        val newer = room(
            "!room-new:example.org",
            "New room",
            message("m2", "!room-new:example.org", "@carol:example.org", "newer", MessageDirection.INCOMING, 200L),
        )

        val payload = PushNotificationPayload.fromLatest(listOf(older, newer))
            ?: throw CheckFailure("Payload present")

        expectEquals("!room-new:example.org", payload.roomId, "Room ID")
        expectEquals("New room", payload.title, "Title")
        expectEquals("carol: newer", payload.text, "Preview")
    }

    Checks.check("fromLatest ignores outgoing-only messages and empty lists") {
        val outgoingOnly = room(
            "!room:example.org",
            "Room",
            message("m1", "!room:example.org", "@alice:example.org", "from me", MessageDirection.OUTGOING, 100L),
        )

        expectNull(PushNotificationPayload.fromLatest(emptyList()), "empty room list")
        expectNull(PushNotificationPayload.fromLatest(listOf(outgoingOnly)), "outgoing only")
        expectNull(PushNotificationPayload.fromLatest(listOf(room("!r:example.org", "Empty", null))), "without message")
    }

    Checks.check("Undecryptable message produces placeholder text") {
        val utd = room(
            "!room:example.org",
            "Room",
            message("m1", "!room:example.org", "@bob:example.org", "unreadable", MessageDirection.INCOMING, 100L, DeliveryState.UNDECRYPTABLE),
        )

        val payload = PushNotificationPayload.fromLatest(listOf(utd))
            ?: throw CheckFailure("Payload present")

        expectEquals(PushNotificationPayload.UNDECRYPTABLE_TEXT, payload.text, "Placeholder")
    }

    Checks.check("Missing room name falls back to the fallback title") {
        val payload = PushNotificationPayload.from(
            null,
            message("m1", "", "@bob:example.org", "", MessageDirection.INCOMING, 100L),
        )

        expectEquals(PushNotificationPayload.FALLBACK_TITLE, payload.title, "Fallback title")
        expectEquals(PushNotificationPayload.EMPTY_BODY_TEXT, payload.text, "empty text")
        expectNull(payload.roomId, "no room ID")
    }
}
