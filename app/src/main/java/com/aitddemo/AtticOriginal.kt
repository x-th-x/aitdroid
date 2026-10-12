package com.aitddemo

import com.aitddemo.game.GameData
import com.aitddemo.game.GameTexts
import com.aitddemo.original.GameFiles
import com.aitddemo.original.Pak
import com.aitddemo.original.s16
import com.aitddemo.original.u16
import com.aitddemo.original.u32
import com.aitddemo.original.u8

/** Thrown when the original game files are missing or not in the expected format; the message is shown to the player. */
class OriginalFilesException(message: String) : Exception(message)

/** Everything the game needs from the original files: the music of the first scene and the game data (objects, scripts, rooms, bodies, sounds, texts). */
class AtticOriginal(
    /** The attic music: AdLib track (ADLM file) of LISTMUS.PAK. */
    val music: ByteArray,
    /** Objects, scripts, rooms, bodies, animations, sounds and texts of the game logic. */
    val game: GameData,
    /** Music tracks of LISTMUS.PAK by number (the scripts start music by number). */
    val musicTrack: (Int) -> ByteArray?,
)

/**
 * Reads the attic (stage 0, room 0) of Alone in the Dark directly from the original files:
 * ETAGE00.PAK (room, hard colliders, cameras, masks), CAMERA00.PAK (pictures), ITD_RESS.PAK (palette),
 * OBJETS.ITD, LISTLIFE.PAK (scripts), LISTBODY.PAK, LISTANIM.PAK, LISTSAMP.PAK and ENGLISH.PAK (the game logic).
 */
object AtticOriginalReader {
    const val STAGE_FILE = "ETAGE00.PAK"
    const val CAMERA_FILE = "CAMERA00.PAK"
    const val RESOURCE_FILE = "ITD_RESS.PAK"
    const val OBJECT_FILE = "OBJETS.ITD"
    const val BODY_FILE = "LISTBODY.PAK"
    const val ANIM_FILE = "LISTANIM.PAK"
    /** Emily's game uses her own lists of bodies and animations: same numbers as Carnby's, only the entries of the hero differ. */
    const val EMILY_BODY_FILE = "LISTBOD2.PAK"
    const val EMILY_ANIM_FILE = "LISTANI2.PAK"
    val REQUIRED = listOf(STAGE_FILE, CAMERA_FILE, RESOURCE_FILE, OBJECT_FILE, BODY_FILE, ANIM_FILE, MusicOriginalReader.FILE, SAMPLE_FILE, LIFE_FILE, VARS_FILE, TEXT_FILE, TRACK_FILE)

    private const val STAGE = 0
    private const val ROOM = 0
    private const val PALETTE_ENTRY = 4 // ITD_RESS.PAK: the 768 byte colour table

    /** The attic plays the first track stored in LISTMUS.PAK (music number 0 = entry 1). */
    const val ATTIC_MUSIC = 0
    const val SAMPLE_FILE = "LISTSAMP.PAK"
    const val LIFE_FILE = "LISTLIFE.PAK"
    const val VARS_FILE = "VARS.ITD"
    const val DEFINES_FILE = "DEFINES.ITD"
    const val PRIORITY_FILE = "PRIORITY.ITD"
    const val TEXT_FILE = "ENGLISH.PAK"
    const val TRACK_FILE = "LISTTRAK.PAK"


    fun missingFiles(files: GameFiles): List<String> = REQUIRED.filter { !files.exists(it) }

    fun missingFilesEmily(files: GameFiles): List<String> = (REQUIRED - BODY_FILE - ANIM_FILE + EMILY_BODY_FILE + EMILY_ANIM_FILE).filter { !files.exists(it) }

    fun read(files: GameFiles, emily: Boolean = false): AtticOriginal {
        val missing = if (emily) missingFilesEmily(files) else missingFiles(files)
        if (missing.isNotEmpty()) throw OriginalFilesException("Fehlende Dateien: " + missing.joinToString(", "))
        try {
            return readAll(files, emily)
        } catch (e: OriginalFilesException) {
            throw e
        } catch (e: Exception) {
            throw OriginalFilesException("Die Originaldateien konnten nicht gelesen werden (${e.javaClass.simpleName}: ${e.message}).")
        }
    }

    private fun readAll(files: GameFiles, emily: Boolean): AtticOriginal {
        val palette = readPalette(Pak(files.read(RESOURCE_FILE)!!).get(PALETTE_ENTRY) ?: throw OriginalFilesException("$RESOURCE_FILE: Palette fehlt"))

        // ---- the game logic: objects, scripts, variables, bodies, animations, sounds, texts
        val textPak = Pak(files.read(TEXT_FILE)!!)
        val resourcePak = Pak(files.read(RESOURCE_FILE)!!)
        val varBytes = files.read(VARS_FILE)!!
        val vars = ShortArray(varBytes.size / 2) { varBytes.s16(it * 2).toShort() }
        val musicPak = Pak(files.read(MusicOriginalReader.FILE)!!)
        val game = GameData(
            files.read(OBJECT_FILE)!!, Pak(files.read(LIFE_FILE)!!), vars, Pak(files.read(if (emily) EMILY_BODY_FILE else BODY_FILE)!!), Pak(files.read(if (emily) EMILY_ANIM_FILE else ANIM_FILE)!!),
            Pak(files.read(SAMPLE_FILE)!!), palette, RoomLoader(files, palette),
            GameTexts.parse(textPak.get(1) ?: throw OriginalFilesException("$TEXT_FILE: Texte fehlen")),
            textPak, resourcePak, Pak(files.read(TRACK_FILE)!!),
            // DEFINES.ITD: 16 bit values, high byte first
            files.read(DEFINES_FILE)?.let { d -> ShortArray(d.size / 2) { (((d[it * 2].toInt() and 0xFF) shl 8) or (d[it * 2 + 1].toInt() and 0xFF)).toShort() } } ?: ShortArray(0),
            files.read(PRIORITY_FILE),
        )
        game.rooms.room(STAGE, ROOM) // the first room must be readable now (it is cached for the start)
        val music = musicPak.get(ATTIC_MUSIC + 1) ?: throw OriginalFilesException("${MusicOriginalReader.FILE}: Musik $ATTIC_MUSIC fehlt")
        return AtticOriginal(music, game) { n -> musicPak.get(n + 1) }
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
            val c = when (p.material) {
                1 -> (palette[p.color] and 0x00FFFFFF) or (0xFE shl 24)
                // material 2 is transparent (window glass): the renderers blend it over what is behind
                2 -> (palette[p.color] and 0x00FFFFFF) or (0xFA shl 24)
                // materials 3, 4 and 5 run through the 16 colours of the palette bank: left to right ("marble"), top to bottom ("copper"), the same at two scanlines per colour
                3, 4, 5 -> ((palette[p.color] and 0x00FFFFFF) or ((0xFE - p.material + 2) shl 24)).also { key ->
                    BodyRamps.ramps[key] = IntArray(16) { palette[(p.color and 0xF0) + it] }
                }
                else -> palette[p.color]
            }
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

/** The colour ramps of the polygon materials 3 to 5, by the colour (with the material in its alpha byte) the mesh gives such a polygon. */
object BodyRamps {
    val ramps = java.util.concurrent.ConcurrentHashMap<Int, IntArray>()
}
