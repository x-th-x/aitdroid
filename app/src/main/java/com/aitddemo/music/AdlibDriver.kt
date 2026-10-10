package com.aitddemo.music

/**
 * The AdLib music driver of Alone in the Dark: plays the "ADLM" tracks of LISTMUS.PAK by writing the chip registers of an [Opl2], one
 * [tick] at a time. This is a port of the original driver as reconstructed in the FITD engine (music.cpp): 11 voices read a byte
 * stream of commands (stop, delay, note, instrument, volume, pitch); the driver turns that state into register writes.
 *
 * Track layout: 0x3C flags (bit 7/6 = deep tremolo/vibrato), 0x3D 0 = rhythm mode (6 melodic voices + 5 drums), 8 + 4 * n offsets of
 * the voice streams, 0x34 offset of the instrument table (13 bytes each).
 */
class AdlibDriver(private val opl: Opl2) {
    /** One voice of the driver (the original's channelTable2 entry). */
    private class Voice(val index: Int) {
        var flags = 0x0040 // 0x40 stopped, 0x20 never loops, 0x02 start from the beginning, 0x8000 note active
        var data = -1 // offset of the voice's command stream, -1 = none
        var cmd = 0 // offset of the next command
        var delay = 0
        var delayReload = 0
        var instrument = 0
        var transpose = 0
        var note = 0
        var pitch = 0
        var loops = 0
        var volumeSet = 0x7F
        var volume = 0x7F
        var attenuation = 0
    }

    /** What the driver last wrote to the chip for a voice (the original's channelDataTable entry). */
    private class Shadow {
        var note = 0xFFFF
        var flags = 0x40
        var instrument = 0xFF
        var volume = 0xFF
        var pitch = 0x9C
        var active = 0xFFFF
    }

    private val voices = Array(VOICES) { Voice(it) }
    private val shadow = Array(VOICES) { Shadow() }
    private var music = ByteArray(0)
    private var instrumentBase = 0
    private var rhythmFlag = 1 // byte 0x3D: 0 = rhythm mode
    private var regBd = 0xC0
    private var channelTable = MELODIC

    private fun send(reg: Int, value: Int) = opl.write(reg, value)

    /** Loads [track] (an ADLM file) and starts all voices. */
    fun start(track: ByteArray) {
        music = track
        for (v in voices) v.flags = 0x0060
        init()
        // musicLoad
        var bd = music[0x3C].toInt() and 0xC0
        rhythmFlag = music[0x3D].toInt() and 0xFF
        channelTable = MELODIC
        if (rhythmFlag == 0) {
            bd = bd or 0x20
            channelTable = RHYTHM
        }
        regBd = bd
        for (i in 0 until VOICES) {
            val off = u32(8 + i * 4)
            voices[i].data = if (off != 0) off else -1
            voices[i].flags = voices[i].flags or 0x40
        }
        instrumentBase = u16(0x34)
        musicStart()
        // fade(start all)
        for (v in voices) {
            if (v.data < 0) continue
            v.volume = 0x7F
            v.volumeSet = 0x7F
            v.attenuation = 0
            createDefault(v.index)
            v.flags = 2
        }
    }

    /** True when every voice has reached the end of its stream. */
    val finished: Boolean get() = voices.all { it.data < 0 || it.flags and 0x40 != 0 }

    /** One driver step (the original runs it about 59 times per second). */
    fun tick() {
        for (i in 0 until VOICES) {
            val v = voices[i]
            execute(v)
            apply(v, shadow[i])
        }
    }

    // the last command of a stream (end, one byte) is read as a 16 bit word like the original does: bytes past the data read as 0
    private fun b(p: Int) = if (p in music.indices) music[p].toInt() and 0xFF else 0
    private fun u16(p: Int) = b(p) or (b(p + 1) shl 8)
    private fun u32(p: Int) = u16(p) or (u16(p + 2) shl 16)

    private fun createDefault(i: Int) {
        val s = shadow[i]
        s.volume = 0xFF
        s.instrument = 0xFF
        s.flags = 0x40
        s.note = 0xFFFF
        s.pitch = 0x9C
        s.active = 0xFFFF
    }

    private fun resetFrequency(channel: Int) {
        send(0xA0 + channel, 0)
        send(0xB0 + channel, 0)
    }

    private fun setupFrequency(channel: Int, cl: Int, dxIn: Int, bp: Int) {
        var dx = dxIn
        if (bp and 0x8000 == 0) send(0xB0 + channel, 0)
        if (cl and 0x80 != 0) dx = 0x40
        val frequency = FREQUENCIES.getOrElse((cl and 0xF) * 25 + (bp and 0xFF)) { FREQUENCIES.last() }
        send(0xA0 + channel, frequency and 0xFF)
        val block = (cl and 0x70) shr 2
        var high = ((frequency shr 8) and 3) or block
        if (dx and 0x40 == 0) high = high or 0x20 // key on
        send(0xB0 + channel, high)
    }

