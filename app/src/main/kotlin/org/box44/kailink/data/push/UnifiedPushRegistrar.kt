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
 * Connector invariant (all branches): `UnifiedPush.saveDistributor` runs
 * BEFORE `UnifiedPush.register` — the connector broadcasts the REGISTER
 * action only to a saved distributor. `ResolvedDistributor.Found` is a
 * resolution result, not connector-store persistence.
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
            is ResolvedDistributor.Found -> {
                // The connector's `register` broadcasts only to a SAVED
                // distributor (`getDistributor(context, store, ack=false)`
                // reads the connector store; KDoc: "saveDistributor must be
                // called before this function"). `Found` is a resolution,
                // not persistence — without saveDistributor the fresh-install
                // REGISTER broadcast never fires (red E2E evidence 2026-09-10,
                // leg 7: "calling UnifiedPush.register" → "register returned"
                // within 53 ms, no distributor reply, no up=1 pusher).
                UnifiedPush.saveDistributor(context, resolved.packageName)
                register(resolved.packageName)
            }
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
