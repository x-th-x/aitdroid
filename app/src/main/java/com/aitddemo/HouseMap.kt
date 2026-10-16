package com.aitddemo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.aitddemo.game.AtticGame
import kotlin.math.cos
import kotlin.math.sin

/**
 * The map of the house (an addition to the original game, which has none): a plan of each floor drawn from the rooms of the original files, where each room lies and where its doors are.
 * The rooms the character has been in are filled and named, the others only show their outline. The character is shown with the way he looks.
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

    /** Names given to the rooms by what their pictures show; a room that is not listed is called "Room n". */
    private val rooms = mapOf(
        0 to mapOf(0 to "Attic"),
        1 to mapOf(0 to "Landing", 1 to "Upper hall", 2 to "East corridor", 3 to "West corridor", 4 to "Bedroom", 5 to "Corridor", 6 to "Attic stairs", 7 to "Stair hall"),
        2 to mapOf(0 to "Gallery", 1 to "Long hall", 2 to "Passage", 3 to "Pink bedroom", 4 to "Landing", 5 to "Bathroom", 6 to "Green bedroom", 7 to "Bedroom", 8 to "Study"),
        3 to mapOf(0 to "Entrance corridor", 1 to "Corridor", 2 to "Passage", 3 to "Statue court", 4 to "Conservatory", 5 to "Main hall", 7 to "Kitchen", 8 to "Back passage", 9 to "Dining room", 10 to "Scullery", 11 to "Sitting room", 12 to "Back corridor", 13 to "Corridor"),
        4 to mapOf(0 to "Wine cellar", 1 to "Cellar passage"),
        5 to mapOf(5 to "Lake cave", 8 to "Lake cave", 9 to "Stone passage", 10 to "Crypt", 11 to "Altar"),
    )

    fun room(stage: Int, room: Int): String = rooms[stage]?.get(room) ?: if (stage == 5) "Tunnel" else "Room $room"
}

private val PAPER = Color(0xFF0C0A08)
private val WALL = Color(0xFF8A6D3B)
private val SEEN = Color(0xFF2B3A38)
private val UNSEEN = Color(0xFF1A1A18)
private val HERE = Color(0xFF5A4A22)
private val DOOR = Color(0xFFE0A040)
private val TEXT = Color(0xFFF2E4B8)
private val DIM = Color(0xFF9C8B6E)

@Composable
fun HouseMapOverlay(game: AtticGame, onClose: () -> Unit) {
    BackHandler { onClose() }
    val visited = game.visited.toSet()
    val here = game.roomView.stage
    // the floors the character knows: those with a room he has been in (the current one first)
    val known = HouseNames.floors.filter { f -> visited.any { it / 100 == f } || f == here }
    var shown by remember { mutableIntStateOf(if (here in HouseNames.floors) here else known.firstOrNull() ?: 0) }
    val floor = if (shown in known) shown else known.firstOrNull() ?: 0
    val outlines by produceState<List<RoomOutline>>(emptyList(), floor) { value = try { game.data.rooms.outlines(floor) } catch (_: Exception) { emptyList() } }
    // draws again every half second, so that the character moves on the map while it is open
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(500); tick++ } }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.97f)).clickable(enabled = false) {}) {
        GameCanvas(Modifier.fillMaxSize()) {
            // title and the buttons to go to the floors above and below
            val index = known.indexOf(floor)
            CenteredLabel(HouseNames.floor(floor), 4, TEXT)
            if (index > 0) Arrow("<", 40, 4) { shown = known[index - 1] }
            if (index < known.lastIndex) Arrow(">", 280, 4) { shown = known[index + 1] }
            if (known.size > 1) CenteredLabel("${index + 1}/${known.size}", 20, DIM, 0.5f)
            Canvas(Modifier.offset(x = gx(8), y = gy(32)).size(gx(304), gy(150))) {
                tick // the picture follows the character
                drawFloor(game, floor, outlines, visited, size)
            }
            if (floor == here) CenteredLabel("You are here: " + HouseNames.room(here, game.roomView.room), 184, DIM, 0.5f)
            // the names of the rooms that are known
            val frame = fit(outlines, 304f, 150f)
            if (frame != null) for (r in outlines) {
                if ((floor * 100 + r.room) !in visited) continue
                val name = HouseNames.room(floor, r.room)
                val cx = 8f + frame.px((r.x1 + r.x2) / 2f)
                val cy = 32f + frame.pz((r.z1 + r.z2) / 2f)
                val w = (r.x2 - r.x1) * frame.scale
                val s = if (bitmapTextWidth(name) * 0.5f <= w - 2f) 0.5f else 0.38f
                Box(Modifier.offset(x = unit * (cx - bitmapTextWidth(name) * s / 2f), y = unit * (cy - 4f * s * 2f))) {
                    BitmapText(text = name, scale = unit.value * s, color = if (floor == here && game.roomView.room == r.room) TEXT else DIM)
                }
            }
        }
        Box(Modifier.align(Alignment.BottomStart).padding(12.dp).background(Color.Black.copy(alpha = 0.5f)).clickable(onClick = onClose).padding(horizontal = 10.dp, vertical = 6.dp)) {
            BitmapText(text = "< Back", scale = 2f, color = TEXT)
        }
    }
}

