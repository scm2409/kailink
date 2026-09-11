package org.box44.kailink.testing

import org.box44.kailink.data.matrix.MatrixSdkChannelClient
import org.box44.kailink.data.push.PushMessageHandler
import org.box44.kailink.data.push.PushNotificationPayload
import org.box44.kailink.data.push.PushPayload
import org.box44.kailink.domain.model.Session
import org.box44.kailink.domain.model.SlidingSyncMode
import kotlinx.coroutines.runBlocking

/**
 * 0.2.10 push-path diagnostics + mode-discovery-persistence checks (strict
 * TDD — written RED against the pre-0.2.10 handler, which logged neither
 * the slidingSyncMode it used nor the room-list fallback outcome, and
 * whose restore discarded the re-detected sliding sync mode).
 *
 * Contracts asserted here:
 * - Fresh-install cold start (simulated: persisted session, cold channel):
 *   the handler READS the persisted slidingSyncMode and logs it with its
 *   source (`persisted store`), then restores — persist-then-read ordering:
 *   the restore receives the persisted session and the channel ends up with
 *   the persisted mode before any notification resolution.
 * - Warm start: the mode comes from the in-memory session and the log
 *   names that source; a persisted-vs-in-memory mismatch is logged.
 * - The NONE room-list fallback outcome is visible: pushed room ID only,
 *   unread count, selected room, render/suppress and the suppression
 *   reason — never room display names or message bodies (G7-safe for the
 *   shareable debug-log ring).
 * - Mode reconciliation at restore (the 0.2.10 fix): the DISCOVER_NATIVE
 *   detection wins over a stale persisted record (self-healing in both
 *   directions) and the difference is described in one readable line.
 */
