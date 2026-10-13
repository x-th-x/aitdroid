package com.aitddemo

import kotlin.math.cos

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

    /** The place of the camera in room units (translateX, Y, Z of the original). */
    val camX: Int get() = tx.toInt()
    val camY: Int get() = ty.toInt()
    val camZ: Int get() = tz.toInt()

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
