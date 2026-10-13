package com.aitddemo

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The balcony outside the window of the attic (NOT in the original game; an addition on this branch). Like the view out of the window ([WindowView]) its picture is painted by code
 * in the game's palette at 320 x 200 pixels. It is painted *through the camera of the room* ([camera]): every pixel is turned back into a ray and met with the floor of the balcony
 * and with the ground of the garden far below, and the railing is projected with the same maths, so the figure that walks on the balcony stands exactly on the painted boards.
 */
object BalconyView {
    // the camera of the balcony: a little inside the attic, high, looking out and down over the balcony into the garden (positions in tenths of the engine's units, as the cameras of the game)
    const val CAM_X = 0f
    const val CAM_Y = -2600f // world y (up is negative)
    const val CAM_Z = 3000f
    const val ALPHA = 55
    const val FOCAL1 = 700f
    const val FOCAL2 = 230f
    const val FOCAL3 = 200f

    /** The camera of the room (the room has the origin of the attic's frame, so the numbers are those of the attic). */
    val camera = AtticCameraDef(
        index = 0, posX = CAM_X / 10f, posY = -CAM_Y / 10f, posZ = -CAM_Z / 10f,
        angleAlpha = ALPHA, angleBeta = 0, angleGamma = 0, focal1 = FOCAL1, focal2 = FOCAL2, focal3 = FOCAL3,
        // the zone of the camera (in tenths): the whole balcony and its surroundings
        zones = listOf(listOf(AtticCameraZonePoint(-400f, 300f), AtticCameraZonePoint(400f, 300f), AtticCameraZonePoint(400f, 900f), AtticCameraZonePoint(-400f, 900f))),
        viewedRooms = setOf(1),
    )

    // the balcony: boards from the wall of the house out to the railing (world units; the floor is at y = 0, the garden lies 4.5 m below)
    const val FLOOR_X = 1850f
    const val FLOOR_Z0 = 5300f
    const val FLOOR_Z1 = 7600f
    private const val GROUND_Y = 4500f

    private val ac = cos(ALPHA * 2.0 * Math.PI / 1024.0).toFloat()
    private val asn = sin(ALPHA * 2.0 * Math.PI / 1024.0).toFloat()

    /** The point of the world that the pixel ([u], [v]) of the picture looks at on the horizontal plane at height [planeY]: x, z and the depth of the camera, or null (above the plane's horizon). */
    private fun hit(u: Float, v: Float, planeY: Float, out: FloatArray): Boolean {
        val xs = u - 160f
        val ys = v - 100f
        val den = ac * ys / FOCAL3 + asn
        if (abs(den) < 1e-5f) return false
        val zc = (planeY - CAM_Y - ac * ys * FOCAL1 / FOCAL3) / den
        if (zc <= 0f) return false
        val xc = xs * (zc + FOCAL1) / FOCAL2
        val yc = ys * (zc + FOCAL1) / FOCAL3
        out[0] = CAM_X + xc
        out[1] = CAM_Z + (-yc * asn + zc * ac)
        out[2] = zc
        return true
    }

    /** The screen row of the horizon of the ground plane (rays above it never meet the ground), with a ragged ridge of hills along it. */
    private fun horizonRow(u: Int): Float {
        val ys = -asn / ac * FOCAL3 // the row of the infinitely distant ground: den = 0
        return 100f + ys - 3f - 11f * WindowView.fbm(u / 55f, 5f, 4) - 3f * sin(u / 19f)
    }

