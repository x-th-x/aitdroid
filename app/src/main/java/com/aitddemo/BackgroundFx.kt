package com.aitddemo

import android.graphics.Bitmap

/**
 * The moving lights of the pictures of one room (see [BackgroundFxCore]; not in the original game): holds a copy of each picture that has lights, redraws the places of the
 * lights about 14 times per second and gives the picture that the renderer draws (the original picture when the camera has no lights).
 */
class BackgroundFx(stage: Int, room: Int, private val photos: List<Bitmap>, pictureIds: List<Int> = emptyList()) {
    private val sets: List<FxSet?> = photos.indices.map {
        if (stage == 0 && room == 0) BackgroundFxCore.attic(it) else if (stage == 0 && room == BalconyRoom.NUMBER) BackgroundFxCore.balcony() else if (stage == 1) pictureIds.getOrNull(it)?.let { id -> BackgroundFxCore.floor1(id) } else null
    }

    /** Has the picture of [camera] a window (rain and the flash of lightning shine in)? */
    fun hasWindow(camera: Int): Boolean = sets.getOrNull(camera)?.rain?.isNotEmpty() == true
    private val base = arrayOfNulls<IntArray>(photos.size)
    private val out = arrayOfNulls<IntArray>(photos.size)
    private val work = arrayOfNulls<Bitmap>(photos.size)
    private val lastMs = LongArray(photos.size) { -1_000_000_000_000L }

    /** The picture of [camera] at [nowMs] (milliseconds of a steady clock). */
    fun frame(camera: Int, nowMs: Long, lampOn: Boolean = true): Bitmap {
        val i = camera.coerceIn(0, photos.size - 1)
        val set = sets[i] ?: return photos[i]
        val w = photos[i].width
        val h = photos[i].height
        if (work[i] == null) {
            val px = IntArray(w * h)
            photos[i].getPixels(px, 0, w, 0, 0, w, h)
            base[i] = px
            out[i] = px.copyOf()
            work[i] = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
        if (nowMs - lastMs[i] >= UPDATE_MS) {
            lastMs[i] = nowMs
            BackgroundFxCore.render(base[i]!!, out[i]!!, w, w / 320, set, nowMs / 1000f, lampOn)
            work[i]!!.setPixels(out[i]!!, 0, w, 0, 0, w, h)
        }
        return work[i]!!
    }

    private companion object {
        const val UPDATE_MS = 70L
    }
}
