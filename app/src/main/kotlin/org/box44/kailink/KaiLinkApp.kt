package org.box44.kailink

import android.app.Application
import android.util.Log
import java.io.File
import org.box44.kailink.data.log.DebugLog
import org.box44.kailink.data.log.SdkLogTailer
import org.box44.kailink.data.push.PushNotifier
import org.box44.kailink.di.AppGraph
import org.matrix.rustcomponents.sdk.LogLevel
import org.matrix.rustcomponents.sdk.TracingConfiguration
import org.matrix.rustcomponents.sdk.TracingFileConfiguration
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
        //
        // SDK diagnostics (0.2.5-phase1, see AGENTS.md): the Rust SDK/HTTP
        // client tracing output goes to logcat AND into rotating files under
        // cacheDir/matrix/tracing; SdkLogTailer (AppGraph) tails those files
        // into the DebugLog ring buffer (level-filtered, bounded). The level
        // filtering itself is the SDK's EnvFilter (initPlatform); the
        // bridge additionally keeps only useful levels out of the files.
        try {
            initPlatform(
                TracingConfiguration(
                    logLevel = LogLevel.INFO,
                    traceLogPacks = emptyList(),
                    extraTargets = emptyList(),
                    writeToStdoutOrSystem = true,
                    writeToFiles = TracingFileConfiguration(
                        path = sdkTraceDir().absolutePath,
                        filePrefix = SdkLogTailer.DEFAULT_PREFIX,
                        fileSuffix = SdkLogTailer.DEFAULT_SUFFIX,
                        maxTotalSizeBytes = SDK_TRACE_MAX_TOTAL_BYTES,
                        maxAgeSeconds = SDK_TRACE_MAX_AGE_SECONDS,
                    ),
                    sentryConfig = null,
                ),
                useLightweightTokioRuntime = false,
            )
        } catch (t: Throwable) {
            DebugLog.append("matrix-rust-sdk platform init failed: $t")
            Log.e(TAG, "matrix-rust-sdk platform init failed", t)
        }
        graph = AppGraph(this)
        // Create the notification channel early (idempotent) so it appears
        // in the system settings before the first push arrives.
        PushNotifier(this).ensureChannel()
    }

    /** Directory of the rotating SDK tracing files (SdkLogTailer reads it). */
    private fun sdkTraceDir(): File =
        File(cacheDir, SdkLogTailer.TRACE_DIRECTORY).apply { mkdirs() }

    companion object {
        private const val TAG = "KaiLink"

        /** 2 MiB total for the SDK tracing files (rotating, file layer). */
        private val SDK_TRACE_MAX_TOTAL_BYTES = 2_000_000uL

        /** Tracing file retention: 7 days (SDK default). */
        private val SDK_TRACE_MAX_AGE_SECONDS = 7uL * 24uL * 60uL * 60uL
    }
}
