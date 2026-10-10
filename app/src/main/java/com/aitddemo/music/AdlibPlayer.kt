package com.aitddemo.music

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/** Plays ADLM tracks through the software AdLib ([AdlibSynth]) on a background thread with a streaming [AudioTrack]. */
class AdlibPlayer {
    /** Counts plays: a player thread belongs to the play with its number and ends as soon as the counter moves on (stop or a new play). */
    @Volatile
    private var generation = 0

    @Volatile
    private var currentAudio: AudioTrack? = null

    @Volatile
    private var playing = false

    val isPlaying: Boolean get() = playing

    /** Whether the track starts again when it ends; can be changed while it plays. */
    @Volatile
    var looping = false

    /** Starts [track]; with [loop] it restarts when it has ended. Replaces a track that is playing. [onEnded] runs on the player thread when a non-looping track ends. */
    @Synchronized
    fun play(track: ByteArray, loop: Boolean = true, onEnded: (() -> Unit)? = null) {
        stop()
        looping = loop
        val mine = ++generation
        val t = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val rate = 44100
            val minBuffer = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val audio = try {
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(maxOf(minBuffer * 4, 32768))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (_: Exception) {
                return@Thread
            }
            currentAudio = audio
            playing = true
            try {
                val synth = AdlibSynth(rate)
                synth.start(track)
                val block = ShortArray(1500)
                // fill the buffer before starting so playback does not run dry at the start
                repeat(8) {
                    synth.render(block, 0, block.size)
                    writeAll(audio, block, mine)
                }
                if (generation == mine) audio.play()
                var ended = false
                while (generation == mine) {
                    synth.render(block, 0, block.size)
                    if (!writeAll(audio, block, mine)) break
                    if (synth.finished) {
                        if (looping) {
                            synth.start(track)
                        } else {
                            ended = true
                            break
                        }
                    }
                }
                if (ended && generation == mine) onEnded?.invoke()
            } catch (_: Exception) {
                // stop playing on any device audio problem
            } finally {
                try {
                    audio.stop()
                } catch (_: Exception) {
                }
                audio.release()
                if (currentAudio === audio) currentAudio = null
                if (generation == mine) playing = false
            }
        }
        t.start()
    }

    /** Writes the block without ever blocking for long, so a stop request is noticed at once; false when playback should end. */
    private fun writeAll(audio: AudioTrack, block: ShortArray, mine: Int): Boolean {
        var off = 0
        while (off < block.size) {
            if (generation != mine) return false
            val n = audio.write(block, off, block.size - off, AudioTrack.WRITE_NON_BLOCKING)
            if (n < 0) return false
            if (n == 0) Thread.sleep(4) else off += n
        }
        return true
    }

    /** Stops the music at once; nothing waits for the player thread, it cleans up on its own. */
    @Synchronized
    fun stop() {
        generation++
        playing = false
        try {
            currentAudio?.pause()
            currentAudio?.flush()
        } catch (_: Exception) {
        }
    }
}
