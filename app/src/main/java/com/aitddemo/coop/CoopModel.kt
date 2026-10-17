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

    var url by mutableStateOf(initialUrl(prefs))
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
    /** The connection was lost in a running game and the phone is trying to take its place again (for two minutes). */
    var reconnecting by mutableStateOf(false)
        private set
    /** Counts up whenever the two phones have to send the world to each other again: after this phone resumed, and after the other one came back. */
    var resyncTick by mutableStateOf(0)
        private set
    /** The other player is gone for good (he left, or did not come back in time): the game goes on without him. */
    var partnerGone by mutableStateOf(false)
        private set
    private var token: String? = null
    private var resuming = false
    private var reconnectDeadline = 0L
    private val retry = Runnable { attemptReconnect() }

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

    /** Called on OkHttp's thread: the other phone's props (entries as in [ServerEvent.Props]), a hit on a creature (guest to host), a hurt (host to guest), the other hero's death. */
    @Volatile var onProps: ((List<IntArray>) -> Unit)? = null
    @Volatile var onCreatureHit: ((Int, Int) -> Unit)? = null
    @Volatile var onHurt: ((Int, Int) -> Unit)? = null
    @Volatile var onLatch: ((Int, Int, Int) -> Unit)? = null
    @Volatile var onSound: ((Int) -> Unit)? = null
    @Volatile var onSnapshot: ((List<IntArray>) -> Unit)? = null
    @Volatile var onEffect: ((Int, Int, Int) -> Unit)? = null
    @Volatile var onWorldVar: ((Int, Int) -> Unit)? = null
    @Volatile var onHeroDied: (() -> Unit)? = null

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
        main.removeCallbacks(retry)
        reconnecting = false
        resuming = false
        token = null
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
        token = null // leaving on purpose: no way back into this game
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

    fun sendProps(items: List<IntArray>): Boolean = client.send(CoopJson.props(items))
    fun sendLatch(index: Int, value: Int, old: Int): Boolean = client.send(CoopJson.latch(index, value, old))
    fun sendSound(n: Int): Boolean = client.send(CoopJson.sound(n))
    fun sendSnapshot(items: List<IntArray>): Boolean = client.send(CoopJson.worldSnapshot(items))
    fun sendEffect(kind: Int, a: Int, b: Int): Boolean = client.send(CoopJson.effect(kind, a, b))
    fun sendWorldVar(index: Int, value: Int): Boolean = client.send(CoopJson.worldVar(index, value))
    fun sendCreatureHit(obj: Int, force: Int): Boolean = client.send(CoopJson.creatureHit(obj, force))
    fun sendHurt(force: Int, by: Int): Boolean = client.send(CoopJson.hurt(force, by))
    fun sendHeroDied(): Boolean = client.send(CoopJson.heroDied())

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
        main.post {
            connection = Connection.CONNECTED
            error = null
            val mine = me
            val secret = token
            if (reconnecting && mine != null && secret != null) { // the connection is back: take the place again
                resuming = true
                client.send(CoopJson.resume(mine.name, secret))
            }
        }
    }

    private fun beginReconnect() {
        if (!reconnecting) {
            reconnecting = true
            reconnectDeadline = android.os.SystemClock.elapsedRealtime() + RECONNECT_WINDOW_MS
        }
        if (android.os.SystemClock.elapsedRealtime() > reconnectDeadline) { giveUp(); return }
        connection = Connection.CONNECTING
        main.removeCallbacks(retry)
        main.postDelayed(retry, 2000)
    }

    private fun attemptReconnect() {
        if (!reconnecting) return
        if (!client.connect(url)) giveUp()
    }

    private fun giveUp() {
        if (start != null) partnerGone = true
        main.removeCallbacks(retry)
        reconnecting = false
        resuming = false
        token = null
        client.close()
        reset()
        connection = Connection.DISCONNECTED
        error = "The connection was lost and the game could not be resumed. You go on alone."
    }

    override fun onEvent(event: ServerEvent) {
        if (event is ServerEvent.Remote) {
            onRemoteState?.invoke(event.state)
            return
        }
        when (event) {
            is ServerEvent.Props -> { onProps?.invoke(event.items); return }
            is ServerEvent.Latch -> { onLatch?.invoke(event.index, event.value, event.old); return }
            is ServerEvent.HeroSound -> { onSound?.invoke(event.n); return }
            is ServerEvent.WorldSnapshot -> { onSnapshot?.invoke(event.items); return }
            is ServerEvent.Effect -> { onEffect?.invoke(event.kind, event.a, event.b); return }
            is ServerEvent.WorldVar -> { onWorldVar?.invoke(event.index, event.value); return }
            is ServerEvent.CreatureHit -> { onCreatureHit?.invoke(event.obj, event.force); return }
            is ServerEvent.Hurt -> { onHurt?.invoke(event.force, event.by); return }
            is ServerEvent.HeroDied -> { onHeroDied?.invoke(); return }
            else -> {}
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
            // a running game tries to take its place again for two minutes; otherwise the screens show why it ended
            if (start != null && token != null && me != null) { beginReconnect(); return@post }
            reset()
            connection = Connection.DISCONNECTED
            error = "The connection ended: $reason"
        }
    }

    private fun apply(event: ServerEvent) {
        when (event) {
            is ServerEvent.Welcome -> if (event.protocol != PROTOCOL) error = "The server speaks protocol ${event.protocol}; this app expects $PROTOCOL."
            is ServerEvent.Sessions -> sessions = event.sessions
            is ServerEvent.Joined -> {
                me = event
                error = null
                if (event.token.isNotEmpty()) token = event.token
                if (resuming) { resuming = false; reconnecting = false; main.removeCallbacks(retry); resyncTick++ }
            }
            is ServerEvent.Session -> session = event.info
            is ServerEvent.Start -> { partnerGone = false; start = event.info; peerNote = null; error = null }
            is ServerEvent.Peer -> peerNote = when (event.state) {
                "connected" -> { if (start != null) resyncTick++; "${event.hero.title} joined." }
                "left" -> { if (start != null) partnerGone = true; "${event.hero.title} left. You go on alone." }
                "disconnected" -> "${event.hero.title} disconnected."
                "silent" -> "${event.hero.title} is not sending updates."
                else -> null
            }
            is ServerEvent.Closed -> { if (start != null) partnerGone = true; reconnecting = false; token = null; main.removeCallbacks(retry); session = null; me = null; start = null; error = "The session ended: ${event.reason}." }
            is ServerEvent.Error -> if (resuming) giveUp() else error = describe(event)
            is ServerEvent.Remote, is ServerEvent.ItemTaken, is ServerEvent.ItemPlaced, is ServerEvent.ObjectMoved, is ServerEvent.Props, is ServerEvent.Latch, is ServerEvent.HeroSound, is ServerEvent.WorldSnapshot, is ServerEvent.Effect, is ServerEvent.WorldVar, is ServerEvent.CreatureHit, is ServerEvent.Hurt, is ServerEvent.HeroDied, is ServerEvent.Pong -> {}
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
        /** An address saved before the production address existed (a test address of the LAN) is dropped once; after that the saved address wins again. */
        private fun initialUrl(prefs: SharedPreferences?): String {
            if (prefs == null) return DEFAULT_URL
            if (prefs.getInt(KEY_URL_VERSION, 0) < URL_VERSION) {
                prefs.edit().remove(KEY_URL).putInt(KEY_URL_VERSION, URL_VERSION).apply()
                return DEFAULT_URL
            }
            return prefs.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
        }

        const val URL_VERSION = 1
        const val KEY_URL_VERSION = "coop_url_version"
        const val PROTOCOL = 1
        /** How long a phone keeps trying to take its place again (the server keeps the game for the same time). */
        const val RECONNECT_WINDOW_MS = 120_000L
        const val KEY_URL = "coop_url"
        // the address of the production service on Render (a phone that has saved another address in the lobby keeps that one)
        const val DEFAULT_URL = "wss://aitdroid-coop.onrender.com"
    }
}
