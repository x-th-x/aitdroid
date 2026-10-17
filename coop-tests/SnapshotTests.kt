import com.aitddemo.coop.RemoteSmoother
import com.aitddemo.coop.RemoteSnapshots
import java.util.Random

// Tests of the snapshot buffer and the smoother on a plain JVM: smoothness under jitter, a stall and its catch-up, reordering, headings, rooms.
var failures = 0

fun check(ok: Boolean, text: String) {
    println((if (ok) "ok   " else "FAIL ") + text)
    if (!ok) failures++
}

class Arrival(val at: Double, val seq: Int, val sent: Double)

fun xAt(sent: Double): Int = (3000 + 0.8 * (sent - 1000)).toInt()

fun main() {
    val rnd = Random(42)

    // 1. a hero walks along x at 800 units/s; the other phone sends 15 states per second; the network delays them by 40 ms plus a jitter of 0..80 ms
    run {
        val snaps = RemoteSnapshots()
        val smoother = RemoteSmoother()
        val arrivals = ArrayList<Arrival>()
        var seq = 0
        var sent = 1000.0
        while (sent < 6000) { arrivals.add(Arrival(sent + 40 + rnd.nextDouble() * 80, ++seq, sent)); sent += 66.6 }
        arrivals.sortBy { it.at }
        var ai = 0
        var prev = Double.NaN
        var maxStep = 0.0
        var backwards = 0
        var held = 0
        var frames = 0
        var t = 1000.0
        while (t < 6100) {
            while (ai < arrivals.size && arrivals[ai].at <= t) { val a = arrivals[ai++]; snaps.add(a.seq, a.sent, a.at, 0, 0, xAt(a.sent), 0, -1500, 256, 254) }
            val s = snaps.sample(t)
            if (s != null) {
                val shown = smoother.step(s, 16.67)
                if (!prev.isNaN()) {
                    val d = shown.x - prev
                    maxStep = maxOf(maxStep, Math.abs(d))
                    if (d < -1) backwards++
                }
                prev = shown.x.toDouble()
                frames++
                if (s.held) held++
            }
            t += 16.67
        }
        check(maxStep < 40, "jitter: the largest step per frame is $maxStep units (a steady walk is 13)")
        check(backwards == 0, "jitter: the hero never steps backwards ($backwards)")
        check(held < frames / 10, "jitter: the hero rarely waits for news ($held of $frames frames)")
        val last = snaps.sample(6100.0)!!
        // the sender is at 6060 on its own clock when the receiver reads 6100 (40 ms of network); the hero is shown the configured delay behind that
        val lag = (xAt(6060.0) - last.x) / 0.8
        check(lag in 100.0..160.0, "jitter: the hero is shown $lag ms behind the sender (the delay is 120)")
    }

    // 2. a stall of 700 ms: the hero waits, then catches up in a quick walk, not a jump
    run {
        val snaps = RemoteSnapshots()
        val smoother = RemoteSmoother()
        var seq = 0
        var sent = 1000.0
        var t = 1000.0
        var prev = Double.NaN
        var maxStep = 0.0
        var heldFrames = 0
        var silent = 0.0
        val backlog = ArrayList<Pair<Int, Double>>()
        while (t < 5000) {
            // the stream is cut from 2000 to 2750; what the sender sent meanwhile arrives at once at 2750
            while (sent + 50 <= t) {
                seq++
                if (sent < 2000 || t >= 2750) snaps.add(seq, sent, t, 0, 0, xAt(sent), 0, -1500, 256, 254) else backlog.add(seq to sent)
                sent += 66.6
            }
            if (t >= 2750 && backlog.isNotEmpty()) {
                for ((q, s) in backlog) snaps.add(q, s, t, 0, 0, xAt(s), 0, -1500, 256, 254)
                backlog.clear()
            }
            val s = snaps.sample(t)
            if (s != null) {
                if (s.held) heldFrames++
                silent = maxOf(silent, s.silentMs)
                val shown = smoother.step(s, 16.67)
                if (!prev.isNaN()) maxStep = maxOf(maxStep, Math.abs(shown.x - prev))
                prev = shown.x.toDouble()
            }
            t += 16.67
        }
        check(heldFrames > 20, "stall: the hero waits while nothing comes ($heldFrames frames held)")
        check(silent > 500, "stall: the silence is reported ($silent ms)")
        val limit = 3500 * 16.67 / 1000 + 1
        check(maxStep <= limit, "stall: the catch-up is a quick walk, the largest step is $maxStep units (limit $limit)")
    }

    // 3. old and repeated states are dropped
    run {
        val snaps = RemoteSnapshots()
        check(snaps.add(5, 1000.0, 1050.0, 0, 0, 0, 0, 0, 0, 4), "reorder: the first state is taken")
        check(!snaps.add(5, 1000.0, 1060.0, 0, 0, 0, 0, 0, 0, 4), "reorder: a repeated seq is dropped")
        check(!snaps.add(3, 900.0, 1070.0, 0, 0, 0, 0, 0, 0, 4), "reorder: an older seq is dropped")
        check(snaps.add(6, 1066.0, 1120.0, 0, 0, 100, 0, 0, 0, 4), "reorder: a newer one is taken")
    }

    // 4. headings take the short way round
    run {
        val snaps = RemoteSnapshots(delayMs = 0.0)
        snaps.add(1, 1000.0, 1000.0, 0, 0, 0, 0, 0, 1000, 4)
        snaps.add(2, 1100.0, 1100.0, 0, 0, 0, 0, 0, 40, 4)
        val mid = snaps.sample(1050.0)!!
        check(mid.beta > 1010 || mid.beta < 14, "angle: from 1000 to 40 the short way passes 1020 or so, got ${mid.beta}")
    }

    // 5. a change of room is not blended and puts the smoothed hero at the target
    run {
        val snaps = RemoteSnapshots(delayMs = 0.0)
        val smoother = RemoteSmoother()
        snaps.add(1, 1000.0, 1000.0, 0, 0, 100, 0, 100, 0, 4)
        snaps.add(2, 1100.0, 1100.0, 1, 6, 9000, 0, 9000, 0, 4)
        val before = snaps.sample(1050.0)!!
        check(before.room == 0 && before.x == 100, "rooms: before the change the earlier state counts (room ${before.room}, x ${before.x})")
        smoother.step(before, 16.0)
        val after = snaps.sample(1200.0)!!
        val shown = smoother.step(after, 16.0)
        check(shown.x == 9000 && shown.room == 6, "rooms: after the change the hero is put at the target (x ${shown.x})")
    }

    // 6. nothing yet
    check(RemoteSnapshots().sample(1000.0) == null, "empty: no sample before the first state")

    println(if (failures == 0) "ALL OK" else "$failures FAILED")
    System.exit(if (failures == 0) 0 else 1)
}