    fun paint(palette: IntArray): IntArray {
        val c = WindowView.Canvas()
        val p = FloatArray(3)
        val rgb = FloatArray(3)
        for (v in 0 until WindowView.H) for (u in 0 until WindowView.W) {
            val uu = u + 0.5f; val vv = v + 0.5f
            var done = false
            if (hit(uu, vv, 0f, p)) {
                val x = p[0]; val z = p[1]
                if (abs(x) <= FLOOR_X && z in FLOOR_Z0..FLOOR_Z1) { boards(x, z, rgb); done = true }
                else if (z < FLOOR_Z0 && abs(x) < 9000f) { doorway(x, z, rgb); done = true } // below the near edge: the dark of the room and the threshold
            }
            if (!done) {
                if (hit(uu, vv, GROUND_Y, p)) {
                    if (p[2] > 90000f) {
                        // beyond the garden: the hills; their ridge is ragged, a pale haze lies along it, below it they get darker
                        val ridge = horizonRow(u)
                        if (vv < ridge) sky(u, v, rgb) else hills(u, ((vv - ridge) / 14f).coerceIn(0f, 1f), rgb)
                    } else garden(p[0], p[1], p[2], rgb)
                } else sky(u, v, rgb)
            }
            c.set(u, v, rgb[0], rgb[1], rgb[2])
        }
        railing(c)
        return c.quantize(palette)
    }

    // ------------------------------------------------------------------------------------------------------------------ the boards

    private fun boards(x: Float, z: Float, o: FloatArray) {
        val plank = floor((z - FLOOR_Z0) / 230f)
        val within = ((z - FLOOR_Z0) / 230f) - plank
        val h = WindowView.hash(plank.toInt() * 17 + 5)
        val grain = WindowView.fbm(x / 700f + h * 9f, z / 40f, 5) // the grain runs along the boards
        var v = (0.75f + 0.5f * h) * (0.8f + 0.5f * grain)
        var r = 58f * v; var g = 42f * v; var b = 32f * v
        // a joint between boards, and the ends of the boards where they meet
        if (within < 0.07f) { r *= 0.35f; g *= 0.35f; b *= 0.35f }
        val endJoint = abs(((x + 4000f + h * 900f) % 1500f) - 750f)
        if (endJoint > 744f) { r *= 0.5f; g *= 0.5f; b *= 0.5f }
        // the rain: the boards are wet and shine cold in the moonlight, puddles stand where they sag
        val pud = WindowView.fbm(x / 420f, z / 260f, 21)
        if (pud > 0.6f) { val k = (pud - 0.6f) * 3.2f; r += 46f * k; g += 62f * k; b += 82f * k }
        val wet = 0.5f + 0.5f * WindowView.fbm(x / 900f + 3f, z / 500f, 22)
        r += 9f * wet; g += 12f * wet; b += 14f * wet
        // the stone edge along the railing and the foot of the wall
        if (z > FLOOR_Z1 - 220f) { r = 52f + 14f * grain; g = 55f + 14f * grain; b = 62f + 14f * grain }
        if (abs(x) > FLOOR_X - 150f) { r = 48f + 12f * grain; g = 51f + 12f * grain; b = 58f + 12f * grain }
        o[0] = r; o[1] = g; o[2] = b
    }

    /** The threshold and the dark room behind it, seen below the near edge of the balcony. */
    private fun doorway(x: Float, z: Float, o: FloatArray) {
        val d = (FLOOR_Z0 - z) / 1200f
        val sill = if (d < 0.1f) 1f else 0f
        val grain = WindowView.fbm(x / 500f, z / 30f, 8)
        val v = (0.9f - d * 0.8f).coerceAtLeast(0.08f) * (0.7f + 0.6f * grain)
        o[0] = 40f * v + 70f * sill; o[1] = 28f * v + 48f * sill; o[2] = 20f * v + 32f * sill
    }

    // ------------------------------------------------------------------------------------------------------------------ the garden below

