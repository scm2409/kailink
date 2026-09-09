package org.box44.kailink

import android.app.Application
import android.util.Log
import org.box44.kailink.data.push.PushNotifier
import org.box44.kailink.di.AppGraph
import org.matrix.rustcomponents.sdk.LogLevel
import org.matrix.rustcomponents.sdk.TracingConfiguration
import org.matrix.rustcomponents.sdk.initPlatform

class KaiLinkApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // Must run before the first client construction (AppGraph builds the
        // MatrixSdkChannelClient): initializes the Tokio runtime and the
        // rustls-platform-verifier JNI bridge (Element X: false, multithreaded).
        // Without this call every HTTPS handshake aborts with the panic
        // "Expect rustls-platform-verifier to be initialized".
        try {
            initPlatform(
                TracingConfiguration(
                    logLevel = LogLevel.INFO,
                    traceLogPacks = emptyList(),
                    extraTargets = emptyList(),
                    writeToStdoutOrSystem = true,
                    writeToFiles = null,
                    sentryConfig = null,
                ),
                useLightweightTokioRuntime = false,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "matrix-rust-sdk platform init failed", t)
        }
        graph = AppGraph(this)
        // Create the notification channel early (idempotent) so it appears
        // in the system settings before the first push arrives.
        PushNotifier(this).ensureChannel()
    }

    companion object {
        private const val TAG = "KaiLink"
    }
}
