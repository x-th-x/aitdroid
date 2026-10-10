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
fun HoldButton(label: String, modifier: Modifier = Modifier, onHeld: (Boolean) -> Unit) {
    Box(
        modifier = modifier
            .size(width = 96.dp, height = 56.dp)
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
        BitmapText(text = label, scale = 2f, color = TEXT)
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

/** The "You find ..." window of the original: the item turns around, the player takes or leaves it. */
@Composable
fun FoundWindow(game: AtticGame, item: Int, onChoice: (Boolean) -> Unit) {
    val w = game.vm.objects[item]
    val texts = game.data.texts
    val heavy = remember(item) { game.tooHeavy(item) }
    val asset = remember(item) { game.data.body(w.foundBody) }
    var choice by remember(item) { mutableIntStateOf(1) } // 1 = take, 0 = leave
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(enabled = false) {}) {
        GameCanvas(Modifier.fillMaxSize()) {
            Box(Modifier.offset(x = gx(40), y = gy(20)).size(gx(240), gy(160)).background(Color.Black.copy(alpha = 0.92f)).border(unit * 1.5f, GOLD))
            CenteredText(texts[20] ?: "You Find", 28)
            CenteredText(texts[w.foundName] ?: "?", 44)
            if (asset != null) {
                Box(Modifier.offset(x = gx(100), y = gy(62)).size(gx(120), gy(70))) {
                    ModelSpinner(vertices = asset.vertices, triangles = asset.triangles, rotationSpeed = 1.2f, modifier = Modifier.fillMaxSize())
                }
            }
            if (heavy) {
                Choice(texts[10] ?: "You cannot carry anything else", 160, 150, true) { onChoice(false) }
            } else {
                Choice(texts[21] ?: "Leave", 110, 150, choice == 0) { choice = 0; onChoice(false) }
                Choice(texts[22] ?: "Take", 210, 150, choice == 1) { choice = 1; onChoice(true) }
            }
        }
    }
}

/**
 * The inventory: the chosen item turns around in the middle (tap it to look at it closer), its name above, `<` `>` change the item; the item's actions
 * (bits of its found flags, text 23 + bit) are listed on the right.
 */
@Composable
fun InventoryOverlay(game: AtticGame, onClose: () -> Unit, onUse: (Int, Int) -> Unit) {
    val texts = game.data.texts
    var selected by remember { mutableIntStateOf(0) }
    var zoomed by remember { mutableStateOf(false) }
    val items = game.inventory
    if (items.isEmpty()) {
        onClose()
        return
    }
    val sel = selected.coerceIn(0, items.size - 1)
    val item = items[sel]
    val w = game.vm.objects[item]
    val asset = remember(item) { game.data.body(w.foundBody) }
    val actions = (0 until 11).filter { w.foundFlag and (1 shl it) != 0 }.take(5)
    val name = texts[w.foundName] ?: "?"
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(enabled = false) {}) {
        GameCanvas(Modifier.fillMaxSize()) {
            Box(Modifier.offset(x = gx(10), y = gy(8)).size(gx(300), gy(184)).background(Color.Black.copy(alpha = 0.92f)).border(unit * 1.5f, GOLD))
            CenteredText(name, 14)
            Box(Modifier.offset(x = gx(105), y = gy(34)).size(gx(110), gy(100)).background(Color(0xFF0C0608)).border(unit, GOLD).clickable(enabled = asset != null) { zoomed = true }) {
                if (asset != null) ModelSpinner(vertices = asset.vertices, triangles = asset.triangles, rotationSpeed = 0.9f, modifier = Modifier.fillMaxSize())
            }
            if (items.size > 1) {
                Choice("<", 125, 142, false) { selected = (sel + items.size - 1) % items.size }
                CenteredText("${sel + 1}/${items.size}", 142, DIM)
                Choice(">", 195, 142, false) { selected = (sel + 1) % items.size }
            }
            actions.forEachIndexed { i, bit ->
                Choice(texts[23 + bit] ?: "?", 264, 44 + i * 18, false) { onUse(item, 1 shl bit) }
            }
            Choice("< Back", 160, 170, false) { onClose() }
        }
        if (zoomed && asset != null) ZoomedItem(name, asset.vertices, asset.triangles) { zoomed = false }
    }
}

/** A closer look at an item: one finger turns it around, two fingers zoom it; "< Back" returns to the inventory. */
@Composable
private fun ZoomedItem(name: String, vertices: FloatArray, triangles: IntArray, onClose: () -> Unit) {
    BackHandler { onClose() }
    var angle by remember { mutableFloatStateOf(0.6f) }
    var zoom by remember { mutableFloatStateOf(1.5f) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0C0608))
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    angle += pan.x * 0.012f
                    zoom = (zoom * gestureZoom).coerceIn(0.5f, 6f)
                }
            },
    ) {
        ModelView(vertices = vertices, triangles = triangles, angle = angle, zoom = zoom, modifier = Modifier.fillMaxSize())
        Box(Modifier.align(Alignment.TopCenter).padding(12.dp)) { BitmapText(text = name, scale = 2.5f, color = TEXT) }
        Box(
            Modifier.align(Alignment.BottomStart).padding(12.dp).background(Color.Black.copy(alpha = 0.5f)).clickable(onClick = onClose).padding(horizontal = 10.dp, vertical = 6.dp),
        ) { BitmapText(text = "< Back", scale = 2f, color = TEXT) }
    }
}

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
