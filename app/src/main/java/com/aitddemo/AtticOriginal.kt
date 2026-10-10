package com.aitddemo

import com.aitddemo.game.AnimData
import com.aitddemo.original.GameFiles
import com.aitddemo.original.VocSample
import com.aitddemo.original.Pak
import com.aitddemo.original.s16
import com.aitddemo.original.u16
import com.aitddemo.original.u32
import com.aitddemo.original.u8

/** Thrown when the original game files are missing or not in the expected format; the message is shown to the player. */
class OriginalFilesException(message: String) : Exception(message)

/** A static body (furniture) placed in the room: posed vertices (x, y, z per vertex), triangles (a, b, c, ARGB) and its OBJETS.ITD placement. */
class AtticProp(
    val objectIndex: Int,
    val body: Int,
    val vertices: FloatArray,
    val triangles: IntArray,
    val x: Int,
    val y: Int,
    val z: Int,
    val alpha: Int,
    val beta: Int,
    val gamma: Int,
    val lines: IntArray = IntArray(0),
    val spheres: IntArray = IntArray(0),
)

/** The player's animated body: unposed per-group vertices, 12 ints per group, group processing order, triangles, and his walk animation. */
class AtticPlayer(
    val vertices: FloatArray,
    val groups: IntArray,
    val groupOrder: IntArray,
    val triangles: IntArray,
    val walkFrames: IntArray,
    val walkGroups: Int,
    val spawnX: Int,
    val spawnZ: Int,
    /** Half width and height of the body box (ZV) from the body header. */
    val halfWidth: Int,
    val height: Int,
    val lines: IntArray = IntArray(0),
    val spheres: IntArray = IntArray(0),
    /** Where he starts: heading (engine angle), kind of collision box (OBJETS typeZv) and body box (x1, x2, y1, y2, z1, z2). */
    val spawnBeta: Int = 0,
    val typeZv: Int = 2,
    val zv: IntArray = IntArray(6),
    /** His movement animations (stand, walk, run, back, turn) by number, as the actor plays them. */
    val anims: Map<Int, AnimData> = emptyMap(),
)

/** Everything the attic needs, parsed from the original files. [photos] are the 320x200 camera pictures as ARGB. */
class AtticOriginal(
    val cameras: List<AtticCameraDef>,
    val hardcols: IntArray,
    val masks: List<List<AtticData.Mask>>,
    val props: List<AtticProp>,
    val player: AtticPlayer,
    val photos: List<IntArray>,
    /** Scene zones of the room (x1, x2, y1, y2, z1, z2, parameter, type; 8 values each): exits, triggers and floor kinds. */
    val zones: IntArray,
    /** Sounds of the room (footsteps), by sample number. */
    val samples: Map<Int, VocSample>,
    /** The attic music: AdLib track (ADLM file) of LISTMUS.PAK. */
    val music: ByteArray,
)

/**
 * Reads the attic (stage 0, room 0) of Alone in the Dark directly from the original files:
 * ETAGE00.PAK (room, hard colliders, cameras, masks), CAMERA00.PAK (pictures), ITD_RESS.PAK (palette),
 * OBJETS.ITD (placements), LISTBODY.PAK (bodies) and LISTANIM.PAK (walk animation).
 */
object AtticOriginalReader {
    const val STAGE_FILE = "ETAGE00.PAK"
    const val CAMERA_FILE = "CAMERA00.PAK"
    const val RESOURCE_FILE = "ITD_RESS.PAK"
    const val OBJECT_FILE = "OBJETS.ITD"
    const val BODY_FILE = "LISTBODY.PAK"
    const val ANIM_FILE = "LISTANIM.PAK"
    val REQUIRED = listOf(STAGE_FILE, CAMERA_FILE, RESOURCE_FILE, OBJECT_FILE, BODY_FILE, ANIM_FILE, MusicOriginalReader.FILE, SAMPLE_FILE)

    private const val STAGE = 0
    private const val ROOM = 0
    private const val PLAYER_BODY = 12
    private const val WALK_ANIM = 254
    private const val PALETTE_ENTRY = 4 // ITD_RESS.PAK: the 768 byte colour table
    private const val OBJECT_SIZE = 52

    /** The attic plays the first track stored in LISTMUS.PAK (music number 0 = entry 1). */
    const val ATTIC_MUSIC = 0
    const val SAMPLE_FILE = "LISTSAMP.PAK"

