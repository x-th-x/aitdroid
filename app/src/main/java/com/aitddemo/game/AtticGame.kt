package com.aitddemo.game

import com.aitddemo.RoomView
import com.aitddemo.initRot
import com.aitddemo.rotatePoint
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
    fun nextMusic(number: Int) { music(number, false) }

    /** FADE_MUSIC: what plays is cut, and the music [number] starts 3 seconds later (at once when nothing plays). */
    fun fadeMusic(number: Int) { music(number, false) }

    /** The number of the music that plays now (-1 none, -2 waiting after a fade), or null when the host does not play any (the scripts read it). */
    fun currentMusic(): Int? = null
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

    /** The wall box (x1, x2, y1, y2, z1, z2) that the object touched when its script last asked for it (GET_HARD_CLIP), null = none. */
    var clipBox: IntArray? = null
    var actor: Actor? = null
    var asset: BodyAsset? = null

    /** Posed vertices (x, y, z each) in the object's own space for drawing, or null when it has no body. */
    fun posedVertices(): FloatArray? {
        val a = asset ?: return null
        val parts = a.parts ?: return a.vertices
        val act = actor ?: return a.vertices
        val posed = BodyPose.pose(parts.vertices, parts.groups, parts.groupOrder, act.state)
        if (scale == 1f) return posed
        return FloatArray(posed.size) { posed[it] * scale } // Together mode: the heroes seated in the car are drawn larger
    }

    /** Together mode: the factor the body is drawn larger by (about its feet). */
    var scale = 1f
}

/**
 * The game of one room: the life scripts of the original run on the world objects every tick, in the order of the original's main loop. The [LifeVm]
 * interprets the scripts; this class is its host: it moves the actors, plays their animations, collides them with the room and with each other, and
 * turns script operations (animations, items, messages, sounds) into effects.
 */
class AtticGame(val data: GameData, private val ui: GameUi, private var stage: Int = 0, private var room: Int = 0, private val intro: Boolean = false, private val together: Boolean = false) : LifeHost {
    override var time = 0
    val vm = LifeVm({ data.script(it) }, data.objectData, data.vars, this).also { for (i in data.defines.indices) if (i < it.cvars.size) it.cvars[i] = data.defines[i] }
    val objects: Array<GameObject> = Array(vm.objects.size) { GameObject(vm.objects[it]) }

    /** A new game starts with the heroes as they were chosen (an earlier game may have swapped them). */
    private val heroesReset = run { if (data.swapped) data.swapHeroes() }

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

    /** WATER: the height below which the vertices of bodies are not drawn (-600 with water, 10000 without; the value the original puts into its point transformation). */
    var waterLimit = 10000
        private set

    /** SHAKING 1: the timer interrupt of the original moves the start of the display by two scanlines on every second tick (60 per second), the picture jitters. */
    var shaking = false
        private set

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

    /**
     * The object the scene follows: the player in the game; in the intro (a film that the scripts play, startGame(7, 1) of the original) the object the camera is pointed at,
     * so the loaded room is the room of the car, then of the driver walking through the house.
     */
    private val leader: Int get() = if (intro) cameraTarget else playerIndex

    private val moveAnims = HashMap<Int, MoveAnims>()
    private var lastCameraTarget = -1

    private val roomWorlds = HashMap<Long, RoomView?>()
    private val roomGeometry = HashMap<Long, Pair<List<Zone>, List<Zone>>>()

    init {
        vm.trace = null
        val seen = viewList()
        for (o in vm.objects) if (wanted(o, seen, stage, room)) activate(objects[o.index])
    }

    // ------------------------------------------------------------------------------------------------------------------ Together mode

    /** Together mode: the other hero, who follows the player (null when the mode is off). It is not an object of the original game, the game moves it. */
    var companion: Partner? = null
        private set

    /** The animations the player's script uses for ANIM_MOVE (stand, walk, run, stop, backward, turns), which the companion walks with as well. */
    private var playerAnims: MoveAnims? = null

    /** The companion as an object to draw (it has an actor and a body of the other hero's list), or null. */
    val companionObject: GameObject? get() = companion?.obj

    /** The health of the hero who is not selected (the selected hero's health is the variable 21 of the game); and whether he is still there. */
    var helperHp = 20
        private set
    var companionAlive = true
        private set

    /** Is the object a monster right now (its life script is one of the fights that hit)? */
    private fun isMonster(g: GameObject): Boolean = g.w.life in MONSTER_SCRIPTS

    /** When (tick) the other hero last hit a monster, by the monster's index: the hit effect appears at his hand. */
    private val companionHitTime = HashMap<Int, Int>()
    private var nextBlow = 0

    /** Until when (tick) the other hero fights the player: he was hit by him. */
    private var provokedUntil = 0
    private var blowIndex = 0

    /** The other hero can be switched to (he is there). */
    val canSwitch: Boolean get() = companion != null && companionAlive && !intro

    /** The clock of the other hero: it runs a little slower than the game's, so its steps and its breathing do not stay in step with the player's. */
    private fun partnerClock(t: Int): Int = t * 92 / 100

    /** A figure that is not an object of the original (no life script, not in the object table) but has a body and an actor: [own] = the body of the player's list, else of the other hero's. */
    private fun makeFigure(bodyNumber: Int, own: Boolean, x: Int, y: Int, z: Int, beta: Int, anim: Int): GameObject? {
        if (!together || !data.hasCompanion) return null
        val asset = (if (own) data.body(bodyNumber) else data.companionBody(bodyNumber)) ?: return null
        fun animOf(n: Int): AnimData? = if (own) data.anim(n) else data.companionAnim(n) ?: data.anim(n) // an animation the other list lacks: the player's
        val w = WorldObject(playerIndex.coerceAtLeast(0), data.objectData)
        w.body = bodyNumber; w.active = true; w.stage = stage; w.room = room
        w.life = -1; w.lifeMode = -1; w.x = x; w.y = y; w.z = z; w.beta = beta
        val g = GameObject(w)
        g.asset = asset
        val groups = asset.groupCount.coerceAtLeast(1)
        val rig = ActorRig(groups, Array(groups) { GroupState() }, asset.zv)
        g.actor = Actor(rig, { n -> animOf(n) ?: error("animation $n") }, x, y, z, beta, if (animOf(anim) != null) anim else -1, if (own) time else partnerClock(time), roomView.hardcols, w.typeZv)
        return g
    }

    /** The companion gets another body of the other hero's list (the hero of the intro has a small body outdoors and a larger one in the house), keeping place, heading and animation. */
    private fun refitCompanion(g: GameObject, bodyNumber: Int) {
        val asset = data.companionBody(bodyNumber) ?: return
        val old = g.actor ?: return
        g.asset = asset
        g.w.body = bodyNumber
        val groups = asset.groupCount.coerceAtLeast(1)
        val rig = ActorRig(groups, Array(groups) { GroupState() }, asset.zv)
        val anim = if (data.companionAnim(old.currentAnim) != null) old.currentAnim else -1
        g.actor = Actor(rig, { n -> data.companionAnim(n) ?: data.anim(n) ?: error("animation $n") }, old.renderX, old.renderY, old.renderZ, old.beta and 0x3FF, anim, partnerClock(time), roomView.hardcols, g.w.typeZv)
    }

