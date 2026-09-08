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
 * Werkzeug für echte Matrix-E2E-Instrumentierungstests.
 *
 * Zugangsdaten kommen ausschließlich aus den Instrumentierungs-Argumenten, z. B.:
 * `adb shell am instrument -e e2e.homeserver http://host:6167 -e e2e.username alice -e e2e.password …`
 * Fehlen sie, wird eine [AssumptionViolatedException] geworfen — der Test wird
 * dann übersprungen statt fehlschlagen. Kein Fake-Login, keine E2EE-Simulation.
 */
class E2eHarness(
    private val arguments: Bundle = InstrumentationRegistry.getArguments(),
) {

    fun credentials(): E2eCredentials {
        val homeserverUrl = arguments.getString(ARG_HOMESERVER)?.trim().orEmpty()
        val username = arguments.getString(ARG_USERNAME)?.trim().orEmpty()
        val password = arguments.getString(ARG_PASSWORD).orEmpty()
        val missing = mutableListOf<String>()
        if (homeserverUrl.isEmpty()) missing.add(ARG_HOMESERVER)
        if (username.isEmpty()) missing.add(ARG_USERNAME)
        if (password.isEmpty()) missing.add(ARG_PASSWORD)
        if (missing.isNotEmpty()) {
            throw AssumptionViolatedException(
                "E2E-Zugangsdaten fehlen; Instrumentierungs-Argumente erforderlich: $missing",
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
                "Kein Gateway erreichbar (Kandidaten: ${GATEWAY_CANDIDATES.joinToString()})",
            )
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
            report("Gateway nicht erreichbar: $gatewayUrl (${t.message ?: t.javaClass.simpleName})")
            return false
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        val GATEWAY_CANDIDATES = listOf(
            "http://192.168.42.20:8090",
            "http://10.0.2.2:8090",
        )

        private const val TAG = "KaiLinkE2E"
        private const val ARG_HOMESERVER = "e2e.homeserver"
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
