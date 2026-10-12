package com.aitddemo.game

import com.aitddemo.RoomView
import com.aitddemo.life.LifeHost
import com.aitddemo.life.LifeVm
import com.aitddemo.life.WorldObject
import com.aitddemo.original.u16

/** What the game needs from the screen: texts, sounds, music. */
interface GameUi {
    fun message(text: String)
    fun playSample(number: Int)
    fun music(number: Int, loop: Boolean)
    fun gameOver()

    /** The player was sent to another room or floor by a script; that part of the game is not available yet. */
    fun leftRoom(stage: Int, room: Int)

    /** A full screen picture of the game (ITD_RESS.PAK entry index + 1) for ticks engine ticks, with a sound. */
    fun picture(index: Int, ticks: Int, sample: Int) {}

    /** The ending of the game. */
    fun endSequence() {}

    /** SAMPLE_THEN / SAMPLE_THEN_REPEAT: [first] sounds, and when it has ended [then] (repeated when [repeat]). */
    fun playSampleThen(first: Int, then: Int, repeat: Boolean) { playSample(first) }

    /** STOP_SAMPLE: everything that sounds is cut off. */
    fun stopSamples() {}

    /** RND_FREQ: the samples that follow vary their pitch by up to [percent] (0 = as recorded). */
    fun randomFrequency(percent: Int) {}

    /** NEXT_MUSIC: [number] plays when the music that is playing has ended (at once when nothing plays). */
    fun nextMusic(number: Int) { music(number, true) }
}

/** A short lived visual effect (SPECIAL): 0 evaporation, 1 blood or dust at a blow, 4 smoke. */
class Effect(val kind: Int, val x: Int, val y: Int, val z: Int, val born: Int, val box: IntArray?, val alpha: Int = 0, val beta: Int = 0, val gamma: Int = 0, val body: Int = -1)

/** An object of the world with its actor (animation, position, collision box) while it is part of the scene. */
class GameObject(val w: WorldObject) {
    /** A blow or a shot in progress (animActionType of the original): 0 none, 1 waits for the animation, 10 for its frame, 2 hits, 4/5 shot. */
    var actionType = 0
    var actionAnim = -1
    var actionFrame = 0
    var actionRange = 0
    var actionGroup = -1
    var actionForce = 0
    var actionParam = 0

    /** Where the last shot of this object hit something, relative to the object (null = none); the hit effect uses it instead of the hand. */
    var shotPoint: IntArray? = null

    /** A turn of the alpha angle in progress (SET_ALPHA): the target (-1 none), where it started and when. */
    var alphaTarget = -1
    var alphaStart = 0
    var alphaStartTime = 0

    /** A fall in progress: the height it started at and the ticks since (the original YHandler 0 to 2000 over 40). */
    var fallStartY = 0
    var fallTicks = -1

    /** A thrown item in flight: where and how it started. */
    var flightX = 0
    var flightZ = 0
    var flightBeta = 0
    var flightTicks = -1
    var flightThrower = -1

    /** Box of an object whose box is a collider of the room (OBJETS type 4, "hard ZV"); such objects are invisible helpers of the furniture scripts. */
    var fixedZv: IntArray? = null
    var actor: Actor? = null
    var asset: BodyAsset? = null

    /** The game time of the frame being drawn (set by the screen), for bodies that are posed by code. */
    var clock = 0

    /** What the body's poser keeps from frame to frame. */
    var poseState: Any? = null

    /** Posed vertices (x, y, z each) in the object's own space for drawing, or null when it has no body. */
    fun posedVertices(): FloatArray? {
        val a = asset ?: return null
        a.poser?.let { return it.pose(this, clock) }
        val parts = a.parts ?: return a.vertices
        val act = actor ?: return a.vertices
        return BodyPose.pose(parts.vertices, parts.groups, parts.groupOrder, act.state)
    }
}

/**
 * The game of one room: the life scripts of the original run on the world objects every tick, in the order of the original's main loop. The [LifeVm]
 * interprets the scripts; this class is its host: it moves the actors, plays their animations, collides them with the room and with each other, and
 * turns script operations (animations, items, messages, sounds) into effects.
 */
class AtticGame(val data: GameData, private val ui: GameUi, private var stage: Int = 0, private var room: Int = 0) : LifeHost {
    override var time = 0
    /** The world object that is the monkey: one more than the original has, a copy of the creature of the trapdoor that waits outside the scene until it is spawned. */
    private val monkeyIndex = data.objectData.u16(0)
    private val patchedScripts = HashMap<Int, ShortArray?>()

    /** The scripts of the original, except the ones changed for the monkey: the creature no longer closes the trapdoor (the monkey does, when it is out), and the monkey has its own copies. */
    private fun scriptFor(n: Int): ShortArray? = patchedScripts.getOrPut(n) {
        if (!data.useMonkey) return@getOrPut data.script(n)
        when (n) {
            LIFE_CREATURE_CLIMBED -> data.script(n)?.let { src ->
                ScriptPatch.patch(src) { name, ow, start, w ->
                    // [ow=8] LIFE 6 (the trapdoor closes) becomes VAR 511 0, which does nothing
                    if (name == "LIFE" && ow == OBJ_TRAPDOOR && w[start].toInt() == LIFE_TRAPDOOR_CLOSING) {
                        w[start - 2] = ScriptPatch.opcode("VAR"); w[start - 1] = 511; w[start] = 0
                    }
                }
            }
            LIFE_CREATURE_CLIMBED + MONKEY_SCRIPTS, LIFE_CREATURE_FIGHT + MONKEY_SCRIPTS -> data.script(n - MONKEY_SCRIPTS)?.let { src ->
                ScriptPatch.patch(src) { name, ow, start, w ->
                    val v = w[start].toInt()
                    if (ow < 0 && name == "LIFE" && v in LIFE_CREATURE_CLIMB..LIFE_CREATURE_FIGHT) w[start] = (v + MONKEY_SCRIPTS).toShort()
                    if (ow < 0 && (name == "DELETE" || name == "CAMERA_TARGET") && v == OBJ_HOLE_CREATURE) w[start] = monkeyIndex.toShort()
                }
            }
            else -> data.script(n)
        }
    }

    val vm = LifeVm({ scriptFor(it) }, withMonkeyObject(data.objectData), data.vars, this).also { for (i in data.defines.indices) if (i < it.cvars.size) it.cvars[i] = data.defines[i] }
    init {
        // the monkey is not in the scene until the creature of the trapdoor has climbed out
        if (data.useMonkey) vm.objects[monkeyIndex].let { it.stage = -1; it.room = -1; it.life = -1; it.body = -1 }
    }
    val objects: Array<GameObject> = Array(vm.objects.size) { GameObject(vm.objects[it]) }

    /** The room the game is in now (colliders, zones, cameras); it changes when the player enters another room or floor. */
    var roomView: RoomView = data.rooms.room(stage, room)
        private set

    /** Counts the room changes, so the screen knows when to load the new cameras. */
    var roomVersion = 0
        private set

    /** The player's object (the one that runs life script 549) or -1. */
    val playerIndex: Int = vm.objects.indexOfFirst { it.life == PLAYER_SCRIPT }

    /** The object the camera follows. */
    var cameraTarget = playerIndex
        private set

    /** Items taken, newest first after the first entry (as the original's inventory list). */
    val inventory = ArrayList<Int>()

    /** May the inventory be opened right now? (ALLOW_INVENTORY of the scripts) */
    var allowInventory = false
        private set

    /** An item the player found and may take: the screen shows the "found" window and answers with [resolveFound]. */
    var pendingFound = -1
        private set

    /** A book, letter or notebook the scripts want shown: (text entry, type), or null. The screen shows the reader and calls [finishRead]. */
    var pendingRead: IntArray? = null
        private set

    /** Set when the player was sent out of this room (to another floor); the screen shows the end of what is available. */
    var exited = false
        private set

    /** For testing: notable decisions of the game logic are reported here. */
    var trace: ((String) -> Unit)? = null