    private fun makeCompanion(bodyNumber: Int, x: Int, y: Int, z: Int, beta: Int, anim: Int): Partner? = makeFigure(bodyNumber, false, x, y, z, beta, anim)?.let { Partner(it) }

    /** The game starts: the other hero stands next to the player. */
    private fun startCompanion() {
        if (!together) return
        val p = player ?: return
        val a = p.actor ?: return
        if (!companionAlive) return
        helperHp = vm.vars.getOrElse(21) { 20 }.toInt().coerceAtLeast(1)
        companion = makeCompanion(vm.objects[playerIndex].body, a.renderX, a.renderY, a.renderZ, a.beta and 0x3FF, p.w.anim) ?: return
        placeCompanion()
    }

    /** Is the box free of walls and of the objects of the room? */
    private fun boxFree(box: IntArray): Boolean {
        fun hits(b: IntArray) = b[0] < box[1] && box[0] < b[1] && b[2] < box[3] && box[2] < b[3] && b[4] < box[5] && box[4] < b[5]
        if (roomView.hardcols.any { hits(intArrayOf(it.x1, it.x2, it.y1, it.y2, it.z1, it.z2)) }) return false
        for (g in objects) {
            if (!g.w.active || g.w.flags and AF_FOUNDABLE != 0) continue
            val other = g.actor ?: continue
            if (hits(other.zv)) return false
        }
        return true
    }

    /**
     * Puts the companion next to the player at a free place: behind him if that is free, else to one side, farther back, in front. Used when the scene changes
     * (the other hero comes through the door with the player) and when the companion has lost him.
     */
    private fun placeCompanion() {
        val c = companion ?: return
        val a = c.actor ?: return
        val lead = player?.actor ?: return
        val back = (lead.beta and 0x3FF) + 512
        var best: IntArray? = null
        search@ for (radius in intArrayOf(1100, 1500, 2000, 700)) {
            for (turn in intArrayOf(0, 160, -160, 320, -320, 480, -480, 640, -640)) {
                val ang = (back + turn) * 2.0 * Math.PI / 1024.0
                val x = lead.renderX + (Math.sin(ang) * radius).toInt()
                val z = lead.renderZ - (Math.cos(ang) * radius).toInt()
                val box = a.zv.copyOf()
                val sx = x - a.renderX; val sy = lead.renderY - a.renderY; val sz = z - a.renderZ
                box[0] += sx; box[1] += sx; box[2] += sy; box[3] += sy; box[4] += sz; box[5] += sz
                if (boxFree(box)) { best = intArrayOf(x, z); break@search }
            }
        }
        val at = best ?: intArrayOf(lead.renderX, lead.renderZ)
        a.teleport(at[0], lead.renderY, at[1], lead.beta and 0x3FF)
        a.hardcols = roomView.hardcols
        c.obj.w.stage = stage; c.obj.w.room = room
        c.reset(time)
    }

    /** One tick of the companion in the game: it follows the player (see [Partner.follow]). */
    private fun tickCompanion() {
        val c = companion ?: return
        val a = c.actor ?: return
        val lead = player?.actor ?: return
        a.hardcols = roomView.hardcols
        a.colliders = objects.filter { it.w.active && it.actor != null }.map { Actor.Collider(it.actor!!.zv, false, it.w.flags and AF_FOUNDABLE == 0) }
        c.obj.w.stage = stage; c.obj.w.room = room
        val dy = lead.renderY - a.renderY // on stairs it keeps the height of the player
        if (dy != 0) a.moveYBy(dy)
        val anims = playerAnims ?: MoveAnims(player?.w?.anim ?: -1, -1, -1, -1, -1, -1, -1)
        val threat = if (time < provokedUntil) player else nearestThreat(a, lead)
        if (threat != null) {
            sideTarget = null
            fight(c, a, threat, anims)
        } else {
            val clock = partnerClock(time)
            val distToPlayer = Math.hypot((lead.renderX - a.renderX).toDouble(), (lead.renderZ - a.renderZ).toDouble()).toFloat()
            // the player comes up to him: he steps aside a few steps (to the left, to the right or back, where there is room) and then follows again
            if (sideTarget == null && distToPlayer < SIDESTEP_DISTANCE) { sideTarget = pickSideStep(a, lead); sideUntil = time + SIDESTEP_TICKS }
            val st = sideTarget
            if (st != null) {
                c.goTo(clock, st[0], st[1], anims, { placeCompanion() }, 300f, 120f, 99999f, false)
                val left = Math.hypot((st[0] - a.renderX).toDouble(), (st[1] - a.renderZ).toDouble())
                if (left < 160 || time > sideUntil) sideTarget = null
            } else {
                c.follow(clock, lead, anims, { placeCompanion() })
            }
        }
        a.update(partnerClock(time))
        c.obj.w.anim = a.currentAnim
        c.obj.w.frame = a.frameIndex
        if (c.obj.actionType != 0) processAction(c.obj)
    }

    /** The place he steps aside to, and until when. */
    private var sideTarget: IntArray? = null
    private var sideUntil = 0

    /** A free place a few steps from the other hero, away from the player: to the side first (the side that is free), else straight back. */
    private fun pickSideStep(a: Actor, lead: Actor): IntArray? {
        var vx = (a.renderX - lead.renderX).toDouble(); var vz = (a.renderZ - lead.renderZ).toDouble()
        val len = Math.hypot(vx, vz)
        if (len < 1.0) { // exactly on the player: any way is as good as another
            val ang = (lead.beta and 0x3FF) * 2.0 * Math.PI / 1024.0
            vx = Math.sin(ang); vz = -Math.cos(ang)
        } else { vx /= len; vz /= len }
        val candidates = arrayOf(doubleArrayOf(-vz, vx), doubleArrayOf(vz, -vx), doubleArrayOf(vx, vz), doubleArrayOf(-vz * 0.7 + vx * 0.7, vx * 0.7 + vz * 0.7), doubleArrayOf(vz * 0.7 + vx * 0.7, -vx * 0.7 + vz * 0.7))
        for (dist in intArrayOf(SIDESTEP_LENGTH, SIDESTEP_LENGTH / 2)) {
            for (cand in candidates) {
                val x = a.renderX + (cand[0] * dist).toInt(); val z = a.renderZ + (cand[1] * dist).toInt()
                val box = a.zv.copyOf()
                val sx = x - a.renderX; val sz = z - a.renderZ
                box[0] += sx; box[1] += sx; box[4] += sz; box[5] += sz
                // the box that is passed on the way (half way) must be free as well
                val half = a.zv.copyOf(); half[0] += sx / 2; half[1] += sx / 2; half[4] += sz / 2; half[5] += sz / 2
                if (boxFree(box) && boxFree(half)) return intArrayOf(x, z)
            }
        }
        return null
    }

