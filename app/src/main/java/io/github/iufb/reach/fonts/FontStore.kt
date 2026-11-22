package io.github.iufb.reach.fonts

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.text.TextRunShaper
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.iufb.reach.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Имя файла похоже на шрифт, который можно импортировать: `.ttf` или `.otf` без учёта регистра. */
fun isFontFileName(name: String): Boolean = name.endsWith(".ttf", ignoreCase = true) || name.endsWith(".otf", ignoreCase = true)

/** Шрифт, импортированный в приложение. [id] — начало SHA-256 файла, он же имя файла `<id>.ttf`. */
data class LocalFont(val id: String, val name: String, val kind: FontKind, val sizeBytes: Long)

/** Выбранные шрифты: id для текста и для эмодзи, `null` — стандартный. */
data class FontSelection(val text: String?, val emoji: String?)

/**
 * Шрифты, собранные при старте процесса. Выбор применяется только после перезапуска.
 * Все поля `null` — стандартные Inter и Noto (через EmojiCompat).
 */
data class DeviceFonts(
    /** Выбранный emoji-шрифт, за ним текстовый (или Inter); `null` — ничего не выбрано. */
    val text: Typeface?,
    /** Файл выбранного текстового шрифта, `null` — Inter. */
    val textFile: File?,
    /** Файл выбранного emoji-шрифта, `null` — Noto + EmojiCompat. */
    val emojiFile: File?,
    /** Emoji-шрифт стоит первым в цепочке fallback (он подготовлен для этого, см. [emojiGoesFirst]). */
    val emojiFirst: Boolean = false,
) {
    /** Id применённых шрифтов (имя файла без `.ttf`), `null` — стандартный. */
    val textId: String? get() = textFile?.nameWithoutExtension
    val emojiId: String? get() = emojiFile?.nameWithoutExtension
}

/**
 * Свои шрифты пользователя (эпик #249): файлы в `filesDir/fonts/` (внутренняя память, без разрешений)
 * и выбор шрифта для текста и для эмодзи. Файлы Apple в APK и репозиторий не попадают — только сюда.
 */
