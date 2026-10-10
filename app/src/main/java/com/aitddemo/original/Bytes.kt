package com.aitddemo.original

/** Little-Endian-Hilfsfunktionen für ByteArrays. */
fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF
fun ByteArray.u16(i: Int): Int = u8(i) or (u8(i + 1) shl 8)
fun ByteArray.s16(i: Int): Int = u16(i).toShort().toInt()
fun ByteArray.u32(i: Int): Int = u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16) or (u8(i + 3) shl 24)
