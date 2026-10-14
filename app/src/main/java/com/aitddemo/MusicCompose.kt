package com.aitddemo

import com.aitddemo.MusicRemix.Ev
import com.aitddemo.MusicRemix.Line
import com.aitddemo.MusicRemix.Song

/*
 * Music that is composed here (NOT part of the original game; an addition on this branch): new pieces for the AdLib driver of the game. They use the instruments of the original
 * track that is handed in (so they sound like the game) and the same kind of voices - bass, pads, arpeggios, a bell and a melody - but the notes are new. Everything is made at
 * runtime; nothing of it is stored.
 */
object MusicCompose {
    private const val NOTE = 2
    private const val INSTRUMENT = 3
    private const val ATTENUATION = 4
    private const val PITCH = 5

    /** The number of a note name ("A4" = the A above middle C, "G#5", "Bb3") as the driver has it: octave * 16 + semitone. */
    private fun note(name: String): Int {
        val base = when (name[0]) { 'C' -> 0; 'D' -> 2; 'E' -> 4; 'F' -> 5; 'G' -> 7; 'A' -> 9; 'B' -> 11; else -> 0 }
        var i = 1
        var s = base
        while (i < name.length && (name[i] == '#' || name[i] == 'b')) { s += if (name[i] == '#') 1 else -1; i++ }
        var oct = name.substring(i).toInt()
        if (s < 0) { s += 12; oct-- }
        if (s > 11) { s -= 12; oct++ }
        return (oct.coerceIn(0, 7) shl 4) or s
    }

    /** One voice that is being written: the notes go to [line] at the given ticks; every note is released a little before the next one. */
    private class Voice(val line: Line) {
        fun setup(instrument: Int, attenuation: Int) {
            line.events.add(Ev(0, NOTE, 0x80))
            line.events.add(Ev(0, INSTRUMENT, instrument))
            line.events.add(Ev(0, ATTENUATION, attenuation))
            line.events.add(Ev(0, PITCH, 0))
        }

        fun play(tick: Int, length: Int, name: String, gate: Float = 0.9f) {
            val n = note(name)
            line.events.add(Ev(tick, NOTE, n))
            line.events.add(Ev(tick + (length * gate).toInt().coerceAtLeast(1), NOTE, n or 0x80))
        }

        fun volume(tick: Int, attenuation: Int) { line.events.add(Ev(tick, ATTENUATION, attenuation)) }
    }

    /** A chord: root, third, fifth as note names of one octave (the bass plays the root two octaves lower). */
    private class Chord(val root: String, val third: String, val fifth: String)

    private val CHORDS = mapOf(
        "Am" to Chord("A", "C", "E"), "F" to Chord("F", "A", "C"), "Dm" to Chord("D", "F", "A"), "E" to Chord("E", "G#", "B"),
        "C" to Chord("C", "E", "G"), "G" to Chord("G", "B", "D"), "Em" to Chord("E", "G", "B"), "D" to Chord("D", "F#", "A"), "B" to Chord("B", "D#", "F#"),
        "Bm" to Chord("B", "D", "F#"),
    )

    /** A melody: notes with their length in beats, "R" = a rest. */
    private fun melody(text: String): List<Pair<String, Float>> = text.trim().split(Regex("\\s+")).map { val p = it.split(":"); p[0] to p[1].toFloat() }

    private class Plan(val sections: List<Section>)

    private class Section(val chords: List<String>, val melody: List<Pair<String, Float>>?, val arp: Boolean, val offbeat: Boolean, val bell: Boolean, val echo: Boolean, val fade: Boolean = false)