    // ------------------------------------------------------------------------------------------------------------------ fighting together

    /** The nearest monster in the room that the other hero can go for, or null. */
    private fun nearestThreat(from: Actor, player: Actor): GameObject? {
        var best: GameObject? = null
        var bestDist = Int.MAX_VALUE
        for (g in objects) {
            if (!isMonster(g) || !g.w.active || g.w.room != room) continue
            val m = g.actor ?: continue
            // only a monster that is near the player (in the picture): the other hero does not run off after one that is far away
            val toPlayer = Math.hypot((m.renderX - player.renderX).toDouble(), (m.renderZ - player.renderZ).toDouble()).toInt()
            if (toPlayer > THREAT_RANGE) continue
            val d = Math.hypot((m.renderX - from.renderX).toDouble(), (m.renderZ - from.renderZ).toDouble()).toInt()
            if (d < bestDist) { best = g; bestDist = d }
        }
        return best
    }

    /** The other hero goes for the monster: he runs to it, stops at the reach of his fist, turns to it, and punches with the fist animations of the game. */
    private fun fight(c: Partner, a: Actor, target: GameObject, anims: MoveAnims) {
        val m = target.actor ?: return
        val clock = partnerClock(time)
        val punching = a.currentAnim in FIST_ANIMS
        if (!punching) c.follow(clock, m, anims, { placeCompanion() }, FIGHT_REACH + 250f, FIGHT_REACH, 2600f, true)
        val dx = (m.renderX - a.renderX).toFloat(); val dz = (m.renderZ - a.renderZ).toFloat()
        val dist = Math.hypot(dx.toDouble(), dz.toDouble()).toFloat()
        var diff = (Partner.headingOf(dx, dz) - (a.beta and 0x3FF)) % 1024f
        if (diff > 512f) diff -= 1024f
        if (diff < -512f) diff += 1024f
        if (!punching && time >= nextBlow && dist <= FIGHT_REACH + 300f && Math.abs(diff) < 80f) {
            val anim = FIST_ANIMS[blowIndex % FIST_ANIMS.size]
            if (startBlow(c.obj, anim)) { blowIndex++; nextBlow = time + BLOW_PAUSE }
        }
    }

    /** A blow of the other hero: the same data as the player's fists (see [FIST_ANIMS]). */
    private fun startBlow(g: GameObject, anim: Int): Boolean {
        val a = g.actor ?: return false
        if (data.companionAnim(anim) == null) return false
        if (!a.playAnim(anim, 0, FIST_NEXT)) return false
        g.actionType = 1
        g.actionAnim = anim; g.actionFrame = FIST_FRAME; g.actionGroup = FIST_GROUPS[FIST_ANIMS.indexOf(anim).coerceAtLeast(0)]; g.actionRange = FIST_SIZE; g.actionForce = FIST_FORCE
        return true
    }

    /** A monster hit the other hero. When his health is gone he dissolves in bubbles as the monsters do, and is gone (the game goes on). */
    private fun hurtCompanion(force: Int, by: GameObject? = null) {
        helperHp -= force.coerceAtLeast(1)
        // hit by the player he fights back, as long as the blows go on (and for a while after)
        if (by != null && by === player) provokedUntil = time + PROVOKED_TICKS
        // the hit effect where the blow lands
        companion?.actor?.let { a -> by?.actor?.let { b -> effects.add(Effect(1, a.renderX, a.renderY - (a.zv[3] - a.zv[2]) / 2, a.renderZ, time, null, 0, (-b.beta) and 0x3FF, 0)) } }
        if (helperHp <= 0) killCompanion()
    }

    private fun killCompanion() {
        val c = companion ?: return
        c.actor?.let { a -> effects.add(Effect(0, a.renderX, a.renderY, a.renderZ, time, a.zv.copyOf(), 0, a.beta and 0x3FF, 0)) }
        ui.playSample(DEATH_SAMPLE)
        companion = null
        companionAlive = false
    }

    /**
     * The player takes over the other hero: the two heroes swap their bodies and animation lists, their places and their health, so each stays where he stands and the control
     * (and the camera) goes to the other one. The hero that is not controlled any more follows and helps in fights.
     */
    fun switchHero(): Boolean {
        val c = companion ?: return false
        val p = player ?: return false
        val pa = p.actor ?: return false
        val ca = c.actor ?: return false
        if (!companionAlive || intro) return false
        val px = pa.renderX; val py = pa.renderY; val pz = pa.renderZ; val pb = pa.beta and 0x3FF
        val cx = ca.renderX; val cy = ca.renderY; val cz = ca.renderZ; val cb = ca.beta and 0x3FF
        val health = vm.vars[21].toInt()
        vm.vars[21] = helperHp.toShort()
        helperHp = health.coerceAtLeast(1)
        data.swapHeroes()
        // the player's object gets the other hero's body (the lists are swapped) and the other hero's place
        createActor(p, -1, 0, -1)
        p.actor?.let { na -> na.teleport(cx, cy, cz, cb); na.hardcols = roomView.hardcols; na.resetMove() }
        p.w.x = cx; p.w.y = cy; p.w.z = cz; p.w.beta = cb
        // the helper gets the body of the hero that was controlled, and his place
        refitCompanion(c.obj, c.obj.w.body)
        c.actor?.let { na -> na.teleport(px, py, pz, pb); na.hardcols = roomView.hardcols }
        c.reset(time)
        return true
    }

    init {
        if (intro) startIntroFigures() else startCompanion() // after the properties above exist
    }

    // ---- the intro in Together mode: both heroes sit in the car, then walk to the house side by side

    /** The hero who plays, seated at the wheel of the car in the intro (the other one, the companion, sits next to him). */
    var driverSeatObject: GameObject? = null
        private set

    /** The two heroes sit in the car right now (the screen draws them together with the car, so the car's body hides what it should hide of them). */
    var figuresSeated = false
        private set

    /** How long the place of the second hero may be bad (a wall, out of the picture) before he takes another one, in ticks. */
    private var walkBadTicks = 0
    private var walkRoomKey = -1
    private var partnerBeta = 0

    /** Which of the places beside the walker the companion walks at (it changes when a wall is in the way). */
    private var walkSide = 0

    private fun startIntroFigures() {
        if (!together || !data.hasCompanion) return
        val bodyNumber = vm.objects[INTRO_WALKER].body // the body the hero has in the film of the intro
        val c = makeCompanion(bodyNumber, 0, 0, 0, 0, -1) ?: return
        c.obj.w.active = false
        companion = c
        driverSeatObject = makeFigure(bodyNumber, true, 0, 0, 0, 0, -1)?.also { it.w.active = false }
    }

