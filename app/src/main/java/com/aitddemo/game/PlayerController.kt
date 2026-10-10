package com.aitddemo.game

import com.aitddemo.AtticPlayer

/**
 * Carnby in the attic: the original actor logic (animations, tank controls, root motion, wall collision) with the movement script of the
 * game (life script 549: ANIM_MOVE 4, 254, 255, 4, 256, 257, 258 and the footstep samples tied to frames of the walk, run and back animations).
 */
class PlayerController(
    private val player: AtticPlayer,
    hardcols: List<Zone>,
    private val zones: List<Zone>,
    startTimer: Int,
    private val playSound: (Int) -> Unit,
) {
    private val anims = MoveAnims(4, 254, 255, 4, 256, 257, 258)
    private val groupCount = player.anims.getValue(254).frames[0].groups.size
    private val rig = ActorRig(groupCount, Array(groupCount) { GroupState() }, player.zv)
    val actor = Actor(rig, { player.anims.getValue(it) }, player.spawnX, 0, player.spawnZ, player.spawnBeta, 4, startTimer, hardcols, player.typeZv)

    /** (animation, frame) -> which footstep sample plays when that frame starts: false = the first of the two samples (var_4), true = the second (var_5). */
    private val stepFrames = mapOf(
        (254 to 2) to false, (254 to 5) to true,
        (256 to 1) to true, (256 to 3) to false,
        (255 to 1) to true, (255 to 3) to false,
    )

    /** Samples of the floor the player stands on: carpet (zone type 9, parameter 1) or wood. */
    private fun stepSamples(): Pair<Int, Int> {
        val x = actor.renderX
        val z = actor.renderZ
        val onCarpet = zones.any { it.type == 9 && it.parameter == 1 && it.contains(x, 0, z) }
        return if (onCarpet) 46 to 47 else 30 to 31
    }

    /** One game tick: keys [joy] (see [Joy]), time [timer] in ticks (60 per second). */
    fun update(joy: Int, timer: Int, run: Boolean = false) {
        // a pushed stick at full tilt keeps him running: the engine enters the run by a quick double tap of forward
        if (run && actor.speed == 4 && joy and Joy.FORWARD != 0) actor.speed = 5
        if (!run && actor.speed == 5 && joy and Joy.FORWARD != 0) actor.speed = 4
        actor.processManual(joy, timer, anims)
        actor.update(timer)
        if (actor.endFrameTick) {
            val which = stepFrames[actor.eventAnim to actor.eventFrame]
            if (which != null) {
                val (a, b) = stepSamples()
                playSound(if (which) b else a)
            }
        }
    }

    /** The posed body in world coordinates (turned by his heading, at his place), 3 values per vertex. */
    fun worldVertices(): FloatArray {
        val posed = BodyPose.pose(player.vertices, player.groups, player.groupOrder, actor.state)
        val a = actor.beta * 2.0 * Math.PI / 1024.0
        val c = Math.cos(a).toFloat()
        val s = Math.sin(a).toFloat()
        val ox = actor.renderX.toFloat()
        val oz = actor.renderZ.toFloat()
        val w = FloatArray(posed.size)
        for (i in 0 until posed.size / 3) {
            val x = posed[i * 3]
            val z = posed[i * 3 + 2]
            w[i * 3] = x * c - z * s + ox
            w[i * 3 + 1] = posed[i * 3 + 1]
            w[i * 3 + 2] = x * s + z * c + oz
        }
        return w
    }
}