fun pushModeDiagnosticsChecks() {

    fun handler(
        client: FakeChannelClient,
        store: FakeSessionStore,
        resolver: suspend (PushPayload?) -> PushNotificationPayload? = { null },
        logs: MutableList<String> = mutableListOf(),
    ): PushMessageHandler = PushMessageHandler(
        channelClient = client,
        sessionStore = store,
        resolveNotification = resolver,
        onLog = { logs += it },
    )

    Checks.check(
        "push handler cold start: reads the persisted slidingSyncMode (source: persisted store) " +
            "before the notification resolution",
    ) {
        runBlocking {
            // Simulated fresh-install cold start: sign-in persisted a NATIVE
            // session; the process was woken by a push (no in-memory session).
            val persisted = TEST_SESSION.copy(slidingSyncMode = SlidingSyncMode.NATIVE)
            val client = FakeChannelClient()
            val store = FakeSessionStore(persisted)
            val logs = mutableListOf<String>()
            var restoredWith: Session? = null
            client.restoreBehavior = { session ->
                restoredWith = session
                session
            }
            val sut = handler(client, store, logs = logs)

            sut.handle("""{"notification":{"room_id":"!room1:example.org","event_id":"${'$'}e1"}}""".toByteArray())

            // Persist-then-read timing (pre-fix contract, must keep holding):
            expectEquals(1, client.restoreCalls, "cold start restores exactly once")
            expectEquals(
                SlidingSyncMode.NATIVE,
                restoredWith?.slidingSyncMode,
                "restore receives the PERSISTED session (persist-then-read)",
            )
            expectEquals(
                SlidingSyncMode.NATIVE,
                client.activeSession?.slidingSyncMode,
                "the active session carries the persisted mode before resolution",
            )
            // New 0.2.10 diagnostic contract:
            expectTrue(
                logs.any { it.contains("persisted slidingSyncMode=NATIVE") },
                "the handler logs the persisted mode read: $logs",
            )
            expectTrue(
                logs.any { it.contains("source: persisted store") },
                "the log names the persisted source: $logs",
            )
        }
    }

    Checks.check("push handler warm start: reads the in-memory slidingSyncMode and names the source") {
        runBlocking {
            val client = FakeChannelClient()
            // The in-memory session carries NATIVE (as a DISCOVER_NATIVE
            // login would produce).
            client.loginBehavior = { url, _, _ ->
                TEST_SESSION.copy(homeserverUrl = url, slidingSyncMode = SlidingSyncMode.NATIVE)
            }
            client.login("https://matrix.example.org", "alice", "pw")
            val logs = mutableListOf<String>()
            val sut = handler(client, FakeSessionStore(TEST_SESSION), logs = logs)

            sut.handle("""{"room_id":"!room1:example.org","event_id":"${'$'}e1"}""".toByteArray())

            expectTrue(
                logs.any { it.contains("slidingSyncMode=NATIVE") },
                "the handler logs the in-memory mode: $logs",
            )
            expectTrue(
                logs.any { it.contains("source: in-memory session") },
                "the log names the in-memory source: $logs",
            )
        }
    }

    Checks.check("push handler warm start: persisted-vs-in-memory mode mismatch is logged") {
        runBlocking {
            val client = FakeChannelClient()
            client.login("https://matrix.example.org", "alice", "pw")
            val logs = mutableListOf<String>()
            val sut = handler(client, FakeSessionStore(TEST_SESSION.copy(slidingSyncMode = SlidingSyncMode.NATIVE)), logs = logs)

            sut.handle("""{"room_id":"!room1:example.org","event_id":"${'$'}e1"}""".toByteArray())

            expectTrue(
                logs.any { it.contains("mismatch persisted=NATIVE") && it.contains("in-memory=NONE") },
                "the mismatch is logged with both sources: $logs",
            )
        }
    }

    Checks.check("push handler: NONE room-list fallback renders and the outcome is visible (room id, unread)") {
        runBlocking {
            val client = FakeChannelClient()
            client.login("https://matrix.example.org", "alice", "pw") // default session mode: NONE
            client.roomsBehavior = { listOf(TEST_ROOM.copy(lastMessage = testMessage("m1"))) }
            val logs = mutableListOf<String>()
            val sut = handler(client, FakeSessionStore(TEST_SESSION), resolver = { null }, logs = logs)

            val raw = """{"notification":{"room_id":"!room1:example.org","counts":{"unread":1}}}""".toByteArray()
            val result = sut.handle(raw)

            expectEquals("!room1:example.org", result?.roomId, "fallback renders the pushed room")
            expectTrue(
                logs.any {
                    it.contains("Push room-list fallback") &&
                        it.contains("mode=NONE") &&
                        it.contains("pushed room=!room1:example.org") &&
                        it.contains("unread=1") &&
                        it.contains("outcome=render")
                },
                "the fallback outcome line carries mode, room id, unread, outcome: $logs",
            )
        }
    }

    Checks.check("push handler: NONE room-list fallback suppression reason is visible") {
        runBlocking {
            val client = FakeChannelClient()
            client.login("https://matrix.example.org", "alice", "pw")
            client.roomsBehavior = {
                listOf(
                    TEST_ROOM.copy(
                        lastMessage = testMessage("m2").copy(direction = org.box44.kailink.domain.model.MessageDirection.OUTGOING),
                    ),
                )
            }
            val logs = mutableListOf<String>()
            val sut = handler(client, FakeSessionStore(TEST_SESSION), resolver = { null }, logs = logs)

            val result = sut.handle("""{"notification":{"room_id":"!room1:example.org","counts":{"unread":1}}}""".toByteArray())

            expectNull(result, "nothing notifiable in the pushed room")
            expectTrue(
                logs.any { it.contains("outcome=suppress") && it.contains("not incoming") },
                "the suppression reason is logged: $logs",
            )
            expectTrue(
                logs.any { it.contains("room=!room1:example.org") && it.contains("unread=1") },
                "the suppress line still carries room id and unread: $logs",
            )
        }
    }

    Checks.check("push handler: fallback logs carry room IDs only — no display names or message bodies") {
        runBlocking {
            val client = FakeChannelClient()
            client.login("https://matrix.example.org", "alice", "pw")
            client.roomsBehavior = {
                listOf(
                    TEST_ROOM.copy(
                        displayName = "Secret Room Name",
                        lastMessage = testMessage("m1").copy(body = "SECRET-BODY"),
                    ),
                )
            }
            val logs = mutableListOf<String>()
            val sut = handler(client, FakeSessionStore(TEST_SESSION), resolver = { null }, logs = logs)

            sut.handle("""{"notification":{"room_id":"!room1:example.org","counts":{"unread":1}}}""".toByteArray())

            expectFalse(
                logs.any { it.contains("Push room-list fallback") && it.contains("Secret Room") },
                "display names never appear in fallback logs: $logs",
            )
            expectFalse(
                logs.any { it.contains("Push room-list fallback") && it.contains("SECRET-BODY") },
                "message bodies never appear in fallback logs: $logs",
            )
        }
    }

    Checks.check("mode reconciliation at restore: the re-detected mode wins and the change is described") {
        val reconciled = MatrixSdkChannelClient.reconcileMode(
            persisted = SlidingSyncMode.NONE,
            detected = SlidingSyncMode.NATIVE,
        )
        expectEquals(SlidingSyncMode.NATIVE, reconciled, "a stale-NONE record self-heals to the detected NATIVE")
        expectEquals(
            SlidingSyncMode.NONE,
            MatrixSdkChannelClient.reconcileMode(
                persisted = SlidingSyncMode.NATIVE,
                detected = SlidingSyncMode.NONE,
            ),
            "the record reflects the last server-verified detection (honest downgrade)",
        )
        expectEquals(
            SlidingSyncMode.NATIVE,
            MatrixSdkChannelClient.reconcileMode(
                persisted = SlidingSyncMode.NATIVE,
                detected = SlidingSyncMode.NATIVE,
            ),
            "equal modes stay unchanged",
        )
        val description = MatrixSdkChannelClient.describeModeReconciliation(
            persisted = SlidingSyncMode.NONE,
            detected = SlidingSyncMode.NATIVE,
        ) ?: throw CheckFailure("a mode change at restore is described in one readable line")
        expectTrue(
            description.contains("persisted=NONE") && description.contains("detected=NATIVE"),
            "the reconciliation line carries both modes: $description",
        )
        expectNull(
            MatrixSdkChannelClient.describeModeReconciliation(
                persisted = SlidingSyncMode.NATIVE,
                detected = SlidingSyncMode.NATIVE,
            ),
            "no description when the modes agree",
        )
    }
}