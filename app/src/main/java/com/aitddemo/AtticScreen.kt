package com.aitddemo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.aitddemo.game.Joy
import com.aitddemo.game.PlayerController
import com.aitddemo.game.Zone
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

// Attic room (stage 0, room 0): the camera photo, furniture and player are read from the user's original files at
// runtime. Fixed camera views with live zone-based camera switching (AtticCameraLogic.kt), 3D actors rendered over
// the photo through the camera matrix with background masks (AtticSceneRenderer).

/** A furniture piece read from the original files, placed in the world (object rotation as in the original, then translation). */
private fun AtticProp.toActor(): AtticActor {
    val rot = initRot(alpha, beta, gamma)
    val n = vertices.size / 3
    val w = FloatArray(n * 3)
    var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
    for (i in 0 until n) {
        val (rx, ry, rz) = rotatePoint(vertices[i * 3], vertices[i * 3 + 1], vertices[i * 3 + 2], rot)
        w[i * 3] = rx + x; w[i * 3 + 1] = ry + y; w[i * 3 + 2] = rz + z
        minX = minOf(minX, w[i * 3]); maxX = maxOf(maxX, w[i * 3]); minZ = minOf(minZ, w[i * 3 + 2]); maxZ = maxOf(maxZ, w[i * 3 + 2])
    }
    return AtticActor(w, triangles, x.toFloat(), y.toFloat(), z.toFloat(), (minX / 10).toInt(), (maxX / 10).toInt(), (minZ / 10).toInt(), (maxZ / 10).toInt(), lines, spheres)
}

/** Entry point: reads the room from the original game files, shows an error message when that is not possible. */
@Composable
fun AtticScreen(onExit: () -> Unit, controlMode: ControlMode = ControlMode.STEERING, developerMode: Boolean = false) {
    val loader = rememberOriginalFiles(AtticOriginalReader::missingFiles, cacheKey = "attic") { files -> AtticOriginalReader.read(files) }
    when (val state = loader.state) {
        is OriginalState.Ready -> AtticRoom(state.data, onExit, controlMode, developerMode)
        is OriginalState.Failed -> OriginalFilesErrorScreen(state.message, onChooseFolder = loader.chooseFolder, onExit = onExit)
        OriginalState.Loading -> Box(Modifier.fillMaxSize().background(Color.Black)) {
            BitmapText(text = "Lade Originaldateien...", scale = 2f, color = Color(0xFFF2E4B8), modifier = Modifier.align(Alignment.Center))
            BackButton(onClick = onExit)
        }
    }
}

