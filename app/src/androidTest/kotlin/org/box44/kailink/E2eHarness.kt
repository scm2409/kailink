package org.box44.kailink

import android.os.Bundle
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import org.junit.AssumptionViolatedException

/**
 * Tool for real Matrix E2E instrumentation tests.
 *
 * Credentials come exclusively from the instrumentation arguments, e.g.:
 * `adb shell am instrument -e e2e.homeserver http://host:6167 -e e2e.username alice -e e2e.password …`
 * If they are missing, an [AssumptionViolatedException] is thrown — the test is
 * then skipped instead of failing. No fake login, no E2EE simulation.
 *
 * Push separation (Chunk C2b): the pusher gateway base (`e2e.pusher_gateway`,
 * e.g. `http://kailink-e2e-ntfy` = the Conduit container's view of
 * ntfy in the shared Podman network) is the URL that Conduit calls via HTTP.
 * The ntfy cache verifiable from the emulator's point of view runs via
 * `requireGateway()` (adb reverse to 127.0.0.1). If the optional
 * `e2e.pusher_gateway` argument is absent, the gateway base is used.
 */
class E2eHarness(
    private val arguments: Bundle = InstrumentationRegistry.getArguments(),
) {

    fun aliceCredentials(): E2eCredentials = credentials("e2e.alice.username", "e2e.alice.password")

    fun bobCredentials(): E2eCredentials = credentials("e2e.bob.username", "e2e.bob.password")

    fun requireHomeserver(): String = arguments.getString(ARG_HOMESERVER)?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw AssumptionViolatedException("e2e.homeserver is missing")

    /**
     * HTTPS homeserver endpoint for the TLS path test (rustls), e.g.
     * `https://127.0.0.1:8443` (via adb reverse to a real TLS server
     * with a self-signed certificate). If the argument is missing, the test
     * is skipped instead of failing.
     */
    fun tlsHomeserver(): String = arguments.getString(ARG_TLS_HOMESERVER)?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw AssumptionViolatedException("e2e.tls_homeserver is missing")

    /** Base URL of the Matrix push gateway from Conduit's point of view (container network). */
    fun pusherGatewayBase(): String =
        arguments.getString(ARG_PUSHER_GATEWAY)?.trim()?.takeIf { it.isNotEmpty() }
            ?: probeGateway()
            ?: throw AssumptionViolatedException("e2e.pusher_gateway is missing and no gateway reachable")

    private fun credentials(userKey: String, passwordKey: String): E2eCredentials {
        val homeserverUrl = requireHomeserver()
        val username = arguments.getString(userKey)?.trim().orEmpty()
        val password = arguments.getString(passwordKey).orEmpty()
        val missing = mutableListOf<String>()
        if (username.isEmpty()) missing.add(userKey)
        if (password.isEmpty()) missing.add(passwordKey)
        if (missing.isNotEmpty()) {
            throw AssumptionViolatedException(
                "E2E credentials missing; instrumentation arguments required: $missing",
            )
        }
        return E2eCredentials(
            homeserverUrl = homeserverUrl,
            username = username,
            password = password,
        )
    }

    fun probeGateway(timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS): String? {
        for (candidate in GATEWAY_CANDIDATES) {
            if (isReachable(candidate, timeoutMillis)) return candidate
        }
        return null
    }

    fun requireGateway(timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS): String {
        return probeGateway(timeoutMillis)
            ?: throw AssumptionViolatedException(
                "No gateway reachable (candidates: ${GATEWAY_CANDIDATES.joinToString()})",
            )
    }

    /** GET with Bearer token (for C2: GET /pushers against Conduit). */
    fun httpGet(url: String, accessToken: String, timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS): String {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer " + accessToken)
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw AssertionError("GET " + url + " -> HTTP " + code + ": " + body.take(300))
            return body
        } finally {
            connection.disconnect()
        }
    }

    /** GET without auth as text (for C2b: ntfy topic cache). */
    fun httpGetText(url: String, timeoutMillis: Int = 10_000): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.requestMethod = "GET"
            val code = connection.responseCode
            if (code !in 200..299) return "HTTP " + code
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * POST a body as JSON (for the fresh-install leg: publish the exact
     * notify body the homeserver would POST to a pusher's endpoint URL —
     * ntfy publishes the whole body to the topic).
     */
    fun httpPost(url: String, body: String, timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS): Int {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw AssertionError("POST $url -> HTTP $code: ${error.take(300)}")
            }
            return code
        } finally {
            connection.disconnect()
        }
    }

    fun report(message: String) {
        Log.i(TAG, message)
        println("$TAG: $message")
    }

    fun newStoreDirs(name: String): E2eStoreDirs {
        val root = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "e2e-$name-${UUID.randomUUID()}",
        )
        val state = File(root, "state").apply { mkdirs() }
        val cache = File(root, "cache").apply { mkdirs() }
        return E2eStoreDirs(state = state, cache = cache)
    }

    fun deleteStoreDirs(dirs: E2eStoreDirs) {
        dirs.state.parentFile?.deleteRecursively()
    }

    private fun isReachable(gatewayUrl: String, timeoutMillis: Int): Boolean {
        val connection = URL(gatewayUrl).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            return connection.responseCode > 0
        } catch (t: Throwable) {
            report("Gateway not reachable: $gatewayUrl (${t.message ?: t.javaClass.simpleName})")
            return false
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        // 127.0.0.1 first: tunneled to the devbox host via "adb reverse"
        // (otherwise the emulator loopback only reaches the emulator itself).
        val GATEWAY_CANDIDATES = listOf(
            "http://127.0.0.1:8090",
            "http://10.0.2.2:8090",
            "http://192.168.42.20:8090",
        )

        private const val TAG = "KaiLinkE2E"
        private const val ARG_HOMESERVER = "e2e.homeserver"
        private const val ARG_TLS_HOMESERVER = "e2e.tls_homeserver"
        private const val ARG_PUSHER_GATEWAY = "e2e.pusher_gateway"
        private const val ARG_USERNAME = "e2e.username"
        private const val ARG_PASSWORD = "e2e.password"
        private const val DEFAULT_TIMEOUT_MILLIS = 3_000
    }
}

data class E2eCredentials(
    val homeserverUrl: String,
    val username: String,
    val password: String,
)

data class E2eStoreDirs(
    val state: File,
    val cache: File,
)
