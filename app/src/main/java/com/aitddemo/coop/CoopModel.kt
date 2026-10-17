package com.aitddemo.coop

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What the co-op screens show and do: the connection, the session, the lobby. Everything the server tells arrives on OkHttp's thread; the state that the screens read is changed on the
 * main thread (the Compose state is not thread safe). The state of the other hero ([onRemoteState]) does not go through the main thread: it is handed over at once to whoever
 * listens (the game's snapshot buffer, a thread safe queue), so the game loop is not delayed by the screens.
 */
class CoopModel(private val prefs: SharedPreferences? = null) : CoopClient.Listener {
    enum class Connection { DISCONNECTED, CONNECTING, CONNECTED }

    private val main = Handler(Looper.getMainLooper())
    private val client = CoopClient(this)

    var url by mutableStateOf(prefs?.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL)
        private set
    var connection by mutableStateOf(Connection.DISCONNECTED)
        private set
    /** The last error or notice for the player (null = none). */
    var error by mutableStateOf<String?>(null)
        private set
    var sessions by mutableStateOf<List<ListedSession>>(emptyList())
        private set
    /** Who I am in the session I am in (slot, hero, name), or null. */
    var me by mutableStateOf<ServerEvent.Joined?>(null)
        private set
    var session by mutableStateOf<SessionInfo?>(null)
        private set
    /** Set when the server has confirmed the shared start: both clients got the same content. */
    var start by mutableStateOf<StartInfo?>(null)
        private set
    /** A note about the other player ("Emily disconnected"), or null. */
    var peerNote by mutableStateOf<String?>(null)
        private set

    /** Called on OkHttp's thread for every state of the other hero. */
    @Volatile var onRemoteState: ((RemoteState) -> Unit)? = null

    /** Called on OkHttp's thread when the other player took an item out of the shared world (the number of its object). */
    @Volatile var onItemTaken: ((Int) -> Unit)? = null

    /** Called on OkHttp's thread when the other player pushed a piece of furniture: (object, x, y, z). */
    @Volatile var onObjectMoved: ((Int, Int, Int, Int) -> Unit)? = null

    /** Called on OkHttp's thread when the other player put an item down: (object, stage, room, x, y, z, alpha, beta, gamma). */
    @Volatile var onItemPlaced: ((IntArray) -> Unit)? = null

    fun changeUrl(value: String) {
        url = value.trim()
        prefs?.edit()?.putString(KEY_URL, url)?.apply()
    }

    fun clearError() { error = null }

    // ------------------------------------------------------------------------------------------ what the player does

    fun connect() {
        if (connection != Connection.DISCONNECTED) return
        error = null
        connection = Connection.CONNECTING
        if (!client.connect(url)) {
            connection = Connection.DISCONNECTED
            error = "That is not a valid server address (it should look like wss://name.onrender.com or ws://192.168.1.20:8080)."
        }
    }

    fun disconnect() {
        client.close()
        reset()
        connection = Connection.DISCONNECTED
    }

    fun listSessions() = send(CoopJson.listSessions())
    fun createSession() = send(CoopJson.createSession())
    fun join(name: String) = send(CoopJson.joinSession(name.trim()))
    fun selectNew() = send(CoopJson.selectNew())
    fun selectSave(saveId: String) = send(CoopJson.selectSave(saveId))
    fun uploadSave(saveId: String, label: String, base64: String) = send(CoopJson.uploadSave(saveId, label, base64))
    fun setReady(ready: Boolean) = send(CoopJson.setReady(ready))

    fun leave() {
        send(CoopJson.leaveSession())
        session = null
        me = null
        start = null
        peerNote = null
    }

    /** For the game: sends the state of the local hero (a frame is only queued, nothing waits). */
    fun sendState(seq: Int, t: Double, hero: Hero, stage: Int, room: Int, x: Int, y: Int, z: Int, beta: Int, anim: Int): Boolean =
        client.send(CoopJson.state(seq, t, hero, stage, room, x, y, z, beta, anim))

    /** For the game: tells the other phone that an item was taken. */
    fun sendItemTaken(obj: Int): Boolean = client.send(CoopJson.itemTaken(obj))

    /** For the game: tells the other phone that an item was put down at this place. */
    fun sendItemPlaced(obj: Int, stage: Int, room: Int, x: Int, y: Int, z: Int, alpha: Int, beta: Int, gamma: Int): Boolean =
        client.send(CoopJson.itemPlaced(obj, stage, room, x, y, z, alpha, beta, gamma))

    /** For the game: tells the other phone where a pushed piece of furniture is now. */
    fun sendObjectMoved(obj: Int, x: Int, y: Int, z: Int): Boolean = client.send(CoopJson.objectMoved(obj, x, y, z))

    private fun send(text: String) {
        error = null
        if (!client.send(text)) error = "There is no connection to the server."
    }

    private fun reset() {
        sessions = emptyList()
        me = null
        session = null
        start = null
        peerNote = null
    }

    // ------------------------------------------------------------------------------------------ what the server tells (OkHttp's thread)

    override fun onOpen() {
        main.post { connection = Connection.CONNECTED; error = null }
    }

    override fun onEvent(event: ServerEvent) {
        if (event is ServerEvent.Remote) {
            onRemoteState?.invoke(event.state)
            return
        }
        if (event is ServerEvent.ItemPlaced) {
            onItemPlaced?.invoke(intArrayOf(event.obj, event.stage, event.room, event.x, event.y, event.z, event.alpha, event.beta, event.gamma))
            return
        }
        if (event is ServerEvent.ObjectMoved) {
            onObjectMoved?.invoke(event.obj, event.x, event.y, event.z)
            return
        }
        if (event is ServerEvent.ItemTaken) {
            onItemTaken?.invoke(event.obj)
            return
        }
        main.post { apply(event) }
    }

    override fun onClosed(reason: String) {
        main.post {
            // a running game does not come back by itself: the screens show why it ended
            reset()
            connection = Connection.DISCONNECTED
            error = "The connection ended: $reason"
        }
    }

    private fun apply(event: ServerEvent) {
        when (event) {
            is ServerEvent.Welcome -> if (event.protocol != PROTOCOL) error = "The server speaks protocol ${event.protocol}; this app expects $PROTOCOL."
            is ServerEvent.Sessions -> sessions = event.sessions
            is ServerEvent.Joined -> { me = event; error = null }
            is ServerEvent.Session -> session = event.info
            is ServerEvent.Start -> { start = event.info; peerNote = null; error = null }
            is ServerEvent.Peer -> peerNote = when (event.state) {
                "connected" -> "${event.hero.title} joined."
                "disconnected" -> "${event.hero.title} disconnected."
                "silent" -> "${event.hero.title} is not sending updates."
                else -> null
            }
            is ServerEvent.Closed -> { session = null; me = null; start = null; error = "The session ended: ${event.reason}." }
            is ServerEvent.Error -> error = describe(event)
            is ServerEvent.Remote, is ServerEvent.ItemTaken, is ServerEvent.ItemPlaced, is ServerEvent.ObjectMoved, is ServerEvent.Pong -> {}
        }
    }

    private fun describe(e: ServerEvent.Error): String = when (e.code) {
        "unknown_session" -> "There is no session with that name."
        "session_full" -> "That session already has two players."
        "session_not_joinable" -> "That game has already started; nobody can join it."
        "selection_mismatch" -> "You and the other player chose different ways to start."
        "save_missing" -> "That save point is not offered by the host."
        "not_host" -> "Only the host can offer a save point."
        "already_in_session" -> "You are already in a session."
        "wrong_hero", "area_not_allowed", "implausible_state", "wrong_state" -> "The server refused an update: ${e.message}."
        else -> e.message
    }

    companion object {
        const val PROTOCOL = 1
        const val KEY_URL = "coop_url"
        // the address of the production service on Render; it has to be changed to the real one (and the service deployed) before it works
        const val DEFAULT_URL = "wss://aitdroid-coop-server.onrender.com"
    }
}
