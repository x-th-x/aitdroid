package com.aitddemo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The view from the attic window (NOT in the original game; an addition on this branch): what Carnby sees when he examines the window with "Open/Search" - the path to the
 * house, the gate, the road and the landscape in the night of the storm. The original has no such picture, so it is drawn here by code, in the game's own way: 320 x 200
 * pixels, only colours of the game's palette (ITD_RESS.PAK), flat shaded areas with a grainy dither as the pictures of the cameras have. Nothing of it is stored.
 */
object WindowView {
    const val W = 320
    const val H = 200

    // ------------------------------------------------------------------------------------------------------------------ the picture

    /** The picture as 320 x 200 ARGB made only of colours of [palette]; [broken] = the window pane has been smashed (the creature came through it). */
    fun paint(palette: IntArray, broken: Boolean): IntArray {
        val c = Canvas()
        sky(c)
        landscape(c)
        car(c)
        trees(c)
        porch(c)
        mist(c)
        frame(c, broken)
        return c.quantize(palette)
    }

    internal class Canvas {
        val r = FloatArray(W * H)
        val g = FloatArray(W * H)
        val b = FloatArray(W * H)

        fun set(x: Int, y: Int, cr: Float, cg: Float, cb: Float) {
            if (x !in 0 until W || y !in 0 until H) return
            val i = y * W + x
            r[i] = cr; g[i] = cg; b[i] = cb
        }

        fun blend(x: Int, y: Int, cr: Float, cg: Float, cb: Float, a: Float) {
            if (x !in 0 until W || y !in 0 until H || a <= 0f) return
            val i = y * W + x
            val k = a.coerceAtMost(1f)
            r[i] += (cr - r[i]) * k; g[i] += (cg - g[i]) * k; b[i] += (cb - b[i]) * k
        }

        fun add(x: Int, y: Int, cr: Float, cg: Float, cb: Float) {
            if (x !in 0 until W || y !in 0 until H) return
            val i = y * W + x
            r[i] += cr; g[i] += cg; b[i] += cb
        }

        /** Scanline fill of a polygon; [f] gets every pixel inside. */
        inline fun poly(xs: FloatArray, ys: FloatArray, f: (Int, Int) -> Unit) {
            val y0 = max(0, floor(ys.min()).toInt())
            val y1 = min(H - 1, floor(ys.max()).toInt())
            val n = xs.size
            val cross = FloatArray(n)
            for (y in y0..y1) {
                val yy = y + 0.5f
                var k = 0
                for (i in 0 until n) {
                    val j = (i + 1) % n
                    val ya = ys[i]; val yb = ys[j]
                    if ((ya <= yy && yb > yy) || (yb <= yy && ya > yy)) {
                        cross[k++] = xs[i] + (yy - ya) / (yb - ya) * (xs[j] - xs[i])
                    }
                }
                java.util.Arrays.sort(cross, 0, k)
                var m = 0
                while (m + 1 < k) {
                    val xa = max(0, floor(cross[m] + 0.5f).toInt())
                    val xb = min(W - 1, floor(cross[m + 1] - 0.5f).toInt())
                    for (x in xa..xb) f(x, y)
                    m += 2
                }
            }
        }

        inline fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, f: (Int, Int, Float, Float) -> Unit) {
            for (y in max(0, floor(cy - ry).toInt())..min(H - 1, floor(cy + ry).toInt())) for (x in max(0, floor(cx - rx).toInt())..min(W - 1, floor(cx + rx).toInt())) {
                val dx = (x + 0.5f - cx) / rx
                val dy = (y + 0.5f - cy) / ry
                if (dx * dx + dy * dy <= 1f) f(x, y, dx, dy)
            }
        }

