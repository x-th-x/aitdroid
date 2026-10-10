package com.aitddemo

import com.aitddemo.original.GameFiles
import com.aitddemo.original.Pak
import com.aitddemo.original.VocSample
import com.aitddemo.original.s16
import com.aitddemo.original.u16
import com.aitddemo.original.u8

/** A waypoint of a movement track (LISTTRAK.PAK): the room and the position in it. */
class TrackPoint(val room: Int, val x: Int, val z: Int)

/** A movement track: waypoints in order, and the marks (numbers the life scripts wait for) with the number of waypoints reached before each. */
class TrackData(val points: List<TrackPoint>, val marks: List<Pair<Int, Int>>)

/** Camera of one shot: projection (with the room's world position), background masks and the 320x200 picture. */
class IntroCamera(val camera: AtticCameraDef, val masks: List<AtticData.Mask>, val picture: IntArray)

/** The frog: animated body, its hop (anim 252) and rest (anim 253) animations and where it starts (OBJETS.ITD object 289). */
class IntroFrog(
    val parts: AnimatedParts,
    val triangles: IntArray,
    val lines: IntArray,
    val spheres: IntArray,
    val hop: IntArray,
    val hopGroups: Int,
    val rest: IntArray,
    val restGroups: Int,
    val x: Int,
    val y: Int,
    val z: Int,
    val beta: Int,
)

/** A body with its placement from OBJETS.ITD: static mesh (x, y, z per vertex; a, b, c, ARGB per triangle). */
class IntroMesh(val vertices: FloatArray, val triangles: IntArray, val lines: IntArray = IntArray(0), val spheres: IntArray = IntArray(0))

/** An animated body (rig as expected by [animatedVertices]) with its walk animation. */
class IntroWalker(val parts: AnimatedParts, val triangles: IntArray, val frames: IntArray, val groups: Int, val lines: IntArray = IntArray(0), val spheres: IntArray = IntArray(0))

/** Everything the intro needs, read from the original files. */
class IntroOriginal(
    val pond: IntroCamera,
    val road: IntroCamera,
    val mansion: IntroCamera,
    val courtyard: IntroCamera,
    val frogPicture: IntArray,
    val car: IntroMesh,
    val carStartX: Int,
    val carStartZ: Int,
    /** Room the car starts in; its track waypoints name their own room (the last ones are in the next room). */
    val carRoom: Int,
    val carTrack: TrackData,
    val carnby: IntroWalker,
    val carnbyTrack: TrackData,
    /** Where the driver appears (life script 547: changeroom(E7R0, -200, 0, 200)) before he walks his track. */
    val carnbyStartX: Int,
    val carnbyStartZ: Int,
    val frog: IntroFrog,
    /** Root step of the car's driving animation (anim 244) per tick, in units: with the measured speed of the car it gives the length of a tick. */
    val carStepPerTick: Float,
    val samples: Map<Int, VocSample>,
    val copyright: String,
    /** World position (x, z in units) of every room: a waypoint in another room is shifted by the difference of the two rooms' positions. */
    val roomOrigins: Map<Int, IntArray>,
    /** The intro music: AdLib track (ADLM file) of LISTMUS.PAK. */
    val music: ByteArray,
)

/**
 * Reads the intro (stage 7) of Alone in the Dark directly from the original files: ETAGE07.PAK / CAMERA07.PAK (cameras, pictures),
 * ITD_RESS.PAK (palette, full-screen pictures), OBJETS.ITD + LISTBODY.PAK (car, driver walking, frog), LISTANIM.PAK (walk animation),
 * LISTTRAK.PAK (movement tracks), LISTSAMP.PAK (sounds) and ENGLISH.PAK (texts).
 */
object IntroOriginalReader {
    val REQUIRED = listOf(
        "ETAGE07.PAK", "CAMERA07.PAK", AtticOriginalReader.RESOURCE_FILE, AtticOriginalReader.OBJECT_FILE, AtticOriginalReader.BODY_FILE,
        AtticOriginalReader.ANIM_FILE, "LISTTRAK.PAK", "LISTSAMP.PAK", "ENGLISH.PAK", MusicOriginalReader.FILE,
    )

    private const val STAGE = 7
    private const val PALETTE_ENTRY = 4
    private const val FROG_PICTURE = 11 // picture(11, 20, 78) of the life script: ITD_RESS.PAK entry 12
    private const val CAR_OBJECT = 286
    private const val CARNBY_OBJECT = 288
    private const val FROG_OBJECT = 289
    private const val CAR_TRACK = 29
    private const val CARNBY_TRACK = 25
    private const val WALK_ANIM = 245
    private const val DRIVE_ANIM = 244
    private const val FROG_HOP_ANIM = 252
    private const val FROG_REST_ANIM = 253
    private const val COPYRIGHT_TEXT = 2
    private const val CARNBY_START_X = -200 // life script 547 at the end of the car track: obj_288.changeroom(E7R0, -200, 0, 200)
    private const val CARNBY_START_Z = 200
    const val INTRO_MUSIC = 7 // fade_music(7) of the intro script: entry number + 1 in LISTMUS.PAK
    private const val OBJECT_SIZE = 52

