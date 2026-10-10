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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.aitddemo.music.AdlibPlayer

/**
 * Music Viewer: lists the AdLib tracks of LISTMUS.PAK (read from the original files) and plays them through the software AdLib
 * (OPL2 emulation). Tap a track to play it (it loops), tap it again to stop.
 */
@Composable
fun MusicViewerScreen(onExit: () -> Unit) {
    val loader = rememberOriginalFiles(MusicOriginalReader::missingFiles) { MusicOriginalReader.read(it) }
    when (val state = loader.state) {
        is OriginalState.Ready -> MusicList(state.data, onExit)
        is OriginalState.Failed -> OriginalFilesErrorScreen(state.message, onChooseFolder = loader.chooseFolder, onExit = onExit)
        OriginalState.Loading -> Box(Modifier.fillMaxSize().background(Color.Black)) {
            BitmapText(text = "Lade Musik...", scale = 2f, color = Color(0xFFF2E4B8), modifier = Modifier.align(Alignment.Center))
            BackButton(onClick = onExit)
        }
    }
}

private fun lengthText(seconds: Int) = "%d:%02d".format(seconds / 60, seconds % 60)

@Composable
private fun MusicList(tracks: List<MusicTrack>, onExit: () -> Unit) {
    BackHandler { onExit() }
    val player = remember { AdlibPlayer() }
    DisposableEffect(player) {
        GameMusic.stop()
        onDispose { player.stop() }
    }
    var playing by remember { mutableIntStateOf(-1) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        GameCanvas(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .offset(x = gx(30), y = gy(14))
                    .size(gx(260), gy(176))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .border(unit * 1.5f, Color(0xFF8A6D3B)),
            )
            val title = "Music Viewer"
            Box(modifier = Modifier.offset(x = unit * (160 - bitmapTextWidth(title) / 2), y = gy(20))) {
                BitmapText(text = title, scale = unit.value, color = Color(0xFFF2E4B8))
            }
            tracks.forEachIndexed { i, track ->
                val y = 42 + i * 15
                val isPlaying = playing == track.number
                val label = "Track ${i + 1}   ${lengthText(track.seconds)}   ${track.voices} voices" + if (track.rhythm) "  drums" else ""
                if (isPlaying) {
                    Box(
                        modifier = Modifier
                            .offset(x = gx(36), y = gy(y))
                            .size(gx(248), gy(15))
                            .background(Color(0xFF4A1420).copy(alpha = 0.6f)),
                    )
                }
                Box(
                    modifier = Modifier
                        .offset(x = gx(42), y = gy(y))
                        .clickable {
                            if (isPlaying) {
                                player.stop()
                                playing = -1
                            } else {
                                playing = track.number
                                player.play(track.data, loop = true)
                            }
                        },
                ) {
                    BitmapText(text = (if (isPlaying) "> " else "  ") + label, scale = unit.value, color = if (isPlaying) Color(0xFFF2E4B8) else Color(0xFF9C8B6E))
                }
            }
            val hint = "Tap a track to play, tap again to stop"
            Box(modifier = Modifier.offset(x = unit * (160 - bitmapTextWidth(hint) / 2), y = gy(172))) {
                BitmapText(text = hint, scale = unit.value, color = Color(0xFF9C8B6E))
            }
        }
        BackButton(onClick = onExit)
    }
}
