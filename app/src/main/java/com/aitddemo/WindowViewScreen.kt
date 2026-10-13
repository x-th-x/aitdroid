package com.aitddemo

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What Carnby sees when he examines the window of the attic with "Open/Search" (not in the original game, see [WindowView]): the painted view with the storm moving in it,
 * and a remark at the bottom. A tap or the back key closes it. [flash] gives the strength of the flash of lightning now (the storm of the house, so the thunder fits).
 */
@Composable
fun WindowViewOverlay(palette: IntArray, broken: Boolean, flash: () -> Float, onClose: () -> Unit) {
    BackHandler { onClose() }
    val scale = 2
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val openedAt = remember { System.nanoTime() }
    LaunchedEffect(broken) {
        val base = withContext(Dispatchers.Default) {
            val pic = WindowView.paint(palette, broken)
            IntArray(WindowView.W * scale * WindowView.H * scale) { pic[(it / (WindowView.W * scale) / scale) * WindowView.W + (it % (WindowView.W * scale)) / scale] }
        }
        val out = base.copyOf()
        val bmp = Bitmap.createBitmap(WindowView.W * scale, WindowView.H * scale, Bitmap.Config.ARGB_8888)
        val start = System.nanoTime()
        var last = -1_000_000_000L
        while (true) {
            val now = withFrameNanos { it }
            if (now - last < 70_000_000L && frame != null) continue
            last = now
            withContext(Dispatchers.Default) { WindowView.render(base, out, scale, (now - start) / 1_000_000_000f, flash(), broken) }
            bmp.setPixels(out, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            frame = bmp
            tick++
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {
                if (System.nanoTime() - openedAt > 600_000_000L) onClose() // not the tap that ends the search animation
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            tick // redraw whenever there is a new frame
            val bmp = frame ?: return@Canvas
            val k = minOf(size.width / bmp.width, size.height / bmp.height)
            val w = (bmp.width * k).toInt()
            val h = (bmp.height * k).toInt()
            drawImage(
                bmp.asImageBitmap(),
                srcOffset = IntOffset.Zero, srcSize = IntSize(bmp.width, bmp.height),
                dstOffset = IntOffset(((size.width - w) / 2).toInt(), ((size.height - h) / 2).toInt()), dstSize = IntSize(w, h),
                filterQuality = FilterQuality.None,
            )
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            BitmapText(
                text = if (broken) "The glass is gone. The rain blows in." else "The path to the house, the gate, the road. A car waits in the rain.",
                scale = 2f,
                color = Color(0xFFF2E4B8),
            )
        }
    }
}
