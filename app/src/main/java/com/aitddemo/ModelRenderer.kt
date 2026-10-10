package com.aitddemo

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Caps the internal per-pixel rasterizer resolution regardless of the phone's actual screen
// size - keeps the software depth-buffer fill affordable every frame, and happens to match the
// spirit of the original engine's own 320x200 render target.
private const val MAX_RASTER_DIM = 480

/**
 * Generic real-time rotating render of a flattened body model (vertices + pre-colored
 * triangles, same shape as TatouModel.kt/CarnbyModel.kt/EmilyModel.kt - all exported from the
 * original LISTBODY.PAK/LISTBOD2.PAK/ITD_RESS.PAK body format).
 *
 * Rasterized every frame with a real per-pixel depth buffer (ModelRaster, same technique as the
 * bodyexport/RenderSolidFrame reference renderer already used to validate these models) instead
 * of sorting whole triangles back-to-front - a plain painter's algorithm flickers on mutually
 * overlapping thin geometry (e.g. Emily's fingers), because no single whole-triangle draw order
 * is correct for every pixel at once.
 */
/** A body drawn at a given turning [angle] (radians), for views the player turns by hand: no animation of its own. */
@Composable
fun ModelView(
    vertices: FloatArray,
    triangles: IntArray,
    angle: Float,
    modifier: Modifier = Modifier,
    zoom: Float = 1f,
    panX: Float = 0f,
    panY: Float = 0f,
    pitch: Float = 0f,
) {
    val raster = remember { ModelRaster() }
    Canvas(modifier = modifier.fillMaxSize()) {
        if (size.width > 0f && size.height > 0f) {
            raster.render(vertices, triangles, angle, size.width, size.height, zoom, panX, panY, pitch)
            drawImage(image = raster.bitmap.asImageBitmap(), dstSize = IntSize(size.width.toInt(), size.height.toInt()))
        }
    }
}

@Composable
fun ModelSpinner(
    vertices: FloatArray,
    triangles: IntArray,
    rotationSpeed: Float,
    modifier: Modifier = Modifier,
    zoom: Float = 1f,
    panX: Float = 0f,
    panY: Float = 0f,
    autoRotate: Boolean = true,
    initialAngle: Float = 0f
) {
    var angle by remember { mutableFloatStateOf(initialAngle) }

    // autoRotate=false holds the model at a fixed angle (e.g. a still shot composited into the
    // intro sequence's fixed camera views) instead of continuously spinning - used wherever the
    // "camera" itself is meant to be static, matching the original engine's fixed-camera rooms.
    //
    // BUG FIX (user report: "Start/Stop in der großen Ansicht hat keinerlei Funktion"): this used to
    // be keyed on `autoRotate` alone. At every call site `autoRotate` is a constant `true`, so the
    // effect launches exactly once and never restarts - it keeps using the STALE `rotationSpeed` it
    // captured at that first launch forever, so toggling Start/Stop (which only changes the
    // `rotationSpeed` argument passed in) had no visible effect on the already-running coroutine.
    // Keying on both restarts the loop (picking up the fresh rotationSpeed) whenever either changes -
    // matching how AnimatedModelSpinner's own separate auto-spin effect already does this correctly.
    LaunchedEffect(autoRotate, rotationSpeed) {
        if (!autoRotate) return@LaunchedEffect
        var lastNanos = 0L
        while (true) {
            val nanos = withFrameNanos { it }
            if (lastNanos != 0L) {
                val deltaSeconds = (nanos - lastNanos) / 1_000_000_000f
                angle += deltaSeconds * rotationSpeed
            }
            lastNanos = nanos
        }
    }

    val raster = remember { ModelRaster() }

    Canvas(modifier = modifier.fillMaxSize()) {
        if (size.width > 0f && size.height > 0f) {
            raster.render(vertices, triangles, angle, size.width, size.height, zoom, panX, panY)
            drawImage(
                image = raster.bitmap.asImageBitmap(),
                dstSize = IntSize(size.width.toInt(), size.height.toInt())
            )
        }
    }
}

/**
 * Like [ModelSpinner], but the posed vertices are recomposed every frame from a real keyframe
 * animation (SkeletalAnimation.kt) instead of being a fixed static mesh - used for the intro's
 * driver head and the Model Viewer's "Driver" gallery entry (the only model in this app that plays
 * a genuine original animation rather than just spinning/holding a single rest pose). [progress]
 * is 0..1 over the whole animation and is driven by the caller (e.g. an Animatable tied to a
 * shot's duration, or a looping infinite transition for the gallery), matching how the intro's
 * path-driven movement already hoists its own progress value. [rotationSpeed] optionally adds a
 * continuous auto-rotation on top of the fixed [viewAngle] (same technique as [ModelSpinner]'s own
 * auto-rotate) - left at 0 for the intro's fixed-camera shot, nonzero for the gallery so it spins
 * like every other model there while also playing its real animation.
 */
