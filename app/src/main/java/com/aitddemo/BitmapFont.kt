package com.aitddemo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aitddemo.original.OriginalFontData
import kotlin.math.roundToInt

// Text helpers with the layout units of a 320x200 screen: one "unit" is 1 * scale dp.
// The glyphs come from the game's own bitmap font (ITD_RESS.PAK, see StartAssets); until the files are read a monospace system font stands in.
private const val INTER_LETTER_SPACE = 1
private const val INTER_WORD_SPACE = 2
private const val GLYPH_CELL = OriginalFontData.CELL
private const val FALLBACK_ADVANCE = 7
private const val FALLBACK_FONT_SIZE_UNITS = 11.5f

internal fun glyphWidth(ch: Char): Int = StartAssets.font?.widths?.getOrNull(ch.code) ?: 0

internal fun glyphAdvance(ch: Char): Int {
    if (StartAssets.font == null) return FALLBACK_ADVANCE
    val w = glyphWidth(ch)
    return (if (w == 0) INTER_WORD_SPACE else w) + INTER_LETTER_SPACE
}

/** Width of [text] in font units (before [scale] is applied). */
fun bitmapTextWidth(text: String): Int = text.sumOf { glyphAdvance(it) }

/** Single-line text in the game's bitmap font; [scale] is dp per font pixel. */
@Composable
fun BitmapText(text: String, scale: Float = 3f, color: Color = Color.White, modifier: Modifier = Modifier) {
    val atlas = StartAssets.atlas()
    val widthDp = (bitmapTextWidth(text) * scale).roundToInt().coerceAtLeast(1)
    val heightDp = (GLYPH_CELL * scale).roundToInt()
    if (atlas == null) {
        Box(modifier = modifier.size(widthDp.dp, heightDp.dp)) {
            BasicText(
                text = text,
                maxLines = 1,
                softWrap = false,
                style = TextStyle(color = color, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = (FALLBACK_FONT_SIZE_UNITS * scale).sp),
            )
        }
        return
    }
    val unitWidth = bitmapTextWidth(text)
    val tint = ColorFilter.tint(color)
    Canvas(modifier = modifier.size(widthDp.dp, heightDp.dp)) {
        val pxScale = if (unitWidth > 0) size.width / (unitWidth * scale) else 1f
        var x = 0f
        for (ch in text) {
            val w = glyphWidth(ch)
            if (w > 0 && ch.code >= OriginalFontData.FIRST) {
                val n = ch.code - OriginalFontData.FIRST
                drawImage(
                    image = atlas,
                    srcOffset = IntOffset((n % OriginalFontData.COLUMNS) * GLYPH_CELL, (n / OriginalFontData.COLUMNS) * GLYPH_CELL),
                    srcSize = IntSize(w, GLYPH_CELL),
                    dstOffset = IntOffset((x * scale * pxScale).roundToInt(), 0),
                    dstSize = IntSize((w * scale * pxScale).roundToInt(), (GLYPH_CELL * scale * pxScale).roundToInt()),
                    colorFilter = tint,
                )
            }
            x += glyphAdvance(ch)
        }
    }
}

/** Greedy word-wrap on [maxWidthUnits], then renders each line with [BitmapText]. */
@Composable
fun BitmapParagraphs(
    text: String,
    scale: Float = 2f,
    color: Color = Color.White,
    maxWidthUnits: Int,
    lineSpacing: Float = 4f,
    modifier: Modifier = Modifier
) {
    val lines = remember(text, maxWidthUnits, StartAssets.original) { wrapText(text, maxWidthUnits) }
    Column(modifier = modifier) {
        lines.forEach { line ->
            if (line.isEmpty()) {
                Spacer(Modifier.size((GLYPH_CELL * scale * 0.6f).roundToInt().dp))
            } else {
                BitmapText(text = line, scale = scale, color = color)
                Spacer(Modifier.size(lineSpacing.roundToInt().dp))
            }
        }
    }
}

private fun wrapText(text: String, maxWidthUnits: Int): List<String> {
    val lines = mutableListOf<String>()
    for (paragraph in text.split("\n\n")) {
        var current = StringBuilder()
        var currentWidth = 0
        for (word in paragraph.split(" ")) {
            val wordWidth = bitmapTextWidth(word)
            val spaceWidth = if (current.isEmpty()) 0 else glyphAdvance(' ')
            if (current.isNotEmpty() && currentWidth + spaceWidth + wordWidth > maxWidthUnits) {
                lines.add(current.toString())
                current = StringBuilder()
                currentWidth = 0
            }
            if (current.isNotEmpty()) { current.append(' '); currentWidth += spaceWidth }
            current.append(word)
            currentWidth += wordWidth
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        lines.add("")
    }
    return lines
}
