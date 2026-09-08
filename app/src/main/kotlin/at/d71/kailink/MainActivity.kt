package at.d71.kailink

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import at.d71.kailink.di.AppGraph
import at.d71.kailink.domain.model.Message
import at.d71.kailink.domain.model.Room
import at.d71.kailink.domain.push.PushState
import at.d71.kailink.ui.login.LoginUiState
import at.d71.kailink.ui.login.LoginViewModel
import at.d71.kailink.ui.rooms.RoomListUiState
import at.d71.kailink.ui.rooms.RoomListViewModel
import at.d71.kailink.ui.timeline.TimelineUiState
import at.d71.kailink.ui.timeline.TimelineViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Phase-1-Oberfläche (XML-Views, ohne Compose).
 *
 * Verdrahtet die drei Screens (Anmeldung, Raumliste, Chronik) mit den
 * ViewModels über [AppGraph]. Screenwechsel geschieht über Sichtbarkeit der
 * in activity_main.xml eingebetteten Screen-Layouts.
 */
class MainActivity : Activity() {

    private enum class Screen { LOGIN, ROOMS, TIMELINE }

    private lateinit var graph: AppGraph

    /** UI-seitiger Scope; wird in onDestroy beendet. */
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var loginViewModel: LoginViewModel
    private lateinit var roomListViewModel: RoomListViewModel
    private var timelineViewModel: TimelineViewModel? = null

    private var current: TimelineViewModel? = null

    private lateinit var screenLogin: View
    private lateinit var screenRooms: View
    private lateinit var screenTimeline: View

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
    private lateinit var listRooms: ListView

    private lateinit var textRoomName: TextView
    private lateinit var textTimelineError: TextView
    private lateinit var listMessages: ListView
    private lateinit var inputDraft: EditText
    private lateinit var btnSend: Button

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        graph = (application as KaiLinkApp).graph
        setContentView(R.layout.activity_main)

        bindViews()
        setUpLoginScreen()
        setUpRoomsScreen()
        setUpTimelineScreen()

        loginViewModel = LoginViewModel(
            graph.channelClient,
            graph.sessionStore,
            graph.pushTrigger,
            uiScope,
        )
        roomListViewModel = RoomListViewModel(graph.channelClient, graph.pushController, uiScope)

