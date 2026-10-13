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
    const val OUT_Z = 5900
    const val IN_Z = 4650

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
        return RoomView(
            0, NUMBER, 0, 0, 0, hard, emptyList(),
            listOf(BalconyView.camera), listOf(emptyList()), listOf(emptyMap()),
            listOf(BalconyView.paint(palette)),
            listOf(0),
        )
    }
}
