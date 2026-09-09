package org.box44.kailink.data.push

import org.box44.kailink.domain.ChannelClient
import org.box44.kailink.domain.push.PushState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * JVM-side state machine of the push chain (no Android dependency,
 * JVM-testable). The Android receiver (`KaiLinkPushReceiver`) and the
 * `UnifiedPushRegistrar` call into it; actions run against the
 * [ChannelClient].
 */
class PushController(
    private val channelClient: ChannelClient,
    private val scope: CoroutineScope,
    private val onLog: (String) -> Unit = {},
) {

    private val _state = MutableStateFlow(PushState.NOT_AVAILABLE)
    val state: StateFlow<PushState> = _state.asStateFlow()

    /** Last endpoint successfully registered at the channel (for rotation). */
    @Volatile
    var lastRegisteredEndpoint: String? = null
        private set

    /** Distributor found; the actual registration runs asynchronously. */
    fun onDistributorAvailable() {
        _state.value = PushState.READY
    }

    /** No distributor installed — push stays disabled, app remains usable. */
    fun onNoDistributor() {
        _state.value = PushState.NOT_AVAILABLE
    }

    /**
     * UnifiedPush delivered a new (possibly rotated) endpoint.
     * Every changed endpoint is registered again (re-registration);
     * the registration runs against the channel layer and keeps
     * [lastRegisteredEndpoint] up to date on success.
     */
    fun onNewEndpoint(endpointUrl: String) {
        _state.value = PushState.READY
        scope.launch {
            try {
                channelClient.registerPushEndpoint(endpointUrl)
                lastRegisteredEndpoint = endpointUrl
                _state.value = PushState.REGISTERED
                onLog("Push endpoint registered as Matrix pusher")
            } catch (t: Throwable) {
                _state.value = PushState.FAILED
                onLog("Pusher registration failed: ${t.message}")
            }
        }
    }

    fun onRegistrationFailed(reason: String?) {
        _state.value = PushState.FAILED
        onLog("Push registration failed: ${reason ?: "unknown"}")
    }

    fun onUnregistered() {
        _state.value = PushState.NOT_AVAILABLE
        onLog("Push registration revoked by the distributor")
    }

    /** Push message received → trigger sync (PoC: no notification). */
    fun onMessage() {
        scope.launch {
            try {
                channelClient.syncOnce()
                onLog("Push triggered: sync executed")
            } catch (t: Throwable) {
                onLog("Push sync failed: ${t.message}")
            }
        }
    }
}
