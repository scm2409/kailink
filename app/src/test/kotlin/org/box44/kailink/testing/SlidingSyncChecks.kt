package org.box44.kailink.testing

import org.box44.kailink.data.session.FileSessionStore
import org.box44.kailink.data.matrix.MatrixSdkChannelClient
import org.box44.kailink.domain.model.Session
import org.box44.kailink.domain.model.SlidingSyncMode
import org.matrix.rustcomponents.sdk.ClientBuildException
import org.matrix.rustcomponents.sdk.SlidingSyncVersion
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import java.io.File
import java.nio.file.Files

/**
 * JVM checks for the sliding sync discovery/configuration behavior
 * (0.2.5-phase1 live-sync fix, docs/decisions.md): the pinned SDK's
 * `sdk-android:26.09.08` defaults the FFI `ClientBuilder` to
 * `SlidingSyncVersionBuilder.None`, so the app must explicitly use the
 * SDK-sanctioned discovery (`DiscoverNative` → `GET /versions` →
 * `org.matrix.simplified_msc3575` → NATIVE) and persist the detected
 * version with the session.
 */
fun slidingSyncChecks() {

    Checks.check("first choice is the SDK-sanctioned discovery (DiscoverNative)") {
        expectEquals(
            SlidingSyncVersionBuilder.DISCOVER_NATIVE,
            MatrixSdkChannelClient.firstChoiceVersionBuilder,
            "first-choice version builder",
        )
    }

    Checks.check("sliding sync version build failure is detected in the error chain") {
        val discoveryError = ClientBuildException.SlidingSyncVersion(
            "`/versions` does not contain `org.matrix.simplified_msc3575` in its " +
                "`unstable_features`, or it's not set to true.",
        )
        expectTrue(
            MatrixSdkChannelClient.isSlidingSyncVersionBuildFailure(discoveryError),
            "direct SlidingSyncVersion error",
        )
        expectTrue(
            MatrixSdkChannelClient.isSlidingSyncVersionBuildFailure(
                RuntimeException("wrapped", discoveryError),
            ),
            "wrapped SlidingSyncVersion error",
        )
        expectFalse(
            MatrixSdkChannelClient.isSlidingSyncVersionBuildFailure(IllegalStateException("unrelated")),
            "unrelated error",
        )
    }

    Checks.check("fallback after a failed discovery build is the SDK default (NONE)") {
        val discoveryError = ClientBuildException.SlidingSyncVersion("server without sliding sync")
        val transportError = IllegalStateException("TLS/versions request failed")
        // The fallback covers both failure classes: a sliding-sync-less
        // server and a transport failure of the discovery requests
        // (Conduit E2E and the self-signed TLS gate must both keep
        // working; the second build carries the pre-0.2.5 semantics).
        expectEquals(SlidingSyncVersionBuilder.NONE, MatrixSdkChannelClient.fallbackVersionBuilder,
            "fallback for the version build error")
        expectEquals(SlidingSyncVersionBuilder.NONE, MatrixSdkChannelClient.fallbackVersionBuilder,
            "same fallback for transport errors")
        expectTrue(
            MatrixSdkChannelClient.firstChoiceVersionBuilder !=
                MatrixSdkChannelClient.fallbackVersionBuilder,
            "fallback must differ from the first choice (no identical retry)",
        )
        expectTrue(discoveryError.message!!.contains("sliding sync"), "sanity")
        expectNotNull(transportError.message, "sanity")
    }

    Checks.check("sliding sync mode maps 1:1 between SDK and domain") {
        expectEquals(
            SlidingSyncMode.NATIVE,
            MatrixSdkChannelClient.sdkVersionToDomainMode(SlidingSyncVersion.NATIVE),
            "NATIVE → NATIVE",
        )
        expectEquals(
            SlidingSyncMode.NONE,
            MatrixSdkChannelClient.sdkVersionToDomainMode(SlidingSyncVersion.NONE),
            "NONE → NONE",
        )
        expectEquals(
            SlidingSyncVersion.NATIVE,
            MatrixSdkChannelClient.domainModeToSdkVersion(SlidingSyncMode.NATIVE),
            "NATIVE → NATIVE (back)",
        )
        expectEquals(
            SlidingSyncVersion.NONE,
            MatrixSdkChannelClient.domainModeToSdkVersion(SlidingSyncMode.NONE),
            "NONE → NONE (back)",
        )
    }

    Checks.check("sliding sync mode is persisted with the session") {
        val root = Files.createTempDirectory("kailink-checks").toFile()
        val store = FileSessionStore(File(root, "session.properties"))
        val native = Session(
            userId = "@alice:example.org",
            deviceId = "DEVICE1",
            homeserverUrl = "https://matrix.org",
            accessToken = "token-123",
            refreshToken = null,
            slidingSyncMode = SlidingSyncMode.NATIVE,
        )
        store.save(native)
        expectEquals(native, store.load(), "NATIVE round trip")
        root.deleteRecursively()
    }

    Checks.check("sessions persisted before 0.2.5 restore with NONE (no mode entry)") {
        val root = Files.createTempDirectory("kailink-checks").toFile()
        val file = File(root, "session.properties")
        // Hand-written legacy properties block (0.2.4-phase1): no
        // slidingSyncMode key.
        file.writeText(
            "userId=@alice:example.org\ndeviceId=DEVICE1\n" +
                "homeserverUrl=https://matrix.example.org\naccessToken=token-123\n",
        )
        val loaded = FileSessionStore(file).load()
        expectNotNull(loaded, "legacy session loads")
        expectEquals(SlidingSyncMode.NONE, loaded?.slidingSyncMode, "legacy sessions degrade to NONE")
        root.deleteRecursively()
    }
}
