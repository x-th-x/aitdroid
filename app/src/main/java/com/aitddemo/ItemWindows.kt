package com.aitddemo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.aitddemo.game.AtticGame
import com.aitddemo.game.BodyAsset
import com.aitddemo.game.GameData
import com.aitddemo.original.OriginalFontData
import com.aitddemo.original.TileSheet
import kotlin.math.cos
import kotlin.math.sin

// The windows of the original that show an item: "You Find ..." and the inventory. The numbers (positions, colours, camera) come from the original program (ip addresses in
// alone_review/INVENTORY_SPEC.md); everything is laid out on the 320x200 screen and drawn into a bitmap of twice that size.

private val BUTTON_TEXT = Color(0xFFF2E4B8)

/** Palette colours of the interface. */
private const val COLOR_WHITE = 1
private const val COLOR_TEXT = 4
private const val COLOR_BRIGHT = 15
private const val COLOR_BAR = 100

/** The tilt of the camera over an item (0x3C of 1024 per turn), the spin per frame, and the frames a second the spin is counted in. */
private const val ITEM_ALPHA = 60
private const val SPIN_PER_FRAME = 8
private const val FRAMES_PER_SECOND = 60.0

/**
 * Draws the two windows (frames of the tile sheet, the game's font, the item in perspective) into [bitmap], 640x400: two pixels for each pixel of the 320x200 screen.
 * [nameOf] gives the text number of the name of an object.
 */