    /** Dark room (LIGHT 0): the room picture is not shown, only the objects. */
    var lightOff = false
        private set

    /** The effects that are alive now (SPECIAL). */
    val effects = ArrayList<Effect>()

    /** Keys of this tick (bits of [Joy]) and whether the action key was pressed. */
    private var joy = 0
    private var run = false

    /** Set when a script ends the game (death). */
    var gameOver = false
        private set

    val player: GameObject? get() = objects.getOrNull(playerIndex)

    private val moveAnims = HashMap<Int, MoveAnims>()
    private var lastCameraTarget = -1

    init {
        vm.trace = null
        for (o in vm.objects) if (o.stage == stage && o.room == room) activate(objects[o.index])
    }

    // ------------------------------------------------------------------------------------------------------------------ scene membership

    private fun activate(g: GameObject) {
        val w = g.w
        w.active = true
        w.chronoStart = time
        w.roomChronoStart = time
        w.col = -1; w.colBy = -1; w.hit = -1; w.hitBy = -1; w.hardCol = -1; w.hardDec = -1
        createActor(g, w.anim, w.animType, w.animInfo)
        if (w.typeZv == 4) { // the box of the room's collider with this object's parameter (foundName), and the place at its centre
            val h = roomView.hardcols.firstOrNull { it.type == 9 && it.parameter == w.foundName }
            g.fixedZv = h?.let { intArrayOf(it.x1, it.x2, it.y1, it.y2, it.z1, it.z2) }
            if (h != null) {
                w.x = (h.x1 + h.x2) / 2; w.y = (h.y1 + h.y2) / 2; w.z = (h.z1 + h.z2) / 2
            }
        }
    }

    private fun deactivate(g: GameObject) {
        g.w.active = false
        g.actor = null
        g.asset = null
    }

    private fun createActor(g: GameObject, anim: Int, animType: Int, animInfo: Int) {
        val w = g.w
        if (w.body < 0) {
            g.actor = null
            g.asset = null
            return
        }
        val asset = data.body(w.body)
        if (asset == null) {
            g.actor = null
            g.asset = null
            return
        }
        g.asset = asset
        val groups = asset.groupCount.coerceAtLeast(1)
        val rig = ActorRig(groups, Array(groups) { GroupState() }, asset.zv)
        val old = g.actor
        val a = Actor(
            rig, { n -> data.anim(n) ?: error("animation $n") },
            old?.worldX ?: w.x, old?.worldY ?: w.y, old?.worldZ ?: w.z, old?.beta ?: w.beta,
            if (data.anim(anim) != null) anim else -1, time, roomView.hardcols, w.typeZv, animType, animInfo,
        )
        a.followTarget = { n -> objects.getOrNull(n)?.takeIf { it.w.active }?.actor?.let { intArrayOf(it.renderX, it.renderZ) } }
        a.trackSource = { n -> data.track(n) }
        a.roomNumber = w.room
        a.roomShift = { target ->
            val v = try { data.rooms.room(stage, target) } catch (_: Exception) { null }
            v?.let { intArrayOf((roomView.worldX - it.worldX) * 10, (roomView.worldY - it.worldY) * 10, (roomView.worldZ - it.worldZ) * 10) }
        }
        a.onTrackWarp = { room, x, y, z ->
            w.room = room
            w.x = x; w.y = y; w.z = z
            moved(w.index)
        }
        if (w.typeZv == 3) Actor.boxFor(asset.zv, 3, a.worldX, a.worldY, a.worldZ, a.beta).copyInto(a.zv)
        old?.let { a.restoreMove(it.trackMode, it.trackNumber, it.positionInTrack, it.mark) }
        if (old == null) a.restoreMove(w.trackMode, w.trackNumber, 0, -1)
        old?.pendingAnim?.let { a.playAnim(it[0], it[1], it[2]) } // an animation that was just requested survives the change of body
        g.actor = a
    }

    // ------------------------------------------------------------------------------------------------------------------ one tick

    /** One tick (60 per second) of the game; [keys] = [Joy] bits, [action] = the action key was pressed, [runHint] = he should run when going forward. */
    fun tick(timer: Int, keys: Int, action: Boolean, runHint: Boolean) {
        if (pendingFound >= 0 || pendingRead != null || exited || gameOver) return
        time = timer
        joy = keys
        run = runHint
        vm.joyd = keys
        vm.click = if (action) 1 else 0
        vm.action = if (action) ACTION_CLICK else 0

        // the item in the hand reacts to the action key (the original runs its found-life script every tick)
        if (vm.inHand >= 0) runFoundLife(vm.inHand, vm.action)

        // forget the collisions of the last tick
        for (g in objects) {
            val w = g.w
            if (!w.active) continue
            w.col = -1; w.colBy = -1; w.hit = -1; w.hitBy = -1; w.hardDec = -1; w.hardCol = -1
            g.actor?.hardCol = -1
        }

        // animations and movement of every active actor (GereAnim), triggers (GereDec)
        for (g in objects) {
            val w = g.w
            if (!w.active) continue
            val a = g.actor ?: continue
            if (g === player) {
                a.colliders = colliders(g)
                a.pushObject = { i, dx, dz -> pushObject(g, i, dx, dz) }
            }
            a.update(time)
            w.anim = a.currentAnim
            w.frame = a.frameIndex
            w.endFrame = if (a.endFrameTick) 1 else 0
            w.flagEndAnim = if (a.flagEndAnim) 1 else 0
            w.hardCol = a.hardCol
            w.trackMode = a.trackMode
            w.trackNumber = a.trackNumber
            w.mark = a.mark
            w.x = a.renderX
            w.y = a.renderY
            w.z = a.renderZ
            w.beta = a.beta and 0x3FF
            if (g === player) {
                for (i in a.collidedAll) {
                    val other = colliderOwner(i)
                    if (other >= 0) {
                        objects[other].w.colBy = g.w.index
                        if (w.col == -1) w.col = other
                        // an item lying on the floor: walking into it offers it (FoundObjet with param 0)
                        if (objects[other].w.flags and AF_FOUNDABLE != 0 && a.trackMode == 1) offerItem(other)
                    }
                }
            }
            if (g !== player && a.trackMode != 0) touchObjects(g, a)
            if (w.flags and AF_TRIGGER != 0 && a.triggersOn) checkZones(g)
            if (g.actionType != 0) processAction(g)
            if (w.flags and AF_FALLABLE != 0) processFall(g)
            if (g.flightTicks >= 0) processFlight(g)
        }

        effects.removeAll { time - it.born > (when (it.kind) { 0 -> EVAPORATION_TICKS; 3 -> 3; 4 -> SMOKE_TICKS; 5 -> HOLE_SMOKE_TICKS; else -> EFFECT_TICKS }) }

        // the life scripts of all active objects
        for (g in objects) {
            val w = g.w
            if (!w.active) continue
            if (w.life != -1 && w.lifeMode != -1) vm.runObject(w.index)
            if (pendingFound >= 0 || pendingRead != null || exited || gameOver) break
        }
    }

    /**
     * A creature on its way touches other objects (checkCollision of the original reports every box it overlaps, it does not need to be blocked): the creature gets
     * COL and the touched object COL_BY, which is how the scripts notice it, for example the window that cracks when a creature breaks through.
     */
    private fun touchObjects(g: GameObject, a: Actor) {
        val z = a.zv
        for (o in objects) {
            if (o === g || o === player || !o.w.active) continue
            val b = o.actor?.zv ?: continue
            if (z[0] < b[1] && b[0] < z[1] && z[2] < b[3] && b[2] < z[3] && z[4] < b[5] && b[4] < z[5]) {
                o.w.colBy = g.w.index
                if (g.w.col == -1) g.w.col = o.w.index
            }
        }
    }

    /** Boxes the player collides with: the other objects that have a body and a box (items on the floor are picked up instead). */
    private var colliderIndexes = IntArray(0)

