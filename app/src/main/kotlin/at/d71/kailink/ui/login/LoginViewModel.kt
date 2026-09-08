package at.d71.kailink.ui.login

import at.d71.kailink.domain.ChannelClient
import at.d71.kailink.domain.SessionStore
import at.d71.kailink.domain.model.Session
import at.d71.kailink.domain.push.PushRegistrationTrigger
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
    val homeserverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val busy: Boolean = false,
    val busyLabel: String? = null,
    val error: String? = null,
    val loggedIn: Boolean = false,
)

/**
 * Steuert Anmeldung und automatische Sitzungswiederherstellung
 * (docs/features/anmeldung-sitzung.md). Reine Kotlin-Klasse ohne
 * Android-/Lifecycle-Abhängigkeit; der CoroutineScope ist injizierbar,
 * damit JVM-Prüfungen deterministisch laufen.
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
                it.copy(busy = true, busyLabel = "Sitzung wird wiederhergestellt …", error = null)
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
                            error = "Sitzung konnte nicht wiederhergestellt werden: ${t.message ?: "unbekannter Fehler"}",
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
            _ui.update { it.copy(error = "Bitte alle Felder ausfüllen.") }
            return
        }
        _ui.update { it.copy(busy = true, busyLabel = "Anmeldung läuft …", error = null) }
        scope.launch {
            try {
                channelClient.login(state.homeserverUrl.trim(), state.username.trim(), state.password)
                _ui.update { it.copy(busy = false, busyLabel = null, password = "", loggedIn = true) }
                pushTrigger.tryRegister()
            } catch (t: Throwable) {
                _ui.update {
                    it.copy(busy = false, busyLabel = null, error = t.message ?: "Anmeldung fehlgeschlagen")
                }
            }
        }
    }

    fun clear() {
        scope.cancel()
    }
}
