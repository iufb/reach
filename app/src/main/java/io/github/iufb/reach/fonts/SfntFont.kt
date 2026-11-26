package io.github.iufb.reach.fonts

import androidx.annotation.StringRes
import io.github.iufb.reach.R
import java.io.File
import java.io.RandomAccessFile

/** Для чего годится шрифт: основной текст или эмодзи. */
enum class FontKind { Text, Emoji }

/** Почему файл не подходит как шрифт. [text] показывается пользователю, [message] — для логов. */
enum class FontProblem(@StringRes val text: Int, val message: String) {
    Empty(R.string.font_problem_empty, "empty file"),
    Truncated(R.string.font_problem_truncated, "truncated or corrupted"),
    Collection(R.string.font_problem_collection, "font collection (.ttc)"),
    NotFont(R.string.font_problem_not_font, "not a TrueType/OpenType font"),
    Sbix(R.string.font_problem_sbix, "sbix font"),
    NoGlyphs(R.string.font_problem_no_glyphs, "no glyphs"),
    Broken(R.string.font_problem_broken, "Android could not load the font"),
}

class FontImportException(val problem: FontProblem) : Exception(problem.message)

/**
 * Что удалось узнать из заголовков шрифта. [name] — семейство из таблицы `name`, если оно есть;
 * [postScriptName] — `name` ID 6.
 */
data class FontInfo(val kind: FontKind, val name: String?, val postScriptName: String? = null) {
    /**
     * SF Pro: системный `SFNS.ttf` с Mac (`.SFNS-…`, семейство называется «System Font») или SF Pro с сайта Apple.
     * Для него типографика берёт метрики iOS (#253).
     */
    val isSfPro: Boolean
        get() = postScriptName?.let { ps -> SF_PRO_PREFIXES.any { ps.startsWith(it) } } == true

    /** Имя для интерфейса: у `SFNS.ttf` семейство «System Font», показываем «SF Pro» (#285). */
    val displayName: String?
        get() = if (isSfPro) SF_PRO_NAME else name

    private companion object {
        val SF_PRO_PREFIXES = listOf(".SFNS", ".SFUI", "SFPro")
        const val SF_PRO_NAME = "SF Pro"
    }
}

/** Чтение байтов файла шрифта по смещению — чтобы не держать в памяти файл на десятки мегабайт. */
interface FontBytes {
    val size: Long

    /** Ровно [length] байт с [offset] или `null`, если файл кончается раньше. */
    fun read(offset: Long, length: Int): ByteArray?
}

class ByteArrayFontBytes(private val bytes: ByteArray) : FontBytes {
    override val size: Long get() = bytes.size.toLong()

    override fun read(offset: Long, length: Int): ByteArray? {
        if (offset < 0 || length < 0 || offset + length > bytes.size) return null
        return bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
    }
}

/**
 * Разбор sfnt (TrueType/OpenType) без Android: каталог таблиц и таблица `name`.
 * Решает, текстовый это шрифт или эмодзи, и отсеивает то, что Android не нарисует как надо.
 */
object SfntFont {