    /** Is the box free of the walls of the room? (the objects do not count: the companion walks next to the hero) */
    private fun wallFree(box: IntArray): Boolean = roomView.hardcols.none { it.x1 < box[1] && box[0] < it.x2 && it.y1 < box[3] && box[2] < it.y2 && it.z1 < box[5] && box[4] < it.z2 }

    /** Puts a figure to a place and heading. */
    private fun putFigure(g: GameObject, x: Int, y: Int, z: Int, beta: Int) {
        val a = g.actor ?: return
        a.teleport(x, y, z, beta)
        g.w.stage = stage; g.w.room = room
    }

    /**
     * One tick of the two figures of the intro. While the car drives they sit in it (the occupants of the car body are left out then); when the script lets the hero
     * walk to the house the companion walks beside and a little behind him, with the same animation, on the side where no wall is.
     */
    private fun tickIntroFigures() {
        // the car slows down towards the house: from the end of the road on, it drives at less than half its speed
        objects.getOrNull(INTRO_CAR)?.takeIf { it.w.active }?.actor?.let { car ->
            car.moveFactor = if (car.roomNumber == 0) 45 + (car.renderX - 450).coerceIn(0, 1050) * 55 / 1050 else 100
        }
        val c = companion ?: return
        val ca = c.actor ?: return
        val driver = driverSeatObject
        val walker = objects.getOrNull(INTRO_WALKER)?.takeIf { it.w.active }
        val wa = walker?.actor
        val car = objects.getOrNull(INTRO_CAR)?.takeIf { it.w.active }
        val carActor = car?.actor
        figuresSeated = wa == null && carActor != null
        if (wa != null) {
            driver?.w?.active = false
            c.obj.w.active = true
            c.obj.scale = 1f
            if (c.obj.w.body != walker.w.body && walker.w.body >= 0) refitCompanion(c.obj, walker.w.body)
            val ca = c.actor ?: return
            ca.wallCollision = false
            ca.colliders = emptyList()
            // beside and behind the hero (x across, z back in his own frame), the other side or straight behind when a wall is there
            val rot = initRot(0, wa.beta and 0x3FF, 0)
            // indoors the film's scale is ten times larger (the body of the house is 1775 units high, the one outdoors 177): places and boxes are given in units of the outdoor scale
            val unit = if (roomView.stage == INTRO_OUTDOORS_STAGE) 1 else 10
            var chosen = -1
            val places = if (roomView.stage == INTRO_OUTDOORS_STAGE) WALK_PLACES else WALK_PLACES_INDOORS
            // the place must be free of walls and in the picture (the camera looks from the front: a place behind the hero can lie below the lower edge, and the hero would be cut off or gone)
            val camera = roomView.cameras.getOrNull(currentCamera)
            val bodyTop = (c.obj.asset?.zv?.get(2) ?: -177).toFloat()
            fun inPicture(px: Int, pz: Int): Boolean {
                if (camera == null) return true
                val feet = Plate.project(camera, px.toFloat(), wa.renderY.toFloat(), pz.toFloat()) ?: return false
                val head = Plate.project(camera, px.toFloat(), wa.renderY + bodyTop, pz.toFloat()) ?: return false
                return feet[0] in 20f..300f && feet[1] <= 186f && head[1] >= 8f
            }
            fun placeAt(index: Int): IntArray {
                val place = places[index]
                val (ox, _, oz) = rotatePoint(place[0].toFloat() * unit, 0f, place[1].toFloat() * unit, rot)
                return intArrayOf(wa.renderX + ox.toInt(), wa.renderZ + oz.toInt())
            }
            fun usable(index: Int): Boolean {
                val at = placeAt(index)
                // only the feet are tested (a slim box above the floor): the boxes of door lintels and the like reach down to the height of the body and are not in the way of a step
                val box = intArrayOf(at[0] - 22 * unit, at[0] + 22 * unit, wa.renderY - 40 * unit, wa.renderY - 5 * unit, at[1] - 22 * unit, at[1] + 22 * unit)
                return wallFree(box) && inPicture(at[0], at[1])
            }
            // the second hero keeps his place as long as it is good (no flipping from one side to the other); when it has been bad for some time, the first good place is taken
            val roomKey = roomView.stage * 100 + roomView.room
            val roomChanged = roomKey != walkRoomKey
            walkRoomKey = roomKey
            if (usable(walkSide)) {
                walkBadTicks = 0
            } else {
                walkBadTicks++
                if (walkBadTicks >= WALK_PLACE_PATIENCE || roomChanged) {
                    for (k in 1 until places.size) {
                        val index = (walkSide + k) % places.size
                        if (usable(index)) { walkSide = index; walkBadTicks = 0; break }
                    }
                }
            }
            val target = placeAt(walkSide)
            // he walks to his place at a limited speed (a jump would be seen), and is put there when the scene changes or he is far away
            val dx = target[0] - ca.renderX; val dz = target[1] - ca.renderZ
            val dist = Math.sqrt((dx.toDouble() * dx + dz.toDouble() * dz)).toInt()
            val maxStep = 3 * unit
            val snap = roomChanged || dist > 2500 * unit
            val nx = if (snap || dist <= maxStep) target[0] else ca.renderX + (dx.toLong() * maxStep / dist).toInt()
            val nz = if (snap || dist <= maxStep) target[1] else ca.renderZ + (dz.toLong() * maxStep / dist).toInt()
            // he turns to the heading of the first hero at a limited rate as well
            val want = wa.beta and 0x3FF
            if (snap) partnerBeta = want else {
                val diff = ((want - partnerBeta + 512) and 1023) - 512
                partnerBeta = (partnerBeta + diff.coerceIn(-12, 12)) and 1023
            }
            // the same animation as the leader, but half a cycle later and at a slightly different tempo (see [partnerClock]): the steps are not taken together
            val anim = wa.currentAnim
            var started = false
            if (anim != ca.currentAnim && data.companionAnim(anim) != null) started = ca.playAnim(anim, 1, -1)
            ca.update(partnerClock(time))
            if (started) data.companionAnim(anim)?.let { d -> ca.seekFrame(d.frames.size / 2, partnerClock(time)) }
            putFigure(c.obj, nx, wa.renderY, nz, partnerBeta)
        } else if (carActor != null) {
            // seated in the car, turned as the car is, at the places of the two heads of the car body
            val rot = initRot(0, carActor.beta and 0x3FF, 0)
            fun seat(g: GameObject?, place: FloatArray, driverSeat: Boolean) {
                val a = g?.actor ?: return
                val (ox, oy, oz) = rotatePoint(place[0], place[1], place[2], rot)
                g.w.active = true
                // the sole of the seated body rests on the floor of the car: the hip joint is (hip to sole) above it, and the origin of the body (its feet) lies a hip height below the hip joint
                g.scale = SEAT_SCALE
                val hipHeight = -(g.asset?.zv?.get(2) ?: -177) * 52 / 100f * SEAT_SCALE
                val originY = CAR_FLOOR - SEATED_HIP_TO_SOLE * SEAT_SCALE + hipHeight
                putFigure(g, carActor.renderX + ox.toInt(), carActor.renderY + oy.toInt() + originY.toInt(), carActor.renderZ + oz.toInt(), carActor.beta and 0x3FF)
                SeatedPose.apply(a, g.asset, driverSeat, time, if (driverSeat) 0 else 1)
            }
            seat(driver, CAR_DRIVER, true)
            seat(c.obj, CAR_PASSENGER, false)
        } else {
            driver?.w?.active = false
            c.obj.w.active = false
        }
    }

