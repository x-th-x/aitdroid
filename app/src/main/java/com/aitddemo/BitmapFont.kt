package com.aitddemo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// Text helpers with the layout units of a 320x200 screen: one "unit" is 1 * scale dp.
// A monospace system font is used, so widths are simple to compute (UNIT_ADVANCE units per character).
private const val UNIT_ADVANCE = 7
private const val LINE_HEIGHT = 16
private const val FONT_SIZE_UNITS = 11.5f

/** Width of [text] in layout units (before [scale] is applied). */
fun bitmapTextWidth(text: String): Int = text.length * UNIT_ADVANCE

/** Single-line text; [scale] maps to the font size (name kept from the original bitmap-font version). */
@Composable
fun BitmapText(text: String, scale: Float = 3f, color: Color = Color.White, modifier: Modifier = Modifier) {
    val widthDp = (bitmapTextWidth(text) * scale).roundToInt().coerceAtLeast(1)
    val heightDp = (LINE_HEIGHT * scale).roundToInt()
    Box(modifier = modifier.size(widthDp.dp, heightDp.dp)) {
        BasicText(
            text = text,
            maxLines = 1,
            softWrap = false,
            style = TextStyle(
                color = color,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = (FONT_SIZE_UNITS * scale).sp
            )
        )
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
    val lines = remember(text, maxWidthUnits) { wrapText(text, maxWidthUnits) }
    Column(modifier = modifier) {
        lines.forEach { line ->
            if (line.isEmpty()) {
                Spacer(Modifier.size((LINE_HEIGHT * scale * 0.6f).roundToInt().dp))
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
            val spaceWidth = if (current.isEmpty()) 0 else UNIT_ADVANCE
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