    private fun colliders(self: GameObject): List<Actor.Collider> {
        val boxes = ArrayList<Actor.Collider>()
        val owners = ArrayList<Int>()
        for (g in objects) {
            if (g === self || !g.w.active) continue
            val a = g.actor ?: continue
            val foundable = g.w.flags and AF_FOUNDABLE != 0
            boxes.add(Actor.Collider(a.zv, !foundable && g.w.flags and AF_MOVABLE != 0, !foundable))
            owners.add(g.w.index)
        }
        colliderIndexes = owners.toIntArray()
        return boxes
    }

    /** The player runs into a movable object: it moves with his step when no wall and no other object is in its way (isPushPossible of the original). */
    private fun pushObject(self: GameObject, colliderIndex: Int, dx: Int, dz: Int): Boolean {
        val target = objects.getOrNull(colliderOwner(colliderIndex)) ?: return false
        val a = target.actor ?: return false
        val moved = a.zv.copyOf()
        moved[0] += dx; moved[1] += dx; moved[4] += dz; moved[5] += dz
        fun hits(b: IntArray) = b[0] < moved[1] && moved[0] < b[1] && b[2] < moved[3] && moved[2] < b[3] && b[4] < moved[5] && moved[4] < b[5]
        if (roomView.hardcols.any { hits(intArrayOf(it.x1, it.x2, it.y1, it.y2, it.z1, it.z2)) }) return false
        for (g in objects) {
            if (g === target || !g.w.active) continue
            val other = g.actor ?: continue
            if (hits(other.zv)) return false
        }
        a.pushBy(dx, dz)
        return true
    }

    private fun colliderOwner(i: Int): Int = colliderIndexes.getOrElse(i) { -1 }

    /** GereDec: the scene zone the object stands in sets its HARD_DEC (type 9), takes it into the next room (type 0) or hands it to its floor script (type 10). */
    private fun checkZones(g: GameObject) {
        val a = g.actor ?: return
        val x = a.renderX
        val y = a.renderY
        val z = a.renderZ
        for (zone in roomView.zones) {
            if (!zone.contains(x, y, z)) continue
            when (zone.type) {
                0 -> if (g.w.index == playerIndex) enterNextRoom(g, zone.parameter)
                9 -> g.w.hardDec = zone.parameter
                10 -> { // stage zone: the object's own floor script takes over
                    val life = g.w.floorLife
                    if (life == -1) return
                    g.w.life = life
                    g.w.hardDec = zone.parameter
                }
            }
            return // the first zone only
        }
    }

    /** The player walked through a room-change zone: the coordinates move to the origin of the next room of the same floor and the scene changes. */
    private fun enterNextRoom(g: GameObject, nextRoom: Int) {
        val a = g.actor ?: return
        val next = try { data.rooms.room(stage, nextRoom) } catch (_: Exception) { return }
        val dx = (next.worldX - roomView.worldX) * 10
        val dy = (next.worldY - roomView.worldY) * 10
        val dz = (next.worldZ - roomView.worldZ) * 10
        a.moveToRoomOrigin(dx, dy, dz)
        g.w.room = nextRoom
        g.w.chronoStart = time
        changeRoom(stage, nextRoom, null)
    }

    /**
     * The scene changes to room [newRoom] of floor [newStage]: the objects of the old room leave the scene, those of the new room join it, the player stays and
     * is placed at [place] (x, y, z) unless it is null (his coordinates are already those of the new room).
     */
    private fun changeRoom(newStage: Int, newRoom: Int, place: IntArray?) {
        val view = try {
            data.rooms.room(newStage, newRoom)
        } catch (_: Exception) {
            exited = true
            ui.leftRoom(newStage, newRoom)
            return
        }
        for (g in objects) if (g.w.active && g.w.index != playerIndex) deactivate(g)
        stage = newStage
        room = newRoom
        roomView = view
        roomVersion++
        val p = player
        p?.w?.stage = newStage
        p?.w?.room = newRoom
        p?.actor?.let { a ->
            a.hardcols = view.hardcols
            a.roomNumber = newRoom
            if (place != null) a.teleport(place[0], place[1], place[2], p.w.beta)
        }
        for (o in vm.objects) if (o.index != playerIndex && o.stage == newStage && o.room == newRoom && !objects[o.index].w.active) activate(objects[o.index])
        p?.let {
            it.w.roomChronoStart = time
            it.w.hardDec = -1
        }
    }

    // ------------------------------------------------------------------------------------------------------------------ items

    /** The player's answer to the "found" window. */
    fun resolveFound(takeIt: Boolean) {
        val item = pendingFound
        pendingFound = -1
        if (item < 0) return
        if (takeIt) takeItem(item) else vm.objects[item].trackNumber = (time).coerceAtLeast(1)
    }

    /** Is the item too heavy to carry together with what he has? */
    fun tooHeavy(item: Int): Boolean {
        val weight = inventory.sumOf { vm.objects[it].weight }
        return vm.objects[item].weight + weight > (vm.cvars.getOrElse(MAX_WEIGHT_CVAR) { 0 }.toInt().takeIf { it > 0 } ?: Int.MAX_VALUE)
    }

    private fun takeItem(item: Int) {
        val w = vm.objects[item]
        if (inventory.isEmpty()) inventory.add(item) else inventory.add(1.coerceAtMost(inventory.size), item)
        runFoundLife(item, ACTION_TAKE)
        objects[item].let { if (it.w.active) deactivate(it) }
        w.foundFlag = (w.foundFlag and 0xBFFF) or 0x8000
        w.room = -1
        w.stage = -1
    }

    /** Runs the found-life script of an item with the given action (executeFoundLife). */
    fun runFoundLife(item: Int, action: Int) {
        val w = vm.objects.getOrNull(item) ?: return
        if (w.foundLife < 0) return
        val saved = vm.action
        vm.action = action
        vm.run(item, w.foundLife)
        vm.action = saved
    }

    /** The player chose [action] (bit mask) for [item] in the inventory. */
    fun useItem(item: Int, action: Int) {
        runFoundLife(item, action)
    }

    // ------------------------------------------------------------------------------------------------------------------ LifeHost: expressions

    /** Box of an object: its actor's, or a point at its place for the invisible helper objects (body -1), as the original gives them a zero-size box. */
    private fun boxOf(i: Int): IntArray? {
        val g = objects.getOrNull(i) ?: return null
        if (!g.w.active) return null
        g.fixedZv?.let { return it }
        g.actor?.let { return it.zv }
        val w = g.w
        return intArrayOf(w.x, w.x, w.y, w.y, w.z, w.z)
    }

    override fun posRel(actor: Int, other: Int): Int {
        val az = boxOf(actor) ?: return 0
        val bz = boxOf(other) ?: return 0
        val beta = (objects[actor].actor?.beta ?: objects[actor].w.beta) and 0x3FF
        var counter = 3
        if (beta in 0x80 until 0x180) counter = 2
        if (beta in 0x180 until 0x280) counter = 1
        if (beta in 0x280 until 0x380) counter = 0
        val cx = (bz[0] + bz[1]) / 2
        val cz = (bz[4] + bz[5]) / 2
        val z = az
        if (z[5] >= cz && z[4] <= cz) {
            if (z[1] < cx) {
                counter++
            } else if (z[0] <= cx) {
                return 0
            } else {
                counter += 3
            }
        } else if (z[1] >= cx || z[0] <= cx) {
            if (z[5] < cz) {
                counter += 2
            } else if (z[4] <= cz) {
                return 0
            }
        } else {
            return 0
        }
        return POS_REL[counter]
    }

    override fun dist(actor: Int, other: Int): Int {
        val a = objects.getOrNull(actor)?.takeIf { it.w.active }?.w ?: return 32000
        val b = objects.getOrNull(other)?.takeIf { it.w.active }?.w ?: return 32000
        return Math.abs(a.x - b.x) + Math.abs(a.y - b.y) + Math.abs(a.z - b.z)
    }

