package com.aitddemo.music

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * Software emulation of the YM3812 (OPL2) FM sound chip of the AdLib card: 9 channels of two operators each (or 6 channels plus
 * 5 rhythm voices), register interface as on the real chip, mono output at [sampleRate].
 *
 * Operator frequencies, envelope times, key scaling, tremolo, vibrato, waveforms, feedback and the rhythm section follow the chip's
 * documented behaviour. The envelope and the sine are computed in floating point instead of with the chip's log/exp tables.
 */
class Opl2(private val sampleRate: Int = 44100) {
    private class Slot {
        var mult = 1
        var ksr = false
        var sustained = false
        var vib = false
        var am = false
        var ksl = 0
        var tl = 0
        var ar = 0
        var dr = 0
        var sl = 0
        var rr = 0
        var wave = 0

        var phase = 0.0
        var state = OFF
        var att = 511.0
        var out = 0.0
        var prev = 0.0
        var keyOn = false
    }

    private class Channel {
        var fnum = 0
        var block = 0
        var keyOn = false
        var feedback = 0
        var additive = false
    }

    private val slots = Array(18) { Slot() }
    private val channels = Array(9) { Channel() }
    private val reg = IntArray(256)
    private var waveSelect = false
    private var deepAm = false
    private var deepVib = false
    private var rhythm = false
    private var drumBits = 0
    private var lfoAm = 0.0
    private var lfoVib = 0.0
    private var noise = 1

    /** Writes [value] to the chip register [register] (as the pair of port writes on the real card). */
    /** Optional observer of register writes (for tools). */
    var listener: ((Int, Int) -> Unit)? = null

    fun write(register: Int, value: Int) {
        listener?.invoke(register, value)
        val r = register and 0xFF
        val v = value and 0xFF
        reg[r] = v
        when {
            r == 0x01 -> waveSelect = v and 0x20 != 0
            r == 0xBD -> {
                deepAm = v and 0x80 != 0
                deepVib = v and 0x40 != 0
                val wasRhythm = rhythm
                rhythm = v and 0x20 != 0
                val newBits = v and 0x1F
                if (rhythm) {
                    // each drum bit keys its slots: on when the bit is set, off when it is cleared
                    for ((bit, slotIndices) in DRUM_SLOTS) for (s in slotIndices) setKey(s, newBits and bit != 0)
                } else if (wasRhythm) {
                    for ((_, slotIndices) in DRUM_SLOTS) for (s in slotIndices) setKey(s, channelKey(s))
                }
                drumBits = newBits
            }
            r in 0x20..0x35 -> slotOf(r - 0x20)?.let { s ->
                val sl = slots[s]
                sl.am = v and 0x80 != 0
                sl.vib = v and 0x40 != 0
                sl.sustained = v and 0x20 != 0
                sl.ksr = v and 0x10 != 0
                sl.mult = v and 0x0F
            }
            r in 0x40..0x55 -> slotOf(r - 0x40)?.let { s ->
                slots[s].ksl = v shr 6
                slots[s].tl = v and 0x3F
            }
            r in 0x60..0x75 -> slotOf(r - 0x60)?.let { s ->
                slots[s].ar = v shr 4
                slots[s].dr = v and 0x0F
            }
            r in 0x80..0x95 -> slotOf(r - 0x80)?.let { s ->
                slots[s].sl = v shr 4
                slots[s].rr = v and 0x0F
            }
            r in 0xA0..0xA8 -> channels[r - 0xA0].fnum = (channels[r - 0xA0].fnum and 0x300) or v
            r in 0xB0..0xB8 -> {
                val c = r - 0xB0
                val ch = channels[c]
                ch.fnum = (ch.fnum and 0xFF) or ((v and 3) shl 8)
                ch.block = (v shr 2) and 7
                val on = v and 0x20 != 0
                if (on != ch.keyOn) {
                    ch.keyOn = on
                    if (!(rhythm && c >= 6)) {
                        setKey(MOD_SLOT[c], on)
                        setKey(MOD_SLOT[c] + 3, on)
                    }
                }
            }
            r in 0xC0..0xC8 -> {
                channels[r - 0xC0].feedback = (v shr 1) and 7
                channels[r - 0xC0].additive = v and 1 != 0
            }
            r in 0xE0..0xF5 -> slotOf(r - 0xE0)?.let { s -> slots[s].wave = if (waveSelect) v and 3 else 0 }
        }
    }