class WindowPainter(private val data: GameData, private val nameOf: (Int) -> Int) {
    val bitmap: Bitmap = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap).also { it.scale(2f, 2f) }
    private val fill = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL }
    private val plain = Paint().apply { isAntiAlias = false; isFilterBitmap = false }
    private val glyphs = Paint().apply { isAntiAlias = false; isFilterBitmap = false }
    private val items = AtticSceneRenderer()
    private val sheet: TileSheet? = data.resource(5)?.let { try { TileSheet(it) } catch (_: Exception) { null } }
    private val opaque = arrayOfNulls<Bitmap>(TileSheet.COUNT)
    private val see = arrayOfNulls<Bitmap>(TileSheet.COUNT)

    private fun color(i: Int) = data.palette.getOrElse(i) { 0xFFFFFFFF.toInt() }

    private fun tileBitmap(i: Int, transparent: Boolean): Bitmap? {
        val t = sheet?.tiles?.getOrNull(i) ?: return null
        val cache = if (transparent) see else opaque
        cache[i]?.let { return it }
        val px = IntArray(t.width * t.height) { k ->
            val c = t.pixels[k].toInt() and 0xFF
            if (transparent && c == 0) 0 else color(c)
        }
        return Bitmap.createBitmap(px, t.width, t.height, Bitmap.Config.ARGB_8888).also { cache[i] = it }
    }

    private fun tile(i: Int, x: Int, y: Int, transparent: Boolean = false) {
        val b = tileBitmap(i, transparent) ?: return
        canvas.drawBitmap(b, x.toFloat(), y.toFloat(), plain)
    }

    private fun fillRect(x1: Int, y1: Int, x2: Int, y2: Int, c: Int) {
        fill.color = c
        canvas.drawRect(x1.toFloat(), y1.toFloat(), (x2 + 1).toFloat(), (y2 + 1).toFloat(), fill)
    }

    /** DrawWindow of the original (centre, size): the frame of tiles, the inside black. Returns the inside (left, top, right, bottom). */
    private fun window(cx: Int, cy: Int, w: Int, h: Int): IntArray {
        val l = cx - w / 2; val t = cy - h / 2; val r = cx + w / 2; val b = cy + h / 2
        tile(TileSheet.CORNER_TL, l, t)
        var x = l + 20
        while (r - 20 > x) { tile(TileSheet.EDGE_TOP, x, t); x += 20 }
        tile(TileSheet.CORNER_TR, x, t)
        var y = t + 20
        while (b - 20 > y) { tile(TileSheet.EDGE_LEFT, l, y); y += 20 }
        y = t + 20
        while (b - 20 > y) { tile(TileSheet.EDGE_RIGHT, r - 8, y); y += 20 }
        val yEnd = y
        tile(TileSheet.CORNER_BL, l, yEnd)
        x = l + 20
        while (r - 20 > x) { tile(TileSheet.EDGE_BOTTOM, x, yEnd + 12); x += 20 }
        tile(TileSheet.CORNER_BR, x, yEnd)
        tile(TileSheet.PLATE, cx - 20, yEnd + 12)
        fillRect(l + 8, t + 8, r - 9, b - 9, color(0))
        return intArrayOf(l + 8, t + 8, r - 9, b - 9)
    }

    /** One line of text of the game's font with its left edge at [x], top at [y]. */
    private fun string(x: Int, y: Int, text: String, c: Int) {
        val atlas = StartAssets.atlas()?.asAndroidBitmap() ?: return
        glyphs.colorFilter = PorterDuffColorFilter(c, PorterDuff.Mode.SRC_IN)
        var px = x
        for (ch in text) {
            val w = glyphWidth(ch)
            if (w > 0 && ch.code >= OriginalFontData.FIRST) {
                val n = ch.code - OriginalFontData.FIRST
                val sx = (n % OriginalFontData.COLUMNS) * OriginalFontData.CELL
                val sy = (n / OriginalFontData.COLUMNS) * OriginalFontData.CELL
                canvas.drawBitmap(atlas, Rect(sx, sy, sx + w, sy + OriginalFontData.CELL), Rect(px, y, px + w, y + OriginalFontData.CELL), glyphs)
            }
            px += glyphAdvance(ch)
        }
    }

    /** DrawText: centred on [cx], one line below [y]. */
    private fun text(cx: Int, y: Int, text: String, c: Int) = string(cx - bitmapTextWidth(text) / 2, y + 1, text, c)

    /** DrawTextSelected: the same text in [shadow] one line lower, then in [front]. */
    private fun selected(cx: Int, y: Int, text: String, front: Int, shadow: Int) {
        val x = cx - bitmapTextWidth(text) / 2
        string(x, y + 1, text, shadow)
        string(x, y, text, front)
    }

    private fun name(item: Int): String = data.texts[nameOf(item)] ?: "?"

    /** The body as the camera of the windows sees it, drawn clipped into [clip]: [cx], [cy] the middle of the picture, [fx], [fy] its focal lengths. */
    private fun body(asset: BodyAsset?, clip: IntArray, cx: Int, cy: Int, fx: Float, fy: Float, spin: Int, dist: Float, tilt: Int = ITEM_ALPHA) {
        if (asset == null) return
        val n = asset.vertices.size / 3
        val ba = (spin and 0x3FF) * 2.0 * Math.PI / 1024.0
        val aa = (tilt and 0x3FF) * 2.0 * Math.PI / 1024.0
        val bc = cos(ba).toFloat(); val bs = sin(ba).toFloat(); val ac = cos(aa).toFloat(); val asn = sin(aa).toFloat()
        val v = FloatArray(n * 3)
        for (i in 0 until n) {
            val x = asset.vertices[i * 3]; val y = asset.vertices[i * 3 + 1]; val z = asset.vertices[i * 3 + 2]
            val x1 = x * bc - z * bs
            val z1 = x * bs + z * bc
            v[i * 3] = x1
            v[i * 3 + 1] = y * ac - z1 * asn
            v[i * 3 + 2] = y * asn + z1 * ac + dist
        }
        val actor = AtticActor(v, asset.triangles, 0f, 0f, dist, 0, 0, 0, 0, asset.lines, asset.spheres)
        items.renderBody(AtticCameraDef(0, 0f, 0f, 0f, 0, 0, 0, 128f, fx, fy, emptyList()), actor)
        canvas.save()
        canvas.clipRect(clip[0].toFloat(), clip[1].toFloat(), (clip[2] + 1).toFloat(), (clip[3] + 1).toFloat())
        canvas.scale(0.5f, 0.5f) // the picture of the body has two pixels for each of the screen
        canvas.drawBitmap(items.bitmap, ((cx - 160) * 2).toFloat(), ((cy - 100) * 2).toFloat(), plain)
        canvas.restore()
    }

    /** The "You Find" window of the original at [frame] (the spin and the breathing of the distance count frames), [mode] 0 leave, 1 take, 2 too heavy. */
    fun found(item: Int, asset: BodyAsset?, mode: Int, frame: Int) {
        bitmap.eraseColor(0)
        val inside = window(160, 100, 240, 120)
        // the distance goes to and fro between 8000 and 25000 in steps of 200 (starting at 15000, going nearer)
        val half = (25000 - 8000) / 200
        val step = ((15000 - 8000) / 200 + frame) % (half * 2)
        val z = 8000 + 200 * (if (step <= half) step else half * 2 - step)
        body(asset, inside, 160, 100, 300f, 298f, -SPIN_PER_FRAME * frame, z.toFloat())
        text(160, 48, data.texts[20] ?: "You Find", color(COLOR_WHITE))
        text(160, 64, name(item), color(COLOR_WHITE))
        val leave = data.texts[21] ?: "Leave"; val take = data.texts[22] ?: "Take"
        when (mode) {
            0 -> { selected(130, 135, leave, color(COLOR_WHITE), color(COLOR_TEXT)); text(190, 135, take, color(COLOR_TEXT)) }
            1 -> { text(130, 135, leave, color(COLOR_TEXT)); selected(190, 135, take, color(COLOR_WHITE), color(COLOR_TEXT)) }
            else -> selected(160, 135, data.texts[10] ?: "You cannot carry anything else", color(COLOR_WHITE), color(COLOR_TEXT))
        }
    }

    /** The inventory: the list (top), the item (bottom left) and the actions of the chosen item (bottom right). */
    fun inventory(list: List<Int>, sel: Int, first: Int, asset: BodyAsset?, number: Int?, actions: List<Int>, frame: Int) {
        bitmap.eraseColor(0)
        window(160, 50, 320, 100)
        for (i in 0 until minOf(ROWS, list.size - first)) {
            val y = 9 + 16 * i
            val label = name(list[first + i])
            if (first + i == sel) {
                fillRect(10, y, 309, y + 16, color(COLOR_BAR))
                selected(160, y, label, color(COLOR_BRIGHT), color(COLOR_TEXT))
            } else {
                text(160, y, label, color(COLOR_TEXT))
            }
        }
        if (first > 0) tile(TileSheet.ARROW_UP, 298, 10, true)
        if (first + ROWS < list.size) tile(TileSheet.ARROW_DOWN, 298, 74, true)

        window(240, 150, 160, 100)
        val y0 = 150 - actions.size * 16 / 2
        actions.forEachIndexed { i, bit -> text(240, y0 + 16 * i, data.texts[23 + bit] ?: "?", color(COLOR_TEXT)) }

        val model = window(80, 150, 160, 100)
        body(asset, model, 79, 149, 400f, 390f, -SPIN_PER_FRAME * frame, 24000f)
        if (number != null) {
            canvas.save()
            canvas.clipRect(model[0].toFloat(), model[1].toFloat(), (model[2] + 1).toFloat(), (model[3] + 1).toFloat())
            string(model[0] + 4, model[1] + 4, number.toString(), color(COLOR_TEXT))
            canvas.restore()
        }
    }

    /** An item looked at closer (the player turns it by hand): the whole screen, [beta] and [alpha] the angles (1024 per turn), [dist] its distance. */
    fun closeUp(asset: BodyAsset?, beta: Int, alpha: Int, dist: Float) {
        bitmap.eraseColor(0)
        fillRect(0, 0, 319, 199, color(0))
        body(asset, intArrayOf(0, 0, 319, 199), 160, 100, 400f, 390f, beta, dist, alpha)
    }

    companion object {
        const val ROWS = 5
    }
}

