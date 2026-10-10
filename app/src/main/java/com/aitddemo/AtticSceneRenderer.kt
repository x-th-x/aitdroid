package com.aitddemo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One thing drawn into the room: a body whose vertices are already in WORLD coordinates (engine units, y down),
 * its triangles (a, b, c, ARGB colour per triangle), the origin used for draw order
 * and its ZV in tenths ([zvX1], [zvX2], [zvZ1], [zvZ2]) which decides which background masks cover it.
 */
class AtticActor(
    val worldVertices: FloatArray,
    val triangles: IntArray,
    val originX: Float,
    val originY: Float,
    val originZ: Float,
    val zvX1: Int,
    val zvX2: Int,
    val zvZ1: Int,
    val zvZ2: Int,
    /** Line primitives (vertex a, vertex b, ARGB per line) and spheres (centre vertex, radius, ARGB per sphere) of the body. */
    val lines: IntArray = IntArray(0),
    val spheres: IntArray = IntArray(0),
) {
    /** Flat things lying on the floor (rug, trap door lid) are drawn before everything that stands on them. */
    val isFlat: Boolean = run {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (i in 0 until worldVertices.size / 3) {
            lo = min(lo, worldVertices[i * 3 + 1]); hi = max(hi, worldVertices[i * 3 + 1])
        }
        hi - lo < 100f
    }
}

/** World -> screen projection of the original engine for one camera (same maths as in the Time Gate port, which was verified against the photos). */
class AtticProjector(private val cam: AtticCameraDef) {
    private val tx = (cam.posX - cam.roomX) * 10f
    private val ty = (cam.roomY - cam.posY) * 10f
    private val tz = (cam.roomZ - cam.posZ) * 10f
    private val bc = rc(cam.angleBeta); private val bs = rs(cam.angleBeta)
    private val ac = rc(cam.angleAlpha); private val asn = rs(cam.angleAlpha)
    private val gc = rc(cam.angleGamma); private val gs = rs(cam.angleGamma)
    private val hasB = cam.angleBeta and 0x3FF != 0
    private val hasA = cam.angleAlpha and 0x3FF != 0
    private val hasG = cam.angleGamma and 0x3FF != 0

    private fun rc(a: Int) = cos((a and 0x3FF) * 2.0 * Math.PI / 1024.0).toFloat()
    private fun rs(a: Int) = kotlin.math.sin((a and 0x3FF) * 2.0 * Math.PI / 1024.0).toFloat()

    /** Writes the camera-space position (x, y, depth z including the projection distance) of a world point into [out]. */
    fun camera(wx: Float, wy: Float, wz: Float, out: FloatArray) {
        var x = wx - tx
        var y = wy - ty
        var z = wz - tz
        if (hasB) { val nx = x * bc - z * bs; z = x * bs + z * bc; x = nx }
        if (hasA) { val ny = y * ac - z * asn; z = y * asn + z * ac; y = ny }
        if (hasG) { val nx = x * gc - y * gs; y = x * gs + y * gc; x = nx }
        out[0] = x
        out[1] = y
        out[2] = z + cam.focal1
    }

    /** Horizontal focal length (pixels per unit at depth 1) for sizing round parts. */
    val focalX: Float get() = cam.focal2
    val focalY: Float get() = cam.focal3

    /** Screen position (320x200 plate units) of a camera-space point with depth z > 0. */
    fun screen(x: Float, y: Float, z: Float, out: FloatArray) {
        out[0] = x * cam.focal2 / z + 160f
        out[1] = y * cam.focal3 / z + 100f
    }

    /** Writes (screenX, screenY, depth) in 320x200 plate units into [out]; false when the point is too close to / behind the camera. */
    fun project(wx: Float, wy: Float, wz: Float, out: FloatArray): Boolean {
        camera(wx, wy, wz, out)
        if (out[2] <= NEAR) return false
        screen(out[0], out[1], out[2], out)
        return true
    }

    companion object {
        const val NEAR = 200f
    }
}