    private fun musicStart() {
        send(1, 0x20)
        send(8, 0)
        send(0xBD, regBd)
        for (i in 0 until 18) {
            send(0x60 + MELODIC[i], 0xFF)
            send(0x80 + MELODIC[i], 0xFF)
        }
        for (i in 0 until 9) resetFrequency(i)
        for (i in 0 until VOICES) createDefault(i)
        if (rhythmFlag == 0) {
            resetFrequency(6); setupFrequency(6, 0, 0x40, 0)
            resetFrequency(7); setupFrequency(7, 7, 0x40, 0)
            resetFrequency(8); setupFrequency(8, 0, 0x40, 0)
        }
    }

    private fun init() {
        for (i in 0 until VOICES) createDefault(i)
        regBd = 0xC0
        rhythmFlag = 1
        musicStart()
    }

    // ---- voice commands

    private fun execute(v: Voice) {
        if (v.flags and 0x40 != 0) return
        if (v.flags and 0x02 != 0) { // start the stream
            v.cmd = v.data
            v.flags = v.flags and 0x02.inv()
            v.loops = 0
        } else {
            v.delay--
            if (v.delay <= 0) v.delay = v.delayReload else return
        }
        do {
            val opcode = u16(v.cmd)
            v.cmd += 2
            val param = opcode shr 8
            when (opcode and 0x7F) {
                0 -> { // end of stream
                    v.flags = v.flags or 2
                    if (v.flags and 0x20 == 0) v.flags = v.flags or 0x40
                }
                1 -> { // delay between events: 16 bit value (parameter byte + next byte)
                    val ax = b(v.cmd - 1) or (b(v.cmd) shl 8)
                    v.delay = ax + v.transpose
                    v.delayReload = v.delay
                    v.cmd++
                }
                2 -> { v.loops++; v.note = param } // note
                3 -> v.instrument = param
                4 -> v.attenuation = param
                5 -> v.pitch = param
                else -> Unit
            }
        } while (opcode and 0x80 == 0)
    }

    private fun applyDirect(index: Int, p1In: Int, p2: Int, p3: Int) {
        var p1 = p1In
        if (rhythmFlag != 0 || index < 6) {
            setupFrequency(index, p1, p2, p3)
            return
        }
        if (index == 6) {
            setupFrequency(index, p1, 0x40, p3)
        } else if (index == 8 && p1 and 0x80 == 0) {
            val saved = p1
            setupFrequency(8, p1, 0x40, p3)
            val al = p1 and 0x70
            var q = (p1 and 0xF) + 7
            var block = al
            if (q >= 0xC) {
                q -= 0xC
                if (al != 0x70) block += 0x10
            }
            setupFrequency(7, q or block, 0x40, p3)
            p1 = saved
        }
        val drum = DRUM_BITS[index - 6]
        var ah = drum.inv() and regBd
        send(0xBD, ah)
        if (p2 and 0x40 == 0 && p1 and 0x80 == 0) {
            ah = ah or drum
            send(0xBD, ah)
        }
        regBd = ah
    }

    private fun configure(slot: Int, data: Int) {
        if (CHANNEL_OF_SLOT[slot] != 0xFF) send(0xC0 + CHANNEL_OF_SLOT[slot], b(data + 2))
        send(0x60 + slot, b(data + 4)) // attack, decay
        send(0x80 + slot, b(data + 5)) // sustain, release
        send(0x20 + slot, b(data + 1)) // tremolo, vibrato, sustain, key scale, multiplier
        send(0xE0 + slot, b(data + 3)) // waveform
    }

    private fun setLevel(slot: Int, data: Int, bp: Int) {
        if (slot == 0xFF) return
        val d = data + 1
        var level = b(d) and 0x3F
        level = 0x3F - ((((level * bp) * 2) + 0x7F) / 0xFE)
        send(0x40 + slot, (b(d) and 0xC0) or (level and 0x3F))
    }

