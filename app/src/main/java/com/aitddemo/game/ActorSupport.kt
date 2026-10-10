package com.aitddemo.game

/** Box (zone) in room coordinates: hard collider (obstacle) or scene zone (door, trigger). */
class Zone(val x1: Int, val x2: Int, val y1: Int, val y2: Int, val z1: Int, val z2: Int, val parameter: Int, val type: Int) {
    /** Is the point inside? (isPointInZV of the original, borders included) */
    fun contains(x: Int, y: Int, z: Int) = x in x1..x2 && y in y1..y2 && z in z1..z2
}

/** State of one bone group in an animation keyframe: kind (0 rotation, 1 translation, 2 zoom) and the three values. */
class GroupState(var type: Int = 0, var x: Int = 0, var y: Int = 0, var z: Int = 0) {
    fun set(o: GroupState) {
        type = o.type
        x = o.x
        y = o.y
        z = o.z
    }
}

/** What the actor needs to know about a body: number of animated groups, their rest state and the body's bounding box (x1, x2, y1, y2, z1, z2). */
class ActorRig(val groupCount: Int, val rest: Array<GroupState>, val zv: IntArray)
