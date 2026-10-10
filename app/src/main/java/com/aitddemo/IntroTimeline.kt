package com.aitddemo

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/** Position and heading (engine angle, 1024 = full turn) of something moving along a track. */
class PathState(val x: Float, val z: Float, val beta: Float)

/** A sound to start at [timeMs] after the start of its shot. */
class TimedSound(val timeMs: Long, val sample: Int)

/**
 * The intro of Alone in the Dark as a timeline built from the original data: the car drives along its track (LISTTRAK 29) from its
 * starting place (OBJETS.ITD), the driver walks his track (25); the cameras (ETAGE07.PAK) decide what is seen: the pond camera while
 * the car is inside its zone, then the road camera; after a short picture of the frog (picture 11) the mansion camera follows the
 * driver's first stretch, the courtyard camera the rest. Every position is projected through the real camera, so nothing is placed by hand.
 *
 * Timing: the pond shot lasts [POND_MS] (measured against the real intro); the car keeps that speed for the rest of its drive. The driver
 * walks at the speed of his walk animation (animation 245: root step per tick). The frog picture lasts 20 ticks (picture(11, 20, 78)).
 */
class IntroTimeline(private val data: IntroOriginal) {
    companion object {
        const val POND_MS = 17000L
        private const val FROG_PICTURE_TICKS = 20 // picture(11, 20, 78)

        /** The driver walks in room 0 of the stage (script 547: changeroom(E7R0, ...)). */
        private const val CARNBY_ROOM = 0
        private const val TURN_WINDOW = 350f
        private const val SAMPLE_GRAVEL = 77
        private const val SAMPLE_ENGINE_B = 76
        private const val SAMPLE_ENGINE_A = 75
        private const val SAMPLE_HORN_A = 79
        private const val SAMPLE_HORN_B1 = 80
        private const val SAMPLE_HORN_B2 = 81
        private const val SAMPLE_CAR_STOP = 59
        private const val SAMPLE_DOOR_A = 3
        private const val SAMPLE_DOOR_B = 4
        private const val SAMPLE_WALK_START = 89
        private const val SAMPLE_STEP_A = 82
        private const val SAMPLE_STEP_B = 83
        const val SAMPLE_FROG = 78
        private const val STEP_A_FRAME = 2
        private const val STEP_B_FRAME = 5

        /** Engine angle (1024 = 360 degrees) of a body that moves in direction (dx, dz): its local -Z axis points that way. */
        fun headingOf(dx: Float, dz: Float): Float {
            val a = atan2(dx, -dz) * 1024f / (2f * PI.toFloat())
            return ((a % 1024f) + 1024f) % 1024f
        }

        private fun lerpAngle(a: Float, b: Float, t: Float): Float {
            var d = (b - a) % 1024f
            if (d > 512f) d -= 1024f
            if (d < -512f) d += 1024f
            return ((a + d * t) % 1024f + 1024f) % 1024f
        }
    }

    /** Shift that turns coordinates of room [from] into coordinates of room [into]. */
    private fun roomShift(from: Int, into: Int): Pair<Int, Int> {
        val a = data.roomOrigins[from] ?: return 0 to 0
        val b = data.roomOrigins[into] ?: return 0 to 0
        return (a[0] - b[0]) to (a[1] - b[1])
    }

    // ---- car
    private val carX = FloatArray(data.carTrack.points.size + 1)
    private val carZ = FloatArray(data.carTrack.points.size + 1)
    private val carDist = FloatArray(carX.size)

    init {
        carX[0] = data.carStartX.toFloat()
        carZ[0] = data.carStartZ.toFloat()
        // waypoints are given in the coordinates of their own room: bring them into the car's start room
        data.carTrack.points.forEachIndexed { i, p ->
            val (dx, dz) = roomShift(p.room, data.carRoom)
            carX[i + 1] = (p.x + dx).toFloat()
            carZ[i + 1] = (p.z + dz).toFloat()
        }
        for (i in 1 until carX.size) carDist[i] = carDist[i - 1] + hypot(carX[i] - carX[i - 1], carZ[i] - carZ[i - 1])
    }

    /** Length of the car's whole drive. */
    val carLength: Float get() = carDist.last()

