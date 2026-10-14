package com.aitddemo.game

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Together mode: the other hero of the pair. It is not an object of the original game (it has no life script and is not in the object table); [AtticGame] moves it.
 * After the intro it follows the player at a normal distance with the engine's own walking: the same animations as the player, the walls of the room and the other
 * objects stop it, and it walks round what is in its way.
 */
class Partner(val obj: GameObject) {
    private var moving = false

    /** The side it turns to when it walks round an obstacle (1 left, -1 right) and until when. */
    private var detourSide = 1
    private var detourUntil = 0

    private var checkTime = 0
    private var checkX = 0
    private var checkZ = 0
    private var stuck = 0

    val actor: Actor? get() = obj.actor

    /** Forgets what it was doing (after it was put at a new place). */
    fun reset(time: Int) {
        moving = false
        detourUntil = 0
        stuck = 0
        checkTime = time
        actor?.let { checkX = it.renderX; checkZ = it.renderZ }
    }

    /**
     * One tick of following [lead]: it turns towards the player and walks when it is far, stands when it is near (with a margin, so it does not jitter),
     * runs when it is far behind, and takes a short turn to one side when it makes no headway (a table, a corner). [onLost] is called when it is too far or stuck for
     * too long: the game puts it next to the player.
     */
    fun follow(time: Int, lead: Actor, anims: MoveAnims, onLost: () -> Unit) {
        val a = actor ?: return
        val dx = lead.renderX - a.renderX
        val dz = lead.renderZ - a.renderZ
        val dist = hypot(dx.toFloat(), dz.toFloat())
        if (dist > LOST_DISTANCE) { onLost(); return }
        if (moving) { if (dist < STOP_DISTANCE) moving = false } else if (dist > START_DISTANCE) moving = true
        var joy = 0
        if (moving) {
            var target = headingOf(dx.toFloat(), dz.toFloat())
            if (time < detourUntil) target += detourSide * DETOUR_TURN
            var diff = (target - (a.beta and 0x3FF)) % 1024f
            if (diff > 512f) diff -= 1024f
            if (diff < -512f) diff += 1024f
            if (diff > 40f) joy = joy or Joy.LEFT
            if (diff < -40f) joy = joy or Joy.RIGHT
            if (abs(diff) < 300f) joy = joy or Joy.FORWARD
        }
        a.processManual(joy, time, anims)
        if (moving && dist > RUN_DISTANCE && a.speed == 4) a.speed = 5
        if (moving && dist <= RUN_DISTANCE && a.speed == 5) a.speed = 4

        // no headway: step round the obstacle, and when that does not help for a long time, give up
        if (!moving) {
            stuck = 0
            checkTime = time
            checkX = a.renderX; checkZ = a.renderZ
        } else if (time - checkTime >= CHECK_TICKS) {
            val moved = hypot((a.renderX - checkX).toFloat(), (a.renderZ - checkZ).toFloat())
            if (moved < MIN_PROGRESS) {
                stuck++
                detourSide = -detourSide
                detourUntil = time + DETOUR_TICKS
                if (stuck >= GIVE_UP) { onLost(); return }
            } else {
                stuck = 0
            }
            checkTime = time
            checkX = a.renderX; checkZ = a.renderZ
        }
    }

    companion object {
        private const val START_DISTANCE = 2200f
        private const val STOP_DISTANCE = 1400f
        private const val RUN_DISTANCE = 4200f
        private const val LOST_DISTANCE = 9000f
        private const val DETOUR_TURN = 280f
        private const val DETOUR_TICKS = 70
        private const val CHECK_TICKS = 50
        private const val MIN_PROGRESS = 120f
        private const val GIVE_UP = 5

        /** Engine angle (1024 = 360 degrees) of a body that moves in direction (dx, dz): its local -Z axis points that way. */
        fun headingOf(dx: Float, dz: Float): Float {
            val a = atan2(dx, -dz) * 1024f / (2f * PI.toFloat())
            return ((a % 1024f) + 1024f) % 1024f
        }
    }
}

/**
 * The sitting pose of a figure in the car (Together mode, the intro). No animation of the game has a person sit down, so the pose is made from the bone groups of the
 * hero's body: the thighs forward, the shins down, the arms to the wheel (driver) or resting on the lap (passenger). The groups are numbered as in the intro body of both
 * heroes: 3/4 right thigh and shin, 14/15 left, 8/9 right upper arm and forearm, 11/12 left, 6 the upper body, 7 the head.
 */
object SeatedPose {
    private const val GROUPS = 17

    /** The bones that bend and the angle (1024 = a full turn) of each: x = forward/backward, y = outward/inward. */
    private val DRIVER = arrayOf(
        intArrayOf(3, 768, 12), intArrayOf(4, 256, 0), intArrayOf(14, 768, -12), intArrayOf(15, 256, 0),
        intArrayOf(8, 830, 8), intArrayOf(9, 880, 0), intArrayOf(11, 830, -8), intArrayOf(12, 880, 0),
        intArrayOf(6, 1016, 0),
    )
    private val PASSENGER = arrayOf(
        intArrayOf(3, 768, 12), intArrayOf(4, 256, 0), intArrayOf(14, 768, -12), intArrayOf(15, 256, 0),
        intArrayOf(8, 985, 0), intArrayOf(9, 850, 0), intArrayOf(11, 985, 0), intArrayOf(12, 850, 0),
        intArrayOf(6, 1016, 0),
    )

    fun apply(actor: Actor, asset: BodyAsset?, driver: Boolean) {
        if (asset == null || asset.groupCount != GROUPS) return
        for (b in if (driver) DRIVER else PASSENGER) {
            val s = actor.state[b[0]]
            s.type = 0; s.x = b[1]; s.y = b[2] and 0x3FF; s.z = 0
        }
    }
}
