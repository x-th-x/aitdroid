package com.aitddemo.life

import com.aitddemo.original.s16
import com.aitddemo.original.u16

/** An object of the game world (entry of OBJETS.ITD, 52 bytes) with the state the life scripts read and change. */
class WorldObject(val index: Int, private val d: ByteArray) {
    private val p = 2 + index * SIZE
    private fun w(i: Int) = d.s16(p + i * 2)

    var body = w(1)
    var flags = w(2)
    var typeZv = w(3)
    var foundBody = w(4)
    var foundName = w(5)
    var foundFlag = w(6) and 0xFFFF
    var foundLife = w(7)
    var x = w(8)
    var y = w(9)
    var z = w(10)
    var alpha = w(11)
    var beta = w(12)
    var gamma = w(13)
    var stage = w(14)
    var room = w(15)
    var lifeMode = w(16)
    var life = w(17)
    var floorLife = w(18)
    var anim = w(19)
    var frame = w(20)
    var animType = w(21)
    var animInfo = w(22)
    var trackMode = w(23)
    var trackNumber = w(24)
    /** Weight of an item (the original stores it where the track position is kept). */
    var weight = w(25)
    var mark = -1

    // ---- runtime state set by the game before the scripts run each frame ----

    /** Is the object part of the scene right now (room loaded, actor exists)? Only then are the runtime values valid. */
    var active = false
    var endFrame = 0
    var flagEndAnim = 0
    var hardDec = -1
    var hardMat = -1
    var hardCol = -1
    var col = -1
    var colBy = -1
    var hit = -1
    var hitBy = -1
    var hitForce = 0
    var falling = 0
    var chronoStart = 0
    var roomChronoStart = 0

    companion object {
        const val SIZE = 52
    }
}

/** Effects of the scripts on the game; every call has a harmless default so a host only handles what it supports. Times are engine ticks (60 per second). */
interface LifeHost {
    val time: Int

    // ---- reading the scene (expressions)
    fun posRel(actor: Int, other: Int): Int = 0
    fun dist(actor: Int, other: Int): Int = 32000
    fun roomY(actor: Int): Int = 0
    fun testZvEndAnim(actor: Int, anim: Int, param: Int): Int = 0
    fun cameraNumber(): Int = 0

    // ---- appearance and movement
    fun body(obj: Int) {}
    fun anim(obj: Int, anim: Int, type: Int, info: Int) {}
    fun doMove(obj: Int) {}
    fun animMove(obj: Int, anims: IntArray) {}
    fun trackMode(obj: Int, mode: Int, number: Int) {}
    fun manualRot(obj: Int) {}
    fun resetMove(obj: Int) {}
    fun continueTrack(obj: Int) {}
    fun setBeta(obj: Int, beta: Int, speed: Int) {}
    fun setAlpha(obj: Int, alpha: Int, speed: Int) {}
    fun testCol(obj: Int, on: Boolean) {}
    fun moved(obj: Int) {} // STAGE, ANGLE, PUT: position, room or angles of an object were set
    fun zv(obj: Int, op: String, values: IntArray) {} // DEF_ZV, DEF_ABS_ZV, DO_REAL_ZV, DO_MAX_ZV, DO_CARRE_ZV, DO_NORMAL_ZV, DO_ROT_ZV

    // ---- fighting
    fun hit(obj: Int, anim: Int, frame: Int, group: Int, size: Int, force: Int, nextAnim: Int) {}
    fun fire(obj: Int, anim: Int, frame: Int, hotPoint: Int, range: Int, force: Int, nextAnim: Int) {}
    fun throwObject(obj: Int, args: IntArray) {}
    fun hitObject(obj: Int, a: Int, b: Int) {}
    fun stopHitObject(obj: Int) {}

    // ---- items and the world
    fun found(item: Int) {}
    fun take(item: Int) {}
    fun drop(item: Int, from: Int) {}
    fun putAt(item: Int, target: Int) {}
    fun put(item: Int, x: Int, y: Int, z: Int, stage: Int, room: Int, alpha: Int, beta: Int, gamma: Int) {}
    fun delete(obj: Int) {}
    fun read(index: Int, type: Int) {} // type 0 = letter, 1 = book, 2 = notebook; index = text entry of ENGLISH.PAK
    fun special(obj: Int, kind: Int) {}
    fun lifeChanged(obj: Int, life: Int) {}

