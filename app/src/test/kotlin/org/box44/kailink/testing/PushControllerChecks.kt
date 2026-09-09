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

    Checks.check("Endpoint leads to pusher registration and state REGISTERED") {
        val client = FakeChannelClient()
        val controller = controllerWith(client)

        controller.onNewEndpoint("https://push.example.org/endpoint")

        expectEquals(
            listOf("https://push.example.org/endpoint"),
            client.registerEndpointCalls,
            "Endpoint delegated to ChannelClient",
        )
        expectEquals(PushState.REGISTERED, controller.state.value, "State")
    }

    Checks.check("Push message triggers sync") {
        val client = FakeChannelClient()
        val controller = controllerWith(client)

        controller.onMessage()

        expectEquals(1, client.syncOnceCalls, "syncOnce calls")
    }

    Checks.check("Failure and unregistration reset the state") {
        val client = FakeChannelClient()
        val controller = controllerWith(client)

        controller.onDistributorAvailable()
        expectEquals(PushState.READY, controller.state.value, "after distributor")

        controller.onRegistrationFailed("NETWORK")
        expectEquals(PushState.FAILED, controller.state.value, "after failure")

        controller.onUnregistered()
        expectEquals(PushState.NOT_AVAILABLE, controller.state.value, "after unregistration")

        controller.onNoDistributor()
        expectEquals(PushState.NOT_AVAILABLE, controller.state.value, "after no distributor")
    }

    Checks.check("Pusher registration error leads to FAILED") {
        val client = FakeChannelClient().apply {
            registerPushEndpointBehavior = {
                throw ChannelException("Pusher registration failed: offline")
            }
        }
        val controller = controllerWith(client)

        controller.onNewEndpoint("https://push.example.org/endpoint")

        expectEquals(PushState.FAILED, controller.state.value, "State after error")
    }

    Checks.check("PushConfiguration: default gateway and app ID") {
        expectEquals(
            "https://ntfy.sh/_matrix/push/v1/notify",
            PushConfiguration.DEFAULT_GATEWAY_URL,
            "DEFAULT_GATEWAY_URL",
        )
        expectEquals("org.box44.kailink", PushConfiguration.DEFAULT_APP_ID, "DEFAULT_APP_ID")
        val configuration = PushConfiguration()
        expectEquals(PushConfiguration.DEFAULT_GATEWAY_URL, configuration.gatewayUrl, "Default gateway")
        expectEquals(PushConfiguration.DEFAULT_APP_ID, configuration.appId, "Default app ID")
    }

    Checks.check("Endpoint rotation: every changed endpoint is registered again") {
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
            "both endpoints registered at the channel layer",
        )
        expectEquals("https://push.example.org/endpoint-2", controller.lastRegisteredEndpoint, "last registered")
        expectEquals(PushState.REGISTERED, controller.state.value, "State after rotation")
    }
}
