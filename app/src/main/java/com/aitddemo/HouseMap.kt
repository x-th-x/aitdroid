package com.aitddemo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.aitddemo.game.AtticGame
import kotlin.math.cos
import kotlin.math.sin

/**
 * The map of the house (an addition to the original game, which has none): the character holds a folded sheet in his hands, and on it a plan of the floor, drawn in ink.
 * All the floors and all their rooms are on it (tabs at the edge turn to another floor); the rooms he has been in are washed with colour, the others are drawn lighter, and he is
 * shown where he stands, with the way he looks. A tap on a room shows its name below the plan.
 */
object HouseNames {
    /** The floors the map shows, from the top of the house to the bottom (the numbers of the floor files). The caves below are left out: they are a surprise. */
    val floors = listOf(0, 1, 2, 3, 4, 5)

    fun floor(stage: Int): String = when (stage) {
        0 -> "Attic"
        1 -> "Second floor"
        2 -> "First floor"
        3 -> "Ground floor"
        4 -> "Cellar"
        5 -> "Tunnels"
        else -> "Floor $stage"
    }

    /** The short name on the tab of a floor. */
    fun tab(stage: Int): String = when (stage) {
        0 -> "Attic"
        1 -> "2nd"
        2 -> "1st"
        3 -> "Ground"
        4 -> "Cellar"
        5 -> "Tunnels"
        else -> "$stage"
    }

    /** Names given to the rooms by what their pictures show; a room that is not listed is called "Room n". */
    private val rooms = mapOf(
        0 to mapOf(0 to "Attic"),
        1 to mapOf(0 to "Landing", 1 to "Upper hall", 2 to "East corridor", 3 to "West corridor", 4 to "Bedroom", 5 to "Corridor", 6 to "Attic stairs", 7 to "Stair hall"),
        2 to mapOf(0 to "Gallery", 1 to "Long hall", 2 to "Passage", 3 to "Pink bedroom", 4 to "Landing", 5 to "Bathroom", 6 to "Green bedroom", 7 to "Bedroom", 8 to "Study", 10 to "Passage"),
        3 to mapOf(0 to "Entrance corridor", 1 to "Corridor", 2 to "Passage", 3 to "Statue court", 4 to "Conservatory", 5 to "Main hall", 6 to "Closet", 7 to "Kitchen", 8 to "Back passage", 9 to "Dining room", 10 to "Scullery", 11 to "Sitting room", 12 to "Back corridor", 13 to "Corridor"),
        4 to mapOf(0 to "Wine cellar", 1 to "Cellar passage"),
        5 to mapOf(5 to "Lake cave", 8 to "Lake cave", 9 to "Stone passage", 10 to "Crypt", 11 to "Altar"),
    )

    fun room(stage: Int, room: Int): String = rooms[stage]?.get(room) ?: if (stage == 5) "Tunnel" else "Room $room"
}

// the ink and the washes of the sheet
private val INK = Color(0xFF3B2A18)
private val INK_LIGHT = Color(0xFF6B5136)
private val WASH_SEEN = Color(0x55A8763C)
private val WASH_HERE = Color(0x88C04A33)
private val RED = Color(0xFFB02A20)
// the tones of the hands and sleeves are those of the bodies of the game (Carnby: skin 8b6b53 / 97775f / af8b73, jacket 273b2f / 2b4333 / 37574f)
private val CARNBY_SKIN = HeldHands.Tones(0xFF8B6B53.toInt(), 0xFF97775F.toInt(), 0xFFAF8B73.toInt())
private val CARNBY_SLEEVE = HeldHands.Tones(0xFF1D2D23.toInt(), 0xFF273B2F.toInt(), 0xFF37574F.toInt())
private val EMILY_SKIN = HeldHands.Tones(0xFF8F5B3F.toInt(), 0xFF9F674B.toInt(), 0xFFB57F63.toInt())
private val EMILY_SLEEVE = HeldHands.Tones(0xFF1F3B46.toInt(), 0xFF2B4F5C.toInt(), 0xFF3F6B78.toInt())
private val SHIRT_CUFF = HeldHands.Tones(0xFF9C968A.toInt(), 0xFFC4BEB0.toInt(), 0xFFE2DCCE.toInt())

/** The area of the sheet (in the 320 x 200 picture of the letter page) that the plan may use. */
private const val PLAN_X = 58f
private const val PLAN_Y = 26f
private const val PLAN_W = 188f
private const val PLAN_H = 130f

