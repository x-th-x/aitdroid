package com.aitddemo.original

/**
 * Dekoder für die alte PKWARE-"Implode"-Kompression der Alone-in-the-Dark-PAKs (Kompressionstyp 1).
 *
 * Datenstrom: Shannon-Fano-Bäume als Längen/Anzahl-Paare (optional Literal-Baum, Längen-Baum, Distanz-Baum),
 * danach Bits LSB-zuerst; die Codebits sind invertiert abgelegt. [flags] stammt aus dem PAK-Header:
 * Bit 2 = Literal-Baum vorhanden, Bit 1 = 8-KB- statt 4-KB-Fenster.
 */
object Explode {
    private val LEN2 = IntArray(64) { it + 2 }
    private val LEN3 = IntArray(64) { it + 3 }
    private val DIST4 = IntArray(64) { 1 + 64 * it }
    private val DIST8 = IntArray(64) { 1 + 128 * it }

    /** Kanonischer Huffman-Code aus Bitlängen (Länge 0 = Symbol unbenutzt). */
    private class Huff(lengths: IntArray) {
        val count = IntArray(17)
        val symbols: IntArray

        init {
            for (l in lengths) count[l]++
            count[0] = 0
            val list = ArrayList<Int>()
            for (len in 1..16) for (s in lengths.indices) if (lengths[s] == len) list.add(s)
            symbols = list.toIntArray()
        }
    }

    private class BitReader(val d: ByteArray, var p: Int) {
        var buf = 0L
        var bits = 0

        fun get(n: Int): Int {
            while (bits < n) {
                val byte = if (p < d.size) d[p].toInt() and 0xFF else 0
                p++
                buf = buf or (byte.toLong() shl bits)
                bits += 8
            }
            val v = (buf and ((1L shl n) - 1)).toInt()
            buf = buf ushr n
            bits -= n
            return v
        }

        fun symbol(h: Huff): Int {
            var code = 0
            var first = 0
            var index = 0
            for (len in 1..16) {
                code = code or (1 - get(1)) // Bits sind invertiert gespeichert
                val cnt = h.count[len]
                if (code - first < cnt) return h.symbols[index + code - first]
                index += cnt
                first = (first + cnt) shl 1
                code = code shl 1
            }
            throw IllegalStateException("Explode: ungültiger Code")
        }
    }

    /** Liest die Längen-Liste eines Baums; liefert (Längen, neue Leseposition). */
    private fun readTree(d: ByteArray, start: Int, n: Int): Pair<IntArray, Int> {
        var p = start
        val pairs = (d.u8(p++)) + 1
        val out = IntArray(n)
        var k = 0
        repeat(pairs) {
            val j = d.u8(p++)
            val bits = (j and 15) + 1
            val cnt = ((j and 0xF0) shr 4) + 1
            repeat(cnt) {
                require(k < n) { "Explode: Baum zu groß" }
                out[k++] = bits
            }
        }
        require(k == n) { "Explode: Baum unvollständig" }
        return Pair(out, p)
    }

    fun decode(src: ByteArray, srcOff: Int, srcLen: Int, outSize: Int, flags: Int): ByteArray {
        val d = src.copyOfRange(srcOff, srcOff + srcLen)
        var p = 0
        var literal: Huff? = null
        if (flags and 4 != 0) {
            val (l, np) = readTree(d, p, 256)
            literal = Huff(l)
            p = np
        }
        val (ll, p2) = readTree(d, p, 64)
        val lengthTree = Huff(ll)
        val baseLen = if (literal != null) LEN3 else LEN2
        val (dl, p3) = readTree(d, p2, 64)
        val distTree = Huff(dl)
        val lowBits = if (flags and 2 != 0) 7 else 6
        val baseDist = if (flags and 2 != 0) DIST8 else DIST4

        val r = BitReader(d, p3)
        val out = ByteArray(outSize)
        var n = 0
        while (n < outSize) {
            if (r.get(1) == 1) {
                out[n++] = (if (literal != null) r.symbol(literal) else r.get(8)).toByte()
            } else {
                val low = r.get(lowBits)
                val dist = low + baseDist[r.symbol(distTree)]
                val ls = r.symbol(lengthTree)
                var len = baseLen[ls]
                if (ls == 63) len += r.get(8)
                var k = 0
                while (k < len && n < outSize) {
                    out[n] = if (dist <= n) out[n - dist] else 0
                    n++
                    k++
                }
            }
        }
        return out
    }
}
