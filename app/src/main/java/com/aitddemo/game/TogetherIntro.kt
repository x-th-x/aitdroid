package com.aitddemo.game

/**
 * The intro of the Together mode: the two heroes drive to the villa themselves, so the car does not stop at the road and drive off again, as in the original film. It drives on
 * through the gate and up the drive and stays in front of the house; the heroes get out there and walk to the door. The original scripts and paths are changed in a few words
 * (a copy; the files are not touched), and only when the mode is on.
 */
object TogetherIntro {
    /** The drive of the car in room 0 (x, z), from the place where the road ends in the film up to the house: through the gate, up the drive, round the bend. */
    private val DRIVE = arrayOf(
        // a wide bend from the road into the gate (the car is slow here, so the bend is tight), straight through the gate, then up the drive and round its bend
        intArrayOf(216, 187), intArrayOf(103, 263), intArrayOf(27, 376), intArrayOf(0, 520), intArrayOf(0, 800), intArrayOf(0, 1150), intArrayOf(-5, 1500),
        intArrayOf(10, 2050), intArrayOf(-80, 2330), intArrayOf(-300, 2500), intArrayOf(-560, 2640), intArrayOf(-1150, 2960),
    )

    /** Where the heroes get out (beside the parked car) and the door of the house they walk to (room 0). */
    const val GET_OUT_X = -880
    const val GET_OUT_Z = 2640
    private const val DOOR_X = -1800
    private const val DOOR_Z = 2700

    /** The film's variable of the car script that tells the car's track has ended: the walker is put there by the car's script. */
    fun patchScript(number: Int, words: ShortArray): ShortArray {
        val w = words.copyOf()
        fun at(i: Int) = w[i].toInt() and 0xFFFF
        when (number) {
            // the car script: when the car has stopped, the walker is put into room 0 at the gate (-200, 200) and the camera goes to the helper 290: now beside the car, and the walker is followed
            547 -> for (i in 0 until w.size - 7) {
                if (at(i) == 32815 && at(i + 1) == 288 && at(i + 2) == 7 && at(i + 3) == 0) {
                    w[i + 4] = GET_OUT_X.toShort(); w[i + 6] = GET_OUT_Z.toShort()
                    for (j in i + 7 until w.size - 1) if (at(j) == 51 && at(j + 1) == 290) { w[j + 1] = 288; break }
                    break
                }
            }
            // the walker's script: at his mark 5 the car was turned round and started to drive away: it stays
            538 -> for (i in 0 until w.size - 5) {
                if (at(i) == 4 && at(i + 1) == 11 && at(i + 2) == 65535 && at(i + 3) == 5 && at(i + 4) == 16) { w[i + 3] = 255; break }
            }
            // the helper's script: after some seconds the car drives off (track 28) and the music changes: the car stays (the game calls for the music)
            544 -> for (i in 0 until w.size - 5) {
                if (at(i) == 7 && at(i + 1) == 13 && at(i + 2) == 65535 && at(i + 3) == 12) { w[i + 3] = 30000.toShort(); break }
            }
        }
        return w
    }

    fun patchTrack(number: Int, words: ShortArray): ShortArray {
        fun w(vararg v: Int) = ShortArray(v.size) { v[it].toShort() }
        when (number) {
            // the car: as before up to the stop of the film, then on up the drive to the house (the marks 13 and 14 first, then the new way, then the mark 15 that stops it)
            29 -> {
                var cut = -1
                for (i in words.indices.reversed()) {
                    if (i + 3 < words.size && words[i].toInt() == 1 && words[i + 1].toInt() == 0 && words[i + 2].toInt() == 50 && words[i + 3].toInt() == 160) { cut = i; break }
                }
                if (cut < 0) return words
                val out = ArrayList<Short>(words.take(cut))
                for (p in DRIVE) { out.add(1); out.add(0); out.add(p[0].toShort()); out.add(p[1].toShort()) }
                out.add(4); out.add(15); out.add(2)
                return out.toShortArray()
            }
            // the walker: from the car to the door (the marks keep their order, the script reacts to them)
            25 -> return w(4, 1, 4, 2, 4, 3, 4, 4, 1, 0, -1300, 2640, 4, 5, 4, 6, 1, 0, DOOR_X, DOOR_Z, 2)
        }
        return words
    }
}
