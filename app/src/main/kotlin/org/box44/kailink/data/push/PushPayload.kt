package org.box44.kailink.data.push

/**
 * Pure payload of an incoming UnifiedPush message (no Android types,
 * JVM-testable).
 *
 * In the real chain the UnifiedPush message bytes are the body the Matrix
 * homeserver POSTed to the push gateway: ntfy publishes the entire notify
 * body to the topic (server_matrix.go), so the app receives the wrapped
 * form `{"notification":{"event_id":…,"room_id":…,"counts":{…},…}}`
 * (same format the Element X push providers parse; with `event_id_only`
 * pusher format the notification carries no message content).
 *
 * The parser scans for the known fields instead of a full JSON grammar —
 * order-independent, tolerant to unknown fields (`devices`, `prio`, …);
 * documented PoC limit instead of adding a JSON dependency. A `null`
 * result means "not a Matrix push" (e.g. a plain ntfy message): the
 * receiver must still perform the wake-up sync in that case.
 */
data class PushPayload(
    val roomId: String?,
    val eventId: String?,
    val unread: Int?,
) {
    companion object {

        /**
         * Parses the raw UnifiedPush message bytes; `null` if neither
         * `room_id` nor `event_id` is present (invalid or foreign payload).
         */
        fun parse(message: ByteArray?): PushPayload? {
            if (message == null || message.isEmpty()) return null
            val text = message.toString(Charsets.UTF_8)
            val eventId = stringField(text, "event_id")
            val roomId = stringField(text, "room_id")
            val unread = intField(text, "unread")
            if (eventId == null && roomId == null) return null
            return PushPayload(roomId = roomId, eventId = eventId, unread = unread)
        }

        private fun stringField(text: String, name: String): String? =
            Regex("\"" + name + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .find(text)
                ?.groupValues
                ?.get(1)
                ?.let(::unescape)

        private fun intField(text: String, name: String): Int? =
            Regex("\"" + name + "\"\\s*:\\s*(-?\\d+)")
                .find(text)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()

        private fun unescape(value: String): String {
            // Single left-to-right pass, so an escaped backslash is never
            // re-interpreted as the start of a new escape sequence.
            val result = StringBuilder(value.length)
            var index = 0
            while (index < value.length) {
                val character = value[index]
                if (character != '\\' || index + 1 >= value.length) {
                    result.append(character)
                    index++
                    continue
                }
                when (val next = value[index + 1]) {
                    '"' -> result.append('"')
                    '\\' -> result.append('\\')
                    '/' -> result.append('/')
                    'n' -> result.append('\n')
                    'r' -> result.append('\r')
                    't' -> result.append('\t')
                    else -> {
                        result.append(character)
                        result.append(next)
                    }
                }
                index += 2
            }
            return result.toString()
        }
    }
}