/** Where the plan of a floor lies in a box of [w] x [h]: the scale (box units per game unit) and the corner. */
private class Fit(val minX: Float, val minZ: Float, val scale: Float, val offX: Float, val offZ: Float) {
    fun px(x: Float) = offX + (x - minX) * scale
    fun pz(z: Float) = offZ + (z - minZ) * scale
}

private fun fit(outlines: List<RoomOutline>, w: Float, h: Float): Fit? {
    if (outlines.isEmpty()) return null
    val minX = outlines.minOf { it.x1 }.toFloat(); val maxX = outlines.maxOf { it.x2 }.toFloat()
    val minZ = outlines.minOf { it.z1 }.toFloat(); val maxZ = outlines.maxOf { it.z2 }.toFloat()
    val scale = minOf((w - 6f) / (maxX - minX), (h - 6f) / (maxZ - minZ))
    return Fit(minX, minZ, scale, (w - (maxX - minX) * scale) / 2f, (h - (maxZ - minZ) * scale) / 2f)
}

private fun DrawScope.drawFloor(game: AtticGame, floor: Int, outlines: List<RoomOutline>, visited: Set<Int>, box: Size) {
    val f = fit(outlines, box.width, box.height) ?: return
    val u = box.width / 304f // one unit of the 320 x 200 picture, in pixels
    for (r in outlines) {
        val seen = (floor * 100 + r.room) in visited
        val here = floor == game.roomView.stage && r.room == game.roomView.room
        val left = f.px(r.x1.toFloat())
        val top = f.pz(r.z1.toFloat())
        val size = Size((r.x2 - r.x1) * f.scale, (r.z2 - r.z1) * f.scale)
        drawRect(if (here) HERE else if (seen) SEEN else UNSEEN, Offset(left, top), size)
        drawRect(WALL.copy(alpha = if (seen) 1f else 0.45f), Offset(left, top), size, style = Stroke(width = 1.3f * u))
        if (seen) for (d in r.doors) drawCircle(DOOR, 1.9f * u, Offset(f.px(d.x.toFloat()), f.pz(d.z.toFloat())))
    }
    // the character
    if (floor == game.roomView.stage) {
        val o = outlines.firstOrNull { it.room == game.roomView.room } ?: return
        val a = game.player?.actor ?: return
        val x = f.px((o.originX + a.renderX).toFloat()); val z = f.pz((o.originZ + a.renderZ).toFloat())
        val ang = (a.beta and 0x3FF) * 2.0 * Math.PI / 1024.0
        val dx = sin(ang).toFloat(); val dz = -cos(ang).toFloat() // beta 0 looks to the north (up on the plan), 256 to the east
        val m = 5.5f * u
        val tip = Offset(x + dx * m, z + dz * m)
        val left = Offset(x - dz * 0.55f * m - dx * 0.5f * m, z + dx * 0.55f * m - dz * 0.5f * m)
        val right = Offset(x + dz * 0.55f * m - dx * 0.5f * m, z - dx * 0.55f * m - dz * 0.5f * m)
        val path = Path().apply { moveTo(tip.x, tip.y); lineTo(left.x, left.y); lineTo(right.x, right.y); close() }
        drawPath(path, Color(0xFFE23B2E))
        drawPath(path, Color.White, style = Stroke(width = 0.8f * u))
    }
}

@Composable
private fun GameCanvasScope.CenteredLabel(text: String, y: Int, color: Color, scale: Float = 1f) {
    Box(Modifier.offset(x = unit * (160 - bitmapTextWidth(text) * scale / 2f), y = gy(y))) { BitmapText(text = text, scale = unit.value * scale, color = color) }
}

@Composable
private fun GameCanvasScope.Arrow(text: String, centerX: Int, y: Int, onClick: () -> Unit) {
    Box(Modifier.offset(x = unit * (centerX - 14), y = gy(y - 2)).size(gx(28), gy(22)).clickable(onClick = onClick)) {
        Box(Modifier.offset(x = unit * (14 - bitmapTextWidth(text) / 2f), y = gy(2))) { BitmapText(text = text, scale = unit.value, color = TEXT) }
    }
}