/** The actions of an item (the bits 0 to 10 of its found flags, five at most; the text of each is 23 + the bit), as the original lists them. */
private fun actionsOf(foundFlag: Int): List<Int> = (0 until 11).filter { foundFlag and (1 shl it) != 0 }.take(5)

/** The frames the windows have run since they opened (the original counts frames; the spin and the breathing go by them). */
@Composable
private fun rememberFrames(key: Any?): Int {
    val start = remember(key) { System.nanoTime() }
    var now by remember(key) { mutableLongStateOf(start) }
    LaunchedEffect(key) { while (true) withFrameNanos { now = it } }
    return ((now - start) / 1_000_000_000.0 * FRAMES_PER_SECOND).toInt()
}

/** The picture of a [WindowPainter] over the 320x200 area. */
@Composable
private fun PaintedScreen(painter: WindowPainter, frame: Int, draw: (WindowPainter) -> Unit) {
    ComposeCanvas(Modifier.fillMaxSize()) {
        frame // the picture changes every frame
        draw(painter)
        drawImage(painter.bitmap.asImageBitmap(), dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.None)
    }
}

/** The "You Find ..." window of the original: the item turns around in perspective, the player takes or leaves it. */
@Composable
fun FoundWindow(game: AtticGame, item: Int, onChoice: (Boolean) -> Unit) {
    val w = game.vm.objects[item]
    val heavy = remember(item) { game.tooHeavy(item) }
    var mode by remember(item) { mutableIntStateOf(if (heavy) 2 else 1) } // 1 = take, 0 = leave, 2 = too heavy
    val asset = remember(item) { game.data.body(w.foundBody) }
    val painter = remember(game) { WindowPainter(game.data) { game.vm.objects[it].foundName } }
    val frame = rememberFrames(item)
    BackHandler { onChoice(false) }
    GameCanvas(Modifier.fillMaxSize()) {
        PaintedScreen(painter, frame) { it.found(item, asset, mode, frame) }
        Box(
            Modifier.fillMaxSize().pointerInput(item, heavy) {
                detectTapGestures { p ->
                    val x = p.x * 320f / size.width
                    val y = p.y * 200f / size.height
                    if (y < 128f || y > 152f) return@detectTapGestures
                    if (heavy) onChoice(false)
                    else if (x in 90f..160f) { mode = 0; onChoice(false) }
                    else if (x in 160f..230f) { mode = 1; onChoice(true) }
                }
            },
        )
    }
}