@Composable
fun AnimatedModelSpinner(
    restVertices: FloatArray,
    groups: IntArray,
    groupOrder: IntArray,
    animFrames: IntArray,
    animNumGroups: Int,
    animCacheKey: String,
    triangles: IntArray,
    progress: Float,
    modifier: Modifier = Modifier,
    viewAngle: Float = 0f,
    rotationSpeed: Float = 0f,
    zoom: Float = 1f,
    panX: Float = 0f,
    panY: Float = 0f,
    // Seamless-loop closing segment (see animatedVertices' own doc comment) - true for anything
    // played on a repeating loop (the gallery's demo animations), false for genuine one-shot
    // playback (the Intro's non-repeating driver-head animation) where there's no next cycle to
    // close smoothly into.
    loop: Boolean = false
) {
    var autoAngle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(rotationSpeed) {
        if (rotationSpeed == 0f) return@LaunchedEffect
        var lastNanos = 0L
        while (true) {
            val nanos = withFrameNanos { it }
            if (lastNanos != 0L) {
                val deltaSeconds = (nanos - lastNanos) / 1_000_000_000f
                autoAngle += deltaSeconds * rotationSpeed
            }
            lastNanos = nanos
        }
    }

    val raster = remember { ModelRaster() }
    // BUG FIX (found while adding the Model Viewer's "Lamp" toggle, which swaps restVertices/groups/
    // triangles to a DIFFERENT body - e.g. CarnbyWithLampAnimatedBody - while keeping the SAME
    // animCacheKey, since it's still the same underlying animation): this used to be keyed only on
    // (animCacheKey, progress), so switching to a different body at the same progress value returned
    // a STALE cached pose computed from the PREVIOUS body's restVertices/groups - at best a wrong
    // pose, at worst a vertex-count mismatch against the new triangles array. Kotlin arrays compare
    // by reference by default, so adding restVertices as a key correctly forces a recompute whenever
    // the body itself changes, while leaving the common case (same body, only progress changing every
    // frame) just as cheap as before.
    val posedVertices = remember(animCacheKey, progress, restVertices) {
        animatedVertices(restVertices, groups, groupOrder, animFrames, animNumGroups, animCacheKey, progress, loop)
    }
    Canvas(modifier = modifier.fillMaxSize()) {
        if (size.width > 0f && size.height > 0f) {
            raster.render(posedVertices, triangles, viewAngle + autoAngle, size.width, size.height, zoom, panX, panY)
            drawImage(
                image = raster.bitmap.asImageBitmap(),
                dstSize = IntSize(size.width.toInt(), size.height.toInt())
            )
        }
    }
}

/** Reusable pixel/depth buffers so a fresh array isn't allocated on every single frame. */
private class ModelRaster {
    private var width = 0
    private var height = 0
    private var pixels = IntArray(0)
    private var depth = FloatArray(0)
    var bitmap: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        private set

    fun render(
        verts: FloatArray,
        tris: IntArray,
        angle: Float,
        canvasW: Float,
        canvasH: Float,
        zoom: Float,
        panX: Float,
        panY: Float,
        pitch: Float = 0f,
    ) {
        val aspect = canvasW / canvasH
        val w: Int
        val h: Int
        if (canvasW >= canvasH) {
            w = min(canvasW.toInt(), MAX_RASTER_DIM).coerceAtLeast(1)
            h = (w / aspect).toInt().coerceAtLeast(1)
        } else {
            h = min(canvasH.toInt(), MAX_RASTER_DIM).coerceAtLeast(1)
            w = (h * aspect).toInt().coerceAtLeast(1)
        }
        if (w != width || h != height) {
            width = w
            height = h
            pixels = IntArray(w * h)
            depth = FloatArray(w * h)
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }

        pixels.fill(0) // fully transparent - the screen behind (always solid black here) shows through
        depth.fill(Float.MAX_VALUE)

        val n = verts.size / 3
        val cosA = cos(angle)
        val sinA = sin(angle)
        val rx = FloatArray(n)
        val ry = FloatArray(n)
        val rz = FloatArray(n)
        for (i in 0 until n) {
            val x = verts[i * 3]
            val y = verts[i * 3 + 1]
            val z = verts[i * 3 + 2]
            rx[i] = x * cosA + z * sinA
            rz[i] = -x * sinA + z * cosA
            ry[i] = -y // engine space is y-down; flip so the model renders upright
        }
        if (pitch != 0f) { // tipped forwards or backwards (a view the player turns by hand)
            val cp = cos(pitch)
            val sp = sin(pitch)
            for (i in 0 until n) {
                val y = ry[i]
                val z = rz[i]
                ry[i] = y * cp - z * sp
                rz[i] = y * sp + z * cp
            }
        }

        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until n) {
            if (rx[i] < minX) minX = rx[i]
            if (rx[i] > maxX) maxX = rx[i]
            if (ry[i] < minY) minY = ry[i]
            if (ry[i] > maxY) maxY = ry[i]
        }
        val spanX = (maxX - minX).coerceAtLeast(1f)
        val spanY = (maxY - minY).coerceAtLeast(1f)
        val scale = min(w / spanX, h / spanY) * 0.8f * zoom
        // panX/panY arrive in real screen-pixel units (from the gesture detector, matching
        // canvasW/canvasH) - rescale into this frame's (possibly downsampled) raster units.
        val cx = w / 2f + panX * (w / canvasW)
        val cy = h / 2f + panY * (h / canvasH)
        val midX = (minX + maxX) / 2f
        val midY = (minY + maxY) / 2f

