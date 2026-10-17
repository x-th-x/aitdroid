package com.aitddemo.coop

/**
 * The buffer that shows the other player's hero smoothly. The states of the other phone arrive in bursts and with jitter; showing each at once would make the hero jump.
 * So the hero is shown a little in the past ([delayMs], about 120 ms): the buffer holds the last second of states, and for the moment "now minus the delay" the position is
 * blended between the two states on either side of it. A late state then costs nothing as long as it arrives within the delay.
 *
 * Time. Every state carries the sender's own clock (its milliseconds, only differences count) and gets the receiver's clock when it arrives. The difference of the two is the
 * network delay plus the (unknown) difference of the two clocks; the smallest of the last differences is the best estimate of the clock offset (the state that was least delayed),
 * and "now" on the sender's clock is then the receiver's clock minus that offset.
 *
 * No state yet or none new: the hero stays where the last state put him (no extrapolation: a guess that runs away is worse than a hero who waits); [Sample.silentMs] tells
 * how long nothing has come. When states come back the blend simply continues from where the hero is shown (the buffer is ordered by the sender's counter).
 *
 * Thread: [add] is called on the network thread, [sample] on the game thread; both take the lock for a few microseconds, never during any blocking work.
 * No Android classes here, so that it can be tested on a plain JVM.
 */
class RemoteSnapshots(private val delayMs: Double = DEFAULT_DELAY_MS) {
    class Snap(val seq: Int, val sentMs: Double, val recvMs: Double, val stage: Int, val room: Int, val x: Int, val y: Int, val z: Int, val beta: Int, val anim: Int)

    /** What to show: position, heading (0..1023), animation; [held] is true when the newest state is older than the render time, i.e. the hero waits for news. */
    class Sample(val stage: Int, val room: Int, val x: Int, val y: Int, val z: Int, val beta: Int, val anim: Int, val held: Boolean, val silentMs: Double)

    private val lock = Any()
    private val buffer = ArrayList<Snap>()
    private val offsets = ArrayDeque<Double>()
    private var lastSeq = -1
    private var lastRecv = 0.0

    /** A state arrived. Old or repeated ones (by the sender's counter) are dropped. Returns false for those. */
    fun add(seq: Int, sentMs: Double, recvMs: Double, stage: Int, room: Int, x: Int, y: Int, z: Int, beta: Int, anim: Int): Boolean = synchronized(lock) {
        if (seq <= lastSeq) return false
        lastSeq = seq
        lastRecv = recvMs
        buffer.add(Snap(seq, sentMs, recvMs, stage, room, x, y, z, beta, anim))
        if (buffer.size > CAPACITY) buffer.removeAt(0)
        offsets.addLast(recvMs - sentMs)
        if (offsets.size > OFFSET_WINDOW) offsets.removeFirst()
        true
    }

    /** Forgets everything (a new game, a new room). */
    fun clear() = synchronized(lock) {
        buffer.clear()
        offsets.clear()
        lastSeq = -1
    }

    val isEmpty: Boolean get() = synchronized(lock) { buffer.isEmpty() }

    /** The hero as he is to be shown at the receiver's clock [nowMs], or null when no state has come yet. */
    fun sample(nowMs: Double): Sample? = synchronized(lock) {
        if (buffer.isEmpty()) return null
        val offset = offsets.minOrNull() ?: 0.0
        val renderAt = nowMs - offset - delayMs // on the sender's clock
        val silent = nowMs - lastRecv
        // drop what is far behind, but keep one state before the render time to blend from
        while (buffer.size > 2 && buffer[1].sentMs < renderAt - KEEP_MS) buffer.removeAt(0)
        val first = buffer.first()
        val last = buffer.last()
        if (renderAt <= first.sentMs) return Sample(first.stage, first.room, first.x, first.y, first.z, first.beta, first.anim, false, silent)
        if (renderAt >= last.sentMs) return Sample(last.stage, last.room, last.x, last.y, last.z, last.beta, last.anim, true, silent)
        var i = 0
        while (i + 1 < buffer.size && buffer[i + 1].sentMs <= renderAt) i++
        val a = buffer[i]
        val b = buffer[i + 1]
        // another room: no blend between two rooms (the coordinates belong to different frames); the earlier state counts until the later one is due
        if (a.stage != b.stage || a.room != b.room) return Sample(a.stage, a.room, a.x, a.y, a.z, a.beta, a.anim, false, silent)
        val span = b.sentMs - a.sentMs
        val f = if (span <= 0.0) 1.0 else ((renderAt - a.sentMs) / span).coerceIn(0.0, 1.0)
        return Sample(
            a.stage, a.room,
            lerp(a.x, b.x, f), lerp(a.y, b.y, f), lerp(a.z, b.z, f),
            lerpAngle(a.beta, b.beta, f),
            a.anim, // the animation changes when its state is due
            false, silent,
        )
    }

    private fun lerp(a: Int, b: Int, f: Double): Int = Math.round(a + (b - a) * f).toInt()

    /** Between two headings (1024 = a full turn) on the short way round. */
    private fun lerpAngle(a: Int, b: Int, f: Double): Int {
        var d = ((b - a) and 1023)
        if (d > 512) d -= 1024
        return Math.floorMod(Math.round(a + d * f).toInt(), 1024)
    }

    companion object {
        const val DEFAULT_DELAY_MS = 120.0
        private const val CAPACITY = 64
        private const val OFFSET_WINDOW = 64
        private const val KEEP_MS = 400.0
    }
}

/**
 * Between the buffer and the screen: the hero is shown at a place that follows the buffer's target at a limited speed, so that a catch-up after a stall (the target jumped ahead
 * while no state came) is a quick walk and not a jump. A hero who is further away than [snapDistance] (a new room, a long stall) is put at the target at once.
 */
class RemoteSmoother(private val maxSpeed: Double = 3500.0, private val maxTurnPerSecond: Double = 2400.0, private val snapDistance: Double = 8000.0) {
    private var x = 0.0
    private var y = 0.0
    private var z = 0.0
    private var beta = 0.0
    private var room = -1
    private var started = false

    /** One step of [dtMs] towards [target]; returns the place to show. */
    fun step(target: RemoteSnapshots.Sample, dtMs: Double): RemoteSnapshots.Sample {
        val key = target.stage * 1000 + target.room
        val dx = target.x - x
        val dy = target.y - y
        val dz = target.z - z
        val dist = Math.sqrt(dx * dx + dz * dz)
        if (!started || key != room || dist > snapDistance) {
            x = target.x.toDouble(); y = target.y.toDouble(); z = target.z.toDouble(); beta = target.beta.toDouble()
            room = key
            started = true
        } else {
            val reach = maxSpeed * dtMs / 1000.0
            if (dist <= reach) { x = target.x.toDouble(); z = target.z.toDouble() } else { x += dx / dist * reach; z += dz / dist * reach }
            y = if (Math.abs(dy) <= reach) target.y.toDouble() else y + Math.signum(dy) * reach
            var d = ((target.beta - Math.round(beta).toInt()) and 1023).toDouble()
            if (d > 512) d -= 1024
            val turn = maxTurnPerSecond * dtMs / 1000.0
            beta = Math.floorMod(Math.round(beta + d.coerceIn(-turn, turn)).toInt(), 1024).toDouble()
        }
        return RemoteSnapshots.Sample(target.stage, target.room, Math.round(x).toInt(), Math.round(y).toInt(), Math.round(z).toInt(), Math.round(beta).toInt(), target.anim, target.held, target.silentMs)
    }

    fun reset() { started = false }
}
