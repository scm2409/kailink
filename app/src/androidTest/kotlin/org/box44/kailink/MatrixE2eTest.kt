package org.box44.kailink

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.box44.kailink.data.matrix.MatrixSdkChannelClient
import org.box44.kailink.data.push.PushController
import org.box44.kailink.data.session.FileSessionStore
import org.box44.kailink.domain.ChannelClient
import org.box44.kailink.domain.ChannelEvent
import org.box44.kailink.domain.model.DeliveryState
import org.box44.kailink.domain.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two-account E2E test (Chunk A, unencrypted):
 * Alice = SUT via [MatrixSdkChannelClient], Bob = raw SDK client
 * ([RawBobClient]). Bob sends, Alice must receive the message via timeline
 * polling; afterwards the restore leg (dispose → restore from FileSessionStore →
 * rooms() contains the room).
 */
@RunWith(AndroidJUnit4::class)
class MatrixE2eTest {

    @Test
    fun gatewayHealthProbe() {
        val harness = E2eHarness()
        val gateway = harness.probeGateway()
        harness.report("Gateway probe reachable=${gateway ?: "none"}")
    }

    @Test
    fun twoAccountTimelineDeliveryUnencrypted() = runBlocking {
        val harness = E2eHarness()
        val aliceCreds = harness.aliceCredentials()
        val bobCreds = harness.bobCredentials()
        val homeserver = harness.requireHomeserver()
        val gateway = harness.requireGateway()
        val pusherGateway = harness.pusherGatewayBase()
        harness.report(
            "E2E A: alice=${aliceCreds.username} homeserver=$homeserver gateway=$gateway pusherGateway=$pusherGateway",
        )

        val aliceDirs = harness.newStoreDirs("alice-a")
        val bobDirs = harness.newStoreDirs("bob-a")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var bobClient: RawBobClient? = null
        var aliceClient: MatrixSdkChannelClient? = null
        try {
            // 1. Alice = SUT.
            val aliceStoreFile = File(aliceDirs.state, "session.properties")
            val alice = MatrixSdkChannelClient(
                sessionStore = FileSessionStore(aliceStoreFile),
                storeDir = aliceDirs.state,
                cacheDir = aliceDirs.cache,
                scope = scope,
                gatewayUrl = "$pusherGateway/_matrix/push/v1/notify",
                onLog = harness::report,
            )
            aliceClient = alice
            val aliceSession = alice.login(homeserver, aliceCreds.username, aliceCreds.password)
            harness.report("Alice signed in: ${aliceSession.userId}")

            // 2. Bob = raw SDK client. NO live sync: the SyncService
            // needs sliding sync (Conduit: VersionIsMissing); sending and
            // receiving run here via syncOnce (syncOnceV2).
            val bob = RawBobClient.login(homeserver, bobCreds.username, bobCreds.password, bobDirs.state)
            bobClient = bob
            bob.e2eeInit()
            val bobUserId = bob.userId()
            harness.report("Bob signed in: $bobUserId")

            // 3. Alice creates an unencrypted room with a Bob invitation.
            val roomName = "kailink-e2e-a-${UUID.randomUUID().toString().take(8)}"
            val roomId = alice.createRoom(roomName, listOf(bobUserId), encrypted = false)
            harness.report("Room created: $roomId")

            // 4. Bob joins (accepts the invitation).
            bob.joinRoom(roomId)
            harness.report("Bob joined: $roomId")

            // 5. Alice: subscribe to the timeline BEFORE Bob's message (no live sync:
            // SyncService needs sliding sync, which Conduit does not offer;
            // instead syncOnce in the poll loop below drives the sync).
            val received = CopyOnWriteArrayList<Message>()
            val collector = scope.launchInCollector(alice) { received.addAll(it) }
            alice.openTimeline(roomId)

            // 6. Bob sends (flush the queue via syncOnce) + Alice syncs.
            val body = "e2e-a-${UUID.randomUUID()}"
            bob.sendText(roomId, body)
            bob.syncOnce()
            alice.syncOnce()
            harness.report("Bob has sent: $body")

            // 7. Alice polls: syncOnce drives sync + send-queue flush; the open
            // timeline delivers the events to the collector.
            val hit: Message? = withTimeoutOrNull(POLL_TIMEOUT_MILLIS) {
                var found: Message? = null
                while (found == null) {
                    val match = received.firstOrNull { it.body == body }
                    if (match != null) {
                        assertEquals(
                            "Unexpected DeliveryState for the received message",
                            DeliveryState.SENT,
                            match.state,
                        )
                        found = match
                    } else {
                        runCatching { alice.syncOnce() }
                        kotlinx.coroutines.delay(POLL_STEP_MILLIS)
                    }
                }
                found
            }
            checkNotNull(hit) { "Alice did not receive Bob's message (timeout ${POLL_TIMEOUT_MILLIS} ms, body=$body)" }
            harness.report("Alice has received: id=${hit.id} state=${hit.state}")

            // 8. Restore leg: no logout! dispose → new → restore → rooms().
            val savedSession = checkNotNull(FileSessionStore(aliceStoreFile).load()) {
                "FileSessionStore contains no session after login"
            }
            alice.dispose()
            val alice2 = MatrixSdkChannelClient(
                sessionStore = FileSessionStore(aliceStoreFile),
                storeDir = aliceDirs.state,
                cacheDir = aliceDirs.cache,
                scope = scope,
                gatewayUrl = "$pusherGateway/_matrix/push/v1/notify",
                onLog = harness::report,
            )
            aliceClient = alice2
            alice2.restore(savedSession)
            alice2.syncOnce()
            val roomIds = alice2.rooms().map { it.id }
            assertTrue(
                "Room $roomId not in rooms() after restore (${roomIds.size} rooms)",
                roomId in roomIds,
            )
            harness.report("Restore leg ok: room contained in rooms() after restore()")
            collector.cancel()

            // 9. Chunk C2: pusher registration + C2b delivery proof.
            runPushLeg(harness, homeserver, gateway, pusherGateway, aliceCreds, scope, alice2, roomId)

            // 10. Chunk B (encrypted): same flow with encrypted=true,
            // Alice with e2eeTestConfig (ALL_DEVICES + UNTRUSTED). If the
            // encrypted path hits a Conduit limit, Chunk A remains the hard
            // result and B is reported as diagnostics.
            runEncryptedLeg(harness, homeserver, gateway, pusherGateway, aliceCreds, bobCreds, scope)
        } finally {
            runCatching { aliceClient?.dispose() }
            bobClient?.let { runCatching { bobClient?.close() } }
            scope.cancel()
            harness.deleteStoreDirs(aliceDirs)
            harness.deleteStoreDirs(bobDirs)
        }
    }

