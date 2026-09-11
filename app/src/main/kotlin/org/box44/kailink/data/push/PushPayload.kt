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

        /**
         * Diagnostics for a FAILED parse (0.2.10): a single REDACTED line
         * saying WHY the bytes are not a Matrix push payload plus shape
         * metadata only — byte length, JSON validity (structural scan),
         * top-level key NAMES, notification presence. Never field VALUES,
         * tokens, or URLs (G7), so the line is safe for the shareable
         * debug-log ring buffer (same seam as all push logs).
         *
         * `null` when the bytes parse successfully (nothing to explain).
         * The parser behavior itself is unchanged by 0.2.10 (a count-only
         * or foreign body stays `null` — wake-up sync only).
         */
        fun parseFailureSummary(message: ByteArray?): String? {
            if (parse(message) != null) return null
            val bytes = message?.size ?: 0
            val text = message?.toString(Charsets.UTF_8)
            val scanned = text?.let(::scanTopLevelKeys)
            val keyNames = scanned.orEmpty()
            val json = scanned != null
            val why = when {
                message == null || message.isEmpty() -> "empty or missing bytes"
                !json -> "not json"
                stringField(text ?: "", "room_id") == null &&
                    stringField(text ?: "", "event_id") == null ->
                    if (intField(text ?: "", "unread") != null) {
                        "no room_id or event_id (count-only notification)"
                    } else {
                        "no room_id or event_id"
                    }
                else -> "no room_id or event_id"
            }
            val notificationPresence = if (keyNames.contains(NOTIFICATION_KEY)) "yes" else "no"
            return "why=$why; shape: bytes=$bytes, json=$json, keys=[${keyNames.joinToString(",")}], " +
                "notification=$notificationPresence"
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

        private const val NOTIFICATION_KEY = "notification"

        /** Upper bound for the reported top-level key names (bounded diagnostics). */
        private const val MAX_REPORTED_KEYS = 8

        /**
         * String-aware structural scan (no JSON dependency — same offline
         * constraint as [parse]): returns the top-level key NAMES when the
         * text is a balanced JSON object, otherwise `null`. Key names are
         * shape metadata (redaction-safe); values are never read here.
         * Diagnostics only — [parse] stays the single source of truth.
         */
        private fun scanTopLevelKeys(text: String): List<String>? {
            var index = 0
            while (index < text.length && text[index].isWhitespace()) index++
            if (index >= text.length || text[index] != '{') return null
            var depth = 0
            var inString = false
            var escaped = false
            var keyBuilder: StringBuilder? = null
            val keys = mutableListOf<String>()
            while (index < text.length) {
                val character = text[index]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        character == '\\' -> escaped = true
                        character == '"' -> {
                            inString = false
                            val builder = keyBuilder
                            keyBuilder = null
                            // A top-level key is a string directly followed by ':'.
                            var lookahead = index + 1
                            while (lookahead < text.length && text[lookahead].isWhitespace()) lookahead++
                            if (builder != null && lookahead < text.length && text[lookahead] == ':' &&
                                keys.size < MAX_REPORTED_KEYS
                            ) {
                                keys.add(builder.toString())
                            }
                        }
                        else -> keyBuilder?.append(character)
                    }
                } else {
                    when (character) {
                        '"' -> {
                            inString = true
                            escaped = false
                            keyBuilder = if (depth == 1) StringBuilder() else null
                        }
                        '{', '[' -> depth++
                        '}', ']' -> {
                            depth--
                            if (depth < 0) return null
                            if (depth == 0) {
                                // Only whitespace may follow the root object.
                                var lookahead = index + 1
                                while (lookahead < text.length) {
                                    if (!text[lookahead].isWhitespace()) return null
                                    lookahead++
                                }
                                return keys
                            }
                        }
                    }
                }
                index++
            }
            return if (inString || depth != 0) null else keys
        }
    }
}