@Composable
fun HouseMapOverlay(game: AtticGame, onClose: () -> Unit) {
    BackHandler { onClose() }
    val visited = game.visited.toSet()
    val here = game.roomView.stage
    var shown by remember { mutableIntStateOf(if (here in HouseNames.floors) here else 0) }
    var picked by remember { mutableIntStateOf(-1) } // the room whose name is shown below the plan
    val outlines by produceState<List<RoomOutline>>(emptyList(), shown) { value = try { game.data.rooms.outlines(shown) } catch (_: Exception) { emptyList() } }
    // draws again every half second, so that the character moves on the map while it is open
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(500); tick++ } }
    // the breathing of the hands: a new picture every frame
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { time = it / 1_000_000_000f } }
    val hands = remember { HeldHands() }
    val paper = remember { game.data.bookPicture(0)?.let { StartAssets.picture(it) } }
    val emily = game.vm.cvars[AtticGame.CVAR_CHOOSE_PERSON].toInt() == 1
    val frame = fit(outlines, PLAN_W, PLAN_H)

    Box(Modifier.fillMaxSize().background(Color.Black).clickable(enabled = false) {}) {
        GameCanvas(Modifier.fillMaxSize()) {
            if (paper != null) Image(bitmap = paper, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            // the sheet: the title, the plan
            Ink(HouseNames.floor(shown), 8, 1.25f, INK)
            Canvas(
                Modifier.offset(x = unit * PLAN_X, y = unit * PLAN_Y).size(unit * PLAN_W, unit * PLAN_H).pointerInput(outlines) {
                    detectTapGestures { p ->
                        if (frame == null) return@detectTapGestures
                        val ux = size.width / PLAN_W
                        val wx = frame.worldX(p.x / ux); val wz = frame.worldZ(p.y / ux)
                        picked = outlines.filter { wx in it.x1..it.x2 && wz in it.z1..it.z2 }.minByOrNull { (it.x2 - it.x1).toLong() * (it.z2 - it.z1) }?.room ?: -1
                    }
                },
            ) {
                tick // the picture follows the character
                if (frame != null) drawPlan(game, shown, outlines, visited, frame, size.width / PLAN_W, picked)
            }
            if (frame != null) for (r in outlines) {
                val cx = PLAN_X + frame.px((r.x1 + r.x2) / 2f)
                val cz = PLAN_Y + frame.pz((r.z1 + r.z2) / 2f)
                val w = (r.x2 - r.x1) * frame.scale
                val h = (r.z2 - r.z1) * frame.scale
                val name = HouseNames.room(shown, r.room)
                val lit = (shown * 100 + r.room) in visited
                val color = if (lit) INK else INK_LIGHT
                // the name as big as the room allows: whole, smaller, on two lines; a very small room gets its number
                val tw = bitmapTextWidth(name).toFloat()
                val words = name.split(' ')
                when {
                    tw * 0.5f <= w - 2f && h > 9f -> InkAt(name, cx, cz, 0.5f, color)
                    tw * 0.38f <= w - 2f && h > 8f -> InkAt(name, cx, cz, 0.38f, color)
                    words.size > 1 && words.maxOf { bitmapTextWidth(it) } * 0.38f <= w - 2f && h > 14f -> {
                        InkAt(words.first(), cx, cz - 3.3f, 0.38f, color)
                        InkAt(words.drop(1).joinToString(" "), cx, cz + 3.3f, 0.38f, color)
                    }
                    w > 5f && h > 5f -> InkAt("${r.room}", cx, cz, 0.45f, color)
                }
            }
            // the floors: tabs at the right edge of the sheet
            HouseNames.floors.forEachIndexed { i, f ->
                val y = 20 + i * 16
                val on = f == shown
                Box(
                    Modifier.offset(x = unit * (if (on) 252f else 256f), y = unit * y.toFloat()).size(unit * (if (on) 66f else 62f), unit * 15f)
                        .background(if (on) Color(0xFFE6CFA0) else Color(0xFFC4A878))
                        .clickable { shown = f; picked = -1 },
                ) {
                    Box(Modifier.offset(x = unit * 4f, y = unit * 2f)) {
                        BitmapText(text = HouseNames.tab(f), scale = unit.value * 0.5f, color = if (f == here) RED else INK)
                    }
                }
            }
            // the name of the room that was tapped, or where the character is
            val caption = if (picked >= 0) HouseNames.room(shown, picked) + (if (shown * 100 + picked !in visited) " (not seen yet)" else "")
            else if (shown == here) "You are here: " + HouseNames.room(here, game.roomView.room) else ""
            if (caption.isNotEmpty()) Ink(caption, 162, 0.55f, INK)
            // the hands that hold the sheet
            Canvas(Modifier.fillMaxSize()) {
                hands.render(time, if (emily) EMILY_SLEEVE else CARNBY_SLEEVE, if (emily) EMILY_SKIN else CARNBY_SKIN, SHIRT_CUFF)
                drawImage(hands.bitmap.asImageBitmap(), dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.None)
            }
        }
        Box(Modifier.align(Alignment.BottomStart).padding(12.dp).background(Color.Black.copy(alpha = 0.5f)).clickable(onClick = onClose).padding(horizontal = 10.dp, vertical = 6.dp)) {
            BitmapText(text = "< Back", scale = 2f, color = Color(0xFFF2E4B8))
        }
    }
}

