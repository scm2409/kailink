package at.d71.kailink

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import at.d71.kailink.di.AppGraph
import at.d71.kailink.domain.model.Room
import at.d71.kailink.domain.push.PushState
import at.d71.kailink.ui.login.LoginUiState
import at.d71.kailink.ui.login.LoginViewModel
import at.d71.kailink.ui.rooms.RoomListAdapter
import at.d71.kailink.ui.rooms.RoomListUiState
import at.d71.kailink.ui.rooms.RoomListViewModel
import at.d71.kailink.ui.timeline.MessageListAdapter
import at.d71.kailink.ui.timeline.TimelineUiState
import at.d71.kailink.ui.timeline.TimelineViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

sealed interface KaiLinkScreen {
    data object Login : KaiLinkScreen
    data object RoomList : KaiLinkScreen
    data class Timeline(val roomId: String, val roomName: String) : KaiLinkScreen
}

/**
 * Phase-1-Oberfläche mit Framework-Views (statt Jetpack Compose, siehe
 * docs/architecture.md): eine Activity, drei durch Sichtbarkeit umgeschaltete
 * Screens, Zustandsaktualisierung über die StateFlows der ViewModels.
 */
class MainActivity : Activity() {

    private val graph: AppGraph by lazy { (application as KaiLinkApp).graph }
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var loginViewModel: LoginViewModel
    private var roomListViewModel: RoomListViewModel? = null
    private var timelineViewModel: TimelineViewModel? = null
    private var currentScreen: KaiLinkScreen = KaiLinkScreen.Login

    private lateinit var loginScreen: View
    private lateinit var roomsScreen: View
    private lateinit var timelineScreen: View

    private lateinit var inputHomeserver: EditText
    private lateinit var inputUsername: EditText
    private lateinit var inputPassword: EditText
    private lateinit var textLoginError: TextView
    private lateinit var btnLogin: Button
    private lateinit var progressLogin: ProgressBar
    private lateinit var textLoginBusy: TextView

    private lateinit var textUser: TextView
    private lateinit var textPushState: TextView
    private lateinit var textRoomsError: TextView
    private lateinit var textRoomsEmpty: TextView
    private lateinit var roomListAdapter: RoomListAdapter

    private lateinit var textRoomName: TextView
    private lateinit var textTimelineError: TextView
    private lateinit var inputDraft: EditText
    private lateinit var listMessages: ListView
    private lateinit var messageListAdapter: MessageListAdapter
    private var lastMessageCount: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        loginScreen = findViewById(R.id.screen_login)
        roomsScreen = findViewById(R.id.screen_rooms)
        timelineScreen = findViewById(R.id.screen_timeline)

        inputHomeserver = findViewById(R.id.input_homeserver)
        inputUsername = findViewById(R.id.input_username)
        inputPassword = findViewById(R.id.input_password)
        textLoginError = findViewById(R.id.text_login_error)
        btnLogin = findViewById(R.id.btn_login)
        progressLogin = findViewById(R.id.progress_login)
        textLoginBusy = findViewById(R.id.text_login_busy)

        textUser = findViewById(R.id.text_user)
        textPushState = findViewById(R.id.text_push_state)
        textRoomsError = findViewById(R.id.text_rooms_error)
        textRoomsEmpty = findViewById(R.id.text_rooms_empty)
        roomListAdapter = RoomListAdapter(this)
        findViewById<ListView>(R.id.list_rooms).apply {
            adapter = roomListAdapter
            setOnItemClickListener { _, _, position, _ ->
                openRoom(roomListAdapter.roomAt(position))
            }
        }

        textRoomName = findViewById(R.id.text_room_name)
        textTimelineError = findViewById(R.id.text_timeline_error)
        inputDraft = findViewById(R.id.input_draft)
        listMessages = findViewById(R.id.list_messages)
        messageListAdapter = MessageListAdapter(this)
        listMessages.adapter = messageListAdapter

        loginViewModel = LoginViewModel(graph.channelClient, graph.sessionStore, graph.pushTrigger)
        uiScope.launch { loginViewModel.ui.collect(::renderLogin) }

        inputHomeserver.addTextChangedListener(simpleWatcher(loginViewModel::onHomeserverUrlChange))
        inputUsername.addTextChangedListener(simpleWatcher(loginViewModel::onUsernameChange))
        inputPassword.addTextChangedListener(simpleWatcher(loginViewModel::onPasswordChange))
        btnLogin.setOnClickListener { loginViewModel.login() }

        findViewById<Button>(R.id.btn_refresh).setOnClickListener { roomListViewModel?.refresh() }
        findViewById<Button>(R.id.btn_logout).setOnClickListener { roomListViewModel?.logout() }
        findViewById<Button>(R.id.btn_back).setOnClickListener { show(KaiLinkScreen.RoomList) }
        inputDraft.addTextChangedListener(simpleWatcher { timelineViewModel?.onDraftChange(it) })
        findViewById<Button>(R.id.btn_send).setOnClickListener { timelineViewModel?.send() }

