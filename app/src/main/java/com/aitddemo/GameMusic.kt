package com.aitddemo

import com.aitddemo.music.AdlibPlayer

/**
 * The game's one music player. Like the original engine, music is not tied to a screen: the intro starts music 7 (fade_music(7) of the car's
 * life script) and it keeps playing, without looping, until it ends or another screen stops it. The attic therefore continues the intro's
 * music instead of starting a track of its own.
 */
object GameMusic {
    private val player = AdlibPlayer()

    /** Number of the music (entry number + 1 in LISTMUS.PAK) that was last started, -1 = none. */
    @Volatile
    var current = -1
        private set

    /** Starts music [number] from the beginning (replacing what plays). */
    fun start(number: Int, data: ByteArray) {
        current = number
        player.play(data, loop = player.looping)
    }

    /** Whether the music starts again when it ends (the attic loops it); turning it on also restarts music that already ended. */
    fun setLooping(loop: Boolean, number: Int, data: ByteArray) {
        player.looping = loop
        if (loop && current == number && !player.isPlaying) start(number, data)
    }

    /** Makes sure music [number] has been started in this session; if it is already running or has run, nothing changes (no restart). */
    fun ensure(number: Int, data: ByteArray) {
        if (current != number) start(number, data)
    }

    fun stop() {
        player.stop()
        current = -1
    }
}