    /** Movement animations of the player body (life script 549: ANIM_MOVE 4, 254, 255, 4, 256, 257, 258) and the footstep samples (wood 30/31, carpet 46/47). */
    private val MOVE_ANIMS = intArrayOf(4, 254, 255, 256, 257, 258)
    private val ROOM_SAMPLES = intArrayOf(30, 31, 46, 47)

    fun missingFiles(files: GameFiles): List<String> = REQUIRED.filter { !files.exists(it) }

    fun read(files: GameFiles): AtticOriginal {
        val missing = missingFiles(files)
        if (missing.isNotEmpty()) throw OriginalFilesException("Fehlende Dateien: " + missing.joinToString(", "))
        try {
            return readAll(files)
        } catch (e: OriginalFilesException) {
            throw e
        } catch (e: Exception) {
            throw OriginalFilesException("Die Originaldateien konnten nicht gelesen werden (${e.javaClass.simpleName}: ${e.message}).")
        }
    }

    private fun readAll(files: GameFiles): AtticOriginal {
        val palette = readPalette(Pak(files.read(RESOURCE_FILE)!!).get(PALETTE_ENTRY) ?: throw OriginalFilesException("$RESOURCE_FILE: Palette fehlt"))

        // ---- ETAGE00.PAK / CAMERA00.PAK: room 0 of stage 0 with its cameras, masks and pictures
        val stage = OriginalStage.open(files, STAGE)
        val room = stage.rooms.firstOrNull { it.index == ROOM } ?: throw OriginalFilesException("$STAGE_FILE: Raum $ROOM fehlt")
        val hardcols = room.hardcols
        val count = hardcols.size / AtticData.HARDCOL_STRIDE
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minZ = Int.MAX_VALUE
        var maxZ = Int.MIN_VALUE
        for (i in 0 until count) {
            minX = minOf(minX, hardcols[i * AtticData.HARDCOL_STRIDE])
            maxX = maxOf(maxX, hardcols[i * AtticData.HARDCOL_STRIDE + 1])
            minZ = minOf(minZ, hardcols[i * AtticData.HARDCOL_STRIDE + 4])
            maxZ = maxOf(maxZ, hardcols[i * AtticData.HARDCOL_STRIDE + 5])
        }
        val roomCameras = room.cameraIds.map { stage.camera(ROOM, it) }
        val cameras = roomCameras.mapIndexed { index, c -> c.camera.copy(index = index) }
        val masks = roomCameras.map { it.masks }
        val photos = room.cameraIds.map { stage.picture(it, palette) }

        // ---- OBJETS.ITD + LISTBODY.PAK
        val objects = files.read(OBJECT_FILE)!!
        val bodies = Pak(files.read(BODY_FILE)!!)
        val total = objects.u16(0)
        val props = ArrayList<AtticProp>()
        var spawnX = 0
        var spawnZ = 0
        var spawnBeta = 0
        var typeZv = 2
        var playerFound = false
        for (i in 0 until total) {
            val p = 2 + i * OBJECT_SIZE
            fun w(k: Int) = objects.s16(p + k * 2)
            if (w(14) != 0 || w(15) != ROOM || w(1) < 0) continue // stage 0, room 0, has a body
            val bodyNo = w(1)
            if (bodyNo == PLAYER_BODY) {
                spawnX = w(8)
                spawnZ = w(10)
                spawnBeta = w(12)
                typeZv = w(3)
                playerFound = true
                continue
            }
            // creatures that appear later by script are parked outside the room (below the floor / beyond the walls)
            if (w(8) < minX || w(8) > maxX || w(10) < minZ || w(10) > maxZ || w(9) > 500) continue
            val data = bodies.get(bodyNo + 1) ?: continue
            val body = OriginalBody.parse(data)
            val mesh = body.toMesh(palette)
            props.add(AtticProp(i, bodyNo, mesh.first, mesh.second, w(8), w(9), w(10), w(11), w(12), w(13), body.lines(palette), body.spheres(palette)))
        }
        if (!playerFound) throw OriginalFilesException("$OBJECT_FILE: Spielfigur (Körper $PLAYER_BODY) nicht in Raum 0 gefunden")

        val playerBody = OriginalBody.parse(bodies.get(PLAYER_BODY + 1) ?: throw OriginalFilesException("$BODY_FILE: Körper $PLAYER_BODY fehlt"))
        val animRaw = Pak(files.read(ANIM_FILE)!!).get(WALK_ANIM + 1) ?: throw OriginalFilesException("$ANIM_FILE: Animation $WALK_ANIM fehlt")
        val frames = animRaw.u16(0)
        val groups = animRaw.u16(2)
        if (animRaw.size != 4 + frames * (8 + groups * 8)) throw OriginalFilesException("$ANIM_FILE: Animation $WALK_ANIM hat ein unerwartetes Format")
        val walk = IntArray(frames * (4 + groups * 4))
        var q = 4
        var o = 0
        for (f in 0 until frames) {
            walk[o++] = animRaw.u16(q)
            walk[o++] = animRaw.s16(q + 2)
            walk[o++] = animRaw.s16(q + 4)
            walk[o++] = animRaw.s16(q + 6)
            q += 8
            for (g in 0 until groups) {
                for (k in 0 until 4) walk[o++] = animRaw.s16(q + k * 2)
                q += 8
            }
        }
        val animPak = Pak(files.read(ANIM_FILE)!!)
        val moveAnims = MOVE_ANIMS.associateWith { n -> AnimData.parse(animPak.get(n + 1) ?: throw OriginalFilesException("$ANIM_FILE: Animation $n fehlt")) }
        val samplePak = Pak(files.read(SAMPLE_FILE)!!)
        val samples = ROOM_SAMPLES.associateWith { n -> VocSample.parse(samplePak.get(n + 1) ?: throw OriginalFilesException("$SAMPLE_FILE: Klang $n fehlt")) ?: throw OriginalFilesException("$SAMPLE_FILE: Klang $n ist kein VOC") }
        val rig = playerBody.animatedParts()
        val tris = playerBody.toMesh(palette).second
        val player = AtticPlayer(
            rig.vertices, rig.groups, rig.groupOrder, tris, walk, groups, spawnX, spawnZ,
            maxOf(-playerBody.zv[0], playerBody.zv[1]), -playerBody.zv[2],
            playerBody.lines(palette), playerBody.spheres(palette),
            spawnBeta, typeZv, playerBody.zv, moveAnims,
        )
        val music = Pak(files.read(MusicOriginalReader.FILE)!!).get(ATTIC_MUSIC + 1) ?: throw OriginalFilesException("${MusicOriginalReader.FILE}: Musik $ATTIC_MUSIC fehlt")
        return AtticOriginal(cameras, hardcols, masks, props, player, photos, room.zones, samples, music)
    }

