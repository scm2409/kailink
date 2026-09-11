package org.box44.kailink

import android.view.View
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 1 encrypted-room gate (leg 4).
 *
 * The host gate starts a matrix-nio[e2e] sender container. This test triggers
 * that sender through the adb-reversed endpoint and asserts the outermost
 * effect: a KaiLink notification containing the encrypted message.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedRoomE2eTest {

    @Test
    fun encryptedRoomMessageRendersNotification() = runBlocking {
        val harness = E2eHarness()
        val alice = harness.aliceCredentials()
        val bob = harness.bobCredentials()
        val homeserver = harness.requireHomeserver()
        val sender = harness.encryptedSenderUrl()
        val body = "stage1-encrypted-${System.currentTimeMillis()}"
        ensureSignedIn(homeserver, alice.username, alice.password)
        val expectedDevice = harness.currentAppSession().deviceId
        harness.report(
            "Stage 1 encrypted-room leg: alice=${alice.username} bob=${bob.username} " +
                "homeserver=$homeserver sender=$sender expected_device=$expectedDevice body=$body",
        )

        val (senderStatus, senderBody) = harness.httpPostResponse(
            "$sender/send",
            "{\"body\":\"$body\",\"expected_device\":\"$expectedDevice\"}",
            timeoutMillis = 180_000,
        )
        assertTrue("Stage 1 sender did not acknowledge encrypted delivery", senderStatus in 200..299)
        val senderJson = JSONObject(senderBody)
        assertTrue("Stage 1 sender did not report success", senderJson.optBoolean("ok"))
        assertTrue(
            "Stage 1 sender did not verify encrypted wire type",
            senderJson.optString("wire_type") == "m.room.encrypted",
        )
        assertTrue(
            "Stage 1 sender did not report the encrypted wire keys",
            senderJson.optJSONArray("wire_content_keys")?.let { keys ->
                (0 until keys.length()).map { keys.getString(it) }.toSet().containsAll(
                    setOf("algorithm", "ciphertext", "session_id"),
                )
            } == true,
        )

        // Option C: Conduit's autonomous push decision is outside emulator scope.
        // The wake payload, gateway conversion, distributor delivery, app parsing,
        // sync, decrypt, and notification remain real in-gate. A matrix.org device
        // test is the proof point for the autonomous server push decision.
        val session = harness.currentAppSession()
        val expectedGateway = "http://kailink-e2e-ntfy/_matrix/push/v1/notify"
        val expectedAppGateway = "https://ntfy.sh/_matrix/push/v1/notify"
        val pushersResponse = try {
            harness.httpGet(
                "$homeserver/_matrix/client/v3/pushers",
                session.accessToken,
            )
        } catch (error: Throwable) {
            throw AssertionError("Stage 1: GET /pushers failed; cannot select wake pusher", error)
        }
        val pushers = JSONObject(pushersResponse).optJSONArray("pushers")
            ?: throw AssertionError("Stage 1: /pushers missing pushers array")
        val matching = (0 until pushers.length()).map { pushers.getJSONObject(it) }
            .filter { it.optJSONObject("data")?.optString("url") == expectedGateway }
        assertTrue("Stage 1: expected exactly one local pusher", matching.size == 1)
        val localPusher = matching.single()
        val localPushkey = localPusher.optString("pushkey")
        val appPushers = (0 until pushers.length()).map { pushers.getJSONObject(it) }
            .filter {
                it.optString("app_id").trim() == "org.box44.kailink" &&
                    it.optJSONObject("data")?.optString("url") == expectedAppGateway
            }
        val appPushkeys = appPushers.map { it.optString("pushkey") }
        assertTrue(
            "Stage 1: expected exactly one app pusher for $expectedAppGateway; " +
                "matches=${appPushers.size} candidates=$appPushkeys",
            appPushers.size == 1,
        )
        val appPushkey = appPushers.single().optString("pushkey")
        harness.report(
            "Stage 1: wake pusher selection local_test_pusher_pushkey=$localPushkey " +
                "app_pusher_pushkey=$appPushkey app_pusher_pushkey_candidates=$appPushkeys",
        )
        assertTrue("Stage 1: local pusher pushkey/topic is empty", localPushkey.isNotEmpty())
        assertTrue("Stage 1: local pusher pushkey is not an HTTP topic", localPushkey.startsWith("http"))
        assertTrue("Stage 1: expected exactly one app pusher", appPushers.size == 1)
        assertTrue(
            "Stage 1: app pusher pushkey must start with http://127.0.0.1:8090/; value=$appPushkey",
            appPushkey.startsWith("http://127.0.0.1:8090/"),
        )
        val appId = "org.box44.kailink"
        val senderEventId = senderJson.getString("event_id")
        val senderRoomId = senderJson.getString("room_id")
        val wakePayload = JSONObject().put(
            "notification",
            JSONObject()
                .put("event_id", senderEventId)
                .put("room_id", senderRoomId)
                .put("counts", JSONObject().put("unread", 1))
                .put(
                    "devices",
                    org.json.JSONArray().put(
                        JSONObject().put("app_id", appId).put("pushkey", appPushkey),
                    ),
                ),
        )
        assertTrue("Stage 1: sender response event_id is empty", senderEventId.isNotEmpty())
        assertTrue("Stage 1: sender response room_id is empty", senderRoomId.isNotEmpty())
        assertEquals("Stage 1: wake event_id must come from sender response", senderJson.getString("event_id"), senderEventId)
        assertEquals("Stage 1: wake room_id must come from sender response", senderJson.getString("room_id"), senderRoomId)
        harness.report(
            "Stage 1: posting wake payload event_id=$senderEventId room_id=$senderRoomId " +
                "target_pushkey_topic=$appPushkey",
        )
        val (wakeStatus, _) = harness.httpPostResponse(appPushkey, wakePayload.toString(), timeoutMillis = 30_000)
        harness.report("Stage 1: wake payload POST response status=$wakeStatus")
        harness.report("Stage 1: verified encrypted delivery and posted real Matrix wake payload")

        val deadline = System.currentTimeMillis() + 120_000
        var rendered = false
        while (System.currentTimeMillis() < deadline) {
            val notificationDump = dumpsysNotifications()
            if (notificationDump.contains("org.box44.kailink") && notificationDump.contains(body)) {
                rendered = true
                break
            }
            harness.report("Stage 1: notification not rendered yet for $body")
            kotlinx.coroutines.delay(2_000)
        }
        assertTrue(
            "Stage 1: KaiLink did not render the encrypted message notification (body=$body)",
            rendered,
        )
    }

    /** Leg 4 starts a fresh app process, so restore/sign-in is an explicit precondition. */
    private suspend fun ensureSignedIn(
        homeserver: String,
        username: String,
        password: String,
    ) {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            var restored = false
            repeat(30) {
                scenario.onActivity { activity ->
                    restored = signedInAndPushReady(activity)
                }
                if (restored) return@repeat
                kotlinx.coroutines.delay(1_000)
            }
            if (!restored) {
                scenario.onActivity { activity ->
                    activity.findViewById<EditText>(R.id.input_homeserver).setText(homeserver)
                    activity.findViewById<EditText>(R.id.input_username).setText(username)
                    activity.findViewById<EditText>(R.id.input_password).setText(password)
                    activity.findViewById<View>(R.id.btn_login).performClick()
                }
            }
            repeat(60) {
                var ready = false
                scenario.onActivity { ready = signedInAndPushReady(it) }
                if (ready) return
                kotlinx.coroutines.delay(1_000)
            }
            throw AssertionError("Stage 1: KaiLink did not reach the signed-in rooms screen")
        } finally {
            scenario.close()
        }
    }

    private fun signedInAndPushReady(activity: MainActivity): Boolean =
        activity.findViewById<View>(R.id.screen_rooms).visibility == View.VISIBLE &&
            activity.findViewById<android.widget.TextView>(R.id.text_push_state).text.toString() ==
            activity.getString(R.string.push_registered)

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
}
