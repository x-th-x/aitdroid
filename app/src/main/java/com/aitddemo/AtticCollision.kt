package com.aitddemo

import kotlin.math.abs

/**
 * Wall collision of the original engine (ZV box against hard colliders, slide along the wall) - a port of the
 * collision step of GereAnim and of `collide()` that was already verified in the Time Gate port.
 *
 * Coordinates are engine units. A ZV is an int array (x1, x2, y1, y2, z1, z2). The player's ZV is a square of
 * [PLAYER_HALF] around its position (the original does the same: DoCarreZV squares the body box).
 */
object AtticCollision {
    /** Half width of the player's square ZV and its height; read from the player body (LISTBODY body 12) when the room is loaded. */
    @Volatile
    var PLAYER_HALF = 311
        private set
    private var playerY1 = -1777

    fun configure(half: Int, height: Int) {
        PLAYER_HALF = half
        playerY1 = -height
    }

    fun playerZv(x: Int, z: Int): IntArray = intArrayOf(x - PLAYER_HALF, x + PLAYER_HALF, playerY1, 0, z - PLAYER_HALF, z + PLAYER_HALF)

    private fun overlaps(zv: IntArray, h: IntArray): Boolean =
        h[0] < zv[1] && zv[0] < h[1] && h[2] < zv[3] && zv[2] < h[3] && h[4] < zv[5] && zv[4] < h[5]

    /** Does the player's box at (x, z) touch any hard collider (types 1 = wall, 9 / 0 = furniture)? */
    fun blocked(x: Int, z: Int): Boolean {
        val zv = playerZv(x, z)
        return boxes().any { overlaps(zv, it) }
    }

    /**
     * Moves the player by (stepX, stepZ) from (x, z) and returns the step that is actually possible: free movement,
     * sliding along a wall, or (0, 0) in a corner.
     */
    fun move(x: Int, z: Int, stepX: Int, stepZ: Int): IntArray {
        val old = playerZv(x, z)
        val local = playerZv(x + stepX, z + stepZ)
        var sx = stepX
        var sz = stepZ
        for (h in boxes()) {
            if (!overlaps(local, h)) continue
            val r = collide(old, local, h, sx, sz)
            local[0] += r[0] - sx
            local[1] += r[0] - sx
            local[4] += r[1] - sz
            local[5] += r[1] - sz
            sx = r[0]
            sz = r[1]
        }
        return intArrayOf(sx, sz)
    }

    private var boxList: List<IntArray>? = null

    /** Forget the box list; called when new room data is installed. */
    fun reset() {
        boxList = null
    }

    private fun boxes(): List<IntArray> = boxList ?: run {
        val d = AtticData.HARDCOLS
        val s = AtticData.HARDCOL_STRIDE
        List(d.size / s) { i -> IntArray(6) { d[i * s + it] } }.also { boxList = it }
    }

    /** Port of `Actor.collide` (Time Gate): which side of the box the player came from decides which axis is stopped. */
    private fun collide(old: IntArray, animated: IntArray, fix: IntArray, stepX: Int, stepZ: Int): IntArray {
        var hx = stepX
        var hz = stepZ
        val fx1 = fix[0]
        val fx2 = fix[1]
        val fz1 = fix[4]
        val fz2 = fix[5]
        fun glisser(flag: Int) {
            when (flag) {
                1, 2 -> hz = 0
                4, 8 -> hx = 0
            }
        }
        var oldPos = if (old[1] > fx1) { if (fx2 <= old[0]) 8 else 0 } else 4
        oldPos = oldPos or if (old[5] > fz1) { if (old[4] >= fz2) 2 else 0 } else 1
        val oldType: Int
        if (oldPos == 5 || oldPos == 9 || oldPos == 6 || oldPos == 10) {
            oldType = 2
        } else if (oldPos == 0) {
            return intArrayOf(0, 0)
        } else {
            oldType = 1
        }
        val halfX = (animated[0] + animated[1]) / 2
        val halfZ = (animated[4] + animated[5]) / 2
        var pos = if (fx1 > halfX) 4 else if (fx2 < halfX) 0 else 8
        pos = pos or if (fz1 > halfZ) 1 else if (fz2 < halfZ) 0 else 2
        val type = if (pos == 5 || pos == 9 || pos == 6 || pos == 10) 2 else if (pos == 0) 0 else 1

        if (oldType == 1) {
            glisser(oldPos)
        } else if (type == 1 && (pos and oldPos) != 0) {
            glisser(pos)
        } else if (pos == oldPos || pos + oldPos == 15) {
            val xm = abs(animated[0] - old[0])
            val zm = abs(animated[4] - old[4])
            if (xm > zm) hz = 0 else hx = 0
        } else if (type == 0 || (type == 1 && (pos and oldPos) == 0)) {
            hz = 0
            hx = 0
        } else {
            glisser(oldPos and pos)
        }
        return intArrayOf(hx, hz)
    }
}
