package org.box44.kailink.di

import android.content.Context
import java.io.File
import org.box44.kailink.data.log.DebugLog
import org.box44.kailink.data.log.SdkLogTailer
import org.box44.kailink.data.matrix.MatrixSdkChannelClient
import org.box44.kailink.data.push.PushController
import org.box44.kailink.data.push.PushMessageHandler
import org.box44.kailink.data.push.UnifiedPushRegistrar
import org.box44.kailink.data.session.AndroidKeystoreSessionStore
import org.box44.kailink.domain.ChannelClient
import org.box44.kailink.domain.SessionStore
import org.box44.kailink.domain.push.PushConfiguration
import org.box44.kailink.domain.push.PushRegistrationTrigger
import org.box44.kailink.domain.speech.NoOpSpeechSpeaker
import org.box44.kailink.domain.speech.NoOpSpeechTranscriber
import org.box44.kailink.domain.speech.SpeechSpeaker
import org.box44.kailink.domain.speech.SpeechTranscriber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Manual dependency wiring of the PoC (deliberately no Hilt/Dagger).
 * Creates exactly one graph per process.
 *
 * Production wiring: real matrix-rust-sdk channel
 * ([MatrixSdkChannelClient]), UnifiedPush registration
 * ([UnifiedPushRegistrar]) and Keystore-encrypted session
 * ([AndroidKeystoreSessionStore]). The Phase-1 reference adapters
 * (`InMemoryChannelClient`, `SimulatedPushTrigger`, `FileSessionStore`)
 * are kept for JVM checks and are not wired.
 */
class AppGraph(private val appContext: Context) {

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Push configuration (gateway/app ID, pure Kotlin). */
    val pushConfiguration: PushConfiguration = PushConfiguration()

    val sessionStore: SessionStore = AndroidKeystoreSessionStore(
        File(appContext.filesDir, "kailink/session.enc"),
    )

    private val matrixChannelClient = MatrixSdkChannelClient(
        sessionStore = sessionStore,
        storeDir = File(appContext.filesDir, "matrix/store"),
        cacheDir = File(appContext.cacheDir, "matrix/cache"),
        scope = appScope,
        gatewayUrl = pushConfiguration.gatewayUrl,
        onLog = ::log,
    )

    val channelClient: ChannelClient = matrixChannelClient

    val pushController: PushController = PushController(
        channelClient = channelClient,
        scope = appScope,
        onLog = ::log,
    )

    val pushTrigger: PushRegistrationTrigger = UnifiedPushRegistrar(appContext, pushController, onLog = ::log)

    /**
     * Real push→notification path of the receiver: payload parse →
     * session restore (cold start) → sync → notification resolution.
     * Resolution uses the SDK NotificationClient for the pushed room/event
     * and falls back to the room list (docs/features/push.md).
     */
    val pushMessageHandler: PushMessageHandler = PushMessageHandler(
        channelClient = channelClient,
        sessionStore = sessionStore,
        resolveNotification = { payload ->
            matrixChannelClient.fetchNotification(payload?.roomId, payload?.eventId)
        },
        onLog = ::log,
    )

    val pushState get() = pushController.state

    /** Speech seams: deliberately no-op (docs/features/sprache.md). */
    val speechTranscriber: SpeechTranscriber = NoOpSpeechTranscriber()
    val speechSpeaker: SpeechSpeaker = NoOpSpeechSpeaker()

    /**
     * Tails the rotating SDK tracing files (KaiLinkApp `initPlatform`
     * `writeToFiles`) into the DebugLog ring buffer — SDK/HTTP client
     * diagnostics reach the "Send log" dump without the app having to
     * run a sync loop (convention: AGENTS.md).
     */
    private val sdkLogTailer = SdkLogTailer(
        directory = File(appContext.cacheDir, SdkLogTailer.TRACE_DIRECTORY),
    )

    init {
        // SDK diagnostics → DebugLog (bounded: see SdkLogTailer). Failures
        // of the tailer itself are logged once, never looped into spam.
        appScope.launch {
            var failureLogged = false
            while (true) {
                delay(SDK_LOG_POLL_MILLIS)
                runCatching { sdkLogTailer.poll() }
                    .onFailure { t ->
                        if (!failureLogged) {
                            failureLogged = true
                            log("SDK log tail failed: ${t.message}")
                        }
                    }
            }
        }
    }

    private fun log(message: String) {
        // Single app-log seam: every message goes to logcat and into the
        // in-app debug log ring buffer (uploaded via "Send log").
        DebugLog.append(message)
        android.util.Log.d("KaiLink", message)
    }

    private companion object {
        /** SDK tracing file poll cadence (bounded ingests, see SdkLogTailer). */
        const val SDK_LOG_POLL_MILLIS = 5_000L
    }
}
