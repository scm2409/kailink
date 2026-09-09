package org.box44.kailink.di

import android.content.Context
import org.box44.kailink.data.matrix.MatrixSdkChannelClient
import org.box44.kailink.data.push.PushController
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
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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

    val channelClient: ChannelClient = MatrixSdkChannelClient(
        sessionStore = sessionStore,
        storeDir = File(appContext.filesDir, "matrix/store"),
        cacheDir = File(appContext.cacheDir, "matrix/cache"),
        scope = appScope,
        gatewayUrl = pushConfiguration.gatewayUrl,
        onLog = ::log,
    )

    val pushController: PushController = PushController(
        channelClient = channelClient,
        scope = appScope,
        onLog = ::log,
    )

    val pushTrigger: PushRegistrationTrigger = UnifiedPushRegistrar(appContext, pushController)

    val pushState get() = pushController.state

    /** Speech seams: deliberately no-op (docs/features/sprache.md). */
    val speechTranscriber: SpeechTranscriber = NoOpSpeechTranscriber()
    val speechSpeaker: SpeechSpeaker = NoOpSpeechSpeaker()

    private fun log(message: String) {
        android.util.Log.d("KaiLink", message)
    }
}