    override fun testZvEndAnim(actor: Int, anim: Int, param: Int): Int {
        val a = objects.getOrNull(actor)?.actor ?: return 0
        val d = data.anim(anim) ?: return 0
        var stepX = 0
        var stepZ = 0
        for (f in d.frames) {
            stepZ += f.stepX
            stepX += f.stepZ
        }
        val c = Math.cos((a.beta and 0x3FF) * 2.0 * Math.PI / 1024.0)
        val s = Math.sin((a.beta and 0x3FF) * 2.0 * Math.PI / 1024.0)
        val dx = (stepX * c).toInt()
        val dz = (stepX * s).toInt()
        val zv = a.zv.copyOf()
        zv[0] += dx; zv[1] += dx; zv[2] += param; zv[3] += param; zv[4] += dz; zv[5] += dz
        fun hits(box: IntArray) = roomView.hardcols.any { h -> h.x1 < box[1] && box[0] < h.x2 && h.y1 < box[3] && box[2] < h.y2 && h.z1 < box[5] && box[4] < h.z2 }
        if (hits(zv)) return 0
        zv[2] += 100; zv[3] += 100
        return if (hits(zv)) 1 else 0
    }

    // ------------------------------------------------------------------------------------------------------------------ LifeHost: objects

    override fun body(obj: Int) {
        val g = objects[obj]
        if (!g.w.active) return
        if (g.asset?.number == g.w.body) return // the same body again changes nothing (LM_BODY of the original)
        val a = g.actor
        val anim = a?.currentAnim ?: g.w.anim
        createActor(g, if (anim != -1) anim else -1, 0, -1)
    }

    override fun anim(obj: Int, anim: Int, type: Int, info: Int) {
        val a = objects.getOrNull(obj)?.actor ?: return
        a.playAnim(anim, type, info)
    }

    override fun doMove(obj: Int) {
        val g = objects.getOrNull(obj) ?: return
        val a = g.actor ?: return
        if (g === player && run && a.speed == 4 && joy and Joy.FORWARD != 0) a.speed = 5
        if (g === player && !run && a.speed == 5 && joy and Joy.FORWARD != 0) a.speed = 4
        a.processTrack(joy, time)
        // the script reads these right after DO_MOVE (a path that just ended reports track -1 in the same run)
        g.w.trackNumber = a.trackNumber
        g.w.trackMode = a.trackMode
        g.w.mark = a.mark
    }

    override fun animMove(obj: Int, anims: IntArray) {
        val a = objects.getOrNull(obj)?.actor ?: return
        a.chooseMoveAnim(MoveAnims(anims[0], anims[1], anims[2], anims[3], anims[4], anims[5], anims[6]))
    }

    /** HIT: a punch or weapon swing starts its animation; at the given frame the zone around the hand (the bone group's base point) hits what stands in it. */
    override fun hit(obj: Int, anim: Int, frame: Int, group: Int, size: Int, force: Int, nextAnim: Int) {
        val g = objects.getOrNull(obj) ?: return
        if (g.actor?.playAnim(anim, 0, nextAnim) != true) return // only a newly started blow arms the hit zone
        g.actionType = 1
        g.actionAnim = anim; g.actionFrame = frame; g.actionGroup = group; g.actionRange = size; g.actionForce = force
    }

    /** FIRE: a shot; the animation cannot be interrupted, at its frame a line is traced from the weapon along the heading (animAction.cpp DO_TIR). */
    override fun fire(obj: Int, anim: Int, frame: Int, hotPoint: Int, range: Int, force: Int, nextAnim: Int) {
        val g = objects.getOrNull(obj) ?: return
        if (g.actor?.playAnim(anim, 2, nextAnim) != true) return
        g.actionType = 4
        g.actionAnim = anim; g.actionFrame = frame; g.actionGroup = hotPoint; g.actionRange = range; g.actionForce = force
    }

    /** The point of the bone group [group] in the object's space (its base vertex, turned by the heading); null when the body has no such group. */
    private fun hotPoint(g: GameObject, group: Int): IntArray? {
        val parts = g.asset?.parts ?: return null
        val a = g.actor ?: return null
        if (group < 0 || group >= parts.groups.size / 12) return null
        val posed = BodyPose.pose(parts.vertices, parts.groups, parts.groupOrder, a.state)
        val v = parts.groups[group * 12 + 2]
        if (v * 3 + 2 >= posed.size) return null
        val ang = (a.beta and 0x3FF) * 2.0 * Math.PI / 1024.0
        val c = Math.cos(ang)
        val sn = Math.sin(ang)
        val x = posed[v * 3]
        val z = posed[v * 3 + 2]
        return intArrayOf((x * c - z * sn).toInt(), posed[v * 3 + 1].toInt(), (x * sn + z * c).toInt())
    }

    /** GereFrappe: the state machine of blows and shots of one object, once per tick after its animation. */
    private fun processAction(g: GameObject) {
        val w = g.w
        when (g.actionType) {
            1, 10 -> {
                if (g.actionType == 1 && w.anim == g.actionAnim) g.actionType = 10
                if (g.actionType == 10) {
                    if (w.anim != g.actionAnim) {
                        g.actionType = 0
                    } else if (w.frame == g.actionFrame) {
                        g.actionType = 2
                    }
                }
            }
            2 -> {
                if (w.anim != g.actionAnim) g.actionType = 0
                val hot = hotPoint(g, g.actionGroup) ?: intArrayOf(0, 0, 0)
                val a = g.actor ?: return
                val x = a.renderX + hot[0]
                val y = a.renderY + hot[1]
                val z = a.renderZ + hot[2]
                val r = g.actionRange
                for (other in objects) {
                    if (other === g || !other.w.active) continue
                    val zv = other.actor?.zv ?: continue
                    if (!(zv[0] < x + r && x - r < zv[1] && zv[2] < y + r && y - r < zv[3] && zv[4] < z + r && z - r < zv[5])) continue
                    w.hit = other.w.index
                    other.w.hitBy = w.index
                    other.w.hitForce = g.actionForce
                    if (other.actor?.animated == true) {
                        g.actionType = 0
                        return
                    }
                }
            }
            6 -> {
                if (w.anim != g.actionAnim) return
                val a = g.actor ?: return
                val item = vm.objects.getOrNull(g.actionParam) ?: return
                val hot = hotPoint(g, g.actionGroup) ?: intArrayOf(0, 0, 0)
                val bz = data.body(item.body)?.zv ?: intArrayOf(-50, 50, -50, 50, -50, 50)
                val x = a.renderX + hot[0]
                val y = a.renderY + hot[1]
                val z = a.renderZ + hot[2]
                val inWall = roomView.hardcols.any { h -> h.x1 < x + bz[1] && x + bz[0] < h.x2 && h.y1 < y + bz[3] && y + bz[2] < h.y2 && h.z1 < z + bz[5] && z + bz[4] < h.z2 }
                if (inWall) { // the hand is in a wall: the item just drops at his feet
                    trace?.invoke("throw: hand at $x,$y,$z in wall ${roomView.hardcols.firstOrNull { h -> h.x1 < x + bz[1] && x + bz[0] < h.x2 && h.y1 < y + bz[3] && y + bz[2] < h.y2 && h.z1 < z + bz[5] && z + bz[4] < h.z2 }?.let { "${it.x1}..${it.x2} ${it.y1}..${it.y2} ${it.z1}..${it.z2}" }} bz=${bz.toList()}")
                    g.actionType = 0
                    putAt(item.index, w.index)
                } else if (w.frame == g.actionFrame) {
                    g.actionType = 0
                    releaseThrow(g)
                }
            }
            4 -> {
                if (w.anim != g.actionAnim || w.frame != g.actionFrame) return
                g.actionType = 5
                shoot(g)
                g.actionType = 0
            }
        }
    }

