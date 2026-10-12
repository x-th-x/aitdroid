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
) {
    /** The rooms that the doors (zones of type 0) of this room lead to. */
    val linkedRooms: List<Int> = zones.filter { it.type == 0 }.map { it.parameter }.distinct()

    /** The corners of the room's colliders: x1, x2, z1, z2 (a place beyond them is outside the room; used to hide parked objects). */
    val bounds: IntArray = if (hardcols.isEmpty()) intArrayOf(-100000, 100000, -100000, 100000) else intArrayOf(hardcols.minOf { it.x1 }, hardcols.maxOf { it.x2 }, hardcols.minOf { it.z1 }, hardcols.maxOf { it.z2 })
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
        )
    }

    private fun zones(v: IntArray) = List(v.size / AtticData.HARDCOL_STRIDE) { i ->
        val o = i * AtticData.HARDCOL_STRIDE
        Zone(v[o], v[o + 1], v[o + 2], v[o + 3], v[o + 4], v[o + 5], v[o + 6], v[o + 7])
    }
}