        val sx = FloatArray(n)
        val sy = FloatArray(n)
        for (i in 0 until n) {
            sx[i] = cx + (rx[i] - midX) * scale
            sy[i] = cy - (ry[i] - midY) * scale
        }

        val lightX = -0.4f; val lightY = -0.6f; val lightZ = -0.7f
        val triCount = tris.size / 4

        for (t in 0 until triCount) {
            val a = tris[t * 4]; val b = tris[t * 4 + 1]; val c = tris[t * 4 + 2]
            val colorInt = tris[t * 4 + 3]

            val ax = sx[a]; val ay = sy[a]
            val bx = sx[b]; val by = sy[b]
            val cxp = sx[c]; val cyp = sy[c]

            val minPx = max(0, floor(min(ax, min(bx, cxp))).toInt())
            val maxPx = min(w - 1, ceil(max(ax, max(bx, cxp))).toInt())
            val minPy = max(0, floor(min(ay, min(by, cyp))).toInt())
            val maxPy = min(h - 1, ceil(max(ay, max(by, cyp))).toInt())
            if (minPx > maxPx || minPy > maxPy) continue

            val denom = (by - cyp) * (ax - cxp) + (cxp - bx) * (ay - cyp)
            if (denom > -1e-6f && denom < 1e-6f) continue

            val ux = rx[b] - rx[a]; val uy = ry[b] - ry[a]; val uz = rz[b] - rz[a]
            val vx = rx[c] - rx[a]; val vy = ry[c] - ry[a]; val vz = rz[c] - rz[a]
            var nx = uy * vz - uz * vy
            var ny = uz * vx - ux * vz
            var nz = ux * vy - uy * vx
            val len = sqrt(nx * nx + ny * ny + nz * nz).let { if (it < 1e-6f) 1f else it }
            nx /= len; ny /= len; nz /= len
            var lambert = nx * lightX + ny * lightY + nz * lightZ
            lambert = 0.35f + 0.65f * lambert.coerceIn(0f, 1f)

            val baseR = (colorInt shr 16) and 0xFF
            val baseG = (colorInt shr 8) and 0xFF
            val baseB = colorInt and 0xFF
            val shadedR = (baseR * lambert).toInt().coerceIn(0, 255)
            val shadedG = (baseG * lambert).toInt().coerceIn(0, 255)
            val shadedB = (baseB * lambert).toInt().coerceIn(0, 255)
            val shadedColor = (0xFF shl 24) or (shadedR shl 16) or (shadedG shl 8) or shadedB

            val za = rz[a]; val zb = rz[b]; val zc = rz[c]

            for (py in minPy..maxPy) {
                val pyf = py + 0.5f
                val rowBase = py * w
                for (px in minPx..maxPx) {
                    val pxf = px + 0.5f
                    val w1 = ((by - cyp) * (pxf - cxp) + (cxp - bx) * (pyf - cyp)) / denom
                    val w2 = ((cyp - ay) * (pxf - cxp) + (ax - cxp) * (pyf - cyp)) / denom
                    val w3 = 1f - w1 - w2
                    if (w1 < -0.001f || w2 < -0.001f || w3 < -0.001f) continue

                    val z = w1 * za + w2 * zb + w3 * zc
                    val idx = rowBase + px
                    if (z < depth[idx]) {
                        depth[idx] = z
                        pixels[idx] = shadedColor
                    }
                }
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }
}
