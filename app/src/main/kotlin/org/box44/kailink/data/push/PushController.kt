package org.box44.kailink.data.push

import org.box44.kailink.domain.ChannelClient
import org.box44.kailink.domain.push.PushState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * JVM-seitiger Zustandsautomat der Push-Kette (ohne Android-Abhängigkeit,
 * JVM-testbar). Der Android-Empfänger (`KaiLinkPushReceiver`) und der
 * `UnifiedPushRegistrar` rufen hier ein; die Aktionen laufen gegen den
 * [ChannelClient].
 */
class PushController(
    private val channelClient: ChannelClient,
    private val scope: CoroutineScope,
    private val onLog: (String) -> Unit = {},
) {

    private val _state = MutableStateFlow(PushState.NOT_AVAILABLE)
    val state: StateFlow<PushState> = _state.asStateFlow()

    /** Distributor gefunden; die eigentliche Registrierung läuft asynchron. */
    fun onDistributorAvailable() {
        _state.value = PushState.READY
    }

    /** Kein Distributor installiert — Push bleibt deaktiviert, App nutzbar. */
    fun onNoDistributor() {
        _state.value = PushState.NOT_AVAILABLE
    }

    /** UnifiedPush hat einen neuen (ggf. rotierten) Endpoint geliefert. */
    fun onNewEndpoint(endpointUrl: String) {
        _state.value = PushState.READY
        scope.launch {
            try {
                channelClient.registerPushEndpoint(endpointUrl)
                _state.value = PushState.REGISTERED
                onLog("Push-Endpoint als Matrix-Pusher registriert")
            } catch (t: Throwable) {
                _state.value = PushState.FAILED
                onLog("Pusher-Registrierung fehlgeschlagen: ${t.message}")
            }
        }
    }

    fun onRegistrationFailed(reason: String?) {
        _state.value = PushState.FAILED
        onLog("Push-Registrierung fehlgeschlagen: ${reason ?: "unbekannt"}")
    }

    fun onUnregistered() {
        _state.value = PushState.NOT_AVAILABLE
        onLog("Push-Registrierung vom Distributor aufgehoben")
    }

    /** Push-Nachricht empfangen → Sync anstoßen (PoC: keine Benachrichtigung). */
    fun onMessage() {
        scope.launch {
            try {
                channelClient.syncOnce()
                onLog("Push ausgelöst: Sync ausgeführt")
            } catch (t: Throwable) {
                onLog("Push-Sync fehlgeschlagen: ${t.message}")
            }
        }
    }
}