        observeLogin()
        observeRooms()
        showScreen(Screen.LOGIN)
    }

    override fun onDestroy() {
        super.onDestroy()
        timelineViewModel?.clear()
        roomListViewModel.clear()
        loginViewModel.clear()
        uiScope.cancel()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::screenTimeline.isInitialized && screenTimeline.visibility == View.VISIBLE) {
            leaveTimeline()
        } else {
            super.onBackPressed()
        }
    }

    // ------------------------------------------------------------------ Setup

    private fun bindViews() {
        screenLogin = findViewById(R.id.screen_login)
        screenRooms = findViewById(R.id.screen_rooms)
        screenTimeline = findViewById(R.id.screen_timeline)

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
        listRooms = findViewById(R.id.list_rooms)

        textRoomName = findViewById(R.id.text_room_name)
        textTimelineError = findViewById(R.id.text_timeline_error)
        listMessages = findViewById(R.id.list_messages)
        inputDraft = findViewById(R.id.input_draft)
        btnSend = findViewById(R.id.btn_send)
    }

    private fun setUpLoginScreen() {
        btnLogin.setOnClickListener {
            loginViewModel.onHomeserverUrlChange(inputHomeserver.text.toString())
            loginViewModel.onUsernameChange(inputUsername.text.toString())
            loginViewModel.onPasswordChange(inputPassword.text.toString())
            loginViewModel.login()
        }
    }

    private fun setUpRoomsScreen() {
        findViewById<Button>(R.id.btn_refresh).setOnClickListener { roomListViewModel.refresh() }
        findViewById<Button>(R.id.btn_logout).setOnClickListener {
            roomListViewModel.logout()
            showScreen(Screen.LOGIN)
        }
        listRooms.setOnItemClickListener { _, _, position, _ ->
            val room = (listRooms.adapter as RoomAdapter).getItem(position) ?: return@setOnItemClickListener
            enterTimeline(room)
        }
    }

    private fun setUpTimelineScreen() {
        findViewById<Button>(R.id.btn_back).setOnClickListener { leaveTimeline() }
        inputDraft.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                timelineViewModel?.onDraftChange(s?.toString().orEmpty())
            }
        })
        btnSend.setOnClickListener { timelineViewModel?.send() }
    }

    // ------------------------------------------------------------------ Beobachter

    private fun observeLogin() {
        uiScope.launch {
            loginViewModel.ui.collect { state -> renderLogin(state) }
        }
    }

    private fun renderLogin(state: LoginUiState) {
        setTextIfChanged(inputHomeserver, state.homeserverUrl)
        setTextIfChanged(inputUsername, state.username)
        setTextIfChanged(inputPassword, state.password)
        showIf(textLoginError, !state.error.isNullOrBlank())
        textLoginError.text = state.error.orEmpty()
        btnLogin.isEnabled = !state.busy
        btnLogin.text = if (state.busy) {
            getString(R.string.login_button_busy)
        } else {
            getString(R.string.login_button)
        }
        showIf(progressLogin, state.busy)
        showIf(textLoginBusy, state.busy)
        textLoginBusy.text = state.busyLabel.orEmpty()
        if (state.loggedIn && screenRooms.visibility != View.VISIBLE) {
            showScreen(Screen.ROOMS)
        }
    }

    private fun observeRooms() {
        uiScope.launch {
            roomListViewModel.ui.collect { state -> renderRooms(state) }
        }
        uiScope.launch {
            roomListViewModel.pushState.collect { state -> renderPushState(state) }
        }
    }

    private fun renderRooms(state: RoomListUiState) {
        textUser.text = state.userId?.let { getString(R.string.rooms_logged_in_as, it) }.orEmpty()
        showIf(textRoomsError, !state.error.isNullOrBlank())
        textRoomsError.text = state.error.orEmpty()
        val adapter = listRooms.adapter as? RoomAdapter
            ?: RoomAdapter(this, state.rooms).also { listRooms.adapter = it }
        adapter.replace(state.rooms)
        showIf(textRoomsEmpty, state.rooms.isEmpty() && !state.refreshing)
        listRooms.emptyView = textRoomsEmpty
    }

    private fun renderPushState(state: PushState) {
        textPushState.text = when (state) {
            PushState.REGISTERED -> getString(R.string.push_registered)
            PushState.READY -> getString(R.string.push_ready)
            PushState.NOT_AVAILABLE -> getString(R.string.push_not_available)
            PushState.FAILED -> getString(R.string.push_failed)
        }
    }

    private fun observeTimeline(viewModel: TimelineViewModel) {
        uiScope.launch {
            viewModel.ui.collect { state ->
                if (timelineViewModel === viewModel) renderTimeline(state)
            }
        }
    }

    private fun renderTimeline(state: TimelineUiState) {
        showIf(textTimelineError, !state.error.isNullOrBlank())
        textTimelineError.text = state.error.orEmpty()
        val adapter = listMessages.adapter as? MessageAdapter
            ?: MessageAdapter(this, state.messages).also { listMessages.adapter = it }
        adapter.replace(state.messages)
        btnSend.isEnabled = !state.sending
    }

    // ------------------------------------------------------------------ Navigation

    private fun showScreen(screen: Screen) {
        screenLogin.visibility = if (screen == Screen.LOGIN) View.VISIBLE else View.GONE
        screenRooms.visibility = if (screen == Screen.ROOMS) View.VISIBLE else View.GONE
        screenTimeline.visibility = if (screen == Screen.TIMELINE) View.VISIBLE else View.GONE
    }

    private fun enterTimeline(room: Room) {
        timelineViewModel?.clear()
        val viewModel = TimelineViewModel(room.id, graph.channelClient, uiScope)
        timelineViewModel = viewModel
        textRoomName.text = room.displayName
        listMessages.adapter = null
        observeTimeline(viewModel)
        showScreen(Screen.TIMELINE)
    }

    private fun leaveTimeline() {
        val viewModel = timelineViewModel
        timelineViewModel = null
        val roomId = viewModel?.let { graph.channelClient }
        viewModel?.clear()
        roomId?.let { client -> uiScope.launch { runCatching { client.closeTimeline(room.id()) } } }
        showScreen(Screen.ROOMS)
    }

    // ------------------------------------------------------------------ Helfer

    private fun showIf(view: View, visible: Boolean) {
        view.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun setTextIfChanged(editText: EditText, value: String) {
        if (editText.text.toString() != value) editText.setText(value)
    }

    /**ListAdapter für die Raumliste (Name, 🔒-Badge, Vorschau). */
    private class RoomAdapter(
        activity: Activity,
        rooms: List<Room>,
    ) : ArrayAdapter<Room>(activity, R.layout.item_room, rooms) {

        private val rooms = rooms.toMutableList()

        fun replace(items: List<Room>) {
            rooms.clear()
            rooms.addAll(items)
            notifyDataSetChanged()
        }

        override fun getCount(): Int = rooms.size

        override fun getItem(position: Int): Room = rooms[position]

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: activity().layoutInflater.inflate(R.layout.item_room, parent, false)
            val room = rooms[position]
            view.findViewById<TextView>(R.id.text_room_title).text =
                if (room.isEncrypted) "🔒 ${room.displayName}" else room.displayName
            view.findViewById<TextView>(R.id.text_room_subtitle).text =
                room.lastMessage?.body.orEmpty()
            return view
        }

        private fun activity(): Activity = context as Activity
    }

    /**ListAdapter für die Chronik (Sprechblasen ein-/ausgehend). */
    private class MessageAdapter(
        activity: Activity,
        messages: List<Message>,
    ) : ArrayAdapter<Message>(activity, R.layout.item_message, messages) {

        private val messages = messages.toMutableList()

        fun replace(items: List<Message>) {
            messages.clear()
            messages.addAll(items)
            notifyDataSetChanged()
        }

        override fun getCount(): Int = messages.size

        override fun getItem(position: Int): Message = messages[position]

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: activity().layoutInflater.inflate(R.layout.item_message, parent, false)
            val message = messages[position]
            val outgoing = message.direction == at.d71.kailink.domain.model.MessageDirection.OUTGOING
            val container = view.findViewById<View>(R.id.message_container)
            val bubble = view.findViewById<View>(R.id.message_bubble)
            container.gravity = if (outgoing) Gravity.END else Gravity.START
            bubble.setBackgroundResource(
                if (outgoing) R.color.bubble_outgoing else R.color.bubble_incoming,
            )
            view.findViewById<TextView>(R.id.text_message_meta).text =
                "${message.sender} · ${timeFormat().format(Date(message.timestampMillis))}"
            view.findViewById<TextView>(R.id.text_message_body).text = message.body
            return view
        }

        private fun activity(): Activity = context as Activity

        private fun timeFormat() = SimpleDateFormat("HH:mm", Locale.getDefault())
    }
}
