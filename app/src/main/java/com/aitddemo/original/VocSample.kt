package com.aitddemo.original

/** A sound sample from LISTSAMP.PAK: a Creative Voice File with 8 bit unsigned mono data. */
class VocSample(val rate: Int, val pcm: ByteArray) {
    companion object {
        private const val MAGIC = "Creative Voice File"

        /** Reads the sample rate and the sound data of a .VOC file; null when the file is not a plain 8 bit voice file. */
        fun parse(d: ByteArray): VocSample? {
            if (d.size < 26 || String(d, 0, MAGIC.length, Charsets.ISO_8859_1) != MAGIC) return null
            var p = d.u16(20)
            var rate = 0
            val out = java.io.ByteArrayOutputStream()
            while (p < d.size) {
                val type = d.u8(p)
                if (type == 0) break
                val size = d.u8(p + 1) or (d.u8(p + 2) shl 8) or (d.u8(p + 3) shl 16)
                val body = p + 4
                when (type) {
                    1 -> { // sound data: frequency divisor, codec (0 = 8 bit), data
                        if (d.u8(body + 1) != 0) return null
                        rate = 1_000_000 / (256 - d.u8(body))
                        out.write(d, body + 2, size - 2)
                    }
                    2 -> out.write(d, body, size) // continuation of the sound data
                }
                p = body + size
            }
            return if (rate > 0 && out.size() > 0) VocSample(rate, out.toByteArray()) else null
        }
    }
}