    /** DO_TIR: a line from the weapon's point along the heading; the first object on it (before a wall) is hit. */
    private fun shoot(g: GameObject) {
        val a = g.actor ?: return
        val hot = hotPoint(g, g.actionGroup) ?: intArrayOf(0, 0, 0)
        var x = a.renderX + hot[0]
        val y = a.renderY + hot[1]
        var z = a.renderZ + hot[2]
        // the flash of the gun: the body BODY_FLAMME at the hand for one frame (mode 3 of InitSpecialObjet), turned like the shooter
        effects.add(Effect(3, x, y, z, time, null, 0, a.beta and 0x3FF, 0, vm.cvars[CVAR_BODY_FLAMME].toInt()))
        val step = g.actionRange.coerceAtLeast(10) * 2
        val ang = ((a.beta and 0x3FF) - 0x100) * 2.0 * Math.PI / 1024.0
        // walkStep(step, 0, beta - 0x100): the step turned by the heading
        val dx = (step * Math.cos(ang)).toInt()
        val dz = (step * Math.sin(ang)).toInt()
        val p = g.actionRange.coerceAtLeast(10)
        // the impact: white dots (mode 2) where the ray was last (before the step that stopped it), whether it touched something or not
        fun impact(px: Int, pz: Int) { effects.add(Effect(2, px, y, pz, time, null, 0, (-a.beta) and 0x3FF, 0)) }
        while (true) {
            val prevX = x
            val prevZ = z
            x += dx
            z += dz
            if (x > 20000 || x < -20000 || z > 20000 || z < -20000) { impact(prevX, prevZ); return }
            if (roomView.hardcols.any { h -> h.x1 < x + p && x - p < h.x2 && h.y1 < y + p && y - p < h.y2 && h.z1 < z + p && z - p < h.z2 }) { impact(prevX, prevZ); return } // a wall stops the shot
            for (other in objects) {
                if (other === g || !other.w.active || other.w.flags and AF_SPECIAL != 0) continue // (effects cannot be hit)
                val zv = other.actor?.zv ?: continue
                if (zv[0] < x + p && x - p < zv[1] && zv[2] < y + p && y - p < zv[3] && zv[4] < z + p && z - p < zv[5]) {
                    g.w.hit = other.w.index
                    other.w.hitBy = g.w.index
                    other.w.hitForce = g.actionForce
                    // the place of the impact is the hot point from now on (the hit effect appears there)
                    g.shotPoint = intArrayOf(prevX - a.renderX, y - a.renderY, prevZ - a.renderZ)
                    impact(prevX, prevZ)
                    return
                }
            }
        }
    }

    override fun trackMode(obj: Int, mode: Int, number: Int) {
        objects.getOrNull(obj)?.actor?.initMove(mode, number)
    }

    override fun manualRot(obj: Int) {
        objects.getOrNull(obj)?.actor?.manualRot(joy, time, 240) // GereManualRot(240) in AITD1
    }

    override fun resetMove(obj: Int) {
        objects.getOrNull(obj)?.actor?.resetMove()
    }

    override fun continueTrack(obj: Int) {
        objects.getOrNull(obj)?.actor?.continueTrack()
    }

    override fun setBeta(obj: Int, beta: Int, speed: Int) {
        objects.getOrNull(obj)?.actor?.turnTo(beta, speed, time)
    }

    override fun testCol(obj: Int, on: Boolean) {
        objects.getOrNull(obj)?.actor?.wallCollision = on
    }

    /** STAGE, ANGLE: the object's position, room or angles were set by a script. */
    override fun moved(obj: Int) {
        val g = objects[obj]
        val w = g.w
        val inScene = w.stage == stage && w.room == room
        if (obj == playerIndex && !inScene && w.stage >= 0) {
            changeRoom(w.stage, w.room, intArrayOf(w.x, w.y, w.z))
            return
        }
        if (inScene && !w.active) {
            activate(g)
        } else if (!inScene && w.active) {
            deactivate(g)
        } else if (w.active) {
            g.actor?.teleport(w.x, w.y, w.z, w.beta)
        }
    }

    override fun delete(obj: Int) {
        objects.getOrNull(obj)?.let { if (it.w.active) deactivate(it) }
        if (inventory.remove(obj)) { /* an item that is destroyed leaves the inventory */ }
        if (vm.inHand == obj) vm.inHand = -1
    }

    override fun found(item: Int) {
        val w = vm.objects.getOrNull(item) ?: return
        if (w.foundFlag and 0xC000 != 0) return
        offerItem(item)
    }

    /** Opens the "found" window for an item unless it was just left behind (the original waits 300 ticks before it asks again). */
    private fun offerItem(item: Int) {
        val w = vm.objects.getOrNull(item) ?: return
        if (w.trackNumber > 0 && time - w.trackNumber < REOPEN_TICKS) return
        w.trackNumber = 0
        if (pendingFound < 0) pendingFound = item
    }

    /** PUT: an item is placed in the world at a position (it leaves the inventory). */
    override fun put(item: Int, x: Int, y: Int, z: Int, stage: Int, room: Int, alpha: Int, beta: Int, gamma: Int) {
        val w = vm.objects.getOrNull(item) ?: return
        w.x = x; w.y = y; w.z = z; w.stage = stage; w.room = room; w.alpha = alpha; w.beta = beta; w.gamma = gamma
        removeFromInventory(item)
        w.foundFlag = w.foundFlag or 0x4000
        moved(item)
    }

    /** PUT_AT / DROP: an item is placed where another object is (it leaves the inventory and can be picked up again). */
    override fun putAt(item: Int, target: Int) {
        val w = vm.objects.getOrNull(item) ?: return
        val t = vm.objects.getOrNull(target) ?: return
        removeFromInventory(item)
        w.x = t.x; w.y = t.y; w.z = t.z; w.stage = t.stage; w.room = t.room; w.alpha = t.alpha; w.beta = t.beta; w.gamma = t.gamma
        w.foundFlag = w.foundFlag or 0x4000
        w.flags = w.flags or AF_FOUNDABLE
        w.trackNumber = time.coerceAtLeast(1) // do not offer it again at once
        moved(item)
    }

    override fun drop(item: Int, from: Int) = putAt(item, from)

    private fun removeFromInventory(item: Int) {
        inventory.remove(item)
        vm.objects[item].foundFlag = vm.objects[item].foundFlag and 0x7FFF
        if (vm.inHand == item) vm.inHand = -1
    }

    override fun read(index: Int, type: Int) {
        pendingRead = intArrayOf(index, type)
    }

    /** The player closed the reader. */
    fun finishRead() {
        pendingRead = null
    }

    override fun take(item: Int) {
        if (vm.objects.getOrNull(item) != null && !inventory.contains(item)) takeItem(item)
    }

    override fun lifeChanged(obj: Int, life: Int) {
        // the creature of the trapdoor has climbed out and starts to fight: now the monkey climbs out too
        if (obj == OBJ_HOLE_CREATURE && life == LIFE_CREATURE_FIGHT) spawnMonkey()
        // not in the original: when the trapdoor starts to open, smoke comes out of the hole, until the creature has climbed out
        if (obj == OBJ_TRAPDOOR && life == LIFE_TRAPDOOR_OPENING) {
            vm.objects.getOrNull(OBJ_HOLE_CREATURE)?.let { effects.add(Effect(5, it.x, 0, it.z, time, null)) }
        }
    }

    /** The monkey climbs out of the hole behind the creature, by the same scripts (copies with its own numbers) and the same path; it closes the trapdoor when it is out. */
    private fun spawnMonkey() {
        if (!data.useMonkey) return
        val m = vm.objects[monkeyIndex]
        if (m.stage >= 0) return
        val src = WorldObject(OBJ_HOLE_CREATURE, data.objectData)
        m.body = MonkeyBody.BODY; m.flags = src.flags; m.typeZv = src.typeZv
        m.x = src.x; m.y = src.y; m.z = src.z; m.alpha = src.alpha; m.beta = src.beta; m.gamma = src.gamma
        m.stage = stage; m.room = room
        m.lifeMode = src.lifeMode; m.life = LIFE_CREATURE_CLIMBED + MONKEY_SCRIPTS; m.floorLife = -1
        m.anim = ANIM_CLIMB; m.animType = src.animType; m.animInfo = src.animInfo; m.frame = 0
        m.trackMode = 3; m.trackNumber = 0
        moved(monkeyIndex)
        anim(monkeyIndex, ANIM_CLIMB, 1, -1)
        trackMode(monkeyIndex, 3, 0)
        cameraTarget = monkeyIndex
        vm.vars[20] = (vm.vars[20] + 1).toShort()
        ui.playSample(61)
    }

