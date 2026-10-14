package com.aitddemo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** Where the game starts when it is begun from the floor selection of the main menu (for testing); null = the normal start in the attic. */
class FloorStart(val stage: Int, val room: Int, val items: List<Int>, val skipWaits: Boolean, val emily: Boolean = false, val lostPlace: Boolean = false)

/** The things a start from the floor selection can carry (world objects of OBJETS.ITD). */
private val PRESETS = listOf(
    "Nothing" to emptyList(),
    "Light" to listOf(13, 16, 174), // oil lamp, oil can, matchbox
    "Weapons" to listOf(38, 103, 101, 3, 18, 25, 26, 27, 28, 78, 46), // sabre, revolver, bullets, rifle, cartridges, bow, arrows, dagger, first aid case
    "Light and weapons" to listOf(13, 16, 174, 38, 103, 101, 3, 18, 25, 26, 27, 28, 78, 46),
)

/** The number of rooms of each floor (ETAGE00..06.PAK). */
private val ROOM_COUNTS = intArrayOf(1, 8, 11, 14, 2, 12, 7)

private fun floorName(stage: Int) = if (stage == 0) "Attic" else "Floor $stage"

/** Chooses floor, room and the things carried; a tap on a line moves to its next value. */
@Composable
fun FloorSelectScreen(onStart: (FloorStart) -> Unit, onBack: () -> Unit) {
    BackHandler { onBack() }
    var stage by remember { mutableIntStateOf(1) }
    var room by remember { mutableIntStateOf(0) }
    var preset by remember { mutableIntStateOf(3) }
    var skipWaits by remember { mutableIntStateOf(1) }
    var emily by remember { mutableIntStateOf(0) }
    var lostPlace by remember { mutableIntStateOf(0) }

    val lines = listOf(
        "Character: ${if (emily == 1) "Emily" else "Carnby"}",
        "Style: ${if (lostPlace == 1) "Lost Place (test)" else "Original"}",
        "Floor: ${floorName(stage)}",
        "Room: $room",
        "Items: ${PRESETS[preset].first}",
        "Waiting times: ${if (skipWaits == 1) "skipped" else "as original"}",
        "Start",
    )
    val actions = listOf<() -> Unit>(
        { emily = 1 - emily },
        { lostPlace = 1 - lostPlace },
        { stage = (stage + 1) % ROOM_COUNTS.size; room = 0 },
        { room = (room + 1) % ROOM_COUNTS[stage] },
        { preset = (preset + 1) % PRESETS.size },
        { skipWaits = 1 - skipWaits },
        { onStart(FloorStart(stage, room, PRESETS[preset].second, skipWaits == 1, emily == 1, lostPlace == 1)) },
    )

    Box(Modifier.fillMaxSize().background(Color(0xFF14080A))) {
        StartPictureImage(StartPicture.MENU)
        GameCanvas(Modifier.fillMaxSize()) {
            Box(Modifier.offset(x = gx(0), y = gy(56)).size(gx(320), gy(124)).background(Color.Black.copy(alpha = 0.5f)).border(unit * 1.5f, Color(0xFF8A6D3B)))
            lines.forEachIndexed { i, label ->
                val last = i == lines.lastIndex
                Box(Modifier.offset(x = gx(40), y = gy(62 + i * 14)).size(gx(240), gy(14)).clickable { actions[i]() }) {
                    Box(Modifier.offset(x = unit * (120 - bitmapTextWidth(label) / 2))) {
                        BitmapText(text = label, scale = unit.value, color = if (last) Color(0xFFF2E4B8) else Color(0xFF9C8B6E))
                    }
                }
            }
        }
        BackButton(onClick = onBack)
    }
}
