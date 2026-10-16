package com.aitddemo

import com.aitddemo.game.Zone
import com.aitddemo.original.GameFiles

/**
 * One room of the house as the game needs it: where it lies in the world, its colliders and zones, and for each of its cameras the picture, the masks
 * and the camera definition. Read from ETAGEnn.PAK / CAMERAnn.PAK of the original files.
 */
class RoomView(
    val stage: Int,
    val room: Int,
    val worldX: Int,
    val worldY: Int,
    val worldZ: Int,
    val hardcols: List<Zone>,
    val zones: List<Zone>,
    val cameras: List<AtticCameraDef>,
    val masks: List<List<AtticData.Mask>>,
    /** For each camera: the masks of every room the camera sees (room number -> masks, in the coordinates of that room). */
    val maskViews: List<Map<Int, List<AtticData.Mask>>>,
    /** 320x200 ARGB picture of each camera. */
    val photos: List<IntArray>,
    /** The number of each camera's picture in CAMERAnn.PAK: the same camera has the same number in every room that lists it. */
    val pictureIds: List<Int> = emptyList(),
) {
    /** The rooms that the doors (zones of type 0) of this room lead to. */
    val linkedRooms: List<Int> = zones.filter { it.type == 0 }.map { it.parameter }.distinct()

    /** The corners of the room's colliders: x1, x2, z1, z2 (a place beyond them is outside the room; used to hide parked objects). */
    val bounds: IntArray = if (hardcols.isEmpty()) intArrayOf(-100000, 100000, -100000, 100000) else intArrayOf(hardcols.minOf { it.x1 }, hardcols.maxOf { it.x2 }, hardcols.minOf { it.z1 }, hardcols.maxOf { it.z2 })
}

/** A room as the map of the house draws it: its place in the world of its floor (x to the east, z to the south, in game units), and its doors. */
class RoomOutline(val room: Int, val x1: Int, val x2: Int, val z1: Int, val z2: Int, val originX: Int, val originZ: Int, val doors: List<Door>) {
    /** A door (room-change zone) to room [target] at world place (x, z). */
    class Door(val target: Int, val x: Int, val z: Int)
}

/** Reads rooms (and the floors that hold them) of the original files when they are first needed and keeps them. */
class RoomLoader(private val files: GameFiles, private val palette: IntArray) {
    private val stages = HashMap<Int, OriginalStage>()
    private val rooms = HashMap<Long, RoomView>()

    /** The room [room] of floor [stage]; throws [OriginalFilesException] when the files do not have it. */
    @Synchronized
    fun room(stage: Int, room: Int): RoomView = rooms.getOrPut(stage * 1000L + room) {
        val st = stages.getOrPut(stage) { OriginalStage.open(files, stage) }
        val r = st.rooms.firstOrNull { it.index == room } ?: throw OriginalFilesException("Etage $stage: Raum $room fehlt")
        val cameras = r.cameraIds.map { st.camera(room, it) }
        RoomView(
            stage, room, r.worldX, r.worldY, r.worldZ,
            zones(r.hardcols), zones(r.zones),
            cameras.mapIndexed { index, c -> c.camera.copy(index = index) },
            cameras.map { it.masks },
            cameras.map { it.maskViews },
            r.cameraIds.map { st.picture(it, palette) },
            r.cameraIds.toList(),
        )
    }

    /**
     * The rooms of floor [stage] with their places in the world (light: no pictures are read). A room lies at (worldX * 10, -worldZ * 10) of its floor; its colliders give its walls.
     * Rooms without colliders (not used by the game) are left out.
     */
    @Synchronized
    fun outlines(stage: Int): List<RoomOutline> {
        val st = stages.getOrPut(stage) { OriginalStage.open(files, stage) }
        return st.rooms.mapNotNull { r ->
            val hc = r.hardcols
            if (hc.size < AtticData.HARDCOL_STRIDE) return@mapNotNull null
            var x1 = Int.MAX_VALUE; var x2 = Int.MIN_VALUE; var z1 = Int.MAX_VALUE; var z2 = Int.MIN_VALUE
            for (i in 0 until hc.size / AtticData.HARDCOL_STRIDE) {
                val o = i * AtticData.HARDCOL_STRIDE
                x1 = minOf(x1, hc[o]); x2 = maxOf(x2, hc[o + 1]); z1 = minOf(z1, hc[o + 4]); z2 = maxOf(z2, hc[o + 5])
            }
            val ox = r.worldX * 10; val oz = -r.worldZ * 10
            val doors = ArrayList<RoomOutline.Door>()
            for (i in 0 until r.zones.size / AtticData.HARDCOL_STRIDE) {
                val o = i * AtticData.HARDCOL_STRIDE
                if (r.zones[o + 7] == 0) doors.add(RoomOutline.Door(r.zones[o + 6], ox + (r.zones[o] + r.zones[o + 1]) / 2, oz + (r.zones[o + 4] + r.zones[o + 5]) / 2))
            }
            RoomOutline(r.index, ox + x1, ox + x2, oz + z1, oz + z2, ox, oz, doors)
        }
    }

    private fun zones(v: IntArray) = List(v.size / AtticData.HARDCOL_STRIDE) { i ->
        val o = i * AtticData.HARDCOL_STRIDE
        Zone(v[o], v[o + 1], v[o + 2], v[o + 3], v[o + 4], v[o + 5], v[o + 6], v[o + 7])
    }
}