@Singleton
class FontStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dir = File(context.filesDir, DIR)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    // Список строится при первом обращении, а не при старте: загрузчику он не нужен
    private val _fonts by lazy { MutableStateFlow(scan()) }

    fun fonts(): StateFlow<List<LocalFont>> = _fonts.asStateFlow()

    // Запись синхронная (commit): сразу после выбора процесс могут перезапустить через Runtime.exit
    /** Id выбранного текстового шрифта, `null` — Inter. */
    var selectedText: String?
        get() = prefs.getString(KEY_TEXT, null)
        set(value) {
            prefs.edit().putString(KEY_TEXT, value).commit()
            _selection.value = FontSelection(value, selectedEmoji)
        }

    /** Id выбранного emoji-шрифта, `null` — Noto. */
    var selectedEmoji: String?
        get() = prefs.getString(KEY_EMOJI, null)
        set(value) {
            prefs.edit().putString(KEY_EMOJI, value).commit()
            _selection.value = FontSelection(selectedText, value)
        }

    private val _selection by lazy { MutableStateFlow(FontSelection(selectedText, selectedEmoji)) }

    /** Текущий выбор (ещё не обязательно применённый — применяется после перезапуска). */
    fun selection(): StateFlow<FontSelection> = _selection.asStateFlow()

    /** Импорт из системного выбора файла. */
    suspend fun import(uri: Uri): Result<LocalFont> = importFrom {
        context.contentResolver.openInputStream(uri) ?: throw FontImportException(FontProblem.Empty)
    }

    /** Импорт из скачанного документа TDLib. */
    suspend fun import(file: File): Result<LocalFont> = importFrom { file.inputStream() }

    fun delete(id: String) {
        fileOf(id).delete()
        if (selectedText == id) selectedText = null
        if (selectedEmoji == id) selectedEmoji = null
        _fonts.value = _fonts.value.filterNot { it.id == id }
    }

    /**
     * Копия во временный файл → проверка заголовков → пробная загрузка Android → `rename`.
     * Оборванный или негодный импорт не оставляет файла; тот же файл повторно не дублируется.
     */
    private suspend fun importFrom(open: () -> InputStream): Result<LocalFont> = withContext(Dispatchers.IO) {
        mutex.withLock {
            dir.mkdirs()
            val temp = File(dir, "$TEMP_PREFIX${UUID.randomUUID()}")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                DigestInputStream(open(), digest).use { input -> temp.outputStream().use { input.copyTo(it) } }
                val id = digest.digest().joinToString("") { "%02x".format(it) }.take(ID_LENGTH)
                val info = SfntFont.inspect(temp).getOrThrow()
                try {
                    Font.Builder(temp).build()
                } catch (e: Exception) {
                    Log.w(TAG, "font $id rejected by Font.Builder", e)
                    throw FontImportException(FontProblem.Broken)
                }
                val target = fileOf(id)
                if (target.exists()) temp.delete() else check(temp.renameTo(target)) { "rename failed" }
                val font = LocalFont(id, info.name ?: id, info.kind, target.length())
                _fonts.value = _fonts.value.filterNot { it.id == id } + font
                Log.i(TAG, "font imported: ${font.name} (${font.kind}, ${font.sizeBytes} B) as $id")
                Result.success(font)
            } catch (e: Exception) {
                temp.delete()
                if (e !is FontImportException) Log.w(TAG, "font import failed", e)
                Result.failure(e)
            }
        }
    }

    private fun scan(): List<LocalFont> {
        // Остатки оборванного импорта (процесс убит посреди копирования)
        dir.listFiles { f -> f.name.startsWith(TEMP_PREFIX) }?.forEach { it.delete() }
        return dir.listFiles { f -> f.name.endsWith(EXT) }.orEmpty().sortedBy { it.lastModified() }.mapNotNull { file ->
            val id = file.name.removeSuffix(EXT)
            SfntFont.inspect(file).getOrNull()?.let { LocalFont(id, it.name ?: id, it.kind, file.length()) }
        }
    }

    private fun fileOf(id: String) = File(dir, id + EXT)

    /**
     * Сборка шрифтов при старте — синхронно, до первого кадра. Файлы отображаются в память, это быстро.
     * Пропавший или битый выбранный файл сбрасывает выбор на стандартный.
     */
    fun load(): DeviceFonts {
        val start = SystemClock.elapsedRealtime()
        val text = selected(selectedText, FontKind.Text) { selectedText = null }
        val emoji = selected(selectedEmoji, FontKind.Emoji) { selectedEmoji = null }

        val typeface = typeface(text?.font, emoji?.font)
        val took = SystemClock.elapsedRealtime() - start
        val summary = if (text == null && emoji == null) {
            "стандартные: Inter + Noto"
        } else {
            "текст ${text?.name?.let { "«$it»" } ?: "Inter"} + эмодзи ${emoji?.name?.let { "«$it»" } ?: "Noto"}"
        }
        Log.i(TAG, "fonts: $summary, $took ms")
        return DeviceFonts(typeface, text?.file, emoji?.file, emoji?.font?.let(::emojiGoesFirst) ?: false)
    }

    /**
     * Превью на экране выбора: [Typeface] из файлов кандидатов, собранный так же, как в [load].
     * `null` — оба стандартные (или файл не загрузился).
     */
    suspend fun preview(textId: String?, emojiId: String?): Typeface? = withContext(Dispatchers.IO) {
        fun font(id: String?) = id?.let { runCatching { Font.Builder(fileOf(it)).build() }.getOrNull() }
        typeface(font(textId), font(emojiId))
    }

    /** Emoji-шрифт (если выбран и [emojiGoesFirst]), за ним текстовый (или Inter), дальше системные. */
    private fun typeface(text: Font?, emoji: Font?): Typeface? {
        if (text == null && emoji == null) return null
        return try {
            val main = FontFamily.Builder(text ?: Font.Builder(context.resources, R.font.inter_variable).build()).build()
            val builder = when {
                emoji == null -> Typeface.CustomFallbackBuilder(main)
                emojiGoesFirst(emoji) -> Typeface.CustomFallbackBuilder(FontFamily.Builder(emoji).build()).addCustomFallback(main)
                else -> Typeface.CustomFallbackBuilder(main).addCustomFallback(FontFamily.Builder(emoji).build())
            }
            builder.setSystemFallback("sans-serif").build()
        } catch (e: Exception) {
            Log.w(TAG, "fonts: typeface build failed", e)
            null
        }
    }

    private class Loaded(val file: File, val name: String, val font: Font)

    private fun selected(id: String?, kind: FontKind, reset: () -> Unit): Loaded? {
        id ?: return null
        val file = fileOf(id)
        val loaded = try {
            if (!file.exists()) error("file missing")
            val info = SfntFont.inspect(file).getOrThrow()
            if (info.kind != kind) error("expected $kind, got ${info.kind}")
            Loaded(file, info.name ?: id, Font.Builder(file).build())
        } catch (e: Exception) {
            Log.w(TAG, "fonts: selected $kind font $id unusable, back to default", e)
            null
        }
        if (loaded == null) reset()
        return loaded
    }

    private companion object {
        const val TAG = "Reach"
        const val DIR = "fonts"
        const val EXT = ".ttf"
        const val TEMP_PREFIX = ".import-"
        const val ID_LENGTH = 12
        const val PREFS = "fonts"
        const val KEY_TEXT = "text"
        const val KEY_EMOJI = "emoji"
    }
}

/**
 * Можно ли ставить emoji-шрифт первым в цепочке fallback. Android отдаёт знак первому шрифту, который
 * его покрывает, и с текстовым шрифтом впереди ❤️, ☀️, 1️⃣ брались бы из него, а ❤️‍🔥 распадался.
 * Но первым можно ставить только шрифт без пробела и цифр — их убирает `tools/fonts/android_fixups.py`.
 * Шрифт, собранный без него, остаётся после текстового: цифры и пробелы важнее.
 */
internal fun emojiGoesFirst(emoji: Font): Boolean {
    val alone = Typeface.CustomFallbackBuilder(FontFamily.Builder(emoji).build()).build()
    val paint = Paint().apply { typeface = alone }
    val glyphs = TextRunShaper.shapeTextRun(TEXT_PROBE, 0, TEXT_PROBE.length, 0, TEXT_PROBE.length, 0f, 0f, false, paint)
    val path = emoji.file?.absolutePath ?: return false
    val first = (0 until glyphs.glyphCount()).none { glyphs.getFont(it).file?.absolutePath == path }
    if (!first) Log.w("Reach", "fonts: emoji font has digits/space (built without android_fixups.py), keeping it after the text font")
    return first
}

private const val TEXT_PROBE = "1 #"