    private fun channelKey(slot: Int): Boolean {
        val c = when (slot) { in 12..14 -> 6 + slot - 12; in 15..17 -> 6 + slot - 15; else -> return false }
        return channels[c].keyOn
    }

    private fun slotOf(offset: Int): Int? {
        val inGroup = offset and 7
        if (inGroup > 5 || offset > 0x15) return null
        return (offset shr 3) * 6 + inGroup
    }

    private fun setKey(index: Int, on: Boolean) {
        val s = slots[index]
        if (on && !s.keyOn) {
            s.phase = 0.0
            s.state = ATTACK
            if (s.ar == 15) s.att = 0.0
        } else if (!on && s.keyOn) {
            if (s.state != OFF) s.state = RELEASE
        }
        s.keyOn = on
    }

    private fun channelOf(slotIndex: Int): Channel {
        val group = slotIndex / 6
        val inGroup = slotIndex % 6
        return channels[group * 3 + inGroup % 3]
    }

    private fun rateOffset(ch: Channel, s: Slot): Int {
        val keycode = (ch.block shl 1) or ((ch.fnum shr 9) and 1)
        return if (s.ksr) keycode else keycode shr 2
    }

    private fun effectiveRate(r: Int, ch: Channel, s: Slot): Int = if (r == 0) 0 else minOf(63, r * 4 + rateOffset(ch, s))

    private fun updateEnvelope(index: Int) {
        val s = slots[index]
        val ch = channelOf(index)
        when (s.state) {
            OFF -> s.att = 511.0
            ATTACK -> {
                val rate = effectiveRate(s.ar, ch, s)
                if (rate == 0) return
                if (s.ar == 15 || rate >= 60) s.att = 0.0 else s.att *= attackFactor[rate]
                if (s.att < 0.4) {
                    s.att = 0.0
                    s.state = DECAY
                }
            }
            DECAY -> {
                val rate = effectiveRate(s.dr, ch, s)
                s.att += stepPerSample(rate)
                val level = (if (s.sl == 15) 31 else s.sl) * 16.0
                if (s.att >= level) {
                    s.att = level
                    s.state = SUSTAIN
                }
            }
            SUSTAIN -> if (!s.sustained) {
                s.att += stepPerSample(effectiveRate(s.rr, ch, s))
                if (s.att >= 511.0) { s.att = 511.0; s.state = OFF }
            }
            RELEASE -> {
                s.att += stepPerSample(effectiveRate(s.rr, ch, s))
                if (s.att >= 511.0) { s.att = 511.0; s.state = OFF }
            }
        }
    }

    /** Attenuation units added per sample for the chip rate [rate] (full range 511 units in the documented decay time). */
    private fun stepPerSample(rate: Int): Double = decayStep[rate]

    /** Per chip rate (0..63): factor applied to the attenuation each sample during attack, and units added per sample during decay/release. */
    private val attackFactor = DoubleArray(64) { rate ->
        if (rate == 0) 1.0 else exp(-1.0 / (ATTACK_MS * 2.0.pow(-(rate - 4) / 4.0) / 1000.0 * sampleRate / LN_FULL))
    }
    private val decayStep = DoubleArray(64) { rate ->
        when {
            rate == 0 -> 0.0
            rate >= 60 -> 511.0
            else -> 511.0 / (DECAY_MS * 2.0.pow(-(rate - 4) / 4.0) / 1000.0 * sampleRate)
        }
    }

    private fun kslAttenuation(ch: Channel, s: Slot): Double {
        if (s.ksl == 0) return 0.0
        val v = (KSL_ROM[ch.fnum shr 6] shl 2) - ((8 - ch.block) shl 5)
        if (v <= 0) return 0.0
        return (v shr KSL_SHIFT[s.ksl]).toDouble()
    }

