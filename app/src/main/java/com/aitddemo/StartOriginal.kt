package com.aitddemo

import com.aitddemo.original.OriginalFontData
import com.aitddemo.original.Pak
import com.aitddemo.original.GameFiles
import com.aitddemo.original.VocSample
import com.aitddemo.original.u8

/** What the start sequence and the menus show, read from the original files: the font, the full screen pictures, the Tatou logo model, two sounds and the story texts. */
class StartOriginal(
    val font: OriginalFontData,
    /** 320 x 200 ARGB pictures */
    val logo: IntArray,
    val title: IntArray,
    val menu: IntArray,
    val select: IntArray,
    val tatouVertices: FloatArray,
    val tatouTriangles: IntArray,
    val thunder: VocSample?,
    val pageTurn: VocSample?,
    val storyCarnby: String,
    val storyEmily: String,
    /** The game's colour table (ITD_RESS.PAK entry 4) */
    val palette: IntArray,
    /** The page picture of the books (ITD_RESS.PAK entry 8, 320 x 200 ARGB) */
    val bookPage: IntArray,
    /** The credits, a book text of ENGLISH.PAK (entry 20) in the format of the books of the game (#P, #T, #C), or null */
    val credits: ByteArray?,
    /** The pictures of PRESENT.PAK as they are in the file (770 bytes of header, 64000 pixels): the slide show of the attract mode; empty when the file is missing */
    val present: List<ByteArray>,
)

object StartOriginalReader {
    const val RESOURCE_FILE = "ITD_RESS.PAK"
    const val SAMPLE_FILE = "LISTSAMP.PAK"
    const val TEXT_FILE = "ENGLISH.PAK"
    const val PRESENT_FILE = "PRESENT.PAK"
    val REQUIRED = listOf(RESOURCE_FILE, SAMPLE_FILE, TEXT_FILE)

    // entries of ITD_RESS.PAK
    private const val TATOU_BODY = 1
    private const val PALETTE = 4
    private const val TATOU_PALETTE = 2
    private const val FONT = 6
    private const val LOGO = 3
    private const val SELECT = 11
    private const val MENU = 13
    private const val TITLE = 14
    private const val BOOK_PAGE = 8

    // entries of LISTSAMP.PAK and ENGLISH.PAK
    private const val THUNDER = 7
    private const val PAGE_TURN = 50
    private const val TEXT_CARNBY = 21
    private const val TEXT_EMILY = 22
    private const val TEXT_CREDITS = 20

    fun missingFiles(files: GameFiles): List<String> = REQUIRED.filter { !files.exists(it) }

    fun read(files: GameFiles): StartOriginal {
        val missing = missingFiles(files)
        if (missing.isNotEmpty()) throw OriginalFilesException("Fehlende Dateien: " + missing.joinToString(", "))
        try {
            val res = Pak(files.read(RESOURCE_FILE)!!)
            fun entry(n: Int) = res.get(n) ?: throw OriginalFilesException("$RESOURCE_FILE: Eintrag $n fehlt")
            val palette = palette(entry(PALETTE), 0)
            val logoData = entry(LOGO)
            val (vertices, triangles) = OriginalBody.parse(entry(TATOU_BODY)).toMesh(palette(entry(TATOU_PALETTE), 0))
            val samples = Pak(files.read(SAMPLE_FILE)!!)
            val texts = Pak(files.read(TEXT_FILE)!!)
            return StartOriginal(
                font = OriginalFontData.parse(entry(FONT)),
                logo = picture(logoData, null),
                title = picture(entry(TITLE), null),
                menu = picture(entry(MENU), palette),
                select = picture(entry(SELECT), palette),
                tatouVertices = vertices,
                tatouTriangles = triangles,
                thunder = samples.get(THUNDER)?.let { VocSample.parse(it) },
                pageTurn = samples.get(PAGE_TURN)?.let { VocSample.parse(it) },
                storyCarnby = story(texts.get(TEXT_CARNBY) ?: throw OriginalFilesException("$TEXT_FILE: Text fehlt")),
                storyEmily = story(texts.get(TEXT_EMILY) ?: throw OriginalFilesException("$TEXT_FILE: Text fehlt")),
                palette = palette,
                bookPage = picture(entry(BOOK_PAGE), palette),
                credits = texts.get(TEXT_CREDITS),
                present = files.read(PRESENT_FILE)?.let { b -> Pak(b).let { p -> (1..14).mapNotNull { p.get(it) }.filter { it.size >= 64770 } } } ?: emptyList(),
            )
        } catch (e: OriginalFilesException) {
            throw e
        } catch (e: Exception) {
            throw OriginalFilesException("Die Originaldateien konnten nicht gelesen werden (${e.javaClass.simpleName}: ${e.message}).")
        }
    }

    /** The 768 byte colour table (8 bit per channel) at [offset] as ARGB. */
    private fun palette(d: ByteArray, offset: Int) = IntArray(256) { i ->
        0xFF000000.toInt() or (d.u8(offset + i * 3) shl 16) or (d.u8(offset + i * 3 + 1) shl 8) or d.u8(offset + i * 3 + 2)
    }

    /** A 320 x 200 picture: 64000 pixels, behind a 770 byte header with its own colour table unless [palette] is given. */
    private fun picture(d: ByteArray, palette: IntArray?): IntArray {
        val pal = palette ?: palette(d, 2)
        val start = d.size - 64000
        return IntArray(64000) { pal[d.u8(start + it)] }
    }

    /** The story text of a menu page: the format codes (#T, #P) removed, the lines joined into paragraphs. */
    private fun story(d: ByteArray): String {
        val s = String(d, Charsets.ISO_8859_1).replace("\r", "").replace("\u001a", "")
        return s.split("#P").joinToString("\n\n") { part ->
            part.replace("#T", "").trim().split("\n").joinToString(" ") { it.trim() }.replace(Regex(" +"), " ")
        }.trim()
    }
}