    /** The rooms of the floor that count as seen from the current room: itself and the rooms its doors lead to (InitViewedRoomList of the original). */
    private fun viewList(): Set<Int> = HashSet<Int>().also {
        it.add(room)
        it.addAll(roomView.linkedRooms)
        roomView.cameras.getOrNull(currentCamera)?.let { c -> it.addAll(c.viewedRooms) } // and the rooms the camera that shows the scene sees (InitViewedRoomList)
    }

    /** The camera that shows the scene now (the screen tells it), -1 = not known yet. */
    private var currentCamera = -1

    /**
     * The camera of the scene changed: the list of the rooms that count as seen is made again (it holds the rooms the camera sees) and the objects that now belong to it
     * join the scene, those that no longer do leave (GenereActiveList of the original, which runs when a camera is loaded).
     */
    fun cameraChanged(index: Int) {
        if (index == currentCamera) return
        currentCamera = index
        val seen = viewList()
        for (o in vm.objects) {
            val g = objects[o.index]
            if (o.index == playerIndex || o.index == leader) continue
            val want = wanted(o, seen, stage, room)
            if (want && !o.active) activate(g) else if (!want && o.active) deactivate(g)
        }
    }

    /**
     * Is the object part of the scene (GenereActiveList of the original)? Objects of the floor with a life script live by their life mode: 0 always, 1 while the player is in
     * their room, 2 while their room is seen from the player's room; objects without a life script live while their room is seen. Objects of other rooms keep their own
     * room number; their places are moved into the frame of the current room (see [frameDelta]) and the camera draws them only if it sees their room.
     */
    private fun wanted(w: WorldObject, seen: Set<Int>, st: Int, rm: Int): Boolean {
        if (w.index == leader || w.index == playerIndex) return w.stage == st && w.room == rm
        if (w.stage != st) return false
        if (w.life != -1) return when (w.lifeMode) { 0 -> true; 1 -> w.room == rm; 2 -> w.room in seen; else -> false }
        return w.room in seen
    }

    /** How the places of room [ownRoom] of the current floor are moved into the frame of the current room (what [Actor.moveToRoomOrigin] takes), or null for the same room. */
    private fun frameDelta(ownRoom: Int): IntArray? {
        if (ownRoom == room || ownRoom < 0) return null
        val r = roomWorlds.getOrPut(stage * 1000L + ownRoom) { try { data.rooms.room(stage, ownRoom) } catch (_: Exception) { null } } ?: return null
        return intArrayOf((roomView.worldX - r.worldX) * 10, (roomView.worldY - r.worldY) * 10, (roomView.worldZ - r.worldZ) * 10)
    }

    /**
     * The walls (hard colliders) and zones of room [r] of the current floor in the frame of the current room: an object of another room keeps colliding with the walls of its
     * own room and walks through the doors of its own room (GereDec of the original uses the room of the object), while all places are in the frame of the current room.
     */
    private fun geometryOf(r: Int): Pair<List<Zone>, List<Zone>> {
        if (r == room || r < 0) return roomView.hardcols to roomView.zones
        return roomGeometry.getOrPut((stage * 1000L + r) * 1000L + room) {
            val own = try { data.rooms.room(stage, r) } catch (_: Exception) { null } ?: return@getOrPut emptyList<Zone>() to emptyList()
            val dx = (roomView.worldX - own.worldX) * 10
            val dy = (roomView.worldY - own.worldY) * 10
            val dz = (roomView.worldZ - own.worldZ) * 10
            fun move(z: Zone) = Zone(z.x1 - dx, z.x2 - dx, z.y1 + dy, z.y2 + dy, z.z1 + dz, z.z2 + dz, z.parameter, z.type)
            own.hardcols.map { move(it) } to own.zones.map { move(it) }
        }
    }

    /** How the places of room [ownRoom] of the current floor are moved into the frame of the current room (x -= a, y += b, z += c), or null for the same room. */
    fun roomShift(ownRoom: Int): IntArray? = frameDelta(ownRoom)

    /** The place of the object (stored in the frame of its own room) in the frame of the current room. */
    private fun currentFrame(w: WorldObject): IntArray {
        val d = frameDelta(w.room) ?: return intArrayOf(w.x, w.y, w.z)
        return intArrayOf(w.x - d[0], w.y + d[1], w.z + d[2])
    }

    // ------------------------------------------------------------------------------------------------------------------ scene membership

    private fun activate(g: GameObject) {
        val w = g.w
        w.active = true
        w.chronoStart = time
        w.roomChronoStart = time
        w.col = -1; w.colBy = -1; w.hit = -1; w.hitBy = -1; w.hardCol = -1; w.hardDec = -1
        createActor(g, w.anim, w.animType, w.animInfo)
        if (w.room != room) { // an object of another room that this room sees: in the frame of this room, without the walls of this room
            frameDelta(w.room)?.let { d -> g.actor?.moveToRoomOrigin(d[0], d[1], d[2]) }
            g.actor?.hardcols = geometryOf(w.room).first
        }
        applyHardZv(g)
    }

    /**
     * An object of ZV type 4 has the box of the collider of its own room with its parameter (foundName; type 9) as its box and stands at its centre. The box is kept in the
     * frame of the current room, so it is applied again whenever the frame changes (the helper that holds a camera in the intro lies in a room the scene enters later).
     */
    private fun applyHardZv(g: GameObject) {
        val w = g.w
        if (w.typeZv != 4) return
        val own = if (w.room == room) roomView else try { data.rooms.room(stage, w.room) } catch (_: Exception) { null } ?: return
        val h = own.hardcols.firstOrNull { it.type == 9 && it.parameter == w.foundName }
        if (h == null) { g.fixedZv = null; return }
        w.x = (h.x1 + h.x2) / 2; w.y = (h.y1 + h.y2) / 2; w.z = (h.z1 + h.z2) / 2
        val d = frameDelta(w.room)
        g.fixedZv = if (d == null) intArrayOf(h.x1, h.x2, h.y1, h.y2, h.z1, h.z2) else intArrayOf(h.x1 - d[0], h.x2 - d[0], h.y1 + d[1], h.y2 + d[1], h.z1 + d[2], h.z2 + d[2])
    }

