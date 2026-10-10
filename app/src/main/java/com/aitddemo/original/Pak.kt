package com.aitddemo.original

import java.util.zip.Inflater

/**
 * Infogrames-PAK-Container (Alone-in-the-Dark-Engine): Offset-Tabelle am Dateianfang,
 * Eintrag = 16-Byte-Header (+ Name), Kompression 0 = roh, 1 = PKWARE-Implode (Alone), 4 = Raw-Deflate.
 */
class Pak(private val b: ByteArray) {
    private val offsets: IntArray

    init {
        val count = b.u32(4) / 4
        offsets = IntArray(count) { b.u32(it * 4) }
    }

    val size: Int get() = offsets.size

    fun get(index: Int): ByteArray? {
        if (index < 0 || index >= offsets.size) return null
        val o = offsets[index]
        if (o == 0) return null
        val compSize = b.u32(o + 4)
        val rawSize = b.u32(o + 8)
        val flag = b.u8(o + 12)
        val nameLen = b.u16(o + 14)
        val start = o + 16 + nameLen
        return when (flag) {
            0 -> b.copyOfRange(start, start + rawSize)
            1 -> Explode.decode(b, start, compSize, rawSize, b.u8(o + 13)) // älteres Format (Alone in the Dark)
            4 -> inflate(start, compSize, rawSize)
            else -> throw IllegalStateException("PAK: unbekannte Kompression $flag")
        }
    }

    private fun inflate(start: Int, compSize: Int, rawSize: Int): ByteArray {
        val input = ByteArray(compSize + 1) // Dummy-Byte für den nowrap-Inflater
        System.arraycopy(b, start, input, 0, compSize)
        val inf = Inflater(true)
        inf.setInput(input)
        val out = ByteArray(rawSize)
        var n = 0
        try {
            while (n < rawSize) {
                val r = inf.inflate(out, n, rawSize - n)
                if (r == 0 && (inf.finished() || inf.needsInput())) break
                n += r
            }
        } finally {
            inf.end()
        }
        return out
    }
}
