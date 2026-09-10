package org.box44.kailink

import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fresh-install push E2E (0.2.8, leg 7 of scripts/emulator-e2e.sh): proves the
 * full outermost UnifiedPush chain after `pm clear org.box44.kailink` with NO
 * test-side distributor-state provisioning (the script clears only the KaiLink
 * app; ntfy state is untouched). Real UI sign-in drives the registration flow;
 * the test asserts: an `up=1` endpoint registered as a Matrix pusher on
 * Conduit, a real push through the existing chain, and the rendered
 * notification (outermost observable effect).
 *
 * TDD history: this test was red FIRST (0.2.7-phase1 code, gate run
 * 2026-09-10 17:24: failure at the endpoint/pusher poll after 90 s — the
 * registrar's `Found` branch called `UnifiedPush.register` without
 * `saveDistributor`, so the REGISTER broadcast never fired). The
 * saveDistributor-before-register fix in `UnifiedPushRegistrar` turned
 * this leg green.
 *
 * No UiAutomator: the sign-in drives the real login screen through
 * [ActivityScenario] + view clicks (androidTest classpath has no
 * uiautomator artifact and the build config must stay unchanged).
 */
@RunWith(AndroidJUnit4::class)
class FreshInstallPushE2eTest {

    @Test
    fun freshInstallUiSignInRegistersEndpointPusherAndRendersNotification() = runBlocking {
        val harness = E2eHarness()
        val aliceCreds = harness.aliceCredentials()
        val bobCreds = harness.bobCredentials()
        val homeserver = harness.requireHomeserver()
        val gateway = harness.requireGateway()
        harness.report("Fresh-install: alice=${aliceCreds.username} homeserver=$homeserver gateway=$gateway")

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var assertionClient: RawBobClient? = null
        var bobClient: RawBobClient? = null
        val dirsToDelete = mutableListOf<E2eStoreDirs>()
        try {
            // 1. Real UI sign-in (this is the trigger for the registration
            // flow: LoginViewModel calls pushTrigger.tryRegister()).
            uiSignIn(harness, homeserver, aliceCreds.username, aliceCreds.password)

            // 2. Assertion client for the same signed-in user (read-only use:
            // GET /pushers + room creation for the delivery leg).
            val aliceDirs = harness.newStoreDirs("fresh-alice")
            dirsToDelete.add(aliceDirs)
            val alice = RawBobClient.login(
                homeserver,
                aliceCreds.username,
                aliceCreds.password,
                aliceDirs.state,
            )
            assertionClient = alice
            val aliceToken = alice.accessToken()

            // 3. ENDPOINT + PUSHER HOP: poll GET /pushers for the app's real
            // UnifiedPush pusher. The endpoint URL is the pushkey; a real
            // ntfy distributor endpoint carries `?up=1`.
            val endpointRegex = Regex("http[^\"']*up[A-Za-z0-9]{12}\\?up=1")
            val (pushersBody, pushersJson) = pollUntil(
                "fresh-install: no up=1 endpoint registered as Matrix pusher on " +
                    "Conduit after real UI sign-in (endpoint/pusher hop) — the " +
                    "REGISTER broadcast to the distributor never fired",
                ENDPOINT_TIMEOUT_MILLIS,
            ) {
                val body = harness.httpGet(
                    "$homeserver/_matrix/client/v3/pushers",
                    aliceToken,
                )
                val match = endpointRegex.find(body)
                if (match == null) {
                    harness.report(
                        "fresh-install: /pushers poll (no up=1 pushkey yet): ${body.take(300)}",
                    )
                }
                match?.value?.let { body to it }
            }
            harness.report("fresh-install: endpoint registered as pusher: $pushersJson")
            assertTrue(
                "fresh-install: pusher app_id missing",
                pushersBody.contains("org.box44.kailink"),
            )

            // 4. DELIVERY HOP: Bob sends a real Matrix message into a room
            // with Alice; the notify body (the exact {"notification":{…}}
            // shape the homeserver POSTs to the gateway, gate step C2b) is
            // published to the topic of Alice's REAL up* endpoint — no
            // synthetic pusher anywhere in the chain. The event id is the
            // REAL id of Bob's message: the app resolves the notification
            // through the SDK NotificationClient, which cannot render an
            // event that does not exist on the server.
            val roomName = "kailink-fresh-${System.currentTimeMillis()}"
            val bobDirs = harness.newStoreDirs("fresh-bob")
            dirsToDelete.add(bobDirs)
            val bob = RawBobClient.login(
                homeserver,
                bobCreds.username,
                bobCreds.password,
                bobDirs.state,
            )
            bobClient = bob
            bob.e2eeInit()
            val bobUserId = bob.userId()
            val roomId = alice.createRoomForTest(roomName, listOf(bobUserId))
            harness.report("fresh-install: room created: $roomId")
            bob.joinRoom(roomId)
            repeat(3) {
                runCatching { bob.syncOnce() }
                runCatching { alice.syncOnce() }
            }

            val body = "fresh-install-${System.currentTimeMillis()}"
            bob.sendText(roomId, body)
            bob.syncOnce()
            val eventId = bob.latestEventId(roomId)
            harness.report("fresh-install: Bob has sent: $body (event $eventId)")

            // The endpoint URL is the pushkey AND the publish URL the
            // homeserver would POST the notify body to (ntfy publishes the
            // whole body to the topic; the distributor delivers it to the
            // app — the same bytes as gate step C2b).
            val endpoint = pushersJson
            val notifyBody =
                "{\"notification\":{\"event_id\":\"$eventId\"," +
                    "\"room_id\":\"$roomId\",\"counts\":{\"unread\":1}," +
                    "\"devices\":[{\"pushkey\":\"$endpoint\"}]}}"
            harness.httpPost(endpoint, notifyBody)
            harness.report("fresh-install: notify body published to $endpoint (existing chain bytes)")

            // 5. OUTERMOST EFFECT: KaiLink renders the notification.
            pollUntil(
                "fresh-install: KaiLink did not render a notification " +
                    "(delivery leg) — chain bytes were published but nothing appeared",
                RENDER_TIMEOUT_MILLIS,
            ) {
                val dump = dumpsysNotifications()
                val hit = dump.contains("org.box44.kailink") &&
                    dump.contains(body)
                if (!hit) harness.report("fresh-install: no notification yet for $body")
                hit
            }
            harness.report("fresh-install ok: notification rendered (outermost observable effect)")
        } finally {
            runCatching { assertionClient?.close() }
            runCatching { bobClient?.close() }
            scope.cancel()
            dirsToDelete.forEach { harness.deleteStoreDirs(it) }
        }
    }