    private fun deactivate(g: GameObject) {
        g.actor?.let {
            g.w.trackPos = it.positionInTrack
            // an animation that was asked for in this very tick and has not begun yet is kept (a script that starts one and then takes the object to another room: the walker of the intro)
            val pending = it.pendingAnim
            if (pending != null) { g.w.anim = pending[0]; g.w.animType = pending[1] and 3; g.w.animInfo = pending[2] }
            else { g.w.anim = it.currentAnim; g.w.animType = it.currentAnimType; g.w.animInfo = it.currentAnimInfo }
        }
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
        // in Together mode the two figures sit in the car: its two heads (round parts of 20 units) stand for the occupants of the original and are left out
        g.asset = if (together && intro && w.index == INTRO_CAR && data.hasCompanion) asset.withoutSpheresOfRadius(20) else asset
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
        if (old == null) a.restoreMove(w.trackMode, w.trackNumber, w.trackPos, -1)
        old?.pendingAnim?.let { a.playAnim(it[0], it[1], it[2]) } // an animation that was just requested survives the change of body
        g.actor = a
    }

    // ------------------------------------------------------------------------------------------------------------------ one tick

    /** One tick (60 per second) of the game; [keys] = [Joy] bits, [action] = the action key was pressed, [runHint] = he should run when going forward. */
    fun tick(timer: Int, keys: Int, action: Boolean, runHint: Boolean) {
        if (pendingFound >= 0 || pendingRead != null || exited || gameOver) return
        time = timer
        ui.currentMusic()?.let { vm.currentMusic = it } // the music that really plays (-1 when it has ended)
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
            frameDelta(w.room)?.let { d -> w.x = a.renderX + d[0]; w.y = a.renderY - d[1]; w.z = a.renderZ - d[2] } // stored in the frame of its own room
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

        if (intro) tickIntroFigures() else tickCompanion()

        effects.removeAll { time - it.born > (when (it.kind) { 0 -> EVAPORATION_TICKS; 3 -> 3; 4 -> SMOKE_TICKS; else -> EFFECT_TICKS }) }

        // the life scripts of all active objects
        for (g in objects) {
            val w = g.w
            if (!w.active) continue
            if (w.life != -1 && w.lifeMode != -1) vm.runObject(w.index)
            if (pendingFound >= 0 || pendingRead != null || exited || gameOver) break
        }

        // the floor loader of the original stops all samples when a script has taken the scene to another floor (after the scripts of the frame ran, so a sample that the
        // scripts started in that frame is cut at once; the sample that waits behind it, SAMPLE_THEN, survives and starts)
        if (stopSamplesAtEnd) {
            stopSamplesAtEnd = false
            ui.stopSamples()
        }

        if (intro && data.hasCompanion) steerCar()
    }

    // ---- the car of the intro in Together mode: smooth steering

    private var carHeading = -1f
    private var carSegment = 0
    private var carPath: List<IntArray>? = null

    /**
     * The path of the car turns by the rules of the original (a quarter turn in 15 ticks, then a new waypoint), which looks jerky at the bends. From the end of the road on the heading is
     * steered smoothly instead: the car aims at a point of its path a little ahead of it and its heading follows that direction at a gentle, steady rate (after the scripts of the tick,
     * so the next step is taken in that direction). The place, the speed and the stop at the house stay as the track gives them.
     */
    private fun steerCar() {
        val car = objects.getOrNull(INTRO_CAR)?.takeIf { it.w.active }?.actor
        if (car == null || car.roomNumber != 0 || car.mark != 14) { if (car == null || car.mark != 14) { carHeading = -1f; carPath = null; carSegment = 0 }; return }
        val path = carPath ?: (listOf(intArrayOf(car.renderX, car.renderZ)) + TogetherIntro.drivePath).also { carPath = it }
        // the segment the car is on: it only moves forward along the path
        fun along(i: Int, px: Int, pz: Int): Double {
            val ax = path[i][0].toDouble(); val az = path[i][1].toDouble(); val bx = path[i + 1][0].toDouble(); val bz = path[i + 1][1].toDouble()
            val len2 = (bx - ax) * (bx - ax) + (bz - az) * (bz - az)
            return if (len2 < 1.0) 1.0 else (((px - ax) * (bx - ax) + (pz - az) * (bz - az)) / len2)
        }
        while (carSegment < path.size - 2 && along(carSegment, car.renderX, car.renderZ) >= 1.0) carSegment++
        // the point ahead: walk along the path from the car's place by the look-ahead distance
        var remain = CAR_LOOK_AHEAD
        var i = carSegment
        var px = car.renderX.toDouble(); var pz = car.renderZ.toDouble()
        var tx = path[path.size - 1][0].toDouble(); var tz = path[path.size - 1][1].toDouble()
        while (i < path.size - 1) {
            val bx = path[i + 1][0].toDouble(); val bz = path[i + 1][1].toDouble()
            val seg = Math.hypot(bx - px, bz - pz)
            if (seg >= remain) { tx = px + (bx - px) * remain / seg; tz = pz + (bz - pz) * remain / seg; break }
            remain -= seg; px = bx; pz = bz; i++
            tx = bx; tz = bz
        }
        val want = Partner.headingOf((tx - car.renderX).toFloat(), (tz - car.renderZ).toFloat())
        if (carHeading < 0f) carHeading = (car.beta and 0x3FF).toFloat()
        var diff = (want - carHeading) % 1024f
        if (diff > 512f) diff -= 1024f
        if (diff < -512f) diff += 1024f
        carHeading = ((carHeading + (diff * CAR_STEER_EASE).coerceIn(-CAR_STEER_MAX, CAR_STEER_MAX)) % 1024f + 1024f) % 1024f
        car.beta = Math.round(carHeading) and 0x3FF
    }

    private var stopSamplesAtEnd = false

