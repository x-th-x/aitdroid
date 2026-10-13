package com.aitddemo

import com.aitddemo.original.VocSample
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Moving light on the camera pictures (NOT in the original game; an addition on this branch). The pictures of the original are still images; here a few places of them
 * get light that changes: the moonlight at the windows (with now and then a flash of lightning), the flame of the candle on the piano, dust that floats in the moonlight.
 * Everything is added to the pixels of the user's own picture at runtime, nothing of it is stored. Places are in the pixels of the 320 x 200 picture.
 */

/** Where a light lies. */
sealed class FxShape {
    class Rect(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : FxShape()
    class Circle(val cx: Float, val cy: Float, val r: Float) : FxShape()
    class Quad(val xs: FloatArray, val ys: FloatArray) : FxShape()
}

/** How the strength of a light changes with the time. */
enum class FxWave { MOON, CANDLE, STORM, CLOUD, DRAFT }

/** A light of one colour (r, g, b added at full strength) on a [shape]; [strength] 1 = the colour is added fully. */
class FxLight(val shape: FxShape, val r: Int, val g: Int, val b: Int, val strength: Float, val wave: FxWave, val seed: Int = 0, val lampOnly: Boolean = false)

/** Rain that runs down a window pane: [count] drops, each a short bright streak that slides from the top to the bottom of the rectangle. */
class FxRain(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val count: Int, val strength: Float)

/** Dust that floats in a rectangle: [count] specks of a pale colour. */
class FxDust(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val count: Int, val strength: Float)

/** A bat that flies across a window pane in the light of a flash of lightning (now and then). */
class FxBat(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

/** A rat that runs from (x0, y0) to (x1, y1) on the floor; [s0] and [s1] are its size at the start and at the end (1 = 8 pixels long). */
class FxRat(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val s0: Float, val s1: Float)

/** What happens in the house now and then (sounds and the rocking horse); the places of the times are fixed by the clock, so the sound and the picture agree. */
enum class Ambient { THUNDER, CREAK, RAT, HORSE, DRIP, WIND, VANISH }

/** Moths that circle a light ([lampOnly]: only while the oil lamp is there). */
class FxMoth(val cx: Float, val cy: Float, val r: Float, val count: Int, val lampOnly: Boolean = false)

/** A drop of water that falls from [y0] to [y1] at [x] now and then and makes a ring on the floor. */
class FxDrip(val x: Float, val y0: Float, val y1: Float)

/** Steam that rises from a rectangle [x1]..[x2] at height [y] by [rise] pixels: [count] soft pale puffs that grow and fade. */
class FxSteam(val x1: Float, val x2: Float, val y: Float, val rise: Float, val count: Int)

/** A thing of the picture (the rectangle) that dissolves after some flashes of lightning and forms again somewhere else ((bedX, bedY) = bottom centre there, [bedScale] its size): its place is painted over with the wall beside it. */
class FxVanish(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val bedX: Float, val bedY: Float, val bedScale: Float)

/** Rain that falls over the whole picture (a view into the open): [count] slanted streaks, near ones fast and bright, far ones slow and faint. */
class FxStormRain(val count: Int)

/** A spider that lets itself down on a thread from [y0] to [y1] at [x], hangs there for a while and climbs up again. */
class FxSpider(val x: Float, val y0: Float, val y1: Float)

class AmbientEvent(val t: Float, val kind: Ambient, val strength: Float)

/** The lights of one camera. */
class FxSet(val lights: List<FxLight>, val dust: List<FxDust>, val rain: List<FxRain> = emptyList(), val bats: List<FxBat> = emptyList(), val rats: List<FxRat> = emptyList(), val moths: List<FxMoth> = emptyList(), val drips: List<FxDrip> = emptyList(), val spiders: List<FxSpider> = emptyList(), val steam: List<FxSteam> = emptyList(), val vanish: List<FxVanish> = emptyList(), val storm: FxStormRain? = null)

object BackgroundFxCore {
    /** The attic: stage 0, room 0, cameras 0 to 4 (places found by looking at the pictures). */
    fun attic(camera: Int): FxSet? = when (camera) {
        0 -> FxSet(
            listOf(
                FxLight(FxShape.Rect(145f, 30f, 168f, 50f), 70, 95, 140, 0.9f, FxWave.MOON),
                FxLight(FxShape.Circle(58f, 39f, 10f), 255, 165, 70, 0.75f, FxWave.CANDLE, 1),
                // the oil lamp on the table (while it is still there): a warm light on the cloth and the floor around it
                FxLight(FxShape.Circle(27f, 160f, 30f), 255, 150, 60, 0.5f, FxWave.CANDLE, 3, lampOnly = true),
            ),
            listOf(FxDust(110f, 52f, 200f, 120f, 12, 0.5f)),
            listOf(FxRain(145f, 30f, 168f, 50f, 4, 1f)),
            listOf(FxBat(145f, 30f, 168f, 50f)),
            listOf(FxRat(290f, 78f, 215f, 74f, 0.9f, 0.8f)),
            listOf(FxMoth(58f, 39f, 8f, 3), FxMoth(27f, 150f, 14f, 3, lampOnly = true)),
        )
        1 -> FxSet(
            listOf(
                FxLight(FxShape.Rect(97f, 83f, 112f, 97f), 70, 95, 140, 0.9f, FxWave.MOON),
                FxLight(FxShape.Quad(floatArrayOf(128f, 152f, 100f, 55f), floatArrayOf(136f, 136f, 200f, 200f)), 40, 52, 80, 0.9f, FxWave.CLOUD),
            ),
            listOf(FxDust(78f, 118f, 150f, 190f, 20, 0.8f)),
            listOf(FxRain(97f, 83f, 112f, 97f, 3, 1f)),
            listOf(FxBat(97f, 83f, 112f, 97f)),
            listOf(FxRat(30f, 116f, 130f, 113f, 0.8f, 0.9f)),
        )
        2 -> FxSet(
            listOf(
                FxLight(FxShape.Rect(143f, 5f, 156f, 32f), 70, 95, 140, 0.9f, FxWave.MOON),
                FxLight(FxShape.Circle(51f, 57f, 11f), 255, 165, 70, 0.75f, FxWave.CANDLE, 2),
            ),
            listOf(FxDust(130f, 40f, 215f, 130f, 12, 0.5f)),
            listOf(FxRain(143f, 5f, 156f, 32f, 3, 1f)),
            listOf(FxBat(143f, 5f, 156f, 32f)),
            listOf(FxRat(270f, 112f, 185f, 86f, 1.4f, 1.1f)),
            listOf(FxMoth(51f, 57f, 8f, 3)),
            listOf(FxDrip(165f, 70f, 122f)),
            listOf(FxSpider(186f, 0f, 44f)),
        )
        3 -> FxSet(emptyList(), emptyList(), emptyList(), emptyList(), listOf(FxRat(260f, 112f, 150f, 96f, 1.6f, 1.3f)))
        4 -> FxSet(emptyList(), emptyList(), emptyList(), emptyList(), listOf(FxRat(318f, 122f, 200f, 100f, 2.0f, 1.5f)))
        else -> null
    }

    private val WINDOW_LIGHT = intArrayOf(70, 95, 140)

    private fun window(x1: Float, y1: Float, x2: Float, y2: Float) = FxLight(FxShape.Rect(x1, y1, x2, y2), WINDOW_LIGHT[0], WINDOW_LIGHT[1], WINDOW_LIGHT[2], 0.9f, FxWave.MOON)
    private fun curtain(x1: Float, y1: Float, x2: Float, y2: Float, r: Int, g: Int, b: Int, seed: Int) = FxLight(FxShape.Rect(x1, y1, x2, y2), r, g, b, 0.3f, FxWave.DRAFT, seed)
    private fun gasLamp(x: Float, y: Float, seed: Int) = FxLight(FxShape.Circle(x, y, 11f), 255, 200, 110, 0.6f, FxWave.CANDLE, seed)

    /** The balcony (room 1 of the attic floor): the flash of lightning lights everything, rain falls over the whole picture. */
    fun balcony(): FxSet = FxSet(
        listOf(FxLight(FxShape.Rect(0f, 0f, 320f, 200f), 34, 46, 70, 0.8f, FxWave.STORM)),
        emptyList(),
        storm = FxStormRain(110),
    )

    /** Floor 1 (ETAGE01): the effects of the pictures of CAMERA01.PAK by their number (places found by looking at the pictures). */
    fun floor1(picture: Int): FxSet? = when (picture) {
        // the hall with the wall lamps
        2 -> FxSet(listOf(gasLamp(70f, 25f, 11)), emptyList())
        3 -> FxSet(listOf(gasLamp(166f, 28f, 12)), emptyList())
        // the room with the window and the chaise longue
        5 -> FxSet(
            listOf(window(84f, 12f, 122f, 46f), curtain(92f, 0f, 148f, 80f, 210, 170, 170, 1)),
            listOf(FxDust(60f, 60f, 200f, 120f, 8, 0.4f)),
            listOf(FxRain(84f, 12f, 122f, 46f, 3, 1f)),
        )
        // the four-poster bedroom with the window
        7 -> FxSet(
            listOf(
                window(33f, 64f, 70f, 126f),
                FxLight(FxShape.Quad(floatArrayOf(40f, 90f, 110f, 10f), floatArrayOf(140f, 140f, 200f, 200f)), 40, 52, 80, 0.35f, FxWave.CLOUD),
                curtain(12f, 52f, 25f, 165f, 200, 110, 110, 2),
                curtain(68f, 62f, 80f, 158f, 200, 110, 110, 3),
            ),
            listOf(FxDust(10f, 130f, 110f, 200f, 10, 0.6f)),
            listOf(FxRain(33f, 64f, 70f, 126f, 4, 1f)),
            vanish = listOf(FxVanish(169f, 102f, 186f, 119f, 108f, 176f, 1.3f)), // the teddy bear on the chest of drawers; it wanders to the bed
        )
        // the red room with the wardrobe and the window on the left
        10 -> FxSet(
            listOf(window(6f, 50f, 34f, 100f), curtain(20f, 0f, 55f, 130f, 120, 190, 170, 4)),
            listOf(FxDust(10f, 60f, 100f, 150f, 6, 0.4f)),
            listOf(FxRain(6f, 50f, 34f, 100f, 3, 1f)),
        )
        // the bathroom: the shower drips into the bath, steam over the water, a flicker in the mirror
        11 -> FxSet(
            listOf(FxLight(FxShape.Rect(82f, 78f, 110f, 102f), 150, 170, 170, 0.25f, FxWave.CANDLE, 5)),
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            listOf(FxDrip(157f, 50f, 113f)),
            emptyList(),
            listOf(FxSteam(132f, 174f, 112f, 40f, 6)),
        )
        12 -> FxSet(emptyList(), emptyList(), steam = listOf(FxSteam(6f, 124f, 138f, 45f, 7)))
        else -> null
    }

    /** The strength of a wave at [t] seconds (0..1.3). The flashes of lightning come at a fixed, irregular rhythm of about 20 seconds. */
    fun intensity(wave: FxWave, t: Float, seed: Int): Float = when (wave) {
        FxWave.MOON -> 0.30f + 0.12f * sin(t * 2f * PI.toFloat() / 7f) + 0.10f * sin(t * 2f * PI.toFloat() / 2.3f) + storm(t) * 1.0f
        FxWave.CANDLE -> (0.55f + 0.25f * noise(t * 9f, seed) + 0.15f * noise(t * 23f, seed + 7) + gust(t) * (noise(t * 31f, seed + 3) - 0.75f) * 1.1f).coerceIn(0.05f, 1f)
        FxWave.STORM -> storm(t)
        FxWave.DRAFT -> ((0.35f + 0.35f * sin(t * 1.7f + seed) + 0.3f * sin(t * 0.63f + seed * 2f)).coerceAtLeast(0f) * (0.6f + gust(t) * 0.8f)) + storm(t) * 0.5f
        FxWave.CLOUD -> (0.22f + 0.2f * sin(t * 2f * PI.toFloat() / 31f) + 0.14f * sin(t * 2f * PI.toFloat() / 11.3f)).coerceAtLeast(0.03f) + storm(t)
    }

    /** The strength of the flash of lightning now (0 when there is none); the figures in the room are lit by it too. */
    fun flash(t: Float): Float = storm(t)

    private const val STORM_SLOT = 20f
    private const val CREAK_SLOT = 37f
    private const val RAT_SLOT = 45f
    private const val HORSE_SLOT = 61f
    private const val RAT_SECONDS = 3.2f
    private const val DRIP_SLOT = 9.7f
    private const val WIND_SLOT = 53f
    const val WIND_SECONDS = 6f
    private const val SPIDER_SLOT = 43f
    private const val SPIDER_SECONDS = 13f

    private fun windAt(slot: Int) = slot * WIND_SLOT + 8f + 25f * hash01(slot * 11 + 4)

    /** The strength of the gust of wind that blows round the roof now (0..1; the candles flicker, the sound is [windSample]). */
    fun gust(t: Float): Float {
        val slot = floor(t / WIND_SLOT).toInt()
        val d = t - windAt(slot)
        if (d < 0f || d > WIND_SECONDS) return 0f
        val s = sin(d / WIND_SECONDS * PI.toFloat())
        return s * s
    }

    /** The sound of a drop of water that falls into a puddle (a short bright tone that falls in pitch). */
    val dripSample: VocSample by lazy {
        val rate = 8000
        val pcm = ByteArray(rate / 4)
        var ph = 0.0
        for (i in pcm.indices) {
            val s = i / rate.toDouble()
            ph += 2.0 * PI * (2100.0 - 3000.0 * s) / rate
            pcm[i] = (128 + 80.0 * sin(ph) * Math.exp(-s * 26.0)).toInt().coerceIn(0, 255).toByte()
        }
        VocSample(rate, pcm)
    }

    /** The sound of the wind in the roof: filtered noise that swells and dies away with a faint howl. */
    val windSample: VocSample by lazy {
        val rate = 8000
        val n = (rate * WIND_SECONDS).toInt()
        val pcm = ByteArray(n)
        val rnd = java.util.Random(5)
        var lp = 0f
        var lp2 = 0f
        for (i in 0 until n) {
            val s = i / rate.toFloat()
            val env = sin(s / WIND_SECONDS * PI.toFloat()).let { it * it }
            lp += 0.05f * (rnd.nextFloat() * 2f - 1f - lp)
            lp2 += 0.3f * (lp - lp2)
            val howl = 0.08f * sin(2f * PI.toFloat() * (380f + 70f * sin(s * 1.1f)) * s)
            pcm[i] = (128 + 127f * env * (lp2 * 5.5f + howl * (0.5f + 0.5f * sin(s * 2.3f)))).toInt().coerceIn(0, 255).toByte()
        }
        VocSample(rate, pcm)
    }

    private fun flashAt(slot: Int) = slot * STORM_SLOT + 4f + 12f * hash01(slot * 31 + 5)
    private fun ratAt(slot: Int) = slot * RAT_SLOT + 12f + 20f * hash01(slot * 19 + 1)

    /** The events between [from] (not included) and [to] (included) seconds: the thunder that follows each flash after a while, a creak of the house, a rat, the horse. */
    fun events(from: Float, to: Float): List<AmbientEvent> {
        val out = ArrayList<AmbientEvent>()
        fun scan(slotLen: Float, at: (Int) -> Float, kind: Ambient, strength: (Int) -> Float) {
            for (slot in floor(from / slotLen).toInt() - 1..floor(to / slotLen).toInt()) {
                val a = at(slot)
                if (a > from && a <= to) out.add(AmbientEvent(a, kind, strength(slot)))
            }
        }
        scan(STORM_SLOT, { flashAt(it) + 1.2f + 2.8f * hash01(it * 17 + 3) }, Ambient.THUNDER) { 0.5f + 0.5f * hash01(it * 5 + 9) }
        scan(CREAK_SLOT, { it * CREAK_SLOT + 5f + 25f * hash01(it * 13 + 9) }, Ambient.CREAK) { 0.6f + 0.4f * hash01(it * 3 + 1) }
        scan(RAT_SLOT, { ratAt(it) }, Ambient.RAT) { 1f }
        scan(DRIP_SLOT, { it * DRIP_SLOT + 0.55f }, Ambient.DRIP) { 1f }
        scan(WIND_SLOT, { windAt(it) }, Ambient.WIND) { 1f }
        for (slot in floor(from / STORM_SLOT).toInt() - 1..floor(to / STORM_SLOT).toInt()) {
            if (vanishOnBed(slot) == vanishOnBed(slot - 1)) continue
            val a = flashAt(slot) + VANISH_START
            if (a > from && a <= to) out.add(AmbientEvent(a, Ambient.VANISH, 1f)) // it dissolves
            val b = a + VANISH_SECONDS + VANISH_GAP
            if (b > from && b <= to) out.add(AmbientEvent(b, Ambient.VANISH, 0f)) // it forms again at the other place
        }
        scan(HORSE_SLOT, { it * HORSE_SLOT + 20f + 30f * hash01(it * 23 + 2) }, Ambient.HORSE) { 1f }
        return out.sortedBy { it.t }
    }

    /** Lightning: in every slot of 20 seconds one flash (two peaks) at a place of the slot that comes from the slot number. */
    private fun storm(t: Float): Float {
        val slot = floor(t / 20f).toInt()
        val at = 4f + 12f * hash01(slot * 31 + 5)
        val d = t - slot * 20f - at
        if (d < 0f || d > 0.9f) return 0f
        val a = (1f - d / 0.9f)
        return a * (0.6f + 0.4f * sin(d * 40f)).coerceAtLeast(0.2f)
    }

    private fun hash01(n: Int): Float {
        var x = n * 374761393 + 668265263
        x = (x xor (x ushr 13)) * 1274126177
        x = x xor (x ushr 16)
        return (x and 0xFFFF) / 65535f
    }

    /** Smooth noise (-1..1... mapped to 0..1) of a value. */
    private fun noise(v: Float, seed: Int): Float {
        val i = floor(v).toInt()
        val f = v - i
        val a = hash01(i * 7919 + seed * 104729)
        val b = hash01((i + 1) * 7919 + seed * 104729)
        val s = f * f * (3f - 2f * f)
        return a + (b - a) * s
    }

    /**
     * Writes into [out] (the picture scaled by [scale] from 320 x 200, [width] pixels wide) the picture [base] with the lights of [fx] at time [t] seconds: only the pixels
     * near a light or a speck are touched, the rest of [out] must already hold [base].
     */
    fun render(base: IntArray, out: IntArray, width: Int, scale: Int, fx: FxSet, t: Float, lampOn: Boolean = true) {
        val height = base.size / width
        // put the original pixels back where the last frame changed something
        for (l in fx.lights) restore(base, out, width, height, scale, boundsOf(l.shape))
        for (d in fx.dust) restore(base, out, width, height, scale, floatArrayOf(d.x1 - 3f, d.y1 - 3f, d.x2 + 3f, d.y2 + 3f))
        for (r in fx.rain) restore(base, out, width, height, scale, floatArrayOf(r.x1, r.y1, r.x2 + 1f, r.y2 + 1f))
        for (bt in fx.bats) restore(base, out, width, height, scale, floatArrayOf(bt.x1 - 6f, bt.y1 - 6f, bt.x2 + 6f, bt.y2 + 6f))
        for (rt in fx.rats) restore(base, out, width, height, scale, floatArrayOf(minOf(rt.x0, rt.x1) - 14f, minOf(rt.y0, rt.y1) - 10f, maxOf(rt.x0, rt.x1) + 14f, maxOf(rt.y0, rt.y1) + 10f))
        for (m in fx.moths) restore(base, out, width, height, scale, floatArrayOf(m.cx - m.r - 2f, m.cy - m.r - 2f, m.cx + m.r + 2f, m.cy + m.r + 2f))
        for (dr in fx.drips) restore(base, out, width, height, scale, floatArrayOf(dr.x - 7f, dr.y0 - 1f, dr.x + 7f, dr.y1 + 4f))
        if (fx.storm != null) restore(base, out, width, height, scale, floatArrayOf(0f, 0f, 320f, 200f))
        for (vn in fx.vanish) {
            restore(base, out, width, height, scale, floatArrayOf(vn.x1 - 32f, vn.y1 - 52f, vn.x2 + 32f, vn.y2 + 24f))
            restore(base, out, width, height, scale, floatArrayOf(vn.bedX - 36f, vn.bedY - 64f, vn.bedX + 36f, vn.bedY + 20f))
        }
        for (st in fx.steam) restore(base, out, width, height, scale, floatArrayOf(st.x1 - 8f, st.y - st.rise - 10f, st.x2 + 8f, st.y + 4f))
        for (sp in fx.spiders) restore(base, out, width, height, scale, floatArrayOf(sp.x - 5f, sp.y0, sp.x + 5f, sp.y1 + 5f))
        if (fx.vanish.isNotEmpty()) {
            val vs = vanishState(t)
            for (vn in fx.vanish) {
                if (vs.first < 1f) paintOver(base, out, width, scale, vn, vs.first)
                if (vs.bed > 0f) drawOnBed(base, out, width, scale, vn, vs.bed)
                if (vs.effect == 1) vanishEffects(out, width, height, scale, (vn.x1 + vn.x2) / 2f, (vn.y1 + vn.y2) / 2f, vs.progress, vs.appearing)
                if (vs.effect == 2) vanishEffects(out, width, height, scale, vn.bedX, vn.bedY - 10f * vn.bedScale, vs.progress, vs.appearing)
            }
        }
        for (l in fx.lights) {
            if (l.lampOnly && !lampOn) continue
            val k = intensity(l.wave, t, l.seed) * l.strength
            val bb = boundsOf(l.shape)
            val x0 = (bb[0] * scale).toInt().coerceIn(0, width - 1); val x1 = (bb[2] * scale).toInt().coerceIn(0, width - 1)
            val y0 = (bb[1] * scale).toInt().coerceIn(0, height - 1); val y1 = (bb[3] * scale).toInt().coerceIn(0, height - 1)
            for (y in y0..y1) for (x in x0..x1) {
                val w = weight(l.shape, (x + 0.5f) / scale, (y + 0.5f) / scale)
                if (w <= 0f) continue
                add(out, y * width + x, (l.r * k * w).toInt(), (l.g * k * w).toInt(), (l.b * k * w).toInt())
            }
        }
        for (r in fx.rain) rain(out, width, height, scale, r, t)
        for (bt in fx.bats) bat(out, width, height, scale, bt, t)
        for (rt in fx.rats) rat(out, width, height, scale, rt, t)
        fx.storm?.let { stormRain(out, width, height, scale, it.count, t) }
        for (m in fx.moths) if (!m.lampOnly || lampOn) moths(out, width, height, scale, m, t)
        for (dr in fx.drips) drip(out, width, height, scale, dr, t)
        for (sp in fx.spiders) spider(out, width, height, scale, sp, t)
        for (st in fx.steam) steam(out, width, height, scale, st, t)
        val lit = storm(t) > 0.25f
        for ((n, d) in fx.dust.withIndex()) {
            for (i in 0 until d.count) {
                val h1 = hash01(i * 17 + n * 1000 + 3); val h2 = hash01(i * 29 + n * 1000 + 11); val h3 = hash01(i * 43 + n * 1000 + 19)
                val w = d.x2 - d.x1; val hgt = d.y2 - d.y1
                // each speck sinks slowly, wraps around and sways; it twinkles
                val fall = (t * (1.2f + 1.5f * h3) + h2 * hgt) % hgt
                val px = d.x1 + ((h1 * w + 4f * sin(t * (0.3f + h3 * 0.5f) + i)) % w + w) % w
                val py = d.y1 + fall
                val tw = 0.35f + 0.65f * (0.5f + 0.5f * sin(t * (1.5f + 2f * h1) + i * 2.3f))
                val edge = minOf(fall, hgt - fall).coerceAtMost(8f) / 8f // fades in and out at the top and the bottom
                val v = (60f * d.strength * tw * edge * (intensity(FxWave.MOON, t, 0) + 0.2f)).toInt()
                val cx = (px * scale).toInt(); val cy = (py * scale).toInt()
                // in the flash of lightning the specks are brighter and bigger
                val sz = if (lit) 2 else 1
                for (yy in cy..cy + sz) for (xx in cx..cx + sz) if (xx in 0 until width && yy in 0 until height) add(out, yy * width + xx, v, v, (v * 1.1f).toInt())
            }
        }
    }

    /** Rain on a window: the drops of every pane slide down at their own speed, rest for a while at the bottom and start again; the storm makes them heavier. */
    private fun rain(out: IntArray, width: Int, height: Int, scale: Int, r: FxRain, t: Float) {
        val w = r.x2 - r.x1
        val hgt = r.y2 - r.y1
        val heavy = 0.7f + 0.6f * storm(t)
        for (i in 0 until r.count) {
            val h1 = hash01(i * 13 + 101); val h2 = hash01(i * 31 + 103); val h3 = hash01(i * 47 + 107)
            val speed = 4f + 7f * h2
            val period = hgt / speed + 1.2f + 2.5f * h3
            val y = (((t + h1 * period * 3f) % period)) * speed
            if (y > hgt) continue
            val x = r.x1 + 1f + h1 * (w - 2f) + 0.4f * sin(t * 1.3f + i)
            for (k in 0..3) { // the streak: bright at the head, fainter above it
                val yy = r.y1 + y - k
                if (yy < r.y1) break
                val v = (80f * r.strength * heavy * (1f - k * 0.27f)).toInt()
                val px = (x * scale).toInt()
                val py = (yy * scale).toInt()
                for (dy in 0 until scale) for (dx in 0 until scale) {
                    val xx = px + dx
                    val y2 = py + dy
                    if (xx in 0 until width && y2 in 0 until height) add(out, y2 * width + xx, v, (v * 1.15f).toInt(), (v * 1.4f).toInt())
                }
            }
        }
    }

    /** A bat in front of a window during a flash of lightning (every second flash or so): a dark body with two wings that beat. */
    private fun bat(out: IntArray, width: Int, height: Int, scale: Int, b: FxBat, t: Float) {
        val slot = floor(t / STORM_SLOT).toInt()
        if (hash01(slot * 53 + 17) > 0.55f) return
        val d = t - flashAt(slot)
        if (d < 0f || d > 0.9f) return
        val w = b.x2 - b.x1
        val x = b.x1 - 2f + (d / 0.9f) * (w + 4f)
        val y = (b.y1 + b.y2) / 2f + 3f * sin(d * 11f)
        val up = sin(d * 38f) > 0f
        shade(out, width, height, scale, x, y, 0.9f, 0.7f, 6, 6, 12, 0.95f) // the body
        for (k in 1..3) { // the wings, the tips go up and down
            val dy = if (up) -k * 0.6f else k * 0.5f
            shade(out, width, height, scale, x - k, y + dy, 0.8f, 0.5f, 6, 6, 12, 0.9f)
            shade(out, width, height, scale, x + k, y + dy, 0.8f, 0.5f, 6, 6, 12, 0.9f)
        }
    }

    /** A rat that runs along the floor now and then, as a dark shape in the picture (a body, a head with an ear, a tail that wiggles, feet that patter). */
    private fun rat(out: IntArray, width: Int, height: Int, scale: Int, r: FxRat, t: Float) {
        val slot = floor(t / RAT_SLOT).toInt()
        val d = t - ratAt(slot)
        if (d < 0f || d > RAT_SECONDS) return
        val p = d / RAT_SECONDS
        val x = r.x0 + (r.x1 - r.x0) * p
        val y = r.y0 + (r.y1 - r.y0) * p
        val s = r.s0 + (r.s1 - r.s0) * p
        val dir = if (r.x1 >= r.x0) 1f else -1f
        val len = 8f * s
        val r0 = 22; val g0 = 17; val b0 = 14
        val fade = minOf(p, 1f - p).coerceAtMost(0.12f) / 0.12f
        val a = 0.9f * fade
        // body
        shade(out, width, height, scale, x, y, len * 0.5f, 1.6f * s, r0, g0, b0, a)
        shade(out, width, height, scale, x - dir * len * 0.2f, y - 0.4f * s, len * 0.3f, 1.4f * s, r0, g0, b0, a)
        // head and ear
        shade(out, width, height, scale, x + dir * len * 0.55f, y, len * 0.22f, 1.0f * s, r0 + 8, g0 + 6, b0 + 5, a)
        shade(out, width, height, scale, x + dir * len * 0.45f, y - 1.3f * s, 0.7f * s, 0.7f * s, r0 + 10, g0 + 7, b0 + 6, a)
        // tail
        for (k in 1..6) shade(out, width, height, scale, x - dir * (len * 0.5f + k * 0.7f * s), y + 0.6f * s * sin(t * 12f + k), 0.5f * s, 0.35f * s, r0 + 20, g0 + 14, b0 + 12, a * 0.8f)
        // feet that patter
        val step = sin(t * 26f)
        shade(out, width, height, scale, x + dir * len * 0.25f + step, y + 1.5f * s, 0.5f * s, 0.4f * s, r0, g0, b0, a)
        shade(out, width, height, scale, x - dir * len * 0.2f - step, y + 1.5f * s, 0.5f * s, 0.4f * s, r0, g0, b0, a)
    }

    /** Moths that flutter round a light in loops of their own, pale where the light catches them. */
    private fun moths(out: IntArray, width: Int, height: Int, scale: Int, m: FxMoth, t: Float) {
        for (i in 0 until m.count) {
            val h1 = hash01(i * 7 + 301); val h2 = hash01(i * 11 + 303)
            val ang = t * (1.4f + 1.6f * h1) + i * 2.1f
            val rr = m.r * (0.45f + 0.5f * (0.5f + 0.5f * sin(t * (0.5f + h2) + i)))
            val x = m.cx + kotlin.math.cos(ang) * rr
            val y = m.cy + sin(ang * 1.3f) * rr * 0.7f
            val wing = 0.45f + 0.35f * abs(sin(t * 38f + i)) // the wings beat
            shade(out, width, height, scale, x, y, wing + 0.3f, 0.45f, 215, 200, 170, 0.85f)
        }
    }

    /** A drop that falls from the roof now and then and a ring that spreads where it lands; the sound comes with the same clock ([events]). */
    private fun drip(out: IntArray, width: Int, height: Int, scale: Int, d: FxDrip, t: Float) {
        val slot = floor(t / DRIP_SLOT).toInt()
        val u = t - slot * DRIP_SLOT // the drop lands at 0.55 s of the slot
        val fallStart = 0.05f
        if (u in fallStart..0.55f) {
            val p = (u - fallStart) / (0.55f - fallStart)
            val y = d.y0 + (d.y1 - d.y0) * p * p // it speeds up
            shade(out, width, height, scale, d.x, y, 0.5f, 1.1f, 150, 175, 200, 0.8f)
        } else if (u > 0.55f && u < 1.6f) {
            val p = (u - 0.55f) / 1.05f
            val rad = 1f + 5f * p
            val a = 0.5f * (1f - p)
            // a ring: two ellipses, the inner one in the colour of what is below
            shade(out, width, height, scale, d.x, d.y1 + 1f, rad, rad * 0.35f, 150, 170, 190, a)
            if (rad > 2.2f) shade(out, width, height, scale, d.x, d.y1 + 1f, rad - 1.3f, (rad - 1.3f) * 0.35f, 70, 62, 55, a * 1.2f)
        }
    }

    private const val VANISH_START = 0.1f
    private const val VANISH_SECONDS = 1.6f
    private const val VANISH_GAP = 0.15f

    /** Where the thing is after the flash of [slot]: on the bed (true) or in its first place (false); about every second flash it goes to the other place. */
    private fun vanishOnBed(slot: Int) = hash01(slot * 7 + 2) > 0.5f

    /**
     * How much of the thing is there at [t] at its first place ([first]) and at the bed ([bed]; 1 = all, 0 = nothing), and the glow that goes with a change: [effect] 1 = at the
     * first place, 2 = at the bed, 0 = none, with its [progress] 0..1 and the direction ([appearing]). After a flash it dissolves where it was, and then forms again at the other place.
     */
    class VanishState(val first: Float, val bed: Float, val effect: Int, val progress: Float, val appearing: Boolean)

    fun vanishState(t: Float): VanishState {
        val slot = floor(t / STORM_SLOT).toInt()
        val prev = vanishOnBed(slot - 1)
        val cur = vanishOnBed(slot)
        fun steady(onBed: Boolean) = VanishState(if (onBed) 0f else 1f, if (onBed) 1f else 0f, 0, -1f, false)
        val t0 = flashAt(slot) + VANISH_START
        if (prev == cur || t < t0) return steady(prev)
        val t1 = t0 + VANISH_SECONDS
        if (t < t1) {
            val p = (t - t0) / VANISH_SECONDS
            return VanishState(if (prev) 0f else 1f - p, if (prev) 1f - p else 0f, if (prev) 2 else 1, p, false)
        }
        val t2 = t1 + VANISH_GAP
        if (t < t2) return VanishState(0f, 0f, 0, -1f, false)
        val t3 = t2 + VANISH_SECONDS
        if (t < t3) {
            val p = (t - t2) / VANISH_SECONDS
            return VanishState(if (cur) 0f else p, if (cur) p else 0f, if (cur) 2 else 1, p, true)
        }
        return steady(cur)
    }

    /** The sounds of the change: a shimmer that falls in pitch when the thing goes and rises when it comes back. */
    val vanishSample: VocSample by lazy { shimmer(false) }
    val appearSample: VocSample by lazy { shimmer(true) }

    private fun shimmer(rising: Boolean): VocSample {
        val rate = 8000
        val n = (rate * VANISH_SECONDS).toInt()
        val pcm = ByteArray(n)
        val rnd = java.util.Random(if (rising) 11 else 12)
        var ph = 0.0
        var ph2 = 0.0
        for (i in 0 until n) {
            val s = i / rate.toFloat()
            val p = s / VANISH_SECONDS
            val q = if (rising) p else 1f - p
            val env = (sin(p * PI.toFloat())).let { it * it } * (if (rising) 0.5f + 0.5f * p else 1.2f - 0.5f * p)
            ph += 2.0 * PI * (350.0 + 1700.0 * q * q) / rate
            ph2 += 2.0 * PI * (520.0 + 2550.0 * q * q) / rate // a fifth above: a glassy chord
            val trem = 0.65f + 0.35f * sin(s * 2f * PI.toFloat() * 14f)
            val v = (0.55f * sin(ph) + 0.3f * sin(ph2)).toFloat() * trem + 0.12f * (rnd.nextFloat() * 2f - 1f) * (1f - p)
            pcm[i] = (128 + 120f * env * v).toInt().coerceIn(0, 255).toByte()
        }
        return VocSample(rate, pcm)
    }

    /** Paints the rectangle with the picture beside it on the left (a flat wall) for the pixels that are no longer there: each row repeats the three pixels left of the rectangle. */
    private fun paintOver(base: IntArray, out: IntArray, width: Int, scale: Int, v: FxVanish, visible: Float) {
        val height = base.size / width
        val x0 = (v.x1 * scale).toInt().coerceIn(3 * scale, width - 1); val x1 = (v.x2 * scale).toInt().coerceIn(0, width - 1)
        val y0 = (v.y1 * scale).toInt().coerceIn(0, height - 1); val y1 = (v.y2 * scale).toInt().coerceIn(0, height - 1)
        val span = 3 * scale
        for (y in y0..y1) for (x in x0..x1) {
            // it dissolves pixel by pixel (blocks of a picture pixel), from the top, in a random order
            val key = hash01((x / scale) * 131 + (y / scale) * 7919) * 0.8f + 0.2f * ((y - y0).toFloat() / (y1 - y0 + 1))
            if (key >= visible) out[y * width + x] = base[y * width + x0 - span + (x - x0) % span]
        }
    }

    /** Draws the thing (the pixels of its first place that differ from the wall beside it) again at the bed, [k] times as big, as far as [visible] says (it forms pixel by pixel). */
    private fun drawOnBed(base: IntArray, out: IntArray, width: Int, scale: Int, v: FxVanish, visible: Float) {
        val height = base.size / width
        val sx0 = (v.x1 * scale).toInt().coerceIn(3 * scale, width - 1); val sx1 = (v.x2 * scale).toInt().coerceIn(0, width - 1)
        val sy0 = (v.y1 * scale).toInt().coerceIn(0, height - 1); val sy1 = (v.y2 * scale).toInt().coerceIn(0, height - 1)
        val span = 3 * scale
        val k = v.bedScale
        val w = ((sx1 - sx0 + 1) * k).toInt(); val h = ((sy1 - sy0 + 1) * k).toInt()
        val dx0 = (v.bedX * scale).toInt() - w / 2; val dy0 = (v.bedY * scale).toInt() - h
        for (dy in 0 until h) for (dx in 0 until w) {
            val x = dx0 + dx; val y = dy0 + dy
            if (x !in 0 until width || y !in 0 until height) continue
            val sx = sx0 + (dx / k).toInt(); val sy = sy0 + (dy / k).toInt()
            if (sx > sx1 || sy > sy1) continue
            val p = base[sy * width + sx]
            val wall = base[sy * width + sx0 - span + (sx - sx0) % span]
            val diff = abs(((p shr 16) and 0xFF) - ((wall shr 16) and 0xFF)) + abs(((p shr 8) and 0xFF) - ((wall shr 8) and 0xFF)) + abs((p and 0xFF) - (wall and 0xFF))
            if (diff < 30) continue // the wall, not the thing
            val key = hash01((x / scale) * 131 + (y / scale) * 7919 + 17) * 0.8f + 0.2f * (dy.toFloat() / h)
            if (key < visible) out[y * width + x] = p
        }
    }

    /** The glow, the ring and the sparkles round the thing while it goes or comes back (p = 0..1 of the change). */
    private fun vanishEffects(out: IntArray, width: Int, height: Int, scale: Int, cx: Float, cy: Float, p: Float, appearing: Boolean) {
        val pulse = sin(p * PI.toFloat())
        // a cold glow that swells and dies
        val rg = 28f
        val x0 = ((cx - rg) * scale).toInt().coerceIn(0, width - 1); val x1 = ((cx + rg) * scale).toInt().coerceIn(0, width - 1)
        val y0 = ((cy - rg) * scale).toInt().coerceIn(0, height - 1); val y1 = ((cy + rg) * scale).toInt().coerceIn(0, height - 1)
        val ring = if (appearing) (1f - p) * 26f else p * 26f // the ring flies out when it goes, in when it comes back
        for (y in y0..y1) for (x in x0..x1) {
            val dx = (x + 0.5f) / scale - cx
            val dy = (y + 0.5f) / scale - cy
            val d = sqrt(dx * dx + dy * dy)
            var k = 0f
            if (d < rg) k += (1f - d / rg).let { it * it } * pulse * 0.75f
            val rd = abs(d - ring)
            if (rd < 1.6f) k += (1f - rd / 1.6f) * (1f - p) * 0.9f
            if (k > 0f) add(out, y * width + x, (110 * k).toInt(), (190 * k).toInt(), (255 * k).toInt())
        }
        // sparkles that rise from it (when it goes) or sink into it (when it comes back)
        for (i in 0 until 16) {
            val h1 = hash01(i * 3 + 601); val h2 = hash01(i * 5 + 603); val h3 = hash01(i * 7 + 607)
            val q = if (appearing) 1f - p else p
            val x = cx + (h1 - 0.5f) * 14f + 4f * sin(q * 6f + i)
            val y = cy + (h2 - 0.5f) * 12f - q * (14f + 26f * h3)
            val a = pulse * (0.5f + 0.5f * sin(p * 30f + i * 1.9f))
            val px = (x * scale).toInt(); val py = (y * scale).toInt()
            for (yy in py..py + 1) for (xx in px..px + 1) if (xx in 0 until width && yy in 0 until height) add(out, yy * width + xx, (190 * a).toInt(), (235 * a).toInt(), (255 * a).toInt())
        }
    }

    private fun stormRain(out: IntArray, width: Int, height: Int, scale: Int, count: Int, t: Float) {
        val sc = scale
        for (i in 0 until count) {
            val near = hash01(i * 3 + 1)
            val speed = 120f + 160f * near
            val period = (200f + 30f) / speed
            val ph = ((t + hash01(i * 7 + 2) * period * 5f) % period) / period
            val y = ph * 230f - 15f
            val x = (hash01(i * 11 + 3) * 400f - 40f) - y * 0.22f
            val len = 3f + 6f * near
            val a = 0.2f + 0.3f * near + 0.2f * storm(t)
            var s = 0f
            while (s < len) {
                val px = ((x - s * 0.22f) * sc).toInt(); val py = ((y - s) * sc).toInt()
                val f = (1f - s / len) * a
                for (dy in 0 until sc) for (dx in 0 until sc) {
                    val xx = px + dx; val yy = py + dy
                    if (xx in 0 until width && yy in 0 until height) {
                        val p = out[yy * width + xx]
                        val nr = (((p shr 16) and 0xFF) * (1f - f) + 170f * f).toInt(); val ng = (((p shr 8) and 0xFF) * (1f - f) + 190f * f).toInt(); val nb = ((p and 0xFF) * (1f - f) + 215f * f).toInt()
                        out[yy * width + xx] = (p and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
                    }
                }
                s += 1f / sc
            }
        }
    }

    /** Steam over a bath: puffs rise slowly, grow and fade out, and sway a little. */
    private fun steam(out: IntArray, width: Int, height: Int, scale: Int, s: FxSteam, t: Float) {
        for (i in 0 until s.count) {
            val h1 = hash01(i * 5 + 401); val h2 = hash01(i * 9 + 403)
            val p = (t * (0.08f + 0.06f * h1) + h2) % 1f
            val x = s.x1 + (s.x2 - s.x1) * h1 + 3f * sin(t * 0.7f + i * 1.7f)
            val y = s.y - s.rise * p
            val a = 0.12f * sin(p * PI.toFloat())
            shade(out, width, height, scale, x, y, 4f + 7f * p, 2.5f + 4f * p, 175, 190, 190, a)
        }
    }

    /** A spider on a thread: down (4 s), it hangs and swings (4 s), up again (5 s). */
    private fun spider(out: IntArray, width: Int, height: Int, scale: Int, s: FxSpider, t: Float) {
        val slot = floor(t / SPIDER_SLOT).toInt()
        val u = t - slot * SPIDER_SLOT - (6f + 20f * hash01(slot * 29 + 7))
        if (u < 0f || u > SPIDER_SECONDS) return
        val down = if (u < 4f) u / 4f else if (u < 8f) 1f else 1f - (u - 8f) / 5f
        val ease = down * down * (3f - 2f * down)
        val y = s.y0 + (s.y1 - s.y0) * ease
        val x = s.x + if (u in 4f..8f) 1.2f * sin((u - 4f) * 2.4f) else 0f
        // the thread
        var yy = s.y0
        while (yy < y) {
            shade(out, width, height, scale, s.x + (x - s.x) * (yy - s.y0) / (y - s.y0 + 0.01f), yy, 0.3f, 0.6f, 200, 195, 185, 0.4f)
            yy += 0.7f
        }
        // the body and the legs that move
        shade(out, width, height, scale, x, y + 1.2f, 1.1f, 1.0f, 18, 14, 12, 0.95f)
        val leg = sin(t * 9f)
        for (k in -1..1 step 2) {
            shade(out, width, height, scale, x + k * 2.1f, y + 0.6f + 0.4f * leg, 1.0f, 0.3f, 18, 14, 12, 0.85f)
            shade(out, width, height, scale, x + k * 2.1f, y + 1.9f - 0.4f * leg, 1.0f, 0.3f, 18, 14, 12, 0.85f)
        }
    }

    /** Blends the colour (r, g, b) with weight [a] over an ellipse (centre in picture pixels, radii [rx] and [ry]). */
    private fun shade(out: IntArray, width: Int, height: Int, scale: Int, cx: Float, cy: Float, rx: Float, ry: Float, r: Int, g: Int, b: Int, a: Float) {
        val x0 = ((cx - rx) * scale).toInt().coerceIn(0, width - 1); val x1 = ((cx + rx) * scale).toInt().coerceIn(0, width - 1)
        val y0 = ((cy - ry) * scale).toInt().coerceIn(0, height - 1); val y1 = ((cy + ry) * scale).toInt().coerceIn(0, height - 1)
        for (y in y0..y1) for (x in x0..x1) {
            val dx = ((x + 0.5f) / scale - cx) / rx
            val dy = ((y + 0.5f) / scale - cy) / ry
            if (dx * dx + dy * dy > 1f) continue
            val i = y * width + x
            val p = out[i]
            val nr = (((p shr 16) and 0xFF) * (1f - a) + r * a).toInt()
            val ng = (((p shr 8) and 0xFF) * (1f - a) + g * a).toInt()
            val nb = ((p and 0xFF) * (1f - a) + b * a).toInt()
            out[i] = (p and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
        }
    }

    private fun boundsOf(s: FxShape): FloatArray = when (s) {
        is FxShape.Rect -> floatArrayOf(s.x1, s.y1, s.x2, s.y2)
        is FxShape.Circle -> floatArrayOf(s.cx - s.r, s.cy - s.r, s.cx + s.r, s.cy + s.r)
        is FxShape.Quad -> floatArrayOf(s.xs.min(), s.ys.min(), s.xs.max(), s.ys.max())
    }

    private fun restore(base: IntArray, out: IntArray, width: Int, height: Int, scale: Int, b: FloatArray) {
        val x0 = (b[0] * scale).toInt().coerceIn(0, width - 1); val x1 = (b[2] * scale).toInt().coerceIn(0, width - 1)
        val y0 = (b[1] * scale).toInt().coerceIn(0, height - 1); val y1 = (b[3] * scale).toInt().coerceIn(0, height - 1)
        for (y in y0..y1) System.arraycopy(base, y * width + x0, out, y * width + x0, x1 - x0 + 1)
    }

    /** How much of the light reaches the point (0..1): a rectangle softly at its border, a circle fading towards its edge, a quad (a beam) softly at its sides. */
    private fun weight(s: FxShape, x: Float, y: Float): Float = when (s) {
        is FxShape.Rect -> if (x < s.x1 || x > s.x2 || y < s.y1 || y > s.y2) 0f else {
            val e = minOf(minOf(x - s.x1, s.x2 - x), minOf(y - s.y1, s.y2 - y)).coerceAtMost(1.5f) / 1.5f
            e
        }
        is FxShape.Circle -> {
            val d = sqrt((x - s.cx) * (x - s.cx) + (y - s.cy) * (y - s.cy)) / s.r
            if (d >= 1f) 0f else (1f - d) * (1f - d)
        }
        is FxShape.Quad -> {
            // four corners in the order: top left, top right, bottom right, bottom left (given as xs/ys of top x1, top x2, bottom x2, bottom x1)
            val topL = s.xs[0]; val topR = s.xs[1]; val botR = s.xs[2]; val botL = s.xs[3]
            val yTop = s.ys[0]; val yBot = s.ys[2]
            if (y < yTop || y > yBot) 0f else {
                val f = (y - yTop) / (yBot - yTop)
                val l = topL + (botL - topL) * f
                val r = topR + (botR - topR) * f
                if (x < l || x > r) 0f else {
                    val side = minOf(x - l, r - x).coerceAtMost(6f) / 6f
                    side * (1f - 0.5f * f)
                }
            }
        }
    }

    private fun add(out: IntArray, i: Int, r: Int, g: Int, b: Int) {
        val p = out[i]
        val nr = ((p shr 16) and 0xFF) + r
        val ng = ((p shr 8) and 0xFF) + g
        val nb = (p and 0xFF) + b
        out[i] = (p and 0xFF000000.toInt()) or (minOf(nr, 255) shl 16) or (minOf(ng, 255) shl 8) or minOf(nb, 255)
    }
}
