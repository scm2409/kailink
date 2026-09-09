package org.box44.kailink.ui.login

import org.box44.kailink.domain.ChannelClient
import org.box44.kailink.domain.SessionStore
import org.box44.kailink.domain.model.Session
import org.box44.kailink.domain.push.PushRegistrationTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val homeserverUrl: String = DEFAULT_HOMESERVER_URL,
    val username: String = "",
    val password: String = "",
    val busy: Boolean = false,
    val busyLabel: String? = null,
    val error: String? = null,
    val loggedIn: Boolean = false,
) {
    companion object {
        /** Pre-filled default homeserver (login form). */
        const val DEFAULT_HOMESERVER_URL = "https://matrix.org"
    }
}

/**
 * Controls sign-in and automatic session restore
 * (docs/features/anmeldung-sitzung.md). Pure Kotlin class without
 * Android-/Lifecycle dependencies; the CoroutineScope is injectable
 * so that JVM checks run deterministically.
 */
class LoginViewModel(
    private val channelClient: ChannelClient,
    private val sessionStore: SessionStore,
    private val pushTrigger: PushRegistrationTrigger,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val _ui = MutableStateFlow(LoginUiState())
    val ui: StateFlow<LoginUiState> = _ui.asStateFlow()

    init {
        val stored: Session? = sessionStore.load()
        if (stored != null) {
            _ui.update {
                it.copy(busy = true, busyLabel = "Restoring session …", error = null)
            }
            scope.launch {
                try {
                    channelClient.restore(stored)
                    _ui.update { it.copy(busy = false, busyLabel = null, loggedIn = true) }
                    pushTrigger.tryRegister()
                } catch (t: Throwable) {
                    sessionStore.clear()
                    _ui.update {
                        it.copy(
                            busy = false,
                            busyLabel = null,
                            error = "Could not restore session: ${t.message ?: "unknown error"}",
                        )
                    }
                }
            }
        }
    }

    fun onHomeserverUrlChange(value: String) = _ui.update { it.copy(homeserverUrl = value, error = null) }
    fun onUsernameChange(value: String) = _ui.update { it.copy(username = value, error = null) }
    fun onPasswordChange(value: String) = _ui.update { it.copy(password = value, error = null) }

    fun login() {
        val state = _ui.value
        if (state.busy) return
        if (state.homeserverUrl.isBlank() || state.username.isBlank() || state.password.isBlank()) {
            _ui.update { it.copy(error = "Please fill in all fields.") }
            return
        }
        _ui.update { it.copy(busy = true, busyLabel = "Signing in …", error = null) }
        scope.launch {
            try {
                channelClient.login(state.homeserverUrl.trim(), state.username.trim(), state.password)
                _ui.update { it.copy(busy = false, busyLabel = null, password = "", loggedIn = true) }
                pushTrigger.tryRegister()
            } catch (t: Throwable) {
                _ui.update {
                    it.copy(busy = false, busyLabel = null, error = t.message ?: "Sign-in failed")
                }
            }
        }
    }

    fun clear() {
        scope.cancel()
    }
}
