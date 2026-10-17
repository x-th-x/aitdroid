package com.aitddemo.coop

import org.json.JSONObject

// The messages of the co-op protocol (server/README.md): JSON text frames with a "type". Parsing is defensive: a message that does not fit gives null and is ignored.

enum class Hero(val id: String, val title: String) {
    CARNBY("carnby", "Carnby"),
    EMILY("emily", "Emily");

    companion object {
        fun of(id: String?): Hero? = entries.firstOrNull { it.id == id }
    }
}

class PlayerInfo(val slot: Int, val hero: Hero, val connected: Boolean, val selectionMode: String?, val selectionSaveId: String?, val ready: Boolean)

class OfferedSave(val saveId: String, val label: String, val bytes: Int)

class SessionInfo(
    val sessionId: String,
    val name: String,
    val status: String,
    val players: List<PlayerInfo>,
    val selectionsMatch: Boolean,
    val mismatch: String?,
    val offeredSave: OfferedSave?,
)

class ListedSession(val name: String, val status: String, val players: Int)

class StartInfo(val sessionId: String, val mode: String, val saveId: String?, val saveData: String?, val stage: Int, val room: Int, val serverTime: Long)

/** The state of the other player's hero as the server relays it: room-relative coordinates of the engine, heading in 1/1024 turns, animation number. */
class RemoteState(
    val seq: Int,
    val senderTime: Double,
    val serverTime: Long,
    val hero: Hero,
    val stage: Int,
    val room: Int,
    val x: Int,
    val y: Int,
    val z: Int,
    val beta: Int,
    val anim: Int,
)

sealed class ServerEvent {
    class Welcome(val protocol: Int, val serverTime: Long) : ServerEvent()
    class Sessions(val sessions: List<ListedSession>) : ServerEvent()
    class Joined(val sessionId: String, val name: String, val slot: Int, val hero: Hero) : ServerEvent()
    class Session(val info: SessionInfo) : ServerEvent()
    class Start(val info: StartInfo) : ServerEvent()
    class Remote(val state: RemoteState) : ServerEvent()
    class ItemPlaced(val obj: Int, val stage: Int, val room: Int, val x: Int, val y: Int, val z: Int, val alpha: Int, val beta: Int, val gamma: Int) : ServerEvent()
    class ObjectMoved(val obj: Int, val x: Int, val y: Int, val z: Int) : ServerEvent()
    class ItemTaken(val hero: Hero, val obj: Int) : ServerEvent()
    class Peer(val slot: Int, val hero: Hero, val state: String) : ServerEvent()
    class Closed(val reason: String) : ServerEvent()
    class Error(val code: String, val message: String, val requestType: String?) : ServerEvent()
    class Pong(val t: Double, val serverTime: Long) : ServerEvent()
}

