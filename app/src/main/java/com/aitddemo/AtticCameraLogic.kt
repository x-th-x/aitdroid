package com.aitddemo

// Camera selection and world-to-screen projection, following the original engine's algorithms. All camera and zone
// data is read from the user's own game files at runtime (see OriginalStage.kt); nothing is embedded here.

data class AtticCameraZonePoint(val x: Float, val z: Float)

data class AtticCameraDef(
    val index: Int,
    val posX: Float,
    val posY: Float,
    val posZ: Float,
    val angleAlpha: Int,
    val angleBeta: Int,
    val angleGamma: Int,
    val focal1: Float,
    val focal2: Float,
    val focal3: Float,
    /** Zone polygons (already room-relative, NOT yet divided by 10 - see [isPointInZone]). */
    val zones: List<List<AtticCameraZonePoint>>,
    /** World position of the room the camera is used from (stage 0 room 0 is at the origin); the projection subtracts it. */
    val roomX: Float = 0f,
    val roomY: Float = 0f,
    val roomZ: Float = 0f,
    /** The rooms this camera sees (its table of viewed rooms): objects of those rooms are drawn with it (checkActorInRoom); empty = not known. */
    val viewedRooms: Set<Int> = emptySet(),
)

/** The room's first camera is active at the start. */
const val ATTIC_INITIAL_CAMERA_INDEX = 0

// Exact port of changeCameraSub1Sub1: does segment (x1,z1)-(x2,z2) cross segment (x3,z3)-(x4,z4)?
private fun segmentsCross(
    x1: Float, z1: Float, x2: Float, z2: Float,
    x3: Float, z3: Float, x4: Float, z4: Float
): Boolean {
    val var1 = x1 - x2; val var2 = z3 - z4; val var3 = x3 - x4; val var4 = z1 - z2
    val var5 = x1 - x3; val var6 = z1 - z3
    var result1 = (var1 * var2) - (var3 * var4)
    if (result1 == 0f) return false
    var result2 = (var5 * var2) - (var3 * var6)
    var result3 = (-var1 * var6) + (var5 * var4)
    if (result1 < 0f) { result1 = -result1; result2 = -result2; result3 = -result3 }
    if (result2 <= 0f || result3 <= 0f) return false
    return result1 > result2 && result1 > result3
}

// Exact port of changeCameraSub1: is (xMid,zMid) inside any polygon of this camera's zone list?
// xMid/zMid must already be in the same divided-by-10 space as the zone polygon points.
private fun isPointInZone(xMid: Float, zMid: Float, polygons: List<List<AtticCameraZonePoint>>): Boolean {
    for (poly in polygons) {
        if (poly.size < 2) continue
        var flag = 0
        for (j in 0 until poly.size) {
            val a = poly[j]
            val b = poly[(j + 1) % poly.size]
            if (segmentsCross(xMid, zMid, xMid - 10000f, zMid, a.x, a.z, b.x, b.z)) flag = flag or 1
            if (segmentsCross(xMid, zMid, xMid + 10000f, zMid, a.x, a.z, b.x, b.z)) flag = flag or 2
        }
        if (flag == 3) return true
    }
    return false
}

/** Plain "is this world position within this camera's zone" test. */
fun isInAtticZone(camera: AtticCameraDef, worldX: Float, worldZ: Float): Boolean =
    isPointInZone(worldX / 10f, worldZ / 10f, camera.zones)

/**
 * Live per-frame camera resolution - call every frame with the box (x1, x2, z1, z2 in world units) and the angle of the camera's target and
 * the previously-active camera index (-1 in a room that was just entered); returns the camera index that should be active NOW.
 *
 * Matches the original's "sticky" behavior exactly: the current camera's own zone is checked
 * FIRST and wins even if another camera's zone would also match, and if no zone matches at all,
 * the camera simply stays unchanged (`changeCameraSub2` returning -1 changes nothing).
 */
fun resolveAtticCamera(x1: Int, x2: Int, z1: Int, z2: Int, beta: Int, currentCameraIndex: Int, cameras: List<AtticCameraDef>): Int {
    // the zone test of the original (isInPoly) takes the middle of the box of the camera's target, each corner divided by 10 first (the zone polygons are in tenths)
    val px = (((x1 / 10) + (x2 / 10)) / 2).toFloat()
    val pz = (((z1 / 10) + (z2 / 10)) / 2).toFloat()

    val current = cameras.getOrNull(currentCameraIndex)
    if (current != null && isPointInZone(px, pz, current.zones)) {
        return currentCameraIndex
    }
    // findBestCamera of the original: of the cameras whose zone holds the target, the one that looks best at it wins (smallest |beta of the target + (camera beta + 0x200) & 0x3FF|)
    var best = -1
    var bestAngle = 32000
    for (cam in cameras) {
        if (!isPointInZone(px, pz, cam.zones)) continue
        var angle = beta + ((cam.angleBeta + 0x200) and 0x3FF)
        if (angle < 0) angle = -angle
        if (angle < bestAngle) {
            bestAngle = angle
            best = cam.index
        }
    }
    return if (best != -1) best else currentCameraIndex // no zone matched at all -> unchanged, exactly as the original does
}

