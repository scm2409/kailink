package at.d71.kailink.testing

import at.d71.kailink.data.push.PushController
import at.d71.kailink.domain.ChannelException
import at.d71.kailink.domain.push.PushState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

fun pushControllerChecks() {

    fun controllerWith(client: FakeChannelClient): PushController =
        PushController(client, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

    Checks.check("Endpoint führt zur Pusher-Registrierung und Zustand REGISTERED") {
        val client = FakeChannelClient()
        val controller = controllerWith(client)

        controller.onNewEndpoint("https://push.example.org/endpoint")

        expectEquals(
            listOf("https://push.example.org/endpoint"),
            client.registerEndpointCalls,
            "Endpoint an ChannelClient delegiert",
        )
        expectEquals(PushState.REGISTERED, controller.state.value, "Zustand")
    }

    Checks.check("Push-Nachricht stößt Sync an") {
        val client = FakeChannelClient()
        val controller = controllerWith(client)

        controller.onMessage()

        expectEquals(1, client.syncOnceCalls, "syncOnce-Aufrufe")
    }

    Checks.check("Fehlschlag und Aufhebung setzen Zustand zurück") {
        val client = FakeChannelClient()
        val controller = controllerWith(client)

        controller.onDistributorAvailable()
        expectEquals(PushState.READY, controller.state.value, "nach Distributor")

        controller.onRegistrationFailed("NETWORK")
        expectEquals(PushState.FAILED, controller.state.value, "nach Fehlschlag")

        controller.onUnregistered()
        expectEquals(PushState.NOT_AVAILABLE, controller.state.value, "nach Aufhebung")

        controller.onNoDistributor()
        expectEquals(PushState.NOT_AVAILABLE, controller.state.value, "nach kein Distributor")
    }

    Checks.check("Registrierungsfehler beim Pusher führt zu FAILED") {
        val client = FakeChannelClient().apply {
            registerPushEndpointBehavior = {
                throw ChannelException("Pusher-Registrierung fehlgeschlagen: offline")
            }
        }
        val controller = controllerWith(client)

        controller.onNewEndpoint("https://push.example.org/endpoint")

        expectEquals(PushState.FAILED, controller.state.value, "Zustand nach Fehler")
    }
}
