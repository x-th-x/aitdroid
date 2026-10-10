package com.aitddemo

import kotlin.math.cos
import kotlin.math.sin

// Keyframe animation playback ported from the original engine's hierarchy composition and interpolation. The pose is
// recomposed every frame from the body's per-group vertices (both parsed from the user's own game files at runtime).

/** One body group's hierarchy metadata, unpacked from the flat 12-int-per-group export format. */
private class BodyGroup(raw: IntArray, i: Int) {
    val start = raw[i * 12]
    val numVertices = raw[i * 12 + 1]
    val baseVertex = raw[i * 12 + 2]
    val orgGroup = raw[i * 12 + 3]
    val numGroup = raw[i * 12 + 4]
    // file-default rest-pose state (used if an animation doesn't cover this group)
    val restStateType = raw[i * 12 + 5]
    val restDeltaX = raw[i * 12 + 6]
    val restDeltaY = raw[i * 12 + 7]
    val restDeltaZ = raw[i * 12 + 8]
}

private class GroupState(val type: Int, val dx: Float, val dy: Float, val dz: Float)

/** One parsed animation keyframe: timestamp (ticks since the previous frame) + per-group state. */
private class AnimFrame(val timestampTicks: Int, val stepX: Float, val stepY: Float, val stepZ: Float, val groups: Array<GroupState>)

/** Parses the flat export format from an export-anim-kotlin-generated object (frames/numGroups). */
private class ParsedAnimation(frames: IntArray, val numGroups: Int) {
    val keyframes: List<AnimFrame>
    val totalTicks: Int
    /** Duration including a synthetic closing segment back to frame 0, for seamless [loop] playback
     * - see [animatedVertices]'s `loop` parameter doc comment for why this closes the "pop" gap. */
    val loopTotalTicks: Int

    init {
        val stride = 4 + numGroups * 4
        val count = frames.size / stride
        val list = ArrayList<AnimFrame>(count)
        var p = 0
        for (f in 0 until count) {
            val timestamp = frames[p]
            val stepX = frames[p + 1].toFloat(); val stepY = frames[p + 2].toFloat(); val stepZ = frames[p + 3].toFloat()
            p += 4
            val groups = Array(numGroups) { g ->
                val type = frames[p]; val dx = frames[p + 1]; val dy = frames[p + 2]; val dz = frames[p + 3]
                p += 4
                GroupState(type, dx.toFloat(), dy.toFloat(), dz.toFloat())
            }
            list.add(AnimFrame(timestamp, stepX, stepY, stepZ, groups))
        }
        keyframes = list
        // frame 0 is the immediate starting pose (the original has no "previous" keyframe to blend
        // in from at animation start - it snaps straight to frame 0), so only frames 1..last
        // contribute real transition segments to the overall duration.
        totalTicks = list.drop(1).sumOf { it.timestampTicks }
        // The closing segment back to frame 0 reuses frame 0's OWN timestamp (its "ticks since the
        // previous frame" value, otherwise unused since nothing transitions INTO the very first
        // frame in non-looping playback) as the duration of the transition FROM the last frame BACK
        // to frame 0 - the same real per-frame timing convention used everywhere else in this file,
        // just applied to the one segment that only exists when looping.
        loopTotalTicks = totalTicks + list[0].timestampTicks
    }
}

private val ANIM_CACHE = HashMap<String, ParsedAnimation>()
private fun parsedAnimationFor(name: String, frames: IntArray, numGroups: Int): ParsedAnimation =
    ANIM_CACHE.getOrPut(name) { ParsedAnimation(frames, numGroups) }

/**
 * Playback duration of an animation in whole engine ticks (frames 1..N-1; frame 0 is the immediate starting pose).
 * One engine tick is 15 ms.
 */
fun animTotalTicks(animFrames: IntArray, animNumGroups: Int, animCacheKey: String, loop: Boolean = false): Int {
    val anim = parsedAnimationFor(animCacheKey, animFrames, animNumGroups)
    return if (loop) anim.loopTotalTicks else anim.totalTicks
}

/**
 * Interpolates the animation at normalized progress [t] (0..1 over its whole duration) and composes the posed vertex
 * array through the group hierarchy. Angles interpolate along the shortest path across the 0/1024 wrap.
 *
 * [loop]: adds a closing segment from the last keyframe back to frame 0, so a repeating [t] does not pop at the wrap.
 */
