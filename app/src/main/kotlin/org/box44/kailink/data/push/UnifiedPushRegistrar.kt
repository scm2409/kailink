package org.box44.kailink.data.push

import android.content.Context
import org.box44.kailink.domain.push.PushRegistrationTrigger
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.ResolvedDistributor

/**
 * Phase-2 replacement for [SimulatedPushTrigger] (principle G5): registration
 * runs via the official UnifiedPush connector against a distributor
 * (e.g. ntfy). The state machine [PushController] stays identical and
 * JVM-testable; only the triggering is real now.
 *
 * Documented PoC limitation: if several distributors are installed but none
 * chosen yet, the registrar deterministically picks the first reported
 * distributor (a user-friendly selection runs via the connector's
 * LinkActivity and is deliberately not part of the PoC).
 */
class UnifiedPushRegistrar(
    private val context: Context,
    private val controller: PushController,
) : PushRegistrationTrigger {

    override fun tryRegister() {
        when (UnifiedPush.resolveDefaultDistributor(context)) {
            is ResolvedDistributor.Found -> register()
            is ResolvedDistributor.ToSelect -> {
                val distributor = UnifiedPush.getDistributors(context).firstOrNull()
                if (distributor == null) {
                    controller.onNoDistributor()
                } else {
                    UnifiedPush.saveDistributor(context, distributor)
                    register()
                }
            }
            ResolvedDistributor.NoneAvailable -> controller.onNoDistributor()
        }
    }

    fun unregister() {
        UnifiedPush.unregister(context)
    }

    private fun register() {
        controller.onDistributorAvailable()
        UnifiedPush.register(context)
    }
}