    fun inspect(file: File): Result<FontInfo> = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val bytes = object : FontBytes {
                override val size = raf.length()
                override fun read(offset: Long, length: Int): ByteArray? {
                    if (offset < 0 || length < 0 || offset + length > size) return null
                    raf.seek(offset)
                    return ByteArray(length).also { raf.readFully(it) }
                }
            }
            inspect(bytes).getOrThrow()
        }
    }

    fun inspect(bytes: ByteArray): Result<FontInfo> = inspect(ByteArrayFontBytes(bytes))

    fun inspect(bytes: FontBytes): Result<FontInfo> {
        if (bytes.size == 0L) return fail(FontProblem.Empty)
        val header = bytes.read(0, HEADER_SIZE) ?: return fail(FontProblem.Truncated)
        when (header.u32(0)) {
            VERSION_TRUETYPE, TAG_TRUE, TAG_OTTO -> Unit
            TAG_TTCF -> return fail(FontProblem.Collection)
            else -> return fail(FontProblem.NotFont)
        }
        val numTables = header.u16(4)
        if (numTables == 0) return fail(FontProblem.NoGlyphs)
        val directory = bytes.read(HEADER_SIZE.toLong(), numTables * RECORD_SIZE) ?: return fail(FontProblem.Truncated)
        val tables = HashMap<String, Pair<Long, Int>>()
        for (i in 0 until numTables) {
            val at = i * RECORD_SIZE
            val tag = String(directory, at, 4, Charsets.ISO_8859_1)
            val offset = directory.u32(at + 8)
            val length = directory.u32(at + 12)
            if (offset + length > bytes.size) return fail(FontProblem.Truncated)
            tables[tag] = offset to length.toInt()
        }

        val kind = when {
            ("CBDT" in tables && "CBLC" in tables) || "COLR" in tables -> FontKind.Emoji
            "sbix" in tables -> return fail(FontProblem.Sbix)
            "glyf" in tables || "CFF " in tables || "CFF2" in tables -> FontKind.Text
            else -> return fail(FontProblem.NoGlyphs)
        }
        val nameTable = tables["name"]?.let { (offset, length) -> bytes.read(offset, length) }
        return Result.success(FontInfo(kind, nameTable?.let(::familyName), nameTable?.let { name(it, NAME_POSTSCRIPT) }))
    }

    /** Семейство: `name` ID 16 (Typographic Family), иначе ID 1 (Font Family). */
    internal fun familyName(table: ByteArray): String? = name(table, NAME_TYPOGRAPHIC_FAMILY, NAME_FAMILY)

    /** Запись `name` с первым из [ids], что есть; среди языков — английский Windows. */
    private fun name(table: ByteArray, vararg ids: Int): String? {
        if (table.size < 6) return null
        val count = table.u16(2)
        val storage = table.u16(4)
        data class Entry(val platform: Int, val language: Int, val nameId: Int, val text: String)
        val entries = (0 until count).mapNotNull { i ->
            val at = 6 + i * 12
            if (at + 12 > table.size) return@mapNotNull null
            val platform = table.u16(at)
            val language = table.u16(at + 4)
            val nameId = table.u16(at + 6)
            if (nameId !in ids) return@mapNotNull null
            val length = table.u16(at + 8)
            val start = storage + table.u16(at + 10)
            if (start + length > table.size) return@mapNotNull null
            val charset = when (platform) {
                PLATFORM_UNICODE, PLATFORM_WINDOWS -> Charsets.UTF_16BE
                PLATFORM_MAC -> Charsets.ISO_8859_1
                else -> return@mapNotNull null
            }
            val text = String(table, start, length, charset).trim()
            if (text.isEmpty()) null else Entry(platform, language, nameId, text)
        }
        return entries.minWithOrNull(
            compareBy<Entry>(
                { ids.indexOf(it.nameId) },
                {
                    when {
                        it.platform == PLATFORM_WINDOWS && it.language == LANGUAGE_EN_US -> 0
                        it.platform == PLATFORM_WINDOWS -> 1
                        it.platform == PLATFORM_UNICODE -> 2
                        else -> 3
                    }
                },
            ),
        )?.text
    }

    private fun fail(problem: FontProblem): Result<FontInfo> = Result.failure(FontImportException(problem))

    private fun ByteArray.u16(at: Int): Int = ((this[at].toInt() and 0xFF) shl 8) or (this[at + 1].toInt() and 0xFF)

    private fun ByteArray.u32(at: Int): Long = (u16(at).toLong() shl 16) or u16(at + 2).toLong()

    private const val HEADER_SIZE = 12
    private const val RECORD_SIZE = 16
    private const val VERSION_TRUETYPE = 0x00010000L
    private const val TAG_TRUE = 0x74727565L // 'true'
    private const val TAG_OTTO = 0x4F54544FL // 'OTTO'
    private const val TAG_TTCF = 0x74746366L // 'ttcf'
    private const val PLATFORM_UNICODE = 0
    private const val PLATFORM_MAC = 1
    private const val PLATFORM_WINDOWS = 3
    private const val LANGUAGE_EN_US = 0x409
    private const val NAME_FAMILY = 1
    private const val NAME_POSTSCRIPT = 6
    private const val NAME_TYPOGRAPHIC_FAMILY = 16
}
