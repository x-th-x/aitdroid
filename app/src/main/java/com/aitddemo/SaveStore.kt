package com.aitddemo

import android.content.Context
import com.aitddemo.game.AtticGame
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** Saved games in the app's own storage (not in the folder with the original files): a few slots, each a header (version, time) and the state of the game. */
object SaveStore {
    const val SLOTS = 3
    private const val MAGIC = 0x41495444 // "AITD"
    private const val VERSION = 3 // 2: the CVars come from DEFINES.ITD; 3: the character (Carnby or Emily) follows the time
    private fun versionOk(v: Int) = v == 2 || v == VERSION

    private fun file(context: Context, slot: Int) = File(context.filesDir, "save$slot.sav")

    /** When the slot was saved (milliseconds since 1970) or null when it is empty or unreadable. */
    fun savedAt(context: Context, slot: Int): Long? {
        val f = file(context, slot)
        if (!f.exists()) return null
        return try {
            DataInputStream(f.inputStream().buffered()).use { inp ->
                if (inp.readInt() != MAGIC || !versionOk(inp.readInt())) null else inp.readLong()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Was the game of this slot played by Emily? (saves of version 2 are Carnby's) */
    fun isEmily(context: Context, slot: Int): Boolean = try {
        DataInputStream(file(context, slot).inputStream().buffered()).use { inp ->
            if (inp.readInt() != MAGIC) false else {
                val version = inp.readInt()
                inp.readLong()
                version >= 3 && inp.readBoolean()
            }
        }
    } catch (_: Exception) {
        false
    }

    fun hasAny(context: Context): Boolean = (0 until SLOTS).any { savedAt(context, it) != null }

    fun save(context: Context, slot: Int, game: AtticGame, emily: Boolean = false): Boolean = try {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC); out.writeInt(VERSION); out.writeLong(System.currentTimeMillis()); out.writeBoolean(emily)
            game.saveState(out)
        }
        file(context, slot).writeBytes(bytes.toByteArray())
        true
    } catch (_: Exception) {
        false
    }

    fun load(context: Context, slot: Int, game: AtticGame): Boolean = try {
        loadBytes(file(context, slot).readBytes(), game)
    } catch (_: Exception) {
        false
    }

    /** The whole file of a slot (header and state), for sending a save point to the other player of a co-op game; null when the slot is empty. */
    fun readBytes(context: Context, slot: Int): ByteArray? = try {
        file(context, slot).takeIf { it.exists() }?.readBytes()
    } catch (_: Exception) {
        null
    }

    /** Loads a game from the bytes of a save file (a slot's own, or the one the other co-op player's phone sent). */
    fun loadBytes(bytes: ByteArray, game: AtticGame): Boolean = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { inp ->
            if (inp.readInt() != MAGIC) false else {
                val version = inp.readInt()
                if (!versionOk(version)) false else { inp.readLong(); if (version >= 3) inp.readBoolean(); game.loadState(inp) }
            }
        }
    } catch (_: Exception) {
        false
    }

    /** "Slot 1 - 10.10.2026 14:23" or "Slot 1 - empty". */
    fun label(context: Context, slot: Int): String {
        val t = savedAt(context, slot) ?: return "Slot ${slot + 1} - empty"
        return "Slot ${slot + 1} - " + java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t))
    }
}
