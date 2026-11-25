package io.github.iufb.reach.stickers

import android.util.Log
import io.github.iufb.reach.chat.ChatScope
import io.github.iufb.reach.chat.SendOptions
import io.github.iufb.reach.chats.StickerInfo
import io.github.iufb.reach.chats.plainEmojiCount
import io.github.iufb.reach.chats.toInfo
import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.TdApi
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Стикеры для панели под полем ввода (эпик #293): недавние, избранные, установленные паки и тренды.
 * Только обычные стикеры (`StickerTypeRegular`); кастомные эмодзи — в `media/CustomEmojis`.
 *
 * Списки загружаются при создании репозитория и после входа в аккаунт, дальше их меняют апдейты
 * TDLib: `updateRecentStickers`, `updateFavoriteStickers`, `updateInstalledStickerSets`,
 * `updateTrendingStickerSets`, `updateStickerSet`. Перезапрашивается только изменившийся список
 * и только если в нём появились неизвестные элементы — новый порядок известных применяется на месте.
 *
 * Действия (`send`, `install`, `addFavorite`…) бросают `TdException` — ошибку показывает вызывающий.
 */
@Singleton
class StickersRepository @Inject constructor(
    private val td: TdClient,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _recent = MutableStateFlow<List<StickerInfo>>(emptyList())

    /** Недавно отправленные, новые первыми (`getRecentStickers`, без прикреплённых к медиа). */
    val recent: StateFlow<List<StickerInfo>> = _recent.asStateFlow()

    private val _favorites = MutableStateFlow<List<StickerInfo>>(emptyList())

    /** Избранные (`getFavoriteStickers`). */
    val favorites: StateFlow<List<StickerInfo>> = _favorites.asStateFlow()

    private val _installed = MutableStateFlow<List<StickerSetInfo>>(emptyList())

    /**
     * Установленные паки в порядке пользователя — для полосы иконок. Паки неполные (только обложки),
     * стикеры пака — через [set] / [sets].
     */
    val installed: StateFlow<List<StickerSetInfo>> = _installed.asStateFlow()

    private val _sets = MutableStateFlow<Map<Long, StickerSetInfo>>(emptyMap())

    /** Кеш полных паков по id: наполняется [set] и `updateStickerSet`, живёт до выхода из аккаунта. */
    val sets: StateFlow<Map<Long, StickerSetInfo>> = _sets.asStateFlow()

    private val _trending = MutableStateFlow(TrendingStickers())

    /** Трендовые паки; первая страница грузится сама, следующие — [loadMoreTrending]. */
    val trending: StateFlow<TrendingStickers> = _trending.asStateFlow()

    private val trendingMutex = Mutex()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            td.updates.collect(::onUpdate)
        }
        scope.launch { loadAll() }
    }

    /**
     * Полный пак: из кеша или `getStickerSet`. `null` — TDLib не отдал (пак удалён, нет сети).
     * Для пака стикера из чата id берётся из [StickerInfo.setId].
     */
    suspend fun set(setId: Long): StickerSetInfo? {
        _sets.value[setId]?.let { return it }
        val set = runCatching { td.send(TdApi.GetStickerSet(setId)) }
            .onFailure { Log.w(TAG, "getStickerSet $setId failed", it) }
            .getOrNull()?.toModel() ?: return null
        _sets.update { it + (set.id to set) }
        return set
    }

    /**
     * Полный пак по короткому имени из ссылки `t.me/addstickers/<name>` (`searchStickerSet`),
     * кладётся в кеш [sets]. `null` — пака с таким именем нет.
     */
    suspend fun setByName(name: String): StickerSetInfo? {
        _sets.value.values.findByName(name)?.let { return it }
        val set = runCatching { td.send(TdApi.SearchStickerSet(name, false)) }
            .onFailure { Log.w(TAG, "searchStickerSet $name failed", it) }
            .getOrNull()?.toModel() ?: return null
        _sets.update { it + (set.id to set) }
        return set
    }

    /** Следующая страница трендов; повторный вызов во время загрузки ничего не делает. */
    suspend fun loadMoreTrending() {
        if (!trendingMutex.tryLock()) return
        try {
            val current = _trending.value
            if (!current.hasMore) return
            _trending.update { it.copy(isLoading = true) }
            val page = runCatching {
                td.send(TdApi.GetTrendingStickerSets(TdApi.StickerTypeRegular(), current.sets.size, TRENDING_PAGE))
            }.onFailure { Log.w(TAG, "getTrendingStickerSets failed", it) }.getOrNull()
            _trending.update { state ->
                if (page == null) state.copy(isLoading = false)
                else state.appendPage(page.sets.map { it.toModel() }, page.totalCount)
            }
        } finally {
            trendingMutex.unlock()
        }
    }

    /** Тренды показаны пользователю: снимает с них отметку «новый» (`viewTrendingStickerSets`). */
    fun viewTrending(setIds: List<Long>) {
        val unviewed = setIds.filter { id -> _trending.value.sets.any { it.id == id && !it.isViewed } }
        if (unviewed.isEmpty()) return
        _trending.update { state ->
            state.copy(sets = state.sets.map { if (it.id in unviewed) it.copy(isViewed = true) else it })
        }
        scope.launch {
            runCatching { td.send(TdApi.ViewTrendingStickerSets(unviewed.toLongArray())) }
                .onFailure { Log.w(TAG, "viewTrendingStickerSets failed", it) }
        }
    }

    /**
     * Отправить стикер в чат. [scope] — тред или топик, [replyToMessageId] — ответ, как у текста.
     * Возвращает сообщение в состоянии «отправляется». Экран чата отправляет через
     * `ChatViewModel.sendSticker`: он ещё подставляет начатый ответ и прокручивает ленту к концу.
     */
    suspend fun send(
        chatId: Long,
        scope: ChatScope,
        replyToMessageId: Long?,
        sticker: StickerInfo,
        options: SendOptions = SendOptions(),
    ): TdApi.Message = td.send(stickerMessage(chatId, scope, replyToMessageId, sticker, options))

    /** Добавить пак (`changeStickerSet`); список [installed] обновит апдейт. */
    /**
     * Поиск в панели стикеров (#396): по эмодзи («😂») или по словам («кот», «cat») на языке системы
     * и английском. Стикеры — сначала из установленных паков (`getStickers`), затем общие
     * (`searchStickers`), без повторов; паки — установленные (`searchInstalledStickerSets`), затем
     * найденные (`searchStickerSets`). Запросы идут параллельно; упавший даёт пустую часть, не ошибку.
     */
    suspend fun search(query: String): StickerSearch = coroutineScope {
        val q = query.trim()
        if (q.isEmpty()) return@coroutineScope StickerSearch()
        val type = TdApi.StickerTypeRegular()
        val languages = listOf(Locale.getDefault().language, "en").distinct().toTypedArray()
        // Запрос из одних эмодзи ищется по эмодзи, иначе — по словам
        val byEmoji = plainEmojiCount(q) > 0
        val installedStickers = async { runCatching { td.send(TdApi.GetStickers(type, q, SEARCH_LIMIT, 0)) }.getOrNull() }
        val foundStickers = async {
            runCatching {
                td.send(TdApi.SearchStickers(type, if (byEmoji) q else "", if (byEmoji) "" else q, languages, 0, SEARCH_LIMIT))
            }.onFailure { Log.w(TAG, "searchStickers failed", it) }.getOrNull()
        }
        val installedSets = async { runCatching { td.send(TdApi.SearchInstalledStickerSets(type, q, SEARCH_SETS_LIMIT)) }.getOrNull() }
        val foundSets = async {
            runCatching { td.send(TdApi.SearchStickerSets(type, q)) }.onFailure { Log.w(TAG, "searchStickerSets failed", it) }.getOrNull()
        }
        // Ответы TDLib — Java-массивы: в списки
        val stickers = (installedStickers.await()?.stickers?.toList().orEmpty() + foundStickers.await()?.stickers?.toList().orEmpty())
            .distinctBy { sticker: TdApi.Sticker -> sticker.sticker.remote?.uniqueId ?: sticker.sticker.id.toString() }
            .map { sticker -> sticker.toInfo() }
        val sets = (installedSets.await()?.sets?.toList().orEmpty() + foundSets.await()?.sets?.toList().orEmpty())
            .distinctBy { set: TdApi.StickerSetInfo -> set.id }
            .map { set -> set.toModel() }
        StickerSearch(stickers, sets)
    }

    suspend fun install(setId: Long) = changeInstalled(setId, installed = true)

    /** Удалить пак (`changeStickerSet`). */
    suspend fun uninstall(setId: Long) = changeInstalled(setId, installed = false)

    private suspend fun changeInstalled(setId: Long, installed: Boolean) {
        td.send(TdApi.ChangeStickerSet(setId, installed, false))
        // Кнопка «Добавить / Удалить» меняется сразу, не дожидаясь updateInstalledStickerSets
        val ids = _installed.value.mapTo(HashSet()) { it.id }.apply { if (installed) add(setId) else remove(setId) }
        applyInstalledFlags(ids)
    }

    fun isFavorite(sticker: StickerInfo): Boolean = _favorites.value.any { it.file.uniqueId == sticker.file.uniqueId }

    /** В избранное (`addFavoriteSticker`); список [favorites] обновит апдейт. */
    suspend fun addFavorite(sticker: StickerInfo) {
        td.send(TdApi.AddFavoriteSticker(sticker.inputFile()))
    }

    suspend fun removeFavorite(sticker: StickerInfo) {
        td.send(TdApi.RemoveFavoriteSticker(sticker.inputFile()))
    }

    /** Убрать из недавних (`removeRecentSticker`). */
    suspend fun removeRecent(sticker: StickerInfo) {
        td.send(TdApi.RemoveRecentSticker(false, sticker.inputFile()))
    }

    private fun onUpdate(update: TdApi.Object) {
        when (update) {
            is TdApi.UpdateRecentStickers -> if (!update.isAttached) {
                val reordered = reorderKnown(_recent.value, update.stickerIds.toList()) { it.file.id }
                if (reordered != null) _recent.value = reordered else scope.launch { loadRecent() }
            }
            is TdApi.UpdateFavoriteStickers -> {
                val reordered = reorderKnown(_favorites.value, update.stickerIds.toList()) { it.file.id }
                if (reordered != null) _favorites.value = reordered else scope.launch { loadFavorites() }
            }
            is TdApi.UpdateInstalledStickerSets -> if (update.stickerType is TdApi.StickerTypeRegular) {
                val ids = update.stickerSetIds.toList()
                val reordered = reorderKnown(_installed.value, ids) { it.id }
                if (reordered != null) _installed.value = reordered else scope.launch { loadInstalled() }
                applyInstalledFlags(ids.toSet())
            }
            is TdApi.UpdateTrendingStickerSets -> if (update.stickerType is TdApi.StickerTypeRegular) {
                val page = update.stickerSets
                _trending.value = trendingFromUpdate(page.sets.map { it.toModel() }, page.totalCount)
            }
            is TdApi.UpdateStickerSet -> if (update.stickerSet.stickerType is TdApi.StickerTypeRegular) {
                val set = update.stickerSet.toModel()
                _sets.update { it + (set.id to set) }
            }
            is TdApi.UpdateAuthorizationState -> when (update.authorizationState) {
                is TdApi.AuthorizationStateClosed -> reset()
                is TdApi.AuthorizationStateReady -> scope.launch { loadAll() }
                else -> Unit
            }
            else -> Unit
        }
    }

    /** Флаг «установлен» в кеше полных паков и в трендах. */
    private fun applyInstalledFlags(installedIds: Set<Long>) {
        _sets.update { sets -> sets.mapValues { (_, set) -> set.withInstalled(installedIds) } }
        _trending.update { state -> state.copy(sets = state.sets.map { it.withInstalled(installedIds) }) }
    }

    private suspend fun loadAll() {
        loadRecent()
        loadFavorites()
        loadInstalled()
        if (_trending.value.sets.isEmpty()) loadMoreTrending()
    }

    private suspend fun loadRecent() {
        runCatching { td.send(TdApi.GetRecentStickers(false)) }
            .onSuccess { result -> _recent.value = result.stickers.map { it.toInfo() } }
            .onFailure { Log.w(TAG, "getRecentStickers failed", it) }
    }

    private suspend fun loadFavorites() {
        runCatching { td.send(TdApi.GetFavoriteStickers()) }
            .onSuccess { result -> _favorites.value = result.stickers.map { it.toInfo() } }
            .onFailure { Log.w(TAG, "getFavoriteStickers failed", it) }
    }

    private suspend fun loadInstalled() {
        runCatching { td.send(TdApi.GetInstalledStickerSets(TdApi.StickerTypeRegular())) }
            .onSuccess { result -> _installed.value = result.sets.map { it.toModel() } }
            .onFailure { Log.w(TAG, "getInstalledStickerSets failed", it) }
    }

    private fun reset() {
        _recent.value = emptyList()
        _favorites.value = emptyList()
        _installed.value = emptyList()
        _sets.value = emptyMap()
        _trending.value = TrendingStickers()
    }

    private companion object {
        const val TAG = "StickersRepository"
        const val TRENDING_PAGE = 20
        const val SEARCH_LIMIT = 100
        const val SEARCH_SETS_LIMIT = 20
    }
}

/** Результат поиска в панели стикеров (#396). */
data class StickerSearch(
    val stickers: List<StickerInfo> = emptyList(),
    val sets: List<StickerSetInfo> = emptyList(),
) {
    val isEmpty: Boolean get() = stickers.isEmpty() && sets.isEmpty()
}