    /** Distance along the drive at which the car leaves the zone of the pond camera (where the road camera takes over). */
    val pondEndDistance: Float = run {
        var s = 0f
        var end = carLength
        while (s <= carLength) {
            val p = carPositionAt(s)
            if (!isInAtticZone(data.pond.camera, p.x, p.z)) { end = s; break }
            s += 5f
        }
        end
    }

    /** Car speed in units per millisecond (the pond shot is [POND_MS] long). */
    val carSpeed: Float = pondEndDistance / POND_MS

    /**
     * Length of one engine tick in milliseconds (the engine's timer constant, 15 ms). The measured speed of the car would give about 17.7 ms
     * ([IntroOriginal.carStepPerTick] units per tick against [carSpeed]); the 15 ms are kept so that the walking driver keeps its timing.
     */
    val tickMs: Float = 15f

    val roadMs: Long get() = ((carLength - pondEndDistance) / carSpeed).toLong()

    private fun carPositionAt(s: Float): PathState {
        val d = s.coerceIn(0f, carLength)
        var i = 1
        while (i < carDist.size - 1 && d > carDist[i]) i++
        val seg = (carDist[i] - carDist[i - 1]).coerceAtLeast(0.001f)
        val t = ((d - carDist[i - 1]) / seg).coerceIn(0f, 1f)
        return PathState(carX[i - 1] + (carX[i] - carX[i - 1]) * t, carZ[i - 1] + (carZ[i] - carZ[i - 1]) * t, 0f)
    }

    private fun segmentHeading(i: Int): Float = headingOf(carX[i] - carX[i - 1], carZ[i] - carZ[i - 1])

    /** Car state after driving [s] units: position on its track and heading (turning smoothly through the corners). */
    fun carAt(s: Float): PathState {
        val d = s.coerceIn(0f, carLength)
        val p = carPositionAt(d)
        var i = 1
        while (i < carDist.size - 1 && d > carDist[i]) i++
        var beta = segmentHeading(i)
        // before the end of this segment: turn into the next one; after its start: still coming out of the previous turn
        if (i < carDist.size - 1) {
            val w = min(TURN_WINDOW, min(carDist[i] - carDist[i - 1], carDist[i + 1] - carDist[i]) / 2f)
            val into = d - (carDist[i] - w)
            if (w > 0f && into > 0f) beta = lerpAngle(segmentHeading(i), segmentHeading(i + 1), (into / (2f * w)).coerceIn(0f, 1f))
        }
        if (i > 1) {
            val w = min(TURN_WINDOW, min(carDist[i - 1] - carDist[i - 2], carDist[i] - carDist[i - 1]) / 2f)
            val out = d - (carDist[i - 1] - w)
            if (w > 0f && out < 2f * w && out > 0f) beta = lerpAngle(segmentHeading(i - 1), segmentHeading(i), (out / (2f * w)).coerceIn(0f, 1f))
        }
        return PathState(p.x, p.z, beta)
    }

    /** Time (ms since the car started) at which the car has reached the waypoint count [k] of the track (0 = its starting place). */
    private fun carTimeAtPoint(k: Int): Long = (carDist[k.coerceIn(0, carDist.size - 1)] / carSpeed).toLong()

    /** Sounds of the pond shot (times since its start). */
    val pondSounds: List<TimedSound> get() = carSounds().filter { it.timeMs < POND_MS }

    /** Sounds of the road shot (times since its start). */
    val roadSounds: List<TimedSound> get() = carSounds().filter { it.timeMs >= POND_MS }.map { TimedSound(it.timeMs - POND_MS, it.sample) }

    /** Marks of the car's track that play a sound: 1 gravel, 5 engine, 11 engine, 13 horn, 15 two horns. */
    private fun carSounds(): List<TimedSound> {
        val out = ArrayList<TimedSound>()
        for ((mark, k) in data.carTrack.marks) {
            val t = carTimeAtPoint(k)
            when (mark) {
                1 -> out.add(TimedSound(t, SAMPLE_GRAVEL))
                5 -> out.add(TimedSound(t, SAMPLE_ENGINE_B))
                11 -> out.add(TimedSound(t, SAMPLE_ENGINE_A))
                13 -> out.add(TimedSound(t, SAMPLE_HORN_A))
                15 -> {
                    out.add(TimedSound(t, SAMPLE_HORN_B1))
                    out.add(TimedSound(t + 100, SAMPLE_HORN_B2))
                }
            }
        }
        return out
    }

