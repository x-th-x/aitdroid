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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.dp
import com.aitddemo.game.AtticGame
import com.aitddemo.game.GameObject
import com.aitddemo.game.GameUi
import com.aitddemo.game.Joy
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// Attic room (stage 0, room 0): the camera photo, the objects and the player are read from the user's original files at
// runtime. The life scripts of the original run on the objects (AtticGame); the objects are drawn through the camera matrix
// over the photo with background masks (AtticSceneRenderer). Fixed camera views switch live by the room's zones (AtticCameraLogic.kt).

/** The screen's side of [GameUi]: messages, sounds, music. */
private class AtticUi(private val world: AtticOriginal, private val sounds: IntroSounds) : GameUi {
    /** Up to five messages, the first slot at the bottom (as in the original); a message that is shown again starts over. */
    val messages = arrayOfNulls<MessageSlot>(5)
    var gameOverShown by mutableStateOf(false)
    private var music = AtticOriginalReader.ATTIC_MUSIC

    override fun message(text: String) {
        val now = System.nanoTime()
        val same = messages.indexOfFirst { it?.text == text }
        val slot = if (same >= 0) same else messages.indexOfFirst { it == null }
        if (slot >= 0) messages[slot] = MessageSlot(text, now)
    }

    /** Forgets the messages whose time is over. */
    fun expire(now: Long) {
        for (i in messages.indices) if (messages[i]?.let { now - it.startNanos > MESSAGE_NANOS } == true) messages[i] = null
    }

    override fun playSample(number: Int) = sounds.play(number)

    override fun music(number: Int, loop: Boolean) {
        if (number == music) return
        val data = world.musicTrack(number) ?: return
        music = number
        GameMusic.setLooping(loop, number, data)
        GameMusic.start(number, data)
    }

    override fun gameOver() {
        gameOverShown = true
    }

    private companion object {
        const val MESSAGE_NANOS = 2_800_000_000L
    }
}

