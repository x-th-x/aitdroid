package com.aitddemo

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The order in which the original engine draws the things of a room: farthest first. It is the compare function of the original program (sortCompareFunction,
 * code at 0x509D of INDARK) that sorts by the boxes (ZV: x1, x2, y1, y2, z1, z2 in room units) of the objects as seen from the camera, not by the depth of a
 * point. Two boxes that lie in the same height band of 2000 units and overlap in x and/or z are compared by their nearest faces (the wardrobe pushed in front of
 * the window against the window), the others by the distance of their centres. A box in another height band is compared by its height band.
 */
object DrawOrder {
    /** < 0 when [a] is farther from the camera ([tx], [ty], [tz]: its place in room units) than [b], that is, [a] is drawn first. */
    fun compare(a: IntArray, b: IntArray, tx: Int, ty: Int, tz: Int): Int {
        val y1 = ((((a[2] + a[3]) / 2) - 2000) / 2000) * 2000
        val y2 = ((((b[2] + b[3]) / 2) - 2000) / 2000) * 2000
        var d1: Int
        var d2: Int
        if (y1 == y2) {
            var flag = 0
            if ((a[0] > b[0] && a[0] < b[1]) || (a[1] > b[0] && a[1] < b[1]) || (b[0] > a[0] && b[0] < a[1]) || (b[1] > a[0] && b[1] < a[1])) flag = flag or 1
            if ((a[4] > b[4] && a[4] < b[5]) || (a[5] > b[4] && a[5] < b[5]) || (b[4] > a[4] && b[4] < a[5]) || (b[5] > a[4] && b[5] < a[5])) flag = flag or 2
            if (flag == 0) {
                d1 = dist2d(tx, tz, (a[0] + a[1]) / 2, (a[4] + a[5]) / 2)
                d2 = dist2d(tx, tz, (b[0] + b[1]) / 2, (b[4] + b[5]) / 2)
            } else {
                d1 = 0
                d2 = 0
                if (flag and 2 != 0) { // they overlap in z: the nearer face in x
                    d1 = nearest(tx, a[0], a[1])
                    d2 = nearest(tx, b[0], b[1])
                }
                if (flag and 1 != 0) { // they overlap in x: the nearer face in z
                    d1 += nearest(tz, a[4], a[5])
                    d2 += nearest(tz, b[4], b[5])
                }
            }
        } else {
            d1 = abs(ty - 2000 - y1)
            d2 = abs(ty - 2000 - y2)
        }
        return if (d1 > d2) -1 else if (d1 < d2) 1 else 0
    }

    private fun nearest(t: Int, lo: Int, hi: Int): Int = if (abs(t - lo) < abs(t - hi)) abs(t - lo) else abs(t - hi)

    private fun dist2d(x1: Int, z1: Int, x2: Int, z2: Int): Int {
        val dx = (x1 - x2).toDouble()
        val dz = (z1 - z2).toDouble()
        return sqrt(dx * dx + dz * dz).toInt()
    }

    /** Sorts [items] (a stable insertion sort: the compare function is not a strict order, so a library sort could refuse it) with the boxes from [box]. */
    fun <T> sort(items: List<T>, box: (T) -> IntArray, tx: Int, ty: Int, tz: Int): List<T> {
        val out = ArrayList<T>(items.size)
        for (item in items) {
            var i = out.size
            while (i > 0 && compare(box(out[i - 1]), box(item), tx, ty, tz) > 0) i--
            out.add(i, item)
        }
        return out
    }
}