/**
 * The inventory of the original: the list of the items on top (five lines, the arrows scroll), the chosen item turning at the bottom left (with its number), its actions at the
 * bottom right. A tap on a line chooses the item, a tap on an action does it, a tap on the item looks at it closer.
 */
@Composable
fun InventoryOverlay(game: AtticGame, onClose: () -> Unit, onUse: (Int, Int) -> Unit) {
    val items = game.inventory
    if (items.isEmpty()) {
        onClose()
        return
    }
    var selected by remember { mutableIntStateOf(0) }
    var first by remember { mutableIntStateOf(0) }
    var zoomed by remember { mutableStateOf(false) }
    val sel = selected.coerceIn(0, items.size - 1)
    if (sel >= first + WindowPainter.ROWS) first = sel - WindowPainter.ROWS + 1
    if (sel < first) first = sel
    first = first.coerceIn(0, maxOf(0, items.size - WindowPainter.ROWS))
    val item = items[sel]
    val w = game.vm.objects[item]
    val asset = remember(item) { game.data.body(w.foundBody) }
    val actions = remember(item, w.foundFlag) { actionsOf(w.foundFlag) }
    val number = if (w.floorLife in game.vm.vars.indices) game.vm.vars[w.floorLife].toInt() else null
    val painter = remember(game) { WindowPainter(game.data) { game.vm.objects[it].foundName } }
    val frame = rememberFrames(null)
    BackHandler { if (zoomed) zoomed = false else onClose() }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        GameCanvas(Modifier.fillMaxSize()) {
            PaintedScreen(painter, frame) { it.inventory(items, sel, first, asset, number, actions, frame) }
            Box(
                Modifier.fillMaxSize().pointerInput(items.size, actions, first, sel) {
                    detectTapGestures { p ->
                        val x = p.x * 320f / size.width
                        val y = p.y * 200f / size.height
                        if (y < 100f) {
                            if (x >= 290f) selected = if (y < 50f) maxOf(0, sel - 1) else minOf(items.size - 1, sel + 1)
                            else {
                                val row = ((y - 9f) / 16f).toInt()
                                if (row in 0 until WindowPainter.ROWS && first + row < items.size) selected = first + row
                            }
                        } else if (x >= 160f) {
                            val top = 150 - actions.size * 8
                            val row = ((y - top) / 16f).toInt()
                            if (y >= top && row in actions.indices) onUse(item, 1 shl actions[row])
                        } else if (asset != null) {
                            zoomed = true
                        }
                    }
                },
            )
        }
        Box(Modifier.align(Alignment.BottomStart).padding(12.dp).background(Color.Black.copy(alpha = 0.5f)).clickable(onClick = onClose).padding(horizontal = 10.dp, vertical = 6.dp)) {
            BitmapText(text = "< Back", scale = 2f, color = BUTTON_TEXT)
        }
        if (zoomed && asset != null) ZoomedItem(painter, game.data.texts[w.foundName] ?: "?", asset) { zoomed = false }
    }
}

