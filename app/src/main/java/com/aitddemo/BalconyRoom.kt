package com.aitddemo

import com.aitddemo.game.Zone

/**
 * The balcony outside the window of the attic as a room of the game (not in the original game): room [NUMBER] of floor 0. It has the frame of the attic (same origin), one
 * camera ([BalconyView.camera]) and the colliders of the railing and the wall; it is not linked to the attic by a door, the game takes the player out and back in by itself
 * (AtticGame.stepOutToBalcony and stepBackIn).
 */
object BalconyRoom {
    const val NUMBER = 1

    /** Where the player stands when he steps out (in front of the window, facing out) and when he comes back in (inside, facing away from the window). */
    const val OUT_Z = 5650
    const val IN_Z = 4650

    /** The opening of the door in the wall of the attic: whoever walks into it with the door open (x within [DOOR_X], z from [DOOR_Z] on) is out on the balcony. */
    const val DOOR_X = 650
    const val DOOR_Z = 4600

    /** The wall of the house has the opening of the window: the player walks into it (x within [EXIT_X], z below [EXIT_Z]) to go back in. */
    const val EXIT_X = 700
    const val EXIT_Z = 5450

    fun view(palette: IntArray): RoomView {
        fun box(x1: Int, x2: Int, z1: Int, z2: Int) = Zone(x1, x2, -6000, 2000, z1, z2, 0, 0)
        val hard = listOf(
            box(-2800, -1750, 5000, 8400), // the railing on the left
            box(1750, 2800, 5000, 8400), // on the right
            box(-2800, 2800, 7450, 8400), // in front
            box(-2800, -760, 5000, 5400), // the wall of the house beside the window
            box(760, 2800, 5000, 5400),
        )
        // what climbs up the outside of the railing (beyond z = 7450) is hidden by the floor and the railing: the mask puts the painted floor and railing back over it
        val proj = AtticProjector(BalconyView.camera)
        val o = FloatArray(3)
        fun at(x: Float, y: Float, z: Float): IntArray { proj.project(x, y, z, o); return intArrayOf(o[0].toInt(), o[1].toInt()) }
        val tl = at(-1850f, -1000f, 7540f)
        val tr = at(1850f, -1000f, 7540f)
        val mask = AtticData.Mask(intArrayOf(-300, 744, 300, 900), listOf(intArrayOf(tl[0] - 6, tl[1], tr[0] + 6, tr[1], 320, 200, 0, 200)))
        return RoomView(
            0, NUMBER, 0, 0, 0, hard, emptyList(),
            listOf(BalconyView.camera), listOf(listOf(mask)), listOf(mapOf(NUMBER to listOf(mask))),
            listOf(BalconyView.paint(palette)),
            listOf(0),
        )
    }
}
