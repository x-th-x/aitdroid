package com.aitddemo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import androidx.compose.ui.graphics.graphicsLayer
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
    private var music = if (GameMusic.playing) GameMusic.current else -1 // what the intro leaves playing; the start script of the game (FADE_MUSIC 0) brings in the attic music

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

    /**
     * The music of the original program (playMusic, the music commands of the life scripts, and the check that runs every frame): a track is played once, with the loops
     * it has inside; when it has ended nothing plays (the number -1), unless NEXT_MUSIC named a next one, which then starts at once. FADE_MUSIC cuts what plays, and
     * the next music starts 3 seconds later (180 ticks); the music number is -2 while it waits. A music that is already the current one is not started again.
     */
    private var queuedMusic = -1
    private var fadeStartNanos = 0L

    override fun currentMusic(): Int = music

    /** MUSIC n (playMusic). */
    override fun music(number: Int, loop: Boolean) {
        if (number == music) return
        music = number
        if (number < 0) return
        val data = world.musicTrack(number) ?: return
        GameMusic.setLooping(false, number, data)
        GameMusic.start(number, data)
    }

    /** NEXT_MUSIC n: starts at once when nothing plays, else after the music that plays now. */
    override fun nextMusic(number: Int) {
        if (music == -1) music(number, false) else queuedMusic = number
    }

    /** FADE_MUSIC n: starts at once when nothing plays, else the music is cut and n starts 3 seconds later. */
    override fun fadeMusic(number: Int) {
        if (music == -1) {
            music(number, false)
        } else {
            GameMusic.stop()
            music = -2
            queuedMusic = number
            fadeStartNanos = System.nanoTime()
        }
    }

    /** Called every tick: the check of the original program that moves on from a music that has ended (or that waits for the end of a fade). */
    fun pollMusic() {
        if (music == -2) {
            if (System.nanoTime() - fadeStartNanos >= 3_000_000_000L) {
                val n = queuedMusic
                queuedMusic = -1
                music = -1
                if (n >= 0) music(n, false)
            }
        } else if (music >= 0 && !GameMusic.playing) {
            val n = queuedMusic
            music = -1
            queuedMusic = -1
            if (n >= 0) music(n, false)
        }
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

private const val INTRO_CAR = 286 // the car of the intro, which the camera follows
private const val CAMERA_HOLD_TICKS = 90 // how long its last picture stays after the car has stopped (1.5 seconds of the 60 ticks per second)

/** An object of the game as something to draw: its posed vertices turned by the object's angles and moved to its place. */
private fun GameObject.toActor(): AtticActor? {
    val act = actor ?: return null
    val local = posedVertices() ?: return null
    val asset = asset ?: return null
    if (!w.active || w.body < 0) return null
    val x = act.renderX
    val y = act.renderY
    val z = act.renderZ
    // like the original: every object that has a body is drawn, wherever it is. Creatures that wait beyond the walls are covered by the camera picture's
    // background masks (drawn over every object whose box lies in a mask zone), the creature rising from the trapdoor is seen from the moment it shows.
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
    val actor = if (asset.parts != null) {
        AtticActor(out, asset.triangles, x.toFloat(), y.toFloat(), z.toFloat(), zv[0] / 10, zv[1] / 10, zv[4] / 10, zv[5] / 10, asset.lines, asset.spheres)
    } else {
        AtticActor(out, asset.triangles, x.toFloat(), y.toFloat(), z.toFloat(), (lowX / 10).toInt(), (highX / 10).toInt(), (lowZ / 10).toInt(), (highZ / 10).toInt(), asset.lines, asset.spheres)
    }
    actor.sortBox = zv.copyOf(6)
    actor.clipBox = clipBox
    return actor
}

/**
 * The same actor with the parts of other actors drawn in it: the heroes seated in the car are sorted together with the triangles of the car by depth (they are inside its box,
 * which the order of whole actors cannot show), so the car's body covers what it should cover of them.
 */
private fun AtticActor.withFigures(figures: List<AtticActor>): AtticActor {
    if (figures.isEmpty()) return this
    var vertexCount = worldVertices.size / 3
    val vertices = ArrayList<Float>(worldVertices.toList())
    val tris = ArrayList<Int>(triangles.toList())
    val lineList = ArrayList<Int>(lines.toList())
    val sphereList = ArrayList<Int>(spheres.toList())
    for (f in figures) {
        val base = vertexCount
        f.worldVertices.forEach { vertices.add(it) }
        for (i in 0 until f.triangles.size / 4) { tris.add(f.triangles[i * 4] + base); tris.add(f.triangles[i * 4 + 1] + base); tris.add(f.triangles[i * 4 + 2] + base); tris.add(f.triangles[i * 4 + 3]) }
        for (i in 0 until f.lines.size / 3) { lineList.add(f.lines[i * 3] + base); lineList.add(f.lines[i * 3 + 1] + base); lineList.add(f.lines[i * 3 + 2]) }
        for (i in 0 until f.spheres.size / 3) { sphereList.add(f.spheres[i * 3] + base); sphereList.add(f.spheres[i * 3 + 1]); sphereList.add(f.spheres[i * 3 + 2]) }
        vertexCount += f.worldVertices.size / 3
    }
    val merged = AtticActor(vertices.toFloatArray(), tris.toIntArray(), originX, originY, originZ, zvX1, zvX2, zvZ1, zvZ2, lineList.toIntArray(), sphereList.toIntArray())
    merged.sortBox = sortBox
    merged.clipBox = clipBox
    return merged
}

/** The box an effect has in the draw order: the box of the creature for the evaporation, else a small box around its place. */
private fun Effect.sortBox(): IntArray = box?.copyOf(6) ?: intArrayOf(x - 50, x + 50, y - 50, y + 50, z - 50, z + 50)

/** A short lived effect as particles: points with a radius and a colour that change with the age of the effect (drawn as the body's spheres). */
/**
 * The smoke of a cigar (mode 4): 20 puffs of grey, half transparent (palette colour 176), at 20 cm height. Each puff has a random place within 2000 units; it moves
 * out from the centre in a straight line as the time t (ticks of 60 per second) runs to 600, so it is at place * t / 600. The radius of the puffs equals t up to 540,
 * then 5 * (660 - t) down to nothing at 660. The cloud turns by 4 every frame.
 */
private fun smokeActor(e: Effect, now: Int, palette: IntArray): AtticActor {
    val t = (now - e.born).coerceAtLeast(0)
    val frames = t / SPECIAL_FRAME_TICKS
    val rnd = java.util.Random(e.born * 31L + e.kind)
    val rot = initRot(e.alpha, (e.beta + 4 * (frames + 1)) and 0x3FF, e.gamma)
    val key = (palette.getOrElse(176) { 0xFFFFFFFF.toInt() } and 0xFFFFFF) or (0xFA shl 24)
    val radius = if (t <= 540) t else (660 - t) * 5
    val count = 20
    val verts = FloatArray(count * 3)
    val spheres = ArrayList<Int>(count * 3)
    for (i in 0 until count) {
        val px = rnd.nextInt(4000) - 2000
        val pz = rnd.nextInt(4000) - 2000
        val (rx, ry, rz) = rotatePoint(px * t / 600f, -200f, pz * t / 600f, rot)
        verts[i * 3] = e.x + rx; verts[i * 3 + 1] = e.y + ry; verts[i * 3 + 2] = e.z + rz
        if (radius > 0) { spheres.add(i); spheres.add(radius); spheres.add(key) }
    }
    return AtticActor(verts, IntArray(0), e.x.toFloat(), e.y.toFloat(), e.z.toFloat(), e.x / 10 - 5, e.x / 10 + 5, e.z / 10 - 5, e.z / 10 + 5, IntArray(0), spheres.toIntArray())
}

/** The flash of a gun (mode 3): the body BODY_FLAMME at the muzzle, turned like the shooter; the original shows it for one frame. */
private fun flashActor(e: Effect, data: com.aitddemo.game.GameData): AtticActor {
    val asset = data.body(e.body)
    val local = asset?.vertices
    if (asset == null || local == null) return AtticActor(FloatArray(0), IntArray(0), e.x.toFloat(), e.y.toFloat(), e.z.toFloat(), 0, 0, 0, 0)
    val rot = initRot(e.alpha, e.beta, e.gamma)
    val n = local.size / 3
    val out = FloatArray(n * 3)
    for (i in 0 until n) {
        val (rx, ry, rz) = rotatePoint(local[i * 3], local[i * 3 + 1], local[i * 3 + 2], rot)
        out[i * 3] = rx + e.x; out[i * 3 + 1] = ry + e.y; out[i * 3 + 2] = rz + e.z
    }
    return AtticActor(out, asset.triangles, e.x.toFloat(), e.y.toFloat(), e.z.toFloat(), e.x / 10 - 5, e.x / 10 + 5, e.z / 10 - 5, e.z / 10 + 5, asset.lines, asset.spheres)
}

/** The photo of each camera of [view], scaled once to the renderer's resolution (nearest neighbour keeps the pixel look). */
private fun scaledPhotos(view: RoomView): List<android.graphics.Bitmap> = view.photos.map { argb ->
    val bmp = android.graphics.Bitmap.createBitmap(argb, 320, 200, android.graphics.Bitmap.Config.ARGB_8888)
    android.graphics.Bitmap.createScaledBitmap(bmp, AtticSceneRenderer.W, AtticSceneRenderer.H, false)
}

/** One frame of the original (the evaporation is stepped per frame of the main loop) in engine ticks; the original ran as fast as the machine allowed, 20 per second is typical. */
private const val SPECIAL_FRAME_TICKS = 3

/**
 * The evaporation of a creature as the original builds and draws it (InitSpecialObjet mode 0 and the flow routine): 30 bubbles at random places in the box of the
 * object, each with a size (150 to 300 units, 5 less every frame) and a rise (30 to 80 units every frame); the whole cloud turns by 8 every frame. The colour is
 * one of four palette banks (144, 192, 48, 112), a ramp of 16 colours from light to dark that runs across each bubble.
 */
private fun evaporationActor(e: Effect, now: Int, palette: IntArray): AtticActor {
    val frame = (now - e.born).coerceAtLeast(0) / SPECIAL_FRAME_TICKS
    val rnd = java.util.Random(e.born * 31L + e.kind)
    val bank = intArrayOf(144, 192, 48, 112)[rnd.nextInt(4)]
    val key = (palette.getOrElse(bank) { 0xFFFFFFFF.toInt() } and 0xFFFFFF) or (0xFB shl 24)
    BodyRamps.ramps[key] = IntArray(16) { palette.getOrElse(bank + it) { 0xFFFFFFFF.toInt() } }
    val rot = initRot(e.alpha, (e.beta + 8 * (frame + 1)) and 0x3FF, e.gamma)
    val box = e.box
    val count = 30
    val verts = FloatArray(count * 3)
    val spheres = ArrayList<Int>(count * 3)
    for (i in 0 until count) {
        val rx = if (box != null) (box[0] - e.x) + rnd.nextFloat() * (box[1] - box[0]) else 0f
        val ry = if (box != null) (box[2] - e.y) + rnd.nextFloat() * (box[3] - box[2]) else 0f
        val rz = if (box != null) (box[4] - e.z) + rnd.nextFloat() * (box[5] - box[4]) else 0f
        val size = 150 + rnd.nextInt(150)
        val rise = 30 + rnd.nextInt(50)
        val radius = size - 5 * frame
        val (px, py, pz) = rotatePoint(rx, ry - rise * frame, rz, rot)
        verts[i * 3] = e.x + px; verts[i * 3 + 1] = e.y + py; verts[i * 3 + 2] = e.z + pz
        if (radius > 0) { spheres.add(i); spheres.add(radius); spheres.add(key) }
    }
    return AtticActor(verts, IntArray(0), e.x.toFloat(), e.y.toFloat(), e.z.toFloat(), e.x / 10 - 5, e.x / 10 + 5, e.z / 10 - 5, e.z / 10 + 5, IntArray(0), spheres.toIntArray())
}

/**
 * The dots of a hit (mode 1, palette colour 85, red) and of a shot that lands (mode 2, colour 15, white), as the original does them: 30 single pixels at random places
 * within 100 units of the point; each has a random direction and an upward speed of 50 to a downward 10, every frame it gets 6 more downward speed, moves by its
 * speed and 10 units along its direction, and it is gone when it reaches the floor (y = 0). The cloud is turned by the angle of the object.
 */
private fun dotsActor(e: Effect, now: Int, palette: IntArray): AtticActor {
    val frames = (now - e.born).coerceAtLeast(0) / SPECIAL_FRAME_TICKS
    val rnd = java.util.Random(e.born * 31L + e.kind)
    val count = 30
    val px = FloatArray(count); val py = FloatArray(count); val pz = FloatArray(count)
    for (i in 0 until count) {
        px[i] = (rnd.nextInt(200) - 100).toFloat()
        py[i] = e.y + (rnd.nextInt(200) - 100).toFloat()
        pz[i] = (rnd.nextInt(200) - 100).toFloat()
    }
    val ang = IntArray(count); val vy = FloatArray(count)
    for (i in 0 until count) { ang[i] = rnd.nextInt(0x3FF); vy[i] = (rnd.nextInt(60) - 50).toFloat() }
    for (f in 0 until frames) {
        for (i in 0 until count) {
            if (py[i] >= 0f) continue
            vy[i] += 6f
            py[i] += vy[i]
            val a = ang[i] * 2.0 * Math.PI / 1024.0
            px[i] += (10 * Math.cos(a)).toFloat()
            pz[i] += (10 * Math.sin(a)).toFloat()
        }
    }
    val color = palette.getOrElse(if (e.kind == 1) 85 else 15) { 0xFFFFFFFF.toInt() }
    val rot = initRot(e.alpha, e.beta, e.gamma)
    val verts = FloatArray(count * 3)
    val spheres = ArrayList<Int>(count * 3)
    for (i in 0 until count) {
        val (rx, ry, rz) = rotatePoint(px[i], py[i], pz[i], rot)
        verts[i * 3] = e.x + rx; verts[i * 3 + 1] = ry; verts[i * 3 + 2] = e.z + rz
        if (py[i] < 0f) { spheres.add(i); spheres.add(-2); spheres.add(color) }
    }
    return AtticActor(verts, IntArray(0), e.x.toFloat(), e.y.toFloat(), e.z.toFloat(), e.x / 10 - 5, e.x / 10 + 5, e.z / 10 - 5, e.z / 10 + 5, IntArray(0), spheres.toIntArray())
}

private fun effectActor(e: Effect, now: Int, palette: IntArray, data: com.aitddemo.game.GameData): AtticActor {
    if (e.kind == 3) return flashActor(e, data)
    if (e.kind == 4) return smokeActor(e, now, palette)
    if (e.kind == 0) return evaporationActor(e, now, palette)
    if (e.kind == 1 || e.kind == 2) return dotsActor(e, now, palette)
    val age = (now - e.born).coerceAtLeast(0)
    val life = AtticGame.EFFECT_TICKS
    val rnd = java.util.Random(e.born * 31L + e.kind)
    val count = when (e.kind) { 0 -> 30; 1 -> 14; else -> 10 }
    val verts = FloatArray(count * 3)
    val spheres = ArrayList<Int>(count * 3)
    val fade = (1f - age.toFloat() / life).coerceIn(0f, 1f)
    for (i in 0 until count) {
        var x = e.x.toFloat(); var y = e.y.toFloat(); var z = e.z.toFloat()
        val box = e.box
        if (box != null) {
            x = box[0] + rnd.nextFloat() * (box[1] - box[0])
            y = box[3] + (box[2] - box[3]) * rnd.nextFloat()
            z = box[4] + rnd.nextFloat() * (box[5] - box[4])
        }
        val vx = (rnd.nextFloat() - 0.5f) * (if (e.kind == 1) 40f else 8f)
        val vz = (rnd.nextFloat() - 0.5f) * (if (e.kind == 1) 40f else 8f)
        val vy = when (e.kind) { 1 -> -10f + rnd.nextFloat() * 6f; 4 -> -(3f + rnd.nextFloat() * 4f); else -> -(4f + rnd.nextFloat() * 6f) }
        val g = if (e.kind == 1) 1.1f * age else 0f
        x += vx * age; z += vz * age; y += vy * age + g * age / 2f
        verts[i * 3] = x; verts[i * 3 + 1] = y; verts[i * 3 + 2] = z
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
fun AtticScreen(onExit: () -> Unit, controlMode: ControlMode = ControlMode.STEERING, developerMode: Boolean = false, devItems: List<Int> = emptyList(), devPos: List<Int>? = null, loadSlot: Int? = null, floorStart: FloorStart? = null, intro: Boolean = false, emily: Boolean = false, together: Boolean = false) {
    val loader = rememberOriginalFiles(if (together) AtticOriginalReader::missingFilesTogether else if (emily) AtticOriginalReader::missingFilesEmily else AtticOriginalReader::missingFiles, cacheKey = (if (emily) "attic-emily" else "attic") + (if (together) "-together" else "")) { files -> AtticOriginalReader.read(files, emily, together) }
    when (val state = loader.state) {
        is OriginalState.Ready -> AtticRoom(state.data, onExit, controlMode, developerMode, devItems, devPos, loadSlot, floorStart, intro, emily, together)
        is OriginalState.Failed -> OriginalFilesErrorScreen(state.message, onChooseFolder = loader.chooseFolder, onExit = onExit)
        OriginalState.Loading -> Box(Modifier.fillMaxSize().background(Color.Black)) {
            BitmapText(text = "Lade Originaldateien...", scale = 2f, color = Color(0xFFF2E4B8), modifier = Modifier.align(Alignment.Center))
            BackButton(onClick = onExit)
        }
    }
}

@Composable
private fun AtticRoom(world: AtticOriginal, onExit: () -> Unit, controlMode: ControlMode, developerMode: Boolean, devItems: List<Int>, devPos: List<Int>?, loadSlot: Int?, floorStart: FloorStart?, intro: Boolean, emily: Boolean, together: Boolean) {
    BackHandler { onExit() }
    // the attic plays the first track of LISTMUS.PAK in a loop (the intro music stops); leaving the attic stops it
    DisposableEffect(world) {
        onDispose { GameMusic.stop() }
    }

    val sounds = remember(world) { IntroSounds({ world.game.sample(it) }, { world.game.samplePriority(it) }) }
    DisposableEffect(sounds) { onDispose { sounds.release() } }
    val ui = remember(world) { AtticUi(world, sounds) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // the intro is the film of stage 7 that the life scripts play (startGame(7, 1) of the original): the car, the driver, his walk through the house up to the attic stairs
    val game = remember(world) { (if (intro) AtticGame(world.game, ui, 7, 1, true, together) else AtticGame(world.game, ui, together = together)).also { g ->
        if (intro) return@also
        devItems.forEach { g.take(it) }
        floorStart?.let { f ->
            g.startAt(f.stage, f.room)
            f.items.forEach { g.take(it) }
            if (f.skipWaits) for (o in g.objects) o.w.roomChronoStart = g.time - 3660
        }
        // for testing: devVars 990=104 starts in room 4 of floor 1 (stage * 100 + room), 991=1 lets everything that waits for a minute in its room go off at once
        DevOptions.vars.firstOrNull { it.first == 990 }?.let { v -> g.player?.let { pl -> pl.w.stage = v.second / 100; pl.w.room = v.second % 100; g.moved(g.playerIndex) } }
        if (DevOptions.vars.any { it.first == 991 }) for (o in g.objects) o.w.roomChronoStart = g.time - 3660
        devPos?.let { p -> g.player?.actor?.teleport(p[0], 0, p[1], p[2]) }
        for ((i, v) in DevOptions.vars) if (i in g.vm.vars.indices) g.vm.vars[i] = v.toShort()
        if (DevOptions.vars.any { it.first == 19 }) g.objects[9].w.chronoStart = g.time
        DevOptions.vars.firstOrNull { it.first == 995 }?.let { v -> g.objects[4].let { w -> w.actor?.teleport(v.second, 0, 4568, w.w.beta) } } // the wardrobe pushed against the window at that x (for testing)
        if (DevOptions.vars.any { it.first == 999 }) g.special(1, 0) // shows the evaporation effect around the player (for testing)
        if (DevOptions.vars.any { it.first == 996 }) g.player?.actor?.let { g.effects.add(Effect(4, it.renderX, it.renderY, it.renderZ, g.time, null, 0, 0, 0)) } // the smoke of a cigar around the player (for testing)
        if (DevOptions.vars.any { it.first == 997 }) g.player?.actor?.let { g.effects.add(Effect(3, it.renderX, it.renderY - 1000, it.renderZ, g.time + 300, null, 0, 0, 0, g.vm.cvars[1].toInt())) } // the flash of a gun in front of the player for 5 seconds (for testing)
        if (DevOptions.vars.any { it.first == 998 }) g.player?.actor?.let { g.effects.add(Effect(1, it.renderX, it.renderY - 1000, it.renderZ, g.time, null)) } // the dots of a hit in front of the player (for testing)
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
    var lastCameraTarget = -1
    var holdCameraTarget = -1
    var holdCameraUntil = 0
    // the room the game is in; its cameras, pictures and masks are prepared the first time it is shown
    val roomCache = remember(world) { java.util.concurrent.ConcurrentHashMap<RoomView, List<android.graphics.Bitmap>>() }
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
                        val target = headingOf(dirX, dirZ)
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
                // in the intro a shot is not cut away at the moment the script gives the camera another target: the picture stays with the old target (the car that has come to a stop)
                // for a moment, so that its stop is seen before the next view comes (the script of the car moves the camera on in the same tick in which the car stops)
                if (intro && game.cameraTarget != lastCameraTarget) {
                    if (lastCameraTarget == INTRO_CAR) { holdCameraTarget = lastCameraTarget; holdCameraUntil = game.time + CAMERA_HOLD_TICKS }
                    lastCameraTarget = game.cameraTarget
                }
                val cameraTargetIndex = if (intro && game.time < holdCameraUntil) holdCameraTarget else game.cameraTarget
                val targetObject = game.objects.getOrNull(cameraTargetIndex)?.takeIf { it.w.active }
                val target = targetObject?.actor ?: game.player?.actor
                // an object without a body (the helper places of the intro) has no box: its place counts
                val camX = target?.renderX?.toFloat() ?: targetObject?.fixedZv?.let { (it[0] + it[1]) / 2f } ?: targetObject?.w?.x?.toFloat() ?: playerX
                val tz = target?.zv ?: targetObject?.fixedZv
                val tx1 = tz?.get(0) ?: camX.toInt(); val tx2 = tz?.get(1) ?: camX.toInt()
                val camZ = target?.renderZ?.toFloat() ?: targetObject?.fixedZv?.let { (it[4] + it[5]) / 2f } ?: targetObject?.w?.z?.toFloat() ?: playerZ
                val tz1 = tz?.get(4) ?: camZ.toInt(); val tz2 = tz?.get(5) ?: camZ.toInt()
                val tBeta = target?.beta?.and(0x3FF) ?: 0
                if (shownRoom !== view) { // a new room: the camera of the zone the target stands in
                    // a camera that sees both rooms stays in use across the border (the new room lists it without a zone of its own): the shot is not cut where the target crosses into the
                    // next room, only when the target comes into the zone of another camera (this is what the cameras of the intro are made for, the car drives past the border)
                    val keptId = shownRoom?.pictureIds?.getOrNull(cameraIndex)
                    val kept = if (keptId == null) -1 else view.pictureIds.indexOf(keptId)
                    shownRoom = view
                    cameraIndex = resolveAtticCamera(tx1, tx2, tz1, tz2, tBeta, kept, view.cameras)
                } else {
                    cameraIndex = resolveAtticCamera(tx1, tx2, tz1, tz2, tBeta, cameraIndex, view.cameras)
                }
                game.cameraChanged(cameraIndex)
            }
            frameTick++
        }
    }

    // the script ends the film with GAME_OVER: on to the game
    if (intro) LaunchedEffect(ui.gameOverShown) { if (ui.gameOverShown) onExit() }

    val view = game.roomView
    val camera = view.cameras.getOrNull(cameraIndex) ?: view.cameras[0]
    val backgrounds = roomCache.getOrPut(view) { scaledPhotos(view) }
    // the intro walks through rooms of four floors: they are read and their pictures scaled in the background while the car drives
    if (intro) LaunchedEffect(world) {
        withContext(Dispatchers.Default) {
            for ((st, rm) in listOf(7 to 0, 3 to 1, 3 to 0, 3 to 5, 2 to 2, 1 to 7, 1 to 1, 1 to 0)) {
                try {
                    val next = world.game.rooms.room(st, rm)
                    roomCache.getOrPut(next) { scaledPhotos(next) }
                } catch (_: Exception) {
                    // a room the files do not have: nothing to prepare
                }
            }
        }
    }
    // the rooms next to this one (doors of the room) are read and their pictures scaled in the background, so walking through a door does not wait
    LaunchedEffect(view) {
        withContext(Dispatchers.Default) {
            for (zone in view.zones) {
                if (zone.type != 0) continue
                try {
                    val next = world.game.rooms.room(view.stage, zone.parameter)
                    roomCache.getOrPut(next) { scaledPhotos(next) }
                } catch (_: Exception) {
                    // a room the files do not have: nothing to prepare
                }
            }
        }
    }
    // one frame of the room: photo, objects and player through the camera matrix, masks over them (AtticSceneRenderer)
    val roomImage = run {
        frameTick // the poses change every frame
        // the heroes of the Together mode (the other hero, and in the intro the one who plays, seated in the car) are in the room of the scene
        val figures = listOfNotNull(game.companionObject, game.driverSeatObject).mapNotNull { f ->
            f.toActor()?.also {
                it.waterLimit = game.waterLimit.toFloat()
                it.maskRoom = view.room
            }
        }
        val actors = game.objects.filter { it.w.room == view.room || camera.viewedRooms.isEmpty() || it.w.room in camera.viewedRooms }.mapNotNull { o ->
            o.toActor()?.let { a -> if (game.figuresSeated && o.w.index == AtticGame.INTRO_CAR) a.withFigures(figures) else a }?.also {
                it.waterLimit = game.waterLimit.toFloat()
                it.maskRoom = o.w.room
                // an object of another room is covered by the masks of that room, which are in the coordinates of that room
                if (o.w.room != view.room) game.roomShift(o.w.room)?.let { d -> it.maskShiftX = d[0] / 10; it.maskShiftZ = -d[2] / 10 }
            }
        } + (if (game.figuresSeated) emptyList() else figures) + game.effects.map { e -> effectActor(e, game.time, world.game.palette, world.game).also { it.sortBox = e.sortBox() } }
        val dark = game.lightOff
        renderer.render(camera, if (showBackground && !dark) backgrounds[camera.index.coerceIn(0, backgrounds.size - 1)] else blackBackground, actors, if (dark) emptyMap() else view.maskViews.getOrNull(camera.index) ?: emptyMap(), view.room)
        renderer.bitmap.asImageBitmap()
    }

    // the intro is skipped by a swipe up (80 dp)
    val skipThresholdPx = with(androidx.compose.ui.platform.LocalDensity.current) { 80.dp.toPx() }
    var swipeAccumPx by remember { mutableFloatStateOf(0f) }
    var swipeDone by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(
                if (!intro) Modifier else Modifier.pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { swipeAccumPx = 0f },
                        onVerticalDrag = { _, dragAmount ->
                            if (swipeDone) return@detectVerticalDragGestures
                            swipeAccumPx += dragAmount
                            if (swipeAccumPx < -skipThresholdPx) {
                                swipeDone = true
                                onExit()
                            }
                        },
                    )
                },
            ),
    ) {
        PlateAspectBox {
            // SHAKING: every second tick of the 60 per second the display starts two of the 200 lines later
            val shift = if (game.shaking && game.time and 1 == 1) -2f / 200f else 0f
            Image(bitmap = roomImage, contentDescription = null, contentScale = ContentScale.Fit, filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize().graphicsLayer { translationY = size.height * shift })
        }

        // Floating joystick: appears where the thumb touches; drag distance is clamped and normalized to -1..1.
        val stickRadiusPx = 70f
        if (controlMode == ControlMode.STEERING && !intro) {
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
        if (developerMode && !intro) {
            Box(modifier = Modifier.align(Alignment.TopStart).padding(12.dp).background(Color.Black.copy(alpha = 0.5f)).padding(6.dp)) {
                BitmapText(
                    text = "World: (${playerX.toInt()}, ${playerZ.toInt()}) cam=$cameraIndex",
                    scale = 1.6f,
                    color = Color(0xFFF2E4B8)
                )
            }
        }

        if (controlMode == ControlMode.DPAD && !intro) {
            DPadOverlay(
                modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 24.dp, end = 16.dp),
                onUp = { dpadUp = it },
                onDown = { dpadDown = it },
                onLeft = { dpadLeft = it },
                onRight = { dpadRight = it }
            )
        }

        // the action key of the original (Enter): held while the finger is down; what it does is chosen in the inventory
        if (!intro) HoldButton(
            label = "Action",
            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = if (controlMode == ControlMode.DPAD) 24.dp else 40.dp, end = if (controlMode == ControlMode.DPAD) 200.dp else 24.dp),
            onHeld = { actionHeld = it },
        )

        // Together mode: the control goes to the other hero
        if (!intro && game.canSwitch) Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = if (controlMode == ControlMode.DPAD) 24.dp else 40.dp, end = if (controlMode == ControlMode.DPAD) 340.dp else 190.dp)
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable { game.switchHero(); stickBase = null; stickThumb = null; stickX = 0f; stickY = 0f }
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            BitmapText(text = "Switch", scale = 2f, color = Color(0xFFF2E4B8))
        }

        BackButton(onClick = onExit)

        if (!intro) Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 130.dp, bottom = 12.dp)
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable { menuMessage = null; menuOpen = true }
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            BitmapText(text = "Menu", scale = 2f, color = Color(0xFFF2E4B8))
        }

        if (!intro && game.allowInventory && game.inventory.isNotEmpty()) {
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
        if (!intro) Box(
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
                    GameMenuAction.SAVE -> menuMessage = if (SaveStore.save(context, slot, game, emily)) "Game saved." else "Saving failed."
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
        if (ui.exitShown && !intro) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)).clickable { onExit() }, contentAlignment = Alignment.Center) {
                BitmapText(text = "To be continued...", scale = 3f, color = Color(0xFFF2E4B8))
            }
        }
        if (ui.gameOverShown && !intro) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).clickable { onExit() }, contentAlignment = Alignment.Center) {
                BitmapText(text = "Game Over", scale = 4f, color = Color(0xFFF2E4B8))
            }
        }
    }
}

/** Engine angle (1024 = 360 degrees) of a body that moves in direction (dx, dz): its local -Z axis points that way. */
private fun headingOf(dx: Float, dz: Float): Float {
    val a = kotlin.math.atan2(dx, -dz) * 1024f / (2f * PI.toFloat())
    return ((a % 1024f) + 1024f) % 1024f
}
