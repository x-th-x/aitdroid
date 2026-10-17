package com.aitddemo.original

/**
 * The tile sheet of the windows (entry 5 of ITD_RESS.PAK): 16 words with the offsets of the tiles, each tile 4 words (position in the picture, not used here; width; height)
 * and then width x height palette indices (0 = transparent when the tile is drawn that way). The frames of the windows, their scroll arrows and the plate "In the Dark" are tiles.
 */
class TileSheet(d: ByteArray) {
    class Tile(val width: Int, val height: Int, val pixels: ByteArray)

    val tiles: List<Tile> = (0 until COUNT).map { i ->
        val p = d.u16(i * 2)
        val w = d.u16(p + 4)
        val h = d.u16(p + 6)
        Tile(w, h, d.copyOfRange(p + 8, p + 8 + w * h))
    }

    companion object {
        const val COUNT = 15
        const val CORNER_TL = 0
        const val CORNER_TR = 1
        const val CORNER_BL = 2
        const val CORNER_BR = 3
        const val EDGE_TOP = 4
        const val EDGE_BOTTOM = 5
        const val EDGE_LEFT = 6
        const val EDGE_RIGHT = 7
        const val PLATE = 8
        const val ARROW_DOWN = 9
        const val ARROW_UP = 10
    }
}
