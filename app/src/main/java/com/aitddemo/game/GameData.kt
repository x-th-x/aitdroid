package com.aitddemo.game

import com.aitddemo.AnimatedParts
import com.aitddemo.OriginalBody
import com.aitddemo.original.Pak
import com.aitddemo.original.VocSample
import com.aitddemo.original.u8

/** A body of LISTBODY.PAK prepared for the game: its rest mesh, the bone data of an articulated body and the box from its header. */
class BodyAsset(
    val number: Int,
    val zv: IntArray,
    val vertices: FloatArray,
    val triangles: IntArray,
    val lines: IntArray,
    val spheres: IntArray,
    val parts: AnimatedParts?,
) {
    val groupCount: Int get() = parts?.groups?.size?.div(12) ?: 0
}

/** The text table of the game (ENGLISH.PAK, first entry): `@number:text` lines. */
class GameTexts(private val map: Map<Int, String>) {
    operator fun get(index: Int): String? = map[index]

    companion object {
        fun parse(d: ByteArray): GameTexts {
            val map = HashMap<Int, String>()
            var p = 0
            while (p < d.size) {
                val c = d.u8(p++)
                if (c == 26) break
                if (c != '@'.code) continue
                var n = 0
                var ch = 0
                while (p < d.size) {
                    ch = d.u8(p++)
                    if (ch < '0'.code || ch > '9'.code) break
                    n = n * 10 + (ch - '0'.code)
                }
                if (ch == ':'.code) {
                    val start = p
                    while (p < d.size && d.u8(p) >= 32) p++
                    map[n] = String(d, start, p - start, Charsets.ISO_8859_1)
                    p++
                }
            }
            return GameTexts(map)
        }
    }
}

/**
 * Everything the game logic reads from the original files besides the room itself: the object table (OBJETS.ITD), the scripts, the start values of
 * the variables, bodies, animations, sounds, texts, and the colours. Bodies, animations and sounds are parsed the first time they are needed.
 */
class GameData(
    val objectData: ByteArray,
    private val lifePak: Pak,
    val vars: ShortArray,
    private val bodyPak: Pak,
    private val animPak: Pak,
    private val samplePak: Pak?,
    val palette: IntArray,
    val hardcols: List<Zone>,
    val zones: List<Zone>,
    val texts: GameTexts,
    private val textPak: Pak,
    private val resourcePak: Pak,
) {
    private val bodies = HashMap<Int, BodyAsset?>()
    private val anims = HashMap<Int, AnimData?>()
    private val samples = HashMap<Int, VocSample?>()
    private val scripts = HashMap<Int, ShortArray?>()

    /** Life script [n] as 16 bit words. */
    fun script(n: Int): ShortArray? = scripts.getOrPut(n) {
        lifePak.get(n + 1)?.let { d -> ShortArray(d.size / 2) { ((d[it * 2].toInt() and 0xFF) or (d[it * 2 + 1].toInt() shl 8)).toShort() } }
    }

    /** Body [n] (entry n + 1 of LISTBODY.PAK) or null. */
    fun body(n: Int): BodyAsset? = bodies.getOrPut(n) {
        try {
            val d = bodyPak.get(n + 1) ?: return@getOrPut null
            val b = OriginalBody.parse(d)
            val (v, t) = b.toMesh(palette)
            BodyAsset(n, b.zv, v, t, b.lines(palette), b.spheres(palette), if (b.isArticulated) b.animatedParts() else null)
        } catch (_: Exception) {
            null
        }
    }

    /** Animation [n] (entry n + 1 of LISTANIM.PAK) or null. */
    fun anim(n: Int): AnimData? = anims.getOrPut(n) {
        try {
            animPak.get(n + 1)?.let { AnimData.parse(it) }
        } catch (_: Exception) {
            null
        }
    }

    /** The text of a book, letter or notebook: entry [index] + 2 of ENGLISH.PAK (the original's `readBook(index + 1)`). */
    fun bookText(index: Int): ByteArray? = textPak.get(index + 2)

    /** The page picture of a reader (type 0 letter, 1 book, 2 notebook): ITD_RESS.PAK entries 7 to 9, 320 x 200 in the game's colours (ARGB). */
    fun bookPicture(type: Int): IntArray? {
        val d = resourcePak.get(7 + type) ?: return null
        if (d.size < 64000) return null
        return IntArray(64000) { palette[d[it].toInt() and 0xFF] }
    }

    /** Sound sample [n] (entry n + 1 of LISTSAMP.PAK) or null. */
    fun sample(n: Int): VocSample? = samples.getOrPut(n) {
        try {
            samplePak?.get(n + 1)?.let { VocSample.parse(it) }
        } catch (_: Exception) {
            null
        }
    }
}