    private fun build(source: Song, beat: Int, plan: Plan, instruments: IntArray, drive: Boolean, extra: List<ByteArray> = emptyList(), leadGate: Float = 0.9f, leadAttenuation: Int = 1): ByteArray {
        // the voices: 0 melody, 1 arpeggio, 2 off-beat arpeggio, 3 and 4 pads, 5 bass, 6 bell
        val lines = Array<Line?>(11) { null }
        val v = Array(7) { Voice(Line(ArrayList(), 0)).also { vo -> lines[it] = vo.line } }
        v[0].setup(instruments[0], leadAttenuation)
        v[1].setup(instruments[1], 5)
        v[2].setup(instruments[2], 15)
        v[3].setup(instruments[3], 15)
        v[4].setup(instruments[4], 17)
        v[5].setup(instruments[5], 5)
        v[6].setup(instruments[6], 13)
        val bar = beat * 4
        var t = 0
        for (section in plan.sections) {
            val sectionStart = t
            for ((b, name) in section.chords.withIndex()) {
                val c = CHORDS.getValue(name)
                val at = sectionStart + b * bar
                // the bass: the root on the first beat and the fifth on the third (a steady pulse in the driving pieces)
                if (drive) {
                    for (e in 0 until 8) v[5].play(at + e * beat / 2, beat / 2, (if (e % 4 == 3) c.fifth else c.root) + "2", 0.8f)
                } else {
                    v[5].play(at, beat * 2, c.root + "2", 0.95f)
                    v[5].play(at + beat * 2, beat * 2, c.fifth + "2", 0.95f)
                }
                // the pads: the fifth and the third in one long note
                v[3].play(at, bar, c.fifth + "3", 0.97f)
                v[4].play(at, bar, c.third + "3", 0.97f)
                // the arpeggio: root, third, fifth, third in sixteenths of the beat
                if (section.arp) {
                    val tones = arrayOf(c.root + "3", c.third + "3", c.fifth + "3", c.third + "4")
                    for (k in 0 until 16) v[1].play(at + k * beat / 4, beat / 4, tones[k % 4], 0.8f)
                }
                // the second arpeggio: on the off-beats, high
                if (section.offbeat) {
                    for (k in 0 until 4) v[2].play(at + k * beat + beat / 2, beat / 2, (if (k % 2 == 0) c.fifth else c.root) + "4", 0.7f)
                }
                // the bell: the root of the chord, high, at the start of every second bar
                if (section.bell && b % 2 == 0) v[6].play(at, bar, c.root + "6", 0.97f)
            }
            // the melody
            section.melody?.let { m ->
                var at = sectionStart
                for ((n, len) in m) {
                    val ticks = (len * beat).toInt()
                    if (n != "R") {
                        v[0].play(at, ticks, n, leadGate)
                        if (section.echo) v[6].play(at + beat, ticks, up(n), 0.9f)
                    }
                    at += ticks
                }
            }
            t = sectionStart + section.chords.size * bar
        }
        // the voices end together
        for (line in lines) line?.end = t
        val last = plan.sections.last()
        if (last.fade) {
            // the last bars fade out: every voice gets quieter bar by bar
            val start = t - last.chords.size * bar
            for ((i, base) in intArrayOf(leadAttenuation, 5, 15, 15, 17, 5, 13).withIndex()) for (b in 0 until last.chords.size) v[i].volume(start + b * bar, base + (b + 1) * 8)
        }
        val song = Song(source.header.copyOf(), source.instruments.map { it.copyOf() }.toMutableList().also { list -> extra.forEach { list.add(it.copyOf()) } }, lines)
        return MusicRemix.write(song)
    }

    /**
     * An organ for the chip: both operators sustain without a decay and sound together (additive), the first at the pitch of the note and the second an octave above it, a little
     * feedback adds the edge of the pipes; the attack is fast but not a click, the release short.
     */
    private val ORGAN = intArrayOf(
        0x41,
        0x1C, 0x21, 0x05, 0x00, 0xA0, 0x04, // operator 1: level, sustain + multiplier 1, feedback 2 and additive connection, sine, attack/decay, sustain/release
        0x2A, 0x22, 0x00, 0x00, 0xA0, 0x04, // operator 2: the same, multiplier 2 (an octave up)
    ).map { it.toByte() }.toByteArray()

    /** The same note an octave higher. */
    private fun up(n: String): String = n.dropLast(1) + (n.last() - '0' + 1)