fun animatedVertices(
    restVertices: FloatArray,
    groupsRaw: IntArray,
    groupOrder: IntArray,
    animFrames: IntArray,
    animNumGroups: Int,
    animCacheKey: String,
    t: Float,
    loop: Boolean = false
): FloatArray {
    val anim = parsedAnimationFor(animCacheKey, animFrames, animNumGroups)
    val groupCount = groupsRaw.size / 12
    val groups = Array(groupCount) { BodyGroup(groupsRaw, it) }

    val lastSegIndex = if (loop) anim.keyframes.size else anim.keyframes.size - 1
    val totalTicksForPlayback = if (loop) anim.loopTotalTicks else anim.totalTicks
    val ticks = t.coerceIn(0f, 1f) * totalTicksForPlayback
    var cumulative = 0f
    var segIndex = lastSegIndex
    for (i in 1..lastSegIndex) {
        // The closing segment (i == keyframes.size, only reachable when looping) transitions INTO
        // frame 0, so both its target frame and its duration come from keyframes[0] rather than an
        // out-of-bounds keyframes[i].
        val target = if (i == anim.keyframes.size) anim.keyframes[0] else anim.keyframes[i]
        val segLen = target.timestampTicks.toFloat()
        if (ticks <= cumulative + segLen || i == lastSegIndex) {
            segIndex = i
            break
        }
        cumulative += segLen
    }
    val targetFrame = if (segIndex == anim.keyframes.size) anim.keyframes[0] else anim.keyframes[segIndex]
    val prevFrame = anim.keyframes[segIndex - 1].groups
    val nextFrame = targetFrame.groups
    val segLen = targetFrame.timestampTicks.toFloat().let { if (it > 0f) it else 1f }
    val localT = ((ticks - cumulative) / segLen).coerceIn(0f, 1f)

    // interpolated (type, delta) per group, falling back to the body's own rest-pose state for
    // any group beyond what this animation covers (matches the original: extra body groups are
    // simply left untouched by a shorter animation).
    //
    // Group-index mapping is offset by one: body group 0 is the root (the actor's own heading, applied elsewhere), so
    // the animation's group slot i drives body group i+1 and its last slot goes unused.
    val stateType = IntArray(groupCount)
    val deltaX = FloatArray(groupCount)
    val deltaY = FloatArray(groupCount)
    val deltaZ = FloatArray(groupCount)
    for (gi in 0 until groupCount) {
        val animGi = gi - 1
        if (animGi in 0 until (animNumGroups - 1)) {
            val a = prevFrame[animGi]; val b = nextFrame[animGi]
            stateType[gi] = b.type
            if (b.type == 0) {
                deltaX[gi] = interpolateAngle(a.dx, b.dx, localT)
                deltaY[gi] = interpolateAngle(a.dy, b.dy, localT)
                deltaZ[gi] = interpolateAngle(a.dz, b.dz, localT)
            } else {
                deltaX[gi] = a.dx + (b.dx - a.dx) * localT
                deltaY[gi] = a.dy + (b.dy - a.dy) * localT
                deltaZ[gi] = a.dz + (b.dz - a.dz) * localT
            }
        } else {
            stateType[gi] = groups[gi].restStateType
            deltaX[gi] = groups[gi].restDeltaX.toFloat()
            deltaY[gi] = groups[gi].restDeltaY.toFloat()
            deltaZ[gi] = groups[gi].restDeltaZ.toFloat()
        }
    }

    // working copy of the raw (unposed) vertices
    val n = restVertices.size / 3
    val px = FloatArray(n); val py = FloatArray(n); val pz = FloatArray(n)
    for (i in 0 until n) {
        px[i] = restVertices[i * 3]; py[i] = restVertices[i * 3 + 1]; pz[i] = restVertices[i * 3 + 2]
    }

    fun translate(g: BodyGroup, tx: Float, ty: Float, tz: Float) {
        for (i in 0 until g.numVertices) {
            val idx = g.start + i
            px[idx] += tx; py[idx] += ty; pz[idx] += tz
        }
    }

    fun zoom(g: BodyGroup, zx: Float, zy: Float, zz: Float) {
        for (i in 0 until g.numVertices) {
            val idx = g.start + i
            px[idx] = px[idx] * (zx + 256f) / 256f
            py[idx] = py[idx] * (zy + 256f) / 256f
            pz[idx] = pz[idx] * (zz + 256f) / 256f
        }
    }

    // rotation setup, same 0-1024=0-360deg fixed-point system used throughout this app
    fun rotatePoint(idx: Int, hasX: Boolean, hasY: Boolean, hasZ: Boolean, xc: Float, xs: Float, yc: Float, ys: Float, zc: Float, zs: Float) {
        var x = px[idx]; var y = py[idx]; var z = pz[idx]
        if (hasY) {
            val tx = x; val tz = z
            x = tx * ys - tz * yc
            z = tx * yc + tz * ys
        }
        if (hasX) {
            val ty = y; val tz = z
            y = ty * xs - tz * xc
            z = ty * xc + tz * xs
        }
        if (hasZ) {
            val tx = x; val ty = y
            x = tx * zs - ty * zc
            y = tx * zc + ty * zs
        }
        px[idx] = x; py[idx] = y; pz[idx] = z
    }

    // The engine's "cosTable" is really a sine table, so the pair is (sin, cos) of the angle.
    fun cosSin(angle: Float): Pair<Float, Float> {
        val theta = (((angle.toInt() and 0x3FF)).toFloat()) * (2f * Math.PI.toFloat() / 1024f)
        val c = sin(theta)
        val s = cos(theta)
        return Pair(c, s)
    }

    fun rotateGroupCascading(gi: Int, hasX: Boolean, hasY: Boolean, hasZ: Boolean, xc: Float, xs: Float, yc: Float, ys: Float, zc: Float, zs: Float) {
        val g = groups[gi]
        for (i in 0 until g.numVertices) rotatePoint(g.start + i, hasX, hasY, hasZ, xc, xs, yc, ys, zc, zs)
        for (j in gi + 1 until groups.size) {
            if (groups[j].orgGroup == g.numGroup) {
                rotateGroupCascading(j, hasX, hasY, hasZ, xc, xs, yc, ys, zc, zs)
            }
        }
    }

    for (gi in groupOrder) {
        val tx = deltaX[gi]; val ty = deltaY[gi]; val tz = deltaZ[gi]
        if (tx == 0f && ty == 0f && tz == 0f) continue
        when (stateType[gi]) {
            0 -> {
                val hasX = tx != 0f; val hasY = ty != 0f; val hasZ = tz != 0f
                val (xc, xs) = if (hasX) cosSin(tx) else Pair(0f, 0f)
                val (yc, ys) = if (hasY) cosSin(ty) else Pair(0f, 0f)
                val (zc, zs) = if (hasZ) cosSin(tz) else Pair(0f, 0f)
                rotateGroupCascading(gi, hasX, hasY, hasZ, xc, xs, yc, ys, zc, zs)
            }
            1 -> translate(groups[gi], tx, ty, tz)
            2 -> zoom(groups[gi], tx, ty, tz)
        }
    }

    // final pass: translate every group by its (now-posed) base/pivot vertex position
    for (g in groups) {
        val bx = px[g.baseVertex]; val by = py[g.baseVertex]; val bz = pz[g.baseVertex]
        for (j in 0 until g.numVertices) {
            val idx = g.start + j
            px[idx] += bx; py[idx] += by; pz[idx] += bz
        }
    }

    val out = FloatArray(n * 3)
    for (i in 0 until n) {
        out[i * 3] = px[i]; out[i * 3 + 1] = py[i]; out[i * 3 + 2] = pz[i]
    }
    return out
}