    /** Drives the real login screen: fields + button, then waits for rooms. */
    private suspend fun uiSignIn(harness: E2eHarness, homeserver: String, username: String, password: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            val server = activity.findViewById<EditText>(R.id.input_homeserver)
            server.setText(homeserver)
            activity.findViewById<EditText>(R.id.input_username).setText(username)
            activity.findViewById<EditText>(R.id.input_password).setText(password)
            activity.findViewById<View>(R.id.btn_login).performClick()
        }
        harness.report("fresh-install: login clicked, waiting for rooms screen")

        val deadline = System.currentTimeMillis() + LOGIN_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            var roomsVisible = false
            runCatching {
                scenario.onActivity { activity ->
                    roomsVisible = activity.findViewById<View>(R.id.screen_rooms).visibility == View.VISIBLE
                }
            }
            if (roomsVisible) {
                var pushStateText = "?"
                runCatching {
                    scenario.onActivity { activity ->
                        pushStateText = (activity.findViewById(R.id.text_push_state) as TextView)
                            .text
                            .toString()
                    }
                }
                harness.report("fresh-install: UI sign-in finished (rooms screen visible, push state: $pushStateText)")
                return
            }
            kotlinx.coroutines.delay(POLL_STEP_MILLIS)
        }
        throw AssertionError("fresh-install: rooms screen never appeared (login failed or timed out)")
    }

    private fun dumpsysNotifications(): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "dumpsys notification --noredact",
        ).let { fd ->
            try {
                java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes().decodeToString() }
            } finally {
                runCatching { fd.close() }
            }
        }

    private suspend fun <T> pollUntil(
        failureMessage: String,
        timeoutMillis: Long,
        step: suspend () -> T?,
    ): T {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            step()?.let { return it }
            kotlinx.coroutines.delay(POLL_STEP_MILLIS)
        }
        throw AssertionError(failureMessage)
    }

    private companion object {
        private const val LOGIN_TIMEOUT_MILLIS = 120_000L
        private const val ENDPOINT_TIMEOUT_MILLIS = 90_000L
        private const val RENDER_TIMEOUT_MILLIS = 60_000L
        private const val POLL_STEP_MILLIS = 2_000L
    }
}
