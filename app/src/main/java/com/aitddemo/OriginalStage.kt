package com.aitddemo

import com.aitddemo.original.GameFiles
import com.aitddemo.original.Pak
import com.aitddemo.original.s16
import com.aitddemo.original.u16
import com.aitddemo.original.u32
import com.aitddemo.original.u8

/**
 * One stage (floor) of the game as stored in ETAGEnn.PAK / CAMERAnn.PAK: rooms with their hard colliders and cameras.
 * Entry 1 of ETAGEnn.PAK holds the rooms, entry 2 the cameras; the pictures are entries id + 1 of CAMERAnn.PAK.
 */
class OriginalStage private constructor(
    private val roomData: ByteArray,
    private val camData: ByteArray,
    private val pictures: Pak,
    val rooms: List<Room>,
) {
    /** A room: world position (for the camera translation), hard colliders (x1,x2,y1,y2,z1,z2,parameter,type) and the cameras that see it. */
    class Room(val index: Int, val worldX: Int, val worldY: Int, val worldZ: Int, val hardcols: IntArray, val cameraIds: IntArray, val zones: IntArray = IntArray(0))

    /** A camera as seen from one room: projection data, cover zones of that room and the background masks of that room. */
    class RoomCamera(val camera: AtticCameraDef, val masks: List<AtticData.Mask>)

    /** The camera [cameraId] as seen from [roomIndex]; the camera's [AtticCameraDef.index] is [cameraId]. */
    fun camera(roomIndex: Int, cameraId: Int): RoomCamera {
        val room = rooms.firstOrNull { it.index == roomIndex } ?: throw OriginalFilesException("Raum $roomIndex fehlt")
        val o = camData.u32(cameraId * 4)
        val views = camData.u16(o + 0x12)
        var zones: List<List<AtticCameraZonePoint>> = emptyList()
        var masks: List<AtticData.Mask> = emptyList()
        for (k in 0 until views) {
            val e = o + 0x14 + k * VIEW_SIZE
            if (camData.u16(e) != roomIndex) continue
            masks = readMasks(camData, o + camData.u16(e + 2))
            zones = readZones(camData, o + camData.u16(e + 4))
        }
        val def = AtticCameraDef(
            index = cameraId,
            posX = camData.s16(o + 6).toFloat(), posY = camData.s16(o + 8).toFloat(), posZ = camData.s16(o + 10).toFloat(),
            angleAlpha = camData.u16(o) and 0x3FF, angleBeta = camData.u16(o + 2) and 0x3FF, angleGamma = camData.u16(o + 4) and 0x3FF,
            focal1 = camData.u16(o + 12).toFloat(), focal2 = camData.u16(o + 14).toFloat(), focal3 = camData.u16(o + 16).toFloat(),
            zones = zones,
            roomX = room.worldX.toFloat(), roomY = room.worldY.toFloat(), roomZ = room.worldZ.toFloat(),
        )
        return RoomCamera(def, masks)
    }

    /** The 320x200 picture of camera [cameraId] as ARGB through [palette]. */
    fun picture(cameraId: Int, palette: IntArray): IntArray {
        val raw = pictures.get(cameraId + 1) ?: throw OriginalFilesException("Kamerabild $cameraId fehlt")
        return IntArray(320 * 200) { palette[raw.u8(it)] }
    }

    private fun readZones(d: ByteArray, off: Int): List<List<AtticCameraZonePoint>> {
        var p = off
        val n = d.u16(p)
        p += 2
        return List(n) {
            val pts = d.u16(p)
            p += 2
            List(pts) { i -> AtticCameraZonePoint(d.s16(p + i * 4).toFloat(), d.s16(p + i * 4 + 2).toFloat()) }.also { p += pts * 4 }
        }
    }

    private fun readMasks(d: ByteArray, base: Int): List<AtticData.Mask> {
        val n = d.u16(base)
        var p = base + 2
        return List(n) {
            val zoneCount = d.u16(p)
            val polyOff = d.u16(p + 2)
            val rects = IntArray(zoneCount * 4) { k -> d.s16(p + 4 + k * 2) }
            p += 4 + zoneCount * 8
            var q = base + polyOff
            val polyCount = d.u16(q)
            q += 2
            val polys = List(polyCount) {
                val pts = d.u16(q)
                q += 2
                IntArray(pts * 2) { k -> d.s16(q + k * 2) }.also { q += pts * 4 }
            }
            AtticData.Mask(rects, polys)
        }
    }

    companion object {
        /** Size of one entry of a camera's table of viewed rooms. */
        private const val VIEW_SIZE = 12

        /** Opens stage [number] (ETAGEnn.PAK + CAMERAnn.PAK). */
        fun open(files: GameFiles, number: Int): OriginalStage {
            val nn = "%02d".format(number)
            val etage = Pak(files.read("ETAGE$nn.PAK") ?: throw OriginalFilesException("ETAGE$nn.PAK fehlt"))
            val roomData = etage.get(1) ?: throw OriginalFilesException("ETAGE$nn.PAK: Raumdaten fehlen")
            val camData = etage.get(2) ?: throw OriginalFilesException("ETAGE$nn.PAK: Kameradaten fehlen")
            val pictures = Pak(files.read("CAMERA$nn.PAK") ?: throw OriginalFilesException("CAMERA$nn.PAK fehlt"))
            // the room table lists ascending offsets; the first value that is not one (or lies outside the data) ends it
            val offsets = ArrayList<Int>()
            var prev = -1
            var i = 0
            while (i * 4 + 4 <= roomData.size) {
                val o = roomData.u32(i * 4)
                if (o <= prev || o >= roomData.size) break
                offsets.add(o)
                prev = o
                i++
            }
            val rooms = offsets.mapIndexed { index, base ->
                val hardOff = base + roomData.u16(base)
                val count = roomData.u16(hardOff)
                val hard = IntArray(count * AtticData.HARDCOL_STRIDE)
                for (c in 0 until count) for (k in 0 until AtticData.HARDCOL_STRIDE) {
                    val p = hardOff + 2 + c * 16 + k * 2
                    hard[c * AtticData.HARDCOL_STRIDE + k] = if (k < 6) roomData.s16(p) else roomData.u16(p)
                }
                val cams = IntArray(roomData.u16(base + 10)) { roomData.u16(base + 12 + it * 2) }
                // scene zones (doors, triggers, floor kinds): same layout as the hard colliders
                val zoneOff = base + roomData.u16(base + 2)
                val zoneCount = roomData.u16(zoneOff)
                val zones = IntArray(zoneCount * AtticData.HARDCOL_STRIDE)
                for (c in 0 until zoneCount) for (k in 0 until AtticData.HARDCOL_STRIDE) {
                    val p = zoneOff + 2 + c * 16 + k * 2
                    zones[c * AtticData.HARDCOL_STRIDE + k] = if (k < 6) roomData.s16(p) else roomData.u16(p)
                }
                Room(index, roomData.s16(base + 4), roomData.s16(base + 6), roomData.s16(base + 8), hard, cams, zones)
            }
            return OriginalStage(roomData, camData, pictures, rooms)
        }
    }
}
