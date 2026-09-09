package org.box44.kailink.data.push

import org.box44.kailink.domain.push.PushRegistrationTrigger

/**
 * Phase-1 replacement for the UnifiedPush connector (principle G5).
 *
 * Simulates the distributor flow locally: distributor available →
 * endpoint delivery → push delivery. The entire push chain
 * ([PushController], pusher registration, sync triggering) remains real
 * and JVM-testable; in Phase 2 the UnifiedPush connector
 * (`UnifiedPushRegistrar` + `KaiLinkPushReceiver`) replaces only this class.
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
        const val SIMULATED_ENDPOINT = "https://push.phase1.local/org-box44-kailink/endpoint"
    }
}
