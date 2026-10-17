package com.aitddemo.game

import com.aitddemo.AnimatedParts
import com.aitddemo.OriginalBody
import com.aitddemo.RoomLoader
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

    /** The same body without its round parts of one size (the two heads that stand for the occupants of the car in the intro). */
    fun withoutSpheresOfRadius(radius: Int): BodyAsset {
        val kept = ArrayList<Int>()
        for (i in 0 until spheres.size / 3) if (spheres[i * 3 + 1] != radius) { kept.add(spheres[i * 3]); kept.add(spheres[i * 3 + 1]); kept.add(spheres[i * 3 + 2]) }
        return BodyAsset(number, zv, vertices, triangles, lines, kept.toIntArray(), parts)
    }
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
    val rooms: RoomLoader,
    val texts: GameTexts,
    private val textPak: Pak,
    private val resourcePak: Pak,
    private val trackPak: Pak? = null,
    /** The constants of DEFINES.ITD (CVars): sample numbers, the weight limit, the object that sends things back and so on. */
    val defines: ShortArray = ShortArray(0),
    /** PRIORITY.ITD: one byte for each sample, how important it is (a sample does not interrupt one with a higher priority), or null when the file is missing. */
    private val priorities: ByteArray? = null,
    /** Together mode: the bodies and animations of the other hero (Emily's when Carnby plays and the other way round), same numbers as the player's own lists. */
    private val companionBodyPak: Pak? = null,
    private val companionAnimPak: Pak? = null,
) {
    /** Does this game have the other hero's bodies and animations (Together mode)? */
    val hasCompanion: Boolean get() = companionBodyPak != null && companionAnimPak != null

    /** The priority of sample [n] (0 when the file is missing). */
    fun samplePriority(n: Int): Int = priorities?.getOrNull(n)?.toInt()?.and(0xFF) ?: 0

    private val bodies = HashMap<Int, BodyAsset?>()
    private val anims = HashMap<Int, AnimData?>()
    private val companionBodies = HashMap<Int, BodyAsset?>()
    private val companionAnims = HashMap<Int, AnimData?>()
    private val samples = HashMap<Int, VocSample?>()
    private val scripts = HashMap<Int, ShortArray?>()
    private val tracks = HashMap<Int, ShortArray?>()

    /** Life script [n] as 16 bit words. */
    fun script(n: Int): ShortArray? = scripts.getOrPut(n) {
        lifePak.get(n + 1)?.let { d -> ShortArray(d.size / 2) { ((d[it * 2].toInt() and 0xFF) or (d[it * 2 + 1].toInt() shl 8)).toShort() } }?.let { if (hasCompanion) TogetherIntro.patchScript(n, it) else it }
    }

    /** Body [n] (entry n + 1 of LISTBODY.PAK) or null. */
    fun body(n: Int): BodyAsset? = bodies.getOrPut(n) { readBody(bodyPak, n) }

    /** Body [n] of the other hero's list (Together mode), or null. */
    fun companionBody(n: Int): BodyAsset? = companionBodies.getOrPut(n) { companionBodyPak?.let { readBody(it, n) } }

    private fun readBody(pak: Pak, n: Int): BodyAsset? = try {
        val d = pak.get(n + 1)
        if (d == null) null else {
            val b = OriginalBody.parse(d)
            val (v, t) = b.toMesh(palette)
            BodyAsset(n, b.zv, v, t, b.lines(palette), b.spheres(palette), if (b.isArticulated) b.animatedParts() else null)
        }
    } catch (_: Exception) {
        null
    }

    /** Animation [n] (entry n + 1 of LISTANIM.PAK) or null. */
    fun anim(n: Int): AnimData? = anims.getOrPut(n) {
        try {
            animPak.get(n + 1)?.let { AnimData.parse(it) }
        } catch (_: Exception) {
            null
        }
    }

    /** Animation [n] of the other hero's list (Together mode), or null. */
    fun companionAnim(n: Int): AnimData? = companionAnims.getOrPut(n) {
        try {
            companionAnimPak?.get(n + 1)?.let { AnimData.parse(it) }
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

    /** Movement path [n] (entry n + 1 of LISTTRAK.PAK) as 16 bit words, or null. */
    fun track(n: Int): ShortArray? = tracks.getOrPut(n) {
        trackPak?.get(n + 1)?.let { d -> ShortArray(d.size / 2) { ((d[it * 2].toInt() and 0xFF) or (d[it * 2 + 1].toInt() shl 8)).toShort() } }?.let { if (hasCompanion) TogetherIntro.patchTrack(n, it) else it }
    }

    /** A full screen picture of ITD_RESS.PAK (entry index + 1: 64000 pixels, with their own colours when 770 bytes of header come first), 320 x 200 ARGB, or null. */
    fun picture(index: Int): IntArray? {
        val d = resourcePak.get(index + 1) ?: return null
        if (d.size < 64000) return null
        val own = d.size >= 64770
        return IntArray(64000) { i ->
            val c = d[d.size - 64000 + i].toInt() and 0xFF
            if (own) 0xFF000000.toInt() or ((d[2 + c * 3].toInt() and 0xFF) shl 16) or ((d[3 + c * 3].toInt() and 0xFF) shl 8) or (d[4 + c * 3].toInt() and 0xFF) else palette[c]
        }
    }

    /** Entry [n] of ITD_RESS.PAK (the pictures, fonts and tile sheets of the interface) or null. */
    fun resource(n: Int): ByteArray? = resourcePak.get(n)

    /** Sound sample [n] (entry n + 1 of LISTSAMP.PAK) or null. */
    fun sample(n: Int): VocSample? = samples.getOrPut(n) {
        try {
            samplePak?.get(n + 1)?.let { VocSample.parse(it) }
        } catch (_: Exception) {
            null
        }
    }
}
