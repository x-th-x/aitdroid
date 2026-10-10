package com.aitddemo

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.aitddemo.original.GameFiles

const val PREVIEW_ROTATION_SPEED = 0.35f // radians/sec, about 18 s per turn

private const val PAGER_LOOP_COUNT = 10_000
private val GOLD = Color(0xFF8A6D3B)
private val TEXT = Color(0xFFF2E4B8)
private const val TICK_MS = 15 // one engine tick of the keyframe animations

/**
 * The Model Viewer: every body of LISTBODY.PAK and LISTBOD2.PAK as a swipeable gallery, and a big view with the animations of LISTANIM.PAK that fit
 * the body. Bodies, animations and colours are read from the original game files when the screen opens.
 */
@Composable
fun ModelsScreen(onExit: () -> Unit) {
    val loader = rememberOriginalFiles(missing = { ModelLibraryReader.missingFiles(it) }, cacheKey = "models") { files: GameFiles -> ModelLibraryReader.read(files) }
    when (val s = loader.state) {
        is OriginalState.Loading -> Box(Modifier.fillMaxSize().background(Color.Black)) {
            BitmapText(text = "Lade Originaldateien...", scale = 2f, color = TEXT, modifier = Modifier.align(Alignment.Center))
        }
        is OriginalState.Failed -> OriginalFilesErrorScreen(s.message, loader.chooseFolder, onExit)
        is OriginalState.Ready -> ModelsContent(s.data, onExit)
    }
}

@Composable
private fun ModelsContent(library: ModelLibrary, onExit: () -> Unit) {
    var viewing by remember { mutableStateOf<ModelEntry?>(null) }
    // survives the change between list and big view, so the gallery returns to the body that was opened
    var lastIndex by remember { mutableIntStateOf(library.entries.indexOfFirst { it.pak == 0 && it.raw == 13 }.coerceAtLeast(0)) }
    BackHandler(enabled = viewing != null) { viewing = null }
    BackHandler(enabled = viewing == null) { onExit() }
    val current = viewing
    if (current == null) {
        ModelsListScreen(library, lastIndex, onSelect = { lastIndex = library.entries.indexOf(it).coerceAtLeast(0); viewing = it }, onBack = onExit)
    } else {
        ModelViewerScreen(library, current, onBack = { viewing = null })
    }
}

@Composable
private fun ModelsListScreen(library: ModelLibrary, initialPage: Int, onSelect: (ModelEntry) -> Unit, onBack: () -> Unit) {
    val entries = library.entries
    val realCount = entries.size.coerceAtLeast(1)
    val virtualCount = realCount * PAGER_LOOP_COUNT
    // HorizontalPager has no endless mode: start in the middle of a very large page range and map the page back with a modulo
    val startPage = (virtualCount / 2 / realCount) * realCount + initialPage
    val pagerState = rememberPagerState(initialPage = startPage, pageCount = { virtualCount })

    Box(modifier = Modifier.fillMaxSize()) {
        StartPictureImage(StartPicture.MENU)
        GameCanvas(modifier = Modifier.fillMaxSize()) {
            // static frames; the pager above them carries the name and the preview of each page
            Box(Modifier.offset(x = gx(10), y = gy(36)).size(gx(299), gy(16)).background(Color(0xFF4A1420).copy(alpha = 0.6f)).border(unit * 1.5f, GOLD))
            Box(Modifier.offset(x = gx(100), y = gy(60)).size(gx(120), gy(120)).background(Color.Black).border(unit * 1.5f, GOLD))
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { virtualPage ->
                val entry = entries.getOrNull(virtualPage % realCount)
                Box(modifier = Modifier.fillMaxSize()) {
                    if (entry != null) {
                        Box(Modifier.offset(x = gx(10), y = gy(36)).size(gx(299), gy(16)), contentAlignment = Alignment.Center) {
                            Box(Modifier.offset(x = unit, y = unit)) { BitmapText(text = entry.label, scale = unit.value, color = Color(0xFF3A0A10)) }
                            BitmapText(text = entry.label, scale = unit.value, color = TEXT)
                        }
                        val mesh = remember(entry) { library.mesh(entry) }
                        Box(Modifier.offset(x = gx(100), y = gy(60)).size(gx(120), gy(120)).clickable { onSelect(entry) }) {
                            ModelSpinner(vertices = mesh.vertices, triangles = mesh.triangles, rotationSpeed = PREVIEW_ROTATION_SPEED, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
        BackButton(onClick = onBack)
    }
}

@Composable
private fun ModelViewerScreen(library: ModelLibrary, entry: ModelEntry, onBack: () -> Unit) {
    val mesh = remember(entry) { library.mesh(entry) }
    val animations = remember(entry) { library.compatibleAnimations(mesh) }
    var animIndex by remember(entry) { mutableIntStateOf(-1) } // -1 = no animation
    var spinning by remember { mutableStateOf(true) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    zoom = (zoom * gestureZoom).coerceIn(0.3f, 6f)
                    panX += pan.x
                    panY += pan.y
                }
            },
    ) {
        val speed = if (spinning) PREVIEW_ROTATION_SPEED else 0f
        val anim = animations.getOrNull(animIndex)?.let { library.animation(it) }
        if (anim != null && mesh.parts != null) {
            val ticks = animTotalTicks(anim.frames, anim.groups, "viewer-${anim.number}", loop = true).coerceAtLeast(20)
            val progress by rememberInfiniteTransition(label = "anim").animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(ticks * TICK_MS, easing = LinearEasing), RepeatMode.Restart),
                label = "progress",
            )
            AnimatedModelSpinner(
                restVertices = mesh.parts.vertices,
                groups = mesh.parts.groups,
                groupOrder = mesh.parts.groupOrder,
                animFrames = anim.frames,
                animNumGroups = anim.groups,
                animCacheKey = "viewer-${anim.number}",
                triangles = mesh.triangles,
                progress = progress,
                rotationSpeed = speed,
                zoom = zoom,
                panX = panX,
                panY = panY,
                loop = true,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            ModelSpinner(vertices = mesh.vertices, triangles = mesh.triangles, rotationSpeed = speed, zoom = zoom, panX = panX, panY = panY, modifier = Modifier.fillMaxSize())
        }
        BackButton(onClick = onBack)
        Column(Modifier.align(Alignment.TopEnd).padding(12.dp)) {
            ViewerButton("Spin: ${if (spinning) "on" else "off"}") { spinning = !spinning }
            if (animations.isNotEmpty()) {
                Row {
                    ViewerButton("<") { animIndex = if (animIndex < 0) animations.size - 1 else animIndex - 1 }
                    ViewerButton(if (animIndex < 0) "Anim: none" else "Anim: ${animations[animIndex]}") { animIndex = -1 }
                    ViewerButton(">") { animIndex = if (animIndex >= animations.size - 1) -1 else animIndex + 1 }
                }
            }
            ViewerButton("Reset view") { zoom = 1f; panX = 0f; panY = 0f }
        }
        Box(Modifier.align(Alignment.TopStart).padding(12.dp).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 10.dp, vertical = 6.dp)) {
            BitmapText(text = entry.label, scale = 2f, color = TEXT)
        }
    }
}

@Composable
private fun ViewerButton(label: String, onClick: () -> Unit) {
    Box(Modifier.padding(top = 6.dp, start = 6.dp).background(Color.Black.copy(alpha = 0.5f)).border(1.dp, GOLD).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp)) {
        BitmapText(text = label, scale = 1.6f, color = TEXT)
    }
}
