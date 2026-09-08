package at.d71.kailink.data.push

import at.d71.kailink.domain.push.PushRegistrationTrigger

/**
 * Phase-1-Ersatz für den UnifiedPush-Connector (Grundsatz G5).
 *
 * Simuliert den Distributor-Ablauf lokal: Distributor vorhanden →
 * Endpoint-Zustellung → Push-Zustellung. Die komplette Push-Kette
 * ([PushController], Pusher-Registrierung, Sync-Auslösung) bleibt dabei
 * echt und ist JVM-testbar; in Phase 2 ersetzt der UnifiedPush-Connector
 * (`UnifiedPushRegistrar` + `KaiLinkPushReceiver`) nur diese Klasse.
 */
class SimulatedPushTrigger(
    private val controller: PushController,
) : PushRegistrationTrigger {

    override fun tryRegister() {
        controller.onDistributorAvailable()
        controller.onNewEndpoint(SIMULATED_ENDPOINT)
    }

    fun unregister() {
        controller.onUnregistered()
    }

    fun simulateIncomingPush() {
        controller.onMessage()
    }

    private companion object {
        const val SIMULATED_ENDPOINT = "https://push.phase1.local/at-d71-kailink/endpoint"
    }
}
