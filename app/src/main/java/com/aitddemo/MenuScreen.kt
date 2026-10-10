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
private val MENU_ITEMS = listOf("Begin a new game", "Resume a saved game", "Options", "Model Viewer", "Music Viewer", "Return to Homescreen")

@Composable
fun MenuScreen(onSelect: (Int) -> Unit) {
    var selected by remember { mutableIntStateOf(0) }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF14080A))) {
        StartPictureImage(StartPicture.MENU)
        GameCanvas(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .offset(x = gx(0), y = gy(60))
                    .size(gx(320), gy(112))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .border(unit * 1.5f, Color(0xFF8A6D3B))
            )

            MENU_ITEMS.forEachIndexed { i, label ->
                val y = 76 + i * 16
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
                            onSelect(i)
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
