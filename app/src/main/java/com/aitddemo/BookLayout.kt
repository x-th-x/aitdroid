package com.aitddemo

/** One word of a page: its x position on the 320x200 picture and its text. */
class BookWord(val x: Int, val text: String)

/** One line of a page at height [y] (the line height is 16). */
class BookLine(val y: Int, val words: List<BookWord>)

/**
 * Lays a book text (ENGLISH.PAK) out in pages as the original's reader does (`Lire`): words are fitted into the box (startX..endX, top..bottom), lines are
 * stretched to the full width unless they end a paragraph, `#P` starts a new page, `#T` a tab, `#C` centres a line, a blank line ends a paragraph, 0x1A ends the text.
 */
fun layoutBook(d: ByteArray, startX: Int, top: Int, endX: Int, bottom: Int, wordWidth: (String) -> Int): List<List<BookLine>> {
    val maxWidth = endX - startX + 4
    val pages = ArrayList<List<BookLine>>()
    var p = 0
    fun byte(i: Int) = if (i < d.size) d[i].toInt() and 0xFF else 26
    var lastPage = false
    var aborted = false
    while (!lastPage && !aborted && pages.size < 200) {
        val lines = ArrayList<BookLine>()
        var y = top
        pageLoop@ while (y <= bottom - 16) {
            var lineType = 1
            val words = ArrayList<Pair<String, Int>>()
            var used = 0
            wordLoop@ while (true) {
                while (byte(p) == '#'.code) {
                    p++
                    when (byte(p++).toChar()) {
                        'P' -> if (y > top) break@pageLoop
                        'T' -> {
                            val w = wordWidth("    ") + 3
                            words.add("    " to w)
                            used += w
                        }
                        'C' -> lineType = (lineType and 1.inv()) or 8
                        'G' -> while (byte(p) in '0'.code..'9'.code) p++
                    }
                }
                val start = p
                var c: Int
                do {
                    c = byte(p++)
                } while (c > ' '.code)
                val word = String(d, start, minOf(p - 1, d.size) - start, Charsets.ISO_8859_1)
                val width = wordWidth(word) + 3
                if (width > maxWidth) {
                    aborted = true
                    break@wordLoop
                }
                if (used + width > maxWidth) {
                    p = start
                    break@wordLoop
                }
                words.add(word to width)
                used += width
                if (c == 26) {
                    lineType = (lineType and 1.inv()) or 4
                    lastPage = true
                    break@wordLoop
                }
                if ((c == 13 || c == 0) && byte(p) < ' '.code) {
                    p++
                    if (byte(p) == 0x0D) {
                        p += 2
                        lineType = (lineType and 1.inv()) or 2
                        break@wordLoop
                    }
                    if (byte(p) == '#'.code) {
                        lineType = lineType and 1.inv()
                        break@wordLoop
                    }
                }
            }
            if (aborted) break
            val gap = if (lineType and 1 != 0 && words.size > 1) (maxWidth - used) / (words.size - 1) else 0
            var x = if (lineType and 8 != 0) startX + (maxWidth - used) / 2 else startX
            val placed = ArrayList<BookWord>()
            for ((text, width) in words) {
                placed.add(BookWord(x, text))
                x += width + gap
            }
            lines.add(BookLine(y, placed))
            if (lineType and 2 != 0) y += 8
            y += 16
            if (lastPage) break
        }
        if (lines.isNotEmpty()) pages.add(lines)
        if (lines.isEmpty() && !lastPage) break
    }
    return pages
}
