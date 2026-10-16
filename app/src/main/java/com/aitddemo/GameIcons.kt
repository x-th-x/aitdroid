package com.aitddemo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** The symbols of the buttons of the game screen: small pictures of 12 x 12 big pixels in the cream of the game's text, instead of words. */
enum class GameIcon(private val rows: List<String>) {
    MENU(listOf(
        "............",
        ".##########.",
        ".##########.",
        "............",
        ".##########.",
        ".##########.",
        "............",
        ".##########.",
        ".##########.",
        "............",
        "............",
        "............",
    )),
    INVENTORY(listOf(
        "....####....",
        "...#....#...",
        "...#....#...",
        "..########..",
        ".##########.",
        ".#........#.",
        ".#...##...#.",
        ".#...##...#.",
        ".#........#.",
        ".##########.",
        "............",
        "............",
    )),
    MAP(listOf(
        "............",
        ".##########.",
        ".#..#..#..#.",
        ".#..#..#..#.",
        ".#..#..#..#.",
        ".#..#..#..#.",
        ".#..#..#..#.",
        ".#..#..#..#.",
        ".##########.",
        "............",
        "............",
        "............",
    )),
    ACTION(listOf(
        "....#.#.#...",
        "....#.#.#...",
        "..#.#.#.##..",
        "..#.#.#.##..",
        "..#.#.#.##..",
        "..#########.",
        "..#########.",
        "...########.",
        "...#######..",
        "....#####...",
        "............",
        "............",
    ));

    /** Draws the picture over the whole size of the canvas. */
    fun draw(scope: androidx.compose.ui.graphics.drawscope.DrawScope, color: Color) {
        val cell = minOf(scope.size.width, scope.size.height) / 12f
        for ((y, row) in rows.withIndex()) for ((x, c) in row.withIndex()) if (c == '#') {
            scope.drawRect(color, topLeft = Offset(x * cell, y * cell), size = Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

private val ICON_COLOR = Color(0xFFF2E4B8)
private val ICON_FRAME = Color(0xFF8A6D3B)

/** The picture of an icon, [size] wide and high. */
@Composable
fun IconGlyph(icon: GameIcon, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 28.dp) {
    Canvas(modifier = modifier.size(size)) { icon.draw(this, ICON_COLOR) }
}

/** A square button with the picture of [icon]: black, half see-through, with the gold frame of the game's menus. */
@Composable
fun IconButton(icon: GameIcon, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier.size(52.dp).background(Color.Black.copy(alpha = 0.5f)).border(2.dp, ICON_FRAME).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { IconGlyph(icon) }
}
