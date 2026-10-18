package com.aitddemo

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.aitddemo.original.u8
import kotlinx.coroutines.delay

/** The title stays for 300 ticks before the credits begin (all.asm 0x1409: cmp ax, 0x12C). */
private const val TITLE_MS = 5000L

/** A picture of the slide show stays for 180 ticks (0x12FF: cmp ax, 0xB4), after its fade in of 8 ticks and before its fade out of 16 ticks (0x12D5, 0x1356). */
private const val SLIDE_MS = 3000L
private const val FADE_IN_MS = 130
private const val FADE_OUT_MS = 270

/**
 * What the original does when the menu has been left alone for 900 ticks (all.asm 0x1C94: cmp ax, 0x384; 0x0A88): the title picture, the credits as a book, and then a slide
 * show of 15 pictures (the title and the 14 pictures of PRESENT.PAK), each faded in and out. A tap (any key in the original) ends all of it and returns to the menu.
 */
@Composable
fun AttractScreen(onDone: () -> Unit) {
    val o = StartAssets.original
    if (o == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    var phase by remember { mutableIntStateOf(0) }
    when (phase) {
        0 -> {
            Box(Modifier.fillMaxSize().background(Color.Black).clickable { onDone() }) { StartPictureImage(StartPicture.TITLE) }
            LaunchedEffect(Unit) { delay(TITLE_MS); phase = 1 }
        }
        1 -> CreditsScreen(onDone = { phase = 2 }, onTap = onDone)
        else -> SlideShow(onDone)
    }
}

/** A picture of PRESENT.PAK: 770 bytes of header (the letters "VP" and the colour table of 8 bit per channel), then 320 x 200 pixels. */
private fun presentPicture(d: ByteArray): ImageBitmap {
    val start = d.size - 64000
    val argb = IntArray(64000) { i ->
        val c = d.u8(start + i)
        0xFF000000.toInt() or (d.u8(2 + c * 3) shl 16) or (d.u8(3 + c * 3) shl 8) or d.u8(4 + c * 3)
    }
    return Bitmap.createBitmap(argb, 320, 200, Bitmap.Config.ARGB_8888).asImageBitmap()
}

@Composable
private fun SlideShow(onDone: () -> Unit) {
    val o = StartAssets.original ?: return
    val count = 1 + o.present.size
    var index by remember { mutableIntStateOf(0) }
    val alpha = remember { Animatable(0f) }
    val bitmap = remember(index) { if (index == 0) StartAssets.picture(o.title) else presentPicture(o.present[index - 1]) }
    LaunchedEffect(Unit) {
        for (i in 0 until count) {
            index = i
            alpha.snapTo(0f)
            alpha.animateTo(1f, tween(FADE_IN_MS))
            delay(SLIDE_MS)
            alpha.animateTo(0f, tween(FADE_OUT_MS))
        }
        onDone()
    }
    Box(Modifier.fillMaxSize().background(Color.Black).clickable { onDone() }) {
        Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Fit, alpha = alpha.value, modifier = Modifier.fillMaxSize())
    }
}
