package com.aitddemo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.aitddemo.game.BodyAsset
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The two arms that hold the map, made of the game's own polygons of the character's body (the jacket sleeve and the claw that is the game's hand, in their own flat colours), posed in
 * front of the camera and drawn like the game draws its bodies (the perspective of its 3D routine, polygons that look away are left out, far to near, flat colours without outlines) into
 * a bitmap of 640 x 400 (two pixels for each pixel of the 320 x 200 screen) without smoothing. A second copy of the sleeve continues the arm below the picture, so that no open end shows.
 * They breathe: the arms rise and sink a little and turn a little, a little out of step with each other.
 * The groups of the body: 9 and 10 are the right sleeve and hand, 12 and 13 the left ones.
 */
class HeldHands(private val body: BodyAsset) {
    val bitmap: Bitmap = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val paint = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL }

    /** A polygon with corners (x, y, z) relative to the wrist of its arm. */
    private class Poly(val pts: FloatArray, val color: Int)
    private class Item(val xs: FloatArray, val ys: FloatArray, val depth: Float, val color: Int)
    private class GamePoly(val ids: IntArray, val color: Int)

    private val arms: List<List<Poly>>

    init {
        val groups = body.parts?.groups
        val all = polygons()
        val list = ArrayList<List<Poly>>()
        // the left arm first (it lies on the left of the picture), then the right one
        for ((sleeve, hand) in listOf(12 to 13, 9 to 10)) {
            if (groups == null || hand * 12 + 1 >= groups.size) { list.add(emptyList()); continue }
            val sleeveFrom = groups[sleeve * 12]; val sleeveTo = sleeveFrom + groups[sleeve * 12 + 1]
            val handFrom = groups[hand * 12]; val handTo = handFrom + groups[hand * 12 + 1]
            // the wrist: the top of the game's hand
            var wx = 0f; var wy = Float.MAX_VALUE; var wz = 0f; var n = 0
            for (v in handFrom until handTo) { wx += body.vertices[v * 3]; wz += body.vertices[v * 3 + 2]; wy = minOf(wy, body.vertices[v * 3 + 1]); n++ }
            if (n > 0) { wx /= n; wz /= n }
            val sleevePolys = all.filter { p -> p.ids.all { it in sleeveFrom until sleeveTo } }
            var top = Float.MAX_VALUE
            for (p in sleevePolys) for (id in p.ids) top = minOf(top, body.vertices[id * 3 + 1])
            // the sleeve of the game ends above the wrist (the forearm is missing): the sleeve is put so that its lower end is at the wrist, so the hand grows out of it
            var bottom = -Float.MAX_VALUE
            for (p in sleevePolys) for (id in p.ids) bottom = max(bottom, body.vertices[id * 3 + 1])
            val length = bottom - top
            val out = ArrayList<Poly>()
            for (copy in 0 until 2) for (p in sleevePolys) out.add(make(p, wx, bottom + copy * length, wz))
            val claw = all.filter { q -> q.ids.all { it in handFrom until handTo } }
            // the game's claw is too simple to look like a hand: a hand of boxes (palm, four fingers, thumb) in the colour of the claw takes its place
            val skin = claw.firstOrNull()?.color ?: (0xFF97775F.toInt())
            out.addAll(handShape(skin, if (hand == 13) 1f else -1f))
            list.add(out)
        }
        arms = list
    }

    private fun shade(c: Int, f: Float): Int = (0xFF shl 24) or ((((c shr 16) and 0xFF) * f).toInt().coerceAtMost(255) shl 16) or ((((c shr 8) and 0xFF) * f).toInt().coerceAtMost(255) shl 8) or (((c and 0xFF) * f).toInt().coerceAtMost(255))

    /** One box (centre, size, turn about the viewing axis) as polygons in both windings, so that the cull rule decides which side shows. */
    private fun box(out: MutableList<Poly>, mirror: Float, cx: Float, cy: Float, cz: Float, sx: Float, sy: Float, sz: Float, turn: Float, skin: Int) {
        val c = cos(turn); val sn = sin(turn)
        fun pt(i: Int): FloatArray {
            val x = (if (i and 1 == 0) -1f else 1f) * sx / 2f; val y = (if (i and 2 == 0) -1f else 1f) * sy / 2f; val z = (if (i and 4 == 0) -1f else 1f) * sz / 2f
            return floatArrayOf((cx + x * c - y * sn) * mirror, cy + x * sn + y * c, cz + z)
        }
        val sides = arrayOf(intArrayOf(0, 1, 3, 2), intArrayOf(4, 5, 7, 6), intArrayOf(0, 2, 6, 4), intArrayOf(1, 3, 7, 5), intArrayOf(0, 1, 5, 4), intArrayOf(2, 3, 7, 6))
        val tone = floatArrayOf(1.15f, 0.8f, 0.9f, 0.9f, 1.0f, 0.85f)
        for ((k, idx) in sides.withIndex()) {
            val f = FloatArray(12); val r = FloatArray(12)
            for (j in 0..3) { val q = pt(idx[j]); for (m in 0..2) { f[j * 3 + m] = q[m]; r[(3 - j) * 3 + m] = q[m] } }
            out.add(Poly(f, shade(skin, tone[k]))); out.add(Poly(r, shade(skin, tone[k])))
        }
    }

    /** A hand seen from its back, the wrist at the origin, the fingers along +y; [mirror] 1 or -1 puts the thumb on the inner side. */
    private fun handShape(skin: Int, mirror: Float): List<Poly> {
        val out = ArrayList<Poly>()
        box(out, mirror, 0f, 28f, 0f, 62f, 56f, 22f, 0f, skin)                       // the palm
        val lengths = floatArrayOf(36f, 44f, 40f, 32f)
        for (i in 0..3) {
            val x = -22f + i * 14.7f
            val l = lengths[i]
            box(out, mirror, x, 56f + l * 0.3f, -2f, 13f, l * 0.6f, 14f, 0f, skin)       // the first joint
            box(out, mirror, x, 56f + l * 0.75f, -8f, 13f, l * 0.5f, 13f, 0f, skin)      // the second joint, bent a little over the edge of the sheet
        }
        box(out, mirror, 38f, 22f, -2f, 16f, 40f, 15f, -0.55f, skin)                    // the thumb
        box(out, mirror, 50f, 46f, -6f, 14f, 24f, 13f, -0.7f, skin)
        return out
    }

    private fun make(p: GamePoly, wx: Float, wy: Float, wz: Float, scale: Float = 1f): Poly {
        val pts = FloatArray(p.ids.size * 3)
        for ((i, id) in p.ids.withIndex()) {
            val x = (body.vertices[id * 3] - wx) * scale
            val y = (body.vertices[id * 3 + 1] - wy) * scale
            val z = (body.vertices[id * 3 + 2] - wz) * scale
            // the turn is about the arm's axis (y)
            pts[i * 3] = x
            pts[i * 3 + 1] = y
            pts[i * 3 + 2] = z
        }
        return Poly(pts, p.color)
    }

    /** The polygons of the body: fans of triangles with the same first corner and colour put together again, as the game draws a polygon in one piece. */
    private fun polygons(): List<GamePoly> {
        val tr = body.triangles
        val total = tr.size / 4
        val out = ArrayList<GamePoly>()
        var k = 0
        while (k < total) {
            val first = tr[k * 4]; val col = tr[k * 4 + 3]
            val ids = ArrayList<Int>().apply { add(first); add(tr[k * 4 + 1]); add(tr[k * 4 + 2]) }
            var j = k + 1
            while (j < total && tr[j * 4] == first && tr[j * 4 + 1] == ids.last() && tr[j * 4 + 3] == col) { ids.add(tr[j * 4 + 2]); j++ }
            out.add(GamePoly(ids.toIntArray(), col or (0xFF shl 24)))
            k = j
        }
        return out
    }

    /** [time] in seconds. */
    fun render(time: Float) {
        bitmap.eraseColor(0)
        val items = ArrayList<Item>()
        for ((index, polys) in arms.withIndex()) {
            val phase = if (index == 0) 0f else 0.9f
            val breath = sin(time * 1.55f + phase)
            val sway = sin(time * 0.95f + phase * 1.7f)
            val side = if (index == 0) 1f else -1f
            var rot = rotZ(side * (ARM_ANGLE + sway * 0.03f))
            rot = mul(rot, rotX(0.5f + breath * 0.03f))
            rot = mul(rot, rotY(side * 0.35f))
            val at = floatArrayOf(side * -SCREEN_OFFSET_X, WRIST_Y + breath * 6f, WRIST_Z - breath * 10f)
            for (p in polys) {
                val n = p.pts.size / 3
                val xs = FloatArray(n); val ys = FloatArray(n)
                var nearest = Float.MAX_VALUE; var farthest = -Float.MAX_VALUE
                for (i in 0 until n) {
                    val q = apply(rot, p.pts[i * 3] * SCALE, p.pts[i * 3 + 1] * SCALE, p.pts[i * 3 + 2] * SCALE)
                    val z = q[2] + at[2] + 128f
                    nearest = minOf(nearest, z); farthest = max(farthest, z)
                    xs[i] = (160f + (q[0] + at[0]) * FOCAL_X / z) * 2f
                    ys[i] = (100f + (q[1] + at[1]) * FOCAL_Y / z) * 2f
                }
                if (nearest <= 10f) continue
                // the game's rule: a polygon whose first three corners run the wrong way on the screen faces away and is not drawn
                if ((xs[1] - xs[0]) * (ys[2] - ys[0]) - (ys[1] - ys[0]) * (xs[2] - xs[0]) < 0f) continue
                items.add(Item(xs, ys, farthest, p.color))
            }
        }
        items.sortByDescending { it.depth }
        val path = Path()
        for (it in items) {
            path.reset()
            path.moveTo(it.xs[0], it.ys[0])
            for (i in 1 until it.xs.size) path.lineTo(it.xs[i], it.ys[i])
            path.close()
            paint.color = it.color
            canvas.drawPath(path, paint)
        }
    }

    private fun rotX(a: Float) = floatArrayOf(1f, 0f, 0f, 0f, cos(a), -sin(a), 0f, sin(a), cos(a))
    private fun rotY(a: Float) = floatArrayOf(cos(a), 0f, sin(a), 0f, 1f, 0f, -sin(a), 0f, cos(a))
    private fun rotZ(a: Float) = floatArrayOf(cos(a), -sin(a), 0f, sin(a), cos(a), 0f, 0f, 0f, 1f)
    private fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(9)
        for (i in 0..2) for (j in 0..2) r[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j]
        return r
    }
    private fun apply(m: FloatArray, x: Float, y: Float, z: Float) = floatArrayOf(m[0] * x + m[1] * y + m[2] * z, m[3] * x + m[4] * y + m[5] * z, m[6] * x + m[7] * y + m[8] * z)

    companion object {
        private const val FOCAL_X = 300f
        private const val FOCAL_Y = 298f
        private const val SCALE = 1.5f
        private const val ARM_ANGLE = -2.55f
        private const val SCREEN_OFFSET_X = 300f
        private const val WRIST_Y = 255f
        private const val WRIST_Z = 800f
    }
}
