package com.aitddemo

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.aitddemo.original.OriginalFontData
import com.aitddemo.original.VocSample

/** The pictures, font and sounds of the start screens and menus, read from the original files ([StartOriginalReader]) before the logo and then kept for all screens. */
object StartAssets {
    var original by mutableStateOf<StartOriginal?>(null)
        private set

    private val bitmaps = HashMap<IntArray, ImageBitmap>()
    private var fontAtlasFor: OriginalFontData? = null
    private var fontAtlas: ImageBitmap? = null

    fun install(data: StartOriginal) {
        bitmaps.clear()
        fontAtlasFor = null
        fontAtlas = null
        original = data
    }

    val font: OriginalFontData? get() = original?.font

    /** The font's glyph atlas: white glyphs on transparent, cells as in [OriginalFontData]. */
    fun atlas(): ImageBitmap? {
        val f = font ?: return null
        if (fontAtlasFor !== f) {
            val px = IntArray(f.alpha.size) { if (f.alpha[it].toInt() != 0) 0xFFFFFFFF.toInt() else 0 }
            fontAtlas = Bitmap.createBitmap(px, f.atlasWidth, f.atlasHeight, Bitmap.Config.ARGB_8888).asImageBitmap()
            fontAtlasFor = f
        }
        return fontAtlas
    }

    /** A 320 x 200 ARGB picture of [StartOriginal] as an image (made once). */
    fun picture(argb: IntArray): ImageBitmap = bitmaps.getOrPut(argb) { Bitmap.createBitmap(argb, 320, 200, Bitmap.Config.ARGB_8888).asImageBitmap() }

    fun playThunder() = play(original?.thunder)

    fun playPageTurn() = play(original?.pageTurn)

    private fun play(s: VocSample?) {
        if (s == null) return
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(s.rate).setEncoding(AudioFormat.ENCODING_PCM_8BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(s.pcm.size)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(s.pcm, 0, s.pcm.size)
            track.setNotificationMarkerPosition(s.pcm.size)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) = t.release()
                override fun onPeriodicNotification(t: AudioTrack) {}
            })
            track.play()
        } catch (_: Exception) {
            // carry on without the sound
        }
    }
}

enum class StartPicture { LOGO, TITLE, MENU, SELECT }

/** One of the full screen pictures, fitted into the screen (black where it is not available). */
@androidx.compose.runtime.Composable
fun StartPictureImage(kind: StartPicture, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
    val o = StartAssets.original ?: return
    val argb = when (kind) {
        StartPicture.LOGO -> o.logo
        StartPicture.TITLE -> o.title
        StartPicture.MENU -> o.menu
        StartPicture.SELECT -> o.select
    }
    androidx.compose.foundation.Image(
        bitmap = StartAssets.picture(argb),
        contentDescription = null,
        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        modifier = modifier,
    )
}
