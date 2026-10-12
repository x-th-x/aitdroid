package com.aitddemo.game

/**
 * Emily Hartwood, the second player character of the game, as the enemy of the attic: her body is entry 12 of LISTBOD2.PAK (the file that holds the bodies the game
 * uses when Emily is played; entry 12 of LISTBODY.PAK is Carnby). She has the skeleton of Carnby, so she is moved with the animations of the player; the enemy's
 * scripts keep their own animation numbers, which are turned into the player's here.
 */
object EmilyBody {
    /** The body number Emily has in the game (the original files have no such number). */
    const val BODY = 1001

    /** Her entry in LISTBOD2.PAK. */
    const val SOURCE_BODY = 12

    /**
     * The enemy's animation numbers (19 climbing out, 15 walking, 14 waiting, 17 attack, 16 hit, 18 death) and the animations of the player that show them for Emily:
     * standing (4, without a step, as the climb is made by the path), the slow walk (245), the left punch (41, without a step), the hit (237) and the fall (261).
     */
    val ANIMS: Map<Int, Int> = mapOf(19 to 4, 15 to 245, 14 to 4, 17 to 41, 16 to 237, 18 to 261)

    /** The animation of the original that is played for the number [n] the scripts use on a body [body]. */
    fun animFor(body: Int, n: Int): Int = if (body == BODY) ANIMS[n] ?: n else n
}
