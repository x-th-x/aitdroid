import com.aitddemo.coop.LocalStateSender

// Tests of the sending rule on a plain JVM: rate limit, nothing while standing still (apart from the sign of life), the counter.
var failures = 0

fun check(ok: Boolean, text: String) {
    println((if (ok) "ok   " else "FAIL ") + text)
    if (!ok) failures++
}

fun main() {
    // a hero who walks for 2 seconds at 60 frames per second: about 15 states per second
    run {
        val s = LocalStateSender()
        var sent = 0
        val seqs = ArrayList<Int>()
        var t = 0.0
        var x = 3000
        while (t < 2000) {
            x += 13
            if (s.maybeSend(t, 0, 0, x, 0, -1500, 256, 254) { q, _ -> seqs.add(q); true }) sent++
            t += 16.67
        }
        check(sent in 28..32, "walking: $sent states in 2 s (about 15 per second)")
        check(seqs == (1..seqs.size).toList(), "walking: the counter grows by one with every state")
    }
    // standing still: the first state goes at once, then only a sign of life each second
    run {
        val s = LocalStateSender()
        var sent = 0
        var t = 0.0
        while (t < 5000) {
            if (s.maybeSend(t, 0, 0, 3000, 0, -1500, 256, 4) { _, _ -> true }) sent++
            t += 16.67
        }
        check(sent in 5..6, "standing: $sent states in 5 s (the first one and a sign of life each second)")
    }
    // a change is sent at the next chance, not held back for the sign of life
    run {
        val s = LocalStateSender()
        s.maybeSend(0.0, 0, 0, 3000, 0, -1500, 256, 4) { _, _ -> true }
        check(!s.maybeSend(30.0, 0, 0, 3000, 0, -1500, 256, 4) { _, _ -> true }, "change: nothing within 66 ms")
        check(!s.maybeSend(200.0, 0, 0, 3000, 0, -1500, 256, 4) { _, _ -> true }, "change: nothing when nothing changed")
        check(s.maybeSend(210.0, 0, 0, 3000, 0, -1500, 300, 4) { _, _ -> true }, "change: a turn is sent at once")
        check(s.maybeSend(300.0, 0, 0, 3000, 0, -1500, 300, 254) { _, _ -> true }, "change: a new animation is sent at once")
    }
    // a frame that could not be queued does not use up the counter
    run {
        val s = LocalStateSender()
        var got = -1
        check(!s.maybeSend(0.0, 0, 0, 1, 0, 1, 1, 4) { _, _ -> false }, "no connection: nothing sent")
        s.maybeSend(100.0, 0, 0, 1, 0, 1, 1, 4) { q, _ -> got = q; true }
        check(got == 1, "no connection: the counter starts at 1 when it works ($got)")
    }
    println(if (failures == 0) "ALL OK" else "$failures FAILED")
    System.exit(if (failures == 0) 0 else 1)
}
