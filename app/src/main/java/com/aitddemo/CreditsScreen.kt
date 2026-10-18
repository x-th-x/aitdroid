package com.aitddemo

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.delay

/** The page picture is held for 300 timer ticks (all.asm 0xE2A9: cmp ax, 0x12C) before the next page is turned in. */
private const val PAGE_HOLD_MS = 5000L

/** One step of the page turn: the edge of the turning page moves 10 pixels (0xDB06: sub [bp-8], 0xA). */
private const val TURN_STEP_MS = 16L

/** The end of the shadow that runs ahead of the new page: halfway between the edge of the old page [edge] and x = 280 (0xDB06). */
private fun shadowEnd(edge: Int): Int = 280 - (280 - edge) / 2

/**
 * The credits of the original, shown after the title picture: the book page picture with the text of ENGLISH.PAK entry 20 laid out like a book of the game (the reader
 * `Lire` with the box 48,2 to 260,197 and the colour 26), page after page. Every page is turned in the way of the original (0xDACC): an edge runs from x = 260 to the left
 * in steps of 10 pixels; left of it the old page stays, a dark line marks the edge, a shadow of six bands (colours 19 to 24, each band half as wide as the rest) follows it
 * and the new page appears right of the shadow. Each turn plays the page-turn sample. A tap ends the credits (any key does in the original).
 */
@Composable
fun CreditsScreen(onDone: () -> Unit) {
    val o = StartAssets.original
    val text = o?.credits
    if (o == null || text == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    val pages = remember(text) { layoutBook(text, 48, 2, 260, 197) { w -> bitmapTextWidth(w) } }
    val titleBitmap = remember(o) { StartAssets.picture(o.title) }
    val pageBitmap = remember(o) { StartAssets.picture(o.bookPage) }
    val ink = Color(o.palette[26])
    var shown by remember { mutableIntStateOf(-1) } // the page that is on the screen (-1: the title picture)
    var turning by remember { mutableIntStateOf(-1) } // the page that is being turned in
    var edge by remember { mutableIntStateOf(260) }

    LaunchedEffect(pages) {
        for (p in pages.indices) {
            StartAssets.playPageTurn()
            turning = p
            var e = 260
            var m: Int
            do {
                edge = e
                delay(TURN_STEP_MS)
                m = shadowEnd(e)
                e -= 10
            } while (m >= -1)
            shown = p
            turning = -1
            delay(PAGE_HOLD_MS)
        }
        onDone()
    }

    GameCanvas(Modifier.fillMaxSize().background(Color.Black).clickable { onDone() }) {
        @Composable
        fun layer(index: Int) {
            Box(Modifier.size(gx(320), gy(200))) {
                Image(
                    bitmap = if (index < 0) titleBitmap else pageBitmap,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
                if (index >= 0) pages.getOrNull(index)?.forEach { line ->
                    line.words.forEach { w -> Box(Modifier.offset(x = gx(w.x), y = gy(line.y))) { BitmapText(text = w.text, scale = unit.value, color = ink) } }
                }
            }
        }

        @Composable
        fun band(x1: Int, x2: Int, color: Int) {
            val a = x1.coerceIn(0, 320)
            val b = (x2 + 1).coerceIn(0, 320)
            if (b > a) Box(Modifier.offset(x = gx(a)).size(gx(b - a), gy(200)).background(Color(color)))
        }

        layer(shown)
        if (turning >= 0) {
            val e = edge
            val m = shadowEnd(e)
            band(e, e, o.palette[16]) // the edge
            // six bands between e + 1 and m: each ends halfway between its start and m, the last one at m (0xD9D8)
            var a = e + 1
            var first = true
            for (c in 19..23) {
                val mid = (m - a) / 2 + a
                band(if (first) a + 1 else a, mid, o.palette[c])
                first = false
                a = mid
            }
            band(a, m, o.palette[24])
            // the new page right of the shadow
            val x0 = (m + 1).coerceIn(0, 320)
            if (x0 < 320) Box(Modifier.offset(x = gx(x0)).size(gx(320 - x0), gy(200)).clipToBounds()) {
                Box(Modifier.offset(x = -gx(x0))) { layer(turning) }
            }
        }
    }
}
