package com.aitddemo

import com.aitddemo.music.AdlibDriver
import com.aitddemo.music.Opl2
import com.aitddemo.original.GameFiles
import com.aitddemo.original.Pak

/** One music track of LISTMUS.PAK: its number (entry in the file), the raw ADLM data, length in seconds and the number of voices used. */
class MusicTrack(val number: Int, val data: ByteArray, val seconds: Int, val voices: Int, val rhythm: Boolean, val name: String? = null)

/** Reads the AdLib music tracks (ADLM files) directly from LISTMUS.PAK of the original game. */
object MusicOriginalReader {
    const val FILE = "LISTMUS.PAK"
    val REQUIRED = listOf(FILE)

    /** Longest track length measured, in seconds (tracks that never end would otherwise run forever). */
    private const val MAX_SECONDS = 1200

    fun missingFiles(files: GameFiles): List<String> = REQUIRED.filter { !files.exists(it) }

    fun read(files: GameFiles): List<MusicTrack> {
        val missing = missingFiles(files)
        if (missing.isNotEmpty()) throw OriginalFilesException("Fehlende Dateien: " + missing.joinToString(", "))
        try {
            val pak = Pak(files.read(FILE)!!)
            val tracks = ArrayList<MusicTrack>()
            for (i in 1 until pak.size) {
                val d = pak.get(i) ?: continue
                if (d.size < 0x40 || String(d, 0, 4, Charsets.ISO_8859_1) != "ADLM") continue
                tracks.add(trackOf(i, d))
            }
            if (tracks.isEmpty()) throw OriginalFilesException("$FILE enthält keine AdLib-Musik")
            // new tracks (composed ones first, then those derived from the music of the attic, track 1), listed first; their numbers (101 and up) are not entries of the file
            val base = tracks.firstOrNull { it.number == 1 }
            val derived = if (base == null) emptyList() else try {
                (MusicCompose.compose(base.data) + MusicRemix.derive(base.data)).mapIndexed { k, n -> trackOf(101 + k, n.data, n.name) }
            } catch (_: Exception) {
                emptyList()
            }
            return derived + tracks
        } catch (e: OriginalFilesException) {
            throw e
        } catch (e: Exception) {
            throw OriginalFilesException("Die Musikdatei konnte nicht gelesen werden (${e.javaClass.simpleName}: ${e.message}).")
        }
    }

    private fun trackOf(number: Int, d: ByteArray, name: String? = null): MusicTrack {
        var voices = 0
        for (v in 0 until 11) {
            val off = (d[8 + v * 4].toInt() and 0xFF) or ((d[9 + v * 4].toInt() and 0xFF) shl 8) or ((d[10 + v * 4].toInt() and 0xFF) shl 16) or ((d[11 + v * 4].toInt() and 0xFF) shl 24)
            if (off != 0) voices++
        }
        return MusicTrack(number, d, measure(d), voices, d[0x3D].toInt() == 0, name)
    }

    /** Length of a track in seconds: runs the driver (without producing sound) until all voices have ended. */
    private fun measure(data: ByteArray): Int {
        val driver = AdlibDriver(Opl2())
        driver.start(data)
        val tickSeconds = com.aitddemo.music.AdlibSynth.SAMPLES_PER_TICK / 44100.0
        var ticks = 0
        while (!driver.finished && ticks * tickSeconds < MAX_SECONDS) {
            driver.tick()
            ticks++
        }
        return Math.round(ticks * tickSeconds).toInt()
    }
}