        /** Takes every pixel to the nearest colour of the palette; an ordered dither (4 x 4) decides between the two nearest, as the grainy pictures of the game do. */
        fun quantize(palette: IntArray): IntArray {
            val pr = FloatArray(palette.size) { ((palette[it] shr 16) and 0xFF).toFloat() }
            val pg = FloatArray(palette.size) { ((palette[it] shr 8) and 0xFF).toFloat() }
            val pb = FloatArray(palette.size) { (palette[it] and 0xFF).toFloat() }
            val out = IntArray(W * H)
            for (y in 0 until H) for (x in 0 until W) {
                val i = y * W + x
                val d = (BAYER[(y and 3) * 4 + (x and 3)] / 16f - 0.47f) * 11f
                val tr = r[i] + d; val tg = g[i] + d; val tb = b[i] + d
                var best = 0
                var bd = Float.MAX_VALUE
                for (k in palette.indices) {
                    val dr = pr[k] - tr; val dg = pg[k] - tg; val db = pb[k] - tb
                    val dd = dr * dr * 0.6f + dg * dg + db * db * 0.8f
                    if (dd < bd) { bd = dd; best = k }
                }
                out[i] = palette[best] or 0xFF000000.toInt()
            }
            return out
        }
    }

    private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

    internal fun hash(n: Int): Float {
        var x = n * 374761393 + 668265263
        x = (x xor (x ushr 13)) * 1274126177
        x = x xor (x ushr 16)
        return (x and 0xFFFF) / 65535f
    }

    internal fun hash2(x: Int, y: Int, seed: Int = 0) = hash(x * 73856093 + y * 19349663 + seed * 83492791)

    /** Smooth value noise 0..1. */
    internal fun noise(x: Float, y: Float, seed: Int = 0): Float {
        val xi = floor(x).toInt(); val yi = floor(y).toInt()
        val fx = x - xi; val fy = y - yi
        val sx = fx * fx * (3f - 2f * fx); val sy = fy * fy * (3f - 2f * fy)
        val a = hash2(xi, yi, seed); val b = hash2(xi + 1, yi, seed)
        val c = hash2(xi, yi + 1, seed); val d = hash2(xi + 1, yi + 1, seed)
        return (a + (b - a) * sx) + ((c + (d - c) * sx) - (a + (b - a) * sx)) * sy
    }

    internal fun fbm(x: Float, y: Float, seed: Int = 0): Float = 0.55f * noise(x, y, seed) + 0.3f * noise(x * 2.1f, y * 2.1f, seed + 1) + 0.15f * noise(x * 4.3f, y * 4.3f, seed + 2)

    // ------------------------------------------------------------------------------------------------------------------ sky and moon

    private const val MOON_X = 84f
    private const val MOON_Y = 17f
    private const val HORIZON = 52

    private fun sky(c: Canvas) {
        for (y in 0 until 66) for (x in 0 until W) {
            val t = min(1f, y / 56f)
            // the storm sky: dark at the top, a cold lighter haze at the horizon, ragged cloud banks stretched across
            val cloud = fbm(x / 46f, y / 9f, 11)
            val bank = fbm(x / 21f + 5f, y / 5f, 31)
            var rr = 11f + 38f * t * t + 22f * cloud * (0.35f + t)
            var gg = 15f + 46f * t * t + 25f * cloud * (0.35f + t)
            var bb = 29f + 58f * t * t + 30f * cloud * (0.35f + t)
            val dark = (bank - 0.5f).coerceAtLeast(0f) * 2f
            rr *= 1f - 0.45f * dark; gg *= 1f - 0.45f * dark; bb *= 1f - 0.4f * dark
            // the moon behind the clouds: a wide glow and the disc, dimmed where a cloud lies in front
            val dx = x - MOON_X; val dy = (y - MOON_Y) * 1.1f
            val d2 = dx * dx + dy * dy
            val veil = 1f - 0.8f * (fbm(x / 17f, y / 6f, 51) - 0.35f).coerceIn(0f, 0.6f) / 0.6f
            val glow = exp(-d2 / (2f * 26f * 26f)) * 80f * (0.45f + 0.55f * veil)
            rr += glow * 0.78f; gg += glow * 0.9f; bb += glow * 1.05f
            if (d2 < 7f * 7f) {
                val k = 0.55f + 0.45f * veil
                rr = 226f * k + rr * (1f - k); gg = 234f * k + gg * (1f - k); bb = 236f * k + bb * (1f - k)
            }
            c.set(x, y, rr, gg, bb)
        }
    }

    // ------------------------------------------------------------------------------------------------------------------ hills, fields, road, gate, path

    private fun ridge(x: Int, base: Float, amp: Float, scale: Float, seed: Int) = base + amp * fbm(x / scale, 3f, seed) + 1.5f * sin(x / 19f + seed)

    /** The path's border lines at row [y] (the path runs from the gate down to the house, getting wider and bending a little to the left). */
    private fun pathLeft(y: Float) = 207f - (y - 91f) * 0.70f - 0.0006f * (y - 91f) * (y - 91f)
    private fun pathRight(y: Float) = 224f + (y - 91f) * 0.46f

    private fun roadCentre(x: Float) = 75f + 2.4f * sin(x / 61f) + 0.01f * (x - 160f)

    private fun landscape(c: Canvas) {
        for (y in HORIZON until H) for (x in 0 until W) {
            val yy = y.toFloat()
            val depth = (yy - HORIZON + 6f) / 60f // 0 at the horizon, grows towards the viewer
            val grain = fbm(x / (4f + 20f / (depth + 0.3f)).coerceAtLeast(2f), yy / (2f + 6f / (depth + 0.3f)), 71)
            // meadows: dark green-grey, getting darker and coarser close to the window
            var rr = 19f + 15f * grain - 3f * depth.coerceAtMost(1.2f)
            var gg = 36f + 22f * grain - 4f * depth.coerceAtMost(1.2f)
            var bb = 32f + 16f * grain - 3f * depth.coerceAtMost(1.2f)
            val moon = (0.55f + 0.45f * fbm(x / 30f, yy / 16f, 9)) * (1.1f - depth * 0.2f)
            rr += 8f * moon; gg += 13f * moon; bb += 14f * moon
            // the lawn near the window is mown in broad stripes
            val stripe = if (depth > 0.7f) 5f * sin(x * 0.13f + yy * 0.045f) * (depth - 0.6f) else 0f
            rr += stripe * 0.6f; gg += stripe; bb += stripe * 0.7f
            c.set(x, y, rr, gg, bb)
        }
        // the far hills in two rows, with a pale haze along their ridge
        for (x in 0 until W) {
            val r1 = ridge(x, 49f, 7f, 58f, 2)
            val r2 = ridge(x, 56f, 5f, 36f, 7)
            for (y in r1.toInt()..HORIZON + 6) {
                val t = (y - r1) / 12f
                c.set(x, y, 17f - 4f * t, 27f - 4f * t, 33f - 3f * t)
            }
            for (y in r1.toInt() - 1..r1.toInt()) c.blend(x, y, 66f, 84f, 100f, 0.35f)
            for (y in r2.toInt()..HORIZON + 11) {
                val t = (y - r2) / 8f
                c.set(x, y, 13f + 4f * t, 24f + 4f * t, 25f + 3f * t)
            }
            for (y in r2.toInt() - 1..r2.toInt()) c.blend(x, y, 40f, 56f, 62f, 0.3f)
        }
        // far trees along the hill line
        for (i in 0 until 26) {
            val x = hash(i * 7 + 3) * W
            val y = 62f + 6f * hash(i * 11 + 5)
            val s = 2.5f + 3f * hash(i * 13 + 1)
            c.ellipse(x, y, s * 1.2f, s, { px, py, dx, dy -> c.set(px, py, 9f + 6f * (1f - dy) * 0.3f, 18f + 7f * (-dy + 1f) * 0.3f, 17f + 5f * (1f - dy) * 0.3f) })
        }
        road(c)
        wallAndGate(c)
        path(c)
    }

    private fun road(c: Canvas) {
        for (x in 0 until W) {
            val cy = roadCentre(x.toFloat())
            val half = 5.5f
            for (y in (cy - half - 3).toInt()..(cy + half + 3).toInt()) {
                val d = abs(y - cy)
                if (d > half + 2.5f) continue
                val edge = d > half
                val grain = fbm(x / 5f, y / 1.7f, 33)
                var rr = 48f + 14f * grain; var gg = 49f + 14f * grain; var bb = 55f + 14f * grain
                if (edge) { rr = 30f + 9f * grain; gg = 38f + 9f * grain; bb = 35f + 9f * grain } // the grass verge
                // the ruts of the carts are a little lighter, the puddles show the sky
                if (!edge && (abs(d - 2.2f) < 0.7f)) { rr += 7f; gg += 7f; bb += 8f }
                if (!edge) {
                    val pud = fbm(x / 9f, y / 2.2f, 91)
                    if (pud > 0.66f) { val k = (pud - 0.66f) * 5f; rr += 38f * k; gg += 52f * k; bb += 70f * k }
                }
                c.set(x, y, rr, gg, bb)
            }
        }
    }

    private fun wallAndGate(c: Canvas) {
        val yTop = 84
        val yBottom = 91
        // the low wall of the park on both sides of the gate
        for (x in 0 until W) {
            if (x in 196..234) continue
            for (y in yTop..yBottom) {
                val row = (y - yTop) / 2
                val stone = hash2(x / 5 + row * 3, row, 5)
                val shade = 0.8f + 0.4f * stone
                val top = if (y == yTop) 1.35f else if (y == yBottom) 0.55f else 1f
                c.set(x, y, 58f * shade * top, 61f * shade * top, 68f * shade * top)
                if ((x + row * 3) % 9 == 0) c.blend(x, y, 24f, 26f, 30f, 0.7f) // the joints
            }
            // a dark shadow where the wall meets the meadow
            c.blend(x, yBottom + 1, 8f, 14f, 14f, 0.5f)
        }
        // the two pillars of the gate and the iron gate between them
        for (p in intArrayOf(196, 228)) {
            for (y in 73..yBottom + 1) for (x in p..p + 6) {
                val face = if (x < p + 4) 1f else 0.62f
                val stone = hash2(x, y / 3, 13)
                c.set(x, y, 66f * face * (0.85f + 0.3f * stone), 69f * face * (0.85f + 0.3f * stone), 76f * face * (0.85f + 0.3f * stone))
            }
            for (x in p - 1..p + 7) { c.set(x, 72, 88f, 91f, 98f); c.set(x, 73, 70f, 73f, 80f) } // the cap
            // the lantern on the pillar: a warm glow
            for (dy in -8..8) for (dx in -8..8) {
                val d = sqrt((dx * dx + dy * dy).toFloat())
                if (d < 8f) { val k = (1f - d / 8f); c.add(p + 3 + dx, 69 + dy, 150f * k * k, 95f * k * k, 38f * k * k) }
            }
            c.set(p + 3, 69, 255f, 224f, 150f); c.set(p + 2, 69, 240f, 190f, 110f); c.set(p + 4, 69, 240f, 190f, 110f); c.set(p + 3, 68, 240f, 190f, 110f); c.set(p + 3, 70, 220f, 160f, 90f)
        }
        for (x in 203..227) {
            if ((x - 203) % 3 == 1) for (y in 78..yBottom) c.set(x, y, 12f, 12f, 16f)
        }
        for (x in 203..227) { c.set(x, 81, 14f, 14f, 18f); c.set(x, 89, 14f, 14f, 18f) }
    }

    private fun path(c: Canvas) {
        val yStart = 91
        for (y in yStart until H) {
            val yy = y.toFloat()
            val l = pathLeft(yy); val r = pathRight(yy)
            val depth = (yy - yStart) / 105f
            val hedge = 3f + 9f * depth
            for (x in max(0, (l - hedge).toInt() - 2)..min(W - 1, (r + hedge).toInt() + 2)) {
                val xf = x + 0.5f
                if (xf in l..r) {
                    // gravel: pale and grainy, with the ruts of the wheels, a pool of moonlight in the middle
                    val grain = hash2(x / 2, y / 2, 17)
                    val coarse = fbm(x / (3f + 8f * depth), yy / (1.6f + 5f * depth), 41)
                    var v = 72f + 26f * coarse + 22f * (grain - 0.5f) + 18f * depth
                    val centre = 1f - abs((xf - (l + r) / 2f) / ((r - l) / 2f))
                    v += 12f * centre
                    val rut = abs(abs(xf - (l + r) / 2f) - (r - l) * 0.2f) < (r - l) * 0.035f + 0.4f
                    if (rut) v -= 14f
                    val edgeDark = (min(xf - l, r - xf) / 3f).coerceIn(0f, 1f)
                    v *= 0.7f + 0.3f * edgeDark
                    c.set(x, y, v * 0.96f, v * 0.97f, v * 0.94f + 4f)
                } else if (xf in (l - hedge)..l || xf in r..(r + hedge)) {
                    // the hedges on both sides: dark, leafy, a rim of moonlight on the side that faces the path
                    val leaf = hash2(x / 2, y / 2, 23)
                    val side = if (xf < l) (xf - (l - hedge)) / hedge else ((r + hedge) - xf) / hedge
                    var rr = 9f + 10f * leaf; var gg = 20f + 17f * leaf; var bb = 15f + 11f * leaf
                    val rim = (1f - side).coerceAtLeast(0f)
                    rr += 14f * rim * leaf; gg += 22f * rim * leaf; bb += 22f * rim * leaf
                    c.set(x, y, rr, gg, bb)
                }
            }
        }
        // puddles on the path that show the sky
        for (i in 0 until 7) {
            val y = 101f + 79f * hash(i * 17 + 2)
            val cx = (pathLeft(y) + pathRight(y)) / 2f + (hash(i * 5 + 9) - 0.5f) * (pathRight(y) - pathLeft(y)) * 0.6f
            val s = 2f + (y - 91f) * 0.07f
            c.ellipse(cx, y, s * 2f, s * 0.55f, { px, py, dx, dy ->
                val k = 1f - (dx * dx + dy * dy)
                c.blend(px, py, 70f + 40f * (0.5f + dx * 0.5f), 86f + 40f * (0.5f + dx * 0.5f), 108f + 40f * (0.5f + dx * 0.5f), 0.6f + 0.3f * k)
            })
        }
    }

    // ------------------------------------------------------------------------------------------------------------------ the car on the road

    /** The car of the story, parked on the road in front of the gate with its lights on (it looks like the one of the introduction). */
    private fun car(c: Canvas) {
        val cx = 112f
        val cy = 77f
        // the light of the headlamps on the road, to the left
        for (x in 20..(cx - 8).toInt()) {
            val d = (cx - 8f - x) / 90f
            if (d > 1f) continue
            val spread = 1.5f + 5f * d
            for (y in (cy + 1f - spread).toInt()..(cy + 1f + spread).toInt()) {
                val k = (1f - d) * (1f - abs(y - cy - 1f) / (spread + 0.1f)).coerceAtLeast(0f)
                c.add(x, y, 82f * k, 76f * k, 44f * k)
            }
        }
        // the body: long and black with the mudguards, the roof a bit lighter with the dark windows
        c.poly(floatArrayOf(cx - 17f, cx - 14f, cx + 15f, cx + 17f, cx + 16f, cx - 16f), floatArrayOf(cy + 1.5f, cy - 2f, cy - 2f, cy + 1f, cy + 4.5f, cy + 4.5f)) { x, y ->
            val k = (y - (cy - 2f)) / 6.5f
            c.set(x, y, 14f + 12f * (1f - k), 14f + 12f * (1f - k), 18f + 14f * (1f - k))
        }
        c.poly(floatArrayOf(cx - 8f, cx - 5f, cx + 7f, cx + 10f), floatArrayOf(cy - 2f, cy - 5.5f, cy - 5.5f, cy - 2f)) { x, y -> c.set(x, y, 20f + 4f * (x - cx + 8f) / 18f, 22f, 28f) }
        for (x in (cx - 6f).toInt()..(cx + 8f).toInt()) for (y in (cy - 4.5f).toInt()..(cy - 2.5f).toInt()) c.set(x, y, 34f, 46f, 62f) // the windows
        c.set((cx + 1).toInt(), (cy - 4).toInt(), 20f, 24f, 32f); c.set((cx + 1).toInt(), (cy - 3).toInt(), 20f, 24f, 32f) // the pillar between them
        for (x in (cx - 16f).toInt()..(cx + 15f).toInt()) c.blend(x, (cy - 2f).toInt(), 150f, 160f, 175f, 0.35f) // a gleam along the shoulder
        // the wheels and the shadow under the car
        for (wx in floatArrayOf(cx - 10f, cx + 9f)) c.ellipse(wx, cy + 4.5f, 3.4f, 2.6f, { px, py, _, _ -> c.set(px, py, 8f, 8f, 10f) })
        for (x in (cx - 17f).toInt()..(cx + 17f).toInt()) c.blend(x, (cy + 7f).toInt(), 6f, 10f, 10f, 0.55f)
        // headlamps: two bright points with a glow
        for (lamp in floatArrayOf(cy + 0.4f)) {
            for (dy in -6..6) for (dx in -9..3) {
                val d = sqrt((dx * dx + dy * dy).toFloat())
                if (d < 7f) { val k = 1f - d / 7f; c.add((cx - 17f).toInt() + dx, lamp.toInt() + dy, 120f * k * k, 108f * k * k, 52f * k * k) }
            }
            c.set((cx - 17f).toInt(), lamp.toInt(), 255f, 244f, 190f); c.set((cx - 16f).toInt(), lamp.toInt(), 240f, 226f, 170f); c.set((cx - 17f).toInt(), lamp.toInt() + 1, 240f, 226f, 170f)
        }
        // the tail lamp: a dull red dot
        c.set((cx + 16f).toInt(), (cy + 1.5f).toInt(), 190f, 40f, 36f)
    }

    // ------------------------------------------------------------------------------------------------------------------ trees and the porch roof

    /** A tree seen from above: a big dark crown of leaves with the moonlight on the side that faces the moon, a shadow below it. */
    private fun crown(c: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, seed: Int) {
        // the shadow on the ground
        c.ellipse(cx + rx * 0.08f, cy + ry * 0.42f, rx * 1.02f, ry * 0.62f, { px, py, _, _ -> c.blend(px, py, 4f, 9f, 8f, 0.6f) })
        c.ellipse(cx, cy, rx, ry, { px, py, dx, dy ->
            val cell = hash2(px / 2, py / 2, seed)
            val lobe = fbm(px / 5f, py / 4f, seed + 3)
            val facing = ((-dx * 0.55f - dy * 0.85f)).coerceAtLeast(0f)
            val rim = (1f - sqrt(dx * dx + dy * dy)).coerceIn(0f, 1f)
            var rr = 10f + 9f * lobe + 6f * cell
            var gg = 22f + 18f * lobe + 11f * cell
            var bb = 18f + 14f * lobe + 9f * cell
            val lit = facing * (0.4f + 0.6f * cell) * (0.5f + lobe) * (1f - rim * 0.2f)
            rr += 34f * lit; gg += 58f * lit; bb += 52f * lit
            // the edge of the crown is ragged: leaves stand out
            if (sqrt(dx * dx + dy * dy) > 0.86f && cell > 0.55f) { rr *= 0.55f; gg *= 0.55f; bb *= 0.55f }
            c.set(px, py, rr, gg, bb)
        })
    }

    private fun trees(c: Canvas) {
        // behind the wall: a row of crowns, far away
        crown(c, 38f, 70f, 13f, 8f, 101)
        crown(c, 66f, 67f, 9f, 6f, 102)
        crown(c, 262f, 66f, 11f, 7f, 103)
        crown(c, 296f, 70f, 14f, 8f, 104)
        crown(c, 148f, 66f, 7f, 5f, 105)
        // the nearer ones that stand in the park beside the path
        crown(c, 255f, 118f, 25f, 20f, 111)
        crown(c, 24f, 134f, 38f, 31f, 112)
        crown(c, 88f, 112f, 18f, 15f, 113)
        // two bare branches that reach in from the left, as of the old tree next to the house
        for (i in 0 until 3) {
            var x = 0f
            var y = 36f + i * 17f
            var a = -0.18f + 0.1f * i
            while (x < 62f - i * 12f) {
                for (t in 0..1) c.set(x.toInt(), (y + t).toInt(), 7f, 8f, 11f)
                x += 1f; y += a; a += (hash(x.toInt() + i * 40) - 0.5f) * 0.22f
                if (hash(x.toInt() * 3 + i) > 0.93f) { // a twig
                    var tx = x; var ty = y; val ta = if (hash(x.toInt() + i) > 0.5f) -0.8f else 0.8f
                    for (s in 0 until 8) { c.set(tx.toInt(), ty.toInt(), 8f, 9f, 13f); tx += 1f; ty += ta * 0.6f }
                }
            }
        }
    }

    /** The roof of the porch right below the window: slate tiles in rows, the ridge and the edge catch the light of the moon. */
    private fun porch(c: Canvas) {
        val xs = floatArrayOf(150f, 252f, 292f, 110f)
        val ys = floatArrayOf(166f, 166f, 200f, 200f)
        c.poly(xs, ys) { x, y ->
            val row = ((y - 166) / 3.4f)
            val rowF = row - floor(row)
            val tile = hash2((x + (floor(row).toInt() and 1) * 5) / 7, floor(row).toInt(), 37)
            var v = 24f + 14f * tile
            if (rowF < 0.2f) v += 20f // the lower edge of each row of tiles catches the light
            if (rowF > 0.8f) v -= 9f
            val side = (x - 110f) / 182f
            v *= 0.85f + 0.3f * side
            c.set(x, y, v * 0.95f, v * 0.98f, v * 1.12f + 3f)
        }
        // the ridge: a pale line with the moonlight
        for (x in 150..252) { c.set(x, 165, 78f, 86f, 100f); c.set(x, 166, 56f, 62f, 74f) }
        // the gutter at the lower edge
        for (x in 110..292) { val y = 199; c.set(x, y, 16f, 14f, 18f); c.set(x, y - 1, 52f, 52f, 60f) }
    }

    /** Ground mist: pale bands that lie in the hollows along the road and in the park. */
    private fun mist(c: Canvas) {
        for (y in 56 until 150) for (x in 0 until W) {
            val band = exp(-((y - 92f) * (y - 92f)) / (2f * 20f * 20f))
            val n = fbm(x / 38f, y / 9f, 61)
            val k = (n - 0.42f).coerceAtLeast(0f) * 1.7f * band
            if (k > 0f) c.blend(x, y, 52f, 64f, 70f, k * 0.5f)
        }
    }

    // ------------------------------------------------------------------------------------------------------------------ the window frame

    /** The window of the attic: the heavy wooden frame round the picture and the cross of two bars that divides it into four panes (the window has 2 x 2 panes). */
    private fun frame(c: Canvas, broken: Boolean) {
        fun wood(x: Int, y: Int, light: Float) {
            val grain = fbm(x / 22f, y / 1.5f, 3) // the grain runs along the bar
            val v = (0.78f + 0.5f * grain) * light
            c.set(x, y, 78f * v, 52f * v, 34f * v)
        }
        val left = 9; val right = W - 10; val top = 8; val bottom = 184
        for (y in 0 until H) for (x in 0 until W) {
            val inside = x in left until right && y in top until bottom
            val onBarV = x in 158..161
            val onBarH = y in 94..97
            if (inside && !onBarV && !onBarH) continue
            // the light falls from the upper left: the edges that face it are bright
            var light = 1f
            if (inside) { // the cross
                light = if (onBarV) (if (x == 158) 1.35f else if (x == 161) 0.6f else 1f) else (if (y == 94) 1.35f else if (y == 97) 0.6f else 1f)
            } else {
                if (x == left - 1 && y in top - 1..bottom) light = 0.55f
                else if (x == right && y in top - 1..bottom) light = 1.3f
                else if (y == top - 1 && x in left..right) light = 0.55f
                else if (y == bottom && x in left - 1..right) light = 1.35f
                else if (y > bottom + 4) light = 0.7f
                else if (x < left - 1 || x > right || y < top - 1 || y > bottom) light = 0.92f - 0.25f * (abs(x - W / 2f) / (W / 2f))
            }
            wood(x, y, light)
        }
        // the sill: the top face is lighter, the front face dark
        for (x in 0 until W) {
            for (y in bottom + 1..bottom + 5) wood(x, y, 1.3f - 0.05f * (y - bottom))
            c.set(x, bottom + 5, 20f, 13f, 9f)
        }
        // the glass: a faint diagonal gleam over the four panes, drops of the rain that stand on it
        for (k in 0 until 9) {
            val x0 = 20f + k * 33f
            c.poly(floatArrayOf(x0, x0 + 4f, x0 - 36f, x0 - 40f), floatArrayOf(10f, 10f, 183f, 183f)) { x, y ->
                if (x in left until right && y in top until bottom && x !in 158..161 && y !in 94..97) {
                    c.add(x, y, 3.5f, 4.5f, 6.5f)
                }
            }
        }
        for (i in 0 until 70) {
            val x = left + 2 + hash(i * 3 + 1) * (right - left - 4)
            val y = top + 2 + hash(i * 5 + 4) * (bottom - top - 4)
            val xi = x.toInt(); val yi = y.toInt()
            if (xi in 158..161 || yi in 94..97) continue
            c.add(xi, yi, 26f, 34f, 46f)
            c.add(xi, yi + 1, 10f, 14f, 20f)
        }
        if (broken) {
            // the pane has been smashed: jagged teeth of glass stand in the frame at the corners of the four panes, and the rain comes in
            for (pane in 0 until 4) {
                val x0 = if (pane % 2 == 0) left else 162
                val x1 = if (pane % 2 == 0) 157 else right - 1
                val y0 = if (pane < 2) top else 98
                val y1 = if (pane < 2) 93 else bottom - 1
                for (corner in 0 until 4) {
                    val cxp = if (corner % 2 == 0) x0 else x1
                    val cyp = if (corner < 2) y0 else y1
                    val sx = if (corner % 2 == 0) 1 else -1
                    val sy = if (corner < 2) 1 else -1
                    for (s in 0 until 4) {
                        val len = 5 + (hash(pane * 31 + corner * 7 + s) * 14f).toInt()
                        val ang = 0.2f + 0.5f * hash(pane * 17 + corner * 3 + s)
                        for (t in 0 until len) {
                            val px = cxp + sx * (t * cos(ang * 1.4f) + s * 2f).toInt()
                            val py = cyp + sy * (t * sin(ang * 1.4f) * 0.6f + s * 1.5f).toInt()
                            val w = (len - t) / len.toFloat()
                            c.blend(px, py, 130f, 150f, 165f, 0.8f * w)
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------------ moving light

    /**
     * The storm outside, drawn over [base] (the picture, [W] x [H], scaled by [scale]) into [out] at time [t] seconds: clouds that drift across the moon, rain that falls and
     * runs down the glass, banks of mist that drift, the flash of lightning that lights everything.
     */
    fun render(base: IntArray, out: IntArray, scale: Int, t: Float, flash: Float, broken: Boolean) {
        val width = W * scale
        val height = H * scale
        System.arraycopy(base, 0, out, 0, base.size)
        // clouds drift over the sky: the sky is darkened and lightened by a moving noise; the moon dims and shines again (computed per pixel of the picture, written to its block)
        for (py in 0 until 62) for (px in 0 until W) {
            val drift = fbm(px / 30f - t * 0.9f, py / 8f, 81)
            val k = 0.82f + 0.4f * (drift - 0.4f)
            val moonD = sqrt((px - MOON_X) * (px - MOON_X) + (py - MOON_Y) * (py - MOON_Y))
            val kk = if (moonD < 9f) k * ((0.7f + 0.3f * fbm(px / 9f - t * 1.4f, py / 5f, 83) / 0.5f).coerceIn(0.55f, 1.2f)) else k
            for (dy in 0 until scale) for (dx in 0 until scale) {
                val i = (py * scale + dy) * width + px * scale + dx
                out[i] = scaleColor(base[i], kk)
            }
        }
        // mist: bands that move across the meadow
        for (py in 58 until 142) for (px in 0 until W) {
            val band = exp(-((py - 100f) * (py - 100f)) / (2f * 22f * 22f))
            val n = fbm(px / 34f + t * 0.55f, py / 8f + 4f, 87)
            val m = (n - 0.45f).coerceAtLeast(0f) * 1.4f * band
            for (dy in 0 until scale) for (dx in 0 until scale) {
                val i = (py * scale + dy) * width + px * scale + dx
                out[i] = if (m > 0f) blendColor(base[i], 58, 70, 78, m * 0.55f) else base[i]
            }
        }
        // the flash lights the whole scene, cold and bright (the window pane glares)
        if (flash > 0.02f) {
            for (y in 0 until height) for (x in 0 until width) {
                val i = y * width + x
                out[i] = addColor(out[i], (34 * flash).toInt(), (46 * flash).toInt(), (66 * flash).toInt())
            }
        }
        // the rain: streaks that fall slanted across the whole view, near ones fast and bright, far ones slow and faint
        for (i in 0 until 130) {
            val near = hash(i * 3 + 1)
            val speed = 110f + 150f * near
            val period = (H + 30f) / speed
            val ph = ((t + hash(i * 7 + 2) * period * 5f) % period) / period
            val y = ph * (H + 30f) - 15f
            val x = (hash(i * 11 + 3) * (W + 80f) - 30f) - y * 0.22f
            val len = 3f + 5f * near
            val a = 0.22f + 0.3f * near + 0.2f * flash
            var s = 0f
            while (s < len) {
                val px = ((x - s * 0.22f) * scale).toInt(); val py = ((y - s) * scale).toInt()
                val f = (1f - s / len) * a
                for (dy in 0 until scale) for (dx in 0 until scale) {
                    val xx = px + dx; val yy = py + dy
                    if (xx in 0 until width && yy in 0 until height && inPane(xx / scale, yy / scale)) out[yy * width + xx] = blendColor(out[yy * width + xx], 170, 190, 215, f)
                }
                s += 1f / scale
            }
        }
        // drops that run down the glass of the panes (not when the glass is gone)
        if (!broken) for (i in 0 until 16) {
            val pane = i % 4
            val x0 = if (pane % 2 == 0) 12f else 164f
            val x1 = if (pane % 2 == 0) 155f else 305f
            val y0 = if (pane < 2) 10f else 99f
            val y1 = if (pane < 2) 91f else 181f
            val h = hash(i * 13 + 6)
            val speed = 5f + 9f * hash(i * 17 + 1)
            val period = (y1 - y0) / speed + 2f + 3f * h
            val y = ((t + h * period * 3f) % period) * speed
            if (y > y1 - y0) continue
            val x = x0 + 4f + hash(i * 19 + 8) * (x1 - x0 - 8f) + 1.2f * sin(t * 0.9f + i)
            for (k in 0..4) {
                val yy = y0 + y - k
                if (yy < y0) break
                val f = (0.6f - k * 0.1f).coerceAtLeast(0f) + 0.25f * flash
                for (dy in 0 until scale) for (dx in 0 until scale) {
                    val xx = (x * scale).toInt() + dx; val y2 = (yy * scale).toInt() + dy
                    if (xx in 0 until width && y2 in 0 until height) out[y2 * width + xx] = blendColor(out[y2 * width + xx], 190, 210, 235, f)
                }
            }
        }
    }

    /** Is the pixel (picture coordinates) in one of the four panes (not in the frame or on the cross)? Rain and the gleam of the flash show only there. */
    private fun inPane(x: Int, y: Int) = x in 9 until W - 10 && y in 8 until 184 && x !in 158..161 && y !in 94..97

    private fun scaleColor(c: Int, k: Float): Int {
        val r = (((c shr 16) and 0xFF) * k).toInt().coerceIn(0, 255)
        val g = (((c shr 8) and 0xFF) * k).toInt().coerceIn(0, 255)
        val b = ((c and 0xFF) * k).toInt().coerceIn(0, 255)
        return (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
    }

    private fun blendColor(c: Int, r: Int, g: Int, b: Int, a: Float): Int {
        val k = a.coerceIn(0f, 1f)
        val nr = (((c shr 16) and 0xFF) * (1f - k) + r * k).toInt()
        val ng = (((c shr 8) and 0xFF) * (1f - k) + g * k).toInt()
        val nb = ((c and 0xFF) * (1f - k) + b * k).toInt()
        return (c and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
    }

    private fun addColor(c: Int, r: Int, g: Int, b: Int): Int {
        val nr = min(255, ((c shr 16) and 0xFF) + r)
        val ng = min(255, ((c shr 8) and 0xFF) + g)
        val nb = min(255, (c and 0xFF) + b)
        return (c and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
    }
}
