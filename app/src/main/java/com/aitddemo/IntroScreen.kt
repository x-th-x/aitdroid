package com.aitddemo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

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

/** Entry point: reads the intro from the original game files, shows an error message when that is not possible. */
@Composable
fun IntroScreen(onFinished: () -> Unit) {
    val loader = rememberOriginalFiles(IntroOriginalReader::missingFiles) { IntroOriginalReader.read(it) }
    when (val state = loader.state) {
        is OriginalState.Ready -> IntroPlayer(state.data, onFinished)
        is OriginalState.Failed -> OriginalFilesErrorScreen(state.message, onChooseFolder = loader.chooseFolder, onExit = onFinished)
        OriginalState.Loading -> Box(Modifier.fillMaxSize().background(Color.Black)) {
            BitmapText(text = "Lade Originaldateien...", scale = 2f, color = Color(0xFFF2E4B8), modifier = Modifier.align(Alignment.Center))
            BackButton(onClick = onFinished)
        }
    }
}

private enum class Shot { POND, ROAD_A, FROG_PICTURE, ROAD_B, MANSION, COURTYARD }

private const val WALK_KEY = "runtime_intro_walk_245"

@Composable
private fun IntroPlayer(data: IntroOriginal, onFinished: () -> Unit) {
    // read the attic in the background while the intro plays, so it opens without a pause afterwards
    rememberOriginalFiles(AtticOriginalReader::missingFiles, cacheKey = "attic") { files -> AtticOriginalReader.read(files) }
    val timeline = remember(data) { IntroTimeline(data) }
    val renderer = remember { AtticSceneRenderer() }
    val sounds = remember(data) { IntroSounds(data.samples) }
    DisposableEffect(sounds) { onDispose { sounds.release() } }
    val pictures = remember(data) {
        fun scaled(argb: IntArray) = android.graphics.Bitmap.createScaledBitmap(
            android.graphics.Bitmap.createBitmap(argb, 320, 200, android.graphics.Bitmap.Config.ARGB_8888), AtticSceneRenderer.W, AtticSceneRenderer.H, false,
        )
        mapOf(
            Shot.POND to scaled(data.pond.picture), Shot.ROAD_A to scaled(data.road.picture), Shot.ROAD_B to scaled(data.road.picture), Shot.FROG_PICTURE to scaled(data.frogPicture),
            Shot.MANSION to scaled(data.mansion.picture), Shot.COURTYARD to scaled(data.courtyard.picture),
        )
    }
    val shots = remember(timeline) {
        listOf(
            Shot.POND to IntroTimeline.POND_MS, Shot.ROAD_A to timeline.frogPictureAtMs, Shot.FROG_PICTURE to timeline.frogPictureMs,
            Shot.ROAD_B to (timeline.roadMs - timeline.frogPictureAtMs).coerceAtLeast(0L),
            Shot.MANSION to timeline.mansionMs, Shot.COURTYARD to timeline.courtyardMs,
        )
    }

    // The intro music is the original AdLib track, played by the software AdLib (OPL2 emulation); it loops until the intro ends.
    // It is the game's music: it goes on in the attic (see GameMusic) and is not looped, as in the original engine.
    remember(data) {
        GameMusic.setLooping(false, IntroOriginalReader.INTRO_MUSIC, data.music)
        GameMusic.start(IntroOriginalReader.INTRO_MUSIC, data.music)
    }
    BackHandler { onFinished() }

    var index by remember { mutableIntStateOf(0) }
    var elapsedMs by remember { mutableFloatStateOf(0f) }
    // sounds waiting for their time must survive the change to the next shot (the horns sound exactly at the end of the road shot)
    val soundScope = rememberCoroutineScope()

    LaunchedEffect(index) {
        val (shot, duration) = shots[index]
        val list = when (shot) {
            Shot.POND -> timeline.pondSounds
            // the close-up stops the game for its length: sounds of the road shot after it are counted from its end
            Shot.ROAD_A -> timeline.roadSounds.filter { it.timeMs < timeline.frogPictureAtMs }
            Shot.ROAD_B -> timeline.roadSounds.filter { it.timeMs >= timeline.frogPictureAtMs }.map { TimedSound(it.timeMs - timeline.frogPictureAtMs, it.sample) }
            Shot.FROG_PICTURE -> listOf(TimedSound(0, IntroTimeline.SAMPLE_FROG))
            Shot.MANSION -> timeline.mansionSounds
            Shot.COURTYARD -> timeline.courtyardSounds
        }
        for (s in list) {
            if (s.timeMs <= 0L) sounds.play(s.sample) else soundScope.launch { delay(s.timeMs); sounds.play(s.sample) }
        }
        elapsedMs = 0f
        val start = androidx.compose.runtime.withFrameNanos { it }
        while (elapsedMs < duration) {
            val now = androidx.compose.runtime.withFrameNanos { it }
            elapsedMs = ((now - start) / 1_000_000f).coerceAtMost(duration.toFloat())
        }
        if (index < shots.size - 1) index++ else onFinished()
    }

    // Swipe up to skip.
    val density = LocalDensity.current
    val swipeUpThresholdPx = with(density) { 80.dp.toPx() }
    var swipeAccumPx by remember { mutableFloatStateOf(0f) }
    var swipeTriggered by remember { mutableStateOf(false) }

    val shot = shots[index].first
    val frame = run {
        val actors: List<AtticActor> = when (shot) {
            Shot.POND -> listOf(carActor(data, timeline, timeline.carSpeed * elapsedMs))
            Shot.ROAD_A -> roadActors(data, timeline, elapsedMs)
            Shot.ROAD_B -> roadActors(data, timeline, timeline.frogPictureAtMs + elapsedMs)
            Shot.MANSION -> listOf(driverActor(data, timeline, elapsedMs))
            Shot.COURTYARD -> listOf(driverActor(data, timeline, timeline.mansionMs + elapsedMs))
            Shot.FROG_PICTURE -> emptyList()
        }
        val cam = when (shot) {
            Shot.POND -> data.pond
            Shot.ROAD_A, Shot.ROAD_B -> data.road
            Shot.MANSION -> data.mansion
            Shot.COURTYARD -> data.courtyard
            Shot.FROG_PICTURE -> null
        }
        if (cam != null) renderer.render(cam.camera, pictures.getValue(shot), actors, cam.masks) else renderer.renderPicture(pictures.getValue(shot))
        renderer.bitmap.asImageBitmap()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { swipeAccumPx = 0f; swipeTriggered = false },
                    onVerticalDrag = { _, dragAmount ->
                        if (swipeTriggered) return@detectVerticalDragGestures
                        swipeAccumPx += dragAmount
                        if (swipeAccumPx < -swipeUpThresholdPx) {
                            swipeTriggered = true
                            onFinished()
                        }
                    },
                )
            },
    ) {
        PlateAspectBox {
            Image(bitmap = frame, contentDescription = null, contentScale = ContentScale.Fit, filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize())
        }
        if (shot == Shot.POND) {
            // small copyright line (text 2 of ENGLISH.PAK) as in the original
            Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)) {
                BitmapText(text = data.copyright, scale = 0.9f, color = Color(0xB3F2E4B8))
            }
        }
        BackButton(onClick = onFinished)
    }
}

