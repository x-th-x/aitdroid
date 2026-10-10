package com.aitddemo

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.aitddemo.original.VocSample

/** Plays the sound samples read from LISTSAMP.PAK (8 bit unsigned mono). Several samples can sound at once; finished ones are released. */
class IntroSounds(private val lookup: (Int) -> VocSample?) {
    constructor(samples: Map<Int, VocSample>) : this({ n -> samples[n] })

    private val active = ArrayList<AudioTrack>()

    /**
     * Plays sample [number]. [pitch] changes the speed (1 = as recorded), [loop] repeats it until [stopAll], [then] is a sample that follows when this one has
     * ended ([thenLoop]: and is repeated).
     */
    @Synchronized
    fun play(number: Int, pitch: Float = 1f, loop: Boolean = false, then: Int = -1, thenLoop: Boolean = false) {
        val s = lookup(number) ?: return
        try {
            while (active.size >= MAX_ACTIVE) active.removeAt(0).release()
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(s.rate).setEncoding(AudioFormat.ENCODING_PCM_8BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                )
                .setBufferSizeInBytes(s.pcm.size)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(s.pcm, 0, s.pcm.size)
            if (pitch != 1f) track.playbackRate = (s.rate * pitch).toInt().coerceIn(2000, 48000)
            if (loop) {
                track.setLoopPoints(0, s.pcm.size, -1)
            } else {
                track.setNotificationMarkerPosition(s.pcm.size)
                track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(t: AudioTrack) {
                        val released = synchronized(this@IntroSounds) { active.remove(t) }
                        if (released) {
                            t.release()
                            if (then >= 0) play(then, pitch, thenLoop)
                        }
                    }

                    override fun onPeriodicNotification(t: AudioTrack) {}
                })
            }
            active.add(track)
            track.play()
        } catch (_: Exception) {
            // carry on without this sound
        }
    }

    /** STOP_SAMPLE: everything that sounds is cut off (a sample that was waiting for another one does not start it). */
    @Synchronized
    fun stopAll() {
        release()
    }

    @Synchronized
    fun release() {
        for (t in active) {
            try {
                t.release()
            } catch (_: Exception) {
            }
        }
        active.clear()
    }

    private companion object {
        const val MAX_ACTIVE = 12
    }
}