    /** The sky above the garden: the storm clouds, the moon behind a veil of them. */
    private fun sky(u: Int, v: Int, o: FloatArray) {
        val t = (v / 36f).coerceIn(0f, 1f)
        val cloud = WindowView.fbm(u / 46f, v / 9f, 11)
        val bank = WindowView.fbm(u / 21f + 5f, v / 5f, 31)
        var r = 11f + 38f * t * t + 22f * cloud * (0.35f + t)
        var g = 15f + 46f * t * t + 25f * cloud * (0.35f + t)
        var b = 29f + 58f * t * t + 30f * cloud * (0.35f + t)
        val dark = (bank - 0.5f).coerceAtLeast(0f) * 2f
        r *= 1f - 0.45f * dark; g *= 1f - 0.45f * dark; b *= 1f - 0.4f * dark
        val dx = u - 252f; val dy = (v - 13f) * 1.1f
        val d2 = dx * dx + dy * dy
        val veil = 1f - 0.8f * (WindowView.fbm(u / 17f, v / 6f, 51) - 0.35f).coerceIn(0f, 0.6f) / 0.6f
        val glow = exp(-d2 / (2f * 24f * 24f)) * 80f * (0.45f + 0.55f * veil)
        r += glow * 0.78f; g += glow * 0.9f; b += glow * 1.05f
        if (d2 < 36f) { val k = 0.55f + 0.45f * veil; r = 226f * k + r * (1f - k); g = 234f * k + g * (1f - k); b = 236f * k + b * (1f - k) }
        o[0] = r; o[1] = g; o[2] = b
    }

    /** The far hills at the end of the garden: dark ridges with a pale haze along the edge. */
    private fun hills(u: Int, t: Float, o: FloatArray) {
        val grain = WindowView.fbm(u / 7f, t * 3f, 14)
        // a second, nearer row of hills in front of the first one
        val near = horizonRow(u + 90) + 10f + 5f * sin(u / 31f)
        val inNear = t * 14f + horizonRow(u) > near
        val k = if (inNear) 0.55f else 1f
        o[0] = (46f - 32f * t + 5f * grain) * k; o[1] = (60f - 35f * t + 5f * grain) * k; o[2] = (70f - 40f * t + 5f * grain) * k
    }

    private data class Tree(val x: Float, val z: Float, val r: Float, val seed: Int)

    private val TREES = listOf(
        Tree(-7000f, 17000f, 2200f, 1), Tree(8000f, 17500f, 2000f, 2), Tree(-13000f, 21000f, 3000f, 3), Tree(12000f, 23000f, 2800f, 4),
        Tree(-5000f, 29000f, 2600f, 5), Tree(6000f, 31000f, 2800f, 6), Tree(-18000f, 26000f, 3300f, 7), Tree(17000f, 27000f, 3200f, 8),
        Tree(-22000f, 19000f, 3400f, 9), Tree(21000f, 16000f, 3000f, 10), Tree(-3500f, 14500f, 1400f, 11), Tree(4000f, 15000f, 1500f, 12),
        Tree(-9500f, 33000f, 3000f, 13), Tree(10500f, 35000f, 3200f, 14),
    )

