package org.box44.kailink.di

import android.content.Context
import org.box44.kailink.data.matrix.MatrixSdkChannelClient
import org.box44.kailink.data.push.PushController
import org.box44.kailink.data.push.UnifiedPushRegistrar
import org.box44.kailink.data.session.FileSessionStore
import org.box44.kailink.domain.ChannelClient
import org.box44.kailink.domain.SessionStore
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
 * Manuelle Abhängigkeitsverdrahtung des PoC (bewusst kein Hilt/Dagger).
 * Erzeugt genau einen Graph pro Prozess.
 *
 * Phase 2: echter matrix-rust-sdk-Kanal ([MatrixSdkChannelClient]) und
 * UnifiedPush-Registrierung ([UnifiedPushRegistrar]). Die Phase-1-Adapter
 * (`InMemoryChannelClient`, `SimulatedPushTrigger`) bleiben für JVM-Prüfungen
 * und als dokumentierter Fallback erhalten.
 */
class AppGraph(private val appContext: Context) {

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val sessionStore: SessionStore = FileSessionStore(
        File(appContext.filesDir, "kailink/session.properties"),
    )

    val channelClient: ChannelClient = MatrixSdkChannelClient(
        sessionStore = sessionStore,
        storeDir = File(appContext.filesDir, "matrix/store"),
        cacheDir = File(appContext.cacheDir, "matrix/cache"),
        scope = appScope,
        onLog = ::log,
    )

    val pushController: PushController = PushController(
        channelClient = channelClient,
        scope = appScope,
        onLog = ::log,
    )

    val pushTrigger: PushRegistrationTrigger = UnifiedPushRegistrar(appContext, pushController)

    val pushState get() = pushController.state

    /** Sprach-Nahtstellen: bewusst No-Op (docs/features/sprache.md). */
    val speechTranscriber: SpeechTranscriber = NoOpSpeechTranscriber()
    val speechSpeaker: SpeechSpeaker = NoOpSpeechSpeaker()

    private fun log(message: String) {
        android.util.Log.d("KaiLink", message)
    }
}