/**
 * Draws the room like the original: the camera photo, then every actor (far to near) through the camera matrix, then
 * the background masks that cover the actor (polygons cut out of the photo, shown again over the actor).
 * Renders at 2x the 320x200 plate.
 */
class AtticSceneRenderer {
    val bitmap: Bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val paint = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL }
    private val path = Path()
    private val maskPaths = HashMap<AtticData.Mask, Path>()
    private val p0 = FloatArray(3)

    /** One thing to draw: a triangle (kind 0), a line (kind 1, from a to b) or a filled circle (kind 2, centre a, radius in [bx]); sorted by depth with the others. */
    private class Tri(val depth: Float, val ax: Float, val ay: Float, val bx: Float, val by: Float, val cx: Float, val cy: Float, val color: Int, val kind: Int = 0, val pts: FloatArray? = null)

    /** A plain picture without actors (for example a full-screen picture of the game). */
    fun renderPicture(picture: Bitmap) {
        canvas.drawBitmap(picture, 0f, 0f, null)
    }

    /** [background] must be the camera photo scaled to [W] x [H]. */
    fun render(
        camera: AtticCameraDef,
        background: Bitmap,
        actors: List<AtticActor>,
        masks: List<AtticData.Mask> = AtticData.MASKS.getOrNull(camera.index) ?: emptyList(),
    ) {
        canvas.drawBitmap(background, 0f, 0f, null)
        val proj = AtticProjector(camera)
        val order = actors.map { a ->
            proj.camera(a.originX, a.originY, a.originZ, p0)
            a to (if (a.isFlat) Float.MAX_VALUE else p0[2])
        }.sortedByDescending { it.second }
        for ((a, _) in order) {
            val box = drawActor(proj, a) ?: continue
            for (m in masks) {
                if (!covers(m, a)) continue
                canvas.save()
                canvas.clipRect(box[0], box[1], box[2], box[3])
                canvas.clipPath(maskPath(m))
                canvas.drawBitmap(background, 0f, 0f, null)
                canvas.restore()
            }
        }
    }

    /** isBgOverlayRequired of the original: the actor's ZV (tenths) must lie completely inside one of the mask's zones. */
    private fun covers(m: AtticData.Mask, a: AtticActor): Boolean {
        var i = 0
        while (i + 3 < m.rects.size) {
            if (a.zvX1 >= m.rects[i] && a.zvZ1 >= m.rects[i + 1] && a.zvX2 <= m.rects[i + 2] && a.zvZ2 <= m.rects[i + 3]) return true
            i += 4
        }
        return false
    }

    private fun maskPath(m: AtticData.Mask): Path = maskPaths.getOrPut(m) {
        Path().also { p ->
            for (poly in m.polys) {
                if (poly.size < 6) continue
                p.moveTo((poly[0] * S).toFloat(), (poly[1] * S).toFloat())
                var i = 2
                while (i + 1 < poly.size) { p.lineTo((poly[i] * S).toFloat(), (poly[i + 1] * S).toFloat()); i += 2 }
                p.close()
            }
        }
    }

    /** Draws one actor; returns its screen box (left, top, right, bottom) in pixels, or null if nothing was visible. */
    private fun drawActor(proj: AtticProjector, a: AtticActor): FloatArray? {
        val n = a.worldVertices.size / 3
        // camera space position of every vertex
        val cx = FloatArray(n)
        val cy = FloatArray(n)
        val cz = FloatArray(n)
        for (i in 0 until n) {
            proj.camera(a.worldVertices[i * 3], a.worldVertices[i * 3 + 1], a.worldVertices[i * 3 + 2], p0)
            cx[i] = p0[0]; cy[i] = p0[1]; cz[i] = p0[2]
        }
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        val tris = ArrayList<Tri>(a.triangles.size / 4)
        val v = a.worldVertices
        // The polygons of a body arrive as fans of triangles (same first corner, same colour, each continuing the last); the original draws a polygon as one
        // piece and sorts whole polygons, so the fans are put back together instead of sorting their triangles one by one.
        val tr = a.triangles
        val total = tr.size / 4
        val ids = IntArray(total + 2)
        var poly = FloatArray(32 * 3)
        var k = 0
        while (k < total) {
            val first = tr[k * 4]
            val col = tr[k * 4 + 3]
            var count = 0
            ids[count++] = first; ids[count++] = tr[k * 4 + 1]; ids[count++] = tr[k * 4 + 2]
            var j = k + 1
            while (j < total && tr[j * 4] == first && tr[j * 4 + 1] == ids[count - 1] && tr[j * 4 + 3] == col) {
                ids[count++] = tr[j * 4 + 2]
                j++
            }
            k = j
            if (poly.size < (count + 2) * 3) poly = FloatArray((count + 2) * 3)
            val m = clipPolygon(cx, cy, cz, ids, count, poly)
            if (m < 3) continue
            val pts = FloatArray(m * 2)
            var depth = 0f
            for (q in 0 until m) {
                proj.screen(poly[q * 3], poly[q * 3 + 1], poly[q * 3 + 2], p0)
                pts[q * 2] = p0[0] * S; pts[q * 2 + 1] = p0[1] * S
                depth += poly[q * 3 + 2]
                l = min(l, pts[q * 2]); r = max(r, pts[q * 2]); t = min(t, pts[q * 2 + 1]); b = max(b, pts[q * 2 + 1])
            }
            tris.add(Tri(depth / m, 0f, 0f, 0f, 0f, 0f, 0f, col, 3, pts))
        }
        // lines: cut at the near plane, drawn two pixels wide (one plate pixel)
        var li = 0
        while (li + 2 < a.lines.size) {
            val ia = a.lines[li]; val ib = a.lines[li + 1]; val col = a.lines[li + 2]
            li += 3
            if (cz[ia] < CLIP && cz[ib] < CLIP) continue
            var ax = cx[ia]; var ay = cy[ia]; var az = cz[ia]
            var bx = cx[ib]; var by = cy[ib]; var bz = cz[ib]
            if (az < CLIP) { val f = (CLIP - az) / (bz - az); ax += (bx - ax) * f; ay += (by - ay) * f; az = CLIP }
            if (bz < CLIP) { val f = (CLIP - bz) / (az - bz); bx += (ax - bx) * f; by += (ay - by) * f; bz = CLIP }
            proj.screen(ax, ay, az, p0); val sax = p0[0] * S; val say = p0[1] * S
            proj.screen(bx, by, bz, p0); val sbx = p0[0] * S; val sby = p0[1] * S
            tris.add(Tri((az + bz) / 2f - 0.5f, sax, say, sbx, sby, 0f, 0f, col, 1))
            l = min(l, min(sax, sbx)); r = max(r, max(sax, sbx)); t = min(t, min(say, sby)); b = max(b, max(say, sby))
        }
        // spheres: a filled circle whose radius follows the perspective
        var si = 0
        while (si + 2 < a.spheres.size) {
            val ic = a.spheres[si]; val radius = a.spheres[si + 1]; val col = a.spheres[si + 2]
            si += 3
            if (cz[ic] < CLIP) continue
            proj.screen(cx[ic], cy[ic], cz[ic], p0)
            val sx = p0[0] * S; val sy = p0[1] * S
            // radius > 0: sphere (units, scaled by perspective); -2 point (a dot of one plate pixel), -6 big point, -7 zixel (20 units)
            val pr = when {
                radius > 0 -> max(1f, radius * proj.focalX / cz[ic] * S)
                radius == -7 -> max(1f, 20f * proj.focalX / cz[ic] * S)
                radius == -6 -> S.toFloat()
                else -> S * 0.75f
            }
            // the evaporation bubbles (alpha code 0xFB) and the smoke (0xFA, half transparent) are ellipses: the vertical radius uses the vertical focal length
            val pry = if (col ushr 24 == 0xFB || col ushr 24 == 0xFA) pr * proj.focalY / proj.focalX else 0f
            tris.add(Tri(cz[ic] - 0.5f, sx, sy, pr, pry, 0f, 0f, col, 2))
            l = min(l, sx - pr); r = max(r, sx + pr); t = min(t, sy - max(pr, pry)); b = max(b, sy + max(pr, pry))
        }
        if (tris.isEmpty()) return null
        tris.sortByDescending { it.depth }
        for (tri in tris) {
            // transparent material (alpha code 0xFA, window glass): half covering
            paint.color = if (tri.color ushr 24 == 0xFA) (tri.color and 0xFFFFFF) or (0x80 shl 24) else tri.color
            when (tri.kind) {
                0 -> {
                    paint.shader = if (tri.color ushr 24 == 0xFE) ditherShader(tri.color or (0xFF shl 24)) else null
                    path.reset()
                    path.moveTo(tri.ax, tri.ay); path.lineTo(tri.bx, tri.by); path.lineTo(tri.cx, tri.cy); path.close()
                    canvas.drawPath(path, paint)
                    paint.shader = null
                }
                3 -> {
                    val q = tri.pts!!
                    paint.shader = when (tri.color ushr 24) {
                        0xFE -> ditherShader(tri.color or (0xFF shl 24))
                        0xFD, 0xFC, 0xFB -> rampShader(tri.color, q)
                        else -> null
                    }
                    path.reset()
                    path.moveTo(q[0], q[1])
                    var i = 2
                    while (i + 1 < q.size) { path.lineTo(q[i], q[i + 1]); i += 2 }
                    path.close()
                    canvas.drawPath(path, paint)
                    paint.shader = null
                }
                1 -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = S.toFloat()
                    canvas.drawLine(tri.ax, tri.ay, tri.bx, tri.by, paint)
                    paint.style = Paint.Style.FILL
                }
                else -> {
                    val ramp = if (tri.color ushr 24 == 0xFB) BodyRamps.ramps[tri.color] else null
                    if (ramp != null) {
                        // a bubble of the evaporation: an ellipse filled with the 16 colours of its palette bank from left (light) to right (dark), polygon type 3 of the original
                        val colors = IntArray(32); val positions = FloatArray(32)
                        for (q in 0 until 16) { colors[q * 2] = ramp[q]; colors[q * 2 + 1] = ramp[q]; positions[q * 2] = q / 16f; positions[q * 2 + 1] = (q + 1) / 16f }
                        positions[31] = 1f
                        paint.shader = android.graphics.LinearGradient(tri.ax - tri.bx, 0f, tri.ax + tri.bx, 0f, colors, positions, android.graphics.Shader.TileMode.CLAMP)
                        canvas.drawOval(tri.ax - tri.bx, tri.ay - tri.by, tri.ax + tri.bx, tri.ay + tri.by, paint)
                        paint.shader = null
                    } else if (tri.by > 0f) {
                        canvas.drawOval(tri.ax - tri.bx, tri.ay - tri.by, tri.ax + tri.bx, tri.ay + tri.by, paint)
                    } else {
                        canvas.drawCircle(tri.ax, tri.ay, tri.bx, paint)
                    }
                }
            }
        }
        return floatArrayOf(max(0f, l - 2f), max(0f, t - 2f), min(W.toFloat(), r + 2f), min(H.toFloat(), b + 2f))
    }

    /** Clips the polygon ids[0 until n] in camera space against the near plane (Sutherland-Hodgman); writes the (x, y, z) points to [out], returns their count. */
    private fun clipPolygon(cx: FloatArray, cy: FloatArray, cz: FloatArray, ids: IntArray, n: Int, out: FloatArray): Int {
        var count = 0
        for (i in 0 until n) {
            val p = ids[i]
            val q = ids[(i + 1) % n]
            val pIn = cz[p] >= CLIP
            val qIn = cz[q] >= CLIP
            if (pIn) {
                out[count * 3] = cx[p]; out[count * 3 + 1] = cy[p]; out[count * 3 + 2] = cz[p]
                count++
            }
            if (pIn != qIn) {
                val f = (CLIP - cz[p]) / (cz[q] - cz[p])
                out[count * 3] = cx[p] + (cx[q] - cx[p]) * f
                out[count * 3 + 1] = cy[p] + (cy[q] - cy[p]) * f
                out[count * 3 + 2] = CLIP
                count++
            }
        }
        return count
    }

    /** Clips the triangle (ia, ib, ic) in camera space against the near plane; writes up to four (x, y, z) points to [out], returns their count. */
    /**
     * Colour ramp of the polygon materials 3 ("marble", left to right over the width of the polygon), 4 ("copper", one colour per scanline from the top) and 5
     * (two scanlines per colour); the ramp is the 16 colours of the palette bank, the last one stays.
     */
    private fun rampShader(color: Int, pts: FloatArray): android.graphics.Shader {
        val ramp = BodyRamps.ramps[color] ?: return android.graphics.LinearGradient(0f, 0f, 1f, 0f, color or (0xFF shl 24), color or (0xFF shl 24), android.graphics.Shader.TileMode.CLAMP)
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var i = 0
        while (i + 1 < pts.size) { minX = min(minX, pts[i]); maxX = max(maxX, pts[i]); minY = min(minY, pts[i + 1]); i += 2 }
        val colors = IntArray(32)
        val positions = FloatArray(32)
        for (k in 0 until 16) {
            colors[k * 2] = ramp[k]; colors[k * 2 + 1] = ramp[k]
            positions[k * 2] = k / 16f; positions[k * 2 + 1] = (k + 1) / 16f
        }
        positions[31] = 1f
        return when (color ushr 24) {
            0xFD -> android.graphics.LinearGradient(minX, 0f, maxX.coerceAtLeast(minX + 1f), 0f, colors, positions, android.graphics.Shader.TileMode.CLAMP)
            0xFC -> android.graphics.LinearGradient(0f, minY, 0f, minY + 16f * S, colors, positions, android.graphics.Shader.TileMode.CLAMP)
            else -> android.graphics.LinearGradient(0f, minY, 0f, minY + 32f * S, colors, positions, android.graphics.Shader.TileMode.CLAMP)
        }
    }

    /** Noise fill of the original's dither material: a small tile of the colour with random brightness per pixel (fixed seed, so it does not flicker). */
    private val dither = HashMap<Int, android.graphics.BitmapShader>()

    private fun ditherShader(base: Int): android.graphics.BitmapShader = dither.getOrPut(base) {
        val rnd = java.util.Random(base.toLong())
        val tile = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        for (y in 0 until 8) for (x in 0 until 8) {
            val f = 0.8f + rnd.nextFloat() * 0.4f
            val r = ((base shr 16 and 0xFF) * f).toInt().coerceIn(0, 255)
            val g = ((base shr 8 and 0xFF) * f).toInt().coerceIn(0, 255)
            val b = ((base and 0xFF) * f).toInt().coerceIn(0, 255)
            tile.setPixel(x, y, (0xFF shl 24) or (r shl 16) or (g shl 8) or b)
        }
        android.graphics.BitmapShader(tile, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
    }

    private fun clipNear(cx: FloatArray, cy: FloatArray, cz: FloatArray, ia: Int, ib: Int, ic: Int, out: FloatArray): Int {
        val idx = intArrayOf(ia, ib, ic)
        var count = 0
        for (i in 0 until 3) {
            val p = idx[i]
            val q = idx[(i + 1) % 3]
            val pIn = cz[p] >= CLIP
            val qIn = cz[q] >= CLIP
            if (pIn) {
                out[count * 3] = cx[p]; out[count * 3 + 1] = cy[p]; out[count * 3 + 2] = cz[p]
                count++
            }
            if (pIn != qIn) {
                val f = (CLIP - cz[p]) / (cz[q] - cz[p])
                out[count * 3] = cx[p] + (cx[q] - cx[p]) * f
                out[count * 3 + 1] = cy[p] + (cy[q] - cy[p]) * f
                out[count * 3 + 2] = CLIP
                count++
            }
        }
        return count
    }

    companion object {
        const val S = 2

        /** Triangles are cut at this camera depth (units); everything nearer is not drawn. */
        const val CLIP = 30f
        const val W = 320 * S
        const val H = 200 * S
    }
}
