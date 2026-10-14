package com.aitddemo

import com.aitddemo.game.BodyAsset
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * "Lost Place" (NOT in the original game; a prototype on this branch): the things of the attic as ruins. The bodies of the game are meshes of coloured polygons; here a copy of a body is
 * made that has decayed - the wardrobe has caved in (it leans and sags, panels have fallen out and lie on the floor), the chest is smashed (the lid and boards are broken out and scattered),
 * the stool is broken, the rocking horse and the lamp are rotten (holes, grey and mouldy colours). The copies are made at runtime from the user's own bodies.
 */
object LostPlaceModels {
    /** A decayed body: its own vertices when it is a static body (null = keep the vertices of the game, the body is animated), and its polygons. */
    class Worn(val vertices: FloatArray?, val triangles: IntArray)

    private val cache = HashMap<Int, Worn?>()

    @Synchronized
    fun worn(asset: BodyAsset): Worn? = cache.getOrPut(asset.number) {
        try {
            when (asset.number) {
                2 -> ruin(asset, seed = 2, lean = 0.20f, sag = 0.10f, holes = 0.5f, scatter = 7)
                0 -> ruin(asset, seed = 0, lean = 0.06f, sag = 0.05f, holes = 0.6f, scatter = 8)
                8 -> ruin(asset, seed = 8, lean = 0.22f, sag = 0.12f, holes = 0.35f, scatter = 3)
                4, 9, 1, 3, 10 -> rot(asset)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------------------------------------------------------ polygons

    /** The polygons of a mesh: the triangles of a body come as fans (same first corner, same colour, each continuing the last); returns the index of the first triangle and the count. */
    private fun polygons(tr: IntArray): List<IntArray> {
        val out = ArrayList<IntArray>()
        val total = tr.size / 4
        var k = 0
        while (k < total) {
            val first = tr[k * 4]; val col = tr[k * 4 + 3]
            var last = tr[k * 4 + 2]
            var j = k + 1
            while (j < total && tr[j * 4] == first && tr[j * 4 + 1] == last && tr[j * 4 + 3] == col) { last = tr[j * 4 + 2]; j++ }
            out.add(intArrayOf(k, j - k))
            k = j
        }
        return out
    }

    private fun hash(n: Int): Float {
        var x = n * 374761393 + 668265263
        x = (x xor (x ushr 13)) * 1274126177
        x = x xor (x ushr 16)
        return (x and 0xFFFF) / 65535f
    }

    /** A colour as it is when it has rotted: darker, grey, with brown and the green of mould in places. */
    private fun rotColor(c: Int, seed: Int): Int {
        val r = ((c shr 16) and 0xFF).toFloat(); val g = ((c shr 8) and 0xFF).toFloat(); val b = (c and 0xFF).toFloat()
        val lum = 0.3f * r + 0.59f * g + 0.11f * b
        val h = hash(seed)
        var nr = lum + (r - lum) * 0.3f
        var ng = lum + (g - lum) * 0.3f
        var nb = lum + (b - lum) * 0.3f
        val dark = 0.5f + 0.34f * hash(seed * 7 + 3)
        nr *= dark; ng *= dark; nb *= dark
        if (h > 0.78f) { nr *= 0.8f; ng *= 1.05f; nb *= 0.78f } // mould
        else if (h < 0.18f) { nr *= 0.7f; ng *= 0.68f; nb *= 0.66f } // water damage, almost black
        return (c and 0xFF000000.toInt()) or (nr.toInt().coerceIn(0, 255) shl 16) or (ng.toInt().coerceIn(0, 255) shl 8) or nb.toInt().coerceIn(0, 255)
    }

    // ------------------------------------------------------------------------------------------------------------------ rot: only the polygons change

    /** An animated body or a small one: the colours rot and some polygons are missing (holes in the wood). */
    private fun rot(a: BodyAsset): Worn {
        val tr = a.triangles
        val polys = polygons(tr)
        val out = ArrayList<Int>()
        for ((pi, p) in polys.withIndex()) {
            if (hash(a.number * 131 + pi) < 0.2f) continue // a hole
            val color = rotColor(tr[p[0] * 4 + 3], a.number * 53 + pi)
            for (t in p[0] until p[0] + p[1]) { out.add(tr[t * 4]); out.add(tr[t * 4 + 1]); out.add(tr[t * 4 + 2]); out.add(color) }
        }
        return Worn(null, out.toIntArray())
    }

    // ------------------------------------------------------------------------------------------------------------------ ruin: a static body that has caved in

    private fun ruin(a: BodyAsset, seed: Int, lean: Float, sag: Float, holes: Float, scatter: Int): Worn {
        val v = a.vertices.copyOf()
        val n = v.size / 3
        var yTop = Float.MAX_VALUE; var yBottom = -Float.MAX_VALUE
        var xMin = Float.MAX_VALUE; var xMax = -Float.MAX_VALUE; var zMin = Float.MAX_VALUE; var zMax = -Float.MAX_VALUE
        for (i in 0 until n) {
            yTop = min(yTop, v[i * 3 + 1]); yBottom = max(yBottom, v[i * 3 + 1])
            xMin = min(xMin, v[i * 3]); xMax = max(xMax, v[i * 3]); zMin = min(zMin, v[i * 3 + 2]); zMax = max(zMax, v[i * 3 + 2])
        }
        val height = (yBottom - yTop).coerceAtLeast(1f)
        val width = (xMax - xMin).coerceAtLeast(1f)
        val depth = (zMax - zMin).coerceAtLeast(1f)
        // the body leans and sags: the higher a point, the more it moves to the side and down; every corner is a little out of true
        for (i in 0 until n) {
            val h = ((yBottom - v[i * 3 + 1]) / height).coerceIn(0f, 1f)
            v[i * 3] += lean * height * h * h + (hash(seed * 31 + i) - 0.5f) * 0.04f * width * h
            v[i * 3 + 1] += sag * height * h * h + (hash(seed * 37 + i) - 0.5f) * 0.03f * height * h
            v[i * 3 + 2] += (hash(seed * 41 + i) - 0.5f) * 0.05f * depth * h
        }
        val tr = a.triangles
        val polys = polygons(tr)
        val outV = ArrayList<Float>(v.toList())
        val outT = ArrayList<Int>()
        val removed = ArrayList<Int>()
        for ((pi, p) in polys.withIndex()) {
            // the mean height of the polygon
            var hSum = 0f; var cnt = 0
            val ids = HashSet<Int>()
            for (t in p[0] until p[0] + p[1]) { ids.add(tr[t * 4]); ids.add(tr[t * 4 + 1]); ids.add(tr[t * 4 + 2]) }
            for (id in ids) { hSum += (yBottom - a.vertices[id * 3 + 1]) / height; cnt++ }
            val h = hSum / max(cnt, 1)
            val gone = hash(seed * 101 + pi) < holes * (0.25f + 0.9f * h) // the higher, the more has fallen out
            if (gone) { removed.add(pi); continue }
            val color = rotColor(tr[p[0] * 4 + 3], seed * 59 + pi)
            for (t in p[0] until p[0] + p[1]) { outT.add(tr[t * 4]); outT.add(tr[t * 4 + 1]); outT.add(tr[t * 4 + 2]); outT.add(color) }
        }
        // what fell out lies on the floor in front of the wreck: the polygons laid flat, turned at random, scattered
        var placed = 0
        for (pi in removed) {
            if (placed >= scatter) break
            val p = polys[pi]
            val ids = LinkedHashSet<Int>()
            for (t in p[0] until p[0] + p[1]) { ids.add(tr[t * 4]); ids.add(tr[t * 4 + 1]); ids.add(tr[t * 4 + 2]) }
            if (ids.size < 3) continue
            var cx = 0f; var cy = 0f; var cz = 0f
            for (id in ids) { cx += a.vertices[id * 3]; cy += a.vertices[id * 3 + 1]; cz += a.vertices[id * 3 + 2] }
            cx /= ids.size; cy /= ids.size; cz /= ids.size
            // the two longest directions of the polygon become the plane of the floor
            val rel = ids.map { floatArrayOf(a.vertices[it * 3] - cx, a.vertices[it * 3 + 1] - cy, a.vertices[it * 3 + 2] - cz) }
            val ext = FloatArray(3) { ax -> (rel.maxOf { it[ax] } - rel.minOf { it[ax] }) }
            val order = (0..2).sortedByDescending { ext[it] }
            val ang = hash(seed * 211 + pi) * 6.2832f
            val dx = (hash(seed * 223 + pi) - 0.5f) * width * 2.2f
            val dz = -(0.35f + hash(seed * 227 + pi) * 1.1f) * depth - depth * 0.5f
            val map = HashMap<Int, Int>()
            for ((k, id) in ids.withIndex()) {
                val r = rel[k]
                val px = r[order[0]]; val pz = r[order[1]]; val py = r[order[2]] * 0.25f
                val rx = px * cos(ang) - pz * sin(ang); val rz = px * sin(ang) + pz * cos(ang)
                outV.add((xMin + xMax) / 2f + dx + rx)
                outV.add(yBottom - 12f + py)
                outV.add((zMin + zMax) / 2f + dz + rz)
                map[id] = outV.size / 3 - 1
            }
            val color = rotColor(tr[p[0] * 4 + 3], seed * 67 + pi)
            for (t in p[0] until p[0] + p[1]) { outT.add(map.getValue(tr[t * 4])); outT.add(map.getValue(tr[t * 4 + 1])); outT.add(map.getValue(tr[t * 4 + 2])); outT.add(color) }
            placed++
        }
        return Worn(outV.toFloatArray(), outT.toIntArray())
    }
}