    /**
     * Chunk C2: registers a synthetic UnifiedPush endpoint as a
     * Matrix pusher (via [PushController.onNewEndpoint] → SUT path, no
     * distributor needed), asserts it via `GET /pushers` (app_id,
     * pushkey, kind=http, data.url=gateway) and proves C2b: after a
     * Bob message ntfy MUST have received a publish (ntfy cache API of
     * the topic = Conduit→ntfy delivery, otherwise fail).
     */
    private suspend fun runPushLeg(
        harness: E2eHarness,
        homeserver: String,
        gateway: String,
        pusherGateway: String,
        aliceCreds: E2eCredentials,
        scope: CoroutineScope,
        alice: ChannelClient,
        roomId: String,
    ) {
        val topic = "kailink-e2e-${UUID.randomUUID().toString().take(8)}"
        // Push separation: the endpoint (pushkey) is the ntfy URL readable
        // from the emulator's point of view (cache check via `gateway`/adb reverse); the
        // gateway (data.url) points into the container network from Conduit's
        // point of view (`pusherGateway`, set via the alice2 constructor). ntfy parses the
        // topic from the pushkey path — the host part is irrelevant.
        val endpoint = "$gateway/$topic"
        val controller = PushController(alice, scope, harness::report)
        controller.onNewEndpoint(endpoint)
        val deadline = System.currentTimeMillis() + PUSH_TIMEOUT_MILLIS
        while (controller.lastRegisteredEndpoint == null && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(500)
        }
        checkNotNull(controller.lastRegisteredEndpoint) {
            "C2: pusher registration failed (lastRegisteredEndpoint==null)"
        }
        harness.report("C2: endpoint registered: $endpoint")

        // GET /pushers assert against Conduit (Alice token from the running session).
        val session = checkNotNull(alice.activeSession) { "C2: no active Alice session" }
        val pushersJson = harness.httpGet(
            "${homeserver}/_matrix/client/v3/pushers",
            session.accessToken,
        )
        harness.report("C2: GET /pushers → ${pushersJson.take(400)}")
        assertTrue("C2: pushkey $endpoint not in /pushers", pushersJson.contains(endpoint))
        assertTrue("C2: app_id org.box44.kailink not in /pushers", pushersJson.contains("org.box44.kailink"))
        assertTrue("C2: gateway $pusherGateway not in /pushers", pushersJson.contains(pusherGateway))

        // C2b: Bob message → ntfy MUST show a publish on the topic.
        val bobDirs = harness.newStoreDirs("bob-c2b")
        var bobClient: RawBobClient? = null
        try {
            val bobCreds = harness.bobCredentials()
            val bob = RawBobClient.login(homeserver, bobCreds.username, bobCreds.password, bobDirs.state)
            bobClient = bob
            bob.e2eeInit()
            val c2bBody = "e2e-c2b-${UUID.randomUUID()}"
            bob.sendText(roomId, c2bBody)
            repeat(3) {
                runCatching { bob.syncOnce() }
                runCatching { alice.syncOnce() }
            }
            harness.report("C2b: Bob has sent: $c2bBody")
        } finally {
            runCatching { bobClient?.close() }
            harness.deleteStoreDirs(bobDirs)
        }
        val before = System.currentTimeMillis()
        var delivered = false
        var lastNtfy = ""
        while (System.currentTimeMillis() - before < PUSH_TIMEOUT_MILLIS) {
            lastNtfy = runCatching { harness.httpGetText("$gateway/$topic/json?poll=1") }.getOrDefault("")
            if (lastNtfy.contains("event") || lastNtfy.contains("message")) {
                delivered = true
                break
            }
            kotlinx.coroutines.delay(2_000)
        }
        harness.report("C2b: ntfy response: ${lastNtfy.take(400)}")
        assertTrue("C2b: no publish at ntfy for topic $topic (Conduit→ntfy delivery missing)", delivered)
        harness.report("C2b ok: Conduit→ntfy delivery proven (topic $topic)")
    }

