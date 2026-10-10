package com.aitddemo.game

import kotlin.math.cos
import kotlin.math.sin

/**
 * Poses an articulated body of the 1992 format from the state of an animation (the original's AnimNuage): every bone group, in the file's
 * processing order, is rotated (with its child groups), moved or zoomed by its state; finally each group moves to its pivot vertex.
 *
 * The animation's slot i drives body group i (group 0 is only the root: the figure's heading is applied by the caller); body groups beyond the animation keep
 * the rest state stored in the body file.
 */
object BodyPose {
    /** [rest]: unposed vertices (x, y, z each); [groupsRaw]: 12 values per body group (start, count, base vertex, parent, own number, rest type, rest x, y, z); [state]: the animation's group states. */
    fun pose(rest: FloatArray, groupsRaw: IntArray, order: IntArray, state: Array<GroupState>): FloatArray {
        val groupCount = groupsRaw.size / 12
        val start = IntArray(groupCount) { groupsRaw[it * 12] }
        val count = IntArray(groupCount) { groupsRaw[it * 12 + 1] }
        val base = IntArray(groupCount) { groupsRaw[it * 12 + 2] }
        val parent = IntArray(groupCount) { groupsRaw[it * 12 + 3] }
        val own = IntArray(groupCount) { groupsRaw[it * 12 + 4] }
        val type = IntArray(groupCount)
        val dx = FloatArray(groupCount)
        val dy = FloatArray(groupCount)
        val dz = FloatArray(groupCount)
        for (g in 0 until groupCount) {
            if (g in 1 until state.size) {
                type[g] = state[g].type
                dx[g] = state[g].x.toFloat(); dy[g] = state[g].y.toFloat(); dz[g] = state[g].z.toFloat()
            } else {
                type[g] = groupsRaw[g * 12 + 5]
                dx[g] = groupsRaw[g * 12 + 6].toFloat(); dy[g] = groupsRaw[g * 12 + 7].toFloat(); dz[g] = groupsRaw[g * 12 + 8].toFloat()
            }
        }
        val n = rest.size / 3
        val px = FloatArray(n) { rest[it * 3] }
        val py = FloatArray(n) { rest[it * 3 + 1] }
        val pz = FloatArray(n) { rest[it * 3 + 2] }

        // the engine's "cosine table" is a sine table: (cosLike, sinLike) = (sin theta, cos theta)
        fun rotateGroup(g: Int, ax: Float, ay: Float, az: Float) {
            fun cs(a: Float): Pair<Float, Float> {
                val theta = (a.toInt() and 0x3FF) * (2f * Math.PI.toFloat() / 1024f)
                return sin(theta) to cos(theta)
            }
            val hasX = ax != 0f
            val hasY = ay != 0f
            val hasZ = az != 0f
            val (xc, xs) = if (hasX) cs(ax) else 0f to 0f
            val (yc, ys) = if (hasY) cs(ay) else 0f to 0f
            val (zc, zs) = if (hasZ) cs(az) else 0f to 0f
            for (i in 0 until count[g]) {
                val k = start[g] + i
                var x = px[k]
                var y = py[k]
                var z = pz[k]
                if (hasY) { val tx = x; val tz = z; x = tx * ys - tz * yc; z = tx * yc + tz * ys }
                if (hasX) { val ty = y; val tz = z; y = ty * xs - tz * xc; z = ty * xc + tz * xs }
                if (hasZ) { val tx = x; val ty = y; x = tx * zs - ty * zc; y = tx * zc + ty * zs }
                px[k] = x; py[k] = y; pz[k] = z
            }
            for (j in g + 1 until groupCount) if (parent[j] == own[g]) rotateGroup(j, ax, ay, az)
        }

        for (g in order) {
            val x = dx[g]; val y = dy[g]; val z = dz[g]
            if (x == 0f && y == 0f && z == 0f) continue
            when (type[g]) {
                0 -> rotateGroup(g, x, y, z)
                1 -> for (i in 0 until count[g]) { px[start[g] + i] += x; py[start[g] + i] += y; pz[start[g] + i] += z }
                2 -> for (i in 0 until count[g]) {
                    px[start[g] + i] = px[start[g] + i] * (x + 256f) / 256f
                    py[start[g] + i] = py[start[g] + i] * (y + 256f) / 256f
                    pz[start[g] + i] = pz[start[g] + i] * (z + 256f) / 256f
                }
            }
        }
        for (g in 0 until groupCount) {
            val bx = px[base[g]]; val by = py[base[g]]; val bz = pz[base[g]]
            for (i in 0 until count[g]) { px[start[g] + i] += bx; py[start[g] + i] += by; pz[start[g] + i] += bz }
        }
        val out = FloatArray(n * 3)
        for (i in 0 until n) { out[i * 3] = px[i]; out[i * 3 + 1] = py[i]; out[i * 3 + 2] = pz[i] }
        return out
    }
}
