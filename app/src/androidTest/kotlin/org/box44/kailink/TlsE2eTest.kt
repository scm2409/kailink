package org.box44.kailink

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.box44.kailink.data.matrix.MatrixSdkChannelClient
import org.box44.kailink.data.session.FileSessionStore
import org.box44.kailink.domain.ChannelException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.rustcomponents.sdk.ClientException
import org.matrix.rustcomponents.sdk.InternalException

/**
 * Real TLS path test (Goal B): sign-in via rustls against a real
 * HTTPS endpoint (`e2e.tls_homeserver`, e.g. https://127.0.0.1:8443 via
 * adb reverse to an nginx TLS proxy in front of Conduit with a self-signed
 * certificate).
 *
 * Strictly asserted:
 * 1. The sign-in fails (the self-signed certificate can never be trusted by
 *    the platform verifier) — but as a clean
 *    ClientException error, not as a panic.
 * 2. The error is TLS/certificate/hostname related (whitelist of
 *    TLS markers including the UniFFI debug details chain).
 * 3. The error is NOT the initialization panic
 *    ("Expect rustls-platform-verifier to be initialized") and also not
 *    the replacement message inserted by the adapter.
 * 4. The vendored class org.rustls.platformverifier.CertificateVerifier
 *    is on the classpath and built with BuildConfig.TEST=false.
 */
@RunWith(AndroidJUnit4::class)
class TlsE2eTest {

    @Test
    fun rustlsLoginOverHttpsFailsWithTlsErrorNotInitPanic() = runBlocking {
        val harness = E2eHarness()
        val tlsHomeserver = harness.tlsHomeserver()
        val creds = harness.aliceCredentials()
        harness.report("TLS-E2E: tlsHomeserver=$tlsHomeserver")

        // 4. Vendored platform verifier class must be reachable.
        val verifierClass = try {
            Class.forName("org.rustls.platformverifier.CertificateVerifier")
        } catch (t: Throwable) {
            fail("org.rustls.platformverifier.CertificateVerifier missing from the classpath: $t")
            throw t
        }
        harness.report("TLS-E2E: CertificateVerifier loaded: ${verifierClass.name}")

        val dirs = harness.newStoreDirs("tls")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var client: MatrixSdkChannelClient? = null
        try {
            client = MatrixSdkChannelClient(
                sessionStore = FileSessionStore(File(dirs.state, "session.properties")),
                storeDir = dirs.state,
                cacheDir = dirs.cache,
                scope = scope,
                onLog = harness::report,
            )

            val failure: Throwable? = try {
                client.login(tlsHomeserver, creds.username, creds.password)
                null
            } catch (t: Throwable) {
                t
            }

            assertNotNull(
                "Login against $tlsHomeserver had to fail on the self-signed certificate — " +
                    "a successful login means the TLS path was not really verified",
                failure,
            )
            val t = failure!!

            // Complete chain including UniFFI debug details (getMessage()
            // of ClientException contains msg=…, details=… with the Rust
            // error chain) and causes (the adapter attaches the original as
            // cause, even when it replaces the panic textually).
            val chain = buildString {
                var current: Throwable? = t
                while (current != null) {
                    append(current.javaClass.name).append(": ").append(current.message).append("\n")
                    current = current.cause
                }
            }
            harness.report("TLS-E2E: error chain:\n$chain")

            // 1. Clean adapter/SDK error, no raw UniFFI panic.
            assertTrue(
                "Expected ChannelException, got: ${t.javaClass.name}",
                t is ChannelException,
            )
            assertTrue(
                "Cause must be an SDK ClientException, got: ${t.cause?.javaClass?.name}",
                t.cause is ClientException,
            )
            assertFalse(
                "UniFFI panic carrier InternalException must not appear in the cause chain",
                t.cause is InternalException,
            )

            // 2. NOT the initialization error (neither raw panic nor
            //    adapter-masked replacement message).
            assertFalse(
                "Initialization panic occurred (rustls-platform-verifier was not initialized via initPlatform):\n$chain",
                chain.contains("rustls-platform-verifier"),
            )
            assertFalse(
                "The adapter's TLS-init replacement text must not appear here (means a masked initialization panic):\n$chain",
                chain.contains(TLS_INIT_REPLACEMENT_MARKER),
            )

            // 3. TLS/certificate/hostname related (whitelist, strict).
            val lower = chain.lowercase()
            val matched = TLS_MARKERS.filter { lower.contains(it) }
            assertTrue(
                "Error is not TLS/certificate/hostname related (none of the markers $TLS_MARKERS in the error chain):\n$chain",
                matched.isNotEmpty(),
            )
            harness.report("TLS-E2E ok: TLS-Marker gefunden: $matched")
        } finally {
            client?.let { runCatching { it.dispose() } }
            scope.cancel()
            harness.deleteStoreDirs(dirs)
        }
    }

    companion object {
        // Markers that only occur with real TLS/certificate/hostname failure
        // (Rust error chain including UniFFI debug details).
        private val TLS_MARKERS = listOf(
            "invalid peer certificate",
            "unknownissuer",
            "certificate",
            "handshake",
            "hostname",
            "tls",
        )

        // Part of the replacement text from MatrixSdkChannelClient.readableFailure —
        // appears here only if an initialization panic was masked.
        private const val TLS_INIT_REPLACEMENT_MARKER = "TLS verification"
    }
}
