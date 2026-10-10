package com.aitddemo

import com.aitddemo.original.GameFiles
import com.aitddemo.original.Pak
import com.aitddemo.original.s16
import com.aitddemo.original.u16
import com.aitddemo.original.u8

/** A body of the game: [pak] 0 = LISTBODY.PAK, 1 = LISTBOD2.PAK; [raw] = index of the PAK entry (the body number + 1). */
class ModelEntry(val pak: Int, val raw: Int) {
    val number: Int get() = raw - 1
    val label: String
        get() {
            val known = when (pak to raw) {
                0 to 13 -> "Carnby"
                1 to 13 -> "Emily"
                0 to 12 -> "Carnby with lamp"
                else -> null
            }
            return known ?: ((if (pak == 0) "Body " else "Body B") + number)
        }
}

/** A body ready to draw: posed rest vertices and coloured triangles, plus the raw group data for animating it. */
class ModelMesh(val vertices: FloatArray, val triangles: IntArray, val parts: AnimatedParts?, val groupCount: Int)

/** An animation of LISTANIM.PAK in the flat form of [animatedVertices]: per keyframe time, root step and per group (type, x, y, z). */
class ModelAnimation(val number: Int, val frames: IntArray, val groups: Int)

/** The bodies, animations and colours of the game, read from the original files for the Model Viewer (and the character preview). */
class ModelLibrary(private val palette: IntArray, private val bodies: Array<Pak>, private val animPak: Pak) {
    private val meshes = HashMap<Int, ModelMesh>()
    private val anims = HashMap<Int, ModelAnimation?>()

    /** All bodies that can be read, in file order. */
    val entries: List<ModelEntry> = buildList {
        for (p in bodies.indices) for (raw in 1 until bodies[p].size) {
            val d = try { bodies[p].get(raw) } catch (_: Exception) { null } ?: continue
            if (d.size < 20) continue
            if (try { OriginalBody.parse(d); true } catch (_: Exception) { false }) add(ModelEntry(p, raw))
        }
    }

    /** Number of animations in LISTANIM.PAK. */
    val animationCount: Int get() = animPak.size - 1

    fun mesh(e: ModelEntry): ModelMesh = meshes.getOrPut(e.pak * 100000 + e.raw) {
        val b = OriginalBody.parse(bodies[e.pak].get(e.raw)!!)
        val (v, t) = b.toMesh(palette)
        val parts = if (b.isArticulated) b.animatedParts() else null
        ModelMesh(v, t, parts, parts?.groups?.size?.div(12) ?: 0)
    }

    /** Animation [n] (the animation number, entry n + 1), or null when it cannot be read. */
    fun animation(n: Int): ModelAnimation? = anims.getOrPut(n) {
        try {
            val d = animPak.get(n + 1) ?: return@getOrPut null
            val frames = d.u16(0)
            val groups = d.u16(2)
            if (frames == 0 || d.size != 4 + frames * (8 + groups * 8)) return@getOrPut null
            val out = IntArray(frames * (4 + groups * 4))
            var q = 4
            var o = 0
            for (f in 0 until frames) {
                out[o++] = d.u16(q)
                out[o++] = d.s16(q + 2)
                out[o++] = d.s16(q + 4)
                out[o++] = d.s16(q + 6)
                q += 8
                for (g in 0 until groups) {
                    for (k in 0 until 4) out[o++] = d.s16(q + k * 2)
                    q += 8
                }
            }
            ModelAnimation(n, out, groups)
        } catch (_: Exception) {
            null
        }
    }

    /** The animations that fit the articulated body [mesh] (they drive its bone groups from the second on). */
    fun compatibleAnimations(mesh: ModelMesh): List<Int> {
        if (mesh.parts == null) return emptyList()
        val g = mesh.groupCount
        return (0 until animationCount).filter { n -> animation(n)?.let { it.groups in (g - 2)..g && it.frames.size > 4 + it.groups * 4 } == true }
    }
}

object ModelLibraryReader {
    const val BODY_FILE = "LISTBODY.PAK"
    const val BODY2_FILE = "LISTBOD2.PAK"
    const val ANIM_FILE = "LISTANIM.PAK"
    const val RESOURCE_FILE = "ITD_RESS.PAK"
    val REQUIRED = listOf(BODY_FILE, BODY2_FILE, ANIM_FILE, RESOURCE_FILE)

    fun missingFiles(files: GameFiles): List<String> = REQUIRED.filter { !files.exists(it) }

    fun read(files: GameFiles): ModelLibrary {
        val missing = missingFiles(files)
        if (missing.isNotEmpty()) throw OriginalFilesException("Fehlende Dateien: " + missing.joinToString(", "))
        try {
            val pd = Pak(files.read(RESOURCE_FILE)!!).get(4) ?: throw OriginalFilesException("$RESOURCE_FILE: Palette fehlt")
            val palette = IntArray(256) { 0xFF000000.toInt() or (pd.u8(it * 3) shl 16) or (pd.u8(it * 3 + 1) shl 8) or pd.u8(it * 3 + 2) }
            return ModelLibrary(palette, arrayOf(Pak(files.read(BODY_FILE)!!), Pak(files.read(BODY2_FILE)!!)), Pak(files.read(ANIM_FILE)!!))
        } catch (e: OriginalFilesException) {
            throw e
        } catch (e: Exception) {
            throw OriginalFilesException("Die Originaldateien konnten nicht gelesen werden (${e.javaClass.simpleName}: ${e.message}).")
        }
    }
}