/**
 * Cumulative root motion (animStep.xyz, summed from animation start up to normalized progress [t])
 * - exposes the per-keyframe world-space translation that [animatedVertices] itself ignores (see
 * its own history: discarded as "unused" when this was only needed for the Intro's driver-head
 * closeup, which doesn't walk anywhere). Real per-frame movement for a walking character is the
 * DIFFERENCE between this function's result at two nearby [t] values, rotated into world space by
 * the character's own current facing angle (yaw) - the same real-data-driven approach the original
 * engine uses (`walkStep`/`animMoveX/Z` in main_jmimu.c/anim_yaz0r.cpp: root motion is local to the
 * body's own facing direction, then rotated by the actor's current angle before being added to
 * world position).
 *
 * Interpolation model: each keyframe's own stored (stepX,stepY,stepZ) is treated as the total delta
 * accumulated during the segment ENDING at that keyframe (matching `timestampTicks`'s own "ticks
 * since the previous keyframe" meaning) and is linearly interpolated across that segment - the same
 * treatment already given to every other per-frame channel in this file (e.g. type=1 "translate"
 * group deltas). The exact original interpolation formula for root motion specifically could not be
 * confirmed from the available reference source (`makeRotationMtx`, called from `walkStep`, is
 * referenced but never defined in the recovered code) - this is the most consistent, structurally-
 * justified inference available, not an invented value.
 */
fun animatedRootMotion(animFrames: IntArray, animNumGroups: Int, animCacheKey: String, t: Float): FloatArray {
    val anim = parsedAnimationFor(animCacheKey, animFrames, animNumGroups)
    val ticks = t.coerceIn(0f, 1f) * anim.totalTicks
    var cumulative = 0f
    var accX = 0f; var accY = 0f; var accZ = 0f
    for (i in 1 until anim.keyframes.size) {
        val kf = anim.keyframes[i]
        val segLen = kf.timestampTicks.toFloat()
        if (ticks <= cumulative + segLen || i == anim.keyframes.size - 1) {
            val localT = if (segLen > 0f) ((ticks - cumulative) / segLen).coerceIn(0f, 1f) else 1f
            accX += kf.stepX * localT; accY += kf.stepY * localT; accZ += kf.stepZ * localT
            break
        }
        accX += kf.stepX; accY += kf.stepY; accZ += kf.stepZ
        cumulative += segLen
    }
    return floatArrayOf(accX, accY, accZ)
}

/** Shortest-arc linear interpolation between two 0-1024 fixed-point angles (original: PatchInterAngle). */
private fun interpolateAngle(previous: Float, next: Float, t: Float): Float {
    val diff = next - previous
    return when {
        diff == 0f -> next
        diff in -512f..512f -> previous + diff * t
        diff < -512f -> {
            // wrap "next" forward a full turn so the shorter (backward-crossing-zero) arc is taken
            val adjNext = next + 1024f
            previous + (adjNext - previous) * t
        }
        else -> {
            // diff > 512f: wrap "previous" forward a full turn instead
            val adjPrev = previous + 1024f
            adjPrev + (next - adjPrev) * t
        }
    }
}