    /** The garden at the point (x, z) of the ground, [depth] metres away: lawn, the gravel path to the gate, the hedges, the park wall and its gate, the road and the trees; the mist thickens with the distance. */
    private fun garden(x: Float, z: Float, depth: Float, o: FloatArray) {
        var r: Float; var g: Float; var b: Float
        val grain = WindowView.fbm(x / 500f, z / 500f, 71)
        val fine = WindowView.hash2((x / 90f).toInt(), (z / 90f).toInt(), 3)
        // the lawn
        r = 19f + 14f * grain + 6f * fine
        g = 38f + 24f * grain + 9f * fine
        b = 31f + 16f * grain + 7f * fine
        val stripe = sin(x * 0.0009f + z * 0.0004f) * 3f
        r += stripe * 0.5f; g += stripe; b += stripe * 0.6f
        // the property: the park wall across the garden at z = 25 m with the gate in the middle, the road beyond it
        val wallZ = 19000f
        val roadZ0 = 21000f
        val roadZ1 = 24000f
        val pathHalf = 1100f
        if (abs(x) < pathHalf && z < wallZ) {
            // gravel, a lighter track in the middle, ruts
            val gr = WindowView.hash2((x / 55f).toInt(), (z / 55f).toInt(), 17)
            val track = 1f - abs(x) / pathHalf
            val v = 70f + 24f * WindowView.fbm(x / 300f, z / 300f, 41) + 16f * (gr - 0.5f) + 10f * track
            val rut = abs(abs(x) - pathHalf * 0.4f) < 120f
            val vv = if (rut) v - 12f else v
            r = vv * 0.96f; g = vv * 0.97f; b = vv * 0.94f + 4f
            // the puddles on the path
            val pud = WindowView.fbm(x / 600f, z / 700f, 91)
            if (pud > 0.64f) { val k = (pud - 0.64f) * 4f; r += 40f * k; g += 56f * k; b += 76f * k }
        } else if (abs(x) < pathHalf + 520f && z < wallZ) {
            // the clipped hedge along the path
            val leaf = WindowView.hash2((x / 70f).toInt(), (z / 70f).toInt(), 23)
            r = 8f + 9f * leaf; g = 22f + 17f * leaf; b = 16f + 11f * leaf
        }
        if (z in wallZ..wallZ + 600f) {
            val gate = abs(x) < 1500f
            if (gate) {
                // the iron gate between the two stone pillars
                val bars = ((x + 2000f) % 380f) < 130f
                if (abs(abs(x) - 1500f) < 250f) { r = 74f; g = 77f; b = 84f } else if (bars) { r = 12f; g = 12f; b = 16f }
            } else {
                val stone = WindowView.hash2((x / 400f).toInt(), (z / 300f).toInt(), 5)
                r = 52f + 24f * stone; g = 55f + 24f * stone; b = 62f + 24f * stone
            }
        }
        // the lanterns on the pillars: a warm glow on the ground round them
        for (lx in floatArrayOf(-1500f, 1500f)) {
            val d = sqrt((x - lx) * (x - lx) + (z - wallZ) * (z - wallZ))
            if (d < 3000f) { val k = (1f - d / 3000f); r += 120f * k * k; g += 78f * k * k; b += 30f * k * k }
        }
        // the road
        if (z in roadZ0..roadZ1) {
            val gr = WindowView.fbm(x / 400f, z / 200f, 33)
            r = 46f + 14f * gr; g = 47f + 14f * gr; b = 53f + 14f * gr
            val pud = WindowView.fbm(x / 700f, z / 300f, 95)
            if (pud > 0.62f) { val k = (pud - 0.62f) * 4f; r += 44f * k; g += 60f * k; b += 82f * k }
            // the car with its lights on, in front of the gate a bit to the side
            val cx = -7500f
            val cz = 22500f
            if (abs(x - cx) < 2200f && abs(z - cz) < 760f) {
                val body = (1f - abs(z - cz) / 760f)
                r = 14f + 12f * body; g = 14f + 12f * body; b = 18f + 14f * body
                if (abs(x - cx) < 700f && abs(z - cz) < 420f) { r = 26f; g = 30f; b = 40f } // the roof
            }
            // the light of the lamps on the road to the left of the car
            if (x < cx - 2100f && x > cx - 9500f && abs(z - cz) < 80f + (cx - 2100f - x) * 0.11f) {
                val k = (1f - (cx - 2100f - x) / 7400f) * (1f - abs(z - cz) / (80f + (cx - 2100f - x) * 0.11f))
                r += 90f * k; g += 82f * k; b += 48f * k
            }
            if (abs(x - (cx - 2200f)) < 260f && abs(abs(z - cz) - 330f) < 150f) { r = 255f; g = 240f; b = 190f }
        } else if (z > roadZ1) {
            r = 14f + 7f * grain; g = 26f + 11f * grain; b = 24f + 9f * grain // the meadows beyond
        }
        // the trees: dark crowns with the light of the moon on their upper left
        for (t in TREES) {
            val dx = x - t.x
            val dz = z - t.z
            val d = sqrt(dx * dx + dz * dz) / t.r
            if (d < 1f) {
                val cell = WindowView.hash2((x / 140f).toInt(), (z / 140f).toInt(), t.seed)
                val lobe = WindowView.fbm(x / 420f, z / 420f, t.seed + 3)
                val facing = (-dx * 0.55f - dz * 0.2f) / t.r
                val lit = facing.coerceAtLeast(0f) * (0.4f + 0.6f * cell) * (0.5f + lobe)
                r = 10f + 9f * lobe + 6f * cell + 32f * lit
                g = 22f + 18f * lobe + 11f * cell + 54f * lit
                b = 18f + 14f * lobe + 9f * cell + 48f * lit
                if (d > 0.88f && cell > 0.55f) { r *= 0.6f; g *= 0.6f; b *= 0.6f }
            } else if (d < 1.25f && dz > -t.r * 0.2f) {
                val k = (1.25f - d) / 0.25f * 0.5f // the shadow on the lawn
                r *= 1f - k; g *= 1f - k; b *= 1f - k
            }
        }
        // the mist: the further away the paler and bluer
        val fog = 1f - exp(-depth / 95000f)
        val shimmer = 0.75f + 0.5f * WindowView.fbm(x / 3000f, z / 2000f, 61)
        val f = (fog * 0.8f * shimmer).coerceIn(0f, 0.9f)
        o[0] = r + (50f - r) * f; o[1] = g + (62f - g) * f; o[2] = b + (70f - b) * f
    }

