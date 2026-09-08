package org.box44.kailink.data.push

import android.content.Context
import org.box44.kailink.domain.push.PushRegistrationTrigger
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.ResolvedDistributor

/**
 * Phase-2-Ersatz für [SimulatedPushTrigger] (Grundsatz G5): Die Registrierung
 * läuft über den offiziellen UnifiedPush-Connector gegen einen Distributor
 * (z. B. ntfy). Der Zustandsautomat [PushController] bleibt identisch und
 * JVM-testbar; nur die Auslösung ist jetzt echt.
 *
 * Dokumentierte PoC-Grenze: Sind mehrere Distributoren installiert, aber noch
 * keiner gewählt, wählt der Registrar deterministisch den ersten gemeldeten
 * Distributor (eine nutzerfreundliche Auswahl läuft über die LinkActivity des
 * Connectors und ist bewusst nicht Teil des PoC).
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