    private fun readPalette(d: ByteArray): IntArray {
        if (d.size < 768) throw OriginalFilesException("$RESOURCE_FILE: Palette hat die falsche Größe")
        val vga = (0 until 768).all { d.u8(it) <= 63 }
        return IntArray(256) { i ->
            var r = d.u8(i * 3)
            var g = d.u8(i * 3 + 1)
            var b = d.u8(i * 3 + 2)
            if (vga) {
                r = (r shl 2) or (r shr 4)
                g = (g shl 2) or (g shr 4)
                b = (b shl 2) or (b shr 4)
            }
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}

/** Primitive of a 1992 body: type 1/8/9/10 polygon, 3 sphere (size), others (lines, points) are not drawn. */
private class Prim(val type: Int, val color: Int, val points: IntArray, val size: Int, val material: Int = 0)

private class Group(val start: Int, val count: Int, val base: Int, val org: Int, val num: Int, val type: Int, val dx: Int, val dy: Int, val dz: Int)

/** Animated-body data as the skeleton code expects it. */
class AnimatedParts(val vertices: FloatArray, val groups: IntArray, val groupOrder: IntArray)

/** A body from LISTBODY.PAK (Alone in the Dark 1992 format: vertex table, bone groups, flat coloured polygons). */
class OriginalBody private constructor(
    /** Body box (x1, x2, y1, y2, z1, z2) from the file header. */
    val zv: IntArray,
    private val x: IntArray,
    private val y: IntArray,
    private val z: IntArray,
    private val groups: List<Group>,
    private val order: IntArray,
    private val prims: List<Prim>,
) {
    /** Raw (unposed) per-group vertices plus the group table in the flat 12-int-per-group format used by [animatedVertices]. */
    fun animatedParts(): AnimatedParts {
        val v = FloatArray(x.size * 3)
        for (i in x.indices) {
            v[i * 3] = x[i].toFloat()
            v[i * 3 + 1] = y[i].toFloat()
            v[i * 3 + 2] = z[i].toFloat()
        }
        val g = IntArray(groups.size * 12)
        groups.forEachIndexed { i, gr ->
            g[i * 12] = gr.start
            g[i * 12 + 1] = gr.count
            g[i * 12 + 2] = gr.base
            g[i * 12 + 3] = gr.org
            g[i * 12 + 4] = gr.num
        }
        return AnimatedParts(v, g, order)
    }

    /** Does the file describe bone groups (an articulated body)? */
    val isArticulated: Boolean get() = groups.isNotEmpty()

    /**
     * Rest pose of an articulated body (the original's ApplyHierarchy): every group in processing order is rotated (with all its
     * children), moved or zoomed by the state stored in the file, then each group is moved to its pivot vertex.
     */
    private fun restPose(): Array<DoubleArray> {
        val px = DoubleArray(x.size) { x[it].toDouble() }
        val py = DoubleArray(x.size) { y[it].toDouble() }
        val pz = DoubleArray(x.size) { z[it].toDouble() }
        fun rotate(gi: Int, ax: Int, ay: Int, az: Int) {
            // the table the engine calls "cos" is a sine table: (cosLike, sinLike) = (sin theta, cos theta)
            fun cs(a: Int) = Math.sin((a and 0x3FF) * 2.0 * Math.PI / 1024.0) to Math.cos((a and 0x3FF) * 2.0 * Math.PI / 1024.0)
            val g = groups[gi]
            for (i in 0 until g.count) {
                val k = g.start + i
                var vx = px[k]
                var vy = py[k]
                var vz = pz[k]
                if (ay != 0) { val (c, sn) = cs(ay); val tx = vx; val tz = vz; vx = tx * sn - tz * c; vz = tx * c + tz * sn }
                if (ax != 0) { val (c, sn) = cs(ax); val ty = vy; val tz = vz; vy = ty * sn - tz * c; vz = ty * c + tz * sn }
                if (az != 0) { val (c, sn) = cs(az); val tx = vx; val ty = vy; vx = tx * sn - ty * c; vy = tx * c + ty * sn }
                px[k] = vx; py[k] = vy; pz[k] = vz
            }
            for (j in gi + 1 until groups.size) if (groups[j].org == g.num) rotate(j, ax, ay, az)
        }
        for (gi in order) {
            val g = groups[gi]
            if (g.dx == 0 && g.dy == 0 && g.dz == 0) continue
            when (g.type) {
                0 -> rotate(gi, g.dx, g.dy, g.dz)
                1 -> for (i in 0 until g.count) { px[g.start + i] += g.dx; py[g.start + i] += g.dy; pz[g.start + i] += g.dz }
                2 -> for (i in 0 until g.count) {
                    px[g.start + i] = px[g.start + i] * (g.dx + 256) / 256.0
                    py[g.start + i] = py[g.start + i] * (g.dy + 256) / 256.0
                    pz[g.start + i] = pz[g.start + i] * (g.dz + 256) / 256.0
                }
            }
        }
        for (g in groups) {
            val bx = px[g.base]
            val by = py[g.base]
            val bz = pz[g.base]
            for (i in 0 until g.count) { px[g.start + i] += bx; py[g.start + i] += by; pz[g.start + i] += bz }
        }
        return arrayOf(px, py, pz)
    }

    /** Line primitives (type 0) as (vertex a, vertex b, ARGB) triples: thin parts of a body, for example the occupants of the car. */
    fun lines(palette: IntArray): IntArray {
        val out = ArrayList<Int>()
        for (p in prims) {
            if (p.type != 0 || p.points.size != 2) continue
            out.add(p.points[0]); out.add(p.points[1]); out.add(palette[p.color])
        }
        return out.toIntArray()
    }

    /**
     * Round parts as (vertex, radius, ARGB) triples: spheres (type 3, radius in body units, for example lamps) and the point primitives
     * (types 2, 6, 7: pupils, sparks), which are given as radius -type; the renderer draws them as small dots.
     */
    fun spheres(palette: IntArray): IntArray {
        val out = ArrayList<Int>()
        for (p in prims) {
            if (p.points.size != 1) continue
            when (p.type) {
                3 -> { out.add(p.points[0]); out.add(p.size); out.add(palette[p.color]) }
                2, 6, 7 -> { out.add(p.points[0]); out.add(-p.type); out.add(palette[p.color]) }
            }
        }
        return out.toIntArray()
    }

    /** Vertices (posed for an articulated body) plus triangles (a, b, c, ARGB) for the polygons. */
    fun toMesh(palette: IntArray): Pair<FloatArray, IntArray> {
        val pose = if (isArticulated) restPose() else arrayOf(DoubleArray(x.size) { x[it].toDouble() }, DoubleArray(x.size) { y[it].toDouble() }, DoubleArray(x.size) { z[it].toDouble() })
        val verts = ArrayList<Float>(x.size * 3)
        for (i in x.indices) {
            verts.add(pose[0][i].toFloat())
            verts.add(pose[1][i].toFloat())
            verts.add(pose[2][i].toFloat())
        }
        val tris = ArrayList<Int>()
        for (p in prims) {
            if (p.points.size < 3) continue
            // material 1 is the original's "dither" fill (noisy, for stone and cloth): marked by an alpha of 0xFE for the renderer
            val c = if (p.material == 1) (palette[p.color] and 0x00FFFFFF) or (0xFE shl 24) else palette[p.color]
            for (i in 1 until p.points.size - 1) {
                tris.add(p.points[0]); tris.add(p.points[i]); tris.add(p.points[i + 1]); tris.add(c)
            }
        }
        return verts.toFloatArray() to tris.toIntArray()
    }

    companion object {
        private const val INFO_ANIM = 2
        private const val INFO_OPTIMISE = 8

        fun parse(d: ByteArray): OriginalBody {
            val flags = d.u16(0)
            var p = 14
            p += 2 + d.u16(p) // scratch buffer
            val nv = d.u16(p)
            p += 2
            val x = IntArray(nv) { d.s16(p + it * 6) }
            val y = IntArray(nv) { d.s16(p + it * 6 + 2) }
            val z = IntArray(nv) { d.s16(p + it * 6 + 4) }
            p += nv * 6
            val groups = ArrayList<Group>()
            var order = IntArray(0)
            if (flags and INFO_ANIM != 0) {
                if (flags and INFO_OPTIMISE != 0) throw OriginalFilesException("Körper im optimierten Format werden nicht unterstützt")
                val ng = d.u16(p)
                p += 2
                order = IntArray(ng) { d.u16(p + it * 2) / 16 }
                p += 2 * ng
                repeat(ng) {
                    groups.add(Group(d.s16(p) / 6, d.s16(p + 2), d.s16(p + 4) / 6, d[p + 6].toInt(), d[p + 7].toInt(), d.s16(p + 8), d.s16(p + 10), d.s16(p + 12), d.s16(p + 14)))
                    p += 16
                }
            }
            val np = d.u16(p)
            p += 2
            val prims = ArrayList<Prim>(np)
            repeat(np) {
                val t = d.u8(p++)
                when (t) {
                    0 -> {
                        prims.add(Prim(t, d.u8(p + 1), intArrayOf(d.u16(p + 3) / 6, d.u16(p + 5) / 6), 0))
                        p += 7
                    }
                    1, 8 -> {
                        val n = d.u8(p)
                        prims.add(Prim(t, d.u8(p + 2), IntArray(n) { d.u16(p + 3 + 2 * it) / 6 }, 0, d.u8(p + 1)))
                        p += 3 + 2 * n
                    }
                    2, 6, 7 -> {
                        prims.add(Prim(t, d.u8(p + 1), intArrayOf(d.u16(p + 3) / 6), 0))
                        p += 5
                    }
                    3 -> {
                        prims.add(Prim(t, d.u8(p + 1), intArrayOf(d.u16(p + 5) / 6), d.u16(p + 3)))
                        p += 7
                    }
                    9, 10 -> {
                        val n = d.u8(p)
                        prims.add(Prim(t, d.u8(p + 2), IntArray(n) { d.u16(p + 3 + 2 * it) / 6 }, 0))
                        p += 3 + 4 * n
                    }
                    else -> throw OriginalFilesException("Körper: unbekannter Primitiv-Typ $t")
                }
            }
            return OriginalBody(IntArray(6) { d.s16(2 + it * 2) }, x, y, z, groups, order, prims)
        }
    }
}
