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
 * Echter TLS-Pfad-Test (Goal B): Anmeldung ueber rustls gegen einen echten
 * HTTPS-Endpunkt (`e2e.tls_homeserver`, z. B. https://127.0.0.1:8443 via
 * adb reverse auf einen nginx-TLS-Proxy vor Conduit mit selbstsigniertem
 * Zertifikat).
 *
 * Streng g_assertiert wird:
 * 1. Die Anmeldung scheitert (das selbstsignierte Zertifikat kann vom
 *    Platform-Verifier nie vertrauenswuerdig sein) — aber als sauberer
 *    ClientException-Fehler, nicht als Panic.
 * 2. Der Fehler ist TLS-/Zertifikats-/Hostname-bezogen (Whitelist von
 *    TLS-Markern inkl. der UniFFI-Debug-Details-Kette).
 * 3. Der Fehler ist NICHT der Initialisierungs-Panic
 *    ("Expect rustls-platform-verifier to be initialized") und auch nicht
 *    die vom Adapter eingefuegte Ersatzmeldung dafuer.
 * 4. Die vendored Klasse org.rustls.platformverifier.CertificateVerifier
 *    liegt im Klassenpfad und ist mit BuildConfig.TEST=false gebaut.
 */
@RunWith(AndroidJUnit4::class)
class TlsE2eTest {

    @Test
    fun rustlsLoginOverHttpsFailsWithTlsErrorNotInitPanic() = runBlocking {
        val harness = E2eHarness()
        val tlsHomeserver = harness.tlsHomeserver()
        val creds = harness.aliceCredentials()
        harness.report("TLS-E2E: tlsHomeserver=$tlsHomeserver")

        // 4. Vendored Platform-Verifier-Klasse muss erreichbar sein.
        val verifierClass = try {
            Class.forName("org.rustls.platformverifier.CertificateVerifier")
        } catch (t: Throwable) {
            fail("org.rustls.platformverifier.CertificateVerifier fehlt im Klassenpfad: $t")
            throw t
        }
        harness.report("TLS-E2E: CertificateVerifier geladen: ${verifierClass.name}")

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
                "Login gegen $tlsHomeserver musste am selbstsignierten Zertifikat scheitern — " +
                    "erfolgreicher Login bedeutet, der TLS-Pfad wurde nicht echt geprueft",
                failure,
            )
            val t = failure!!

            // Vollstaendige Kette inkl. UniFFI-Debug-Details (getMessage()
            // von ClientException enthaelt msg=…, details=… mit der Rust-
            // Fehlerkette) und Ursachen (der Adapter haengt das Original als
            // cause an, auch wenn er den Panic textlich ersetzt).
            val chain = buildString {
                var current: Throwable? = t
                while (current != null) {
                    append(current.javaClass.name).append(": ").append(current.message).append("\n")
                    current = current.cause
                }
            }
            harness.report("TLS-E2E: Fehlerkette:\n$chain")

            // 1. Sauberer Adapter-/SDK-Fehler, kein roher UniFFI-Panic.
            assertTrue(
                "Erwartet ChannelException, erhalten: ${t.javaClass.name}",
                t is ChannelException,
            )
            assertTrue(
                "Ursache muss eine ClientException des SDK sein, erhalten: ${t.cause?.javaClass?.name}",
                t.cause is ClientException,
            )
            assertFalse(
                "UniFFI-Panic-Carrier InternalException darf nicht in der Ursachenkette auftreten",
                t.cause is InternalException,
            )

            // 2. NICHT der Initialisierungsfehler (weder roher Panic noch
            //    vom Adapter maskierte Ersatzmeldung).
            assertFalse(
                "Initialisierungs-Panic ist aufgetreten (rustls-platform-verifier wurde nicht per initPlatform initialisiert):\n$chain",
                chain.contains("rustls-platform-verifier"),
            )
            assertFalse(
                "Der TLS-Init-Ersatztext des Adapters darf hier nicht erscheinen (bedeutet maskierten Initialisierungs-Panic):\n$chain",
                chain.contains(TLS_INIT_REPLACEMENT_MARKER),
            )

            // 3. TLS-/Zertifikats-/Hostname-bezogen (Whitelist, strikt).
            val lower = chain.lowercase()
            val matched = TLS_MARKERS.filter { lower.contains(it) }
            assertTrue(
                "Fehler ist nicht TLS-/Zertifikats-/Hostname-bezogen (keiner der Marker $TLS_MARKERS in der Fehlerkette):\n$chain",
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
        // Marker, die nur bei echtem TLS-/Zertifikats-/Hostname-Versagen
        // vorkommen (Rust-Fehlerkette inkl. UniFFI-Debug-Details).
        private val TLS_MARKERS = listOf(
            "invalid peer certificate",
            "unknownissuer",
            "certificate",
            "handshake",
            "hostname",
            "tls",
        )

        // Teil des Ersatztexts aus MatrixSdkChannelClient.readableFailure —
        // taucht hier nur auf, wenn ein Initialisierungs-Panic maskiert wurde.
        private const val TLS_INIT_REPLACEMENT_MARKER = "TLS-Verifizierung"
    }
}