/** A closer look at an item: one finger turns it around (left/right and up/down), two fingers move it nearer or farther; "< Back" returns to the inventory. */
@Composable
private fun ZoomedItem(painter: WindowPainter, name: String, asset: BodyAsset, onClose: () -> Unit) {
    BackHandler { onClose() }
    var beta by remember { mutableFloatStateOf(0f) }
    var alpha by remember { mutableFloatStateOf(ITEM_ALPHA.toFloat()) }
    var zoom by remember { mutableFloatStateOf(1f) }
    val frame = rememberFrames("zoom")
    Box(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
            detectTransformGestures { _, pan, gestureZoom, _ ->
                beta -= pan.x * 0.6f
                alpha += pan.y * 0.6f
                zoom = (zoom * gestureZoom).coerceIn(0.3f, 6f)
            }
        },
    ) {
        GameCanvas(Modifier.fillMaxSize()) {
            // a little nearer than the inventory shows it: the whole screen is the item's
            PaintedScreen(painter, frame) { it.closeUp(asset, beta.toInt(), alpha.toInt(), 16000f / zoom) }
        }
        Box(Modifier.align(Alignment.TopCenter).padding(12.dp)) { BitmapText(text = name, scale = 2.5f, color = BUTTON_TEXT) }
        Box(Modifier.align(Alignment.BottomStart).padding(12.dp).background(Color.Black.copy(alpha = 0.5f)).clickable(onClick = onClose).padding(horizontal = 10.dp, vertical = 6.dp)) {
            BitmapText(text = "< Back", scale = 2f, color = BUTTON_TEXT)
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(12.dp).background(Color.Black.copy(alpha = 0.5f)).clickable { beta = 0f; alpha = ITEM_ALPHA.toFloat(); zoom = 1f }.padding(horizontal = 10.dp, vertical = 6.dp)) {
            BitmapText(text = "Reset", scale = 2f, color = BUTTON_TEXT)
        }
    }
}
