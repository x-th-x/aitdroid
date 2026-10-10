package com.aitddemo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Shared "< Back" control (bottom-left, semi-transparent box). */
@Composable
fun BoxScope.BackButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(12.dp)
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        BitmapText(text = "< Back", scale = 2f, color = Color(0xFFF2E4B8))
    }
}

/** Four press-and-hold arrow buttons; each callback gets true while its button is held. */
@Composable
fun DPadOverlay(
    modifier: Modifier = Modifier,
    onUp: (Boolean) -> Unit,
    onDown: (Boolean) -> Unit,
    onLeft: (Boolean) -> Unit,
    onRight: (Boolean) -> Unit,
) {
    Box(modifier = modifier.size(168.dp)) {
        DPadButton("^", Modifier.align(Alignment.TopCenter), onUp)
        DPadButton("v", Modifier.align(Alignment.BottomCenter), onDown)
        DPadButton("<", Modifier.align(Alignment.CenterStart), onLeft)
        DPadButton(">", Modifier.align(Alignment.CenterEnd), onRight)
    }
}

@Composable
private fun DPadButton(label: String, modifier: Modifier, onHeld: (Boolean) -> Unit) {
    Box(
        modifier = modifier
            .size(56.dp)
            .background(Color.Black.copy(alpha = 0.5f))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    onHeld(true)
                    waitForUpOrCancellation()
                    onHeld(false)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        BitmapText(text = label, scale = 2.5f, color = Color(0xFFF2E4B8))
    }
}