@Composable
private fun AtticRoom(world: AtticOriginal, onExit: () -> Unit, controlMode: ControlMode, developerMode: Boolean) {
    BackHandler { onExit() }
    remember(world) { AtticData.install(world) } // collision boxes and masks of this room
    val cameras = world.cameras
    // the attic plays the first track of LISTMUS.PAK in a loop (the intro music stops); leaving the attic stops it
    androidx.compose.runtime.DisposableEffect(world) {
        GameMusic.setLooping(true, AtticOriginalReader.ATTIC_MUSIC, world.music) // the attic loops its music
        GameMusic.start(AtticOriginalReader.ATTIC_MUSIC, world.music) // stops the intro music and plays the first track of LISTMUS.PAK
        onDispose { GameMusic.stop() }
    }

    // The player is the original body 12 (Carnby) driven by the original actor logic (animations from LISTANIM.PAK, collision with the room's boxes).
    val player = world.player
    val sounds = remember(world) { IntroSounds(world.samples) }
    androidx.compose.runtime.DisposableEffect(sounds) { onDispose { sounds.release() } }
    val startNanos = remember { System.nanoTime() }
    val controller = remember(world) {
        fun zones(a: IntArray) = List(a.size / AtticData.HARDCOL_STRIDE) { i ->
            val o = i * AtticData.HARDCOL_STRIDE
            Zone(a[o], a[o + 1], a[o + 2], a[o + 3], a[o + 4], a[o + 5], a[o + 6], a[o + 7])
        }
        PlayerController(player, zones(world.hardcols), zones(world.zones), 0) { sounds.play(it) }
    }
    var playerX by remember { mutableFloatStateOf(player.spawnX.toFloat()) }
    var playerZ by remember { mutableFloatStateOf(player.spawnZ.toFloat()) }
    var frameTick by remember { mutableIntStateOf(0) }
    var cameraIndex by remember { mutableIntStateOf(ATTIC_INITIAL_CAMERA_INDEX) }

    // Floating joystick state: drag vector in stick-radius units, (-1..1) on each axis.
    var stickX by remember { mutableFloatStateOf(0f) }
    var stickY by remember { mutableFloatStateOf(0f) }
    var stickBase by remember { mutableStateOf<Offset?>(null) }
    var stickThumb by remember { mutableStateOf<Offset?>(null) }

    // D-Pad mode: fixed arrows with the original tank controls (forward/backward, turn in place).
    var dpadUp by remember { mutableStateOf(false) }
    var dpadDown by remember { mutableStateOf(false) }
    var dpadLeft by remember { mutableStateOf(false) }
    var dpadRight by remember { mutableStateOf(false) }
    val dpadTurnRateRadPerMs = 0.0028f

    val furniture = remember(world) { world.props.map { it.toActor() } }
    val renderer = remember { AtticSceneRenderer() }
    val backgrounds = remember(world) {
        // photo of each camera, scaled once to the renderer's resolution (nearest neighbour keeps the pixel look)
        world.photos.map { argb ->
            val bmp = android.graphics.Bitmap.createBitmap(argb, 320, 200, android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Bitmap.createScaledBitmap(bmp, AtticSceneRenderer.W, AtticSceneRenderer.H, false)
        }
    }
    val blackBackground = remember { android.graphics.Bitmap.createBitmap(AtticSceneRenderer.W, AtticSceneRenderer.H, android.graphics.Bitmap.Config.ARGB_8888).also { it.eraseColor(android.graphics.Color.BLACK) } }

    // Toggle: hide the camera photo and show only the 3D actors on black.
    var showBackground by remember { mutableStateOf(true) }

    // Stick mode: the stick gives the screen direction to walk in; the body turns towards it with the engine tank keys.
    val steerTurnRateRadPerMs = 0.005f
    val stickDeadzone = 0.15f

    // The stick direction is rotated by the active camera's own yaw, so "up" always means away from the camera.
    val angleUnitToRad = 2f * PI.toFloat() / 1024f

    LaunchedEffect(controller) {
        while (true) {
            val nanos = withFrameNanos { it }
            val timer = ((nanos - startNanos) / 16_666_667L).toInt() // engine ticks, 60 per second
            var joy = 0
            var run = false
            when (controlMode) {
                ControlMode.STEERING -> if (hypot(stickX, stickY) > stickDeadzone) {
                    // stick up = away from the camera, right = screen right, in the active camera's own ground directions
                    val camBeta = cameras[cameraIndex].angleBeta * angleUnitToRad
                    val fwd = -stickY
                    val dirX = sin(camBeta) * fwd + cos(camBeta) * stickX
                    val dirZ = cos(camBeta) * fwd - sin(camBeta) * stickX
                    // turn the body towards that direction with the engine's tank keys: turn left/right while it is off, walk when it faces it
                    val target = IntroTimeline.headingOf(dirX, dirZ)
                    var diff = (target - (controller.actor.beta and 0x3FF)) % 1024f
                    if (diff > 512f) diff -= 1024f
                    if (diff < -512f) diff += 1024f
                    if (diff > 40f) joy = joy or Joy.LEFT
                    if (diff < -40f) joy = joy or Joy.RIGHT
                    if (kotlin.math.abs(diff) < 300f) joy = joy or Joy.FORWARD
                    run = hypot(stickX, stickY) > 0.92f
                }
                ControlMode.DPAD -> {
                    if (dpadUp) joy = joy or Joy.FORWARD
                    if (dpadDown) joy = joy or Joy.BACKWARD
                    if (dpadLeft) joy = joy or Joy.LEFT
                    if (dpadRight) joy = joy or Joy.RIGHT
                }
            }
            controller.update(joy, timer, run)
            playerX = controller.actor.renderX.toFloat()
            playerZ = controller.actor.renderZ.toFloat()
            // the room's camera follows the player by the engine's zone rule (checkIfCameraChangeIsRequired)
            cameraIndex = resolveAtticCamera(playerX, playerZ, cameraIndex, cameras)
            frameTick++
        }
    }

    val camera = cameras[cameraIndex]
    val playerActor = run {
        frameTick // the pose changes every frame
        val zv = controller.actor.zv
        AtticActor(
            controller.worldVertices(), player.triangles,
            controller.actor.renderX.toFloat(), 0f, controller.actor.renderZ.toFloat(),
            zv[0] / 10, zv[1] / 10, zv[4] / 10, zv[5] / 10,
            player.lines, player.spheres,
        )
    }
    // one frame of the room: photo, furniture and player through the camera matrix, masks over them (AtticSceneRenderer)
    val roomImage = run {
        renderer.render(camera, if (showBackground) backgrounds[cameraIndex] else blackBackground, furniture + playerActor)
        renderer.bitmap.asImageBitmap()
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        PlateAspectBox {
            Image(bitmap = roomImage, contentDescription = null, contentScale = ContentScale.Fit, filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize())
        }

        // Floating joystick: appears where the thumb touches; drag distance is clamped and normalized to -1..1.
        val stickRadiusPx = 70f
        if (controlMode == ControlMode.STEERING) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                stickBase = offset
                                stickThumb = offset
                            },
                            onDragEnd = {
                                stickBase = null
                                stickThumb = null
                                stickX = 0f
                                stickY = 0f
                            },
                            onDragCancel = {
                                stickBase = null
                                stickThumb = null
                                stickX = 0f
                                stickY = 0f
                            }
                        ) { change, _ ->
                            val base = stickBase ?: return@detectDragGestures
                            val raw = change.position - base
                            val dist = hypot(raw.x, raw.y)
                            val clamped = if (dist > stickRadiusPx) raw * (stickRadiusPx / dist) else raw
                            stickThumb = base + clamped
                            stickX = (clamped.x / stickRadiusPx).coerceIn(-1f, 1f)
                            stickY = (clamped.y / stickRadiusPx).coerceIn(-1f, 1f)
                        }
                    }
            ) {
                val base = stickBase
                val thumb = stickThumb
                if (base != null && thumb != null) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawCircle(color = Color(0x55FFFFFF), radius = stickRadiusPx, center = base, style = Stroke(width = 4f))
                        drawCircle(color = Color(0xAAFFFFFF), radius = 28f, center = thumb)
                    }
                }
            }
        }

        // Developer mode: world position and active camera index.
        if (developerMode) {
            Box(modifier = Modifier.align(Alignment.TopStart).padding(12.dp).background(Color.Black.copy(alpha = 0.5f)).padding(6.dp)) {
                BitmapText(
                    text = "World: (${playerX.toInt()}, ${playerZ.toInt()}) cam=$cameraIndex",
                    scale = 1.6f,
                    color = Color(0xFFF2E4B8)
                )
            }
        }

        if (controlMode == ControlMode.DPAD) {
            DPadOverlay(
                modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 24.dp, end = 16.dp),
                onUp = { dpadUp = it },
                onDown = { dpadDown = it },
                onLeft = { dpadLeft = it },
                onRight = { dpadRight = it }
            )
        }

        BackButton(onClick = onExit)

        // Same visual style as BackButton (semi-transparent box, original bitmap font), opposite
        // corner (top-right) so it doesn't compete with Back/the joystick for space.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable { showBackground = !showBackground }
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            BitmapText(text = if (showBackground) "Hintergrund: an" else "Hintergrund: aus", scale = 1.6f, color = Color(0xFFF2E4B8))
        }
    }
}
