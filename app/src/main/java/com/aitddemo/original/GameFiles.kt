package com.aitddemo.original

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/** Zugriff auf die Original-Spieldateien (Groß-/Kleinschreibung egal). */
interface GameFiles {
    fun exists(name: String): Boolean
    fun read(name: String): ByteArray?

    /** Nur die ersten [max] Bytes der Datei (für Köpfe großer Dateien) oder null. */
    fun readPrefix(name: String, max: Int): ByteArray?

    /** Unterordner (Groß-/Kleinschreibung egal) oder null, wenn es ihn nicht gibt. */
    fun child(name: String): GameFiles?
}

/** Normaler Ordner (für Tests). */
class DirFiles(dir: File) : GameFiles {
    private val index: Map<String, File> =
        dir.listFiles()?.filter { it.isFile }?.associateBy { it.name.uppercase() } ?: emptyMap()

    private val dirs: Map<String, File> =
        dir.listFiles()?.filter { it.isDirectory }?.associateBy { it.name.uppercase() } ?: emptyMap()

    override fun exists(name: String) = index.containsKey(name.uppercase())
    override fun read(name: String): ByteArray? = index[name.uppercase()]?.readBytes()

    override fun readPrefix(name: String, max: Int): ByteArray? = index[name.uppercase()]?.inputStream()?.use { it.readUpTo(max) }
    override fun child(name: String): GameFiles? = dirs[name.uppercase()]?.let { DirFiles(it) }
}

/** Ordner, den der Nutzer per Storage Access Framework gewählt hat. */
class TreeFiles(
    private val ctx: Context,
    private val tree: Uri,
    startDocumentId: String? = null,
    searchSubfolders: Boolean = true,
) : GameFiles {
    private class Entry(val id: String, val name: String, val isDir: Boolean)

    private var index: Map<String, String> = emptyMap() // GROSSER NAME -> Document-ID
    private var dirs: Map<String, String> = emptyMap() // GROSSER ORDNERNAME -> Document-ID

    init {
        val rootId = startDocumentId ?: DocumentsContract.getTreeDocumentId(tree)
        val root = list(rootId)
        index = root.filter { !it.isDir }.associate { it.name.uppercase() to it.id }
        dirs = root.filter { it.isDir }.associate { it.name.uppercase() to it.id }
        if (searchSubfolders && !index.containsKey("LISTBODY.PAK")) {
            // evtl. wurde der übergeordnete Ordner gewählt: eine Ebene tiefer suchen
            for (dir in root.filter { it.isDir }) {
                val entries = list(dir.id)
                val sub = entries.filter { !it.isDir }.associate { it.name.uppercase() to it.id }
                if (sub.containsKey("LISTBODY.PAK")) {
                    index = sub
                    dirs = entries.filter { it.isDir }.associate { it.name.uppercase() to it.id }
                    break
                }
            }
        }
    }

    private fun list(parentId: String): List<Entry> {
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val res = ArrayList<Entry>()
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        ctx.contentResolver.query(childUri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                res.add(Entry(c.getString(0), c.getString(1), c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR))
            }
        }
        return res
    }

    override fun exists(name: String) = index.containsKey(name.uppercase())

    override fun child(name: String): GameFiles? =
        dirs[name.uppercase()]?.let { TreeFiles(ctx, tree, it, searchSubfolders = false) }

    override fun read(name: String): ByteArray? {
        val id = index[name.uppercase()] ?: return null
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        return ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }

    override fun readPrefix(name: String, max: Int): ByteArray? {
        val id = index[name.uppercase()] ?: return null
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        return ctx.contentResolver.openInputStream(uri)?.use { it.readUpTo(max) }
    }
}

/** Liest höchstens [max] Bytes (kürzer, wenn der Strom früher endet). */
private fun java.io.InputStream.readUpTo(max: Int): ByteArray {
    val buf = ByteArray(max)
    var n = 0
    while (n < max) {
        val r = read(buf, n, max - n)
        if (r < 0) break
        n += r
    }
    return if (n == max) buf else buf.copyOf(n)
}