        show(KaiLinkScreen.Login)
    }

    override fun onDestroy() {
        uiScope.cancel()
        loginViewModel.clear()
        roomListViewModel?.clear()
        timelineViewModel?.clear()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentScreen is KaiLinkScreen.Timeline) {
            show(KaiLinkScreen.RoomList)
        } else {
            super.onBackPressed()
        }
    }

    private fun show(screen: KaiLinkScreen) {
        currentScreen = screen
        loginScreen.visibility = if (screen is KaiLinkScreen.Login) View.VISIBLE else View.GONE
        roomsScreen.visibility = if (screen is KaiLinkScreen.RoomList) View.VISIBLE else View.GONE
        timelineScreen.visibility = if (screen is KaiLinkScreen.Timeline) View.VISIBLE else View.GONE
        when (screen) {
            is KaiLinkScreen.RoomList -> ensureRoomList()
            is KaiLinkScreen.Timeline -> ensureTimeline(screen)
            KaiLinkScreen.Login -> Unit
        }
    }

    private fun openRoom(room: Room) {
        show(KaiLinkScreen.Timeline(roomId = room.id, roomName = room.displayName))
    }

    private fun ensureRoomList() {
        if (roomListViewModel != null) return
        val viewModel = RoomListViewModel(
            channelClient = graph.channelClient,
            pushState = graph.pushController.state,
        )
        roomListViewModel = viewModel
        uiScope.launch { viewModel.ui.collect(::renderRooms) }
        uiScope.launch { viewModel.pushState.collect(::renderPushState) }
    }

    private fun ensureTimeline(target: KaiLinkScreen.Timeline) {
        timelineViewModel?.clear()
        val viewModel = TimelineViewModel(roomId = target.roomId, channelClient = graph.channelClient)
        timelineViewModel = viewModel
        textRoomName.text = target.roomName
        lastMessageCount = 0
        uiScope.launch { viewModel.ui.collect(::renderTimeline) }
    }

    private fun renderLogin(state: LoginUiState) {
        setTextIfChanged(inputHomeserver, state.homeserverUrl)
        setTextIfChanged(inputUsername, state.username)
        setTextIfChanged(inputPassword, state.password)
        textLoginError.text = state.error.orEmpty()
        textLoginError.visibility = if (state.error != null) View.VISIBLE else View.GONE
        btnLogin.text = if (state.busy) getString(R.string.login_button_busy) else getString(R.string.login_button)
        btnLogin.isEnabled = !state.busy
        inputHomeserver.isEnabled = !state.busy
        inputUsername.isEnabled = !state.busy
        inputPassword.isEnabled = !state.busy
        progressLogin.visibility = if (state.busy) View.VISIBLE else View.GONE
        textLoginBusy.text = state.busyLabel.orEmpty()
        textLoginBusy.visibility = if (state.busyLabel != null) View.VISIBLE else View.GONE
        if (state.loggedIn && currentScreen is KaiLinkScreen.Login) {
            show(KaiLinkScreen.RoomList)
        }
    }

    private fun renderRooms(state: RoomListUiState) {
        textUser.text = state.userId?.let { getString(R.string.rooms_logged_in_as, it) }
            ?: getString(R.string.rooms_logged_in)
        textRoomsError.text = state.error.orEmpty()
        textRoomsError.visibility = if (state.error != null) View.VISIBLE else View.GONE
        textRoomsEmpty.visibility =
            if (!state.refreshing && state.rooms.isEmpty()) View.VISIBLE else View.GONE
        roomListAdapter.update(state.rooms)
    }

    private fun renderPushState(state: PushState) {
        textPushState.text = when (state) {
            PushState.REGISTERED -> getString(R.string.push_registered)
            PushState.READY -> getString(R.string.push_ready)
            PushState.NOT_AVAILABLE -> getString(R.string.push_not_available)
            PushState.FAILED -> getString(R.string.push_failed)
        }
    }

    private fun renderTimeline(state: TimelineUiState) {
        textTimelineError.text = state.error.orEmpty()
        textTimelineError.visibility = if (state.error != null) View.VISIBLE else View.GONE
        messageListAdapter.update(state.messages)
        if (state.messages.size != lastMessageCount) {
            lastMessageCount = state.messages.size
            if (state.messages.isNotEmpty()) {
                listMessages.post { listMessages.setSelection(state.messages.size - 1) }
            }
        }
        setTextIfChanged(inputDraft, state.draft)
    }

    private fun setTextIfChanged(view: EditText, value: String) {
        if (view.text.toString() != value) {
            view.setText(value)
        }
    }

    private fun simpleWatcher(block: (String) -> Unit): TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            block(s?.toString().orEmpty())
        }
    }
}
