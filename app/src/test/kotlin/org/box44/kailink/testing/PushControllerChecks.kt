package org.box44.kailink.testing

import org.box44.kailink.data.push.PushController
import org.box44.kailink.domain.ChannelException
import org.box44.kailink.domain.push.PushConfiguration
import org.box44.kailink.domain.push.PushState
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

    Checks.check("PushConfiguration: Standard-Gateway und App-ID") {
        expectEquals(
            "https://ntfy.sh/_matrix/push/v1/notify",
            PushConfiguration.DEFAULT_GATEWAY_URL,
            "DEFAULT_GATEWAY_URL",
        )
        expectEquals("org.box44.kailink", PushConfiguration.DEFAULT_APP_ID, "DEFAULT_APP_ID")
        val configuration = PushConfiguration()
        expectEquals(PushConfiguration.DEFAULT_GATEWAY_URL, configuration.gatewayUrl, "Standard-Gateway")
        expectEquals(PushConfiguration.DEFAULT_APP_ID, configuration.appId, "Standard-App-ID")
    }

    Checks.check("Endpoint-Rotation: jeder geänderte Endpoint wird erneut registriert") {
        val client = FakeChannelClient()
        val controller = controllerWith(client)

        controller.onNewEndpoint("https://push.example.org/endpoint-1")
        controller.onNewEndpoint("https://push.example.org/endpoint-2")

        expectEquals(
            listOf(
                "https://push.example.org/endpoint-1",
                "https://push.example.org/endpoint-2",
            ),
            client.registerEndpointCalls,
            "beide Endpoints an Kanalschicht registriert",
        )
        expectEquals("https://push.example.org/endpoint-2", controller.lastRegisteredEndpoint, "zuletzt registriert")
        expectEquals(PushState.REGISTERED, controller.state.value, "Zustand nach Rotation")
    }
}
