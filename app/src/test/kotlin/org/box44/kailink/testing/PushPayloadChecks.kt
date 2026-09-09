package org.box44.kailink.testing

import org.box44.kailink.data.push.PushPayload

fun pushPayloadChecks() {

    Checks.check("Parses the wrapped ntfy/Matrix notify body (UnifiedPush message)") {
        val raw = """
            {"notification":{"event_id":"${'$'}evt123","room_id":"!room1:example.org",
             "counts":{"unread":2},"prio":"high","devices":[]}}
        """.trimIndent().toByteArray()

        val payload = PushPayload.parse(raw) ?: throw CheckFailure("Payload parsed")

        expectEquals("${'$'}evt123", payload.eventId, "event_id")
        expectEquals("!room1:example.org", payload.roomId, "room_id")
        expectEquals(2, payload.unread, "counts.unread")
    }

    Checks.check("Parses a flat notification body (without the wrapper)") {
        val raw = """{"event_id":"${'$'}e1","room_id":"!r2:example.org"}""".toByteArray()

        val payload = PushPayload.parse(raw) ?: throw CheckFailure("Payload parsed")

        expectEquals("${'$'}e1", payload.eventId, "event_id")
        expectEquals("!r2:example.org", payload.roomId, "room_id")
        expectNull(payload.unread, "no counts")
    }

    Checks.check("Field detection is order- and whitespace-independent") {
        val raw = """{"room_id":"!r3:example.org" , "event_id":"${'$'}e3"}""".toByteArray()

        val payload = PushPayload.parse(raw) ?: throw CheckFailure("Payload parsed")

        expectEquals("!r3:example.org", payload.roomId, "room_id first")
        expectEquals("${'$'}e3", payload.eventId, "event_id second")
    }

    Checks.check("Non-Matrix payloads are rejected (null)") {
        expectNull(PushPayload.parse(null), "null bytes")
        expectNull(PushPayload.parse(ByteArray(0)), "empty bytes")
        expectNull(PushPayload.parse("not json".toByteArray()), "not json")
        expectNull(PushPayload.parse("""{"ntfy":"plain message"}""".toByteArray()), "foreign payload")
        expectNull(PushPayload.parse("""{"devices":[]}""".toByteArray()), "no room/event id")
    }

    Checks.check("Escaped quote inside a value survives unescaping") {
        val raw = """{"event_id":"${'$'}a\"b","room_id":"!r4:example.org"}""".toByteArray()

        val payload = PushPayload.parse(raw) ?: throw CheckFailure("Payload parsed")

        expectEquals("""${'$'}a"b""", payload.eventId, "unescaped event_id")
        expectEquals("!r4:example.org", payload.roomId, "room_id")
    }
}