    override fun cameraTarget(obj: Int) {
        cameraTarget = obj
    }

    override fun inventory(allowed: Boolean) {
        allowInventory = allowed
    }

    // ------------------------------------------------------------------------------------------------------------------ LifeHost: presentation

    override fun message(id: Int) {
        data.texts[id]?.let { ui.message(it) }
    }

    override fun sample(n: Int) {
        ui.playSample(n)
    }

    override fun repSample(n: Int) {
        ui.playSample(n)
    }

    override fun music(n: Int) {
        ui.music(n, true)
    }

    override fun nextMusic(n: Int) {
        ui.nextMusic(n)
    }

    override fun sampleThen(first: Int, then: Int) {
        ui.playSampleThen(first, then, false)
    }

    override fun sampleThenRepeat(first: Int, then: Int) {
        ui.playSampleThen(first, then, true)
    }

    override fun stopSample() {
        ui.stopSamples()
    }

    override fun randomFrequency(percent: Int) {
        ui.randomFrequency(percent)
    }

    override fun fadeMusic(n: Int) {
        ui.music(n, true)
    }

    override fun gameOver() {
        gameOver = true
        ui.gameOver()
    }

    /** SPECIAL: a visual effect at the object (0 evaporation around its box, 1 hit effect at the hand of what hit it, 4 smoke). */
    override fun special(obj: Int, kind: Int) {
        val g = objects.getOrNull(obj) ?: return
        val a = g.actor
        when (kind) {
            0 -> if (a != null) effects.add(Effect(0, a.renderX, a.renderY, a.renderZ, time, a.zv.copyOf(), g.w.alpha, a.beta and 0x3FF, g.w.gamma))
            1 -> { // flow at the hand of the one that hit this object
                val by = objects.getOrNull(g.w.hitBy)
                val h = by?.let { b -> b.shotPoint?.also { b.shotPoint = null } ?: hotPoint(b, b.actionGroup) }
                val b = by?.actor
                if (b != null) effects.add(Effect(1, b.renderX + (h?.get(0) ?: 0), b.renderY + (h?.get(1) ?: 0), b.renderZ + (h?.get(2) ?: 0), time, null, 0, (-b.beta) and 0x3FF, 0))
            }
            4 -> if (a != null) effects.add(Effect(4, a.renderX, a.renderY, a.renderZ, time, null, g.w.alpha, a.beta and 0x3FF, g.w.gamma))
        }
    }

    override fun light(on: Int) {
        lightOff = on == 0
    }

    override fun picture(index: Int, ticks: Int, sample: Int) {
        ui.picture(index, ticks, sample)
    }

    override fun endSequence() {
        exited = true
        ui.endSequence()
    }

    override fun upCoorY(obj: Int) {
        objects.getOrNull(obj)?.actor?.moveYBy(-2000)
    }

    override fun setAlpha(obj: Int, alpha: Int, speed: Int) {
        val w = objects.getOrNull(obj)?.w ?: return
        val g = objects[obj]
        val target = alpha and 1023
        if (w.alpha == target) { g.alphaTarget = -1; return }
        // the angle turns the short way to the target in a straight line over [speed] ticks (updateActorRotation of the original); a new target starts a new turn
        if (g.alphaTarget != target) {
            g.alphaTarget = target
            g.alphaStart = w.alpha
            g.alphaStartTime = time
        }
        val elapsed = time - g.alphaStartTime
        if (speed <= 1 || elapsed >= speed) {
            w.alpha = target
            g.alphaTarget = -1
        } else {
            val diff = ((target - g.alphaStart + 512) and 1023) - 512
            w.alpha = (g.alphaStart + diff * elapsed / speed) and 1023
        }
    }

    /** DEF_ZV, DEF_ABS_ZV and the DO_..._ZV commands: the box of the object is set or recomputed from its body. */
    override fun zv(obj: Int, op: String, values: IntArray) {
        val g = objects.getOrNull(obj) ?: return
        val a = g.actor ?: return
        val z = a.zv
        val x = a.renderX
        val y = a.renderY
        val zz = a.renderZ
        val b = g.asset?.zv
        when (op) {
            "DEF_ZV" -> { z[0] = x + values[0]; z[1] = x + values[1]; z[2] = y + values[2]; z[3] = y + values[3]; z[4] = zz + values[4]; z[5] = zz + values[5] }
            "DEF_ABS_ZV" -> values.copyInto(z, 0, 0, 6)
            "DO_MAX_ZV" -> if (b != null) Actor.boxFor(b, 0, x, y, zz, a.beta).copyInto(z)
            "DO_CARRE_ZV" -> if (b != null) Actor.boxFor(b, 2, x, y, zz, a.beta).copyInto(z)
            "DO_NORMAL_ZV" -> if (b != null) Actor.boxFor(b, 1, x, y, zz, a.beta).copyInto(z)
            "DO_ROT_ZV" -> if (b != null) Actor.boxFor(b, 3, x, y, zz, a.beta).copyInto(z)
            "DO_REAL_ZV" -> realBox(g)?.let { r -> intArrayOf(x + r[0], x + r[1], y + r[2], y + r[3], zz + r[4], zz + r[5]).copyInto(z) }
        }
    }

    /** The box around the posed, turned body (computeScreenBox and setupRealZv of the original), relative to the object place; null when it has no body. */
    private fun realBox(g: GameObject): IntArray? {
        val v = g.posedVertices() ?: return null
        val a = g.actor ?: return null
        val ang = (a.beta and 0x3FF) * 2.0 * Math.PI / 1024.0
        val c = Math.cos(ang)
        val s = Math.sin(ang)
        var x1 = Int.MAX_VALUE; var x2 = Int.MIN_VALUE; var y1 = Int.MAX_VALUE; var y2 = Int.MIN_VALUE; var z1 = Int.MAX_VALUE; var z2 = Int.MIN_VALUE
        for (i in 0 until v.size / 3) {
            val px = (v[i * 3] * c - v[i * 3 + 2] * s).toInt()
            val pz = (v[i * 3] * s + v[i * 3 + 2] * c).toInt()
            val py = v[i * 3 + 1].toInt()
            x1 = minOf(x1, px); x2 = maxOf(x2, px); y1 = minOf(y1, py); y2 = maxOf(y2, py); z1 = minOf(z1, pz); z2 = maxOf(z2, pz)
        }
        return intArrayOf(x1, x2, y1, y2, z1, z2)
    }

    // ------------------------------------------------------------------------------------------------------------------ falling

    /**
     * An object with the fall flag that has nothing under it falls: 2000 units over 40 ticks, until its box meets the floor, a collider or another object.
     * While it falls FALLING reads 1 (the player script plays the fall animation and rates the fall by ROOMY).
     */
    private fun processFall(g: GameObject) {
        val a = g.actor ?: return
        val w = g.w
        fun blocked(box: IntArray): Boolean =
            roomView.hardcols.any { h -> h.x1 < box[1] && box[0] < h.x2 && h.y1 < box[3] && box[2] < h.y2 && h.z1 < box[5] && box[4] < h.z2 } ||
                objects.any { o ->
                    o !== g && o.w.active && o.actor?.let { b ->
                        val zb = b.zv
                        zb[0] < box[1] && box[0] < zb[1] && zb[2] < box[3] && box[2] < zb[3] && zb[4] < box[5] && box[4] < zb[5]
                    } == true
                }
        if (g.fallTicks < 0) {
            // standing? the box 100 units lower meets something, or he is at the floor
            val under = a.zv.copyOf()
            under[3] += 100
            if (a.renderY >= -10 || blocked(under)) {
                w.falling = 0
            } else {
                g.fallStartY = a.renderY
                g.fallTicks = 0
                w.falling = 1
            }
            return
        }
        g.fallTicks++
        val target = (g.fallStartY + 2000 * g.fallTicks / 40).coerceAtMost(0)
        val dy = target - a.renderY
        val moved = a.zv.copyOf()
        moved[2] += dy
        moved[3] += dy
        if (target >= 0 || blocked(moved) || g.fallTicks >= 40) {
            // landed: he stops at the floor or on what carries him
            if (target >= 0) a.moveYBy(-a.renderY)
            g.fallTicks = -1
            w.falling = 0
        } else {
            a.moveYBy(dy)
            w.falling = 1
        }
    }

