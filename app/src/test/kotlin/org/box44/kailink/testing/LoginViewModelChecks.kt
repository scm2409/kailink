package org.box44.kailink.testing

import org.box44.kailink.domain.ChannelException
import org.box44.kailink.ui.login.LoginUiState
import org.box44.kailink.ui.login.LoginViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

fun loginViewModelChecks() {

    fun viewModelScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    Checks.check("Homeserver field is pre-filled with https://matrix.org") {
        val viewModel = LoginViewModel(FakeChannelClient(), FakeSessionStore(), RecordingPushTrigger(), viewModelScope())

        expectEquals("https://matrix.org", viewModel.ui.value.homeserverUrl, "Initial homeserver URL")
        expectEquals(LoginUiState.DEFAULT_HOMESERVER_URL, viewModel.ui.value.homeserverUrl, "Constant and state identical")
        viewModel.clear()
    }

    Checks.check("Successful login sets loggedIn and registers push") {
        val client = FakeChannelClient()
        val store = FakeSessionStore()
        val push = RecordingPushTrigger()
        val viewModel = LoginViewModel(client, store, push, viewModelScope())

        viewModel.onHomeserverUrlChange("https://matrix.example.org")
        viewModel.onUsernameChange("alice")
        viewModel.onPasswordChange("secret")
        viewModel.login()

        expectEquals(1, client.loginCalls, "Login calls")
        expectTrue(viewModel.ui.value.loggedIn, "loggedIn")
        expectNull(viewModel.ui.value.error, "no error")
        expectEquals("", viewModel.ui.value.password, "Password cleared")
        expectEquals(1, push.registrations, "Push registration triggered")
        viewModel.clear()
    }

    Checks.check("Empty fields produce an error message without a login attempt") {
        val client = FakeChannelClient()
        val viewModel = LoginViewModel(client, FakeSessionStore(), RecordingPushTrigger(), viewModelScope())

        viewModel.login()

        expectEquals(0, client.loginCalls, "no login attempt")
        expectEquals("Please fill in all fields." as String?, viewModel.ui.value.error, "Error message")
        expectFalse(viewModel.ui.value.loggedIn, "not signed in")
        viewModel.clear()
    }

    Checks.check("Failed login shows the adapter error message") {
        val client = FakeChannelClient().apply {
            loginBehavior = { _, _, _ -> throw ChannelException("Sign-in failed: 401") }
        }
        val viewModel = LoginViewModel(client, FakeSessionStore(), RecordingPushTrigger(), viewModelScope())

        viewModel.onHomeserverUrlChange("https://matrix.example.org")
        viewModel.onUsernameChange("alice")
        viewModel.onPasswordChange("wrong")
        viewModel.login()

        expectFalse(viewModel.ui.value.loggedIn, "not signed in")
        expectEquals("Sign-in failed: 401" as String?, viewModel.ui.value.error, "Error message")
        viewModel.clear()
    }

    Checks.check("Stored session is restored on start") {
        val client = FakeChannelClient()
        val store = FakeSessionStore(initial = TEST_SESSION)
        val push = RecordingPushTrigger()

        val viewModel = LoginViewModel(client, store, push, viewModelScope())

        expectEquals(1, client.restoreCalls, "restore calls")
        expectTrue(viewModel.ui.value.loggedIn, "loggedIn")
        expectEquals(1, push.registrations, "Push registration triggered")
        viewModel.clear()
    }

    Checks.check("Failed restore clears the session and shows an error") {
        val client = FakeChannelClient().apply {
            restoreBehavior = { throw ChannelException("Could not restore session") }
        }
        val store = FakeSessionStore(initial = TEST_SESSION)

        val viewModel = LoginViewModel(client, store, RecordingPushTrigger(), viewModelScope())

        expectNotNull(viewModel.ui.value.error, "error message present")
        expectFalse(viewModel.ui.value.loggedIn, "not signed in")
        expectEquals(1, store.clearCalls, "Session cleared")
        viewModel.clear()
    }
}
