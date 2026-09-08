package at.d71.kailink.di

import android.content.Context
import at.d71.kailink.data.channel.InMemoryChannelClient
import at.d71.kailink.data.push.PushController
import at.d71.kailink.data.push.SimulatedPushTrigger
import at.d71.kailink.data.session.FileSessionStore
import at.d71.kailink.domain.ChannelClient
import at.d71.kailink.domain.SessionStore
import at.d71.kailink.domain.push.PushRegistrationTrigger
import at.d71.kailink.domain.speech.NoOpSpeechSpeaker
import at.d71.kailink.domain.speech.NoOpSpeechTranscriber
import at.d71.kailink.domain.speech.SpeechSpeaker
import at.d71.kailink.domain.speech.SpeechTranscriber
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manuelle Abhängigkeitsverdrahtung des PoC (bewusst kein Hilt/Dagger).
 * Erzeugt genau einen Graph pro Prozess.
 */
class AppGraph(appContext: Context) {

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val sessionStore: SessionStore = FileSessionStore(
        File(appContext.filesDir, "kailink/session.properties"),
    )

    val channelClient: ChannelClient = InMemoryChannelClient(sessionStore)

    val pushController: PushController = PushController(
        channelClient = channelClient,
        scope = appScope,
        onLog = ::log,
    )

    val pushTrigger: PushRegistrationTrigger = SimulatedPushTrigger(pushController)

    val pushState get() = pushController.state

    /** Sprach-Nahtstellen: bewusst No-Op (docs/features/sprache.md). */
    val speechTranscriber: SpeechTranscriber = NoOpSpeechTranscriber()
    val speechSpeaker: SpeechSpeaker = NoOpSpeechSpeaker()

    private fun log(message: String) {
        android.util.Log.d("KaiLink", message)
    }
}
