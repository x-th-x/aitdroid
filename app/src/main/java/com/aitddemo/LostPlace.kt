package com.aitddemo

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * "Lost Place" (NOT in the original game; a prototype on this branch): the attic as an abandoned place of today. The pictures of the cameras are changed at runtime by code (nothing of the
 * user's pictures is stored). Nothing is pasted over the picture: the whole picture gets one light (the daylight through the broken window falls on the floor as a patch with the shadow
 * of the window cross, a haze of dust in the air, a glow round the window), the surfaces are changed in their own material (damp stains, dirt in the joints, dust, rot), and the few
 * things that lie about (boards, heaps of plaster, footprints, a puddle) are placed on the floor in world coordinates, so they have the size and the foreshortening of the room's own
 * perspective. The result is taken back to the colours of the game's palette. Only the first picture of the attic (camera 0) has its own light and things so far; the other pictures get
 * the grade, the dust and the dirt.
 */
object LostPlace {
    private val W = WindowView.W
    private val H = WindowView.H

    /** The picture [photo] (320 x 200 ARGB of the game) of camera [camera] of the attic as a lost place, in the colours of [palette]. [cam] is the camera of the picture. */
    fun paint(photo: IntArray, palette: IntArray, camera: Int, cam: AtticCameraDef? = null): IntArray {
        val c = WindowView.Canvas()
        for (i in photo.indices) { c.r[i] = ((photo[i] shr 16) and 0xFF).toFloat(); c.g[i] = ((photo[i] shr 8) and 0xFF).toFloat(); c.b[i] = (photo[i] and 0xFF).toFloat() }
        val floor = if (cam != null) Floor(cam) else null
        surfaces(c, floor)
        if (cam != null && floor != null) {
            // the pixels that show boards (not the things that stand on them): what lies on the floor is drawn only there
            val board = boardMask(photo)
            val wall = wallMask(photo)
            slits(c, cam, wall)
            objects(c, photo, camera)
            furniture(c, photo, camera)
            if (camera == 0) cloth(c, photo)
            light(c, floor, photo, cam)
            mould(c, floor, board)
            holes(c, floor, board)
            things(c, floor, cam, board)
        }
        finish(c)
        return c.quantize(palette)
    }

    /** The pixels of the boards: the brown colours of the floor that are grainy (the floor is dithered; the faces of crates and cabinets are smoother). */
    private fun boardMask(photo: IntArray): BooleanArray {
        val lum = FloatArray(W * H) { val p = photo[it]; 0.3f * ((p shr 16) and 0xFF) + 0.59f * ((p shr 8) and 0xFF) + 0.11f * (p and 0xFF) }
        return BooleanArray(W * H) { i ->
            if (!isBoard(photo[i])) return@BooleanArray false
            val x = i % W; val y = i / W
            if (x < 1 || y < 1 || x >= W - 1 || y >= H - 1) return@BooleanArray false
            val d = (abs(lum[i] - lum[i - 1]) + abs(lum[i] - lum[i + 1]) + abs(lum[i] - lum[i - W]) + abs(lum[i] - lum[i + W])) / 4f
            d > 3.2f
        }
    }

    /** The pixels of the wall of boards: a pink-brown that is not red like the beams and not brown like the floor. */
    private fun wallMask(photo: IntArray): BooleanArray = BooleanArray(W * H) { i ->
        val p = photo[i]
        val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
        b >= g && r > g * 1.12f && r < g * 2.2f && (0.3f * r + 0.59f * g + 0.11f * b) in 38f..130f
    }

    /** Does this colour of the original picture belong to the boards of the floor (dark brown, not orange, not green) and not to a thing that stands on them? */
    private fun isBoard(p: Int): Boolean {
        val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
        val lum = 0.3f * r + 0.59f * g + 0.11f * b
        return lum in 30f..112f && r >= g && r - g < 55 && r - b in 10..74 && g >= b
    }

