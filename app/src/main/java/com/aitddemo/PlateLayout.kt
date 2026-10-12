package com.aitddemo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds

// All original camera plates are 320x200. Overlays are positioned as fractions of that plate, so the plate is shown in a box with
// exactly its aspect ratio (letterboxed in the screen), and children are clipped to it.
private const val PLATE_ASPECT = 320f / 200f

/** Wraps [content] in a box with exactly the 320x200 plate's aspect ratio, centered in the available space; children are clipped to it. */
@Composable
fun PlateAspectBox(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.aspectRatio(PLATE_ASPECT).fillMaxSize().clipToBounds(), content = content)
    }
}

/** Positions [content] at a region given in fractions (0..1) of the box it is placed in; use it as a direct child of [PlateAspectBox]. */
@Composable
fun PlateRegion(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .offset(x = maxWidth * left, y = maxHeight * top)
                .size(maxWidth * width, maxHeight * height),
            content = content,
        )
    }
}
