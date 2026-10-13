package com.aitddemo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Moving light on the camera pictures (NOT in the original game; an addition on this branch). The pictures of the original are still images; here a few places of them
 * get light that changes: the moonlight at the windows (with now and then a flash of lightning), the flame of the candle on the piano, dust that floats in the moonlight.
 * Everything is added to the pixels of the user's own picture at runtime, nothing of it is stored. Places are in the pixels of the 320 x 200 picture.
 */

/** Where a light lies. */
sealed class FxShape {
    class Rect(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : FxShape()
    class Circle(val cx: Float, val cy: Float, val r: Float) : FxShape()
    class Quad(val xs: FloatArray, val ys: FloatArray) : FxShape()
}

/** How the strength of a light changes with the time. */
enum class FxWave { MOON, CANDLE, STORM }

/** A light of one colour (r, g, b added at full strength) on a [shape]; [strength] 1 = the colour is added fully. */
class FxLight(val shape: FxShape, val r: Int, val g: Int, val b: Int, val strength: Float, val wave: FxWave, val seed: Int = 0)

/** Dust that floats in a rectangle: [count] specks of a pale colour. */
class FxDust(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val count: Int, val strength: Float)

/** The lights of one camera. */
class FxSet(val lights: List<FxLight>, val dust: List<FxDust>)

object BackgroundFxCore {
    /** The attic: stage 0, room 0, cameras 0 to 4 (places found by looking at the pictures). */
    fun attic(camera: Int): FxSet? = when (camera) {
        0 -> FxSet(
            listOf(
                FxLight(FxShape.Rect(145f, 30f, 168f, 50f), 70, 95, 140, 0.9f, FxWave.MOON),
                FxLight(FxShape.Circle(58f, 39f, 10f), 255, 165, 70, 0.75f, FxWave.CANDLE, 1),
            ),
            emptyList(),
        )
        1 -> FxSet(
            listOf(
                FxLight(FxShape.Rect(97f, 83f, 112f, 97f), 70, 95, 140, 0.9f, FxWave.MOON),
                FxLight(FxShape.Quad(floatArrayOf(128f, 152f, 100f, 55f), floatArrayOf(136f, 136f, 200f, 200f)), 40, 52, 80, 0.5f, FxWave.MOON),
            ),
            listOf(FxDust(78f, 118f, 150f, 190f, 14, 0.8f)),
        )
        2 -> FxSet(
            listOf(
                FxLight(FxShape.Rect(143f, 5f, 156f, 32f), 70, 95, 140, 0.9f, FxWave.MOON),
                FxLight(FxShape.Circle(51f, 57f, 11f), 255, 165, 70, 0.75f, FxWave.CANDLE, 2),
            ),
            emptyList(),
        )
        else -> null
    }

    /** The strength of a wave at [t] seconds (0..1.3). The flashes of lightning come at a fixed, irregular rhythm of about 20 seconds. */
    fun intensity(wave: FxWave, t: Float, seed: Int): Float = when (wave) {
        FxWave.MOON -> 0.30f + 0.12f * sin(t * 2f * PI.toFloat() / 7f) + 0.10f * sin(t * 2f * PI.toFloat() / 2.3f) + storm(t) * 1.0f
        FxWave.CANDLE -> (0.55f + 0.25f * noise(t * 9f, seed) + 0.15f * noise(t * 23f, seed + 7)).coerceIn(0f, 1f)
        FxWave.STORM -> storm(t)
    }

    /** Lightning: in every slot of 20 seconds one flash (two peaks) at a place of the slot that comes from the slot number. */
    private fun storm(t: Float): Float {
        val slot = floor(t / 20f).toInt()
        val at = 4f + 12f * hash01(slot * 31 + 5)
        val d = t - slot * 20f - at
        if (d < 0f || d > 0.9f) return 0f
        val a = (1f - d / 0.9f)
        return a * (0.6f + 0.4f * sin(d * 40f)).coerceAtLeast(0.2f)
    }

    private fun hash01(n: Int): Float {
        var x = n * 374761393 + 668265263
        x = (x xor (x ushr 13)) * 1274126177
        x = x xor (x ushr 16)
        return (x and 0xFFFF) / 65535f
    }

    /** Smooth noise (-1..1... mapped to 0..1) of a value. */
    private fun noise(v: Float, seed: Int): Float {
        val i = floor(v).toInt()
        val f = v - i
        val a = hash01(i * 7919 + seed * 104729)
        val b = hash01((i + 1) * 7919 + seed * 104729)
        val s = f * f * (3f - 2f * f)
        return a + (b - a) * s
    }

