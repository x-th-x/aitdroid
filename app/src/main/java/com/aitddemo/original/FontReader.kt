package com.aitddemo.original

/**
 * The 1-bit bitmap font of the game (an entry of ITD_RESS.PAK), decoded as the engine's SetFont/PrintFont do.
 * Glyphs are laid out in 16x16 cells of one atlas (code 32 + n at column n % 16, row n / 16); [alpha] holds 0 or 255 per pixel.
 */
class OriginalFontData(val height: Int, val widths: IntArray, val atlasWidth: Int, val atlasHeight: Int, val alpha: ByteArray) {
    companion object {
        const val CELL = 16
        const val FIRST = 32
        const val COLUMNS = 16
        const val LAST = 255

        fun parse(d: ByteArray): OriginalFontData {
            val alignment = d.s16(0)
            val height = d.u8(2)
            var stride = d.u8(3)
            if (stride == 0) stride = d.s16(4)
            val swapped = d.u16(6)
            val tableOffset = (((swapped and 0xFF) shl 8) or (swapped shr 8)) - (alignment and 0xFF) * 2
            val bitmapBase = 8
            val rows = (LAST - FIRST) / COLUMNS + 1
            val atlasWidth = COLUMNS * CELL
            val atlasHeight = rows * CELL
            val alpha = ByteArray(atlasWidth * atlasHeight)
            val widths = IntArray(LAST + 1)
            for (code in FIRST..LAST) {
                if (tableOffset + code * 2 + 1 >= d.size) continue
                val raw = d.u16(tableOffset + code * 2)
                val entry = ((raw and 0xFF) shl 8) or (raw shr 8)
                val w = (entry shr 12) and 0xF
                widths[code] = w
                if (w == 0) continue
                val bit = entry and 0xFFF
                val cx = ((code - FIRST) % COLUMNS) * CELL
                val cy = ((code - FIRST) / COLUMNS) * CELL
                for (row in 0 until minOf(height, CELL)) {
                    for (x in 0 until minOf(w, CELL)) {
                        val byte = bitmapBase + (bit shr 3) + row * stride + ((bit and 7) + x) / 8
                        if (byte < d.size && d.u8(byte) and (0x80 shr ((bit + x) and 7)) != 0) alpha[(cy + row) * atlasWidth + cx + x] = -1
                    }
                }
            }
            return OriginalFontData(height, widths, atlasWidth, atlasHeight, alpha)
        }
    }
}
