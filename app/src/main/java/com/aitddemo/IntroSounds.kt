package com.aitddemo

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.aitddemo.original.VocSample

/** Plays the sound samples read from LISTSAMP.PAK (8 bit unsigned mono). Several samples can sound at once; finished ones are released. */
class IntroSounds(private val lookup: (Int) -> VocSample?) {
    constructor(samples: Map<Int, VocSample>) : this({ n -> samples[n] })

    private val active = ArrayList<AudioTrack>()

    @Synchronized
    fun play(number: Int) {
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
            track.setNotificationMarkerPosition(s.pcm.size)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) {
                    synchronized(this@IntroSounds) {
                        if (active.remove(t)) t.release()
                    }
                }

                override fun onPeriodicNotification(t: AudioTrack) {}
            })
            active.add(track)
            track.play()
        } catch (_: Exception) {
            // carry on without this sound
        }
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