/** Where the plan of a floor lies in a box of [w] x [h]: the scale (box units per game unit) and the corner. */
private class Fit(val minX: Float, val minZ: Float, val scale: Float, val offX: Float, val offZ: Float) {
    fun px(x: Float) = offX + (x - minX) * scale
    fun pz(z: Float) = offZ + (z - minZ) * scale
    fun worldX(px: Float) = ((px - offX) / scale + minX).toInt()
    fun worldZ(pz: Float) = ((pz - offZ) / scale + minZ).toInt()
}

private fun fit(outlines: List<RoomOutline>, w: Float, h: Float): Fit? {
    if (outlines.isEmpty()) return null
    val minX = outlines.minOf { it.x1 }.toFloat(); val maxX = outlines.maxOf { it.x2 }.toFloat()
    val minZ = outlines.minOf { it.z1 }.toFloat(); val maxZ = outlines.maxOf { it.z2 }.toFloat()
    val scale = minOf((w - 6f) / (maxX - minX), (h - 6f) / (maxZ - minZ))
    return Fit(minX, minZ, scale, (w - (maxX - minX) * scale) / 2f, (h - (maxZ - minZ) * scale) / 2f)
}

/** The plan in ink: [u] pixels for one unit of the 320 x 200 picture. */
private fun DrawScope.drawPlan(game: AtticGame, floor: Int, outlines: List<RoomOutline>, visited: Set<Int>, f: Fit, u: Float, picked: Int) {
    for (r in outlines) {
        val seen = (floor * 100 + r.room) in visited
        val here = floor == game.roomView.stage && r.room == game.roomView.room
        val left = f.px(r.x1.toFloat()) * u
        val top = f.pz(r.z1.toFloat()) * u
        val size = Size((r.x2 - r.x1) * f.scale * u, (r.z2 - r.z1) * f.scale * u)
        if (here) drawRect(WASH_HERE, Offset(left, top), size) else if (seen) drawRect(WASH_SEEN, Offset(left, top), size)
        if (r.room == picked) drawRect(RED.copy(alpha = 0.18f), Offset(left, top), size)
        drawRect(if (seen) INK else INK_LIGHT.copy(alpha = 0.75f), Offset(left, top), size, style = Stroke(width = (if (seen) 1.5f else 1f) * u))
        // doors: a dot in the ink (only where the zone lies inside the room)
        for (d in r.doors) {
            if (d.x !in r.x1..r.x2 || d.z !in r.z1..r.z2) continue
            drawCircle(INK, 1.1f * u, Offset(f.px(d.x.toFloat()) * u, f.pz(d.z.toFloat()) * u))
        }
        // stairs: three short steps in the upper right corner of the room (the name stays free)
        if (r.stairs.isNotEmpty()) {
            val x = f.px(r.x2.toFloat()) * u - 5f * u; val z = f.pz(r.z1.toFloat()) * u + 4f * u
            if (size.width > 12f * u && size.height > 9f * u) for (k in -1..1) drawLine(INK, Offset(x - 2.6f * u, z + k * 1.4f * u), Offset(x + 2.6f * u, z + k * 1.4f * u), strokeWidth = 0.8f * u)
        }
    }
    // the character: a red arrow, with the way he looks
    if (floor == game.roomView.stage) {
        val o = outlines.firstOrNull { it.room == game.roomView.room } ?: return
        val a = game.player?.actor ?: return
        val x = f.px((o.originX + a.renderX).toFloat()) * u; val z = f.pz((o.originZ + a.renderZ).toFloat()) * u
        val ang = (a.beta and 0x3FF) * 2.0 * Math.PI / 1024.0
        val dx = sin(ang).toFloat(); val dz = -cos(ang).toFloat() // beta 0 looks to the north (up on the plan), 256 to the east
        val m = 5.5f * u
        val path = Path().apply {
            moveTo(x + dx * m, z + dz * m)
            lineTo(x - dz * 0.55f * m - dx * 0.5f * m, z + dx * 0.55f * m - dz * 0.5f * m)
            lineTo(x + dz * 0.55f * m - dx * 0.5f * m, z - dx * 0.55f * m - dz * 0.5f * m)
            close()
        }
        drawPath(path, RED)
        drawPath(path, Color(0xFFF4E6C4), style = Stroke(width = 0.8f * u))
    }
}

@Composable
private fun GameCanvasScope.Ink(text: String, y: Int, scale: Float, color: Color) {
    Box(Modifier.offset(x = unit * (160 - bitmapTextWidth(text) * scale / 2f), y = gy(y))) { BitmapText(text = text, scale = unit.value * scale, color = color) }
}

/** Ink text centred on (cx, cz) of the 320 x 200 picture. */
@Composable
private fun GameCanvasScope.InkAt(text: String, cx: Float, cz: Float, scale: Float, color: Color) {
    Box(Modifier.offset(x = unit * (cx - bitmapTextWidth(text) * scale / 2f), y = unit * (cz - 8f * scale))) { BitmapText(text = text, scale = unit.value * scale, color = color) }
}
