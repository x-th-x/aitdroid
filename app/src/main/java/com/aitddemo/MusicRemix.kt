package com.aitddemo

import java.io.ByteArrayOutputStream

/*
 * New background music in the style of the original (NOT part of the original game; an addition on this branch). The music of the game is AdLib music (ADLM files in LISTMUS.PAK): per
 * voice a stream of commands (note, instrument, volume, pitch, delay) for the OPL2 chip, and a table of instruments. This file reads such a track into a timeline of events per
 * voice, changes it (key, tempo, timbre, an echo voice) and writes an ADLM file again that the driver of the game plays like the originals. The new tracks are made at runtime from
 * the user's own LISTMUS.PAK; nothing of the music is stored.
 */
object MusicRemix {
    private const val VOICES = 11
    private const val INSTRUMENT_SIZE = 13

    // commands of a voice stream (the low 7 bits of the first byte; bit 7 ends a group of commands that run at the same time)
    private const val END = 0
    private const val DELAY = 1
    private const val NOTE = 2
    private const val INSTRUMENT = 3
    private const val ATTENUATION = 4
    private const val PITCH = 5

    /** One command at a time of the voice (in ticks of the driver, about 59 per second). */
    class Ev(val t: Int, val op: Int, val par: Int)

    class Line(val events: MutableList<Ev>, var end: Int)

    class Song(val header: ByteArray, val instruments: MutableList<ByteArray>, val lines: Array<Line?>)

    private fun b(d: ByteArray, p: Int) = if (p in d.indices) d[p].toInt() and 0xFF else 0
    private fun u16(d: ByteArray, p: Int) = b(d, p) or (b(d, p + 1) shl 8)
    private fun u32(d: ByteArray, p: Int) = u16(d, p) or (u16(d, p + 2) shl 16)

    fun parse(data: ByteArray): Song {
        val base = u16(data, 0x34)
        val offsets = IntArray(VOICES) { u32(data, 8 + it * 4) }
        val first = offsets.filter { it != 0 }.min()
        val instruments = MutableList((first - base) / INSTRUMENT_SIZE) { data.copyOfRange(base + it * INSTRUMENT_SIZE, base + (it + 1) * INSTRUMENT_SIZE) }
        return Song(data.copyOf(base), instruments, Array(VOICES) { v -> if (offsets[v] == 0) null else parseLine(data, offsets[v]) })
    }

    /** Reads a voice stream like the driver does: a group of commands runs, then the voice waits for the delay that was set last. */
    private fun parseLine(d: ByteArray, start: Int): Line {
        val events = ArrayList<Ev>()
        var p = start
        var t = 0
        var reload = 0
        while (true) {
            var last = false
            var ended = false
            while (!last) {
                val op = u16(d, p)
                p += 2
                val c = op and 0x7F
                val par = op shr 8
                last = op and 0x80 != 0
                when (c) {
                    END -> { ended = true; break }
                    DELAY -> { reload = par or (b(d, p) shl 8); p++ }
                    NOTE, INSTRUMENT, ATTENUATION, PITCH -> events.add(Ev(t, c, par))
                }
            }
            if (ended) return Line(events, t)
            t += reload
            if (p > d.size + 8) return Line(events, t)
        }
    }

    /** Writes the song as an ADLM file: header, instrument table, then the voice streams one after the other. */
    fun write(song: Song): ByteArray {
        val out = ByteArrayOutputStream()
        val header = song.header.copyOf()
        out.write(header)
        for (i in song.instruments) out.write(i)
        for ((v, line) in song.lines.withIndex()) {
            val at = if (line == null) 0 else out.size()
            for (k in 0 until 4) header[8 + v * 4 + k] = ((at shr (8 * k)) and 0xFF).toByte()
            if (line != null) writeLine(out, line)
        }
        val bytes = out.toByteArray()
        System.arraycopy(header, 0, bytes, 0, header.size)
        return bytes
    }

    private fun writeLine(out: ByteArrayOutputStream, line: Line) {
        fun word(c: Int, par: Int, last: Boolean) { out.write(c or (if (last) 0x80 else 0)); out.write(par) }
        fun delay(ticks: Int) { val d = ticks.coerceIn(0, 0xFFFF); word(DELAY, d and 0xFF, true); out.write(d shr 8) }
        val evs = line.events.sortedBy { it.t } // stable: the order of the commands of one moment stays
        var t = 0
        var i = 0
        if (evs.isNotEmpty() && evs[0].t > 0) { delay(evs[0].t); t = evs[0].t }
        while (i < evs.size) {
            var j = i
            while (j < evs.size && evs[j].t == evs[i].t) j++
            for (k in i until j) word(evs[k].op, evs[k].par, false)
            val next = if (j < evs.size) evs[j].t else line.end
            delay((next - evs[i].t).coerceAtLeast(0))
            t = next
            i = j
        }
        out.write(0x80); out.write(0) // the end of the stream
    }

    // ------------------------------------------------------------------------------------------------------------------ changes

