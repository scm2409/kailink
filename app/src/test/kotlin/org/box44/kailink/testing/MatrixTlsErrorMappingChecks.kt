package org.box44.kailink.testing

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.box44.kailink.data.matrix.MatrixSdkChannelClient
import org.box44.kailink.data.session.FileSessionStore

/**
 * JVM checks (V1) for the TLS/certificate error mapping in
 * MatrixSdkChannelClient (extension of the mapping introduced in 4bdba22):
 *
 * - rustls `InvalidCertificate(Revoked)` / `invalid peer certificate` chains
 *   map to the readable certificate-error message (TLS false-positive fix,
 *   matrix-rust-sdk #6319 / PR #6323).
 * - The existing platform-verifier initialization mapping keeps precedence
 *   and its text (existing mappings are not replaced).
 * - Raw reqwest/hyper details are only logged, not shown to the user.
 * - Non-TLS failures (wrong credentials, plain network errors) keep the
 *   generic mapping.
 */
fun matrixTlsErrorMappingChecks() {
    Checks.check("tls-mapping: revoked-cert chain maps to readable message") {
        val error = RuntimeException(
            "error sending request for url (https://matrix.org/_matrix/client/versions)",
            RuntimeException("client error (Connect): invalid peer certificate: Revoked"),
        )
        val client = newClient()
        expectEquals(MatrixSdkChannelClient.TLS_CERT_FAILED_MESSAGE, client.readableFailure("Sign-in failed", error), "revoked cert message")
    }

    Checks.check("tls-mapping: UniFFI InvalidCertificate details in deep cause map") {
        val error = RuntimeException(
            "msg=Login failed",
            RuntimeException("details=InvalidCertificate(Revoked); invalid peer certificate: Revoked"),
        )
        expectTrue(
            MatrixSdkChannelClient.isTlsCertificateFailure(error),
            "InvalidCertificate(Revoked) details must be detected",
        )
    }

    Checks.check("tls-mapping: unknown-issuer and handshake failures map") {
        expectTrue(
            MatrixSdkChannelClient.isTlsCertificateFailure(RuntimeException("invalid peer certificate: UnknownIssuer")),
            "UnknownIssuer must be detected",
        )
        expectTrue(
            MatrixSdkChannelClient.isTlsCertificateFailure(RuntimeException("tls handshake failure")),
            "handshake failure must be detected",
        )
        expectTrue(
            MatrixSdkChannelClient.isTlsCertificateFailure(RuntimeException("hostname mismatch")),
            "hostname mismatch must be detected",
        )
    }

    Checks.check("tls-mapping: readable message contains no raw reqwest/hyper details") {
        val raw = "error sending request for url (https://matrix.org/_matrix/client/versions): " +
            "client error (Connect): invalid peer certificate: Revoked"
        val message = newClient().readableFailure("Sign-in failed", RuntimeException(raw))
        expectFalse(message.contains("matrix.org"), "raw URL must not leak into user message")
        expectFalse(message.contains("invalid peer certificate"), "raw rustls detail must not leak into user message")
        expectTrue(message.startsWith("Secure connection to the homeserver failed"), "readable prefix required")
    }

    Checks.check("tls-mapping: raw details go to the log sink only") {
        val logs = mutableListOf<String>()
        val error = RuntimeException(
            "error sending request for url (https://matrix.org/_matrix/client/versions)",
            RuntimeException("client error (Connect): invalid peer certificate: Revoked"),
        )
        val client = MatrixSdkChannelClient(
            sessionStore = FileSessionStore(File(createTempDir(), "session.properties")),
            storeDir = createTempDir(),
            cacheDir = createTempDir(),
            scope = CoroutineScope(Dispatchers.Default),
            onLog = { logs += it },
        )
        client.readableFailure("Sign-in failed", error)
        expectTrue(logs.size == 1, "exactly one log line expected, got $logs")
        expectTrue(logs[0].contains("invalid peer certificate: Revoked"), "raw detail must be in the log")
    }

    Checks.check("tls-mapping: platform-verifier init mapping keeps precedence and text") {
        val initPanic = RuntimeException("Expect rustls-platform-verifier to be initialized")
        expectTrue(MatrixSdkChannelClient.isPlatformVerifierInitFailure(initPanic), "init panic must be detected")
        val message = newClient().readableFailure("Sign-in failed", initPanic)
        expectEquals(MatrixSdkChannelClient.TLS_INIT_FAILED_MESSAGE, message, "init mapping text unchanged")
    }

    Checks.check("tls-mapping: non-TLS failures keep the generic mapping") {
        val client = newClient()
        expectEquals(
            "Sign-in failed: Invalid username or password",
            client.readableFailure("Sign-in failed", RuntimeException("Invalid username or password")),
            "credential failure must stay generic",
        )
        expectEquals(
            "Could not connect to homeserver: Connection refused",
            client.readableFailure("Could not connect to homeserver", RuntimeException("Connection refused")),
            "plain network failure must stay generic",
        )
        expectFalse(
            MatrixSdkChannelClient.isTlsCertificateFailure(RuntimeException("Connection refused")),
            "plain network failure must not count as certificate failure",
        )
    }

    Checks.check("tls-mapping: readable certificate message wording") {
        expectEquals(
            "Secure connection to the homeserver failed (certificate error). Check the server address.",
            MatrixSdkChannelClient.TLS_CERT_FAILED_MESSAGE,
            "exact user-facing wording",
        )
    }
}

private fun newClient(): MatrixSdkChannelClient = MatrixSdkChannelClient(
    sessionStore = FileSessionStore(File(createTempDir(), "session.properties")),
    storeDir = createTempDir(),
    cacheDir = createTempDir(),
    scope = CoroutineScope(Dispatchers.Default),
)

private fun createTempDir(): File = java.nio.file.Files.createTempDirectory("kailink-tls-checks").toFile()
