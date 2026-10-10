package com.aitddemo.music

/**
 * Plays an ADLM track through [AdlibDriver] and [Opl2]: the driver is stepped every [SAMPLES_PER_TICK] samples (the engine's music
 * timer) and the chip renders the samples in between.
 */
class AdlibSynth(private val sampleRate: Int = 44100) {
    private val opl = Opl2(sampleRate)
    private val driver = AdlibDriver(opl)
    private var untilTick = 0

    /** True when the current track has played to its end. */
    val finished: Boolean get() = driver.finished

    fun start(track: ByteArray) {
        driver.start(track)
        untilTick = 0
    }

    /** Renders [count] mono samples into [out] starting at [offset]. */
    fun render(out: ShortArray, offset: Int, count: Int) {
        var done = 0
        while (done < count) {
            if (untilTick == 0) {
                driver.tick()
                untilTick = SAMPLES_PER_TICK
            }
            val n = minOf(untilTick, count - done)
            opl.generate(out, offset + done, n)
            done += n
            untilTick -= n
        }
    }

    companion object {
        /** The engine calls the music driver every 1500 bytes of 16 bit mono output at 44.1 kHz. */
        const val SAMPLES_PER_TICK = 750
    }
}