/** World position of a body at ([x], [z]) turned by [beta] (engine angle): rotation about Y as in the original, then translation. */
private fun placed(vertices: FloatArray, triangles: IntArray, x: Float, z: Float, beta: Float, lines: IntArray = IntArray(0), spheres: IntArray = IntArray(0), y: Float = 0f): AtticActor {
    val a = beta * 2.0 * Math.PI / 1024.0
    val c = cos(a).toFloat()
    val s = sin(a).toFloat()
    val n = vertices.size / 3
    val w = FloatArray(n * 3)
    var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
    for (i in 0 until n) {
        val vx = vertices[i * 3]
        val vz = vertices[i * 3 + 2]
        w[i * 3] = vx * c - vz * s + x
        w[i * 3 + 1] = vertices[i * 3 + 1] + y
        w[i * 3 + 2] = vx * s + vz * c + z
        minX = minOf(minX, w[i * 3]); maxX = maxOf(maxX, w[i * 3]); minZ = minOf(minZ, w[i * 3 + 2]); maxZ = maxOf(maxZ, w[i * 3 + 2])
    }
    return AtticActor(w, triangles, x, y, z, (minX / 10).toInt(), (maxX / 10).toInt(), (minZ / 10).toInt(), (maxZ / 10).toInt(), lines, spheres)
}

private const val FROG_HOP_KEY = "runtime_frog_hop"
private const val FROG_REST_KEY = "runtime_frog_rest"

/** The road shot at [roadMs] of game time: the car on its drive, and the frog once it has started to hop. */
private fun roadActors(data: IntroOriginal, timeline: IntroTimeline, roadMs: Float): List<AtticActor> {
    val list = ArrayList<AtticActor>()
    list.add(carActor(data, timeline, timeline.pondEndDistance + timeline.carSpeed * roadMs))
    if (roadMs >= timeline.frogAppearsMs) {
        val f = data.frog
        val st = timeline.frogAt(roadMs - timeline.frogAppearsMs)
        val posed = if (st.rest) {
            animatedVertices(f.parts.vertices, f.parts.groups, f.parts.groupOrder, f.rest, f.restGroups, FROG_REST_KEY, 0f, false)
        } else {
            animatedVertices(f.parts.vertices, f.parts.groups, f.parts.groupOrder, f.hop, f.hopGroups, FROG_HOP_KEY, st.progress, false)
        }
        list.add(placed(posed, f.triangles, st.x, st.z, st.beta, f.lines, f.spheres, f.y.toFloat()))
    }
    return list
}

private fun carActor(data: IntroOriginal, timeline: IntroTimeline, distance: Float): AtticActor {
    val st = timeline.carAt(distance)
    return placed(data.car.vertices, data.car.triangles, st.x, st.z, st.beta, data.car.lines, data.car.spheres)
}

/** The walking driver after [ms] of walking. */
private fun driverActor(data: IntroOriginal, timeline: IntroTimeline, ms: Float): AtticActor {
    val st = timeline.driverAt(ms)
    val w = data.carnby
    val loopTicks = animTotalTicks(w.frames, w.groups, WALK_KEY, loop = true).toFloat()
    val posed = animatedVertices(w.parts.vertices, w.parts.groups, w.parts.groupOrder, w.frames, w.groups, WALK_KEY, timeline.walkPhase(ms, loopTicks), true)
    return placed(posed, w.triangles, st.x, st.z, st.beta, w.lines, w.spheres)
}