    private fun apply(v: Voice, s: Shadow) {
        if (v.flags and 0x40 != s.flags) {
            s.flags = v.flags and 0x40
            if (v.flags and 0x40 != 0) {
                applyDirect(v.index, v.note or 0x80, 0x40, v.pitch)
                createDefault(v.index)
                return
            }
        }
        if (v.flags and 0x40 != 0) return
        if (s.active and 1 != 0 || s.active != (v.flags and 0x8000)) {
            s.active = v.flags and 0x8000
            s.volume = 0xFF
            s.instrument = 0xFF
            s.note = 0xFFFF
            s.pitch = 0x9C
        }
        val op1 = channelTable[v.index * 2]
        val op2 = channelTable[v.index * 2 + 1]
        if (op1 == 0xFF && op2 == 0xFF) return
        val inst = instrumentBase + 0xD * v.instrument
        if (v.instrument != s.instrument) {
            s.instrument = v.instrument
            configure(op1, inst + 1)
            if (op2 != 0xFF) configure(op2, inst + 7)
            s.volume = 0xFF
        }
        var al = v.volume - v.attenuation
        if (al < 0) al = 0
        if (s.volume != al) {
            s.volume = al
            var dx = v.volume
            if (op2 == 0xFF) dx = s.volume
            setLevel(op1, inst, dx)
            if (op2 != 0xFF) setLevel(op2, inst + 6, s.volume)
        }
        var bp = v.pitch
        if (v.pitch != s.pitch) {
            s.pitch = v.pitch
            if (v.note == s.note) bp = bp or 0x8000
        } else if (v.note == s.note) {
            return
        }
        v.note = v.note or 0x8000
        s.note = v.note
        applyDirect(v.index, s.note and 0xFF, v.flags, bp)
    }

    private companion object {
        const val VOICES = 11

        /** Operator slots of the nine melodic channels (modulator, carrier) in the order of the original's table. */
        val MELODIC = intArrayOf(0x00, 0x03, 0x01, 0x04, 0x02, 0x05, 0x08, 0x0B, 0x09, 0x0C, 0x0A, 0x0D, 0x10, 0x13, 0x11, 0x14, 0x12, 0x15, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF)

        /** The same for rhythm mode: voices 6..10 are bass drum, snare, tom, cymbal, hi-hat. */
        val RHYTHM = intArrayOf(0x00, 0x03, 0x01, 0x04, 0x02, 0x05, 0x08, 0x0B, 0x09, 0x0C, 0x0A, 0x0D, 0x10, 0x13, 0x14, 0xFF, 0x12, 0xFF, 0x15, 0xFF, 0x11, 0xFF)

        /** Channel (register 0xC0 + n) of a modulator slot. */
        val CHANNEL_OF_SLOT = intArrayOf(0, 1, 2, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 3, 4, 5, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 6, 7, 8, 0xFF, 0xFF, 0xFF)

        val DRUM_BITS = intArrayOf(0x10, 8, 4, 2, 1)

        /** 12 notes x 25 fine steps of the chip's frequency number (register A0/B0) for one octave. */
        val FREQUENCIES = intArrayOf(
        343, 344, 345, 346, 346, 347, 348, 349, 350, 351, 351, 352, 353, 354, 355,
        356, 356, 357, 358, 359, 360, 360, 361, 362, 363, 364, 365, 365, 366, 367,
        368, 369, 370, 371, 372, 372, 373, 374, 375, 376, 377, 378, 379, 379, 380,
        381, 382, 383, 384, 385, 385, 387, 387, 388, 389, 390, 391, 392, 393, 394,
        395, 396, 397, 398, 398, 399, 400, 401, 402, 403, 404, 405, 406, 407, 408,
        408, 410, 410, 411, 412, 413, 415, 415, 416, 417, 418, 419, 420, 421, 422,
        423, 424, 425, 426, 427, 428, 429, 430, 431, 432, 433, 434, 435, 436, 437,
        438, 439, 440, 441, 442, 443, 444, 445, 447, 448, 449, 450, 451, 452, 453,
        454, 455, 456, 457, 458, 459, 460, 461, 462, 463, 464, 466, 467, 468, 469,
        470, 471, 472, 473, 474, 475, 477, 478, 479, 480, 481, 482, 483, 484, 485,
        486, 488, 489, 490, 491, 492, 493, 495, 496, 497, 498, 499, 500, 502, 503,
        504, 505, 506, 507, 509, 510, 511, 512, 513, 514, 515, 517, 518, 519, 520,
        522, 523, 524, 525, 527, 528, 529, 530, 532, 533, 534, 535, 537, 538, 539,
        540, 541, 543, 544, 545, 546, 548, 549, 550, 551, 553, 554, 556, 557, 558,
        559, 561, 562, 564, 565, 566, 567, 569, 570, 571, 572, 574, 575, 577, 578,
        579, 581, 582, 583, 584, 586, 587, 589, 590, 592, 593, 594, 596, 597, 599,
        600, 601, 603, 604, 606, 607, 608, 610, 611, 612, 614, 615, 617, 618, 619,
        621, 622, 624, 625, 627, 628, 630, 631, 633, 634, 636, 637, 639, 640, 642,
        643, 645, 646, 648, 649, 650, 652, 653, 655, 657, 658, 660, 661, 663, 665,
        666, 668, 669, 671, 672, 674, 675, 677, 679, 680, 682, 683, 685, 687, 688
        )
    }
}