    /** Sounds the intro plays (sample numbers of the life scripts; entry = number + 1 in LISTSAMP.PAK). */
    val SAMPLES = intArrayOf(3, 4, 59, 75, 76, 77, 78, 79, 80, 81, 82, 83, 89)

    fun missingFiles(files: GameFiles): List<String> = REQUIRED.filter { !files.exists(it) }

    fun read(files: GameFiles): IntroOriginal {
        val missing = missingFiles(files)
        if (missing.isNotEmpty()) throw OriginalFilesException("Fehlende Dateien: " + missing.joinToString(", "))
        try {
            return readAll(files)
        } catch (e: OriginalFilesException) {
            throw e
        } catch (e: Exception) {
            throw OriginalFilesException("Die Originaldateien konnten nicht gelesen werden (${e.javaClass.simpleName}: ${e.message}).")
        }
    }

    private fun readAll(files: GameFiles): IntroOriginal {
        val resources = Pak(files.read(AtticOriginalReader.RESOURCE_FILE)!!)
        val palette = readPalette(resources.get(PALETTE_ENTRY) ?: throw OriginalFilesException("ITD_RESS.PAK: Palette fehlt"))
        val stage = OriginalStage.open(files, STAGE)
        fun shot(room: Int, camera: Int): IntroCamera {
            val c = stage.camera(room, camera)
            return IntroCamera(c.camera, c.masks, stage.picture(camera, palette))
        }
        val frogRaw = resources.get(FROG_PICTURE + 1) ?: throw OriginalFilesException("ITD_RESS.PAK: Bild $FROG_PICTURE fehlt")
        val frogPicture = IntArray(320 * 200) { palette[frogRaw.u8(it)] }

        // ---- objects and bodies
        val objects = files.read(AtticOriginalReader.OBJECT_FILE)!!
        val bodies = Pak(files.read(AtticOriginalReader.BODY_FILE)!!)
        fun obj(i: Int, k: Int) = objects.s16(2 + i * OBJECT_SIZE + k * 2)
        fun body(objectIndex: Int): OriginalBody {
            val n = obj(objectIndex, 1)
            return OriginalBody.parse(bodies.get(n + 1) ?: throw OriginalFilesException("LISTBODY.PAK: Körper $n fehlt"))
        }
        val carBody = body(CAR_OBJECT)
        val car = carBody.toMesh(palette).let { IntroMesh(it.first, it.second, carBody.lines(palette), carBody.spheres(palette)) }
        val frogBody = body(FROG_OBJECT)
        val carnbyBody = body(CARNBY_OBJECT)
        val animRaw = Pak(files.read(AtticOriginalReader.ANIM_FILE)!!).get(WALK_ANIM + 1) ?: throw OriginalFilesException("LISTANIM.PAK: Animation $WALK_ANIM fehlt")
        val (frames, groups) = readAnimation(animRaw)
        val animPak = Pak(files.read(AtticOriginalReader.ANIM_FILE)!!)
        fun anim(n: Int) = readAnimation(animPak.get(n + 1) ?: throw OriginalFilesException("LISTANIM.PAK: Animation $n fehlt"))
        val drive = anim(DRIVE_ANIM)
        val driveStride = 4 + drive.second * 4
        val driveFrames = drive.first.size / driveStride
        val carStep = -(0 until driveFrames).sumOf { drive.first[it * driveStride + 3] }.toFloat() / (0 until driveFrames).sumOf { drive.first[it * driveStride] }
        val hop = anim(FROG_HOP_ANIM)
        val restOne = anim(FROG_REST_ANIM)
        // a one-frame animation is played as two identical frames so that it can be interpolated
        val rest = restOne.first + restOne.first
        val frog = IntroFrog(
            frogBody.animatedParts(), frogBody.toMesh(palette).second, frogBody.lines(palette), frogBody.spheres(palette),
            hop.first, hop.second, rest, restOne.second,
            obj(FROG_OBJECT, 8), obj(FROG_OBJECT, 9), obj(FROG_OBJECT, 10), obj(FROG_OBJECT, 12),
        )
        val walker = IntroWalker(carnbyBody.animatedParts(), carnbyBody.toMesh(palette).second, frames, groups, carnbyBody.lines(palette), carnbyBody.spheres(palette))

        // ---- tracks, sounds, text
        val tracks = Pak(files.read("LISTTRAK.PAK")!!)
        val carTrack = readTrack(tracks.get(CAR_TRACK + 1) ?: throw OriginalFilesException("LISTTRAK.PAK: Bahn $CAR_TRACK fehlt"))
        val carnbyTrack = readTrack(tracks.get(CARNBY_TRACK + 1) ?: throw OriginalFilesException("LISTTRAK.PAK: Bahn $CARNBY_TRACK fehlt"))
        val samplePak = Pak(files.read("LISTSAMP.PAK")!!)
        val samples = HashMap<Int, VocSample>()
        for (n in SAMPLES) {
            val voc = samplePak.get(n + 1)?.let { VocSample.parse(it) } ?: throw OriginalFilesException("LISTSAMP.PAK: Klang $n fehlt")
            samples[n] = voc
        }
        val text = Pak(files.read("ENGLISH.PAK")!!).get(1) ?: throw OriginalFilesException("ENGLISH.PAK: Texte fehlen")
        val copyright = readMessage(text, COPYRIGHT_TEXT) ?: throw OriginalFilesException("ENGLISH.PAK: Text $COPYRIGHT_TEXT fehlt")

        return IntroOriginal(
            pond = shot(1, 3), road = shot(1, 4), mansion = shot(0, 1), courtyard = shot(0, 2),
            frogPicture = frogPicture,
            car = car, carStartX = obj(CAR_OBJECT, 8), carStartZ = obj(CAR_OBJECT, 10), carRoom = obj(CAR_OBJECT, 15), carTrack = carTrack,
            carnby = walker, carnbyTrack = carnbyTrack, carnbyStartX = CARNBY_START_X, carnbyStartZ = CARNBY_START_Z,
            frog = frog, carStepPerTick = carStep, samples = samples, copyright = copyright,
            roomOrigins = stage.rooms.associate { it.index to intArrayOf(it.worldX * 10, it.worldZ * 10) },
            music = Pak(files.read(MusicOriginalReader.FILE)!!).get(INTRO_MUSIC + 1) ?: throw OriginalFilesException("LISTMUS.PAK: Musik $INTRO_MUSIC fehlt"),
        )
    }