    private fun waveform(w: Int, phaseCycles: Double): Double {
        val f = phaseCycles - floor(phaseCycles)
        val s = sin(2.0 * PI * f)
        return when (w) {
            0 -> s
            1 -> if (s < 0) 0.0 else s
            2 -> if (s < 0) -s else s
            else -> if ((f * 4).toInt() and 1 == 0) (if (s < 0) -s else s) else 0.0
        }
    }

    private fun gain(att: Double): Double = if (att >= 511.0) 0.0 else GAIN[(att * 4.0).toInt()]

    /** Output of [index] for the given phase modulation (in cycles); advances its phase. */
    private fun operator(index: Int, pm: Double): Double {
        val s = slots[index]
        val ch = channelOf(index)
        updateEnvelope(index)
        if (s.state == OFF) {
            s.out = 0.0
            return 0.0
        }
        var f = ch.fnum * 49716.0 / (1 shl (20 - ch.block)) * MULT[s.mult]
        if (s.vib) f *= 2.0.pow(lfoVib * (if (deepVib) 14.0 else 7.0) / 1200.0)
        val att = s.att + s.tl * 4 + kslAttenuation(ch, s) + (if (s.am) lfoAm * (if (deepAm) 4.8 else 1.0) / 0.1875 else 0.0)
        val v = waveform(s.wave, s.phase + pm) * gain(att)
        s.phase += f / sampleRate
        if (s.phase > 1e6) s.phase -= floor(s.phase)
        s.out = v
        return v
    }

    private fun feedbackModulation(s: Slot, fb: Int): Double = if (fb == 0) 0.0 else (s.out + s.prev) * 2.0.pow(fb - 7)

    private fun channelOutput(c: Int): Double {
        val ch = channels[c]
        val m = MOD_SLOT[c]
        val modSlot = slots[m]
        modSlot.prev = modSlot.out
        val modOut = operator(m, feedbackModulation(modSlot, ch.feedback))
        return if (ch.additive) modOut + operator(m + 3, 0.0) else operator(m + 3, modOut * PM_CYCLES)
    }

    /** Rhythm section: bass drum on channel 6, hi-hat/snare on 7 (modulator/carrier), tom/cymbal on 8. */
    private fun rhythmOutput(): Double {
        var sum = channelOutput(6) * 2.0
        val hhSlot = slots[13]
        val sdSlot = slots[16]
        val tomSlot = slots[14]
        val tcSlot = slots[17]
        // advance the four drum operators' envelopes and read their phases like the chip does
        for (i in intArrayOf(13, 14, 16, 17)) updateEnvelope(i)
        val ch7 = channels[7]
        val ch8 = channels[8]
        fun cycles(s: Slot, ch: Channel): Double {
            val f = ch.fnum * 49716.0 / (1 shl (20 - ch.block)) * MULT[s.mult]
            s.phase += f / sampleRate
            return s.phase
        }
        val hhPhase = cycles(hhSlot, ch7)
        val tomPhase = cycles(tomSlot, ch8)
        val sdPhase = cycles(sdSlot, ch7)
        val tcPhase = cycles(tcSlot, ch8)
        fun bit(p: Double, n: Int) = ((((p - floor(p)) * 1024).toInt()) shr n) and 1
        val rmXor = (bit(hhPhase, 2) xor bit(hhPhase, 7)) or (bit(hhPhase, 3) xor bit(tcPhase, 5)) or (bit(tcPhase, 3) xor bit(tcPhase, 5))
        noise = (noise shr 1) or (((noise xor (noise shr 14)) and 1) shl 22)
        val n = noise and 1
        fun level(s: Slot, ch: Channel): Double = gain(s.att + s.tl * 4 + kslAttenuation(ch, s))
        if (hasDrum(0x01) && slots[13].state != OFF) {
            val phase = (rmXor shl 9) or (if (rmXor xor n != 0) 0xD0 else 0x34)
            sum += waveform(slots[13].wave, phase / 1024.0) * level(hhSlot, ch7) * 2.0
        }
        if (hasDrum(0x08) && slots[16].state != OFF) {
            val b = bit(hhPhase, 8)
            val phase = (b shl 9) or ((b xor n) shl 8)
            sum += waveform(slots[16].wave, phase / 1024.0) * level(sdSlot, ch7) * 2.0
        }
        if (hasDrum(0x04) && slots[14].state != OFF) {
            sum += waveform(slots[14].wave, tomPhase) * level(tomSlot, ch8) * 2.0
        }
        if (hasDrum(0x02) && slots[17].state != OFF) {
            val phase = (rmXor shl 9) or 0x80
            sum += waveform(slots[17].wave, phase / 1024.0) * level(tcSlot, ch8) * 2.0
        }
        return sum
    }

