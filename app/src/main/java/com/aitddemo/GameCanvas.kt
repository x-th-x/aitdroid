package com.aitddemo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/**
 * The original game always renders to a fixed 320x200 logical screen. This lays out that same
 * coordinate space (letterboxed to fit, like the background images) so menu/text positions can
 * be expressed in original game units and stay correct on any phone screen size.
 */
@Composable
fun GameCanvas(modifier: Modifier = Modifier, content: @Composable GameCanvasScope.() -> Unit) {
    BoxWithConstraints(modifier = modifier) {
        val fitHeight = maxWidth * (200f / 320f)
        val boxWidth = if (fitHeight <= maxHeight) maxWidth else maxHeight * (320f / 200f)
        val boxHeight = boxWidth * (200f / 320f)
        val unit = boxWidth / 320f
        val scope = GameCanvasScope(unit)

        Box(modifier = Modifier.align(Alignment.Center).size(boxWidth, boxHeight)) {
            scope.content()
        }
    }
}

class GameCanvasScope(val unit: Dp) {
    fun gx(v: Int): Dp = unit * v
    fun gy(v: Int): Dp = unit * v
}
