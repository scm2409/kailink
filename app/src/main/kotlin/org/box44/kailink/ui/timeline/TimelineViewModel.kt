package org.box44.kailink.ui.timeline

import org.box44.kailink.domain.ChannelClient
import org.box44.kailink.domain.ChannelEvent
import org.box44.kailink.domain.model.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TimelineUiState(
    val roomId: String,
    val messages: List<Message> = emptyList(),
    val draft: String = "",
    val loading: Boolean = true,
    val sending: Boolean = false,
    val error: String? = null,
)

/**
 * Chronik eines Raums; bezieht Nachrichten aus [ChannelEvent.TimelineUpdated]
 * (der Adapter wendet die SDK-Diffs über TimelineReducer an). Reine
 * Kotlin-Klasse ohne Android-/Lifecycle-Abhängigkeit; der CoroutineScope ist
 * injizierbar, damit JVM-Prüfungen deterministisch laufen.
 */
class TimelineViewModel(
    private val roomId: String,
    private val channelClient: ChannelClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val _ui = MutableStateFlow(TimelineUiState(roomId = roomId))
    val ui: StateFlow<TimelineUiState> = _ui.asStateFlow()

    init {
        scope.launch {
            channelClient.events.collect { event ->
                when (event) {
                    is ChannelEvent.TimelineUpdated ->
                        if (event.roomId == roomId) {
                            _ui.update {
                                it.copy(messages = event.messages, loading = false)
                            }
                        }
                    is ChannelEvent.ClientError -> _ui.update { it.copy(error = event.message) }
                    else -> Unit
                }
            }
        }
        scope.launch {
            try {
                channelClient.openTimeline(roomId)
                channelClient.syncOnce()
            } catch (t: Throwable) {
                _ui.update {
                    it.copy(loading = false, error = t.message ?: "Chronik konnte nicht geladen werden")
                }
            }
        }
    }

    fun onDraftChange(value: String) = _ui.update { it.copy(draft = value, error = null) }

    fun send() {
        val state = _ui.value
        val body = state.draft.trim()
        if (body.isEmpty() || state.sending) return
        _ui.update { it.copy(draft = "", sending = true, error = null) }
        scope.launch {
            try {
                channelClient.sendMessage(roomId, body)
                _ui.update { it.copy(sending = false) }
            } catch (t: Throwable) {
                _ui.update {
                    it.copy(sending = false, draft = body, error = t.message ?: "Senden fehlgeschlagen")
                }
            }
        }
    }

    fun clear() {
        scope.cancel()
    }
}
