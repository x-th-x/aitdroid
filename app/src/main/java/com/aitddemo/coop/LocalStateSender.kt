package com.aitddemo.coop

/**
 * Decides when the state of the local hero goes to the server. At most [maxPerSecond] states per second (default 15), and only when something has changed (place, heading,
 * animation); a hero who stands still sends nothing, except a small sign of life every [keepAliveMs] so that the other phone and the server know that he is still there.
 * The first state goes at once. The counter grows with every state that was really sent, so the receiver can tell old from new. No Android classes: testable on a plain JVM.
 */
class LocalStateSender(maxPerSecond: Double = 15.0, private val keepAliveMs: Double = 1000.0) {
    private val minIntervalMs = 1000.0 / maxPerSecond
    private var seq = 0
    private var lastSentAt = -1e12
    private var last: IntArray? = null

    /**
     * Called every frame with the local hero's values; [send] gets (seq, timeMs, ...) and returns whether the frame was queued. Returns true when a state was sent.
     * [nowMs] is a steady clock in milliseconds (only differences count; the receiver uses it as the sender's clock).
     */
    fun maybeSend(nowMs: Double, stage: Int, room: Int, x: Int, y: Int, z: Int, beta: Int, anim: Int, send: (Int, Double) -> Boolean): Boolean {
        val since = nowMs - lastSentAt
        if (since < minIntervalMs) return false
        val now = intArrayOf(stage, room, x, y, z, beta, anim)
        val changed = last?.contentEquals(now) != true
        if (!changed && since < keepAliveMs) return false
        if (!send(seq + 1, nowMs)) return false
        seq++
        lastSentAt = nowMs
        last = now
        return true
    }

    /** A new game or a new connection: the counter starts again, and the first state goes at once. */
    fun reset() {
        seq = 0
        lastSentAt = -1e12
        last = null
    }
}