/** An object of the game as something to draw: its posed vertices turned by the object's angles and moved to its place. */
private fun GameObject.toActor(minX: Int, maxX: Int, minZ: Int, maxZ: Int): AtticActor? {
    val act = actor ?: return null
    val local = posedVertices() ?: return null
    val asset = asset ?: return null
    if (!w.active || w.body < 0) return null
    val x = act.renderX
    val y = act.renderY
    val z = act.renderZ
    // creatures that appear later by script are parked outside the room (below the floor, beyond the walls)
    if (x < minX || x > maxX || z < minZ || z > maxZ || y > 500) return null
    val rot = initRot(w.alpha, act.beta and 0x3FF, w.gamma)
    val n = local.size / 3
    val out = FloatArray(n * 3)
    var lowX = Float.MAX_VALUE; var highX = -Float.MAX_VALUE; var lowZ = Float.MAX_VALUE; var highZ = -Float.MAX_VALUE
    for (i in 0 until n) {
        val (rx, ry, rz) = rotatePoint(local[i * 3], local[i * 3 + 1], local[i * 3 + 2], rot)
        out[i * 3] = rx + x; out[i * 3 + 1] = ry + y; out[i * 3 + 2] = rz + z
        lowX = minOf(lowX, out[i * 3]); highX = maxOf(highX, out[i * 3]); lowZ = minOf(lowZ, out[i * 3 + 2]); highZ = maxOf(highZ, out[i * 3 + 2])
    }
    val zv = act.zv
    // figures (articulated bodies) use their body box for the background masks, furniture the box of its vertices
    return if (asset.parts != null) {
        AtticActor(out, asset.triangles, x.toFloat(), y.toFloat(), z.toFloat(), zv[0] / 10, zv[1] / 10, zv[4] / 10, zv[5] / 10, asset.lines, asset.spheres)
    } else {
        AtticActor(out, asset.triangles, x.toFloat(), y.toFloat(), z.toFloat(), (lowX / 10).toInt(), (highX / 10).toInt(), (lowZ / 10).toInt(), (highZ / 10).toInt(), asset.lines, asset.spheres)
    }
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
    remember(world) { AtticData.install(world) } // masks of this room
    val cameras = world.cameras
    // the attic plays the first track of LISTMUS.PAK in a loop (the intro music stops); leaving the attic stops it
    DisposableEffect(world) {
        GameMusic.setLooping(true, AtticOriginalReader.ATTIC_MUSIC, world.music) // the attic loops its music
        GameMusic.start(AtticOriginalReader.ATTIC_MUSIC, world.music) // stops the intro music and plays the first track of LISTMUS.PAK
        onDispose { GameMusic.stop() }
    }

    val sounds = remember(world) { IntroSounds { world.game.sample(it) } }
    DisposableEffect(sounds) { onDispose { sounds.release() } }
    val ui = remember(world) { AtticUi(world, sounds) }
    val game = remember(world) { AtticGame(world.game, ui) }
    val bounds = remember(world) {
        val h = world.game.hardcols
        intArrayOf(h.minOf { it.x1 }, h.maxOf { it.x2 }, h.minOf { it.z1 }, h.maxOf { it.z2 })
    }
    val startNanos = remember { System.nanoTime() }
    var frameTick by remember { mutableIntStateOf(0) }
    var cameraIndex by remember { mutableIntStateOf(ATTIC_INITIAL_CAMERA_INDEX) }
    var playerX by remember { mutableFloatStateOf(0f) }
    var playerZ by remember { mutableFloatStateOf(0f) }

    // the found window and the inventory stop the game
    var foundItem by remember { mutableIntStateOf(-1) }
    var inventoryOpen by remember { mutableStateOf(false) }
    var actionHeld by remember { mutableStateOf(false) }

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
    val stickDeadzone = 0.15f

    // The stick direction is rotated by the active camera's own yaw, so "up" always means away from the camera.
    val angleUnitToRad = 2f * PI.toFloat() / 1024f

    LaunchedEffect(game) {
        var gameClock = 0
        var lastTimer = 0
        while (true) {
            val nanos = withFrameNanos { it }
            val timer = ((nanos - startNanos) / 16_666_667L).toInt() // engine ticks, 60 per second
            val paused = foundItem >= 0 || inventoryOpen || ui.gameOverShown
            if (!paused) gameClock += (timer - lastTimer).coerceAtLeast(0)
            lastTimer = timer
            ui.expire(System.nanoTime())
            if (!paused) {
                val player = game.player?.actor
                var joy = 0
                var run = false
                when (controlMode) {
                    ControlMode.STEERING -> if (player != null && hypot(stickX, stickY) > stickDeadzone) {
                        // stick up = away from the camera, right = screen right, in the active camera's own ground directions
                        val camBeta = cameras[cameraIndex].angleBeta * angleUnitToRad
                        val fwd = -stickY
                        val dirX = sin(camBeta) * fwd + cos(camBeta) * stickX
                        val dirZ = cos(camBeta) * fwd - sin(camBeta) * stickX
                        // turn the body towards that direction with the engine's tank keys: turn left/right while it is off, walk when it faces it
                        val target = IntroTimeline.headingOf(dirX, dirZ)
                        var diff = (target - (player.beta and 0x3FF)) % 1024f
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
                game.tick(gameClock, joy, actionHeld, run)
                if (game.pendingFound >= 0) foundItem = game.pendingFound
                game.player?.actor?.let {
                    playerX = it.renderX.toFloat()
                    playerZ = it.renderZ.toFloat()
                }
                // the room's camera follows the player by the engine's zone rule (checkIfCameraChangeIsRequired)
                cameraIndex = resolveAtticCamera(playerX, playerZ, cameraIndex, cameras)
            }
            frameTick++
        }
    }

    val camera = cameras[cameraIndex]
    // one frame of the room: photo, objects and player through the camera matrix, masks over them (AtticSceneRenderer)
    val roomImage = run {
        frameTick // the poses change every frame
        val actors = game.objects.mapNotNull { it.toActor(bounds[0], bounds[1], bounds[2], bounds[3]) }
        renderer.render(camera, if (showBackground) backgrounds[cameraIndex] else blackBackground, actors)
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

        // the action key of the original (Enter): held while the finger is down; what it does is chosen in the inventory
        HoldButton(
            label = "Action",
            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = if (controlMode == ControlMode.DPAD) 24.dp else 40.dp, end = if (controlMode == ControlMode.DPAD) 200.dp else 24.dp),
            onHeld = { actionHeld = it },
        )

        BackButton(onClick = onExit)

        if (game.allowInventory && game.inventory.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 120.dp, bottom = 12.dp)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable { inventoryOpen = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                BitmapText(text = "Inventory", scale = 2f, color = Color(0xFFF2E4B8))
            }
        }

        // Same visual style as BackButton, opposite corner (top-right).
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

        frameTick // the messages fade every frame
        if (ui.messages.any { it != null }) MessageLines(ui.messages, System.nanoTime(), world.game.palette)

        if (foundItem >= 0) {
            FoundWindow(game, foundItem) { take ->
                game.resolveFound(take)
                foundItem = -1
            }
        }
        if (inventoryOpen) {
            InventoryOverlay(game, onClose = { inventoryOpen = false }) { item, action ->
                inventoryOpen = false
                game.useItem(item, action)
            }
        }
        if (ui.gameOverShown) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).clickable { onExit() }, contentAlignment = Alignment.Center) {
                BitmapText(text = "Game Over", scale = 4f, color = Color(0xFFF2E4B8))
            }
        }
    }
}
