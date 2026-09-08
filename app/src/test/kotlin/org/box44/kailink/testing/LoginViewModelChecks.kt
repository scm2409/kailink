package org.box44.kailink.testing

import org.box44.kailink.domain.ChannelException
import org.box44.kailink.ui.login.LoginUiState
import org.box44.kailink.ui.login.LoginViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

fun loginViewModelChecks() {

    fun viewModelScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    Checks.check("Homeserver-Feld ist vorbelegt mit https://matrix.org") {
        val viewModel = LoginViewModel(FakeChannelClient(), FakeSessionStore(), RecordingPushTrigger(), viewModelScope())

        expectEquals("https://matrix.org", viewModel.ui.value.homeserverUrl, "Initiale Homeserver-URL")
        expectEquals(LoginUiState.DEFAULT_HOMESERVER_URL, viewModel.ui.value.homeserverUrl, "Konstante und Zustand identisch")
        viewModel.clear()
    }

    Checks.check("Erfolgreiche Anmeldung setzt loggedIn und registriert Push") {
        val client = FakeChannelClient()
        val store = FakeSessionStore()
        val push = RecordingPushTrigger()
        val viewModel = LoginViewModel(client, store, push, viewModelScope())

        viewModel.onHomeserverUrlChange("https://matrix.example.org")
        viewModel.onUsernameChange("alice")
        viewModel.onPasswordChange("secret")
        viewModel.login()

        expectEquals(1, client.loginCalls, "Login-Aufrufe")
        expectTrue(viewModel.ui.value.loggedIn, "loggedIn")
        expectNull(viewModel.ui.value.error, "kein Fehler")
        expectEquals("", viewModel.ui.value.password, "Passwort geleert")
        expectEquals(1, push.registrations, "Push-Registrierung ausgelöst")
        viewModel.clear()
    }

    Checks.check("Leere Felder erzeugen Fehlermeldung ohne Login-Versuch") {
        val client = FakeChannelClient()
        val viewModel = LoginViewModel(client, FakeSessionStore(), RecordingPushTrigger(), viewModelScope())

        viewModel.login()

        expectEquals(0, client.loginCalls, "kein Login-Versuch")
        expectEquals("Bitte alle Felder ausfüllen." as String?, viewModel.ui.value.error, "Fehlermeldung")
        expectFalse(viewModel.ui.value.loggedIn, "nicht angemeldet")
        viewModel.clear()
    }

    Checks.check("Fehlerhafte Anmeldung zeigt Adapter-Fehlermeldung") {
        val client = FakeChannelClient().apply {
            loginBehavior = { _, _, _ -> throw ChannelException("Anmeldung fehlgeschlagen: 401") }
        }
        val viewModel = LoginViewModel(client, FakeSessionStore(), RecordingPushTrigger(), viewModelScope())

        viewModel.onHomeserverUrlChange("https://matrix.example.org")
        viewModel.onUsernameChange("alice")
        viewModel.onPasswordChange("wrong")
        viewModel.login()

        expectFalse(viewModel.ui.value.loggedIn, "nicht angemeldet")
        expectEquals("Anmeldung fehlgeschlagen: 401" as String?, viewModel.ui.value.error, "Fehlermeldung")
        viewModel.clear()
    }

    Checks.check("Gespeicherte Sitzung wird beim Start wiederhergestellt") {
        val client = FakeChannelClient()
        val store = FakeSessionStore(initial = TEST_SESSION)
        val push = RecordingPushTrigger()

        val viewModel = LoginViewModel(client, store, push, viewModelScope())

        expectEquals(1, client.restoreCalls, "restore-Aufrufe")
        expectTrue(viewModel.ui.value.loggedIn, "loggedIn")
        expectEquals(1, push.registrations, "Push-Registrierung ausgelöst")
        viewModel.clear()
    }

    Checks.check("Fehlgeschlagene Wiederherstellung löscht Sitzung und zeigt Fehler") {
        val client = FakeChannelClient().apply {
            restoreBehavior = { throw ChannelException("Sitzung konnte nicht wiederhergestellt werden") }
        }
        val store = FakeSessionStore(initial = TEST_SESSION)

        val viewModel = LoginViewModel(client, store, RecordingPushTrigger(), viewModelScope())

        expectNotNull(viewModel.ui.value.error, "Fehlermeldung vorhanden")
        expectFalse(viewModel.ui.value.loggedIn, "nicht angemeldet")
        expectEquals(1, store.clearCalls, "Sitzung gelöscht")
        viewModel.clear()
    }
}
