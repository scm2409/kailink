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
        // Muss vor der ersten Client-Konstruktion laufen (AppGraph baut den
        // MatrixSdkChannelClient): initialisiert Tokio-Runtime und die
        // rustls-platform-verifier JNI-Bridge (Element X: false, multithreaded).
        // Ohne diesen Aufruf bricht jeder HTTPS-Handshake mit dem Panic
        // "Expect rustls-platform-verifier to be initialized" ab.
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
        // Benachrichtigungskanal früh anlegen (idempotent), damit er in den
        // Systemeinstellungen erscheint, bevor der erste Push eintrifft.
        PushNotifier(this).ensureChannel()
    }

    companion object {
        private const val TAG = "KaiLink"
    }
}