    // ------------------------------------------------------------------------------------------------------------------ throwing

    /** THROW: the throw animation plays; at its frame the item leaves the hand and flies along the heading (animAction.cpp states 6, 7, 9). */
    override fun throwObject(obj: Int, args: IntArray) {
        val g = objects.getOrNull(obj) ?: return
        if (g.actor?.playAnim(args[0], 2, args[6]) != true) return
        g.actionType = 6
        g.actionAnim = args[0]; g.actionFrame = args[1]; g.actionGroup = args[2]; g.actionParam = args[3]; g.actionForce = args[5]
        val item = vm.objects.getOrNull(args[3]) ?: return
        if (args[4] == 0) item.gamma -= 0x100
        item.foundFlag = item.foundFlag or 0x1000
    }

    /** HIT_OBJECT / STOP_HIT_OBJECT: a state of the original that nothing evaluates; kept so the calls of the scripts are understood. */
    override fun hitObject(obj: Int, a: Int, b: Int) {
        val g = objects.getOrNull(obj) ?: return
        g.actionType = 8; g.actionParam = a; g.actionForce = b
    }

    override fun stopHitObject(obj: Int) {
        val g = objects.getOrNull(obj) ?: return
        if (g.actionType == 8) { g.actionType = 0; g.actionParam = 0; g.actionForce = 0 }
    }

    /** The throw animation reached its frame: the item appears at the hand and starts to fly. */
    private fun releaseThrow(g: GameObject) {
        val item = vm.objects.getOrNull(g.actionParam) ?: return
        val a = g.actor ?: return
        val hot = hotPoint(g, g.actionGroup) ?: intArrayOf(0, 0, 0)
        // a wall at the hand: the item is not thrown, it is put down at the thrower
        val ib = data.body(item.body)?.zv
        if (ib != null) {
            val hx = a.renderX + hot[0]; val hy = a.renderY + hot[1]; val hz = a.renderZ + hot[2]
            if (roomView.hardcols.any { h -> h.x1 < hx + ib[1] && hx + ib[0] < h.x2 && h.y1 < hy + ib[3] && hy + ib[2] < h.y2 && h.z1 < hz + ib[5] && hz + ib[4] < h.z2 }) {
                g.actionType = 0
                putAt(item.index, g.w.index)
                return
            }
        }
        removeFromInventory(item.index)
        item.x = a.renderX + hot[0]; item.y = a.renderY + hot[1]; item.z = a.renderZ + hot[2]
        item.stage = stage; item.room = room
        item.alpha = g.w.alpha; item.beta = (a.beta + 0x200) and 0x3FF
        item.foundFlag = item.foundFlag and 0xBFFF
        item.flags = (item.flags or 0x85) and 0x20.inv()
        moved(item.index)
        val thrown = objects[item.index]
        thrown.flightX = item.x; thrown.flightZ = item.z
        thrown.flightBeta = a.beta
        thrown.flightThrower = g.w.index
        thrown.flightTicks = 0
        thrown.actionForce = g.actionForce
    }

    /**
     * A thrown item flies 3000 units in 60 ticks along the heading of the thrower. A wall, or an object that is hit (it gets HIT_BY and the force), stops it; the
     * object named by the CVar REVERSE_OBJECT sends it back. It lands at floor height on the last place that is free of walls (throwStoppedAt of the original).
     */
    private fun processFlight(g: GameObject) {
        val a = g.actor ?: run { g.flightTicks = -1; return }
        g.flightTicks++
        val dist = 3000 * minOf(g.flightTicks, 60) / 60
        val ang = (g.flightBeta and 0x3FF) * 2.0 * Math.PI / 1024.0
        val nx = g.flightX + (dist * Math.sin(ang)).toInt()
        val nz = g.flightZ - (dist * Math.cos(ang)).toInt()
        val y = a.renderY
        // the original tests a box of 200 units around the item
        val box = intArrayOf(nx - 200, nx + 200, y - 200, y + 200, nz - 200, nz + 200)
        fun hits(z: IntArray) = z[0] < box[1] && box[0] < z[1] && z[2] < box[3] && box[2] < z[3] && z[4] < box[5] && box[4] < z[5]
        var stop = roomView.hardcols.any { h -> hits(intArrayOf(h.x1, h.x2, h.y1, h.y2, h.z1, h.z2)) }
        for (o in objects) {
            if (o === g || !o.w.active || o.w.index == g.flightThrower) continue
            val zo = o.actor?.zv ?: continue
            if (!hits(zo)) continue
            if (o.w.index == vm.cvars[CVAR_REVERSE_OBJECT].toInt()) {
                // it flies back from where it is, and the thrower is now the one that sent it back
                g.flightThrower = o.w.index
                g.flightBeta = (g.flightBeta + 0x200) and 0x3FF
                g.flightX = g.w.x
                g.flightZ = g.w.z
                g.flightTicks = 0
                return
            }
            g.w.hit = o.w.index
            o.w.hitBy = g.w.index
            o.w.hitForce = g.actionForce
            stop = true
        }
        if (stop || g.flightTicks >= 60) {
            ui.playSample(vm.cvars[CVAR_SAMPLE_CHOC].toInt())
            landThrown(g, g.w.x, g.w.z)
            g.flightTicks = -1
            return
        }
        a.pushBy(nx - a.renderX, nz - a.renderZ)
        g.w.x = nx
        g.w.z = nz
    }

    /** The item comes to rest at floor height at the last free place on its way back from (x, z): 100 units at a time against its flight until no wall touches it. */
    private fun landThrown(g: GameObject, x: Int, z: Int) {
        val a = g.actor ?: return
        val b = g.asset?.zv ?: intArrayOf(-100, 100, -100, 100, -100, 100)
        val floorY = (a.renderY / 2000) * 2000
        val back = ((g.flightBeta + 0x200) and 0x3FF) * 2.0 * Math.PI / 1024.0
        var step = 0
        var px = x
        var pz = z
        while (step < 6000) {
            px = x + (step * Math.sin(back)).toInt()
            pz = z - (step * Math.cos(back)).toInt()
            val free = roomView.hardcols.none { h ->
                h.x1 < px + b[1] && px + b[0] < h.x2 && h.y1 < floorY + b[3] && floorY + b[2] < h.y2 && h.z1 < pz + b[5] && pz + b[4] < h.z2
            }
            if (free) break
            step += 100
        }
        a.pushBy(px - a.renderX, pz - a.renderZ)
        a.moveYBy(floorY - a.renderY)
        g.w.x = px
        g.w.y = floorY
        g.w.z = pz
        g.w.gamma = 0
    }

    // ------------------------------------------------------------------------------------------------------------------ saving