object CoopJson {
    fun parse(text: String): ServerEvent? {
        return try {
            val o = JSONObject(text)
            when (o.getString("type")) {
                "welcome" -> ServerEvent.Welcome(o.getInt("protocol"), o.getLong("serverTime"))
                "sessions" -> {
                    val a = o.getJSONArray("sessions")
                    ServerEvent.Sessions((0 until a.length()).map { a.getJSONObject(it) }.map { ListedSession(it.getString("name"), it.getString("status"), it.getInt("players")) })
                }
                "joined" -> ServerEvent.Joined(o.getString("sessionId"), o.getString("name"), o.getInt("slot"), Hero.of(o.getString("hero")) ?: return null)
                "session" -> ServerEvent.Session(sessionInfo(o) ?: return null)
                "start" -> {
                    val area = o.getJSONObject("area")
                    ServerEvent.Start(StartInfo(o.getString("sessionId"), o.getString("mode"), o.optStringOrNull("saveId"), o.optStringOrNull("saveData"), area.getInt("stage"), area.getInt("room"), o.getLong("serverTime")))
                }
                "remote_state" -> ServerEvent.Remote(
                    RemoteState(o.getInt("seq"), o.getDouble("t"), o.getLong("serverTime"), Hero.of(o.getString("hero")) ?: return null, o.getInt("stage"), o.getInt("room"), o.getInt("x"), o.getInt("y"), o.getInt("z"), o.getInt("beta"), o.getInt("anim")),
                )
                "item_placed" -> ServerEvent.ItemPlaced(o.getInt("obj"), o.getInt("stage"), o.getInt("room"), o.getInt("x"), o.getInt("y"), o.getInt("z"), o.getInt("alpha"), o.getInt("beta"), o.getInt("gamma"))
                "object_moved" -> ServerEvent.ObjectMoved(o.getInt("obj"), o.getInt("x"), o.getInt("y"), o.getInt("z"))
                "item_taken" -> ServerEvent.ItemTaken(Hero.of(o.getString("hero")) ?: return null, o.getInt("obj"))
                "peer" -> ServerEvent.Peer(o.getInt("slot"), Hero.of(o.getString("hero")) ?: return null, o.getString("state"))
                "closed" -> ServerEvent.Closed(o.getString("reason"))
                "error" -> ServerEvent.Error(o.getString("code"), o.getString("message"), o.optStringOrNull("requestType"))
                "pong" -> ServerEvent.Pong(o.getDouble("t"), o.getLong("serverTime"))
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun JSONObject.optStringOrNull(name: String): String? = if (isNull(name) || !has(name)) null else getString(name)

    private fun sessionInfo(o: JSONObject): SessionInfo? {
        val ps = o.getJSONArray("players")
        val players = (0 until ps.length()).map { ps.getJSONObject(it) }.map { p ->
            val sel = if (p.isNull("selection")) null else p.getJSONObject("selection")
            PlayerInfo(p.getInt("slot"), Hero.of(p.getString("hero")) ?: return null, p.getBoolean("connected"), sel?.getString("mode"), sel?.optStringOrNull("saveId"), p.getBoolean("ready"))
        }
        val save = if (o.isNull("offeredSave")) null else o.getJSONObject("offeredSave").let { OfferedSave(it.getString("saveId"), it.getString("label"), it.getInt("bytes")) }
        return SessionInfo(o.getString("sessionId"), o.getString("name"), o.getString("status"), players, o.getBoolean("selectionsMatch"), o.optStringOrNull("mismatch"), save)
    }

    // ---- what the client sends

    private fun msg(type: String, build: JSONObject.() -> Unit = {}): String = JSONObject().put("type", type).apply(build).toString()

    fun listSessions() = msg("list_sessions")
    fun createSession() = msg("create_session")
    fun joinSession(name: String) = msg("join_session") { put("name", name) }
    fun leaveSession() = msg("leave_session")
    fun selectNew() = msg("select_start") { put("mode", "new") }
    fun selectSave(saveId: String) = msg("select_start") { put("mode", "save"); put("saveId", saveId) }
    fun uploadSave(saveId: String, label: String, base64: String) = msg("upload_save") { put("saveId", saveId); put("label", label); put("data", base64) }
    fun setReady(ready: Boolean) = msg("set_ready") { put("ready", ready) }
    fun ping(t: Double) = msg("ping") { put("t", t) }
    fun itemPlaced(obj: Int, stage: Int, room: Int, x: Int, y: Int, z: Int, alpha: Int, beta: Int, gamma: Int) = msg("item_placed") {
        put("obj", obj); put("stage", stage); put("room", room); put("x", x); put("y", y); put("z", z); put("alpha", alpha); put("beta", beta); put("gamma", gamma)
    }
    fun objectMoved(obj: Int, x: Int, y: Int, z: Int) = msg("object_moved") { put("obj", obj); put("x", x); put("y", y); put("z", z) }
    fun itemTaken(obj: Int) = msg("item_taken") { put("obj", obj) }
    fun state(seq: Int, t: Double, hero: Hero, stage: Int, room: Int, x: Int, y: Int, z: Int, beta: Int, anim: Int) = msg("state") {
        put("seq", seq); put("t", t); put("hero", hero.id); put("stage", stage); put("room", room)
        put("x", x); put("y", y); put("z", z); put("beta", beta); put("anim", anim)
    }
}
