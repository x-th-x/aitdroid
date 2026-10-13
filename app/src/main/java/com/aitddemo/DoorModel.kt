package com.aitddemo

import kotlin.math.cos
import kotlin.math.sin

/**
 * The glass door to the balcony in the wall of the attic (not in the original game, which has a window in this place): a frame and two leaves with panes, built from flat polygons
 * like the bodies of the game. The leaves swing into the room when the door is opened ([open] 0 = closed .. 1 = wide open).
 */
object DoorModel {
    private const val HALF = 750f // half width of the opening
    private const val HEIGHT = 2100f
    private const val WALL_Z = 5000f
    private const val LEAF = 730f
    private const val OPEN_ANGLE = 100f * Math.PI.toFloat() / 180f

    private const val WOOD = 0xFF5C3E2A.toInt()
    private const val WOOD_DARK = 0xFF432D1E.toInt()
    private const val WOOD_LIGHT = 0xFF7A5539.toInt()
    private const val GLASS = 0xFF3A5068.toInt()
    private const val GLASS_LIGHT = 0xFF4E6C88.toInt()

    /** The door as a thing to draw (world coordinates of the attic). */
    fun actor(open: Float): AtticActor {
        val v = ArrayList<Float>()
        val t = ArrayList<Int>()
        fun vert(x: Float, y: Float, z: Float): Int { v.add(x); v.add(y); v.add(z); return v.size / 3 - 1 }
        fun quad(a: Int, b: Int, c: Int, d: Int, color: Int) { t.add(a); t.add(b); t.add(c); t.add(color); t.add(a); t.add(c); t.add(d); t.add(color) }

        // the frame in the wall: two posts, the header and the threshold
        val fz = WALL_Z - 60f
        fun flat(x1: Float, x2: Float, y1: Float, y2: Float, color: Int) = quad(vert(x1, y1, fz), vert(x2, y1, fz), vert(x2, y2, fz), vert(x1, y2, fz), color)
        flat(-HALF - 70f, -HALF, -HEIGHT - 70f, 0f, WOOD_DARK)
        flat(HALF, HALF + 70f, -HEIGHT - 70f, 0f, WOOD_DARK)
        flat(-HALF - 70f, HALF + 70f, -HEIGHT - 70f, -HEIGHT, WOOD_DARK)
        flat(-HALF - 70f, HALF + 70f, -30f, 0f, WOOD_LIGHT)

        val theta = open.coerceIn(0f, 1f) * OPEN_ANGLE
        val ct = cos(theta); val st = sin(theta)
        val zh = WALL_Z - 30f
        for (side in intArrayOf(-1, 1)) {
            val hx = side * HALF // the hinge
            val dirX = -side * ct // the leaf runs from the hinge towards the middle, swinging into the room
            val dirZ = -st
            // the side of the leaf that faces the room (the normal), used to put the panes a little in front of the wood
            val nx = -(-side) * st * 1f
            val nz = -ct
            fun p(d: Float, y: Float, lift: Float = 0f) = vert(hx + dirX * d + nx * lift, y, zh + dirZ * d + nz * lift)
            // the leaf: wood, a raised panel in the lower part, three panes above it
            quad(p(0f, 0f), p(LEAF, 0f), p(LEAF, -HEIGHT), p(0f, -HEIGHT), WOOD)
            quad(p(95f, -90f, 8f), p(LEAF - 95f, -90f, 8f), p(LEAF - 95f, -520f, 8f), p(95f, -520f, 8f), WOOD_DARK)
            quad(p(125f, -120f, 14f), p(LEAF - 125f, -120f, 14f), p(LEAF - 125f, -490f, 14f), p(125f, -490f, 14f), WOOD_LIGHT)
            val tops = floatArrayOf(-1980f, -1520f, -1060f)
            for (y in tops) {
                quad(p(95f, y, 8f), p(LEAF - 95f, y, 8f), p(LEAF - 95f, y + 400f, 8f), p(95f, y + 400f, 8f), GLASS)
                // a gleam across the upper left of every pane
                quad(p(110f, y + 20f, 12f), p(300f, y + 20f, 12f), p(190f, y + 150f, 12f), p(110f, y + 150f, 12f), GLASS_LIGHT)
            }
        }
        val actor = AtticActor(
            v.toFloatArray(), t.toIntArray(), 0f, -HEIGHT / 2f, WALL_Z,
            (-HALF - 70f).toInt() / 10, (HALF + 70f).toInt() / 10, (WALL_Z - 100f).toInt() / 10, (WALL_Z + 100f).toInt() / 10,
        )
        actor.sortBox = intArrayOf((-HALF - 70f).toInt(), (HALF + 70f).toInt(), (-HEIGHT - 70f).toInt(), 0, (WALL_Z - 100f).toInt(), (WALL_Z + 100f).toInt())
        return actor
    }
}