    /**
     * Writes into [out] (the picture scaled by [scale] from 320 x 200, [width] pixels wide) the picture [base] with the lights of [fx] at time [t] seconds: only the pixels
     * near a light or a speck are touched, the rest of [out] must already hold [base].
     */
    fun render(base: IntArray, out: IntArray, width: Int, scale: Int, fx: FxSet, t: Float) {
        val height = base.size / width
        // put the original pixels back where the last frame changed something
        for (l in fx.lights) restore(base, out, width, height, scale, boundsOf(l.shape))
        for (d in fx.dust) restore(base, out, width, height, scale, floatArrayOf(d.x1 - 3f, d.y1 - 3f, d.x2 + 3f, d.y2 + 3f))
        for (l in fx.lights) {
            val k = intensity(l.wave, t, l.seed) * l.strength
            val bb = boundsOf(l.shape)
            val x0 = (bb[0] * scale).toInt().coerceIn(0, width - 1); val x1 = (bb[2] * scale).toInt().coerceIn(0, width - 1)
            val y0 = (bb[1] * scale).toInt().coerceIn(0, height - 1); val y1 = (bb[3] * scale).toInt().coerceIn(0, height - 1)
            for (y in y0..y1) for (x in x0..x1) {
                val w = weight(l.shape, (x + 0.5f) / scale, (y + 0.5f) / scale)
                if (w <= 0f) continue
                add(out, y * width + x, (l.r * k * w).toInt(), (l.g * k * w).toInt(), (l.b * k * w).toInt())
            }
        }
        for ((n, d) in fx.dust.withIndex()) {
            for (i in 0 until d.count) {
                val h1 = hash01(i * 17 + n * 1000 + 3); val h2 = hash01(i * 29 + n * 1000 + 11); val h3 = hash01(i * 43 + n * 1000 + 19)
                val w = d.x2 - d.x1; val hgt = d.y2 - d.y1
                // each speck sinks slowly, wraps around and sways; it twinkles
                val fall = (t * (1.2f + 1.5f * h3) + h2 * hgt) % hgt
                val px = d.x1 + ((h1 * w + 4f * sin(t * (0.3f + h3 * 0.5f) + i)) % w + w) % w
                val py = d.y1 + fall
                val tw = 0.35f + 0.65f * (0.5f + 0.5f * sin(t * (1.5f + 2f * h1) + i * 2.3f))
                val edge = minOf(fall, hgt - fall).coerceAtMost(8f) / 8f // fades in and out at the top and the bottom
                val v = (60f * d.strength * tw * edge * (intensity(FxWave.MOON, t, 0) + 0.2f)).toInt()
                val cx = (px * scale).toInt(); val cy = (py * scale).toInt()
                for (yy in cy..cy + 1) for (xx in cx..cx + 1) if (xx in 0 until width && yy in 0 until height) add(out, yy * width + xx, v, v, (v * 1.1f).toInt())
            }
        }
    }

    private fun boundsOf(s: FxShape): FloatArray = when (s) {
        is FxShape.Rect -> floatArrayOf(s.x1, s.y1, s.x2, s.y2)
        is FxShape.Circle -> floatArrayOf(s.cx - s.r, s.cy - s.r, s.cx + s.r, s.cy + s.r)
        is FxShape.Quad -> floatArrayOf(s.xs.min(), s.ys.min(), s.xs.max(), s.ys.max())
    }

    private fun restore(base: IntArray, out: IntArray, width: Int, height: Int, scale: Int, b: FloatArray) {
        val x0 = (b[0] * scale).toInt().coerceIn(0, width - 1); val x1 = (b[2] * scale).toInt().coerceIn(0, width - 1)
        val y0 = (b[1] * scale).toInt().coerceIn(0, height - 1); val y1 = (b[3] * scale).toInt().coerceIn(0, height - 1)
        for (y in y0..y1) System.arraycopy(base, y * width + x0, out, y * width + x0, x1 - x0 + 1)
    }

    /** How much of the light reaches the point (0..1): a rectangle softly at its border, a circle fading towards its edge, a quad (a beam) softly at its sides. */
    private fun weight(s: FxShape, x: Float, y: Float): Float = when (s) {
        is FxShape.Rect -> if (x < s.x1 || x > s.x2 || y < s.y1 || y > s.y2) 0f else {
            val e = minOf(minOf(x - s.x1, s.x2 - x), minOf(y - s.y1, s.y2 - y)).coerceAtMost(1.5f) / 1.5f
            e
        }
        is FxShape.Circle -> {
            val d = sqrt((x - s.cx) * (x - s.cx) + (y - s.cy) * (y - s.cy)) / s.r
            if (d >= 1f) 0f else (1f - d) * (1f - d)
        }
        is FxShape.Quad -> {
            // four corners in the order: top left, top right, bottom right, bottom left (given as xs/ys of top x1, top x2, bottom x2, bottom x1)
            val topL = s.xs[0]; val topR = s.xs[1]; val botR = s.xs[2]; val botL = s.xs[3]
            val yTop = s.ys[0]; val yBot = s.ys[2]
            if (y < yTop || y > yBot) 0f else {
                val f = (y - yTop) / (yBot - yTop)
                val l = topL + (botL - topL) * f
                val r = topR + (botR - topR) * f
                if (x < l || x > r) 0f else {
                    val side = minOf(x - l, r - x).coerceAtMost(6f) / 6f
                    side * (1f - 0.5f * f)
                }
            }
        }
    }

    private fun add(out: IntArray, i: Int, r: Int, g: Int, b: Int) {
        val p = out[i]
        val nr = ((p shr 16) and 0xFF) + r
        val ng = ((p shr 8) and 0xFF) + g
        val nb = (p and 0xFF) + b
        out[i] = (p and 0xFF000000.toInt()) or (minOf(nr, 255) shl 16) or (minOf(ng, 255) shl 8) or minOf(nb, 255)
    }
}
