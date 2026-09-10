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
 *
 * Diagnostic-only observability (0.2.7 investigation, authorized): every
 * registration branch emits DEBUG lines through the injected [onLog] seam
 * (AppGraph wiring: `AppGraph.log` → `DebugLog` + logcat). Behavior is
 * unchanged — logs never alter control flow.
 */
class UnifiedPushRegistrar(
    private val context: Context,
    private val controller: PushController,
    private val onLog: (String) -> Unit = {},
) : PushRegistrationTrigger {

    override fun tryRegister() {
        when (val resolved = UnifiedPush.resolveDefaultDistributor(context)) {
            is ResolvedDistributor.Found -> register(resolved.packageName)
            is ResolvedDistributor.ToSelect -> {
                val distributor = UnifiedPush.getDistributors(context).firstOrNull()
                if (distributor == null) {
                    onLog("UnifiedPush registration: no distributor found (ToSelect, distributor list empty)")
                    controller.onNoDistributor()
                } else {
                    UnifiedPush.saveDistributor(context, distributor)
                    register(distributor)
                }
            }
            ResolvedDistributor.NoneAvailable -> {
                onLog("UnifiedPush registration: no distributor found (NoneAvailable)")
                controller.onNoDistributor()
            }
        }
    }

    fun unregister() {
        UnifiedPush.unregister(context)
    }

    private fun register(distributorPackage: String) {
        controller.onDistributorAvailable()
        onLog("UnifiedPush registration: calling UnifiedPush.register (distributor: $distributorPackage)")
        UnifiedPush.register(context)
        onLog("UnifiedPush registration: UnifiedPush.register returned (distributor: $distributorPackage)")
    }
}