    /** Moves every note by [semitones] (notes are octave * 16 + semitone, bit 7 = the note is released; the drums of a rhythm track are left alone). */
    fun transpose(song: Song, semitones: Int) {
        val rhythm = song.header[0x3D].toInt() == 0
        for ((v, line) in song.lines.withIndex()) {
            if (line == null || (rhythm && v >= 6)) continue
            for ((i, e) in line.events.withIndex()) {
                if (e.op != NOTE) continue
                val n = ((e.par shr 4) and 7) * 12 + (e.par and 15) + semitones
                if (n !in 0..95) continue
                line.events[i] = Ev(e.t, NOTE, (e.par and 0x80) or ((n / 12) shl 4) or (n % 12))
            }
        }
    }

    /** Stretches the time of all voices by [factor] (more than 1 = slower). */
    fun stretch(song: Song, factor: Float) {
        for (line in song.lines) {
            if (line == null) continue
            for ((i, e) in line.events.withIndex()) line.events[i] = Ev((e.t * factor).toInt(), e.op, e.par)
            line.end = (line.end * factor).toInt()
        }
    }

    /** Changes a byte of an instrument (operator 1 at 1..6, operator 2 at 7..12: level, tremolo/vibrato/multiplier, feedback, waveform, attack/decay, sustain/release). */
    fun patch(song: Song, instrument: Int, offset: Int, value: Int) {
        song.instruments.getOrNull(instrument)?.let { it[offset] = value.toByte() }
    }

    /** Switches vibrato (bit 6) and tremolo (bit 7) of an operator of an instrument on. */
    fun wobble(song: Song, instrument: Int, operator: Int, vibrato: Boolean, tremolo: Boolean) {
        val at = if (operator == 1) 2 else 8
        val e = song.instruments.getOrNull(instrument) ?: return
        var v = e[at].toInt() and 0xFF
        if (vibrato) v = v or 0x40
        if (tremolo) v = v or 0x80
        e[at] = v.toByte()
    }

    /** The voice of the track with the fewest notes (the one that can be spared best). */
    fun sparestVoice(song: Song): Int =
        song.lines.withIndex().filter { it.value != null }.minBy { (_, l) -> l!!.events.count { it.op == NOTE && it.par and 0x80 == 0 } }.index

    /**
     * Makes [target] an echo of [source]: the notes of the source, [delay] ticks later, [semitones] higher, with the instrument [instrument] and the attenuation [attenuation]
     * (the old voice of [target] is replaced).
     */
    fun echo(song: Song, source: Int, target: Int, delay: Int, semitones: Int, instrument: Int, attenuation: Int) {
        val src = song.lines[source] ?: return
        val events = ArrayList<Ev>()
        events.add(Ev(0, INSTRUMENT, instrument))
        events.add(Ev(0, ATTENUATION, attenuation))
        events.add(Ev(0, PITCH, 0))
        for (e in src.events) {
            if (e.op != NOTE) continue
            val n = ((e.par shr 4) and 7) * 12 + (e.par and 15) + semitones
            if (n !in 0..95) continue
            events.add(Ev(e.t + delay, NOTE, (e.par and 0x80) or ((n / 12) shl 4) or (n % 12)))
        }
        song.lines[target] = Line(events, src.end + delay)
    }

    /** A copy of instrument [from] at the end of the table; returns its number. */
    fun copyInstrument(song: Song, from: Int): Int {
        song.instruments.add(song.instruments[from].copyOf())
        return song.instruments.size - 1
    }

    // ------------------------------------------------------------------------------------------------------------------ the new tracks

    class NewTrack(val name: String, val data: ByteArray)

    /**
     * Three new tracks made from track [source] (the slow, eerie music of the attic; its entry in LISTMUS.PAK): a darker slower version in another key, a hollow deep one with
     * other timbres, and one with an echo voice.
     */
    fun derive(source: ByteArray): List<NewTrack> {
        val out = ArrayList<NewTrack>()

        // Dusk: three semitones lower, a quarter slower, the lead and the pads with a slow vibrato
        parse(source).let { s ->
            transpose(s, -3)
            stretch(s, 1.25f)
            for (i in intArrayOf(5, 6, 2)) wobble(s, i, 2, vibrato = true, tremolo = false)
            wobble(s, 5, 1, vibrato = true, tremolo = false)
            out.add(NewTrack("Dusk", write(s)))
        }

        // Hollow: a fifth lower and half as slow again, hollow waveforms (half sine and absolute sine) for the lead and the arpeggios, tremolo on the pads
        parse(source).let { s ->
            transpose(s, -7)
            stretch(s, 1.5f)
            patch(s, 5, 10, 1)
            patch(s, 0, 10, 1)
            patch(s, 8, 10, 2)
            patch(s, 2, 10, 1)
            for (i in intArrayOf(6, 7, 3)) wobble(s, i, 2, vibrato = false, tremolo = true)
            out.add(NewTrack("Hollow", write(s)))
        }

        // Echo: the original key and tempo; the lead is repeated an octave higher and 0.85 s later by a glassy voice that takes the place of the sparest voice
        parse(source).let { s ->
            val bell = copyInstrument(s, 5)
            patch(s, bell, 10, 1)
            wobble(s, bell, 2, vibrato = true, tremolo = true)
            wobble(s, bell, 1, vibrato = true, tremolo = false)
            echo(s, source = 0, target = sparestVoice(s), delay = 50, semitones = 12, instrument = bell, attenuation = 24)
            out.add(NewTrack("Echo", write(s)))
        }
        return out
    }
}
