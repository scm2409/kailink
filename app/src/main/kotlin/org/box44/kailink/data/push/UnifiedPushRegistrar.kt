package org.box44.kailink.data.push

import android.content.Context
import org.box44.kailink.domain.push.PushRegistrationTrigger
import org.unifiedpush.android.connector.UnifiedPush

/**
 * Phase-2-Ersatz für [SimulatedPushTrigger] (Grundsatz G5): Die Registrierung
 * läuft über den offiziellen UnifiedPush-Connector gegen einen Distributor
 * (z. B. ntfy). Der Zustandsautomat [PushController] bleibt identisch und
 * JVM-testbar; nur die Auslösung ist jetzt echt.
 */
class UnifiedPushRegistrar(
    private val context: Context,
    private val controller: PushController,
) : PushRegistrationTrigger {

    override fun tryRegister() {
        UnifiedPush.tryUseCurrentOrDefaultDistributor(context) { found ->
            if (found) {
                controller.onDistributorAvailable()
                UnifiedPush.registerApp(context)
            } else {
                controller.onNoDistributor()
            }
        }
    }

    fun unregister() {
        UnifiedPush.unregisterApp(context)
    }
}
