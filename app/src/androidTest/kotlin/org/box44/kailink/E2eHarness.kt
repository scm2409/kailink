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
 *
 * Push-Trennung (Chunk C2b): Die Pusher-Gateway-Basis (`e2e.pusher_gateway`,
 * z. B. `http://kailink-e2e-ntfy` = Container-Sicht des Conduit-Containers auf
 * ntfy im geteilten Podman-Netz) ist die URL, die Conduit per HTTP aufruft.
 * Der aus Emulator-Sicht pruefbare ntfy-Cache laeuft weiter ueber
 * `requireGateway()` (adb reverse nach 127.0.0.1). Faellt das optionale
 * `e2e.pusher_gateway`-Argument weg, wird die Gateway-Basis verwendet.
 */
class E2eHarness(
    private val arguments: Bundle = InstrumentationRegistry.getArguments(),
) {

    fun aliceCredentials(): E2eCredentials = credentials("e2e.alice.username", "e2e.alice.password")

    fun bobCredentials(): E2eCredentials = credentials("e2e.bob.username", "e2e.bob.password")

    fun requireHomeserver(): String = arguments.getString(ARG_HOMESERVER)?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw AssumptionViolatedException("e2e.homeserver fehlt")

    /** Basis-URL des Matrix-Push-Gateways aus Conduit-Sicht (Container-Netz). */
    fun pusherGatewayBase(): String =
        arguments.getString(ARG_PUSHER_GATEWAY)?.trim()?.takeIf { it.isNotEmpty() }
            ?: probeGateway()
            ?: throw AssumptionViolatedException("e2e.pusher_gateway fehlt und kein Gateway erreichbar")

    private fun credentials(userKey: String, passwordKey: String): E2eCredentials {
        val homeserverUrl = requireHomeserver()
        val username = arguments.getString(userKey)?.trim().orEmpty()
        val password = arguments.getString(passwordKey).orEmpty()
        val missing = mutableListOf<String>()
        if (username.isEmpty()) missing.add(userKey)
        if (password.isEmpty()) missing.add(passwordKey)
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

    /** GET mit Bearer-Token (fuer C2: GET /pushers gegen Conduit). */
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

    /** GET ohne Auth als Text (fuer C2b: ntfy-Topic-Cache). */
    fun httpGetText(url: String, timeoutMillis: Int = 10_000): String {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
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
        // 127.0.0.1 zuerst: via "adb reverse" auf den Devbox-Host getunnelt
        // (Emulator-Loopback erreicht andernfalls nur den Emulator selbst).
        val GATEWAY_CANDIDATES = listOf(
            "http://127.0.0.1:8090",
            "http://10.0.2.2:8090",
            "http://192.168.42.20:8090",
        )

        private const val TAG = "KaiLinkE2E"
        private const val ARG_HOMESERVER = "e2e.homeserver"
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
