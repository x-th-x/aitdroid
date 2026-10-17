package com.aitddemo.game

/**
 * The ending animation of the original (ENDSEQ.PAK): the evil tree burns. Entry 1 is the first picture: 2 bytes "VP", the 768 byte palette (8 bit per colour), then 320 x 200
 * pixels. Every further entry changes the picture of the one before (a delta frame), as a stream of small commands:
 *  - 0: nothing
 *  - 1 v: one pixel of colour v
 *  - 2 v: two pixels of colour v
 *  - 3 n v: n pixels of colour v
 *  - 4 lo hi: skip lo + 256 * hi pixels (they stay as they were)
 *  - any other byte: one pixel of that colour
 * Colour 0 means "no change" in a delta frame (the flames are drawn over the picture). The pixels run left to right, row after row; what lies beyond the last pixel is dropped.
 */
object EndSequence {
    const val WIDTH = 320
    const val HEIGHT = 200
    private const val PIXELS = WIDTH * HEIGHT

    /** The frames, each 320 x 200 ARGB, the first one is the picture itself; null when the first entry is not a picture of this kind. */
    fun decode(entries: List<ByteArray>): List<IntArray>? {
        if (entries.isEmpty()) return null
        val first = entries[0]
        if (first.size < 2 + 768 + PIXELS) return null
        val palette = IntArray(256) { i ->
            val r = first[2 + i * 3].toInt() and 0xFF
            val g = first[3 + i * 3].toInt() and 0xFF
            val b = first[4 + i * 3].toInt() and 0xFF
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val pix = ByteArray(PIXELS) { first[2 + 768 + it] }
        val frames = ArrayList<IntArray>(entries.size)
        frames.add(IntArray(PIXELS) { palette[pix[it].toInt() and 0xFF] })
        for (e in 1 until entries.size) {
            applyDelta(entries[e], pix)
            frames.add(IntArray(PIXELS) { palette[pix[it].toInt() and 0xFF] })
        }
        return frames
    }

    /** One delta frame laid over the picture [pix]. */
    internal fun applyDelta(d: ByteArray, pix: ByteArray) {
        var i = 0
        var p = 0
        fun put(count: Int, v: Int) {
            if (v != 0) for (k in 0 until count) if (p + k < PIXELS) pix[p + k] = v.toByte()
            p += count
        }
        while (i < d.size && p < PIXELS) {
            val op = d[i++].toInt() and 0xFF
            when (op) {
                0 -> {}
                1 -> { if (i < d.size) put(1, d[i].toInt() and 0xFF); i++ }
                2 -> { if (i < d.size) put(2, d[i].toInt() and 0xFF); i++ }
                3 -> { if (i + 1 < d.size) put(d[i].toInt() and 0xFF, d[i + 1].toInt() and 0xFF); i += 2 }
                4 -> { if (i + 1 < d.size) p += (d[i].toInt() and 0xFF) or ((d[i + 1].toInt() and 0xFF) shl 8); i += 2 }
                else -> put(1, op)
            }
        }
    }
}
