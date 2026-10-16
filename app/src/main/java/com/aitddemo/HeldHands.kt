package com.aitddemo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.aitddemo.game.AnimData
import com.aitddemo.game.BodyAsset
import com.aitddemo.game.BodyPose
import com.aitddemo.game.GroupState
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The character himself, holding the map: the game's own 3D body of the character (so Emily is Emily), posed with the game's own skeleton code (every bone group turned by its state, as
 * an animation does) in a keyframe of the game's own animation 5 (pushing) in which both hands are raised at the sides of his head, seen from behind and above, and drawn like the game draws its bodies: flat polygons with their own colours, the
 * perspective of the game's 3D routine, polygons that look away left out, far to near, into a bitmap of 640 x 400 (two pixels for each pixel of the 320 x 200 screen), without smoothing or
 * outlines. The game has no animation of holding a sheet; this keyframe comes closest. He breathes: the pose moves a little towards the next keyframe and back, and the whole figure moves a
 * little.
 * The groups of the body: 8 and 11 are the shoulders, 9 and 12 the sleeves, 10 and 13 the hands (the right ones are 9 and 10).
 */
class HeldHands(private val body: BodyAsset, private val anim: AnimData?) {
    val bitmap: Bitmap = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val paint = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL }

    private class Poly(val ids: IntArray, val color: Int)
    private class Item(val xs: FloatArray, val ys: FloatArray, val depth: Float, val color: Int)

    private val polys: List<Poly> = run {
        val tr = body.triangles
        val total = tr.size / 4
        val out = ArrayList<Poly>()
        var k = 0
        while (k < total) {
            val first = tr[k * 4]; val col = tr[k * 4 + 3]
            val ids = ArrayList<Int>().apply { add(first); add(tr[k * 4 + 1]); add(tr[k * 4 + 2]) }
            var j = k + 1
            while (j < total && tr[j * 4] == first && tr[j * 4 + 1] == ids.last() && tr[j * 4 + 3] == col) { ids.add(tr[j * 4 + 2]); j++ }
            out.add(Poly(ids.toIntArray(), col or (0xFF shl 24)))
            k = j
        }
        out
    }

    /** The rest states of the bone groups as the file stores them: the pose that the groups without an own state keep. */
    private fun restStates(): Array<GroupState> {
        val g = body.parts?.groups ?: return emptyArray()
        val n = g.size / 12
        return Array(n) { GroupState(g[it * 12 + 5], g[it * 12 + 6], g[it * 12 + 7], g[it * 12 + 8]) }
    }

    /** [time] in seconds. */
    fun render(time: Float) {
        bitmap.eraseColor(0)
        val parts = body.parts ?: return
        val breath = sin(time * 1.55f)
                // the pose: keyframe 2 of the animation, moved a little towards keyframe 1 by the breath (the angles are in 1024ths of a turn: the short way round)
        val st = restStates()
        val frames = anim?.frames
        if (frames != null && frames.size > KEY_FRAME) {
            val a = frames[KEY_FRAME].groups
            val b = frames[KEY_FRAME - 1].groups
            val w = (0.5f + 0.5f * breath) * BREATH_BLEND
            for (g in 1 until minOf(a.size, st.size)) {
                val ga = a[g]; val gb = b[g]
                if (ga.type != 0 || gb.type != 0) { st[g] = GroupState(ga.type, ga.x, ga.y, ga.z); continue }
                fun mix(x: Int, y: Int): Int { var d = (y - x) and 0x3FF; if (d > 512) d -= 1024; return x + (d * w).toInt() }
                st[g] = GroupState(0, mix(ga.x, gb.x), mix(ga.y, gb.y), mix(ga.z, gb.z))
            }
        }
        val v = BodyPose.pose(parts.vertices, parts.groups, parts.groupOrder, st)
        // the camera: behind (+z) and above, looking at the middle of the chest; the body looks towards -z
        val pitch = Math.toRadians(PITCH_DEG.toDouble() + breath * 0.5).toFloat()
        val tx = 0f; val ty = TARGET_Y; val tz = TARGET_Z
        val cx = tx; val cy = ty - DIST * sin(pitch); val cz = tz + DIST * cos(pitch)
        var fx = tx - cx; var fy = ty - cy; var fz = tz - cz
        val fl = sqrt(fx * fx + fy * fy + fz * fz); fx /= fl; fy /= fl; fz /= fl
        var rx = fz; val ry = 0f; var rz = -fx
        val rl = sqrt(rx * rx + rz * rz); rx /= rl; rz /= rl
        val dx = fy * rz - fz * ry; val dy = fz * rx - fx * rz; val dz = fx * ry - fy * rx
        val items = ArrayList<Item>()
        val cam = FloatArray(3)
        for (p in polys) {
            val n = p.ids.size
            val xs = FloatArray(n); val ys = FloatArray(n)
            var nearest = Float.MAX_VALUE; var farthest = -Float.MAX_VALUE
            for (i in 0 until n) {
                val id = p.ids[i]
                val x = v[id * 3] - cx; val y = v[id * 3 + 1] - cy; val z = v[id * 3 + 2] - cz
                cam[0] = x * rx + y * ry + z * rz; cam[1] = x * dx + y * dy + z * dz; cam[2] = x * fx + y * fy + z * fz
                val zz = cam[2] + 128f
                nearest = minOf(nearest, zz); farthest = max(farthest, zz)
                xs[i] = (160f + SHIFT_X + cam[0] * FOCAL_X / zz) * 2f
                ys[i] = (100f + SHIFT_Y + cam[1] * FOCAL_Y / zz) * 2f
            }
            if (nearest <= 20f) continue
            // the game's rule: a polygon whose first three corners run the wrong way on the screen faces away and is not drawn
            if ((xs[1] - xs[0]) * (ys[2] - ys[0]) - (ys[1] - ys[0]) * (xs[2] - xs[0]) < 0f) continue
            items.add(Item(xs, ys, farthest, p.color))
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

    companion object {
        private const val FOCAL_X = 300f
        private const val FOCAL_Y = 298f
        /** The keyframe of the game's animation 5 (pushing) with both hands raised, how far the breath moves towards the keyframe before it, and the camera. */
        const val ANIMATION = 5
        private const val KEY_FRAME = 2
        private const val BREATH_BLEND = 0.35f
        private const val PITCH_DEG = 38f
        private const val DIST = 2200f
        private const val TARGET_Y = -1100f
        private const val TARGET_Z = -150f
        private const val SHIFT_X = 0f
        private const val SHIFT_Y = 87f
    }
}
