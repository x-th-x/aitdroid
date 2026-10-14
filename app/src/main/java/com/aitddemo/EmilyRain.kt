package com.aitddemo

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.sin

/*
 * Emily's intro in the rain (NOT in the original game; an addition on this branch): outside the house it rains, lightly at first and then harder and harder, and Emily carries an umbrella.
 * The rain is drawn over the picture as streaks and heard as a steady noise that swells; the umbrella is a small model made by code that is held over her head.
 */

/** How hard it rains [seconds] after the start of the intro: 0 = a few drops, 1 = a downpour; the rain grows slowly over the first minute. */
fun rainLevel(seconds: Float): Float = (seconds / 55f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }

private fun hash(n: Int): Float {
    var x = n * 374761393 + 668265263
    x = (x xor (x ushr 13)) * 1274126177
    x = x xor (x ushr 16)
    return (x and 0xFFFF) / 65535f
}

/** The rain over the picture: [level] sets the number, the length and the brightness of the streaks and how dark the air gets. */
@Composable
fun RainOverlay(level: Float, seconds: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        drawRect(Color(0xFF0A1220).copy(alpha = 0.04f + 0.2f * level)) // the sky gets darker
        val n = (14 + 330 * level).toInt()
        val slant = 0.18f
        for (i in 0 until n) {
            val near = hash(i * 3 + 1)
            val speed = 0.7f + 1.4f * near // screens per second
            val ph = (seconds * speed * 0.9f + hash(i * 7 + 2) * 7f) % 1f
            val y = ph * 1.2f * h - 0.1f * h
            val x = ((hash(i * 11 + 3) * 1.3f - 0.15f) * w) - y * slant
            val len = (0.012f + 0.04f * near) * h * (0.7f + 0.6f * level)
            val a = (0.12f + 0.3f * near) * (0.5f + 0.5f * level)
            drawLine(Color(0xFFBCCBE2).copy(alpha = a.coerceAtMost(0.6f)), Offset(x, y), Offset(x - len * slant, y - len), strokeWidth = (1f + 1.6f * near) * (w / 1600f).coerceAtLeast(1f))
        }
    }
}

/** The sound of the rain: a soft noise in a loop whose volume follows the rain. */
class RainSound {
    private var track: AudioTrack? = null

    fun start() {
        if (track != null) return
        try {
            val rate = 11025
            val pcm = ByteArray(rate * 3)
            val rnd = java.util.Random(7)
            var lp = 0f
            var lp2 = 0f
            for (i in pcm.indices) {
                // brown-ish noise with a patter on top: the drops on leaves and the umbrella
                lp += 0.35f * ((rnd.nextFloat() * 2f - 1f) - lp)
                lp2 += 0.04f * (lp - lp2)
                val patter = if (rnd.nextFloat() > 0.992f) 0.8f else 0f
                pcm[i] = (128 + 70f * (lp * 0.7f + lp2 * 1.6f) + 40f * patter).toInt().coerceIn(0, 255).toByte()
            }
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_8BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.size)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            t.write(pcm, 0, pcm.size)
            t.setLoopPoints(0, pcm.size, -1)
            t.setVolume(0.05f)
            t.play()
            track = t
        } catch (_: Exception) {
            track = null
        }
    }

    fun level(level: Float) {
        try { track?.setVolume(0.05f + 0.5f * level) } catch (_: Exception) { }
    }

    fun stop() {
        try { track?.release() } catch (_: Exception) { }
        track = null
    }
}

/** The umbrella that Emily holds over her head: a canopy of dark red and darker red panels, ribs, a stick down to her hand; a model made by code like the other additions. */
object UmbrellaModel {
    private const val RED = 0xFF70202A.toInt()
    private const val DARK = 0xFF3C1219.toInt()
    private const val STICK = 0xFF2A2018.toInt()

    /** The umbrella over a figure at ([x], [y], [z]) turned by [beta] (engine angle, 1024 = full turn); [sway] 0..1 moves it a little as she walks. */
    fun actor(x: Float, y: Float, z: Float, beta: Int, sway: Float, scale: Float = 1f): AtticActor {
        val v = ArrayList<Float>()
        val t = ArrayList<Int>()
        val th = beta * 2f * Math.PI.toFloat() / 1024f
        val c = cos(th); val s = sin(th)
        fun vert(lx0: Float, ly0: Float, lz0: Float): Int {
            // the umbrella is as big as the figure: the body of the intro outside the house is small, the umbrella takes its scale
            val lx = lx0 * scale; val ly = ly0 * scale; val lz = lz0 * scale
            // the umbrella leans a little with her steps
            val tilt = 0.06f * sin(sway * 6.2832f)
            val tx = lx + ly * -tilt * 0.3f
            v.add(x + tx * c + lz * s); v.add(y + ly); v.add(z - tx * s + lz * c)
            return v.size / 3 - 1
        }
        val n = 10
        val apex = vert(0f, -2260f, 0f)
        val rim = IntArray(n) { k ->
            val a = k * 2f * Math.PI.toFloat() / n
            vert(cos(a) * 720f, -1990f, sin(a) * 720f)
        }
        for (k in 0 until n) {
            val c1 = rim[k]; val c2 = rim[(k + 1) % n]
            t.add(apex); t.add(c1); t.add(c2); t.add(if (k % 2 == 0) RED else DARK)
        }
        // the stick down to the hand
        fun bar(w: Float) {
            val a = vert(-w, -2260f, 0f); val b = vert(w, -2260f, 0f); val d = vert(w, -1130f, 0f); val e = vert(-w, -1130f, 0f)
            t.add(a); t.add(b); t.add(d); t.add(STICK); t.add(a); t.add(d); t.add(e); t.add(STICK)
        }
        bar(16f)
        // the hook of the handle
        val h1 = vert(-30f, -1130f, 0f); val h2 = vert(30f, -1130f, 0f); val h3 = vert(30f, -1060f, 0f); val h4 = vert(-30f, -1060f, 0f)
        t.add(h1); t.add(h2); t.add(h3); t.add(STICK); t.add(h1); t.add(h3); t.add(h4); t.add(STICK)
        val r = (700f * scale).toInt().coerceAtLeast(20)
        val actor = AtticActor(v.toFloatArray(), t.toIntArray(), x, y - 1100f * scale, z, (x / 10).toInt() - r / 10, (x / 10).toInt() + r / 10, (z / 10).toInt() - r / 10, (z / 10).toInt() + r / 10)
        actor.sortBox = intArrayOf(x.toInt() - r, x.toInt() + r, (y - 2300f * scale).toInt(), (y - 1050f * scale).toInt(), z.toInt() - r, z.toInt() + r)
        return actor
    }
}