    /** Slow and sad: A minor, 84 beats per minute, intro, a melody, a higher one, the first again with an echo, a bridge, the end with a fade. About 2 minutes. */
    private fun nocturne(source: Song): ByteArray {
        val a = melody("A4:1.5 C5:.5 E5:2  F5:1.5 E5:.5 C5:2  D5:1 F5:1 E5:1 D5:1  B4:1 G#4:1 E5:2  A4:1.5 C5:.5 E5:1 A5:1  G5:1.5 F5:.5 E5:1 C5:1  D5:1 E5:1 F5:1 D5:1  E5:1.5 D5:.5 B4:1 G#4:1")
        val b = melody("A5:1.5 G5:.5 F5:2  D5:1 G5:1 B5:2  C6:1.5 B5:.5 A5:2  G#5:1 B5:1 E6:2  C6:1 A5:1 F5:2  D6:1 B5:1 G5:2  E6:1.5 C6:.5 A5:1 E5:1  B5:2 G#5:1 E5:1")
        val bridge = melody("D5:4 R:4 E5:3 G5:1 F5:2 E5:1 D5:1")
        val end = melody("A4:2 C5:1 E5:1 A4:2 R:2")
        val plan = Plan(
            listOf(
                Section(listOf("Am", "F", "Dm", "E"), null, arp = false, offbeat = false, bell = true, echo = false),
                Section(listOf("Am", "F", "Dm", "E", "Am", "F", "Dm", "E"), a, arp = true, offbeat = false, bell = false, echo = false),
                Section(listOf("F", "G", "Am", "E", "F", "G", "Am", "E"), b, arp = true, offbeat = true, bell = false, echo = false),
                Section(listOf("Am", "F", "Dm", "E", "Am", "F", "Dm", "E"), a, arp = true, offbeat = false, bell = true, echo = true),
                Section(listOf("Dm", "Em", "F", "E"), bridge, arp = false, offbeat = false, bell = true, echo = false),
                Section(listOf("Am", "F", "Dm", "E", "Am", "F", "Dm", "E"), a, arp = true, offbeat = true, bell = true, echo = true),
                Section(listOf("Am", "F", "Am", "Am"), end, arp = false, offbeat = false, bell = true, echo = false, fade = true),
            ),
        )
        // instruments of the original track: the arpeggios, the pads, the bass, the bell; the melody is played by an organ (a new instrument at the end of the table)
        return build(source, 44, plan, intArrayOf(source.instruments.size, 0, 8, 6, 7, 2, 4), drive = false, extra = listOf(ORGAN), leadGate = 0.96f, leadAttenuation = 7)
    }

    /** Tense and driving: E minor, 100 beats per minute, a pulsing bass of eighths under a sharp melody. About 1 minute 20. */
    private fun pursuit(source: Song): ByteArray {
        val a = melody("E5:.5 E5:.5 G5:1 B5:1 A5:1  G5:.5 G5:.5 F#5:1 D5:1 E5:1  E5:.5 E5:.5 G5:1 B5:1 C6:1  B5:1 A5:1 F#5:1 B4:1")
        val b = melody("B5:1 A5:.5 G5:.5 F#5:1 G5:1  E5:2 G5:1 E5:1  C6:1 B5:.5 A5:.5 G5:1 A5:1  B5:1 F#5:1 D#5:1 F#5:1")
        val plan = Plan(
            listOf(
                Section(listOf("Em", "Em", "C", "D"), null, arp = true, offbeat = false, bell = false, echo = false),
                Section(listOf("Em", "C", "D", "B"), a, arp = true, offbeat = true, bell = false, echo = false),
                Section(listOf("Em", "C", "D", "B"), a, arp = true, offbeat = true, bell = true, echo = true),
                Section(listOf("C", "D", "Em", "B"), b, arp = true, offbeat = true, bell = true, echo = true),
                Section(listOf("Em", "C", "D", "B"), a, arp = true, offbeat = true, bell = true, echo = true),
                Section(listOf("Em", "Em", "Em", "Em"), melody("E5:4 B4:4 E5:4 E5:4"), arp = false, offbeat = false, bell = true, echo = false, fade = true),
            ),
        )
        return build(source, 36, plan, intArrayOf(5, 0, 8, 6, 3, 1, 4), drive = true)
    }

    /** The composed tracks, made with the instruments of [source] (an original ADLM track, for instance the music of the attic). */
    fun compose(source: ByteArray): List<MusicRemix.NewTrack> {
        val song = MusicRemix.parse(source)
        return listOf(MusicRemix.NewTrack("Nocturne", nocturne(song)), MusicRemix.NewTrack("Pursuit", pursuit(song)))
    }
}