    /** What the figures and things of the room look like: the colours of the bodies are taken towards grey, cooler and darker, like the pictures. */
    fun gradeColor(c: Int): Int {
        val r = ((c shr 16) and 0xFF).toFloat(); val g = ((c shr 8) and 0xFF).toFloat(); val b = (c and 0xFF).toFloat()
        val lum = 0.3f * r + 0.59f * g + 0.11f * b
        val nr = ((lum + (r - lum) * 0.88f) * 0.96f + 3f).toInt().coerceIn(0, 255)
        val ng = ((lum + (g - lum) * 0.88f) * 0.97f + 3f).toInt().coerceIn(0, 255)
        val nb = ((lum + (b - lum) * 0.88f) * 0.98f + 4f).toInt().coerceIn(0, 255)
        return (c and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
    }

    // ------------------------------------------------------------------------------------------------------------------ the floor in world coordinates

    /** For every pixel of the picture that shows the floor of the room: the place on the floor (x, z in engine units); NaN elsewhere. */
    private class Floor(cam: AtticCameraDef) {
        val x = FloatArray(W * H) { Float.NaN }
        val z = FloatArray(W * H) { Float.NaN }

        init {
            val proj = AtticProjector(cam)
            val o = FloatArray(3)
            val step = 120
            val xs = (-7800..7800 step step).toList()
            val zs = (-5000..5000 step step).toList()
            val px = Array(xs.size) { FloatArray(zs.size) }
            val py = Array(xs.size) { FloatArray(zs.size) }
            val ok = Array(xs.size) { BooleanArray(zs.size) }
            for (i in xs.indices) for (j in zs.indices) {
                ok[i][j] = proj.project(xs[i].toFloat(), 0f, zs[j].toFloat(), o)
                px[i][j] = o[0]; py[i][j] = o[1]
            }
            val canvas = WindowView.Canvas()
            for (i in 0 until xs.size - 1) for (j in 0 until zs.size - 1) {
                if (!ok[i][j] || !ok[i + 1][j] || !ok[i + 1][j + 1] || !ok[i][j + 1]) continue
                // the corners (i, j), (i+1, j), (i, j+1) span the cell on the screen: the place inside it is found by its two parts along the sides
                val x00 = px[i][j]; val y00 = py[i][j]
                val ax = px[i + 1][j] - x00; val ay = py[i + 1][j] - y00
                val bx = px[i][j + 1] - x00; val by = py[i][j + 1] - y00
                val det = ax * by - ay * bx
                if (abs(det) < 1e-4f) continue
                val x0w = xs[i].toFloat(); val z0w = zs[j].toFloat()
                canvas.poly(floatArrayOf(px[i][j], px[i + 1][j], px[i + 1][j + 1], px[i][j + 1]), floatArrayOf(py[i][j], py[i + 1][j], py[i + 1][j + 1], py[i][j + 1])) { sx, sy ->
                    val rx = sx + 0.5f - x00; val ry = sy + 0.5f - y00
                    val u = ((rx * by - ry * bx) / det).coerceIn(-0.2f, 1.2f)
                    val v = ((ax * ry - ay * rx) / det).coerceIn(-0.2f, 1.2f)
                    x[sy * W + sx] = x0w + u * step; z[sy * W + sx] = z0w + v * step
                }
            }
        }

        fun has(i: Int) = !x[i].isNaN()
    }

    // ------------------------------------------------------------------------------------------------------------------ the material of the surfaces

    private fun surfaces(c: WindowView.Canvas, floor: Floor?) {
        // where the floor begins in every column of the picture: above it lies the wall (or a post or a beam)
        val wallY = IntArray(W) { x ->
            var top = -1
            if (floor != null) for (y in 0 until H) if (floor.has(y * W + x)) { top = y; break }
            if (top < 0) 64 else top.coerceIn(30, 120)
        }
        for (y in 0 until H) for (x in 0 until W) {
            val i = y * W + x
            var r = c.r[i]; var g = c.g[i]; var b = c.b[i]
            // the colours are drained: a little warmth is left in the wood
            val lum = 0.3f * r + 0.59f * g + 0.11f * b
            r = lum + (r - lum) * 0.84f; g = lum + (g - lum) * 0.84f; b = lum + (b - lum) * 0.84f
            // the room is dim: the only light is the daylight that comes in through the window, cool and soft
            r *= 1.0f; g *= 1.0f; b *= 1.0f
            if (floor != null && floor.has(i)) {
                val fx = floor.x[i]; val fz = floor.z[i]
                // damp stains on the boards: large soft shapes that are darker, with a pale tide mark at the edge where the water dried
                val s = WindowView.fbm(fx / 1300f, fz / 1000f, 501)
                val stain = smooth(0.5f, 0.62f, s)
                val rim = smooth(0.48f, 0.52f, s) * (1f - smooth(0.52f, 0.58f, s))
                val k = 1f - 0.30f * stain + 0.10f * rim
                r *= k; g *= k; b *= k
                // the dirt in the joints and the wear of the planks: a fine noise along the boards
                val grain = 0.9f + 0.2f * WindowView.fbm(fx / 80f, fz / 900f, 502)
                r *= grain; g *= grain; b *= grain
                // dust: a matte veil over the boards that is thicker where nobody has walked (the walls, the corners)
                val edge = smooth(2800f, 5200f, abs(fx)) * 0.5f + smooth(2500f, 4800f, fz) * 0.5f
                val dust = 0.03f + 0.07f * edge + 0.03f * WindowView.fbm(fx / 400f, fz / 400f, 503)
                r += (126f - r) * dust; g += (124f - g) * dust; b += (116f - b) * dust
            } else if (y < wallY[x]) {
                // the wall of boards and the roof: the damp comes from above - dark where the water ran down, mould at the bottom, the grime of the beams
                val above = 1f - (y / wallY[x].toFloat())
                val streak = WindowView.fbm(x / 14f, y / 60f, 511) // long vertical streaks
                val blot = smooth(0.52f, 0.64f, WindowView.fbm(x / 33f, y / 14f, 512))
                val k = 1f - (0.08f * above + 0.15f * smooth(0.45f, 0.7f, streak) + 0.18f * blot)
                r *= k; g *= k; b *= k
                if (y >= wallY[x] - 18) { // mould and salts at the foot of the wall
                    val m = smooth(0.5f, 0.7f, WindowView.fbm(x / 9f, y / 5f, 513)) * ((y - (wallY[x] - 18f)) / 18f)
                    r += (50f - r) * 0.4f * m; g += (66f - g) * 0.4f * m; b += (48f - b) * 0.4f * m
                }
            } else {
                // what stands in the room (the posts, the table, the chair): a layer of dust on top of it
                val dust = 0.02f + 0.03f * WindowView.fbm(x / 20f, y / 20f, 521)
                r += (118f - r) * dust; g += (116f - g) * dust; b += (108f - b) * dust
            }
            c.r[i] = r; c.g[i] = g; c.b[i] = b
        }
    }

    private fun smooth(a: Float, b: Float, v: Float): Float {
        val t = ((v - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    // ------------------------------------------------------------------------------------------------------------------ the light

    // the daylight comes in through the window in the back wall (z = 5000), x in -750..750, y (up is negative) -2000..-800, falling into the room and down
    private const val SUN_X = 0.2f
    private const val SUN_Y = 0.25f
    private const val SUN_Z = -0.9f

    /** How much of the daylight reaches the floor at (x, z): 0 = shade, 1 = the full patch of sun; the window cross throws its shadow into the patch. */
    private fun sunAt(fx: Float, fz: Float): Float {
        val t = (fz - 5000f) / SUN_Z // how far along the ray from the window plane to the floor (a negative step in z)
        val xw = fx - SUN_X * t
        val yw = -SUN_Y * t
        val inX = smooth(-760f, -700f, xw) * (1f - smooth(700f, 760f, xw))
        val inY = smooth(-2010f, -1950f, yw) * (1f - smooth(-850f, -790f, yw))
        var s = inX * inY
        // the cross of the frame: a vertical and a horizontal bar, 60 units wide
        val bar = max(1f - smooth(30f, 90f, abs(xw)), 1f - smooth(30f, 90f, abs(yw + 1400f)))
        s *= 1f - 0.85f * bar
        // the broken glass and the rot of the frame: the edge of the patch is not clean
        s *= 0.82f + 0.18f * WindowView.fbm(fx / 140f, fz / 140f, 531)
        return s
    }

    private fun light(c: WindowView.Canvas, floor: Floor, photo: IntArray, cam: AtticCameraDef) {
        for (y in 0 until H) for (x in 0 until W) {
            val i = y * W + x
            if (!floor.has(i)) continue
            // things that stand on the floor (the chair, the table) are not boards: the patch of sun is not drawn over them (they are the light, warm, saturated parts of the picture)
            val pr = (photo[i] shr 16) and 0xFF; val pg = (photo[i] shr 8) and 0xFF; val pb = photo[i] and 0xFF
            if ((0.3f * pr + 0.59f * pg + 0.11f * pb) > 104f || pr - pb > 62 || pg > pr + 6) continue
            val s = sunAt(floor.x[i], floor.z[i])
            if (s <= 0.01f) continue
            // sunlit boards: brighter and warmer than the shade, broken up by the leaves of the tree outside
            val dapple = (0.35f + 0.9f * WindowView.fbm(floor.x[i] / 230f, floor.z[i] / 230f, 535)).coerceIn(0.2f, 1.1f)
            val k = s * dapple
            c.r[i] = c.r[i] * (1f + 0.8f * k) + 13f * k
            c.g[i] = c.g[i] * (1f + 0.8f * k) + 15f * k
            c.b[i] = c.b[i] * (1f + 0.75f * k) + 15f * k
        }
        val proj = AtticProjector(cam)
        val o = FloatArray(3)
        if (!proj.project(0f, -1400f, 5000f, o)) return
        val wx = o[0]; val wy = o[1]
        if (wx < -40f || wx > W + 40f || wy < -40f || wy > H + 40f) return // the window is not in this picture
        window(c, proj)
        // a shaft of light in the dusty air: from the window to the patch, a pale haze with streaks
        val centre = patchCentre(floor)
        val tx = centre[0]; val ty = centre[1]
        val dx = tx - wx; val dy = ty - wy
        val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
        for (y in 0 until H) for (x in 0 until W) {
            val px = x - wx; val py = y - wy
            val along = (px * dx + py * dy) / (len * len)
            if (along < 0f || along > 1.05f) continue
            val across = abs(px * dy - py * dx) / len
            val width = 5f + 34f * along
            if (across > width) continue
            val k = (1f - across / width) * (1f - 0.6f * along) * (0.7f + 0.6f * WindowView.fbm(x / 11f + along * 6f, y / 6f, 541))
            val i = y * W + x
            c.r[i] += 30f * k; c.g[i] += 32f * k; c.b[i] += 30f * k
        }
        // the glow round the window: the light is stronger than the picture can show
        val y0 = (wy - 24f).toInt().coerceAtLeast(0); val y1 = (wy + 24f).toInt().coerceAtMost(H - 1)
        val x0 = (wx - 40f).toInt().coerceAtLeast(0); val x1 = (wx + 40f).toInt().coerceAtMost(W - 1)
        for (y in y0..y1) for (x in x0..x1) {
            val ddx = (x - wx) / 22f; val ddy = (y - wy) / 14f
            val d = sqrt(ddx * ddx + ddy * ddy)
            if (d > 1.9f) continue
            val k = exp(-d * d * 1.3f)
            val i = y * W + x
            c.r[i] += 70f * k; c.g[i] += 74f * k; c.b[i] += 74f * k
        }
    }

    /** The middle of the patch of sun on the floor, in pixels of the picture. */
    private fun patchCentre(floor: Floor): FloatArray {
        var sx = 0f; var sy = 0f; var sw = 0f
        for (y in 62 until H step 2) for (x in 0 until W step 2) {
            val i = y * W + x
            if (!floor.has(i)) continue
            val s = sunAt(floor.x[i], floor.z[i])
            sx += x * s; sy += y * s; sw += s
        }
        return if (sw > 0f) floatArrayOf(sx / sw, sy / sw) else floatArrayOf(160f, 110f)
    }

    /** The window itself, seen through the camera: the glass is gone, there is pale sky in the frame, the cross of the frame is dark and rotten. */
    private fun window(c: WindowView.Canvas, proj: AtticProjector) {
        val o = FloatArray(3)
        fun quad(x1: Float, x2: Float, y1: Float, y2: Float, f: (Int, Int, Float) -> Unit) {
            val xs = FloatArray(4); val ys = FloatArray(4)
            val corners = arrayOf(floatArrayOf(x1, y1), floatArrayOf(x2, y1), floatArrayOf(x2, y2), floatArrayOf(x1, y2))
            for ((k, p) in corners.withIndex()) { if (!proj.project(p[0], p[1], 5000f, o)) return; xs[k] = o[0]; ys[k] = o[1] }
            val top = ys.min(); val h = (ys.max() - top).coerceAtLeast(1f)
            c.poly(xs, ys) { x, y -> f(x, y, (y - top) / h) }
        }
        // the sky in the opening
        quad(-750f, 750f, -2000f, -800f) { x, y, f -> c.set(x, y, 206f - 26f * f, 214f - 24f * f, 218f - 20f * f) }
        // the cross of the frame, rotten and dark; the frame all round
        quad(-35f, 35f, -2000f, -800f) { x, y, _ -> c.blend(x, y, 38f, 32f, 28f, 0.92f) }
        quad(-750f, 750f, -1435f, -1365f) { x, y, _ -> c.blend(x, y, 38f, 32f, 28f, 0.92f) }
        quad(-820f, -750f, -2070f, -730f) { x, y, _ -> c.blend(x, y, 34f, 28f, 24f, 0.95f) }
        quad(750f, 820f, -2070f, -730f) { x, y, _ -> c.blend(x, y, 34f, 28f, 24f, 0.95f) }
        quad(-820f, 820f, -2070f, -2000f) { x, y, _ -> c.blend(x, y, 34f, 28f, 24f, 0.95f) }
        quad(-820f, 820f, -800f, -730f) { x, y, _ -> c.blend(x, y, 34f, 28f, 24f, 0.95f) }
        // teeth of glass in the corners
        for (k in 0 until 6) {
            val x0 = -700f + 1400f * WindowView.hash(k * 3 + 200)
            val len = 120f + 330f * WindowView.hash(k * 5 + 201)
            quad(x0 - 22f, x0 + 22f, -2000f, -2000f + len) { x, y, f -> c.blend(x, y, 120f, 134f, 140f, 0.8f * (1f - f)) }
        }
    }

    // ------------------------------------------------------------------------------------------------------------------ the things on the floor

    private fun things(c: WindowView.Canvas, floor: Floor, cam: AtticCameraDef, board: BooleanArray) {
        val proj = AtticProjector(cam)
        val o = FloatArray(3)
        fun at(x: Float, y: Float, z: Float): FloatArray? = if (proj.project(x, y, z, o)) floatArrayOf(o[0], o[1]) else null
        // the shade of an object on the dusty floor: a soft dark ellipse under it (in world coordinates, so it has the right foreshortening)
        fun shadow(cx: Float, cz: Float, rx: Float, rz: Float, k: Float) {
            for (y in 62 until H) for (x in 0 until W) {
                val i = y * W + x
                if (!floor.has(i) || !board[i]) continue
                val dx = (floor.x[i] - cx) / rx; val dz = (floor.z[i] - cz) / rz
                val d = dx * dx + dz * dz
                if (d < 1f) { val m = 1f - k * (1f - d); c.r[i] *= m; c.g[i] *= m; c.b[i] *= m }
            }
        }
        // a board that fell from the roof: a long thin box on the floor
        fun plank(cx: Float, cz: Float, length: Float, angle: Float, shade: Float) {
            val hx = cos(angle) * length / 2f; val hz = sin(angle) * length / 2f
            val wx = -sin(angle) * 80f; val wz = cos(angle) * 80f
            shadow(cx + 30f, cz + 40f, length * 0.58f, 190f, 0.5f)
            val a = at(cx - hx - wx, 0f, cz - hz - wz) ?: return
            val b = at(cx + hx - wx, 0f, cz + hz - wz) ?: return
            val d = at(cx - hx + wx, 0f, cz - hz + wz) ?: return
            val e = at(cx + hx + wx, 0f, cz + hz + wz) ?: return
            val a2 = at(cx - hx - wx, -90f, cz - hz - wz) ?: return
            val b2 = at(cx + hx - wx, -90f, cz + hz - wz) ?: return
            val d2 = at(cx - hx + wx, -90f, cz - hz + wz) ?: return
            val e2 = at(cx + hx + wx, -90f, cz + hz + wz) ?: return
            val front = if (a[1] > d[1]) listOf(a, b, b2, a2) else listOf(d, e, e2, d2) // the long side that faces the camera
            c.poly(front.map { it[0] }.toFloatArray(), front.map { it[1] }.toFloatArray()) { x, y ->
                if (!board[y * W + x]) return@poly
                val g = WindowView.fbm(x / 6f, y / 1.5f, 551)
                c.set(x, y, (46f + 22f * g) * shade, (40f + 20f * g) * shade, (34f + 16f * g) * shade)
            }
            c.poly(floatArrayOf(a2[0], b2[0], e2[0], d2[0]), floatArrayOf(a2[1], b2[1], e2[1], d2[1])) { x, y ->
                if (!board[y * W + x]) return@poly
                val g = WindowView.fbm(x / 6f, y / 1.5f, 552)
                c.set(x, y, (84f + 28f * g) * shade, (76f + 26f * g) * shade, (66f + 22f * g) * shade)
            }
        }
        // a heap of plaster and rubble: a low mound of grey crumbs, lit from the window side
        fun heap(cx: Float, cz: Float, radius0: Float, seed: Int) {
            val radius = radius0 * 0.55f
            shadow(cx + 40f, cz + 50f, radius * 1.25f, radius * 0.9f, 0.45f)
            val centre = at(cx, 0f, cz) ?: return
            val edge = at(cx + radius, 0f, cz) ?: return
            val front = at(cx, 0f, cz - radius) ?: return
            val rx = abs(edge[0] - centre[0]).coerceAtLeast(1.5f)
            val ry = abs(front[1] - centre[1]).coerceAtLeast(1f)
            c.ellipse(centre[0], centre[1] - ry * 0.6f, rx, ry * 1.3f) { x, y, dx, dy ->
                if (!board[y * W + x]) return@ellipse
                val crumb = WindowView.hash2(x, y, seed)
                val lit = (0.5f - dx * 0.35f - dy * 0.4f).coerceIn(0f, 1f)
                val v = 40f + 40f * lit + 34f * crumb
                c.blend(x, y, v * 1.06f, v * 0.96f, v * 0.84f, 0.88f * (1f - (dx * dx + dy * dy) * 0.5f))
            }
        }
        plank(2100f, -300f, 2200f, 0.5f, 1f)
        plank(-3200f, -3000f, 1500f, -0.35f, 0.9f)
        plank(900f, 2600f, 1800f, 0.15f, 0.95f)
        plank(-600f, -2200f, 2600f, 1.2f, 0.85f)
        plank(4400f, 1800f, 1700f, -0.8f, 0.9f)
        plank(-4800f, 1100f, 2000f, 0.25f, 0.8f)
        plank(1700f, 4000f, 1500f, -0.1f, 0.9f)
        heap(-500f, -900f, 330f, 1)
        heap(2900f, 3300f, 520f, 2)
        heap(3100f, 1500f, 260f, 3)
        heap(-2500f, 900f, 300f, 4)
        heap(-1500f, -3200f, 380f, 5)
        heap(1400f, -2000f, 280f, 6)
        heap(-3600f, -800f, 420f, 7)
        heap(500f, 1100f, 240f, 8)
        heap(3900f, -2800f, 460f, 9)
        heap(-300f, 3400f, 340f, 10)
        heap(-4400f, 3300f, 500f, 11)
        // a puddle under a leak: it shows the light of the window
        val pc = at(-700f, 0f, 1500f)
        val pe = at(-700f + 600f, 0f, 1500f)
        if (pc != null && pe != null) {
            val rx = abs(pe[0] - pc[0]); val ry = rx * 0.3f
            c.ellipse(pc[0], pc[1], rx, ry) { x, y, dx, dy ->
                if (!board[y * W + x]) return@ellipse
                val k = 1f - (dx * dx + dy * dy)
                val sky = 0.55f + 0.45f * (-dy)
                c.blend(x, y, 92f * sky + 30f, 104f * sky + 32f, 112f * sky + 34f, 0.38f + 0.4f * k)
            }
        }
        // footprints in the dust, from the front of the room to the piano: in the plane of the floor, with the perspective of the room
        val pts = ArrayList<FloatArray>()
        val n = 18
        for (k in 0 until n) {
            val t = k / (n - 1f)
            pts.add(floatArrayOf(1700f + (-2900f - 1700f) * t + 500f * sin(t * 5f), -4700f + (3300f + 4700f) * t))
        }
        for (i in pts.indices) {
            val p = pts[i]
            val q = pts[min(i + 1, pts.size - 1)]
            val ang = Math.atan2((q[1] - p[1]).toDouble(), (q[0] - p[0]).toDouble()).toFloat()
            val side = if (i % 2 == 0) -1f else 1f
            val fx0 = p[0] + (-sin(ang)) * 170f * side
            val fz0 = p[1] + cos(ang) * 170f * side
            for (y in 62 until H) for (x in 0 until W) {
                val j = y * W + x
                if (!floor.has(j) || !board[j]) continue
                // the foot in its own frame: long along the way, narrow across it
                val dx = floor.x[j] - fx0; val dz = floor.z[j] - fz0
                val along = (dx * cos(ang) + dz * sin(ang)) / 280f
                val across = (-dx * sin(ang) + dz * cos(ang)) / 120f
                val d = along * along + across * across
                if (d < 1f) { val m = 1f - 0.34f * (1f - d); c.r[j] *= m; c.g[j] *= m; c.b[j] *= m }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------------ the decay of the building

    private class Hole(val cx: Float, val cz: Float, val rx: Float, val rz: Float, val seed: Int)

    private val HOLES = listOf(
        Hole(-1500f, 300f, 900f, 650f, 1), Hole(2500f, 3100f, 450f, 330f, 2), Hole(-4300f, -2400f, 560f, 400f, 3), Hole(900f, -3300f, 300f, 220f, 4), Hole(4300f, -300f, 380f, 300f, 5),
    )

    /** Holes in the floor: the boards have rotted through or broken, the joists below show, the edges are splintered; the dark of the room below. */
    private fun holes(c: WindowView.Canvas, floor: Floor, board: BooleanArray) {
        for (y in 62 until H) for (x in 0 until W) {
            val i = y * W + x
            if (!floor.has(i) || !board[i]) continue
            for (h in HOLES) {
                val dx = (floor.x[i] - h.cx) / h.rx; val dz = (floor.z[i] - h.cz) / h.rz
                val ang = Math.atan2(dz.toDouble(), dx.toDouble()).toFloat()
                val wob = 1f + 0.5f * (WindowView.fbm(ang * 1.3f + h.seed * 7f, h.seed * 3f, 561) - 0.5f) + 0.22f * (WindowView.fbm(ang * 4f + h.seed, 9f, 562) - 0.5f)
                val d = sqrt(dx * dx + dz * dz) / wob
                if (d > 1.12f) continue
                if (d < 1f) {
                    // the dark below, with a joist or two across it that catch a little light
                    val joist = abs(((floor.z[i] - h.cz) % 760f + 760f) % 760f - 380f) < 70f
                    val v = if (joist) 30f + 14f * WindowView.hash2(x, y, 7) else 8f + 7f * (1f - d)
                    c.set(x, y, v, v - 1f, v - 2f)
                } else {
                    // the splintered ends of the boards at the edge: pale where the wood is broken
                    val k = 1f - (d - 1f) / 0.12f
                    c.blend(x, y, 120f, 108f, 90f, 0.7f * k * (0.5f + 0.5f * WindowView.hash2(x, y, 8)))
                }
            }
        }
    }

    /** Mould and damp growth on the boards in the places where the roof leaks: a grey-green that eats into the wood. */
    private fun mould(c: WindowView.Canvas, floor: Floor, board: BooleanArray) {
        for (y in 62 until H) for (x in 0 until W) {
            val i = y * W + x
            if (!floor.has(i) || !board[i]) continue
            val m = smooth(0.58f, 0.7f, WindowView.fbm(floor.x[i] / 750f, floor.z[i] / 620f, 571))
            if (m <= 0f) continue
            val speck = 0.65f + 0.35f * WindowView.hash2(x, y, 12)
            c.blend(x, y, 40f, 58f, 38f, 0.42f * m * speck)
        }
        // moss: a bright, soft green that grows in the damp places and along the walls, thick in the middle of its patches and thin at the edges
        for (y in 62 until H) for (x in 0 until W) {
            val i = y * W + x
            if (!floor.has(i) || !board[i]) continue
            val fx = floor.x[i]; val fz = floor.z[i]
            val wet = smooth(0.68f, 0.8f, WindowView.fbm(fx / 520f, fz / 480f, 591))
            val wallside = smooth(4200f, 5400f, max(abs(fx), fz)) * 0.45f
            val m = max(wet, wallside * smooth(0.55f, 0.72f, WindowView.fbm(fx / 300f, fz / 260f, 592)))
            if (m <= 0.02f) continue
            val tuft = 0.55f + 0.45f * WindowView.hash2(x, y, 14)
            c.blend(x, y, 62f + 30f * tuft, 96f + 36f * tuft, 44f + 16f * tuft, 0.62f * m * tuft)
        }
    }

    /** Boards missing in the back wall of the attic (z = 5000): through the gaps the daylight shines in, thin and bright, with a faint glow round them. */
    private fun slits(c: WindowView.Canvas, cam: AtticCameraDef, wall: BooleanArray) {
        val proj = AtticProjector(cam)
        val o = FloatArray(3)
        val gaps = listOf(-6200f to 130f, -5300f to 90f, -3900f to 140f, -3100f to 90f, -1900f to 110f, 1800f to 100f, 2500f to 90f, 3600f to 150f, 4400f to 90f, 5300f to 170f)
        for ((gx, gw) in gaps) {
            fun fill(width: Float, alpha: Float, r: Float, g: Float, b: Float) {
                val xs = FloatArray(4); val ys = FloatArray(4)
                val corners = arrayOf(floatArrayOf(gx - width, -2300f), floatArrayOf(gx + width, -2300f), floatArrayOf(gx + width, -150f), floatArrayOf(gx - width, -150f))
                for ((k, p) in corners.withIndex()) { if (!proj.project(p[0], p[1], 5000f, o)) return; xs[k] = o[0]; ys[k] = o[1] }
                if (xs.max() < 0f || xs.min() > W || ys.max() < 0f || ys.min() > H) return
                c.poly(xs, ys) { x, y -> if (wall[y * W + x]) c.blend(x, y, r, g, b, alpha * (0.85f + 0.15f * WindowView.hash2(x, y, 5))) }
            }
            fill(gw * 2.4f, 0.1f, 150f, 162f, 170f) // the glow
            fill(gw * 0.55f, 0.9f, 170f, 182f, 190f) // the gap itself, bright
        }
    }

    /**
     * The things of the original picture that would decay: the iron hoops of the barrels rust, the wood of barrels and crates rots in dark patches and has holes where boards are
     * broken, moss grows on the tops, the dust sheet is stained and mouldy.
     */
    private fun objects(c: WindowView.Canvas, photo: IntArray, camera: Int) {
        for (y in 0 until H) for (x in 0 until W) {
            val i = y * W + x
            val p = photo[i]
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            val lum = 0.3f * r + 0.59f * g + 0.11f * b
            val light = lum > 82f
            // the hoops of the barrels: a bright grey-green iron that rusts to orange-brown
            if (lum > 105f && g >= r * 0.9f && b >= r * 0.85f && camera != 0 || (camera == 0 && y in 52..72 && x in 170..215 && lum > 105f && g >= r * 0.9f && b >= r * 0.85f)) {
                val rust = 0.6f + 0.35f * WindowView.fbm(x / 3f, y / 2f, 601)
                c.blend(x, y, 128f, 72f, 40f, 0.85f * rust)
                continue
            }
            // wood of barrels and crates: orange-brown and light
            if (r > g && g >= b && r - b > 52 && light) {
                val rot = WindowView.fbm(x / 4f, y / 3f, 602)
                if (rot > 0.72f) c.blend(x, y, 30f, 22f, 18f, 0.85f) // a hole, a broken board
                else if (rot > 0.6f) c.blend(x, y, 62f, 44f, 32f, 0.6f) // rot
                // moss on the upper parts that catch the damp and the light: soft green patches
                val moss = WindowView.fbm(x / 5f, y / 4f, 603)
                if (moss > 0.66f && WindowView.hash2(x, y, 16) > 0.2f) c.blend(x, y, 66f, 100f, 46f, 0.75f)
            }
            // the dust sheet over the cabinet: pale grey-green; stained and mouldy
            if (g >= r && g >= b && lum > 120f && camera == 1) {
                val s = WindowView.fbm(x / 6f, y / 5f, 604)
                if (s > 0.55f) c.blend(x, y, 70f, 84f, 62f, 0.55f * ((s - 0.55f) * 4f).coerceAtMost(1f))
                if (WindowView.fbm(x / 3f, y / 3f, 605) > 0.78f) c.blend(x, y, 40f, 52f, 36f, 0.8f)
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------------ the furniture of the pictures

    /**
     * The furniture that is part of the original pictures, hurt where it stands (places found by looking at the pictures): the bookshelves have lost books and are mouldy, the piano has
     * broken keys, a split lid and cracks, the cabinets have lost their veneer and a panel, the crates are broken, the rug is eaten by moths and mould, the edge of the trap door is splintered.
     */
    private fun furniture(c: WindowView.Canvas, photo: IntArray, camera: Int) {
        when (camera) {
            0 -> { piano(c, 38, 36, 70, 62, 1); rug(c, photo, 80, 98, 250, 152) }
            1 -> {
                books(c, photo, 265, 88, 312, 122, 5, 11)
                wreck(c, 160, 70, 242, 136, 21, 1f)
                wreck(c, 230, 60, 262, 126, 22, 0.8f)
            }
            2 -> { piano(c, 0, 48, 92, 150, 2); keys(c, 2) }
            3 -> {
                books(c, photo, 160, 49, 188, 68, 3, 12)
                wreck(c, 143, 52, 161, 69, 23, 1f)
                wreck(c, 187, 56, 212, 77, 24, 1.2f)
                wreck(c, 212, 54, 242, 77, 25, 1f)
            }
        }
    }

    /** Books on a shelf: spines every few pixels; some are gone (the shelf shows dark), the rest are faded, dusty and mouldy. */
    private fun books(c: WindowView.Canvas, photo: IntArray, x1: Int, y1: Int, x2: Int, y2: Int, spine: Int, seed: Int) {
        for (x in x1..x2) {
            val col = (x - x1) / spine
            val gone = WindowView.hash(col * 17 + seed) < 0.26
            for (y in y1..y2) {
                val i = y * W + x
                val p = photo[i]
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                val lum = 0.3f * r + 0.59f * g + 0.11f * b
                // only the books (bright or coloured), not the dark of the shelf
                if (lum < 55f && abs(r - g) < 40) continue
                if (gone) { c.set(x, y, 34f + 6f * WindowView.hash2(x, y, 3), 26f, 26f); continue }
                // faded: the colour of the book is drained, the cloth dull
                val l = lum * 0.85f
                c.blend(x, y, l * 1.04f, l, l * 0.94f, 0.6f)
                if (WindowView.fbm(x / 3f, y / 3f, 610 + seed) > 0.58f) c.blend(x, y, 72f, 92f, 66f, 0.6f) // mould
            }
        }
        // a book that has slipped and leans: a bright slanted strip on the shelf
        val bx = x1 + 4 + (WindowView.hash(seed * 5) * (x2 - x1 - 12)).toInt()
        for (t in 0..(y2 - y1) / 2) c.blend(bx + t / 3, y2 - t, 150f, 118f, 90f, 0.8f)
    }

    /** The keys of the piano in the close picture: a number of them are missing (black gaps), some are crooked. */
    private fun keys(c: WindowView.Canvas, seed: Int) {
        // the band of the keys runs from (0, 108)-(0, 129) at the left edge to (87, 79)-(84, 90) on the right
        for (k in 0 until 40) {
            val t = k / 39f
            if (WindowView.hash(k * 7 + seed) > 0.4f) continue
            val xx = (t * 84f).toInt()
            val yTop = 108f - 29f * t; val yBot = 129f - 39f * t
            for (dx in 0..1) for (y in yTop.toInt()..yBot.toInt()) c.set(xx + dx, y, 8f, 8f, 10f)
        }
    }

    /** A piano: cracks across the dark wood, the veneer chipped away in light patches, a corner of the lid broken off. */
    private fun piano(c: WindowView.Canvas, x1: Int, y1: Int, x2: Int, y2: Int, seed: Int) {
        for (y in y1..y2) for (x in x1..x2) {
            val i = y * W + x
            val lum = 0.3f * c.r[i] + 0.59f * c.g[i] + 0.11f * c.b[i]
            if (lum > 85f || c.r[i] - c.g[i] > 40f) continue // keys, the gold of the fittings, the red beams and the wall stay
            val chip = WindowView.fbm(x / 3f, y / 3f, 620 + seed)
            if (chip > 0.72f) c.blend(x, y, 128f, 104f, 86f, 0.55f) // the veneer has come off
            val stain = WindowView.fbm(x / 9f, y / 6f, 625 + seed)
            if (stain > 0.55f) c.blend(x, y, 20f, 18f, 18f, 0.4f * ((stain - 0.55f) * 4f).coerceAtMost(1f))
        }
        // cracks: thin dark lines that run down and across
        for (k in 0 until 4) {
            var x = x1 + (x2 - x1) * WindowView.hash(k * 3 + seed * 11)
            var y = y1 + 2f + (y2 - y1) * 0.3f * WindowView.hash(k * 5 + seed)
            for (s in 0 until (y2 - y1) / 2) {
                c.blend(x.toInt(), y.toInt(), 8f, 6f, 6f, 0.85f)
                x += (WindowView.hash2(s, k, seed) - 0.4f) * 1.6f; y += 1f
            }
        }
    }

    /** A cabinet or a crate: the veneer peeled in grey patches, a dark damp stain, a panel broken out, cracks along the wood. */
    private fun wreck(c: WindowView.Canvas, x1: Int, y1: Int, x2: Int, y2: Int, seed: Int, strength: Float) {
        for (y in y1..y2) for (x in x1..x2) {
            val i = y * W + x
            val peel = WindowView.fbm(x / 6f, y / 4f, 630 + seed)
            if (peel > 0.66f) c.blend(x, y, 118f, 100f, 90f, 0.55f * strength)
            val stain = WindowView.fbm(x / 14f, y / 8f, 635 + seed)
            if (stain > 0.55f) c.blend(x, y, 24f, 22f, 20f, 0.38f * strength * ((stain - 0.55f) * 4f).coerceAtMost(1f))
        }
        // a broken-out panel: a dark jagged hole
        val cx = x1 + (x2 - x1) * (0.35f + 0.3f * WindowView.hash(seed))
        val cy = y1 + (y2 - y1) * (0.45f + 0.3f * WindowView.hash(seed * 7))
        val rx = (x2 - x1) * 0.12f * strength; val ry = (y2 - y1) * 0.14f * strength
        c.ellipse(cx, cy, rx, ry) { x, y, dx, dy ->
            val edge = 1f + 0.3f * (WindowView.hash2(x, y, 9) - 0.5f)
            if (dx * dx + dy * dy < edge * edge) c.set(x, y, 14f, 11f, 11f)
        }
    }

    /** The rug: moth-eaten holes where the boards show through, mould, a frayed edge. */
    private fun rug(c: WindowView.Canvas, photo: IntArray, x1: Int, y1: Int, x2: Int, y2: Int) {
        for (y in y1..y2) for (x in x1..x2) {
            val p = photo[y * W + x]
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            val lum = 0.3f * r + 0.59f * g + 0.11f * b
            // the rug: its pinkish pattern and its teal border (the floor is darker and browner)
            val isRug = lum > 88f && ((b >= g * 0.86f && r > g * 1.1f) || (g >= r * 0.95f && b >= r * 0.9f))
            if (!isRug) continue
            val hole = WindowView.fbm(x / 5f, y / 4f, 640)
            if (hole > 0.7f) { c.set(x, y, 50f + 14f * WindowView.hash2(x, y, 2), 38f, 31f); continue }
            val mould = WindowView.fbm(x / 8f, y / 6f, 641)
            if (mould > 0.56f) c.blend(x, y, 66f, 84f, 60f, 0.5f * ((mould - 0.56f) * 4f).coerceAtMost(1f))
        }
    }

    /** The edge of the trap door: the boards round the opening are splintered and broken, pale where the wood has split. */
    private fun trapdoor(c: WindowView.Canvas, photo: IntArray, x1: Int, y1: Int, x2: Int, y2: Int) {
        fun hole(x: Int, y: Int): Boolean {
            if (x !in 0 until W || y !in 0 until H) return false
            val p = photo[y * W + x]
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            val lum = 0.3f * r + 0.59f * g + 0.11f * b
            return lum in 55f..135f && g >= r * 0.97f && b >= r * 0.88f
        }
        for (y in y1..y2) for (x in x1..x2) {
            if (hole(x, y)) continue
            var near = false
            for (d in 1..3) if (hole(x - d, y) || hole(x + d, y) || hole(x, y - d) || hole(x, y + d)) { near = true; break }
            if (!near) continue
            val h = WindowView.hash2(x, y, 31)
            if (h > 0.55f) c.blend(x, y, 176f, 148f, 110f, 0.8f) // a pale splinter
            else if (h < 0.15f) c.blend(x, y, 14f, 10f, 10f, 0.8f)
        }
    }

    /** The cloth over the table: torn, with holes, grey-green with mildew. */
    private fun cloth(c: WindowView.Canvas, photo: IntArray) {
        for (y in 125 until H) for (x in 0..150) {
            val i = y * W + x
            val pr = (photo[i] shr 16) and 0xFF; val pg = (photo[i] shr 8) and 0xFF
            if (pg <= pr * 1.05f) continue
            val m = WindowView.fbm(x / 8f, y / 6f, 581)
            if (m > 0.78f) { c.set(x, y, 10f, 12f, 11f) }
            else if (m > 0.62f) c.blend(x, y, 70f, 90f, 74f, 0.4f)
            if (WindowView.fbm(x / 3f, y / 2f, 582) > 0.82f) c.blend(x, y, 20f, 22f, 20f, 0.8f)
        }
    }

    // ------------------------------------------------------------------------------------------------------------------ the film

    /** The last step: a soft vignette, the shadows go teal and the lights go warm, a fine grain like a photograph of low light. */
    private fun finish(c: WindowView.Canvas) {
        for (y in 0 until H) for (x in 0 until W) {
            val i = y * W + x
            var r = c.r[i]; var g = c.g[i]; var b = c.b[i]
            val lum = (0.3f * r + 0.59f * g + 0.11f * b) / 255f
            // split toning: cool in the shadows, warm in the lights
            val shade = (1f - lum * 2.2f).coerceIn(0f, 1f)
            val light = ((lum - 0.45f) * 2f).coerceIn(0f, 1f)
            r += -1f * shade + 4f * light; g += 1f * shade + 2f * light; b += 3f * shade - 2f * light
            val dx = (x - 160f) / 160f; val dy = (y - 104f) / 104f
            val vig = 1f - 0.14f * (dx * dx * 0.8f + dy * dy * 0.7f)
            val grain = (WindowView.hash2(x, y, 99) - 0.5f) * 4f
            c.r[i] = r * vig + grain; c.g[i] = g * vig + grain; c.b[i] = b * vig + grain
        }
    }

    // ------------------------------------------------------------------------------------------------------------------ the moving light

    /** The moving things of the first picture: the daylight that clouds dim and brighten, the dust that floats in the shaft of light. */
    fun fx(camera: Int): FxSet? = when (camera) {
        0 -> FxSet(
            listOf(
                FxLight(FxShape.Rect(143f, 30f, 168f, 52f), 235, 240, 245, 0.45f, FxWave.OVERCAST),
                FxLight(FxShape.Circle(172f, 108f, 46f), 150, 156, 146, 0.16f, FxWave.OVERCAST),
            ),
            listOf(FxDust(96f, 54f, 236f, 134f, 36, 0.9f)),
        )
        in 1..4 -> FxSet(emptyList(), listOf(FxDust(70f, 50f, 250f, 160f, 16, 0.6f)))
        else -> null
    }
}