    // ---- frog: at mark 9 of the car's track it hops (anim 252, then 253); when that has ended (mark 10) the close-up picture is shown and the
    // frog keeps hopping (anim 252 repeated) until the car's drive ends

    /** Time since the start of the road shot (game time) at which the frog starts its hop. */
    val frogAppearsMs: Long
        get() {
            val k = data.carTrack.marks.firstOrNull { it.first == 9 }?.second ?: return 0L
            return (carTimeAtPoint(k) - POND_MS).coerceAtLeast(0L)
        }

    private fun ticksOf(frames: IntArray, groups: Int): Int {
        val stride = 4 + groups * 4
        return (0 until frames.size / stride).sumOf { frames[it * stride] }
    }

    private val hopTicks = ticksOf(data.frog.hop, data.frog.hopGroups)
    private val restTicks = ticksOf(data.frog.rest, data.frog.restGroups) / 2

    /** Game time since the start of the road shot at which the frog's hop and rest animations are over and the close-up picture is shown. */
    val frogPictureAtMs: Long get() = frogAppearsMs + ((hopTicks + restTicks) * tickMs).toLong()

    /** The close-up picture lasts 20 ticks. */
    val frogPictureMs: Long get() = (FROG_PICTURE_TICKS * tickMs).toLong()

    /** Where the frog is, how it is turned and which pose it has [ms] after its hop started: (x, z, beta, animation 0 hop / 1 rest, progress 0..1 of that animation). */
    class FrogState(val x: Float, val z: Float, val beta: Float, val rest: Boolean, val progress: Float)

    private val hopStepTotal: Float = run {
        val stride = 4 + data.frog.hopGroups * 4
        -(0 until data.frog.hop.size / stride).sumOf { data.frog.hop[it * stride + 3] }.toFloat()
    }

    /** Distance walked in the hop animation after [ticks] ticks of it (the step of a keyframe is spread over its time). */
    private fun hopDistance(ticks: Float): Float {
        val stride = 4 + data.frog.hopGroups * 4
        var left = ticks
        var d = 0f
        for (f in 0 until data.frog.hop.size / stride) {
            val t = data.frog.hop[f * stride].toFloat()
            val step = -data.frog.hop[f * stride + 3].toFloat()
            if (left >= t) { d += step; left -= t } else { d += step * (left / t); break }
        }
        return d
    }

    fun frogAt(sinceHopMs: Float): FrogState {
        val ticks = sinceHopMs / tickMs
        var dist: Float
        var rest = false
        var progress: Float
        if (ticks < hopTicks) {
            dist = hopDistance(ticks)
            progress = ticks / hopTicks
        } else if (ticks < hopTicks + restTicks) {
            dist = hopStepTotal
            rest = true
            progress = 0f
        } else {
            val after = ticks - hopTicks - restTicks
            val cycles = (after / hopTicks).toInt()
            val phase = after - cycles * hopTicks
            dist = hopStepTotal * (1 + cycles) + hopDistance(phase)
            progress = phase / hopTicks
        }
        val a = data.frog.beta * 2.0 * Math.PI / 1024.0
        // the body walks towards its local -Z: that is (sin beta, -cos beta) in the engine's rotation
        return FrogState(data.frog.x + dist * kotlin.math.sin(a).toFloat(), data.frog.z - dist * kotlin.math.cos(a).toFloat(), data.frog.beta.toFloat(), rest, progress.coerceIn(0f, 1f))
    }

    // ---- driver
    private val walkerTicks: Int = (0 until data.carnby.frames.size / (4 + data.carnby.groups * 4)).sumOf { data.carnby.frames[it * (4 + data.carnby.groups * 4)] }

    /** Walking speed in units per millisecond from the root motion of the walk animation (sum of the Z steps over its duration). */
    val walkSpeed: Float = run {
        val stride = 4 + data.carnby.groups * 4
        val step = (0 until data.carnby.frames.size / stride).sumOf { data.carnby.frames[it * stride + 3] }
        -step.toFloat() / walkerTicks / tickMs
    }

    // the driver appears at his starting place and walks the track from there
    private val walkX = FloatArray(data.carnbyTrack.points.size + 1)
    private val walkZ = FloatArray(walkX.size)
    private val walkDist = FloatArray(walkX.size)

