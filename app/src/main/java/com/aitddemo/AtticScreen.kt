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
import com.aitddemo.game.Effect
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
    var exitShown by mutableStateOf(false)
    var endShown by mutableStateOf(false)

    /** A full screen picture of the game being shown (from PICTURE) and until when (engine ticks of the screen clock). */
    var picture by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    var pictureNanos = 0L
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

    private var pitchPercent = 0
    private val random = java.util.Random()

    /** RND_FREQ: a sample varies its speed by up to half of the given percentage either way. */
    private fun pitch(): Float = if (pitchPercent == 0) 1f else 1f + (random.nextFloat() * 2f - 1f) * pitchPercent / 200f

    override fun playSample(number: Int) = sounds.play(number, pitch())

    override fun playSampleThen(first: Int, then: Int, repeat: Boolean) = sounds.play(first, pitch(), then = then, thenLoop = repeat)

    override fun stopSamples() = sounds.stopAll()

    override fun randomFrequency(percent: Int) {
        pitchPercent = percent
    }

    /** The music that follows the one that plays now, -1 = none. */
    private var queuedMusic = -1

    override fun nextMusic(number: Int) {
        if (!GameMusic.playing) {
            music(number, true)
        } else {
            // the music that plays now runs to its end instead of repeating, then the next one starts
            queuedMusic = number
            GameMusic.setLooping(false, music, world.musicTrack(music) ?: return)
        }
    }

    /** Called every tick: starts the queued music when the one before it has ended. */
    fun pollMusic() {
        if (queuedMusic >= 0 && !GameMusic.playing) {
            val n = queuedMusic
            queuedMusic = -1
            music = -1
            music(n, true)
        }
    }

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

    override fun leftRoom(stage: Int, room: Int) {
        exitShown = true
    }

    override fun endSequence() {
        endShown = true
    }

    override fun picture(index: Int, ticks: Int, sample: Int) {
        val argb = world.game.picture(index) ?: return
        picture = StartAssets.picture(argb)
        pictureNanos = System.nanoTime() + ticks * 16_666_667L
        if (sample >= 0) sounds.play(sample)
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
    // (a creature on its way in walks outside the room first and is seen all the time)
    if (w.trackMode != 3 && (x < minX || x > maxX || z < minZ || z > maxZ) || y > 500) return null
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

/** A short lived effect as particles: points with a radius and a colour that change with the age of the effect (drawn as the body's spheres). */
private fun effectActor(e: Effect, now: Int, palette: IntArray): AtticActor {
    val age = (now - e.born).coerceAtLeast(0)
    val life = AtticGame.EFFECT_TICKS
    val rnd = java.util.Random(e.born * 31L + e.kind)
    val count = when (e.kind) { 0 -> 30; 1 -> 14; else -> 10 }
    val verts = FloatArray(count * 3)
    val spheres = ArrayList<Int>(count * 3)
    val fade = (1f - age.toFloat() / life).coerceIn(0f, 1f)
    // the evaporation colour is one of three palette entries (specialTable of the original: 144, 192, 48), the same for all its bubbles
    val bubbleColor = palette.getOrElse(intArrayOf(144, 192, 48)[rnd.nextInt(3)]) { 0xFFFFFFFF.toInt() }
    for (i in 0 until count) {
        var x = e.x.toFloat(); var y = e.y.toFloat(); var z = e.z.toFloat()
        val box = e.box
        if (box != null) {
            x = box[0] + rnd.nextFloat() * (box[1] - box[0])
            y = box[3] + (box[2] - box[3]) * rnd.nextFloat()
            z = box[4] + rnd.nextFloat() * (box[5] - box[4])
        }
        // each bubble of the evaporation has its own size (150 to 300 units across) and lifetime (30 to 80 ticks), as the original stores them per point
        val size = (150 + rnd.nextInt(150)) / 2
        val lifeTicks = 30 + rnd.nextInt(50)
        val vx = (rnd.nextFloat() - 0.5f) * (if (e.kind == 1) 40f else 8f)
        val vz = (rnd.nextFloat() - 0.5f) * (if (e.kind == 1) 40f else 8f)
        val vy = when (e.kind) { 1 -> -10f + rnd.nextFloat() * 6f; 4 -> -(3f + rnd.nextFloat() * 4f); else -> -(4f + rnd.nextFloat() * 6f) }
        val g = if (e.kind == 1) 1.1f * age else 0f
        x += vx * age; z += vz * age; y += vy * age + g * age / 2f
        verts[i * 3] = x; verts[i * 3 + 1] = y; verts[i * 3 + 2] = z
        if (e.kind == 0) {
            if (age > lifeTicks) continue // this bubble is gone
            spheres.add(i); spheres.add(size); spheres.add((bubbleColor and 0xFFFFFF) or (0xFC shl 24)) // alpha 0xFC: drawn as a shaded ball
            continue
        }
        val shade = (255 * fade).toInt().coerceIn(0, 255)
        val color = when (e.kind) {
            1 -> (0xFF shl 24) or ((150 * fade + 60).toInt().coerceIn(0, 255) shl 16) or (20 shl 8) or 20
            4 -> (0xFF shl 24) or (shade / 2 + 60 shl 16) or (shade / 2 + 60 shl 8) or (shade / 2 + 60)
            else -> (0xFF shl 24) or (shade shl 16) or (shade shl 8) or shade
        }
        spheres.add(i); spheres.add(if (e.kind == 1) 14 else 24); spheres.add(color)
    }
    return AtticActor(verts, IntArray(0), e.x.toFloat(), e.y.toFloat(), e.z.toFloat(), e.x / 10 - 5, e.x / 10 + 5, e.z / 10 - 5, e.z / 10 + 5, IntArray(0), spheres.toIntArray())
}

/** Entry point: reads the room from the original game files, shows an error message when that is not possible. */
@Composable
fun AtticScreen(onExit: () -> Unit, controlMode: ControlMode = ControlMode.STEERING, developerMode: Boolean = false, devItems: List<Int> = emptyList(), devPos: List<Int>? = null, loadSlot: Int? = null) {
    val loader = rememberOriginalFiles(AtticOriginalReader::missingFiles, cacheKey = "attic") { files -> AtticOriginalReader.read(files) }
    when (val state = loader.state) {
        is OriginalState.Ready -> AtticRoom(state.data, onExit, controlMode, developerMode, devItems, devPos, loadSlot)
        is OriginalState.Failed -> OriginalFilesErrorScreen(state.message, onChooseFolder = loader.chooseFolder, onExit = onExit)
        OriginalState.Loading -> Box(Modifier.fillMaxSize().background(Color.Black)) {
            BitmapText(text = "Lade Originaldateien...", scale = 2f, color = Color(0xFFF2E4B8), modifier = Modifier.align(Alignment.Center))
            BackButton(onClick = onExit)
        }
    }
}

@Composable
private fun AtticRoom(world: AtticOriginal, onExit: () -> Unit, controlMode: ControlMode, developerMode: Boolean, devItems: List<Int>, devPos: List<Int>?, loadSlot: Int?) {
    BackHandler { onExit() }
    // the attic plays the first track of LISTMUS.PAK in a loop (the intro music stops); leaving the attic stops it
    DisposableEffect(world) {
        GameMusic.setLooping(true, AtticOriginalReader.ATTIC_MUSIC, world.music) // the attic loops its music
        GameMusic.start(AtticOriginalReader.ATTIC_MUSIC, world.music) // stops the intro music and plays the first track of LISTMUS.PAK
        onDispose { GameMusic.stop() }
    }

    val sounds = remember(world) { IntroSounds { world.game.sample(it) } }
    DisposableEffect(sounds) { onDispose { sounds.release() } }
    val ui = remember(world) { AtticUi(world, sounds) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val game = remember(world) { AtticGame(world.game, ui).also { g ->
        devItems.forEach { g.take(it) }
        devPos?.let { p -> g.player?.actor?.teleport(p[0], 0, p[1], p[2]) }
        for ((i, v) in DevOptions.vars) if (i in g.vm.vars.indices) g.vm.vars[i] = v.toShort()
        if (DevOptions.vars.any { it.first == 19 }) g.objects[9].w.chronoStart = g.time
        if (DevOptions.vars.any { it.first == 999 }) g.special(1, 0) // shows the evaporation effect around the player (for testing)
        if (loadSlot != null) SaveStore.load(context, loadSlot, g)
    } }
    // the room behind the attic stairs is read in the background, so the change of floor does not stop the game
    LaunchedEffect(world) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try { world.game.rooms.room(1, 6) } catch (_: Exception) { }
        }
    }
    val startNanos = remember { System.nanoTime() }
    var frameTick by remember { mutableIntStateOf(0) }
    var cameraIndex by remember { mutableIntStateOf(ATTIC_INITIAL_CAMERA_INDEX) }
    // the room the game is in; its cameras, pictures and masks are prepared the first time it is shown
    val roomCache = remember(world) { HashMap<RoomView, List<android.graphics.Bitmap>>() }
    var shownRoom by remember { mutableStateOf<RoomView?>(null) }
    var playerX by remember { mutableFloatStateOf(0f) }
    var playerZ by remember { mutableFloatStateOf(0f) }

    // the found window and the inventory stop the game
    var foundItem by remember { mutableIntStateOf(-1) }
    var inventoryOpen by remember { mutableStateOf(false) }
    var actionHeld by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var menuMessage by remember { mutableStateOf<String?>(null) }

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
            val paused = foundItem >= 0 || inventoryOpen || menuOpen || ui.gameOverShown || ui.exitShown || ui.endShown || ui.picture != null || game.pendingRead != null
            if (!paused) gameClock += (timer - lastTimer).coerceAtLeast(0)
            lastTimer = timer
            ui.expire(System.nanoTime())
            ui.pollMusic()
            if (ui.picture != null && System.nanoTime() > ui.pictureNanos) ui.picture = null
            if (!paused) {
                val player = game.player?.actor
                var joy = 0
                var run = false
                when (controlMode) {
                    ControlMode.STEERING -> if (player != null && hypot(stickX, stickY) > stickDeadzone) {
                        // stick up = away from the camera, right = screen right, in the active camera's own ground directions
                        val camBeta = (game.roomView.cameras.getOrNull(cameraIndex) ?: game.roomView.cameras[0]).angleBeta * angleUnitToRad
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
                val view = game.roomView
                // the camera follows the object the scripts point it at (the player, or for a moment a creature entering)
                val target = game.objects.getOrNull(game.cameraTarget)?.takeIf { it.w.active }?.actor ?: game.player?.actor
                val camX = target?.renderX?.toFloat() ?: playerX
                val camZ = target?.renderZ?.toFloat() ?: playerZ
                if (shownRoom !== view) { // a new room: the camera of the zone the target stands in
                    shownRoom = view
                    cameraIndex = resolveAtticCamera(camX, camZ, 0, view.cameras)
                } else {
                    cameraIndex = resolveAtticCamera(camX, camZ, cameraIndex, view.cameras)
                }
            }
            frameTick++
        }
    }

    val view = game.roomView
    val camera = view.cameras.getOrNull(cameraIndex) ?: view.cameras[0]
    val backgrounds = roomCache.getOrPut(view) {
        // photo of each camera, scaled once to the renderer's resolution (nearest neighbour keeps the pixel look)
        view.photos.map { argb ->
            val bmp = android.graphics.Bitmap.createBitmap(argb, 320, 200, android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Bitmap.createScaledBitmap(bmp, AtticSceneRenderer.W, AtticSceneRenderer.H, false)
        }
    }
    // one frame of the room: photo, objects and player through the camera matrix, masks over them (AtticSceneRenderer)
    val roomImage = run {
        frameTick // the poses change every frame
        val actors = game.objects.mapNotNull { it.toActor(view.bounds[0], view.bounds[1], view.bounds[2], view.bounds[3]) } + game.effects.map { effectActor(it, game.time, world.game.palette) }
        val dark = game.lightOff
        renderer.render(camera, if (showBackground && !dark) backgrounds[camera.index.coerceIn(0, backgrounds.size - 1)] else blackBackground, actors, if (dark) emptyList() else view.masks.getOrNull(camera.index) ?: emptyList())
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

        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 130.dp, bottom = 12.dp)
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable { menuMessage = null; menuOpen = true }
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            BitmapText(text = "Menu", scale = 2f, color = Color(0xFFF2E4B8))
        }

        if (game.allowInventory && game.inventory.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 230.dp, bottom = 12.dp)
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
        if (menuOpen) {
            GameMenu(
                slotLabel = { SaveStore.label(context, it) },
                canLoad = { SaveStore.savedAt(context, it) != null },
                message = menuMessage,
            ) { action, slot ->
                when (action) {
                    GameMenuAction.RESUME -> menuOpen = false
                    GameMenuAction.QUIT -> onExit()
                    GameMenuAction.SAVE -> menuMessage = if (SaveStore.save(context, slot, game)) "Game saved." else "Saving failed."
                    GameMenuAction.LOAD -> {
                        if (SaveStore.load(context, slot, game)) {
                            foundItem = -1
                            menuOpen = false
                            ui.music(game.vm.currentMusic.coerceAtLeast(0), true)
                        } else {
                            menuMessage = "Loading failed."
                        }
                    }
                }
            }
        }
        game.pendingRead?.let { read ->
            BookReader(game, read[0], read[1]) { game.finishRead() }
        }
        ui.picture?.let { pic ->
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Image(bitmap = pic, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
        if (ui.endShown) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)).clickable { onExit() }, contentAlignment = Alignment.Center) {
                BitmapText(text = "The End", scale = 4f, color = Color(0xFFF2E4B8))
            }
        }
        if (ui.exitShown) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)).clickable { onExit() }, contentAlignment = Alignment.Center) {
                BitmapText(text = "To be continued...", scale = 3f, color = Color(0xFFF2E4B8))
            }
        }
        if (ui.gameOverShown) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).clickable { onExit() }, contentAlignment = Alignment.Center) {
                BitmapText(text = "Game Over", scale = 4f, color = Color(0xFFF2E4B8))
            }
        }
    }
}
