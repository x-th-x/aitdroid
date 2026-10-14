package com.aitddemo.game

import com.aitddemo.original.s16
import com.aitddemo.original.u16
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Animation aus LISTANIM.PAK: Keyframes mit Dauer (Ticks, 60 je Sekunde), Verschiebung und Gruppenzuständen. */
class AnimData(val frames: Array<Frame>) {
    class Frame(val time: Int, val stepX: Int, val stepY: Int, val stepZ: Int, val groups: Array<GroupState>)

    val numFrames: Int get() = frames.size

    companion object {

        fun parse(d: ByteArray): AnimData {
            val nf = d.u16(0)
            val ng = d.u16(2)
            require(d.size == 4 + nf * (8 + ng * 8)) { "Animation: unerwartete Größe" }
            var p = 4
            val frames = Array(nf) {
                val t = d.u16(p)
                val sx = d.s16(p + 2)
                val sy = d.s16(p + 4)
                val sz = d.s16(p + 6)
                p += 8
                val groups = Array(ng) { g ->
                    GroupState(d.s16(p + g * 8), d.s16(p + g * 8 + 2), d.s16(p + g * 8 + 4), d.s16(p + g * 8 + 6))
                }
                p += ng * 8
                Frame(t, sx, sy, sz, groups)
            }
            return AnimData(frames)
        }
    }
}

/** Animationsnummern der Steuerung (Argumente von ANIM_MOVE in Williams Lebensskript). */
class MoveAnims(val stand: Int, val walk: Int, val run: Int, val stop: Int, val backward: Int, val turnRight: Int, val turnLeft: Int)

/** Tasten der Panzersteuerung (wie `JoyD` im Original: 1 vor, 2 zurück, 4 links, 8 rechts). */
object Joy {
    const val FORWARD = 1
    const val BACKWARD = 2
    const val LEFT = 4
    const val RIGHT = 8
}

/**
 * Eine animierte Spielfigur mit der Bewegungslogik der Originalengine (GereAnim, animMove, processTrack/GereManualRot,
 * Kollision mit den Hindernissen des Raums). Alle Zeiten in Ticks (60 je Sekunde), Winkel 1024 = 360 Grad.
 */
