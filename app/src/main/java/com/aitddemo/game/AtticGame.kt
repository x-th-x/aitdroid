package com.aitddemo.game

import com.aitddemo.life.LifeHost
import com.aitddemo.life.LifeVm
import com.aitddemo.life.WorldObject

/** What the game needs from the screen: texts, sounds, music. */
interface GameUi {
    fun message(text: String)
    fun playSample(number: Int)
    fun music(number: Int, loop: Boolean)
    fun gameOver()

    /** The player was sent to another room or floor by a script; that part of the game is not available yet. */
    fun leftRoom(stage: Int, room: Int)
}

/** An object of the world with its actor (animation, position, collision box) while it is part of the scene. */
class GameObject(val w: WorldObject) {
    /** Box of an object whose box is a collider of the room (OBJETS type 4, "hard ZV"); such objects are invisible helpers of the furniture scripts. */
    var fixedZv: IntArray? = null
    var actor: Actor? = null
    var asset: BodyAsset? = null

    /** Posed vertices (x, y, z each) in the object's own space for drawing, or null when it has no body. */
    fun posedVertices(): FloatArray? {
        val a = asset ?: return null
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
class AtticGame(val data: GameData, private val ui: GameUi, private val stage: Int = 0, private val room: Int = 0) : LifeHost {
    override var time = 0
    val vm = LifeVm({ data.script(it) }, data.objectData, data.vars, this)
    val objects: Array<GameObject> = Array(vm.objects.size) { GameObject(vm.objects[it]) }

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
            val h = data.hardcols.firstOrNull { it.type == 9 && it.parameter == w.foundName }
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
            if (data.anim(anim) != null) anim else -1, time, data.hardcols, w.typeZv, animType, animInfo,
        )
        a.followTarget = { n -> objects.getOrNull(n)?.takeIf { it.w.active }?.actor?.let { intArrayOf(it.renderX, it.renderZ) } }
        a.trackSource = { n -> null }
        if (w.typeZv == 3) Actor.boxFor(asset.zv, 3, a.worldX, a.worldY, a.worldZ, a.beta).copyInto(a.zv)
        old?.let { a.restoreMove(it.trackMode, it.trackNumber, it.positionInTrack, it.mark) }
        if (old == null) a.restoreMove(w.trackMode, w.trackNumber, 0, -1)
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
            if (w.flags and AF_TRIGGER != 0) checkZones(g)
        }

        // the life scripts of all active objects
        for (g in objects) {
            val w = g.w
            if (!w.active) continue
            if (w.life != -1 && w.lifeMode != -1) vm.runObject(w.index)
            if (pendingFound >= 0 || pendingRead != null || exited || gameOver) break
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
        if (data.hardcols.any { hits(intArrayOf(it.x1, it.x2, it.y1, it.y2, it.z1, it.z2)) }) return false
        for (g in objects) {
            if (g === target || !g.w.active) continue
            val other = g.actor ?: continue
            if (hits(other.zv)) return false
        }
        a.pushBy(dx, dz)
        return true
    }

    private fun colliderOwner(i: Int): Int = colliderIndexes.getOrElse(i) { -1 }

    /** GereDec: the scene zone the object stands in sets its HARD_DEC (type 9) or changes the room (type 0, later). */
    private fun checkZones(g: GameObject) {
        val a = g.actor ?: return
        val x = a.renderX
        val y = a.renderY
        val z = a.renderZ
        for (zone in data.zones) {
            if (!zone.contains(x, y, z)) continue
            when (zone.type) {
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
        fun hits(box: IntArray) = data.hardcols.any { h -> h.x1 < box[1] && box[0] < h.x2 && h.y1 < box[3] && box[2] < h.y2 && h.z1 < box[5] && box[4] < h.z2 }
        if (hits(zv)) return 0
        zv[2] += 100; zv[3] += 100
        return if (hits(zv)) 1 else 0
    }

    // ------------------------------------------------------------------------------------------------------------------ LifeHost: objects

    override fun body(obj: Int) {
        val g = objects[obj]
        if (!g.w.active) return
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
    }

    override fun animMove(obj: Int, anims: IntArray) {
        val a = objects.getOrNull(obj)?.actor ?: return
        a.chooseMoveAnim(MoveAnims(anims[0], anims[1], anims[2], anims[3], anims[4], anims[5], anims[6]))
    }

    /** HIT: a punch or weapon swing starts its animation (the damage to what it hits comes with the creatures). */
    override fun hit(obj: Int, anim: Int, frame: Int, group: Int, size: Int, force: Int, nextAnim: Int) {
        objects.getOrNull(obj)?.actor?.playAnim(anim, 0, nextAnim)
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
            exited = true
            ui.leftRoom(w.stage, w.room)
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

    override fun lifeChanged(obj: Int, life: Int) {}

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

    override fun fadeMusic(n: Int) {
        ui.music(n, true)
    }

    override fun gameOver() {
        gameOver = true
        ui.gameOver()
    }

    override fun special(obj: Int, kind: Int) {}

    companion object {
        const val PLAYER_SCRIPT = 549
        const val AF_TRIGGER = 0x40
        const val AF_FOUNDABLE = 0x80
        const val AF_MOVABLE = 0x10
        const val ACTION_CLICK = 0x2000
        const val ACTION_TAKE = 0x800
        const val MAX_WEIGHT_CVAR = 0
        const val REOPEN_TICKS = 300
        private val POS_REL = intArrayOf(4, 1, 8, 2, 4, 1, 8, 0)
    }
}
