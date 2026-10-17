package com.aitddemo

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

// Menu layout on the 320x200 grid: framed box, lines from y=76 in steps of 16, centered at x=160,
// highlighted entry gets a background bar and a shadow.
private val MENU_ITEMS = listOf("Begin a new game", "Resume a saved game", "Options", "Floor selection", "Model Viewer", "Music Viewer", "Return to Homescreen")
// the entries only shown in Developer Mode (indexes in MENU_ITEMS)
private val DEVELOPER_ITEMS = setOf(3, 4, 5)

@Composable
fun MenuScreen(developerMode: Boolean, onSelect: (Int) -> Unit) {
    var selected by remember(developerMode) { mutableIntStateOf(0) }
    // the entries on show with their index in MENU_ITEMS (onSelect gets that index)
    val items = MENU_ITEMS.withIndex().filter { developerMode || it.index !in DEVELOPER_ITEMS }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        GameCanvas(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .offset(x = gx(0), y = gy(60))
                    .size(gx(320), gy(19 + items.size * 15)) // 10 above the first line, 8 below the last, 15 for each entry
                    .background(Color.Black.copy(alpha = 0.5f))
                    .border(unit * 1.5f, Color(0xFF8A6D3B))
            )

            items.forEachIndexed { i, (itemIndex, label) ->
                val y = 70 + i * 15
                val isSelected = selected == i
                val textWidthUnits = bitmapTextWidth(label)
                val textX = 160 - textWidthUnits / 2

                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .offset(x = gx(10), y = gy(y))
                            .size(gx(299), gy(16))
                            .background(Color(0xFF4A1420).copy(alpha = 0.6f))
                    )
                }

                Box(
                    modifier = Modifier
                        .offset(x = unit * textX, y = gy(y))
                        .clickable {
                            selected = i
                            onSelect(itemIndex)
                        }
                ) {
                    if (isSelected) {
                        // faint shadow copy one unit down-right, like the original's SelectedMessage
                        Box(modifier = Modifier.offset(x = unit, y = unit)) {
                            BitmapText(text = label, scale = unit.value, color = Color(0xFF3A0A10))
                        }
                        BitmapText(text = label, scale = unit.value, color = Color(0xFFF2E4B8))
                    } else {
                        BitmapText(text = label, scale = unit.value, color = Color(0xFF9C8B6E))
                    }
                }
            }
        }
    }
}