class Actor(
    private val rig: ActorRig,
    private val loadAnim: (Int) -> AnimData,
    startX: Int,
    startY: Int,
    startZ: Int,
    var beta: Int,
    startAnim: Int,
    startTimer: Int,
    var hardcols: List<Zone>,
    private val typeZv: Int = 1,
    startAnimType: Int = 0,
    startAnimInfo: Int = -1,
) {
    // Position: festgeschrieben + Schritt der laufenden Keyframe (wie worldX + stepX im Original)
    var worldX = startX
    var worldY = startY
    var worldZ = startZ
    private var stepX = 0
    private var stepZ = 0
    private var animNegX = 0
    private var animNegZ = 0

    /** Hüllquader in Raumkoordinaten (x1, x2, y1, y2, z1, z2). */
    val zv = IntArray(6)

    private var anim = -1
    private var animData: AnimData? = null
    private var frame = 0
    private var animType = 0
    private var animInfo = -1
    private var newAnim = -1
    private var newAnimType = 0
    private var newAnimInfo = -1
    private var endFrame = false

    /** Zuletzt begonnene Keyframe (Animation, Bild) – die Sound-Ereignisse der Skripte (ANIM_SAMPLE) hängen daran; -1 = keins. */
    var eventAnim = -1
    var eventFrame = -1

    /** Im letzten [update] ging eine Keyframe zu Ende (END_FRAME im Original) bzw. die ganze Animation (flagEndAnim). */
    var endFrameTick = false
        private set
    var endAnimTick = false
        private set

    /** Index (in [extraColliders]) des Hüllquaders, gegen den die Figur im letzten [update] stieß; -1 = keiner. */
    var collidedWith = -1
        private set

    /** Alle Hüllquader (Indizes in [colliders]), die der Schritt im letzten [update] berührte (CheckObjectCol im Original). */
    val collidedAll = ArrayList<Int>()

    private var stopPending = false

    /** Eine schon angeforderte, noch nicht begonnene Animation (Nummer, Art, Folgeanimation) oder null. */
    val pendingAnim: IntArray? get() = if (newAnim != -1) intArrayOf(newAnim, newAnimType, newAnimInfo) else null

    /** flagEndAnim des Originals: die letzte Animation ist zu Ende gelaufen (bleibt gesetzt, bis eine neue beginnt). */
    var flagEndAnim = false
        private set

    /** Wand-Kollision (Zone Typ 9: deren Parameter, Typ 3: 255) der letzten Bewegung, -1 = keine (HARD_COL im Original). */
    var hardCol = -1

    /** Stoßen an Wände und Zonen aktiv (TEST_COL des Skripts, dynFlags & 1). */
    var wallCollision = true

    /** Läuft eine Animation (AF_ANIMATED)? Ohne Animation bleibt der Körper in der Haltung des Körpers stehen. */
    val animated: Boolean get() = animData != null

    /** Animation starten wie ANIM_ONCE (type 0), ANIM_REPEAT (1) oder ANIM_ALL_ONCE (2) im Skript; [info] = Folgeanimation; -1 beendet die Animation. */
    fun playAnim(num: Int, type: Int, info: Int): Boolean {
        if (num < 0) {
            if (anim != -1 || animData != null) {
                anim = -1
                animData = null
                newAnim = -1
                newAnimType = 0
                newAnimInfo = -1
                flagEndAnim = true
            }
            return true
        }
        return initAnim(num, type, info)
    }

    /** Aktueller (interpolierter) Zustand der Gruppen und der Ausgangszustand der laufenden Keyframe. */
    val state: Array<GroupState> = Array(rig.groupCount) { GroupState().also { s -> s.set(rig.rest[it]) } }
    private var previous: Array<GroupState> = Array(rig.groupCount) { GroupState().also { s -> s.set(rig.rest[it]) } }
    private var keyStart = startTimer
    private var animStepX = 0
    private var animStepZ = 0

    // Steuerung
    var speed = 0
    var direction = 0
    private var rotStart = 0
    private var rotEnd = 0
    private var rotSteps = 0
    private var rotMemo = 0
    private var lastTimeForward = -1000

    val renderX: Int get() = worldX + stepX
    val renderY: Int get() = worldY
    val renderZ: Int get() = worldZ + stepZ
    val currentAnim: Int get() = anim
    val currentAnimType: Int get() = animType and 3
    val currentAnimInfo: Int get() = animInfo

    /** Aktuelles Bild (Keyframe) der laufenden Animation. */
    val frameIndex: Int get() = frame

    private var turning = false
    val isTurning: Boolean get() = turning

    /** Dreht die Figur in [ticks] Ticks auf den Winkel [target] (wie SET_BETA des Skripts). */
    fun turnTo(target: Int, ticks: Int, now: Int) {
        // das Skript ruft SET_BETA jedes Bild auf: eine laufende Drehung zum selben Ziel nicht neu beginnen
        if (!(rotSteps != 0 && (rotEnd and 0x3FF) == (target and 0x3FF))) initRot(beta, target, ticks, now)
        turning = true
    }

    /** ANIM_MOVE: wählt die Animation passend zu Geschwindigkeit und Drehung. */
    fun chooseMoveAnim(anims: MoveAnims) = animMove(anims)

    /** Spielt eine Skript-Animation einmal ab (ANIM_ONCE) und danach [after] (Stehen) in Schleife; die Steuerung ruht. */
    fun startScriptedAnim(num: Int, after: Int) {
        speed = 0
        direction = 0
        initAnim(num, 0, after)
    }

    /** Weitere feste Hüllquader (x1, x2, y1, y2, z1, z2) im Raum, z. B. andere Objekte: sie blockieren die Figur wie Hindernisse. */
    var colliders: List<Collider> = emptyList()

    /** Ein Hindernis-Quader (x1, x2, y1, y2, z1, z2); [movable] = er lässt sich schieben (AF_MOVABLE). */
    class Collider(val zv: IntArray, val movable: Boolean, val solid: Boolean = true)

    /** Schiebt Objekt [index] von [colliders] um (dx, dz); true, wenn es sich bewegt hat (der Aufrufer prüft Wände und andere Objekte). */
    var pushObject: ((Int, Int, Int) -> Boolean)? = null

    /** Wird von außen um (dx, dz) verschoben (geschoben von einer Figur). */
    fun pushBy(dx: Int, dz: Int) {
        worldX += dx
        worldZ += dz
        zv[0] += dx; zv[1] += dx; zv[4] += dz; zv[5] += dz
    }

    private fun hits(l: IntArray, x1: Int, x2: Int, y1: Int, y2: Int, z1: Int, z2: Int) =
        x1 < l[1] && l[0] < x2 && y1 < l[3] && l[2] < y2 && z1 < l[5] && l[4] < z2

    /** Bodenmaterial aus der Szenenzone Typ 8 (steuert später die Schrittgeräusche), -1 = keins. */
    var hardMat = -1

    /** Parameter der letzten Szenenzone Typ 9/10 (HARD_DEC im Original). */
    var hardDec = -1

    private fun resetZv() {
        boxFor(rig.zv, typeZv, worldX, worldY, worldZ, beta).copyInto(zv)
    }

    /** Together mode: how much of the step of the animation the figure really moves, in percent (the car of the intro drives slower up to the house). */
    var moveFactor = 100

    /** Together mode: puts a running animation at another key frame (the second hero does not take the same step as the first). Call it after the first [update] of the animation. */
    fun seekFrame(index: Int, timer: Int) {
        val d = animData ?: return
        if (d.frames.isEmpty()) return
        frame = index.coerceIn(0, d.frames.size - 1)
        previous = copyOf(state)
        keyStart = timer
    }

    /** Setzt die Figur an eine neue Stelle (STAGE-Befehl des Skripts): Position, Blickrichtung, Hüllquader neu. */
    fun teleport(x: Int, y: Int, z: Int, newBeta: Int) {
        worldX = x
        worldY = y
        worldZ = z
        stepX = 0
        stepZ = 0
        animNegX = 0
        animNegZ = 0
        beta = newBeta
        rotSteps = 0
        resetZv()
    }

    /** Raumwechsel über eine Zone Typ 0: Koordinaten in den Ursprung des neuen Raums umrechnen (Welt bleibt gleich). */
    fun moveToRoomOrigin(dx: Int, dy: Int, dz: Int) {
        worldX -= dx
        worldY += dy
        worldZ += dz
        zv[0] -= dx
        zv[1] -= dx
        zv[2] += dy
        zv[3] += dy
        zv[4] += dz
        zv[5] += dz
    }

    init {
        // Hüllquader je nach Typ aus dem Körper-Quader (Original: getZvMax / GiveZVObjet / getZvCube)
        val b = rig.zv
        val box = when (typeZv) {
            0 -> {
                val h = maxOf(b[1] - b[0], b[5] - b[4]) / 2
                intArrayOf(-h, h, b[2], b[3], -h, h)
            }
            2 -> {
                val h = (b[1] + b[5]) / 2
                intArrayOf(-h, h, b[2], b[3], -h, h)
            }
            else -> b
        }
        zv[0] = box[0] + startX
        zv[1] = box[1] + startX
        zv[2] = box[2] + startY
        zv[3] = box[3] + startY
        zv[4] = box[4] + startZ
        zv[5] = box[5] + startZ
        // erste Animation setzen (SetAnimObjet: Keyframe 0 gilt sofort)
        anim = startAnim
        animData = if (startAnim >= 0) loadAnim(startAnim) else null
        frame = 0
        animData?.let { applyKeyframe(it.frames[0]) }
        previous = copyOf(state)
        keyStart = startTimer
        animType = startAnimType
        animInfo = startAnimInfo
    }

    private fun copyOf(src: Array<GroupState>) = Array(src.size) { GroupState().also { s -> s.set(src[it]) } }

    private fun applyKeyframe(f: AnimData.Frame) {
        val n = minOf(f.groups.size, state.size)
        for (i in 0 until n) state[i].set(f.groups[i])
        animStepX = f.stepX
        animStepZ = f.stepZ
    }

    // ---------------------------------------------------------------- Steuerung (processTrack, Modus 1 = manuell)

    /** Verarbeitet die Tasten [joy]: Drehung (GereManualRot(60)) und Geschwindigkeit; danach wählt [animMove] die Animation. */
    fun processManual(joy: Int, timer: Int, anims: MoveAnims) {
        manualMove(joy, timer)
        animMove(anims)
    }

    /** Bewegungsmodus 1 (manuell): Tasten -> Drehung und Geschwindigkeit (processTrack, Fall 1). */
    private fun manualMove(joy: Int, timer: Int) {
        manualRotation(joy, timer, 60)
        if (joy and Joy.FORWARD != 0) {
            if (timer - lastTimeForward < 10 && speed != 4) {
                speed = 5 // Doppeltipp nach vorn: Laufen
            } else if (speed == 0 || speed == -1) {
                speed = 4
            }
            lastTimeForward = timer
        } else if (speed in 1..4) {
            speed--
        } else {
            speed = 0
        }
        if (joy and Joy.BACKWARD != 0) {
            if (speed == 0 || speed >= 4) speed = -1
            if (speed == 5) speed = 0
        }
    }

    // ---------------------------------------------------------------- Bewegungsmodi (InitDeplacement / processTrack)

    /** Bewegungsmodus: 0 = keiner, 1 = manuell (Tasten), 2 = folgt einem Objekt, 3 = Pfad aus LISTTRAK. */
    var trackMode = 0
        private set
    var trackNumber = -1
        private set
    var positionInTrack = 0
        private set

    /** Marke, die ein Pfad setzt (MARK); Skripte fragen sie ab. */
    var mark = -1
        private set

    /** Pfad [n] aus LISTTRAK.PAK als 16-Bit-Wörter (null, wenn es ihn nicht gibt). */
    var trackSource: (Int) -> ShortArray? = { null }

    /** Lage (x, z in Raumkoordinaten dieses Objekts) des verfolgten Objekts [n] oder null, wenn es nicht in der Szene ist. */
    var followTarget: (Int) -> IntArray? = { null }

    /** Pfad-Befehl "Sprung": (Raum, x, y, z). Der Spielstand setzt das Objekt dorthin. */
    var onTrackWarp: ((Int, Int, Int, Int) -> Unit)? = null

    /** Kollisionen mit anderen Objekten aktiv (Pfad-Befehle COL_OFF/COL_ON). */
    var collisionsOn = true

    /** MOVE-Befehl: Modus und Pfad-/Zielnummer setzen (wie InitDeplacement). */
    fun initMove(mode: Int, number: Int) {
        trackMode = mode
        when (mode) {
            2, 5 -> { // 5 (nur TimeGate): wie 2, das Objekt läuft auf das Ziel zu
                trackNumber = number
                mark = -1
            }
            3 -> {
                trackNumber = number
                positionInTrack = 0
                mark = -1
            }
        }
    }

    /** Spielstand laden: Bewegungsmodus, Pfad, Position im Pfad und Marke wie gespeichert setzen. */
    fun restoreMove(mode: Int, number: Int, position: Int, markValue: Int) {
        trackMode = mode
        trackNumber = number
        positionInTrack = position
        mark = markValue
    }

    /** CONTINUE_TRACK: steht der Pfad an einem Warte-Befehl (STOP), geht er weiter. */
    fun continueTrack() {
        val t = trackSource(trackNumber) ?: return
        if (positionInTrack < t.size && t[positionInTrack].toInt() == 5) positionInTrack++
    }

    /** MANUAL_ROT des Skripts: nur die Drehung per Tasten. */
    fun manualRot(joy: Int, timer: Int, param: Int) = manualRotation(joy, timer, param)

    /** Setzt Drehung/Geschwindigkeit zurück (RESET_MOVE_MANUAL). */
    fun resetMove() {
        speed = 0
        direction = 0
        rotSteps = 0
    }

    private fun capSub(a: Int, dx: Int, dz: Int): Int {
        val ang = (a and 0x3FF) * 2.0 * Math.PI / 1024.0
        val xo = Math.round(Math.cos(ang) * 1000).toInt()
        val yo = Math.round(-Math.sin(ang) * 1000).toInt()
        val v = yo.toLong() * dz - xo.toLong() * dx
        return if (v == 0L) 0 else if (v > 0) 1 else -1
    }

    /** CapObjet: auf welcher Seite liegt das Ziel (+1 / -1), 0 = geradeaus. */
    private fun cap(x1: Int, z1: Int, b: Int, x2: Int, z2: Int): Int {
        val dx = x2 - x1
        val dz = z2 - z1
        val min = capSub(b - 4, dx, dz)
        val max = capSub(b + 4, dx, dz)
        return if (max == -1 && min == 1) capSub(b, dx, dz) else ((max + min) + 1) shr 1
    }

    /** Läuft William/ein Objekt nach den Regeln des Bewegungsmodus ein Bild weiter (processTrack). */
    fun processTrack(joy: Int, timer: Int) {
        when (trackMode) {
            1 -> manualMove(joy, timer)
            2, 5 -> follow(timer)
            3 -> track(timer)
        }
        beta = beta and 0x3FF
    }

    private fun turnToward(tx: Int, tz: Int, timer: Int, scale: Int, steps: Int) {
        val mod = cap(renderX, renderZ, beta, tx, tz)
        if (rotSteps == 0 || direction != mod) initRot(beta, beta - mod * scale, steps, timer)
        direction = mod
        if (mod == 0) rotSteps = 0 else beta = updateRotation(timer)
    }

    private fun follow(timer: Int) {
        val t = followTarget(trackNumber)
        if (t == null) {
            direction = 0
            speed = 0
            return
        }
        turnToward(t[0], t[1], timer, 256, 60)
        speed = 4
    }

    /** Nummer des Raums, in dem das Objekt gerade steht (für Wege, die in einen anderen Raum führen). */
    var roomNumber = 0

    /** Verschiebung (x, y, z) einer Position des Raums [n] in die Koordinaten des aktuellen Raums: x -= a, y += b, z += c; null = unbekannt. */
    var roomShift: (Int) -> IntArray? = { null }

    /** Hintergrund-Zonen (GereDec) für dieses Objekt aktiv (Pfad-Befehle DEC_OFF/DEC_ON). */
    var triggersOn = true

    /** Gemerkte Lage (Pfad-Befehl MEMO_COOR), Ausgangspunkt der Treppenwege. */
    var memoX = startX
    var memoY = startY
    var memoZ = startZ

    private var yDelta = 0
    private var ySteps = 0
    private var yElapsed = 0
    private var yApplied = 0

    /** Raises or lowers the object by dy (height and box). */
    fun moveYBy(dy: Int) = moveY(dy)

    private fun moveY(dy: Int) {
        worldY += dy
        zv[2] += dy
        zv[3] += dy
    }

    /** Eine laufende Höhenbewegung (Pfad-Befehl GOTO_3D) einen Schritt weiter. */
    private fun stepHeight() {
        if (ySteps == 0) return
        yElapsed++
        val target = if (yElapsed >= ySteps) yDelta else yDelta * yElapsed / ySteps
        moveY(target - yApplied)
        yApplied = target
        if (yElapsed >= ySteps) ySteps = 0
    }

    private fun proportional(x1: Int, x2: Int, y1: Int, y2: Int): Int = if (y1 == 0) x1 else x1 + ((x2 - x1) * y2) / y1

    /** Pfad aus LISTTRAK.PAK (Betriebsart 3), Befehle wie im Original von Alone in the Dark (track.cpp). */
    private fun track(timer: Int) {
        val t = trackSource(trackNumber) ?: run { speed = 0; return }
        if (positionInTrack >= t.size) return
        val macro = t[positionInTrack].toInt()
        fun w(i: Int) = t.getOrElse(positionInTrack + i) { 0 }.toInt()
        when (macro) {
            0 -> { // Sprung an eine Stelle (Raum, x, y, z)
                val room = w(1); val x = w(2); val y = w(3); val z = w(4)
                speed = 0
                direction = 0
                rotSteps = 0
                positionInTrack += 5 // before the jump: the jump may take the object out of the scene, and it comes back at the next step
                onTrackWarp?.invoke(room, x, y, z)
            }
            1 -> { // zu einer Stelle gehen (Raum, x, z)
                var x = w(2)
                var z = w(3)
                if (w(1) != roomNumber) roomShift(w(1))?.let { x -= it[0]; z += it[2] }
                if (Math.abs(renderX - x) + Math.abs(renderZ - z) >= TRACK_REACHED) { // the distance of the original is the sum of both differences
                    turnToward(x, z, timer, 64, 15)
                } else {
                    positionInTrack += 4
                }
            }
            2 -> { // Ende
                speed = 0
                trackNumber = -1
                trackMode = 0
            }
            3 -> positionInTrack = 0 // wiederholen
            4 -> { // Marke
                mark = w(1)
                positionInTrack += 2
            }
            5 -> { speed = 4; positionInTrack++ } // gehen
            6 -> { speed = 5; positionInTrack++ } // laufen
            7 -> { speed = 0; positionInTrack++ } // anhalten
            8 -> { speed = -1; positionInTrack++ } // rückwärts
            9 -> { // auf einen Winkel drehen
                val target = w(1)
                direction = if (((beta - target) and 1023) > 512) 1 else -1
                if (rotSteps == 0) initRot(beta, target, 120, timer)
                beta = updateRotation(timer)
                if ((beta and 0x3FF) == (target and 0x3FF)) {
                    direction = 0
                    positionInTrack += 2
                }
            }
            10 -> { wallCollision = false; positionInTrack++ }
            11 -> { wallCollision = true; positionInTrack++ }
            12 -> positionInTrack += 2
            13 -> { triggersOn = false; positionInTrack++ }
            14 -> { triggersOn = true; positionInTrack++ }
            15 -> { // zu einer Stelle mit Höhe (Raum, x, y, z, Zeit)
                var x = w(2)
                var y = w(3)
                var z = w(4)
                val time = w(5)
                if (w(1) != roomNumber) roomShift(w(1))?.let { x -= it[0]; y += it[1]; z += it[2] }
                if (y == renderY && Math.abs(renderX - x) + Math.abs(renderZ - z) < TRACK_REACHED) {
                    positionInTrack += 6
                } else {
                    if (ySteps == 0 && y != renderY) {
                        yDelta = y - renderY
                        ySteps = time.coerceAtLeast(1)
                        yElapsed = 0
                        yApplied = 0
                    }
                    val dir = cap(renderX, renderZ, beta, x, z)
                    if (rotSteps == 0 || direction != dir) initRot(beta, beta - dir * 256, 60, timer)
                    direction = dir
                    if (dir == 0) rotSteps = 0 else beta = updateRotation(timer)
                }
            }
            16 -> { // Lage merken
                memoX = renderX; memoY = renderY; memoZ = renderZ
                positionInTrack++
            }
            17, 18 -> { // Treppe auf x / auf z: die Höhe folgt dem Weg zum Ziel
                val x = w(1)
                val y = w(2)
                val z = w(3)
                if (renderY < y - 100 || renderY > y + 100) {
                    val prop = if (macro == 18) proportional(memoY, y, z - memoZ, renderZ - memoZ) else proportional(memoY, y, x - memoX, renderX - memoX)
                    moveY(prop - worldY)
                    val dir = cap(renderX, renderZ, beta, x, z)
                    if (rotSteps == 0 || direction != dir) initRot(beta, beta - (dir shl 8), 60, timer)
                    direction = dir
                    if (dir != 0) beta = updateRotation(timer) else rotSteps = 0
                } else {
                    moveY(y - worldY)
                    positionInTrack += 4
                }
            }
            19 -> { // Winkel setzen (alpha, beta, gamma)
                beta = w(2)
                direction = 0
                positionInTrack += 4
            }
            else -> positionInTrack++
        }
    }

    private fun manualRotation(joy: Int, timer: Int, param: Int) {
        if (joy and Joy.LEFT != 0) {
            if (direction != 1) rotSteps = 0
            direction = 1
            if (rotSteps == 0) initRot(beta, beta + 0x100, if (speed == 0) param / 2 else param, timer)
            beta = updateRotation(timer)
        }
        if (joy and Joy.RIGHT != 0) {
            if (direction != -1) rotSteps = 0
            direction = -1
            if (rotSteps == 0) initRot(beta, beta - 0x100, if (speed == 0) param / 2 else param, timer)
            beta = updateRotation(timer)
        }
        if (joy and (Joy.LEFT or Joy.RIGHT) == 0) {
            direction = 0
            rotSteps = 0
        }
    }

    private fun initRot(start: Int, end: Int, steps: Int, timer: Int) {
        rotStart = start
        rotEnd = end
        rotSteps = steps
        rotMemo = timer
    }

    private fun updateRotation(timer: Int): Int {
        if (rotSteps == 0) return rotEnd
        val dt = timer - rotMemo
        if (dt > rotSteps) {
            rotSteps = 0
            return rotEnd
        }
        val e = rotEnd and 0x3FF
        val s = rotStart and 0x3FF
        val diff = e - s
        return if (diff <= 0x200) {
            if (diff >= -0x200) s + diff * dt / rotSteps else s + (e + 0x400 - s) * dt / rotSteps
        } else {
            s + (e - (s + 0x400)) * dt / rotSteps
        }
    }

    /** ANIM_MOVE des Lebensskripts: wählt je nach Geschwindigkeit und Drehrichtung die Animation. */
    private fun animMove(a: MoveAnims) {
        if (speed == 5) initAnim(a.run, 1, -1)
        if (speed == 4) initAnim(a.walk, 1, -1)
        if (speed == -1) {
            if (anim == a.walk) initAnim(a.stand, 0, a.backward)
            else if (anim == a.run) initAnim(a.stop, 0, a.stand)
            else initAnim(a.backward, 1, -1)
        }
        if (speed == 0) {
            if (anim == a.walk || anim == a.run) {
                initAnim(a.stop, 0, a.stand)
            } else {
                if (direction == 0) initAnim(a.stand, 1, -1)
                if (direction == 1) initAnim(a.turnLeft, 0, a.stand)
                if (direction == -1) initAnim(a.turnRight, 0, a.stand)
            }
        }
    }

    /** InitAnim des Originals: true, wenn die Animation neu angefordert wurde; false, wenn sie schon läuft (dann ändern sich nur Art und Folgeanimation) oder gesperrt ist. */
    private fun initAnim(num: Int, type: Int, info: Int): Boolean {
        stopPending = false
        if (num == anim) {
            animType = type
            animInfo = info
            return false
        }
        // AITD1: eine nicht unterbrechbare Animation (ANIM_ALL_ONCE) lässt jede neue Anforderung verfallen
        if (animType and 2 != 0) return false
        if (newAnimType and 2 != 0) return false
        newAnim = num
        newAnimType = type
        newAnimInfo = info
        frame = 0
        return true
    }

    // ---------------------------------------------------------------- Animation und Bewegung (GereAnim)

    fun update(timer: Int) {
        stepHeight()
        endFrameTick = false
        endAnimTick = false
        collidedWith = -1
        collidedAll.clear()
        if (turning) {
            beta = updateRotation(timer)
            if (rotSteps == 0) turning = false
        }
        if (newAnim == -1 && stopPending) { // the animation ended with nothing to follow (animInfo -1): it stops now, one tick after its last keyframe
            stopPending = false
            playAnim(-1, 0, -1)
        }
        if (newAnim != -1) {
            if (newAnimType and 4 == 0) {
                if (!endFrame) { // Schritt der unterbrochenen Keyframe festschreiben
                    worldX += stepX
                    worldZ += stepZ
                    stepX = 0
                    stepZ = 0
                    animNegX = 0
                    animNegZ = 0
                }
                previous = copyOf(state) // StockInterAnim: von der aktuellen Haltung aus überblenden
                keyStart = timer
            } else {
                previous = copyOf(state)
                keyStart = timer
                newAnimType = newAnimType and 4.inv()
            }
            anim = newAnim
            animType = newAnimType
            animInfo = newAnimInfo
            animData = loadAnim(anim)
            newAnim = -1
            newAnimType = 0
            newAnimInfo = -1
            frame = 0
            flagEndAnim = false
        }
        val data = animData ?: return
        val oldStepX = stepX
        val oldStepZ = stepZ
        endFrame = interpolate(data, timer)

        // Schritt der Animation in Blickrichtung drehen (walkStep: x = sx*cos - sz*sin, z = sx*sin + sz*cos)
        val a = (beta and 0x3FF) * 2.0 * Math.PI / 1024.0
        val moveX = (animStepX * cos(a) - animStepZ * sin(a)).toInt() * moveFactor / 100
        val moveZ = (animStepX * sin(a) + animStepZ * cos(a)).toInt() * moveFactor / 100
        var sx = moveX + animNegX - oldStepX
        var sz = moveZ + animNegZ - oldStepZ

        if (sx != 0 || sz != 0) {
            val local = zv.copyOf()
            local[0] += sx
            local[1] += sx
            local[4] += sz
            local[5] += sz
            // walls of the room (AsmCheckListCol + GereCollision)
            if (wallCollision) for (h in hardcols) {
                if (!hits(local, h.x1, h.x2, h.y1, h.y2, h.z1, h.z2)) continue
                if (h.type == 9) hardCol = h.parameter
                if (h.type == 3) hardCol = 255
                val r = collide(zv, local, h, sx, sz)
                animNegX += r[0] - sx
                animNegZ += r[1] - sz
                local[0] += r[0] - sx
                local[1] += r[0] - sx
                local[4] += r[1] - sz
                local[5] += r[1] - sz
                sx = r[0]
                sz = r[1]
            }
            // other objects (CheckObjectCol): a movable one is pushed along when it can move, anything else stops him (only when his collisions are on)
            for ((i, c) in colliders.withIndex()) {
                val b = c.zv
                if (!hits(local, b[0], b[1], b[2], b[3], b[4], b[5])) continue
                if (collidedWith < 0) collidedWith = i
                if (i !in collidedAll) collidedAll.add(i)
                if (!c.solid) continue // touched only (items lying on the floor)
                if (c.movable && (sx != 0 || sz != 0) && pushObject?.invoke(i, sx, sz) == true) continue
                if (!c.movable && !wallCollision) continue
                val r = collide(zv, local, Zone(b[0], b[1], b[2], b[3], b[4], b[5], 0, 99), sx, sz)
                animNegX += r[0] - sx
                animNegZ += r[1] - sz
                local[0] += r[0] - sx
                local[1] += r[0] - sx
                local[4] += r[1] - sz
                local[5] += r[1] - sz
                sx = r[0]
                sz = r[1]
            }
            stepX = sx + oldStepX
            stepZ = sz + oldStepZ
            zv[0] += sx
            zv[1] += sx
            zv[4] += sz
            zv[5] += sz
        }

        if (endFrame) {
            frame++
            endFrameTick = true
            eventAnim = anim
            eventFrame = if (frame >= data.numFrames) 0 else frame
            if (frame >= data.numFrames) {
                endAnimTick = true
                flagEndAnim = true
                frame = 0
                if (animType and 1 == 0 && newAnim == -1) {
                    animType = animType and 2.inv()
                    if (animInfo == -1) stopPending = true else initAnim(animInfo, 1, -1)
                }
            }
            worldX += stepX
            worldZ += stepZ
            stepX = 0
            stepZ = 0
            animNegX = 0
            animNegZ = 0
        }
    }

    /** SetInterAnimObjet: Zustand zwischen letzter und aktueller Keyframe; true, wenn die Keyframe zu Ende ist. */
    private fun interpolate(data: AnimData, timer: Int): Boolean {
        val key = data.frames[frame]
        val len = key.time
        val t = (timer - keyStart) and 0xFFFF
        val n = minOf(key.groups.size, state.size)
        if (t < len) {
            for (i in 0 until n) {
                val s = state[i]
                val p = previous[i]
                val k = key.groups[i]
                s.type = k.type
                when (k.type) {
                    0 -> {
                        s.x = angle(p.x, k.x, t, len)
                        s.y = angle(p.y, k.y, t, len)
                        s.z = angle(p.z, k.z, t, len)
                    }
                    1, 2 -> {
                        s.x = step(p.x, k.x, t, len)
                        s.y = step(p.y, k.y, t, len)
                        s.z = step(p.z, k.z, t, len)
                    }
                }
            }
            animStepX = key.stepX * t / len
            animStepZ = key.stepZ * t / len
            return false
        }
        for (i in 0 until n) state[i].set(key.groups[i])
        previous = Array(state.size) { i -> GroupState().also { it.set(if (i < n) key.groups[i] else state[i]) } }
        keyStart = timer
        animStepX = key.stepX
        animStepZ = key.stepZ
        return true
    }

    private fun angle(prev: Int, next: Int, t: Int, len: Int): Int {
        val diff = (next - prev).toShort().toInt()
        if (diff == 0) return next
        return if (diff <= 0x200) {
            if (diff >= -0x200) diff * t / len + prev else (next + 0x400 - prev) * t / len + prev
        } else {
            (next - (prev + 0x400)) * t / len + prev + 0x400
        }
    }

    private fun step(prev: Int, next: Int, t: Int, len: Int): Int = if (prev == next) next else (next - prev) * t / len + prev

    // ---------------------------------------------------------------- Kollision (GereCollision / Glisser)

    companion object {
        private const val TRACK_REACHED = 400
        /**
         * Hüllquader eines Objekts in Raumkoordinaten aus dem Körper-Quader [b] (x1, x2, y1, y2, z1, z2) je nach Typ
         * (OBJETS typeZV): 0 = getZvMax, 1 = Körperquader, 2 = Würfel, 3 = um beta gedrehter Quader.
         */
        fun boxFor(b: IntArray, typeZv: Int, x: Int, y: Int, z: Int, beta: Int): IntArray {
            val box = when (typeZv) {
                0 -> {
                    val h = maxOf(b[1] - b[0], b[5] - b[4]) / 2
                    intArrayOf(-h, h, b[2], b[3], -h, h)
                }
                2 -> {
                    val h = (b[1] + b[5]) / 2
                    intArrayOf(-h, h, b[2], b[3], -h, h)
                }
                3 -> {
                    val a = (beta and 0x3FF) * 2.0 * Math.PI / 1024.0
                    val c = cos(a)
                    val s = sin(a)
                    var x1 = Int.MAX_VALUE
                    var x2 = Int.MIN_VALUE
                    var z1 = Int.MAX_VALUE
                    var z2 = Int.MIN_VALUE
                    for (px in intArrayOf(b[0], b[1])) for (pz in intArrayOf(b[4], b[5])) {
                        val rx = (px * c - pz * s).toInt()
                        val rz = (px * s + pz * c).toInt()
                        x1 = minOf(x1, rx); x2 = maxOf(x2, rx); z1 = minOf(z1, rz); z2 = maxOf(z2, rz)
                    }
                    intArrayOf(x1, x2, b[2], b[3], z1, z2)
                }
                else -> b
            }
            return intArrayOf(box[0] + x, box[1] + x, box[2] + y, box[3] + y, box[4] + z, box[5] + z)
        }
    }

    /** Ergebnis: erlaubter Schritt (x, z) nach dem Anstoß an die Zone [fix]: an der Wand entlang gleiten oder stehen bleiben. */
    private fun collide(old: IntArray, animated: IntArray, fix: Zone, stepX: Int, stepZ: Int): IntArray {
        var hx = stepX
        var hz = stepZ
        fun glisser(flag: Int) {
            when (flag) {
                1, 2 -> hz = 0
                4, 8 -> hx = 0
            }
        }
        var oldPos: Int
        oldPos = if (old[1] > fix.x1) { if (fix.x2 <= old[0]) 8 else 0 } else 4
        oldPos = oldPos or if (old[5] > fix.z1) { if (old[4] >= fix.z2) 2 else 0 } else 1
        val oldType: Int
        if (oldPos == 5 || oldPos == 9 || oldPos == 6 || oldPos == 10) {
            oldType = 2
        } else if (oldPos == 0) {
            // Already inside the box (the original stops him dead here). A creature that came in through the window would never get out again, so it may walk on.
            return intArrayOf(stepX, stepZ)
        } else {
            oldType = 1
        }
        val halfX = (animated[0] + animated[1]) / 2
        val halfZ = (animated[4] + animated[5]) / 2
        var pos = if (fix.x1 > halfX) 4 else if (fix.x2 < halfX) 0 else 8
        pos = pos or if (fix.z1 > halfZ) 1 else if (fix.z2 < halfZ) 0 else 2
        val type = if (pos == 5 || pos == 9 || pos == 6 || pos == 10) 2 else if (pos == 0) 0 else 1

        if (oldType == 1) {
            glisser(oldPos)
        } else if (type == 1 && (pos and oldPos) != 0) {
            glisser(pos)
        } else if (pos == oldPos || pos + oldPos == 15) {
            val xm = abs(animated[0] - old[0])
            val zm = abs(animated[4] - old[4])
            if (xm > zm) hz = 0 else hx = 0
        } else if (type == 0 || (type == 1 && (pos and oldPos) == 0)) {
            hz = 0
            hx = 0
        } else {
            glisser(oldPos and pos)
        }
        return intArrayOf(hx, hz)
    }
}