    /**
     * A creature on its way touches other objects, the player too (checkCollision of the original reports every box it overlaps, it does not need to be blocked): the
     * creature gets COL and the touched object COL_BY, which is how the scripts notice it, for example the window that cracks when a creature breaks through, or the
     * creature that starts to bite when it touches the player.
     */
    private fun touchObjects(g: GameObject, a: Actor) {
        val z = a.zv
        for (o in objects) {
            if (o === g || !o.w.active) continue
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
        // Together mode: the other hero is a solid body (he steps aside, see [tickCompanion])
        if (companionAlive) companion?.actor?.let { boxes.add(Actor.Collider(it.zv, false, true)); owners.add(-1) }
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
        for (zone in geometryOf(g.w.room).second) {
            if (!zone.contains(x, y, z)) continue
            when (zone.type) {
                0 -> if (g.w.index == leader) {
                    enterNextRoom(g, zone.parameter)
                } else if (g.w.room != zone.parameter) { // an enemy that walks through a door of its room is in the next room from then on
                    g.w.room = zone.parameter
                    a.roomNumber = zone.parameter
                    a.hardcols = geometryOf(zone.parameter).first
                }
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
        changeRoom(stage, nextRoom, null, leaderMoved = true)
    }

    /**
     * The scene changes to room [newRoom] of floor [newStage]: the objects of the old room leave the scene, those of the new room join it, the player stays and
     * is placed at [place] (x, y, z) unless it is null (his coordinates are already those of the new room).
     */
    private fun changeRoom(newStage: Int, newRoom: Int, place: IntArray?, leaderMoved: Boolean = false) {
        val view = try {
            data.rooms.room(newStage, newRoom)
        } catch (_: Exception) {
            exited = true
            ui.leftRoom(newStage, newRoom)
            return
        }
        // the objects that stay: the scene keeps those the original keeps (see [wanted]) and moves them into the frame of the new room, the others leave
        val oldView = roomView
        val oldStage = stage
        currentCamera = -1
        if (newStage != oldStage) stopSamplesAtEnd = true
        stage = newStage
        room = newRoom
        roomView = view
        roomVersion++
        val seen = viewList()
        val dx = (view.worldX - oldView.worldX) * 10
        val dy = (view.worldY - oldView.worldY) * 10
        val dz = (view.worldZ - oldView.worldZ) * 10
        for (g in objects) {
            if (!g.w.active || g.w.index == playerIndex) continue
            if (oldStage == newStage && wanted(g.w, seen, newStage, newRoom)) {
                val a = g.actor
                if (a != null) {
                    if (oldView !== view && !(leaderMoved && g.w.index == leader)) a.moveToRoomOrigin(dx, dy, dz)
                    a.hardcols = geometryOf(g.w.room).first
                }
            } else {
                deactivate(g)
            }
        }
        for (g in objects) if (g.w.active && g.w.typeZv == 4) applyHardZv(g)
        val p = if (intro) null else player
        p?.w?.stage = newStage
        p?.w?.room = newRoom
        val lead = if (intro) objects.getOrNull(leader)?.takeIf { it.w.active } else p
        lead?.actor?.let { a ->
            a.hardcols = view.hardcols
            a.roomNumber = newRoom
            if (place != null) a.teleport(place[0], place[1], place[2], lead.w.beta)
        }
        for (o in vm.objects) if (o.index != playerIndex && !objects[o.index].w.active && wanted(o, seen, newStage, newRoom)) activate(objects[o.index])
        p?.let {
            it.w.roomChronoStart = time
            it.w.hardDec = -1
        }
        if (!intro) placeCompanion()
    }

    /** For testing (floor selection of the main menu): puts the player into a room of the game at a free place that its first camera sees. */
    fun startAt(newStage: Int, newRoom: Int) {
        if (newStage == stage && newRoom == room) return
        val view = try { data.rooms.room(newStage, newRoom) } catch (_: Exception) { return }
        val c = view.cameras.firstOrNull()?.zones?.firstOrNull()?.takeIf { it.isNotEmpty() }
        val cx = c?.let { p -> (p.sumOf { it.x.toDouble() } / p.size * 10).toInt() } ?: (view.bounds[0] + view.bounds[1]) / 2
        val cz = c?.let { p -> (p.sumOf { it.z.toDouble() } / p.size * 10).toInt() } ?: (view.bounds[2] + view.bounds[3]) / 2
        fun free(x: Int, z: Int) = x in view.bounds[0]..view.bounds[1] && z in view.bounds[2]..view.bounds[3] &&
            view.hardcols.none { x in it.x1 - 300..it.x2 + 300 && z in it.z1 - 300..it.z2 + 300 } &&
            view.zones.none { it.type == 0 && x in it.x1 - 400..it.x2 + 400 && z in it.z1 - 400..it.z2 + 400 }
        var spot = intArrayOf(cx, 0, cz)
        search@ for (r in 0..4000 step 250) for (k in 0 until if (r == 0) 1 else 16) {
            val ang = k * Math.PI / 8
            val x = cx + (r * Math.cos(ang)).toInt()
            val z = cz + (r * Math.sin(ang)).toInt()
            if (free(x, z)) { spot = intArrayOf(x, 0, z); break@search }
        }
        changeRoom(newStage, newRoom, spot)
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

    override fun getHardClip(obj: Int) {
        val g = objects.getOrNull(obj) ?: return
        val z = g.actor?.zv ?: return
        // the first collider of the object's room whose box overlaps the object's box (the original walks the room's list of colliders in order)
        val hit = geometryOf(g.w.room).first.firstOrNull { z[0] < it.x2 && it.x1 < z[1] && z[2] < it.y2 && it.y1 < z[3] && z[4] < it.z2 && it.z1 < z[5] }
        g.clipBox = hit?.let { intArrayOf(it.x1, it.x2, it.y1, it.y2, it.z1, it.z2) }
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
        val moves = MoveAnims(anims[0], anims[1], anims[2], anims[3], anims[4], anims[5], anims[6])
        if (obj == playerIndex) playerAnims = moves
        a.chooseMoveAnim(moves)
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
                val a = g.actor ?: return
                val ours = g === companion?.obj
                // a body without the hand group: the zone lies in front of the hero
                val hot = hotPoint(g, g.actionGroup) ?: if (ours) {
                    val ang = (a.beta and 0x3FF) * 2.0 * Math.PI / 1024.0
                    intArrayOf((Math.sin(ang) * 450).toInt(), -900, (-Math.cos(ang) * 450).toInt())
                } else intArrayOf(0, 0, 0)
                val x = a.renderX + hot[0]
                val y = a.renderY + hot[1]
                val z = a.renderZ + hot[2]
                val r = g.actionRange
                for (other in objects) {
                    if (other === g || !other.w.active) continue
                    // the other hero fights monsters, and the player when he was hit by him (not the furniture)
                    if (ours && !isMonster(other) && !(other === player && time < provokedUntil)) continue
                    val zv = other.actor?.zv ?: continue
                    if (!(zv[0] < x + r && x - r < zv[1] && zv[2] < y + r && y - r < zv[3] && zv[4] < z + r && z - r < zv[5])) continue
                    w.hit = other.w.index
                    other.w.hitBy = w.index
                    other.w.hitForce = g.actionForce
                    if (ours) companionHitTime[other.w.index] = time
                    if (other.actor?.animated == true) {
                        g.actionType = 0
                        return
                    }
                }
                // a monster's blow can hit the other hero as well
                val cmp = companion
                val cz = cmp?.actor?.zv
                if (!ours && cmp != null && cz != null && (isMonster(g) || g === player) &&
                    cz[0] < x + r && x - r < cz[1] && cz[2] < y + r && y - r < cz[3] && cz[4] < z + r && z - r < cz[5]) {
                    hurtCompanion(g.actionForce, g)
                    g.actionType = 0
                    return
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
            val cmp = companion
            val cbox = cmp?.actor?.zv
            if (cmp != null && cbox != null && g === player && cbox[0] < x + p && x - p < cbox[1] && cbox[2] < y + p && y - p < cbox[3] && cbox[4] < z + p && z - p < cbox[5]) {
                impact(prevX, prevZ)
                hurtCompanion(g.actionForce, g)
                return
            }
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
        if (mode == 3) vm.objects.getOrNull(obj)?.trackPos = 0
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
        val inScene = wanted(w, viewList(), stage, room)
        if (obj == leader && !inScene && w.stage >= 0) {
            changeRoom(w.stage, w.room, intArrayOf(w.x, w.y, w.z))
            return
        }
        if (inScene && !w.active) {
            activate(g)
        } else if (!inScene && w.active) {
            deactivate(g)
        } else if (w.active) {
            val p = currentFrame(w)
            g.actor?.teleport(p[0], p[1], p[2], w.beta)
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
        if (intro) { // the scene follows the target into its room
            val w = vm.objects.getOrNull(obj) ?: return
            if (w.stage >= 0 && (w.stage != stage || w.room != room)) changeRoom(w.stage, w.room, null)
        }
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
        ui.music(n, false)
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
        ui.fadeMusic(n)
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
                val byCompanion = companion != null && (companionHitTime[obj] ?: -99) >= time - 3
                val by = if (byCompanion) companion?.obj else objects.getOrNull(g.w.hitBy)
                val h = by?.let { b -> b.shotPoint?.also { b.shotPoint = null } ?: hotPoint(b, b.actionGroup) }
                val b = by?.actor
                if (b != null) effects.add(Effect(1, b.renderX + (h?.get(0) ?: 0), b.renderY + (h?.get(1) ?: 0), b.renderZ + (h?.get(2) ?: 0), time, null, 0, (-b.beta) and 0x3FF, 0))
            }
            4 -> if (a != null) effects.add(Effect(4, a.renderX, a.renderY, a.renderZ, time, null, g.w.alpha, a.beta and 0x3FF, g.w.gamma))
        }
    }

    override fun water(n: Int) {
        waterLimit = if (n != 0) -600 else 10000
    }

    override fun shaking(n: Int) {
        shaking = n == 1
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
        // Together mode: who is controlled, the health of the other hero, and whether he is still there
        out.writeBoolean(data.swapped); out.writeInt(helperHp); out.writeBoolean(companionAlive)
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
        var savedSwapped = false; var savedHelperHp = -1; var savedAlive = true
        try { savedSwapped = inp.readBoolean(); savedHelperHp = inp.readInt(); savedAlive = inp.readBoolean() } catch (_: java.io.IOException) { }
        if (savedSwapped != data.swapped && data.hasCompanion) data.swapHeroes()
        companionAlive = savedAlive
        if (savedHelperHp >= 0) helperHp = savedHelperHp
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
                val at = currentFrame(w)
                a.teleport(at[0], at[1], at[2], x[0])
                a.restoreMove(x[1], x[2], x[3], x[4])
                a.wallCollision = fl[0]; a.triggersOn = fl[1]
                a.memoX = x[5]; a.memoY = x[6]; a.memoZ = x[7]
            }
        }
        if (!intro) { if (companion == null) { val keep = helperHp; startCompanion(); if (savedHelperHp >= 0) helperHp = keep } else placeCompanion() }
        return true
    }

    companion object {
        const val PLAYER_SCRIPT = 549

        /** The life scripts of the monsters (the ones that hit and dissolve when they die). */
        private val MONSTER_SCRIPTS = setOf(18, 21, 53, 74, 84, 91, 92, 123, 131, 132, 142, 268, 353, 366, 400, 405, 410, 415, 442, 445, 448, 467, 476, 480, 484, 501, 517)

        /** How far away a monster is for the other hero to go for it, the reach of his fist (centre to centre), the pause between two blows, the data of the fist (HIT 37 1 18 200 force 38). */
        private const val THREAT_RANGE = 5500
        private const val SIDESTEP_DISTANCE = 850f
        private const val SIDESTEP_LENGTH = 1100
        private const val SIDESTEP_TICKS = 100
        private const val FIGHT_REACH = 950f
        private const val BLOW_PAUSE = 50
        private const val PROVOKED_TICKS = 900
        /** The blows of the bare fists: the left hand, the right hand and the foot (script 560 of the Fight action: HIT 265 1 10 200 1 264, HIT 263 1 13 200 1 264, HIT 262 1 16 200 1 264). */
        private val FIST_ANIMS = intArrayOf(265, 263, 262)
        private val FIST_GROUPS = intArrayOf(10, 13, 16)
        private const val FIST_NEXT = 264
        private const val FIST_FRAME = 1
        private const val FIST_SIZE = 200
        private const val FIST_FORCE = 1

        /** The sound of a monster that dissolves (SAMPLE 6 of its script). */
        private const val DEATH_SAMPLE = 6

        /** The car and the walking hero of the intro (objects of the film). */
        const val INTRO_CAR = 286
        const val INTRO_WALKER = 288

        /** Together mode, the intro: the places beside the walker (across, back) in his own frame, in order of liking: beside and a little behind on one side, the other side, straight behind. */
        private val WALK_PLACES = arrayOf(intArrayOf(75, 40), intArrayOf(-75, 40), intArrayOf(65, 110), intArrayOf(-65, 110), intArrayOf(0, 130))

        /** Inside the house the second hero keeps a good distance behind the first (more than two metres, in the outdoor scale of the places), well to one side so he is not hidden by him. */
        private val WALK_PLACES_INDOORS = arrayOf(intArrayOf(90, 230), intArrayOf(-90, 230), intArrayOf(65, 190), intArrayOf(-65, 190), intArrayOf(0, 260), intArrayOf(0, 180), intArrayOf(90, 120), intArrayOf(-90, 120), intArrayOf(80, 40), intArrayOf(-80, 40))

        /** The floor of the intro that lies outdoors. */
        private const val INTRO_OUTDOORS_STAGE = 7

        /** The steering of the car: how far ahead on its path it aims (units), the share of the remaining turn it takes each tick, and the largest turn in one tick (1024 = a full turn). */
        private const val CAR_LOOK_AHEAD = 650.0
        private const val CAR_STEER_EASE = 0.10f
        private const val CAR_STEER_MAX = 2.2f
        private const val WALK_PLACE_PATIENCE = 25

        /** The heroes in the car are drawn this much larger than the body of the film (the car's own occupants were drawn larger than life as well). */
        private const val SEAT_SCALE = 1.6f

        /** Together mode, the intro: where the two figures sit in the car body (x across, y up is negative, z back; the car looks along -z): the driver's head, the passenger's. */
        private val CAR_DRIVER = floatArrayOf(20f, 0f, 30f)
        private val CAR_PASSENGER = floatArrayOf(-20f, 0f, 30f)

        /** The floor of the car (y, up is negative): the soles of the seated heroes rest on it; from the hip joint to the sole of a seated body are 52 units (before it is scaled). */
        private const val CAR_FLOOR = -42f
        private const val SEATED_HIP_TO_SOLE = 52f
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