    private suspend fun runEncryptedLeg(
        harness: E2eHarness,
        homeserver: String,
        gateway: String,
        pusherGateway: String,
        aliceCreds: E2eCredentials,
        bobCreds: E2eCredentials,
        scope: CoroutineScope,
    ) {
        val aliceDirs = harness.newStoreDirs("alice-b")
        val bobDirs = harness.newStoreDirs("bob-b")
        var bobClient: RawBobClient? = null
        var aliceClient: MatrixSdkChannelClient? = null
        try {
            val aliceStoreFile = File(aliceDirs.state, "session.properties")
            val alice = MatrixSdkChannelClient(
                sessionStore = FileSessionStore(aliceStoreFile),
                storeDir = aliceDirs.state,
                cacheDir = aliceDirs.cache,
                scope = scope,
                gatewayUrl = "$pusherGateway/_matrix/push/v1/notify",
                e2eeTestConfig = org.box44.kailink.data.matrix.E2eeTestConfig(),
                onLog = harness::report,
            )
            aliceClient = alice
            val aliceSession = alice.login(homeserver, aliceCreds.username, aliceCreds.password)
            harness.report("E2E B: Alice signed in: ${aliceSession.userId}")

            val bob = RawBobClient.login(homeserver, bobCreds.username, bobCreds.password, bobDirs.state)
            bobClient = bob
            bob.e2eeInit()
            harness.report("E2E B: Bob signed in: ${bob.userId()}")

            val roomName = "kailink-e2e-b-${UUID.randomUUID().toString().take(8)}"
            val roomId = alice.createRoom(roomName, listOf(bob.userId()), encrypted = true)
            harness.report("E2E B: room created (encrypted): $roomId")
            try {
                bob.joinRoom(roomId)
                harness.report("E2E B: Bob joined: $roomId")
            } catch (t: Throwable) {
                harness.report("E2E B: Bob-join ERROR: ${t.message}")
                throw t
            }
            repeat(2) {
                runCatching { bob.syncOnce() }.onFailure { harness.report("E2E B: bob.syncOnce: ${it.message}") }
                runCatching { alice.syncOnce() }.onFailure { harness.report("E2E B: alice.syncOnce: ${it.message}") }
            }

            val received = CopyOnWriteArrayList<Message>()
            val collector = scope.launchInCollector(alice) { received.addAll(it) }
            alice.openTimeline(roomId)

            val body = "e2e-b-${UUID.randomUUID()}"
            try {
                bob.sendText(roomId, body)
                harness.report("E2E B: bob-send ok")
            } catch (t: Throwable) {
                harness.report("E2E B: bob-send ERROR: ${t.message}")
                throw t
            }
            repeat(3) {
                runCatching { bob.syncOnce() }.onFailure { harness.report("E2E B: sendflush bob.syncOnce: ${it.message}") }
                runCatching { alice.syncOnce() }.onFailure { harness.report("E2E B: sendflush alice.syncOnce: ${it.message}") }
            }
            harness.report("E2E B: Bob has sent: $body")

            val hit: Message? = withTimeoutOrNull(POLL_TIMEOUT_MILLIS) {
                var found: Message? = null
                while (found == null) {
                    found = received.firstOrNull { it.body == body || it.body.contains("encrypted") }
                    if (found == null) {
                        runCatching { bob.syncOnce() }
                        runCatching { alice.syncOnce() }
                        kotlinx.coroutines.delay(POLL_STEP_MILLIS)
                    }
                }
                found
            }
            checkNotNull(hit) { "E2E B: Alice did not receive Bob's message (timeout, body=$body)" }
            assertEquals(
                "E2E B: message not decrypted (state=${hit.state}, body=${hit.body})",
                DeliveryState.SENT,
                hit.state,
            )
            assertEquals("E2E B: body mismatch", body, hit.body)
            harness.report("E2E B ok: id=${hit.id} state=${hit.state}")
            collector.cancel()
        } finally {
            runCatching { aliceClient?.dispose() }
            bobClient?.let { runCatching { bobClient?.close() } }
            harness.deleteStoreDirs(aliceDirs)
            harness.deleteStoreDirs(bobDirs)
        }
    }

    private fun CoroutineScope.launchInCollector(
        client: MatrixSdkChannelClient,
        onUpdate: (List<Message>) -> Unit,
    ): kotlinx.coroutines.Job = client.events
        .onEach { event ->
            if (event is ChannelEvent.TimelineUpdated) onUpdate(event.messages)
        }
        .launchIn(this)

    companion object {
        private const val POLL_TIMEOUT_MILLIS = 60_000L
        private const val POLL_STEP_MILLIS = 1_000L
        private const val PUSH_TIMEOUT_MILLIS = 30_000L
    }
}
