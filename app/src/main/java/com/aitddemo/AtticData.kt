package com.aitddemo

/** Shared shapes of the room data read from the original files ([RoomLoader]). */
object AtticData {
    const val HARDCOL_STRIDE = 8

    /** Background mask: shown over an actor when its ZV (/10) lies inside one of [rects] (x1,z1,x2,z2 each); [polys] are screen polygons (x,y pairs, 320x200). */
    class Mask(val rects: IntArray, val polys: List<IntArray>)

    /** Masks per camera of the room shown by default (none: every mask comes with its room). */
    val MASKS: List<List<Mask>> = emptyList()
}
