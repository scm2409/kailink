package org.box44.kailink.testing

import org.box44.kailink.data.push.PushMessageHandler
import org.box44.kailink.data.push.PushNotificationPayload
import org.box44.kailink.data.push.PushPayload
import org.box44.kailink.domain.SessionStore
import kotlinx.coroutines.runBlocking

fun pushMessageHandlerChecks() {

    fun handler(
        client: FakeChannelClient,
        store: SessionStore,
        resolver: suspend (PushPayload?) -> PushNotificationPayload? = { null },
        logs: MutableList<String> = mutableListOf(),
    ): PushMessageHandler = PushMessageHandler(
        channelClient = client,
        sessionStore = store,
        resolveNotification = resolver,
        onLog = { logs += it },
    )

    Checks.check("Cold start: restores the stored session, then syncs and resolves") {
        runBlocking {
            val client = FakeChannelClient()
            val store = FakeSessionStore(TEST_SESSION)
            val logs = mutableListOf<String>()
            var resolvedWith: PushPayload? = null
            val expected = PushNotificationPayload.from(TEST_ROOM, testMessage("m1"))
            val handler = handler(client, store, resolver = { payload ->
                resolvedWith = payload
                expected
            }, logs = logs)

            val raw = """{"notification":{"room_id":"!room1:example.org","event_id":"${'$'}e1"}}""".toByteArray()
            val result = handler.handle(raw)

            expectEquals(expected, result, "Resolved payload returned")
            expectEquals(1, client.restoreCalls, "restore once")
            expectEquals(1, client.syncOnceCalls, "syncOnce once")
            expectEquals(TEST_SESSION, client.activeSession, "session restored")
            expectNotNull(resolvedWith, "resolver received parsed payload")
            expectEquals("!room1:example.org", resolvedWith?.roomId, "parsed room_id")
            expectTrue(logs.any { it.contains("restored") }, "restore logged")
        }
    }

    Checks.check("Warm start: no restore, resolver receives the parsed payload") {
        runBlocking {
            val client = FakeChannelClient()
            client.login("https://matrix.example.org", "alice", "pw")
            val store = FakeSessionStore(TEST_SESSION)
            val seen = mutableListOf<PushPayload?>()
            val handler = handler(client, store, resolver = { payload -> seen += payload; null })

            val raw = """{"room_id":"!room9:example.org","event_id":"${'$'}e9"}""".toByteArray()
            val result = handler.handle(raw)

            expectNull(result, "no resolver payload")
            expectEquals(0, client.restoreCalls, "no restore on warm start")
            expectEquals(1, client.syncOnceCalls, "synced")
            expectEquals(1, seen.size, "resolver called once")
            expectEquals("!room9:example.org", seen[0]?.roomId, "parsed room_id")
        }
    }

    Checks.check("Invalid payload still syncs (wake-up semantics), resolver gets null") {
        runBlocking {
            val client = FakeChannelClient()
            client.login("https://matrix.example.org", "alice", "pw")
            val store = FakeSessionStore(TEST_SESSION)
            var seen: PushPayload? = PushPayload(roomId = "unset", eventId = "unset", unread = null)
            val fallback = PushNotificationPayload.from(TEST_ROOM, testMessage("m1"))
            val handler = handler(client, store, resolver = { payload -> seen = payload; fallback })

            val result = handler.handle("this is not a matrix push".toByteArray())

            expectEquals(fallback, result, "fallback payload returned")
            expectEquals(1, client.syncOnceCalls, "sync still executed")
            expectNull(seen, "resolver received null payload")
        }
    }

    Checks.check("Without any session the push is dropped (no sync, no notification)") {
        runBlocking {
            val client = FakeChannelClient()
            val store = FakeSessionStore()
            var resolverCalled = false
            val handler = handler(client, store, resolver = { _ -> resolverCalled = true; null })

            val result = handler.handle("""{"room_id":"!r:example.org"}""".toByteArray())

            expectNull(result, "no payload")
            expectEquals(0, client.restoreCalls, "no restore without stored session")
            expectEquals(0, client.syncOnceCalls, "no sync without session")
            expectFalse(resolverCalled, "resolver not called")
        }
    }

    Checks.check("Restore failure aborts the push without crashing") {
        runBlocking {
            val client = FakeChannelClient().apply {
                restoreBehavior = { throw org.box44.kailink.domain.ChannelException("keystore broken") }
            }
            val store = FakeSessionStore(TEST_SESSION)
            val handler = handler(client, store)

            val result = handler.handle("""{"room_id":"!r:example.org"}""".toByteArray())

            expectNull(result, "no payload after restore failure")
            expectEquals(1, client.restoreCalls, "restore attempted once")
            expectEquals(0, client.syncOnceCalls, "no sync after restore failure")
        }
    }

    Checks.check("Resolver failure falls back to the room list of the pushed room") {
        runBlocking {
            val client = FakeChannelClient()
            client.login("https://matrix.example.org", "alice", "pw")
            client.roomsBehavior = {
                listOf(TEST_ROOM.copy(lastMessage = testMessage("m1")))
            }
            val handler = handler(client, FakeSessionStore(TEST_SESSION), resolver = {
                throw IllegalStateException("resolver broken")
            })

            val result = handler.handle("""{"room_id":"!room1:example.org"}""".toByteArray())

            expectEquals("!room1:example.org", result?.roomId, "fallback used pushed room")
            expectEquals("bob: hello-m1", result?.text, "fallback preview")
        }
    }
}
