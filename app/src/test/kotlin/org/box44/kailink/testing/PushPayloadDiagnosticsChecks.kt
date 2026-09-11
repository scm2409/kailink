package org.box44.kailink.testing

import org.box44.kailink.data.push.PushPayload

/**
 * 0.2.10 diagnostics checks for `PushPayload.parse` (strict TDD — written
 * RED against the pre-0.2.10 parser, which failed silently with a bare
 * `null` and no WHY/shape diagnostics).
 *
 * Parser behavior contract (unchanged by 0.2.10 — no parser extension):
 * - The documented notification shape (ntfy publishes the whole notify
 *   body the homeserver POSTed to the gateway, `event_id_only` pusher
 *   format) parses: room_id, event_id, counts.unread.
 * - A count-only notification body (no room_id/event_id) stays
 *   wake-up-only (`null`) — the parser is NOT extended for it (no
 *   observed/documented device evidence that KaiLink must render from it);
 *   the diagnostics only name the shape.
 * - An unknown/foreign shape stays wake-up-only (`null`).
 *
 * New diagnostic contract (the red part):
 * - A FAILED parse exposes a REDACTED shape summary (`parseFailureSummary`):
 *   why + byte length + JSON validity + top-level key NAMES + notification
 *   presence. Never field VALUES/tokens/URLs (G7), so the summary is safe
 *   for the shareable debug-log ring buffer (same seam as all push logs).
 * - A SUCCESSFUL parse produces no failure summary.
 */
fun pushPayloadDiagnosticsChecks() {

    Checks.check("parse: documented wrapped notify body with counts.unread parses (pin)") {
        val raw = """
            {"notification":{"event_id":"${'$'}evt123","room_id":"!room1:example.org",
             "counts":{"unread":2},"prio":"high","devices":[]}}
        """.trimIndent().toByteArray()

        val payload = PushPayload.parse(raw) ?: throw CheckFailure("Payload parsed")

        expectEquals("!room1:example.org", payload.roomId, "room_id")
        expectEquals("${'$'}evt123", payload.eventId, "event_id")
        expectEquals(2, payload.unread, "counts.unread")
        expectNull(PushPayload.parseFailureSummary(raw), "successful parse has no failure summary")
    }

    Checks.check("parse: event_id_only + counts shape without devices parses (pin)") {
        val raw = """{"notification":{"event_id":"${'$'}e1","room_id":"!r2:example.org","counts":{"unread":1}}}"""
            .toByteArray()

        val payload = PushPayload.parse(raw) ?: throw CheckFailure("Payload parsed")

        expectEquals("!r2:example.org", payload.roomId, "room_id")
        expectEquals("${'$'}e1", payload.eventId, "event_id")
        expectEquals(1, payload.unread, "counts.unread")
        expectNull(PushPayload.parseFailureSummary(raw), "successful parse has no failure summary")
    }

    Checks.check("parse: count-only notification stays wake-up-only and the diagnostic names the shape") {
        val raw = """{"notification":{"counts":{"unread":3}}}""".toByteArray()

        expectNull(PushPayload.parse(raw), "count-only body has no room_id/event_id — wake-up only")
        val summary = PushPayload.parseFailureSummary(raw)
            ?: throw CheckFailure("parse failure summary present for a failed parse")
        expectTrue(
            summary.contains("no room_id or event_id"),
            "why names the missing matrix fields: $summary",
        )
        expectTrue(summary.contains("count-only"), "why names the count-only shape: $summary")
        expectTrue(summary.contains("bytes="), "shape carries the byte length: $summary")
        expectTrue(summary.contains("json=true"), "shape carries the JSON validity: $summary")
        expectTrue(summary.contains("keys=[notification]"), "shape carries top-level key names: $summary")
        expectTrue(summary.contains("notification=yes"), "shape carries notification presence: $summary")
    }

    Checks.check("parse: unknown shape stays wake-up-only and the diagnostic names the shape") {
        val raw = """{"ntfy":"plain message"}""".toByteArray()

        expectNull(PushPayload.parse(raw), "foreign payload stays null — wake-up only")
        val summary = PushPayload.parseFailureSummary(raw)
            ?: throw CheckFailure("parse failure summary present for a failed parse")
        expectTrue(
            summary.contains("no room_id or event_id"),
            "why names the missing matrix fields: $summary",
        )
        expectTrue(summary.contains("keys=[ntfy]"), "shape carries top-level key names: $summary")
        expectTrue(summary.contains("notification=no"), "shape carries notification absence: $summary")
    }

    Checks.check("parse failure summary: not-JSON bytes are named as such (redacted)") {
        val raw = "this is not a matrix push".toByteArray()

        expectNull(PushPayload.parse(raw), "not-JSON stays null")
        val summary = PushPayload.parseFailureSummary(raw)
            ?: throw CheckFailure("parse failure summary present for a failed parse")
        expectTrue(summary.contains("not json"), "why names invalid JSON: $summary")
        expectTrue(summary.contains("json=false"), "shape carries the JSON validity: $summary")
        expectTrue(summary.contains("keys=[]"), "no key names without JSON: $summary")
    }

    Checks.check("parse failure summary: redacted — values, tokens, and URLs never appear") {
        val raw = """{"ntfy":"SECRET-TOKEN-VALUE","url":"https://secret.example/path"}""".toByteArray()

        val summary = PushPayload.parseFailureSummary(raw)
            ?: throw CheckFailure("parse failure summary present for a failed parse")

        expectTrue(summary.contains("keys=[ntfy,url]"), "top-level key NAMES are shape metadata: $summary")
        expectFalse(summary.contains("SECRET-TOKEN-VALUE"), "string values never leak: $summary")
        expectFalse(summary.contains("https://"), "URLs never leak: $summary")
        expectFalse(summary.contains("secret.example"), "hosts never leak: $summary")
    }

    Checks.check("parse failure summary: byte length of arbitrary bytes, no crash") {
        val raw = byteArrayOf(0x00, 0x7B, 0x1F, 0x22, 0x0A)

        expectNull(PushPayload.parse(raw), "garbage stays null")
        val summary = PushPayload.parseFailureSummary(raw)
            ?: throw CheckFailure("parse failure summary present for a failed parse")
        expectTrue(summary.contains("bytes=5"), "byte length of the raw message: $summary")
        expectTrue(summary.contains("json=false"), "structural scan rejects garbage: $summary")
    }
}