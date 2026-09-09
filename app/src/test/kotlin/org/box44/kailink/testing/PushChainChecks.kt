package org.box44.kailink.testing

import org.box44.kailink.data.push.PushController
import org.box44.kailink.data.push.SimulatedPushTrigger
import org.box44.kailink.domain.push.PushState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

fun pushChainChecks() {

    fun wired(): Triple<PushController, SimulatedPushTrigger, FakeChannelClient> {
        val client = FakeChannelClient()
        val controller = PushController(client, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        return Triple(controller, SimulatedPushTrigger(controller), client)
    }

    Checks.check("Registration goes through READY to REGISTERED and reports the endpoint") {
        val (controller, trigger, client) = wired()

        trigger.tryRegister()

        expectEquals(PushState.REGISTERED, controller.state.value, "Final state")
        expectEquals(
            listOf("https://push.phase1.local/org-box44-kailink/endpoint"),
            client.registerEndpointCalls,
            "Endpoint at the channel layer",
        )
    }

    Checks.check("Incoming push triggers syncOnce") {
        val (controller, trigger, client) = wired()
        trigger.tryRegister()

        trigger.simulateIncomingPush()

        expectEquals(1, client.syncOnceCalls, "syncOnce calls")
        expectEquals(PushState.REGISTERED, controller.state.value, "State unchanged")
    }

    Checks.check("Unregistration sets the state to NOT_AVAILABLE") {
        val (controller, trigger, _) = wired()
        trigger.tryRegister()

        trigger.unregister()

        expectEquals(PushState.NOT_AVAILABLE, controller.state.value, "State after unregistration")
    }
}
