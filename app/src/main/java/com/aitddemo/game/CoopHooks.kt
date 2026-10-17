package com.aitddemo.game

/** Where the other player's hero is to be shown right now (room-relative coordinates of the engine, heading in 1/1024 turns, animation number). */
class RemotePose(val stage: Int, val room: Int, val x: Int, val y: Int, val z: Int, val beta: Int, val anim: Int, val silentMs: Double = 0.0)

/**
 * Co-op prototype: the source of the other hero. When [AtticGame.remoteHero] is set, the companion figure is not moved by the game's own follow logic any more; it is put where
 * this source says (the network layer fills it from the states the other phone sends, smoothed) and plays the animation it reports. It ignores all local input.
 */
fun interface RemoteHeroSource {
    /** The pose to show now, or null when nothing is known yet (the figure is then hidden). Called on the game thread once per tick. */
    fun pose(): RemotePose?
}
