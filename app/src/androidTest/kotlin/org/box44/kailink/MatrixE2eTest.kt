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
 * Zwei-Konten-E2E-Test (Chunk A, unverschlüsselt):
 * Alice = SUT über [MatrixSdkChannelClient], Bob = roher SDK-Client
 * ([RawBobClient]). Bob sendet, Alice muss die Nachricht per Timeline-Polling
 * empfangen; danach Restore-Leg (dispose → restore aus FileSessionStore →
 * rooms() enthält den Raum).
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
            harness.report("Alice angemeldet: ${aliceSession.userId}")

            // 2. Bob = roher SDK-Client. KEIN Live-Sync: der SyncService
            // braucht Sliding Sync (Conduit: VersionIsMissing); Senden und
            // Empfangen laufen hier ueber syncOnce (syncOnceV2).
            val bob = RawBobClient.login(homeserver, bobCreds.username, bobCreds.password, bobDirs.state)
            bobClient = bob
            bob.e2eeInit()
            val bobUserId = bob.userId()
            harness.report("Bob angemeldet: $bobUserId")

            // 3. Alice erstellt unverschlüsselten Raum mit Bob-Einladung.
            val roomName = "kailink-e2e-a-${UUID.randomUUID().toString().take(8)}"
            val roomId = alice.createRoom(roomName, listOf(bobUserId), encrypted = false)
            harness.report("Raum erstellt: $roomId")

            // 4. Bob tritt bei (Einladung annehmen).
            bob.joinRoom(roomId)
            harness.report("Bob beigetreten: $roomId")

            // 5. Alice: Chronik VOR Bobs Nachricht abonnieren (kein Live-Sync:
            // SyncService braucht Sliding Sync, das Conduit nicht bietet;
            // stattdessen treibt syncOnce im Poll-Loop unten den Sync).
            val received = CopyOnWriteArrayList<Message>()
            val collector = scope.launchInCollector(alice) { received.addAll(it) }
            alice.openTimeline(roomId)

            // 6. Bob sendet (Queue via syncOnce flushen) + Alice syncen.
            val body = "e2e-a-${UUID.randomUUID()}"
            bob.sendText(roomId, body)
            bob.syncOnce()
            alice.syncOnce()
            harness.report("Bob hat gesendet: $body")

            // 7. Alice pollt: syncOnce treibt Sync + Send-Queue-Flush, die offene
            // Timeline liefert die Events an den Collector.
            val hit: Message? = withTimeoutOrNull(POLL_TIMEOUT_MILLIS) {
                var found: Message? = null
                while (found == null) {
                    val match = received.firstOrNull { it.body == body }
                    if (match != null) {
                        assertEquals(
                            "Unerwarteter DeliveryState für empfangene Nachricht",
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
            checkNotNull(hit) { "Alice hat Bobs Nachricht nicht empfangen (Timeout ${POLL_TIMEOUT_MILLIS} ms, body=$body)" }
            harness.report("Alice hat empfangen: id=${hit.id} state=${hit.state}")

            // 8. Restore-Leg: kein logout! dispose → neu → restore → rooms().
            val savedSession = checkNotNull(FileSessionStore(aliceStoreFile).load()) {
                "FileSessionStore enthält nach Login keine Sitzung"
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
                "Raum $roomId nach Restore nicht in rooms() (${roomIds.size} Räume)",
                roomId in roomIds,
            )
            harness.report("Restore-Leg ok: Raum nach restore() in rooms() enthalten")
            collector.cancel()

            // 9. Chunk C2: Pusher-Registrierung + C2b Zustellbeweis.
            runPushLeg(harness, homeserver, gateway, pusherGateway, aliceCreds, scope, alice2, roomId)

            // 10. Chunk B (verschluesselt): gleicher Ablauf mit encrypted=true,
            // Alice mit e2eeTestConfig (ALL_DEVICES + UNTRUSTED). Laeuft der
            // verschluesselte Pfad an einer Conduit-Grenze auf, bleibt Chunk A
            // das harte Ergebnis und B wird als Diagnose berichtet.
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
     * Chunk C2: registriert einen synthetischen UnifiedPush-Endpoint als
     * Matrix-Pusher (via [PushController.onNewEndpoint] → SUT-Pfad, kein
     * Distributor nötig), assertert ihn über `GET /pushers` (app_id,
     * pushkey, kind=http, data.url=Gateway) und beweist C2b: Nach einer
     * Bob-Nachricht MUSS ntfy einen Publish erhalten (ntfy-Cache-API des
     * Topics = Conduit→ntfy-Zustellung, sonst Fail).
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
        // Push-Trennung: Der Endpoint (pushkey) ist die aus Emulator-Sicht
        // lesbare ntfy-URL (Cache-Pruefung via `gateway`/adb reverse); das
        // Gateway (data.url) zeigt aus Conduit-Sicht ins Container-Netz
        // (`pusherGateway`, via alice2-Konstruktor gesetzt). ntfy parst das
        // Topic aus dem pushkey-Pfad — Host-Anteil ist irrelevant.
        val endpoint = "$gateway/$topic"
        val controller = PushController(alice, scope, harness::report)
        controller.onNewEndpoint(endpoint)
        val deadline = System.currentTimeMillis() + PUSH_TIMEOUT_MILLIS
        while (controller.lastRegisteredEndpoint == null && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(500)
        }
        checkNotNull(controller.lastRegisteredEndpoint) {
            "C2: Pusher-Registrierung schlug fehl (lastRegisteredEndpoint==null)"
        }
        harness.report("C2: Endpoint registriert: $endpoint")

        // GET /pushers-Assert gegen Conduit (Alice-Token aus laufender Sitzung).
        val session = checkNotNull(alice.activeSession) { "C2: keine aktive Alice-Sitzung" }
        val pushersJson = harness.httpGet(
            "${homeserver}/_matrix/client/v3/pushers",
            session.accessToken,
        )
        harness.report("C2: GET /pushers → ${pushersJson.take(400)}")
        assertTrue("C2: pushkey $endpoint nicht in /pushers", pushersJson.contains(endpoint))
        assertTrue("C2: app_id org.box44.kailink nicht in /pushers", pushersJson.contains("org.box44.kailink"))
        assertTrue("C2: gateway $pusherGateway nicht in /pushers", pushersJson.contains(pusherGateway))

        // C2b: Bob-Nachricht → ntfy MUSS einen Publish am Topic zeigen.
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
            harness.report("C2b: Bob hat gesendet: $c2bBody")
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
        harness.report("C2b: ntfy-Antwort: ${lastNtfy.take(400)}")
        assertTrue("C2b: kein Publish bei ntfy für Topic $topic (Conduit→ntfy-Zustellung fehlt)", delivered)
        harness.report("C2b ok: Conduit→ntfy-Zustellung bewiesen (Topic $topic)")
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
            harness.report("E2E B: Alice angemeldet: ${aliceSession.userId}")

            val bob = RawBobClient.login(homeserver, bobCreds.username, bobCreds.password, bobDirs.state)
            bobClient = bob
            bob.e2eeInit()
            harness.report("E2E B: Bob angemeldet: ${bob.userId()}")

            val roomName = "kailink-e2e-b-${UUID.randomUUID().toString().take(8)}"
            val roomId = alice.createRoom(roomName, listOf(bob.userId()), encrypted = true)
            harness.report("E2E B: Raum erstellt (verschluesselt): $roomId")
            try {
                bob.joinRoom(roomId)
                harness.report("E2E B: Bob beigetreten: $roomId")
            } catch (t: Throwable) {
                harness.report("E2E B: Bob-Join FEHLER: ${t.message}")
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
                harness.report("E2E B: Bob-send ok")
            } catch (t: Throwable) {
                harness.report("E2E B: Bob-send FEHLER: ${t.message}")
                throw t
            }
            repeat(3) {
                runCatching { bob.syncOnce() }.onFailure { harness.report("E2E B: sendflush bob.syncOnce: ${it.message}") }
                runCatching { alice.syncOnce() }.onFailure { harness.report("E2E B: sendflush alice.syncOnce: ${it.message}") }
            }
            harness.report("E2E B: Bob hat gesendet: $body")

            val hit: Message? = withTimeoutOrNull(POLL_TIMEOUT_MILLIS) {
                var found: Message? = null
                while (found == null) {
                    found = received.firstOrNull { it.body == body || it.body.contains("verschluesselt") }
                    if (found == null) {
                        runCatching { bob.syncOnce() }
                        runCatching { alice.syncOnce() }
                        kotlinx.coroutines.delay(POLL_STEP_MILLIS)
                    }
                }
                found
            }
            checkNotNull(hit) { "E2E B: Alice hat Bobs Nachricht nicht empfangen (Timeout, body=$body)" }
            assertEquals(
                "E2E B: Nachricht nicht entschluesselt (state=${hit.state}, body=${hit.body})",
                DeliveryState.SENT,
                hit.state,
            )
            assertEquals("E2E B: Body-Mismatch", body, hit.body)
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
