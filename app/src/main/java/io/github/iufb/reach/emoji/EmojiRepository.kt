package io.github.iufb.reach.emoji

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.fonts.DeviceFonts
import io.github.iufb.reach.fonts.EmojiCoverage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Категория пикера: [key] из метаданных noto-emoji, эмодзи в их порядке. */
data class EmojiCategory(val key: String, val emojis: List<String>)

/**
 * Эмодзи для пикера (PRODUCT.md §4.5): категории и порядок — из метаданных noto-emoji
 * (`assets/emoji/ordering.txt`, собирается scripts/gen_emoji_ordering.py), поиск по словам — TDLib,
 * недавние — в настройках приложения.
 */
@Singleton
class EmojiRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val td: TdClient,
    fonts: DeviceFonts,
) {
    // Свой emoji-шрифт (#254): показываем только то, что он рисует сам
    private val coverage = fonts.emojiFile?.let { file -> fonts.text?.let { EmojiCoverage(it, file) } }

    private var categories: List<EmojiCategory>? = null

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val _recent by lazy {
        MutableStateFlow(prefs.getString(KEY_RECENT, "").orEmpty().split(' ').filter { it.isNotEmpty() })
    }
    val recent: StateFlow<List<String>> get() = _recent.asStateFlow()

    suspend fun categories(): List<EmojiCategory> = categories ?: withContext(Dispatchers.IO) {
        context.assets.open(ORDERING).bufferedReader().useLines { lines ->
            lines.filter { '\t' in it }.map { line ->
                val (key, emojis) = line.split('\t', limit = 2)
                EmojiCategory(key, emojis.split(' ').filter { it.isNotEmpty() })
            }.toList()
        }.let(::covered)
    }.also { categories = it }

    private fun covered(all: List<EmojiCategory>): List<EmojiCategory> {
        val coverage = coverage ?: return all
        val start = SystemClock.elapsedRealtime()
        val covered = all.map { category -> category.copy(emojis = category.emojis.filter(coverage::covers)) }
        val unique = all.flatMap { it.emojis }.distinct()
        val total = unique.size
        val missing = unique - covered.flatMap { it.emojis }.toSet()
        val took = SystemClock.elapsedRealtime() - start
        Log.i(TAG, "emoji font covers ${total - missing.size}/$total, $took ms; missing: ${missing.take(LOG_MISSING).joinToString(" ")}")
        // Проверка не сработала (например, Android не отдал путь шрифта) — лучше Noto-порядок целиком, чем пустой пикер
        if (missing.size > total / 2) return all
        return covered.filter { it.emojis.isNotEmpty() }
    }

    /** Поиск по ключевым словам на языке системы и английском. */
    suspend fun search(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        val languages = listOf(Locale.getDefault().language, "en").distinct().toTypedArray()
        return runCatching { td.send(TdApi.SearchEmojis(query.trim(), languages)) }
            .onFailure { Log.w(TAG, "searchEmojis failed", it) }
            .getOrNull()?.emojiKeywords.orEmpty().map { it.emoji }.distinct()
            .filter { emoji -> coverage?.covers(emoji) ?: true }
    }

    /** Выбранный эмодзи — в начало недавних. */
    fun addRecent(emoji: String) {
        _recent.update { (listOf(emoji) + it.filter { e -> e != emoji }).take(MAX_RECENT) }
        prefs.edit().putString(KEY_RECENT, _recent.value.joinToString(" ")).apply()
    }

    private companion object {
        const val TAG = "EmojiRepository"
        const val ORDERING = "emoji/ordering.txt"
        const val PREFS = "emoji"
        const val KEY_RECENT = "recent"
        const val MAX_RECENT = 32
        const val LOG_MISSING = 40
    }
}
