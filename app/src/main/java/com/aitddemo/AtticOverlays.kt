package com.aitddemo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.aitddemo.game.AtticGame

private val GOLD = Color(0xFF8A6D3B)
private val TEXT = Color(0xFFF2E4B8)
private val DIM = Color(0xFF9C8B6E)

/** Text centred on the 320x200 grid at height [y]. */
@Composable
private fun GameCanvasScope.CenteredText(text: String, y: Int, color: Color = TEXT, centerX: Int = 160) {
    Box(Modifier.offset(x = unit * (centerX - bitmapTextWidth(text) / 2), y = gy(y))) {
        BitmapText(text = text, scale = unit.value, color = color)
    }
}

/** A choice on the 320x200 grid; selected = bright with a bar behind it. */
@Composable
private fun GameCanvasScope.Choice(text: String, centerX: Int, y: Int, selected: Boolean, onClick: () -> Unit) {
    val width = bitmapTextWidth(text)
    Box(
        Modifier
            .offset(x = unit * (centerX - width / 2 - 4), y = gy(y - 1))
            .size(unit * (width + 8), gy(17))
            .then(if (selected) Modifier.background(Color(0xFF4A1420).copy(alpha = 0.7f)) else Modifier)
            .clickable(onClick = onClick),
    )
    CenteredText(text, y, if (selected) TEXT else DIM, centerX)
}

/** Press-and-hold button; [onHeld] gets true while the finger is down. */
@Composable
fun HoldButton(icon: GameIcon, modifier: Modifier = Modifier, onHeld: (Boolean) -> Unit) {
    Box(
        modifier = modifier
            .size(52.dp)
            .background(Color.Black.copy(alpha = 0.5f))
            .border(2.dp, GOLD)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    onHeld(true)
                    waitForUpOrCancellation()
                    onHeld(false)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        IconGlyph(icon)
    }
}

/** A message of the scripts (MESSAGE) and when it appeared. */
class MessageSlot(val text: String, val startNanos: Long)

/**
 * The messages of the original: bitmap font, centred at the bottom of the 320x200 picture (first line at y 184), further messages above it in steps of 16,
 * no box. A message stays about 3 seconds; in its second half the colour runs down the palette ramp (entries 16 to 30) until it is gone.
 */
@Composable
fun MessageLines(slots: Array<MessageSlot?>, nowNanos: Long, palette: IntArray, modifier: Modifier = Modifier) {
    GameCanvas(modifier.fillMaxSize()) {
        slots.forEachIndexed { i, m ->
            if (m == null) return@forEachIndexed
            val time = ((nowNanos - m.startNanos) / 1_000_000_000.0 / MESSAGE_SECONDS * 55).toInt()
            if (time > 55) return@forEachIndexed
            val color = Color(palette.getOrElse(if (time < 26) 16 else 16 + (time - 26) / 2) { 0xFFFFFFFF.toInt() })
            Box(Modifier.offset(x = unit * (160 - bitmapTextWidth(m.text) / 2), y = gy(184 - 16 * i))) {
                BitmapText(text = m.text, scale = unit.value, color = color)
            }
        }
    }
}

private const val MESSAGE_SECONDS = 2.8

/** The reader of the original for letters (type 0), books (1) and notebooks (2): the page picture with the text laid out in pages; "<" ">" turn the pages. */
@Composable
fun BookReader(game: AtticGame, index: Int, type: Int, onClose: () -> Unit) {
    BackHandler { onClose() }
    val rect = when (type) {
        0 -> intArrayOf(60, 10, 245, 190)
        1 -> intArrayOf(48, 2, 260, 197)
        else -> intArrayOf(50, 20, 250, 199)
    }
    val pages = remember(index, type, StartAssets.original) {
        game.data.bookText(index)?.let { layoutBook(it, rect[0], rect[1], rect[2], rect[3]) { w -> bitmapTextWidth(w) } } ?: emptyList()
    }
    val picture = remember(type) { game.data.bookPicture(type)?.let { StartAssets.picture(it) } }
    var page by remember(index, type) { mutableIntStateOf(0) }
    val color = Color(game.data.palette.getOrElse(26) { 0xFF000000.toInt() })
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        GameCanvas(Modifier.fillMaxSize()) {
            if (picture != null) {
                androidx.compose.foundation.Image(bitmap = picture, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            }
            pages.getOrNull(page)?.forEach { line ->
                line.words.forEach { w ->
                    Box(Modifier.offset(x = gx(w.x), y = gy(line.y))) { BitmapText(text = w.text, scale = unit.value, color = color) }
                }
            }
            if (page > 0) Choice("<", 22, 182, false) { page-- }
            if (page < pages.size - 1) Choice(">", 298, 182, false) { page++ }
        }
        Box(
            Modifier.align(Alignment.BottomStart).padding(12.dp).background(Color.Black.copy(alpha = 0.5f)).clickable(onClick = onClose).padding(horizontal = 10.dp, vertical = 6.dp),
        ) { BitmapText(text = "< Back", scale = 2f, color = TEXT) }
    }
}

/** What the game menu asks for: [SAVE]/[LOAD] with a slot number, or one of the plain commands. */
enum class GameMenuAction { RESUME, SAVE, LOAD, QUIT }

/**
 * The menu of the game: save to a slot, load from a slot, back to the game, quit to the main menu. [slotLabel] gives the text of each slot; [message] shows the
 * result of the last action.
 */
@Composable
fun GameMenu(slotLabel: (Int) -> String, canLoad: (Int) -> Boolean, message: String?, onAction: (GameMenuAction, Int) -> Unit) {
    BackHandler { onAction(GameMenuAction.RESUME, 0) }
    var mode by remember { mutableIntStateOf(0) } // 0 main, 1 save, 2 load
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).clickable(enabled = false) {}) {
        GameCanvas(Modifier.fillMaxSize()) {
            Box(Modifier.offset(x = gx(50), y = gy(20)).size(gx(220), gy(160)).background(Color.Black.copy(alpha = 0.92f)).border(unit * 1.5f, GOLD))
            when (mode) {
                0 -> {
                    CenteredText("Menu", 30)
                    Choice("Save game", 160, 58, false) { mode = 1 }
                    Choice("Load game", 160, 78, false) { mode = 2 }
                    Choice("Back to the game", 160, 98, false) { onAction(GameMenuAction.RESUME, 0) }
                    Choice("Quit to main menu", 160, 118, false) { onAction(GameMenuAction.QUIT, 0) }
                }
                else -> {
                    CenteredText(if (mode == 1) "Save game" else "Load game", 30)
                    for (slot in 0 until SaveStore.SLOTS) {
                        val enabled = mode == 1 || canLoad(slot)
                        Choice(slotLabel(slot), 160, 56 + slot * 20, false) {
                            if (enabled) { onAction(if (mode == 1) GameMenuAction.SAVE else GameMenuAction.LOAD, slot); mode = 0 }
                        }
                    }
                    Choice("< Back", 160, 128, false) { mode = 0 }
                }
            }
            if (message != null) CenteredText(message, 152, DIM)
        }
    }
}
