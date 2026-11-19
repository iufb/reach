package io.github.iufb.reach.emoji

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.iufb.reach.core.td.TdClient
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
) {
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
        }
    }.also { categories = it }

    /** Поиск по ключевым словам на языке системы и английском. */
    suspend fun search(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        val languages = listOf(Locale.getDefault().language, "en").distinct().toTypedArray()
        return runCatching { td.send(TdApi.SearchEmojis(query.trim(), languages)) }
            .onFailure { Log.w(TAG, "searchEmojis failed", it) }
            .getOrNull()?.emojiKeywords.orEmpty().map { it.emoji }.distinct()
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
    }
}
