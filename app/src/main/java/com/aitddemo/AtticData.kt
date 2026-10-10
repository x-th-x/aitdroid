package com.aitddemo

/**
 * Room data of the attic as read from the original files at runtime ([AtticOriginalReader]); [install] makes it
 * the data the collision and the renderer use.
 */
object AtticData {
    const val HARDCOL_STRIDE = 8

    /** Background mask: shown over an actor when its ZV (/10) lies inside one of [rects] (x1,z1,x2,z2 each); [polys] are screen polygons (x,y pairs, 320x200). */
    class Mask(val rects: IntArray, val polys: List<IntArray>)

    /** Hard colliders of room 0 as stored in ETAGE00.PAK: x1,x2,y1,y2,z1,z2,parameter,type (8 values each). */
    @Volatile
    var HARDCOLS: IntArray = IntArray(0)
        private set

    /** Masks per camera (index = camera). */
    @Volatile
    var MASKS: List<List<Mask>> = emptyList()
        private set

    fun install(data: AtticOriginal) {
        HARDCOLS = data.hardcols
        MASKS = data.masks
        AtticCollision.configure(data.player.halfWidth, data.player.height)
        AtticCollision.reset()
    }
}
