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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.delay

/** The page picture is held for 300 timer ticks (all.asm 0xE2A9: cmp ax, 0x12C) before the next page is turned in. */
private const val PAGE_HOLD_MS = 5000L

/**
 * The credits of the original, shown after the title picture: the book page picture with the text of ENGLISH.PAK entry 20 laid out like a book of the game (the reader
 * `Lire` with the box 48,2 to 260,197 and the colour 26), page after page, every page turned in like in the reader ([PageTurn]) with the page-turn sample. The first page
 * turns in over the title picture. [onDone] is called after the last page, [onTap] when the player taps (any key ends the credits in the original).
 */
@Composable
fun CreditsScreen(onDone: () -> Unit, onTap: () -> Unit = onDone) {
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
            runPageTurn(true) { edge = it }
            shown = p
            turning = -1
            delay(PAGE_HOLD_MS)
        }
        onDone()
    }

    GameCanvas(Modifier.fillMaxSize().background(Color.Black).clickable { onTap() }) {
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

        if (turning >= 0) PageTurn(edge, true, o.palette, { layer(shown) }, { layer(turning) }) else layer(shown)
    }
}
