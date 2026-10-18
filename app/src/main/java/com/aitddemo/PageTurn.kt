package com.aitddemo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay

/** One step of the page turn on the screen: the edge moves 10 pixels (all.asm 0xDB06: sub [bp-8], 0xA; 0xDC16: add [bp-8], 0xA). */
private const val TURN_STEP_MS = 16L

/** The end of the shadow that goes with the edge of the turning page: halfway between the edge and x = 280 (0xDB06). */
fun pageTurnShadowEnd(edge: Int): Int = 280 - (280 - edge) / 2

/**
 * The page turn of the reader of the original (0xDACC to the next page, 0xDB8C back), as the sequence of positions of the edge: forward the edge runs from x = 260 to the left
 * until the end of its shadow has left the screen, backward from x = -540 to the right up to 250. [setEdge] shows each position for one step.
 */
suspend fun runPageTurn(forward: Boolean, setEdge: (Int) -> Unit) {
    if (forward) {
        var e = 260
        var m: Int
        do {
            setEdge(e)
            delay(TURN_STEP_MS)
            m = pageTurnShadowEnd(e)
            e -= 10
        } while (m >= -1)
    } else {
        var e = -540
        while (e < 260) {
            setEdge(e)
            delay(TURN_STEP_MS)
            e += 10
        }
    }
}

/**
 * The screen while a page turns: the old page, a dark line at the edge, a shadow of six bands behind it (colours 19 to 24 of the game's palette; each band ends halfway between
 * its start and the end of the shadow) and the new page. Forward (the next page): the old page stays left of the edge and the new one appears right of the shadow;
 * backward: the new page grows from the left up to the edge and the old one stays right of the shadow.
 */
@Composable
fun GameCanvasScope.PageTurn(edge: Int, forward: Boolean, palette: IntArray, old: @Composable () -> Unit, new: @Composable () -> Unit) {
    @Composable
    fun band(x1: Int, x2: Int, color: Int) {
        val a = x1.coerceIn(0, 320)
        val b = (x2 + 1).coerceIn(0, 320)
        if (b > a) Box(Modifier.offset(x = gx(a)).size(gx(b - a), gy(200)).background(Color(color)))
    }

    @Composable
    fun clipped(x1: Int, x2: Int, content: @Composable () -> Unit) {
        val a = x1.coerceIn(0, 320)
        val b = x2.coerceIn(0, 320)
        if (b > a) Box(Modifier.offset(x = gx(a)).size(gx(b - a), gy(200)).clipToBounds()) {
            Box(Modifier.offset(x = -gx(a))) { content() }
        }
    }

    @Composable
    fun shadow() {
        val m = pageTurnShadowEnd(edge)
        band(edge, edge, palette[16])
        // 0xD9D8: from e + 1 to m in six bands, the first one starting one pixel further right
        var a = edge + 1
        var first = true
        for (c in 19..23) {
            val mid = (m - a) / 2 + a
            band(if (first) a + 1 else a, mid, palette[c])
            first = false
            a = mid
        }
        band(a, m, palette[24])
    }

    old()
    if (forward) {
        shadow()
        clipped(pageTurnShadowEnd(edge) + 1, 320, new)
    } else {
        clipped(0, edge + 1, new)
        shadow()
    }
}
