package com.aitddemo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * "Lost Place" (NOT in the original game; a prototype on this branch): the attic as an abandoned place of today - cold desaturated colours, dust, peeling plaster, damp stains, rubble,
 * footprints in the dust, graffiti, a broken window with daylight that falls into the room. The pictures of the cameras are changed at runtime by code (nothing of the user's pictures
 * is stored): the colours are graded, things are painted over them, and the result is taken back to the colours of the game's palette. Only the first picture of the attic (camera 0) has
 * its own details so far; the other pictures get the colour grade and the dust.
 */
object LostPlace {
    private val W = WindowView.W
    private val H = WindowView.H

    /** The picture [photo] (320 x 200 ARGB of the game) of camera [camera] of the attic as a lost place, in the colours of [palette]. */
    fun paint(photo: IntArray, palette: IntArray, camera: Int): IntArray {
        val c = WindowView.Canvas()
        for (i in photo.indices) { c.r[i] = ((photo[i] shr 16) and 0xFF).toFloat(); c.g[i] = ((photo[i] shr 8) and 0xFF).toFloat(); c.b[i] = (photo[i] and 0xFF).toFloat() }
        grade(c)
        if (camera == 0) details(c)
        return c.quantize(palette)
    }

    /** What the figures and things of the room look like: the colours of the bodies are taken towards grey and dust. */
    fun gradeColor(c: Int): Int {
        val r = ((c shr 16) and 0xFF).toFloat(); val g = ((c shr 8) and 0xFF).toFloat(); val b = (c and 0xFF).toFloat()
        val lum = 0.3f * r + 0.59f * g + 0.11f * b
        val nr = ((lum + (r - lum) * 0.5f) * 0.9f + 14f).toInt().coerceIn(0, 255)
        val ng = ((lum + (g - lum) * 0.5f) * 0.92f + 15f).toInt().coerceIn(0, 255)
        val nb = ((lum + (b - lum) * 0.5f) * 0.95f + 17f).toInt().coerceIn(0, 255)
        return (c and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
    }

    // ------------------------------------------------------------------------------------------------------------------ colour grade

    private fun grade(c: WindowView.Canvas) {
        for (y in 0 until H) for (x in 0 until W) {
            val i = y * W + x
            val lum = 0.3f * c.r[i] + 0.59f * c.g[i] + 0.11f * c.b[i]
            // colours drain out: only about half is left, and what is left leans to a cold grey-green
            var r = lum + (c.r[i] - lum) * 0.42f
            var g = lum + (c.g[i] - lum) * 0.42f
            var b = lum + (c.b[i] - lum) * 0.42f
            r *= 0.9f; g *= 0.94f; b *= 0.98f
            // the blacks are lifted by the dust in the air
            r = r * 0.88f + 16f; g = g * 0.88f + 17f; b = b * 0.88f + 18f
            // dirt on everything: a slow noise of lighter and darker
            val dirt = 0.9f + 0.2f * WindowView.fbm(x / 23f, y / 17f, 401)
            r *= dirt; g *= dirt; b *= dirt
            // the edges of the picture are darker
            val dx = (x - 160f) / 160f; val dy = (y - 100f) / 100f
            val vig = 1f - 0.4f * (dx * dx * 0.7f + dy * dy * 0.6f)
            c.r[i] = r * vig; c.g[i] = g * vig; c.b[i] = b * vig
        }
        // dust that lies on the floor of the room: a pale veil that gets thicker towards the corners where nobody walks
        for (y in 62 until H) for (x in 0 until W) {
            val dust = (0.1f + 0.14f * WindowView.fbm(x / 31f, y / 14f, 402)) * (0.5f + 0.5f * abs(x - 160f) / 160f)
            c.blend(x, y, 128f, 126f, 118f, dust)
        }
    }

    // ------------------------------------------------------------------------------------------------------------------ the details of the first picture

    private fun details(c: WindowView.Canvas) {
        wallDecay(c)
        piano(c)
        barrels(c)
        tablecloth(c)
        rubble(c)
        puddle(c)
        footprints(c)
        graffiti(c)
        cobwebs(c)
        window(c)
    }

    /** The planks of the back wall: plaster has come off in patches, the bare laths show, damp stains run down from the roof. */
    private fun wallDecay(c: WindowView.Canvas) {
        for (y in 23 until 65) for (x in 0 until W) {
            if (x in 140..171 && y in 28..54) continue // the window
            val m = WindowView.fbm(x / 10f, y / 7f, 301)
            if (m > 0.6f) {
                val n = WindowView.fbm(x / 3f, y / 3f, 302)
                val edge = ((m - 0.6f) / 0.04f).coerceIn(0f, 1f)
                if (m > 0.74f) { // bare laths: dark brown strips
                    val strip = if ((y / 3) % 2 == 0) 1f else 0.7f
                    c.blend(x, y, 52f * strip, 46f * strip, 40f * strip, 0.85f * edge)
                } else { // plaster that has not fallen yet: pale and flaky
                    c.blend(x, y, 168f + 24f * n, 160f + 22f * n, 142f + 18f * n, 0.7f * edge)
                }
            }
        }
        // damp: dark streaks that run down from the roof beam, and mould where they end
        for (k in 0 until 26) {
            val x0 = (WindowView.hash(k * 7 + 1) * 320f).toInt()
            val len = 8 + (WindowView.hash(k * 11 + 3) * 28f).toInt()
            val w = 1 + (WindowView.hash(k * 13 + 5) * 3f).toInt()
            for (yy in 0 until len) {
                val f = 1f - yy / len.toFloat()
                for (xx in 0 until w) c.blend(x0 + xx, 23 + yy, 18f, 26f, 20f, 0.35f * f)
            }
            if (len > 18) for (dy in 0..3) for (dx in -2..w + 2) if (WindowView.hash2(x0 + dx, dy + k, 9) > 0.35f) c.blend(x0 + dx, 23 + len + dy, 52f, 74f, 44f, 0.4f)
        }
    }

    /** The piano under a dust sheet: a pale cloth with folds. */
    private fun piano(c: WindowView.Canvas) {
        val xs = floatArrayOf(38f, 66f, 71f, 35f)
        val ys = floatArrayOf(36f, 35f, 62f, 63f)
        c.poly(xs, ys) { x, y ->
            val fold = WindowView.fbm(x / 4f, y / 9f, 311)
            val v = 104f + 40f * fold - 0.4f * (y - 36f)
            c.blend(x, y, v, v - 1f, v - 8f, 0.8f)
        }
        for (x in 38..70 step 5) for (y in 40..62) c.blend(x, y, 70f, 70f, 66f, 0.12f) // the folds hang down
    }

    /** Moss on the barrels that stand in the damp, and the rings of the staves that have rotted. */
    private fun barrels(c: WindowView.Canvas) {
        for (x in 172..212) for (y in 54..62) {
            val m = WindowView.fbm(x / 2.5f, y / 2.5f, 321)
            if (m > 0.5f) c.blend(x, y, 66f, 92f, 52f, 0.45f * ((m - 0.5f) * 3f).coerceAtMost(1f))
        }
    }

    /** The green cloth over the table: faded, stained, with holes where the moths were. */
    private fun tablecloth(c: WindowView.Canvas) {
        for (y in 128 until H) for (x in 0..148) {
            val i = y * W + x
            if (c.g[i] < c.r[i] * 1.05f) continue // only the cloth (it is the greenish thing)
            val m = WindowView.fbm(x / 7f, y / 5f, 331)
            if (m > 0.64f) c.blend(x, y, 30f, 34f, 28f, 0.55f) // a stain
            if (WindowView.fbm(x / 3f, y / 3f, 332) > 0.86f) c.blend(x, y, 12f, 12f, 12f, 0.8f) // a hole
            c.blend(x, y, 130f, 135f, 125f, 0.1f * (0.5f + m))
        }
    }

    /** Plaster and tiles that fell from the roof: chunks over the floor, a heap beside the post, a plank that fell from the beams. */
    private fun rubble(c: WindowView.Canvas) {
        fun chunk(cx: Float, cy: Float, s: Float, seed: Int) {
            val xs = FloatArray(5); val ys = FloatArray(5)
            for (k in 0 until 5) { val a = k * 2f * PI.toFloat() / 5f; val r = s * (0.6f + 0.5f * WindowView.hash(seed * 5 + k)); xs[k] = cx + cos(a) * r * 1.4f; ys[k] = cy + sin(a) * r * 0.8f }
            val light = 92f + 46f * WindowView.hash(seed * 3)
            c.poly(floatArrayOf(xs[0] + 1f, xs[1] + 1f, xs[2] + 1f, xs[3] + 1f, xs[4] + 1f), floatArrayOf(ys[0] + 1f, ys[1] + 1f, ys[2] + 1f, ys[3] + 1f, ys[4] + 1f)) { x, y -> c.blend(x, y, 20f, 20f, 18f, 0.5f) } // the shadow
            c.poly(xs, ys) { x, y -> c.set(x, y, light, light - 3f, light - 12f) }
        }
        for (i in 0 until 24) {
            val y = 80f + 112f * WindowView.hash(i * 3 + 1) * WindowView.hash(i * 3 + 1)
            val x = 4f + 312f * WindowView.hash(i * 5 + 2)
            if (x in 0f..150f && y > 130f) continue // not on the table cloth
            chunk(x, y, 0.7f + 2.2f * (y / 200f), i)
        }
        // the heap beside the post on the right
        for (i in 0 until 16) chunk(238f + 26f * WindowView.hash(i * 7 + 90), 74f + 12f * WindowView.hash(i * 11 + 91), 2.2f + 2f * WindowView.hash(i + 95), 100 + i)
        // the plank that fell down
        c.poly(floatArrayOf(188f, 248f, 249f, 189f), floatArrayOf(176f, 166f, 171f, 183f)) { x, y -> val g = WindowView.fbm(x / 9f, y / 1.5f, 341); c.set(x, y, 66f + 36f * g, 61f + 32f * g, 55f + 28f * g) }
        c.poly(floatArrayOf(188f, 248f, 248f, 189f), floatArrayOf(176f, 166f, 167f, 177f)) { x, y -> c.blend(x, y, 150f, 140f, 120f, 0.35f) } // the lit edge
    }

    /** A puddle under the leak in the roof: it shows the light of the window. */
    private fun puddle(c: WindowView.Canvas) {
        c.ellipse(178f, 128f, 22f, 5.5f) { x, y, dx, dy ->
            val k = 1f - (dx * dx + dy * dy)
            val sky = 0.6f + 0.4f * (-dy)
            c.blend(x, y, 150f * sky + 40f, 168f * sky + 40f, 182f * sky + 40f, 0.55f + 0.35f * k)
        }
        // the leaves and dirt that the water has brought
        for (i in 0 until 14) {
            val a = WindowView.hash(i * 3 + 7) * 2f * PI.toFloat()
            c.ellipse(178f + cos(a) * 24f, 128f + sin(a) * 6.5f, 1.4f, 0.9f) { x, y, _, _ -> c.set(x, y, 58f, 52f, 30f) }
        }
    }

    /** Footprints in the dust: someone has come in from the front of the room and gone to the piano. */
    private fun footprints(c: WindowView.Canvas) {
        val n = 22
        for (i in 0 until n) {
            val t = i / (n - 1f)
            // from the bottom of the picture (150, 197) to the piano (62, 66), a little bent
            val x = 150f + (62f - 150f) * t + 9f * sin(t * 5f)
            val y = 197f + (66f - 197f) * t
            val s = 0.5f + 1.5f * (y - 60f) / 140f
            val side = if (i % 2 == 0) -1f else 1f
            c.ellipse(x + side * 3.4f * s, y, 1.9f * s, 3.2f * s * 0.62f) { px, py, _, _ -> c.blend(px, py, 28f, 26f, 24f, 0.68f) }
        }
    }

    /** A tag on the wall right of the window: a ring with a cross, an arrow, drips of paint. */
    private fun graffiti(c: WindowView.Canvas) {
        val cx = 242f; val cy = 42f
        c.ellipse(cx, cy, 11f, 11f) { x, y, dx, dy ->
            val d = sqrt(dx * dx + dy * dy)
            if (d > 0.72f) c.blend(x, y, 196f, 52f, 46f, 0.8f * (1f - abs(d - 0.86f) / 0.2f).coerceIn(0f, 1f))
        }
        for (t in -9..9) {
            c.blend((cx + t * 0.8f).toInt(), (cy + t * 0.8f).toInt(), 196f, 52f, 46f, 0.8f)
            c.blend((cx + t * 0.8f).toInt() + 1, (cy + t * 0.8f).toInt(), 196f, 52f, 46f, 0.6f)
            c.blend((cx + t * 0.8f).toInt(), (cy - t * 0.8f).toInt(), 196f, 52f, 46f, 0.8f)
        }
        for (d in intArrayOf(-7, -1, 6)) { // drips
            val len = 6 + (WindowView.hash(d + 50) * 7f).toInt()
            for (yy in 0 until len) c.blend((cx + d).toInt(), (cy + 8f).toInt() + yy, 190f, 50f, 44f, 0.7f * (1f - yy / len.toFloat()))
        }
        // an arrow below it that points at the window
        for (t in 0..16) c.blend((cx - 2f - t).toInt(), 58, 232f, 232f, 226f, 0.7f)
        for (t in 0..4) { c.blend((cx - 19f + t).toInt(), 58 - t, 232f, 232f, 226f, 0.7f); c.blend((cx - 19f + t).toInt(), 58 + t, 232f, 232f, 226f, 0.7f) }
    }

    /** Cobwebs in the corners between the roof beams. */
    private fun cobwebs(c: WindowView.Canvas) {
        fun web(cx: Float, cy: Float, dirx: Float, diry: Float, size: Float) {
            for (k in 0..6) {
                val a = k / 6f * (PI.toFloat() / 2f)
                val ex = cx + dirx * cos(a) * size
                val ey = cy + diry * sin(a) * size
                val n = (size * 2).toInt()
                for (s in 0..n) c.blend((cx + (ex - cx) * s / n).toInt(), (cy + (ey - cy) * s / n).toInt(), 205f, 205f, 200f, 0.3f)
            }
            for (ring in 1..4) {
                val r = size * ring / 4f
                var prevX = 0f; var prevY = 0f
                for (k in 0..6) {
                    val a = k / 6f * (PI.toFloat() / 2f)
                    val px = cx + dirx * cos(a) * r; val py = cy + diry * sin(a) * r * (1f + 0.1f * sin(k * 2f + ring))
                    if (k > 0) { val n = 8; for (s in 0..n) c.blend((prevX + (px - prevX) * s / n).toInt(), (prevY + (py - prevY) * s / n).toInt(), 205f, 205f, 200f, 0.22f) }
                    prevX = px; prevY = py
                }
            }
        }
        web(0f, 23f, 1f, 1f, 26f)
        web(138f, 23f, -1f, 1f, 18f)
        web(268f, 23f, 1f, 1f, 22f)
        web(316f, 23f, -1f, 1f, 16f)
    }

    /** The window: the glass is broken out, the daylight is pale and bright, a few teeth of glass stand in the frame. */
    private fun window(c: WindowView.Canvas) {
        for (y in 30..51) for (x in 143..168) {
            val f = (y - 30f) / 22f
            c.set(x, y, 214f - 22f * f, 222f - 20f * f, 228f - 16f * f)
        }
        // the cross of the frame, rotten and dark
        for (y in 30..51) for (x in 154..157) c.blend(x, y, 40f, 32f, 26f, 0.9f)
        for (x in 143..168) for (y in 40..42) c.blend(x, y, 40f, 32f, 26f, 0.9f)
        // teeth of glass
        for (k in 0 until 7) {
            val x0 = 143f + 25f * WindowView.hash(k * 3 + 200)
            val len = 3 + (WindowView.hash(k * 5 + 201) * 6f).toInt()
            for (s in 0 until len) c.blend((x0 + s * 0.3f).toInt(), 30 + s, 150f, 168f, 176f, 0.9f * (1f - s / len.toFloat()))
        }
        // a glow round the window: the light is stronger than the picture can show
        for (y in 22..62) for (x in 130..182) {
            val dx = (x - 155.5f) / 26f; val dy = (y - 41f) / 21f
            val d = sqrt(dx * dx + dy * dy)
            if (d > 1f && d < 1.7f) { val k = (1.7f - d) / 0.7f; c.add(x, y, 28f * k * k, 31f * k * k, 32f * k * k) }
        }
    }

    // ------------------------------------------------------------------------------------------------------------------ the moving light

    /** The moving things of the first picture: the daylight in the window that clouds dim and brighten, the shaft of light with the dust in it, the drop that falls into the puddle. */
    fun fx(camera: Int): FxSet? = when (camera) {
        0 -> FxSet(
            listOf(
                FxLight(FxShape.Rect(143f, 30f, 168f, 52f), 225, 232, 240, 0.6f, FxWave.OVERCAST),
                FxLight(FxShape.Quad(floatArrayOf(143f, 168f, 238f, 92f), floatArrayOf(52f, 52f, 142f, 142f)), 205, 214, 198, 0.17f, FxWave.OVERCAST),
                FxLight(FxShape.Circle(174f, 112f, 52f), 200, 208, 190, 0.18f, FxWave.OVERCAST),
            ),
            listOf(FxDust(100f, 54f, 240f, 138f, 30, 1f)),
            drips = listOf(FxDrip(178f, 24f, 126f)),
        )
        else -> null
    }
}