    private fun readPalette(d: ByteArray): IntArray {
        if (d.size < 768) throw OriginalFilesException("ITD_RESS.PAK: Palette hat die falsche Größe")
        val vga = (0 until 768).all { d.u8(it) <= 63 }
        return IntArray(256) { i ->
            var r = d.u8(i * 3)
            var g = d.u8(i * 3 + 1)
            var b = d.u8(i * 3 + 2)
            if (vga) {
                r = (r shl 2) or (r shr 4)
                g = (g shl 2) or (g shr 4)
                b = (b shl 2) or (b shr 4)
            }
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /** Animation: per keyframe time, root step (x, y, z) and per group (type, x, y, z) - flat 4 + 4 * groups values, like the exported walk animation. */
    private fun readAnimation(d: ByteArray): Pair<IntArray, Int> {
        val frames = d.u16(0)
        val groups = d.u16(2)
        if (d.size != 4 + frames * (8 + groups * 8)) throw OriginalFilesException("LISTANIM.PAK: Animation $WALK_ANIM hat ein unerwartetes Format")
        val out = IntArray(frames * (4 + groups * 4))
        var q = 4
        var o = 0
        for (f in 0 until frames) {
            out[o++] = d.u16(q)
            out[o++] = d.s16(q + 2)
            out[o++] = d.s16(q + 4)
            out[o++] = d.s16(q + 6)
            q += 8
            for (g in 0 until groups) {
                for (k in 0 until 4) out[o++] = d.s16(q + k * 2)
                q += 8
            }
        }
        return out to groups
    }

    /** Track words: 1 = go to (room, x, z), 4 = mark (number), 19 = set angle (0, beta, 0), 2 = end. */
    private fun readTrack(d: ByteArray): TrackData {
        val points = ArrayList<TrackPoint>()
        val marks = ArrayList<Pair<Int, Int>>()
        var p = 0
        while (p + 2 <= d.size) {
            when (d.s16(p)) {
                1 -> { points.add(TrackPoint(d.s16(p + 2), d.s16(p + 4), d.s16(p + 6))); p += 8 }
                4 -> { marks.add(d.s16(p + 2) to points.size); p += 4 }
                19 -> p += 8
                else -> break
            }
        }
        return TrackData(points, marks)
    }

    /** Text number [n] of a text file made of lines "@n:text". */
    private fun readMessage(d: ByteArray, n: Int): String? {
        val prefix = "@$n:"
        return String(d, Charsets.ISO_8859_1).split("\r\n", "\n").firstOrNull { it.startsWith(prefix) }?.substring(prefix.length)
    }
}