    // ------------------------------------------------------------------------------------------------------------------ the railing

    private fun railing(c: WindowView.Canvas) {
        val proj = AtticProjector(camera)
        val a = FloatArray(3)
        val b = FloatArray(3)
        fun seg(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, w: Int, r: Float, g: Float, bl: Float) {
            if (!proj.project(x0, y0, z0, a) || !proj.project(x1, y1, z1, b)) return
            val n = max(abs(b[0] - a[0]), abs(b[1] - a[1])).toInt() + 1
            for (i in 0..n) {
                val t = i / n.toFloat()
                val px = a[0] + (b[0] - a[0]) * t
                val py = a[1] + (b[1] - a[1]) * t
                for (k in 0 until w) {
                    c.set((px + k).toInt(), py.toInt(), r, g, bl)
                    if (k == 0) c.set((px - 1).toInt(), py.toInt(), r * 2.2f + 6f, g * 2.2f + 8f, bl * 2.2f + 12f) // a gleam on the side that faces the moon
                }
            }
        }
        val topY = -1000f
        val midY = -450f
        val ex = FLOOR_X - 60f
        val ez = FLOOR_Z1 - 60f
        val iron = floatArrayOf(11f, 12f, 17f)
        // the rails: along the front and the two sides, at the top and in the middle
        for (y in floatArrayOf(topY, midY)) {
            seg(-ex, y, ez, ex, y, ez, 2, iron[0], iron[1], iron[2])
            seg(-ex, y, FLOOR_Z0 + 40f, -ex, y, ez, 2, iron[0], iron[1], iron[2])
            seg(ex, y, FLOOR_Z0 + 40f, ex, y, ez, 2, iron[0], iron[1], iron[2])
        }
        // the balusters, every 320 units, with a spearhead on top
        var x = -ex
        while (x <= ex + 1f) {
            seg(x, 0f, ez, x, topY, ez, 1, iron[0], iron[1], iron[2])
            if (proj.project(x, topY - 90f, ez, a)) c.set(a[0].toInt(), a[1].toInt(), 40f, 44f, 56f)
            x += 320f
        }
        var z = FLOOR_Z0 + 40f
        while (z <= ez + 1f) {
            seg(-ex, 0f, z, -ex, topY, z, 1, iron[0], iron[1], iron[2])
            seg(ex, 0f, z, ex, topY, z, 1, iron[0], iron[1], iron[2])
            z += 320f
        }
        // the posts at the corners: thicker, with a ball on top
        for ((px, pz) in listOf(-ex to ez, ex to ez, -ex to FLOOR_Z0 + 40f, ex to FLOOR_Z0 + 40f)) {
            seg(px, 0f, pz, px, topY - 120f, pz, 3, 9f, 10f, 14f)
            if (proj.project(px, topY - 200f, pz, a)) c.ellipse(a[0], a[1], 2.2f, 2.2f, { x2, y2, dx, dy -> c.set(x2, y2, 24f + 30f * (-dx - dy).coerceAtLeast(0f), 26f + 32f * (-dx - dy).coerceAtLeast(0f), 34f + 36f * (-dx - dy).coerceAtLeast(0f)) })
        }
    }
}