    // ---- presentation
    fun message(id: Int) {}
    fun sample(n: Int) {}
    fun repSample(n: Int) {}
    fun stopSample() {}
    fun sampleThen(first: Int, then: Int) {}
    fun randomFrequency(percent: Int) {}
    fun music(n: Int) {}
    fun nextMusic(n: Int) {}
    fun fadeMusic(n: Int) {}
    fun picture(index: Int, ticks: Int, sample: Int) {}
    fun sequence(n: Int) {}
    fun cameraTarget(obj: Int) {}
    fun light(on: Int) {}
    fun shaking(n: Int) {}
    fun water(n: Int) {}
    fun inventory(allowed: Boolean) {}
    fun setInventory(n: Int) {}
    fun callInventory() {}
    fun gameOver() {}
    fun unsupported(op: String, obj: Int) {}
}

/**
 * Interpreter of the life scripts (LISTLIFE.PAK) of Alone in the Dark. As in the original, every frame the script of each active object runs from the
 * beginning to RETURN/END; the state lives in the game variables and in the [objects]. Operations that do something in the game are handed to the [host].
 */
class LifeVm(
    private val scriptSource: (Int) -> ShortArray?,
    objectData: ByteArray,
    initialVars: ShortArray,
    private val host: LifeHost,
    private val random: java.util.Random = java.util.Random(),
) {
    val vars: ShortArray = initialVars.copyOf(maxOf(initialVars.size, 512))
    val cvars = ShortArray(64)
    val objects: Array<WorldObject> = Array(objectData.u16(0)) { WorldObject(it, objectData) }

    /** The action key (bit mask of the chosen action, as `action` in the original), the click flag and the joystick bits of this frame. */
    var action = 0
    var click = 0
    var joyd = 0

    /** Item in the hand (world object) or -1. */
    var inHand = -1

    /** Number of the music that plays, -1 = none. */
    var currentMusic = -1

    val unsupported = LinkedHashSet<String>()

    /** Debugging: changes of the life script of an object, and other notable operations. */
    var trace: ((String) -> Unit)? = null

    private val cache = HashMap<Int, ShortArray?>()

    private fun script(n: Int): ShortArray? = cache.getOrPut(n) { scriptSource(n) }

    /** Runs the script of object [index] (if it has one). */
    fun runObject(index: Int) {
        val o = objects.getOrNull(index) ?: return
        if (o.life < 0) return
        run(index, o.life)
    }

    /** Runs script [life] with [owner] as the executing object. */
    fun run(owner: Int, life: Int) {
        val w = script(life) ?: return
        Exec(owner, w, life).run()
    }

    private inner class Exec(val owner: Int, val w: ShortArray, val scriptNo: Int) {
        var p = 0
        var switchVal = 0

        fun s16(): Int = if (p < w.size) w[p++].toInt() else 0

        fun eval(): Int {
            val v = s16()
            if (v == -1) return s16()
            if (v == 0) return vars.getOrElse(s16()) { 0 }.toInt()
            var idx = owner
            var explicit = false
            if (v and 0x8000 != 0) {
                idx = s16()
                explicit = true
            }
            val code = (v and 0x7FFF) - 1
            val t = objects.getOrNull(idx)
            if (t == null || (explicit && !t.active && code != 0x1E && code != 0x25)) {
                // the object is not in the scene: only its room and stage are known
                skipOperands(code)
                return when (code) {
                    0x1E -> t?.room ?: -1
                    0x25 -> t?.stage ?: -1
                    else -> 0
                }
            }
            return when (code) {
                0x00 -> t.col
                0x01 -> t.hardDec
                0x02 -> t.hardCol
                0x03 -> t.hit
                0x04 -> t.hitBy
                0x05 -> t.anim
                0x06 -> t.flagEndAnim
                0x07 -> t.frame
                0x08 -> t.endFrame
                0x09 -> t.body
                0x0A -> t.mark
                0x0B -> t.trackNumber
                0x0C -> (host.time - t.chronoStart) / 60
                0x0D -> (host.time - t.roomChronoStart) / 60
                0x0E -> {
                    val o = objects.getOrNull(s16())
                    if (o == null || !o.active) 32000 else host.dist(idx, o.index)
                }
                0x0F -> t.colBy
                0x10 -> if ((objects.getOrNull(eval())?.foundFlag ?: 0) and 0x8000 != 0) 1 else 0
                0x11 -> action
                0x12 -> {
                    val o = objects.getOrNull(s16())
                    if (o == null || !o.active) 0 else host.posRel(idx, o.index)
                }
                0x13 -> when {
                    joyd and 4 != 0 -> 4
                    joyd and 8 != 0 -> 8
                    joyd and 1 != 0 -> 1
                    joyd and 2 != 0 -> 2
                    else -> 0
                }
                0x14 -> click
                0x15 -> if (t.col != -1) t.col else t.colBy
                0x16 -> t.alpha
                0x17 -> t.beta
                0x18 -> t.gamma
                0x19 -> inHand
                0x1A -> t.hitForce
                0x1B -> host.cameraNumber()
                0x1C -> {
                    val n = s16()
                    if (n > 0) random.nextInt(n) else 0
                }
                0x1D -> t.falling
                0x1E -> t.room
                0x1F -> t.life
                0x20 -> if ((objects.getOrNull(s16())?.foundFlag ?: 0) and 0xC000 != 0) 1 else 0
                0x21 -> host.roomY(idx)
                0x22 -> {
                    val a = s16()
                    val b = s16()
                    host.testZvEndAnim(idx, a, b)
                }
                0x23 -> currentMusic
                0x24 -> cvars.getOrElse(s16()) { 0 }.toInt()
                0x25 -> t.stage
                0x26 -> if ((objects.getOrNull(s16())?.foundFlag ?: 0) and 0x1000 != 0) 1 else 0
                else -> {
                    host.unsupported("var 0x%X".format(code), owner)
                    0
                }
            }
        }

        fun skipOperands(code: Int) {
            if (code == 0x10) eval() else for (i in 0 until Aitd1Ops.extraOperands(code)) s16()
        }

        fun run() {
            var steps = 0
            while (p < w.size) {
                if (++steps > 20000) {
                    host.unsupported("endless loop in script $scriptNo", owner)
                    return
                }
                val op = s16()
                var target = owner
                if (op and 0x8000 != 0) {
                    target = s16()
                    if (target < 0 || target >= objects.size) return
                }
                val name = Aitd1Ops.NAMES.getOrNull(op and 0x7FFF) ?: return
                val t = objects[target]
                when (name) {
                    "IF_EGAL", "IF_DIFFERENT", "IF_SUP_EGAL", "IF_SUP", "IF_INF_EGAL", "IF_INF" -> {
                        val a = eval()
                        val b = eval()
                        val j = s16()
                        val ok = when (name) {
                            "IF_EGAL" -> a == b
                            "IF_DIFFERENT" -> a != b
                            "IF_SUP_EGAL" -> a >= b
                            "IF_SUP" -> a > b
                            "IF_INF_EGAL" -> a <= b
                            else -> a < b
                        }
                        if (!ok) p += j
                    }
                    "GOTO" -> {
                        val j = s16()
                        p += j
                    }
                    "SWITCH" -> switchVal = eval()
                    "CASE" -> {
                        val c = s16()
                        val j = s16()
                        if (c != switchVal) p += j
                    }
                    "MULTI_CASE" -> {
                        val n = s16()
                        var hit = false
                        for (i in 0 until n) if (s16() == switchVal) hit = true
                        val j = s16()
                        if (!hit) p += j
                    }
                    "RETURN", "END" -> return
                    "GAME_OVER", "WAIT_GAME_OVER" -> {
                        host.gameOver()
                        return
                    }
                    "VAR" -> { val i = s16(); val v = eval(); if (i in vars.indices) vars[i] = v.toShort() }
                    "INC" -> { val i = s16(); if (i in vars.indices) vars[i]++ }
                    "DEC" -> { val i = s16(); if (i in vars.indices) vars[i]-- }
                    "ADD" -> { val i = s16(); val v = eval(); if (i in vars.indices) vars[i] = (vars[i] + v).toShort() }
                    "SUB" -> { val i = s16(); val v = eval(); if (i in vars.indices) vars[i] = (vars[i] - v).toShort() }
                    "C_VAR" -> { val i = s16(); val v = eval(); if (i in cvars.indices) cvars[i] = v.toShort() }
                    "MESSAGE" -> host.message(s16())
                    "MESSAGE_VALUE" -> { val m = s16(); s16(); host.message(m) }

                    // ---- appearance
                    "BODY" -> {
                        t.body = eval()
                        if (t.active) host.body(target)
                    }
                    "TYPE" -> t.flags = (t.flags and 1) or s16()
                    "ANIM_ONCE" -> {
                        val a = s16()
                        val info = s16()
                        t.anim = a
                        host.anim(target, a, 0, info)
                    }
                    "ANIM_ALL_ONCE" -> {
                        val a = s16()
                        val info = s16()
                        t.anim = a
                        host.anim(target, a, 2, info)
                    }
                    "ANIM_REPEAT" -> {
                        val a = s16()
                        t.anim = a
                        host.anim(target, a, 1, -1)
                    }
                    "ANIM_SAMPLE" -> {
                        val sample = eval()
                        val a = s16()
                        val f = s16()
                        if (t.endFrame != 0 && t.anim == a && t.frame == f && sample >= 0) host.sample(sample)
                    }
                    "ANIM_MOVE" -> host.animMove(target, IntArray(7) { s16() })
                    "MOVE" -> {
                        val mode = s16()
                        val num = s16()
                        t.trackMode = mode
                        t.trackNumber = num
                        host.trackMode(target, mode, num)
                    }
                    "DO_MOVE" -> host.doMove(target)
                    "MANUAL_ROT" -> host.manualRot(target)
                    "CONTINUE_TRACK" -> host.continueTrack(target)
                    "SET_BETA" -> { val b = s16(); val sp = s16(); host.setBeta(target, b, sp) }
                    "SET_ALPHA" -> { val a = s16(); val sp = s16(); host.setAlpha(target, a, sp) }
                    "ANGLE" -> {
                        t.alpha = s16(); t.beta = s16(); t.gamma = s16()
                        host.moved(target)
                    }
                    "COPY_ANGLE" -> {
                        val o = objects.getOrNull(s16())
                        if (o != null) { t.alpha = o.alpha; t.beta = o.beta; t.gamma = o.gamma; host.moved(target) }
                    }
                    "STAGE" -> {
                        t.stage = s16(); t.room = s16(); t.x = s16(); t.y = s16(); t.z = s16()
                        trace?.invoke("script $scriptNo (obj $owner): obj $target STAGE ${t.stage}/${t.room} at ${t.x},${t.y},${t.z}")
                        host.moved(target)
                    }
                    "TEST_COL" -> host.testCol(target, s16() != 0)
                    "LIFE" -> {
                        t.life = s16()
                        trace?.invoke("script $scriptNo (obj $owner): obj $target -> life ${t.life}")
                        host.lifeChanged(target, t.life)
                    }
                    "LIFE_MODE" -> t.lifeMode = s16()
                    "STAGE_LIFE" -> t.floorLife = s16()
                    "START_CHRONO" -> t.chronoStart = host.time

                    // ---- zones of an object
                    "DEF_ZV", "DEF_ABS_ZV" -> host.zv(target, name, IntArray(6) { s16() })
                    "DO_REAL_ZV", "DO_ROT_ZV", "DO_MAX_ZV", "DO_NORMAL_ZV", "DO_CARRE_ZV" -> host.zv(target, name, IntArray(0))

                    // ---- fighting
                    "HIT" -> {
                        val a = s16(); val f = s16(); val g = s16(); val size = s16(); val force = eval(); val next = s16()
                        host.hit(target, a, f, g, size, force, next)
                    }
                    "FIRE" -> {
                        val a = s16(); val f = s16(); val hp = s16(); val range = s16(); val force = s16(); val next = s16()
                        host.fire(target, a, f, hp, range, force, next)
                    }
                    "THROW" -> host.throwObject(target, IntArray(7) { s16() })
                    "HIT_OBJECT" -> { val a = s16(); val b = s16(); host.hitObject(target, a, b) }
                    "STOP_HIT_OBJECT" -> host.stopHitObject(target)

                    // ---- items
                    "FOUND" -> host.found(s16())
                    "TAKE" -> host.take(s16())
                    "IN_HAND" -> inHand = s16()
                    "DROP" -> { val item = eval(); val from = s16(); host.drop(item, from) }
                    "PUT_AT" -> { val a = s16(); val b = s16(); host.putAt(a, b) }
                    "PUT" -> {
                        val item = s16(); val x = s16(); val y = s16(); val z = s16(); val room = s16(); val stage = s16()
                        val alpha = s16(); val beta = s16(); val gamma = s16()
                        host.put(item, x, y, z, stage, room, alpha, beta, gamma)
                    }
                    "DELETE" -> {
                        val i = s16()
                        val o = objects.getOrNull(i)
                        if (o != null) {
                            o.room = -1
                            o.stage = -1
                            o.active = false
                            if (o.foundBody != -1) {
                                o.foundFlag = (o.foundFlag and 0x8000.inv()) or 0x4000
                            }
                            host.delete(i)
                        }
                    }
                    "READ" -> { val type = s16(); val index = s16(); s16(); host.read(index, type) }
                    "FOUND_NAME" -> t.foundName = s16()
                    "FOUND_BODY" -> t.foundBody = s16()
                    "FOUND_FLAG" -> t.foundFlag = (t.foundFlag and 0xE000) or (s16() and 0x1FFF)
                    "FOUND_LIFE" -> t.foundLife = s16()
                    "FOUND_WEIGHT" -> t.weight = s16()
                    "INVENTORY" -> host.inventory(s16() != 0)
                    "SET_INVENTORY" -> host.setInventory(s16())
                    "SPECIAL" -> host.special(target, s16())

                    // ---- sound, music, pictures
                    "SAMPLE" -> { val n = eval(); if (n >= 0) host.sample(n) }
                    "REP_SAMPLE" -> { val n = eval(); s16(); if (n >= 0) host.repSample(n) }
                    "STOP_SAMPLE" -> host.stopSample()
                    "SAMPLE_THEN", "SAMPLE_THEN_REPEAT" -> { val a = eval(); val b = eval(); if (a >= 0) host.sampleThen(a, b) }
                    "RND_FREQ" -> host.randomFrequency(s16())
                    "MUSIC" -> { val n = s16(); currentMusic = n; host.music(n) }
                    "NEXT_MUSIC" -> host.nextMusic(s16())
                    "FADE_MUSIC" -> { val n = s16(); currentMusic = n; host.fadeMusic(n) }
                    "PICTURE" -> { val i = s16(); val ticks = s16(); val sample = s16(); host.picture(i, ticks, sample) }
                    "CAMERA_TARGET" -> host.cameraTarget(s16())
                    "LIGHT" -> host.light(s16())
                    "SHAKING" -> host.shaking(s16())
                    "WATER" -> host.water(s16())
                    "CALL_INVENTORY" -> host.callInventory()
                    "SPEED", "CAMERA", "STOP_BETA", "UP_COOR_Y", "GET_HARD_CLIP", "END_SEQUENCE" -> {
                        skip(name)
                        note(name, target)
                    }
                    else -> {
                        skip(name)
                        note(name, target)
                    }
                }
            }
        }

        fun note(name: String, target: Int) {
            if (unsupported.add(name)) host.unsupported(name, target)
        }

        fun skip(name: String) {
            val sig = Aitd1Ops.SIG[name] ?: run { p = w.size; return }
            for (c in sig) when (c) {
                'v' -> eval()
                '*' -> {}
                else -> s16()
            }
        }
    }
}