    /** Writes everything that makes up the state of the game: variables, world objects, inventory, the room the player is in and what moves in it. */
    fun saveState(out: java.io.DataOutputStream) {
        out.writeInt(stage); out.writeInt(room)
        out.writeInt(vm.vars.size); for (v in vm.vars) out.writeShort(v.toInt())
        out.writeInt(vm.cvars.size); for (v in vm.cvars) out.writeShort(v.toInt())
        out.writeInt(inventory.size); for (i in inventory) out.writeInt(i)
        out.writeInt(vm.inHand); out.writeInt(cameraTarget); out.writeBoolean(allowInventory); out.writeBoolean(lightOff); out.writeInt(vm.currentMusic)
        out.writeInt(objects.size)
        for (g in objects) {
            val w = g.w
            for (v in intArrayOf(w.body, w.flags, w.typeZv, w.foundBody, w.foundName, w.foundFlag, w.foundLife, w.x, w.y, w.z, w.alpha, w.beta, w.gamma, w.stage, w.room,
                w.lifeMode, w.life, w.floorLife, w.anim, w.frame, w.animType, w.animInfo, w.trackMode, w.trackNumber, w.weight, w.mark, w.hitForce, w.falling,
                time - w.chronoStart, time - w.roomChronoStart)) out.writeInt(v)
            out.writeBoolean(w.active)
            val a = g.actor
            out.writeBoolean(a != null)
            if (a != null) {
                out.writeInt(a.beta); out.writeInt(a.trackMode); out.writeInt(a.trackNumber); out.writeInt(a.positionInTrack); out.writeInt(a.mark)
                out.writeBoolean(a.wallCollision); out.writeBoolean(a.triggersOn); out.writeInt(a.memoX); out.writeInt(a.memoY); out.writeInt(a.memoZ)
            }
        }
    }

    /** Puts the game back into a state written by [saveState]; false when the saved room cannot be read. */
    fun loadState(inp: java.io.DataInputStream): Boolean {
        val newStage = inp.readInt()
        val newRoom = inp.readInt()
        val view = try { data.rooms.room(newStage, newRoom) } catch (_: Exception) { return false }
        val varCount = inp.readInt(); val vars = IntArray(varCount) { inp.readShort().toInt() }
        val cvarCount = inp.readInt(); val cvars = IntArray(cvarCount) { inp.readShort().toInt() }
        val inv = IntArray(inp.readInt()) { inp.readInt() }
        val inHand = inp.readInt(); val target = inp.readInt(); val allow = inp.readBoolean(); val dark = inp.readBoolean(); val music = inp.readInt()
        val count = inp.readInt()
        if (count != objects.size) return false
        class Saved(val f: IntArray, val active: Boolean, val actor: IntArray?, val flags: BooleanArray?)
        val saved = Array(count) {
            val f = IntArray(30) { inp.readInt() }
            val active = inp.readBoolean()
            if (inp.readBoolean()) {
                val ints = intArrayOf(inp.readInt(), inp.readInt(), inp.readInt(), inp.readInt(), inp.readInt())
                val wall = inp.readBoolean(); val trig = inp.readBoolean()
                val memo = intArrayOf(inp.readInt(), inp.readInt(), inp.readInt())
                Saved(f, active, ints + memo, booleanArrayOf(wall, trig))
            } else {
                Saved(f, active, null, null)
            }
        }
        // the scene is rebuilt from nothing
        for (g in objects) { g.actor = null; g.asset = null; g.w.active = false; g.actionType = 0; g.fallTicks = -1; g.flightTicks = -1; g.alphaTarget = -1 }
        stage = newStage; room = newRoom; roomView = view; roomVersion++
        for (i in 0 until minOf(varCount, vm.vars.size)) vm.vars[i] = vars[i].toShort()
        for (i in 0 until minOf(cvarCount, vm.cvars.size)) vm.cvars[i] = cvars[i].toShort()
        inventory.clear(); for (i in inv) inventory.add(i)
        vm.inHand = inHand; cameraTarget = target; allowInventory = allow; lightOff = dark; vm.currentMusic = music
        pendingFound = -1; pendingRead = null; exited = false; gameOver = false; effects.clear()
        for ((i, s) in saved.withIndex()) {
            val w = objects[i].w
            val f = s.f
            w.body = f[0]; w.flags = f[1]; w.typeZv = f[2]; w.foundBody = f[3]; w.foundName = f[4]; w.foundFlag = f[5]; w.foundLife = f[6]
            w.x = f[7]; w.y = f[8]; w.z = f[9]; w.alpha = f[10]; w.beta = f[11]; w.gamma = f[12]; w.stage = f[13]; w.room = f[14]
            w.lifeMode = f[15]; w.life = f[16]; w.floorLife = f[17]; w.anim = f[18]; w.frame = f[19]; w.animType = f[20]; w.animInfo = f[21]
            w.trackMode = f[22]; w.trackNumber = f[23]; w.weight = f[24]; w.mark = f[25]; w.hitForce = f[26]; w.falling = f[27]
        }
        for ((i, s) in saved.withIndex()) {
            if (!s.active) continue
            val g = objects[i]
            activate(g)
            val w = g.w
            w.chronoStart = time - s.f[28]; w.roomChronoStart = time - s.f[29]
            val a = g.actor
            val x = s.actor
            val fl = s.flags
            if (a != null && x != null && fl != null) {
                a.teleport(w.x, w.y, w.z, x[0])
                a.restoreMove(x[1], x[2], x[3], x[4])
                a.wallCollision = fl[0]; a.triggersOn = fl[1]
                a.memoX = x[5]; a.memoY = x[6]; a.memoZ = x[7]
            }
        }
        return true
    }

    companion object {
        const val PLAYER_SCRIPT = 549
        const val AF_SPECIAL = 0x20
        const val AF_TRIGGER = 0x40
        const val AF_FOUNDABLE = 0x80
        const val AF_MOVABLE = 0x10
        const val AF_FALLABLE = 0x100
        const val EFFECT_TICKS = 120

        /** The evaporation lasts until its largest bubble (300 units, 5 less every frame) is gone: 60 frames of the original, 3 engine ticks each. */
        const val EVAPORATION_TICKS = 190

        /** The smoke of a cigar (mode 4) lasts 660 ticks of the 60 per second timer. */
        const val SMOKE_TICKS = 660

        /** The smoke out of the trapdoor hole (an addition to the original): puffs rise for 9 seconds, each lives 4 seconds. */
        const val HOLE_SMOKE_TICKS = 540 + 240

        /** The trapdoor object of the attic, the life script that opens it, and the creature that climbs out of the hole. */
        const val OBJ_TRAPDOOR = 8
        const val LIFE_TRAPDOOR_OPENING = 5
        const val OBJ_HOLE_CREATURE = 9

        /** Life scripts of the creature of the trapdoor: the climb (16), the end of the climb (17) and the fight (18); the monkey runs copies numbered 1000 higher. */
        const val LIFE_CREATURE_CLIMB = 16
        const val LIFE_CREATURE_CLIMBED = 17
        const val LIFE_CREATURE_FIGHT = 18
        const val LIFE_TRAPDOOR_CLOSING = 6
        const val MONKEY_SCRIPTS = 1000
        const val ANIM_CLIMB = 19

        /** The object table with one more entry (a copy of the creature of the trapdoor) at the end, for the monkey. */
        fun withMonkeyObject(d: ByteArray): ByteArray {
            val count = d.u16(0)
            val out = d.copyOf(d.size + WorldObject.SIZE)
            System.arraycopy(d, 2 + OBJ_HOLE_CREATURE * WorldObject.SIZE, out, 2 + count * WorldObject.SIZE, WorldObject.SIZE)
            out[0] = ((count + 1) and 0xFF).toByte(); out[1] = ((count + 1) shr 8).toByte()
            return out
        }
        /** Positions in the CVars (DEFINES.ITD): the sound of an impact, the weight limit, the object that sends a thrown item back. */
        const val CVAR_SAMPLE_CHOC = 9
        const val CVAR_REVERSE_OBJECT = 11
        const val CVAR_BODY_FLAMME = 1
        const val ACTION_CLICK = 0x2000
        const val ACTION_TAKE = 0x800
        const val MAX_WEIGHT_CVAR = 2
        const val REOPEN_TICKS = 300
        private val POS_REL = intArrayOf(4, 1, 8, 2, 4, 1, 8, 0)
    }
}
