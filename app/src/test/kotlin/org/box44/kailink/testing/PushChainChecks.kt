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

    Checks.check("Registrierung durchläuft READY bis REGISTERED und meldet Endpoint an") {
        val (controller, trigger, client) = wired()

        trigger.tryRegister()

        expectEquals(PushState.REGISTERED, controller.state.value, "Endzustand")
        expectEquals(
            listOf("https://push.phase1.local/org-box44-kailink/endpoint"),
            client.registerEndpointCalls,
            "Endpoint an Kanalschicht",
        )
    }

    Checks.check("Eingehender Push stößt syncOnce an") {
        val (controller, trigger, client) = wired()
        trigger.tryRegister()

        trigger.simulateIncomingPush()

        expectEquals(1, client.syncOnceCalls, "syncOnce-Aufrufe")
        expectEquals(PushState.REGISTERED, controller.state.value, "Zustand unverändert")
    }

    Checks.check("Abmeldung setzt Zustand auf NOT_AVAILABLE") {
        val (controller, trigger, _) = wired()
        trigger.tryRegister()

        trigger.unregister()

        expectEquals(PushState.NOT_AVAILABLE, controller.state.value, "Zustand nach Abmeldung")
    }
}
