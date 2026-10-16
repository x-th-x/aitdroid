package com.aitddemo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The two hands that hold the map: low-poly, flat-shaded polygons in the colours of the game's own bodies (skin and sleeve), drawn with the perspective of the game's 3D routine into
 * a bitmap of 640 x 400 (two pixels for each pixel of the 320 x 200 screen), without smoothing, so that they look like the rest of the game. They breathe: the hands rise and sink a
 * little and turn a little, a little out of step with each other.
 */
class HeldHands {
    val bitmap: Bitmap = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val paint = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL }
    private val edge = Paint().apply { isAntiAlias = false; style = Paint.Style.STROKE; strokeWidth = 1f }

    /** A polygon in camera space: its corners, its colour and its depth key. */
    private class Face(val p: FloatArray, val color: Int)

    /** The shades of one material, dark to light (the game's bodies are painted in a few flat tones, not smoothly lit). */
    class Tones(val dark: Int, val mid: Int, val light: Int)

    private val faces = ArrayList<Face>()

    /** [time] in seconds; [sleeve] the tones of the jacket; the skin is the game's (the hands of Carnby: 8b6b53, 97775f, af8b73). */
    fun render(time: Float, sleeve: Tones, skin: Tones, cuff: Tones) {
        bitmap.eraseColor(0)
        faces.clear()
        for (side in 0..1) {
            val phase = if (side == 0) 0f else 0.9f
            val breath = sin(time * 1.55f + phase)           // the breath: up and down
            val sway = sin(time * 0.95f + phase * 1.7f)      // a slower turning of the hands
            buildHand(side == 1, breath, sway, sleeve, skin, cuff)
        }
        // far to near, as the game's polygons are sorted
        faces.sortByDescending { f -> var z = -1e9f; for (i in 2 until f.p.size step 3) z = max(z, f.p[i]); z }
        val path = Path()
        for (f in faces) {
            path.reset()
            var ok = true
            for (i in 0 until f.p.size / 3) {
                val z = f.p[i * 3 + 2] + 128f
                if (z <= 10f) { ok = false; break }
                val sx = (160f + f.p[i * 3] * FOCAL_X / z) * 2f
                val sy = (100f + f.p[i * 3 + 1] * FOCAL_Y / z) * 2f
                if (i == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
            }
            if (!ok) continue
            path.close()
            paint.color = f.color
            canvas.drawPath(path, paint)
            edge.color = darker(f.color)
            canvas.drawPath(path, edge)
        }
    }

    private fun darker(c: Int): Int = (0xFF shl 24) or ((((c shr 16) and 0xFF) * 3 / 4) shl 16) or ((((c shr 8) and 0xFF) * 3 / 4) shl 8) or ((c and 0xFF) * 3 / 4)

    // ------------------------------------------------------------------------------------------------------------------ the shape of a hand

    /** A 3 x 3 rotation (row major). */
    private fun rotX(a: Float) = floatArrayOf(1f, 0f, 0f, 0f, cos(a), -sin(a), 0f, sin(a), cos(a))
    private fun rotY(a: Float) = floatArrayOf(cos(a), 0f, sin(a), 0f, 1f, 0f, -sin(a), 0f, cos(a))
    private fun rotZ(a: Float) = floatArrayOf(cos(a), -sin(a), 0f, sin(a), cos(a), 0f, 0f, 0f, 1f)
    private fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(9)
        for (i in 0..2) for (j in 0..2) r[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j]
        return r
    }
    private fun apply(m: FloatArray, x: Float, y: Float, z: Float) = floatArrayOf(m[0] * x + m[1] * y + m[2] * z, m[3] * x + m[4] * y + m[5] * z, m[6] * x + m[7] * y + m[8] * z)

    /**
     * One part of the hand as a box: [c] its centre in the hand's own frame, [s] its size, [r] its own turning; the hand's frame is then turned by [hand] and put at [at] of the camera
     * space. The six sides are shaded by where they look (the light comes from above, left, in front).
     */
    private fun box(at: FloatArray, hand: FloatArray, c: FloatArray, s: FloatArray, r: FloatArray, tones: Tones, mirror: Boolean) {
        val corners = Array(8) { k ->
            val sx = if (k and 1 == 0) -1f else 1f; val sy = if (k and 2 == 0) -1f else 1f; val sz = if (k and 4 == 0) -1f else 1f
            val l = apply(r, sx * s[0] / 2f, sy * s[1] / 2f, sz * s[2] / 2f)
            val w = apply(hand, c[0] + l[0], c[1] + l[1], c[2] + l[2])
            // the right hand is the left one seen in a mirror
            floatArrayOf((w[0] + at[0]) * (if (mirror) -1f else 1f), w[1] + at[1], w[2] + at[2])
        }
        // the sides: corner indices around, and the outward direction in the part's own frame (before turning)
        val sides = arrayOf(
            intArrayOf(0, 1, 3, 2) to floatArrayOf(0f, 0f, -1f), intArrayOf(4, 5, 7, 6) to floatArrayOf(0f, 0f, 1f),
            intArrayOf(0, 2, 6, 4) to floatArrayOf(-1f, 0f, 0f), intArrayOf(1, 3, 7, 5) to floatArrayOf(1f, 0f, 0f),
            intArrayOf(0, 1, 5, 4) to floatArrayOf(0f, -1f, 0f), intArrayOf(2, 3, 7, 6) to floatArrayOf(0f, 1f, 0f),
        )
        val centre = floatArrayOf(corners.sumOf { it[0].toDouble() }.toFloat() / 8f, corners.sumOf { it[1].toDouble() }.toFloat() / 8f, corners.sumOf { it[2].toDouble() }.toFloat() / 8f)
        for ((idx, _) in sides) {
            val p = FloatArray(12)
            var mx = 0f; var my = 0f; var mz = 0f
            for (k in 0..3) { val q = corners[idx[k]]; p[k * 3] = q[0]; p[k * 3 + 1] = q[1]; p[k * 3 + 2] = q[2]; mx += q[0] / 4f; my += q[1] / 4f; mz += q[2] / 4f }
            // the normal of the side, turned to point away from the middle of the part
            var nx = (p[4] - p[1]) * (p[8] - p[2]) - (p[5] - p[2]) * (p[7] - p[1])
            var ny = (p[5] - p[2]) * (p[6] - p[0]) - (p[3] - p[0]) * (p[8] - p[2])
            var nz = (p[3] - p[0]) * (p[7] - p[1]) - (p[4] - p[1]) * (p[6] - p[0])
            if (nx * (mx - centre[0]) + ny * (my - centre[1]) + nz * (mz - centre[2]) < 0f) { nx = -nx; ny = -ny; nz = -nz }
            val len = sqrt(nx * nx + ny * ny + nz * nz).let { if (it < 1e-6f) 1f else it }
            nx /= len; ny /= len; nz /= len
            // not drawn when it looks away from the camera (the camera is at the origin)
            if (nx * mx + ny * my + nz * (mz + 128f) >= 0f) continue
            val lit = -(nx * LIGHT[0] + ny * LIGHT[1] + nz * LIGHT[2])
            faces.add(Face(p, if (lit > 0.55f) tones.light else if (lit > 0.22f) tones.mid else tones.dark))
        }
    }

    /**
     * A hand with its sleeve, seen from the back, holding the lower corner of a sheet: the forearm comes from below the picture, the fingers curl over the edge, the thumb lies along
     * the side. [right] mirrors it. [breath] and [sway] (-1 to 1) move it a little.
     */
    private fun buildHand(right: Boolean, breath: Float, sway: Float, sleeve: Tones, skin: Tones, cuff: Tones) {
        // where the wrist is (camera space) and how the hand is turned: the fingers point up and inwards
        val at = floatArrayOf(-330f, 198f + breath * 7f, 900f - breath * 12f)
        // turn: roll so the fingers point up and to the inside, the back of the hand a little towards the camera and tilted with the breath
        var hand = rotZ(-0.7f + sway * 0.03f)
        hand = mul(hand, rotX(-0.22f + breath * 0.025f))
        hand = mul(hand, rotY(0.22f))
        val none = rotZ(0f)
        // the forearm in its sleeve and the shirt cuff
        box(at, hand, floatArrayOf(-235f, 0f, 0f), floatArrayOf(250f, 100f, 94f), none, sleeve, right)
        box(at, hand, floatArrayOf(-98f, 0f, 0f), floatArrayOf(34f, 108f, 102f), none, cuff, right)
        // the back of the hand (the wrist narrower than the knuckles)
        box(at, hand, floatArrayOf(-48f, 0f, 0f), floatArrayOf(66f, 80f, 44f), none, skin, right)
        box(at, hand, floatArrayOf(20f, 0f, 2f), floatArrayOf(72f, 94f, 46f), none, skin, right)
        // the four fingers: first joint along the hand, second joint bent over the edge of the sheet (away from the camera)
        for (i in 0..3) {
            val v = (-33f + i * 22f)
            val bend = 0.45f + (if (i == 1 || i == 2) 0.1f else 0f) + breath * 0.02f
            box(at, hand, floatArrayOf(80f, v, 6f), floatArrayOf(46f, 19f, 26f), rotY(0.16f), skin, right)
            box(at, hand, floatArrayOf(118f, v, 24f), floatArrayOf(36f, 18f, 23f), rotY(bend + 0.3f), skin, right)
        }
        // the thumb along the inner side, on top of the sheet's edge
        box(at, hand, floatArrayOf(14f, 62f, -22f), floatArrayOf(64f, 26f, 28f), rotZ(-0.35f), skin, right)
        box(at, hand, floatArrayOf(62f, 78f, -26f), floatArrayOf(42f, 22f, 24f), rotZ(-0.6f), skin, right)
    }

    companion object {
        private const val FOCAL_X = 300f
        private const val FOCAL_Y = 298f
        private val LIGHT = floatArrayOf(0.35f, 0.55f, 0.76f)
    }
}