// World -> screen projection (the engine's setupCamera()/projection formula).

/** Rotation state of one object or camera (engine rotation convention, angles in 1024ths of a turn). */
data class RotState(
    val hasX: Boolean, val hasY: Boolean, val hasZ: Boolean,
    val xCos: Float, val xSin: Float, val yCos: Float, val ySin: Float, val zCos: Float, val zSin: Float
)

fun initRot(tx: Int, ty: Int, tz: Int): RotState {
    // The engine's "cosTable" is really a sine table, so the pair is (sin, cos) of the angle.
    fun cosSin(angle: Int): Pair<Float, Float> {
        val theta = (angle and 0x3FF) * (2.0 * Math.PI / 1024.0)
        val cosLike = kotlin.math.sin(theta).toFloat() // matches cosTable[angle]
        val sinLike = kotlin.math.cos(theta).toFloat() // matches cosTable[(angle+0x100)&0x3FF]
        return cosLike to sinLike
    }
    val hasX = tx != 0; val hasY = ty != 0; val hasZ = tz != 0
    val (xCos, xSin) = if (hasX) cosSin(tx) else 0f to 0f
    val (yCos, ySin) = if (hasY) cosSin(ty) else 0f to 0f
    val (zCos, zSin) = if (hasZ) cosSin(tz) else 0f to 0f
    return RotState(hasX, hasY, hasZ, xCos, xSin, yCos, ySin, zCos, zSin)
}

fun rotatePoint(px: Float, py: Float, pz: Float, rot: RotState): Triple<Float, Float, Float> {
    var x = px; var y = py; var z = pz
    if (rot.hasY) {
        val tx = x; val tz = z
        x = tx * rot.ySin - tz * rot.yCos
        z = tx * rot.yCos + tz * rot.ySin
    }
    if (rot.hasX) {
        val ty = y; val tz = z
        y = ty * rot.xSin - tz * rot.xCos
        z = ty * rot.xCos + tz * rot.xSin
    }
    if (rot.hasZ) {
        val tx = x; val ty = y
        x = tx * rot.zSin - ty * rot.zCos
        y = tx * rot.zCos + ty * rot.zSin
    }
    return Triple(x, y, z)
}

/** Result of [projectToScreen]: plate fractions (0..1 = visible) plus [scaleProxy], the apparent size at that depth. */
data class ScreenProjection(val fracX: Float, val fracY: Float, val depthZ: Float, val scaleProxy: Float)

/** Projects a world point onto [camera]'s screen, or null if it is behind the camera. */
fun projectToScreen(camera: AtticCameraDef, worldX: Float, worldY: Float, worldZ: Float): ScreenProjection? {
    val translateX = camera.posX * 10f
    val translateY = -camera.posY * 10f
    val translateZ = -camera.posZ * 10f
    val relX = worldX - translateX
    val relY = worldY - translateY
    val relZ = worldZ - translateZ
    val rot = initRot(camera.angleAlpha, camera.angleBeta, camera.angleGamma)
    val (rx, ry, rz) = rotatePoint(relX, relY, relZ, rot)
    val zFinal = rz + camera.focal1
    if (zFinal <= 200f) return null
    val screenX = (rx * camera.focal2) / zFinal + 160f
    val screenY = (ry * camera.focal3) / zFinal + 100f
    val fracY = screenY / 200f
    return ScreenProjection(screenX / 320f, fracY, zFinal, camera.focal2 / zFinal)
}

/**
 * Apparent (width, height) of an object of [heightUnits]/[widthUnits] at depth [zFinal] under [camera], as fractions of
 * the 320x200 plate (apparent size = real size * focal / depth), times a uniform display scale.
 */
const val GLOBAL_SIZE_SCALE = 1.875f

fun realApparentSize(camera: AtticCameraDef, zFinal: Float, heightUnits: Float, widthUnits: Float): Pair<Float, Float> {
    if (zFinal <= 0f) return 0.2f to 0.32f // degenerate depth (shouldn't normally happen) - safe fallback
    val height = (heightUnits * camera.focal3 / zFinal) / 200f * GLOBAL_SIZE_SCALE
    val width = (widthUnits * camera.focal2 / zFinal) / 320f * GLOBAL_SIZE_SCALE
    // sanity clamp only
    return width.coerceIn(0.01f, 0.7f) to height.coerceIn(0.03f, 1.2f)
}