    init {
        walkX[0] = data.carnbyStartX.toFloat()
        walkZ[0] = data.carnbyStartZ.toFloat()
        data.carnbyTrack.points.forEachIndexed { i, p ->
            val (dx, dz) = roomShift(p.room, CARNBY_ROOM)
            walkX[i + 1] = (p.x + dx).toFloat()
            walkZ[i + 1] = (p.z + dz).toFloat()
        }
        for (i in 1 until walkX.size) walkDist[i] = walkDist[i - 1] + hypot(walkX[i] - walkX[i - 1], walkZ[i] - walkZ[i - 1])
    }

    /** The mansion camera follows the driver until mark 3 of his track (arrival at his second waypoint); the courtyard camera shows the rest. */
    private val cutIndex: Int = (data.carnbyTrack.marks.firstOrNull { it.first == 3 }?.second ?: 2).coerceIn(1, walkDist.size - 1)
    val mansionMs: Long = (walkDist[cutIndex] / walkSpeed).toLong()
    val courtyardMs: Long = ((walkDist.last() - walkDist[cutIndex]) / walkSpeed).toLong()

    /** Driver state after walking for [ms] milliseconds from his starting place. */
    fun driverAt(ms: Float): PathState {
        val d = (ms * walkSpeed).coerceIn(0f, walkDist.last())
        var i = 1
        while (i < walkDist.size - 1 && d > walkDist[i]) i++
        val seg = (walkDist[i] - walkDist[i - 1]).coerceAtLeast(0.001f)
        val t = ((d - walkDist[i - 1]) / seg).coerceIn(0f, 1f)
        val heading = headingOf(walkX[i] - walkX[i - 1], walkZ[i] - walkZ[i - 1])
        return PathState(walkX[i - 1] + (walkX[i] - walkX[i - 1]) * t, walkZ[i - 1] + (walkZ[i] - walkZ[i - 1]) * t, heading)
    }

    /** Length of the walk animation in ticks including the closing segment (progress 0..1 of [animatedVertices] with loop = true). */
    fun walkPhase(ms: Float, loopTicks: Float): Float = ((ms / tickMs) % loopTicks) / loopTicks

    /** Footstep sounds for a walk of [totalMs] starting at animation time [startMs]: sample 82 at frame 2 and 83 at frame 5 of every cycle. */
    private fun footsteps(startMs: Long, totalMs: Long): List<TimedSound> {
        val stride = 4 + data.carnby.groups * 4
        fun frameStartTicks(n: Int) = (0 until n).sumOf { data.carnby.frames[it * stride] }
        val cycleMs = (walkerTicks * tickMs).toLong()
        val out = ArrayList<TimedSound>()
        var cycle = 0L
        while (cycle < startMs + totalMs) {
            for ((frame, sample) in listOf(STEP_A_FRAME to SAMPLE_STEP_A, STEP_B_FRAME to SAMPLE_STEP_B)) {
                val t = cycle + (frameStartTicks(frame) * tickMs).toLong()
                if (t >= startMs && t < startMs + totalMs) out.add(TimedSound(t - startMs, sample))
            }
            cycle += cycleMs
        }
        return out
    }

    /** Sounds of the mansion shot: sound 89 at mark 1 (arrival at his first waypoint), footsteps. */
    val mansionSounds: List<TimedSound>
        get() {
            val k = data.carnbyTrack.marks.firstOrNull { it.first == 1 }?.second ?: 1
            val startMs = (walkDist[k.coerceIn(0, walkDist.size - 1)] / walkSpeed).toLong()
            return listOf(TimedSound(startMs, SAMPLE_WALK_START)) + footsteps(0, mansionMs)
        }

    /** Sounds of the courtyard shot: footsteps, the car stopping (mark 5 of the driver's track), the door (end of the track). */
    val courtyardSounds: List<TimedSound>
        get() {
            val k = data.carnbyTrack.marks.firstOrNull { it.first == 5 }?.second ?: 3
            val stopMs = ((walkDist[k.coerceIn(0, walkDist.size - 1)] - walkDist[cutIndex]) / walkSpeed).toLong()
            return footsteps(mansionMs, courtyardMs) + listOf(
                TimedSound(stopMs.coerceAtLeast(0L), SAMPLE_CAR_STOP),
                TimedSound((courtyardMs - 300L).coerceAtLeast(0L), SAMPLE_DOOR_A),
                TimedSound((courtyardMs - 150L).coerceAtLeast(0L), SAMPLE_DOOR_B),
            )
        }
}