    private fun hasDrum(bit: Int) = drumBits and bit != 0

    private var lfoAmPhase = 0.0
    private var lfoVibPhase = 0.0

    /** Next output sample (mono, about -1..1 per channel before mixing). */
    fun next(): Double {
        lfoAmPhase += AM_HZ / sampleRate
        lfoVibPhase += VIB_HZ / sampleRate
        lfoAm = triangle(lfoAmPhase)
        lfoVib = triangle(lfoVibPhase) * 2.0 - 1.0
        var sum = 0.0
        val melodic = if (rhythm) 6 else 9
        for (c in 0 until melodic) sum += channelOutput(c)
        if (rhythm) sum += rhythmOutput()
        return sum
    }

    private fun triangle(p: Double): Double {
        val f = p - floor(p)
        return if (f < 0.5) f * 2.0 else 2.0 - f * 2.0
    }

    /** Fills [out] with [count] samples as 16 bit PCM. */
    fun generate(out: ShortArray, offset: Int, count: Int, volume: Double = OUTPUT_SCALE) {
        for (i in 0 until count) {
            val v = (next() * volume * 32767.0).toInt().coerceIn(-32768, 32767)
            out[offset + i] = v.toShort()
        }
    }

    private companion object {
        const val OFF = 0
        const val ATTACK = 1
        const val DECAY = 2
        const val SUSTAIN = 3
        const val RELEASE = 4

        /** Documented time of the full envelope swing at chip rate 4: attack 2826.24 ms, decay/release 39280.64 ms; every +4 halves it. */
        const val ATTACK_MS = 2826.24
        const val DECAY_MS = 39280.64
        val LN_FULL = ln(512.0)

        const val AM_HZ = 3.7
        const val VIB_HZ = 6.1

        /** Phase modulation (in cycles) of the carrier by a full scale modulator. */
        const val PM_CYCLES = 2.0

        /** Mix gain: one full scale channel is about a quarter of the output range. */
        const val OUTPUT_SCALE = 0.22

        /** Linear gain for an attenuation in quarter units of 0.1875 dB. */
        val GAIN = DoubleArray(511 * 4 + 1) { 10.0.pow(-it / 4.0 * 0.1875 / 20.0) }

        val MULT = doubleArrayOf(0.5, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 10.0, 12.0, 12.0, 15.0, 15.0)
        val KSL_ROM = intArrayOf(0, 32, 40, 45, 48, 51, 53, 55, 56, 58, 59, 60, 61, 62, 63, 64)
        val KSL_SHIFT = intArrayOf(8, 1, 2, 0)

        /** Modulator slot of each channel; the carrier is three slots further. */
        val MOD_SLOT = intArrayOf(0, 1, 2, 6, 7, 8, 12, 13, 14)

        /** Rhythm bits of register 0xBD and the slots they key: 0x10 bass drum (slots 12, 15), 0x08 snare (16), 0x04 tom (14), 0x02 cymbal (17), 0x01 hi-hat (13). */
        val DRUM_SLOTS = listOf(0x10 to intArrayOf(12, 15), 0x08 to intArrayOf(16), 0x04 to intArrayOf(14), 0x02 to intArrayOf(17), 0x01 to intArrayOf(13))
    }
}
