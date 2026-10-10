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
}

/** An object of the world with its actor (animation, position, collision box) while it is part of the scene. */
class GameObject(val w: WorldObject) {
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
        if (pendingFound >= 0 || gameOver) return
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
            if (pendingFound >= 0 || gameOver) break
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
            if (g.w.flags and AF_FOUNDABLE != 0) continue
            boxes.add(Actor.Collider(a.zv, g.w.flags and AF_MOVABLE != 0))
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
                10 -> g.w.hardDec = zone.parameter
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

    override fun posRel(actor: Int, other: Int): Int {
        val a = objects.getOrNull(actor)?.actor ?: return 0
        val b = objects.getOrNull(other)?.actor ?: return 0
        val beta = a.beta and 0x3FF
        var counter = 3
        if (beta in 0x80 until 0x180) counter = 2
        if (beta in 0x180 until 0x280) counter = 1
        if (beta in 0x280 until 0x380) counter = 0
        val cx = (b.zv[0] + b.zv[1]) / 2
        val cz = (b.zv[4] + b.zv[5]) / 2
        val z = a.zv
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
        val a = objects.getOrNull(actor)?.actor ?: return 32000
        val b = objects.getOrNull(other)?.actor ?: return 32000
        return Math.abs(a.worldX - b.worldX) + Math.abs(a.worldY - b.worldY) + Math.abs(a.worldZ - b.worldZ)
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
        if (w.trackNumber > 0 && time - w.trackNumber < REOPEN_TICKS) return
        w.trackNumber = 0
        pendingFound = item
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
