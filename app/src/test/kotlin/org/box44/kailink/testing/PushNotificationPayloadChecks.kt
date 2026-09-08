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

    Checks.check("fromLatest wählt den Raum mit der jüngsten eingehenden Nachricht") {
        val older = room(
            "!raum-alt:example.org",
            "Alter Raum",
            message("m1", "!raum-alt:example.org", "@bob:example.org", "älter", MessageDirection.INCOMING, 100L),
        )
        val newer = room(
            "!raum-neu:example.org",
            "Neuer Raum",
            message("m2", "!raum-neu:example.org", "@carol:example.org", "neuer", MessageDirection.INCOMING, 200L),
        )

        val payload = PushNotificationPayload.fromLatest(listOf(older, newer))
            ?: throw CheckFailure("Payload vorhanden")

        expectEquals("!raum-neu:example.org", payload.roomId, "Raum-ID")
        expectEquals("Neuer Raum", payload.title, "Titel")
        expectEquals("carol: neuer", payload.text, "Vorschau")
    }

    Checks.check("fromLatest ignoriert nur ausgehende Nachrichten und leere Listen") {
        val outgoingOnly = room(
            "!raum:example.org",
            "Raum",
            message("m1", "!raum:example.org", "@alice:example.org", "von mir", MessageDirection.OUTGOING, 100L),
        )

        expectNull(PushNotificationPayload.fromLatest(emptyList()), "leere Raumliste")
        expectNull(PushNotificationPayload.fromLatest(listOf(outgoingOnly)), "nur ausgehend")
        expectNull(PushNotificationPayload.fromLatest(listOf(room("!r:example.org", "Leer", null))), "ohne Nachricht")
    }

    Checks.check("Nicht entschlüsselbare Nachricht erzeugt Platzhaltertext") {
        val utd = room(
            "!raum:example.org",
            "Raum",
            message("m1", "!raum:example.org", "@bob:example.org", "unlesbar", MessageDirection.INCOMING, 100L, DeliveryState.UNDECRYPTABLE),
        )

        val payload = PushNotificationPayload.fromLatest(listOf(utd))
            ?: throw CheckFailure("Payload vorhanden")

        expectEquals(PushNotificationPayload.UNDECRYPTABLE_TEXT, payload.text, "Platzhalter")
    }

    Checks.check("Fehlender Raumname fällt auf Fallback-Titel zurück") {
        val payload = PushNotificationPayload.from(
            null,
            message("m1", "", "@bob:example.org", "", MessageDirection.INCOMING, 100L),
        )

        expectEquals(PushNotificationPayload.FALLBACK_TITLE, payload.title, "Fallback-Titel")
        expectEquals(PushNotificationPayload.EMPTY_BODY_TEXT, payload.text, "leerer Text")
        expectNull(payload.roomId, "keine Raum-ID")
    }
}
