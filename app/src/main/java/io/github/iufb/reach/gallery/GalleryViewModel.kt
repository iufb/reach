package io.github.iufb.reach.gallery

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.iufb.reach.R
import io.github.iufb.reach.chat.Message
import io.github.iufb.reach.chat.OpenFile
import io.github.iufb.reach.chat.toModel
import io.github.iufb.reach.chats.MessageBody
import io.github.iufb.reach.chats.DocumentInfo
import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.files.DocumentDownloads
import io.github.iufb.reach.files.FileState
import io.github.iufb.reach.files.FilesRepository
import io.github.iufb.reach.media.AudioPlayer
import io.github.iufb.reach.media.AudioState
import io.github.iufb.reach.media.AudioTrack
import io.github.iufb.reach.navigation.ProfileRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject

/** Вкладки галереи и их фильтры `searchChatMessages`. */
enum class GalleryTab(@StringRes val label: Int) {
    Media(R.string.gallery_media),
    Files(R.string.gallery_files),
    Links(R.string.gallery_links),
    Voice(R.string.gallery_voice),
    Music(R.string.gallery_music),
    Gifs(R.string.gallery_gifs),
    ;

    fun filter(): TdApi.SearchMessagesFilter = when (this) {
        Media -> TdApi.SearchMessagesFilterPhotoAndVideo()
        Files -> TdApi.SearchMessagesFilterDocument()
        Links -> TdApi.SearchMessagesFilterUrl()
        Voice -> TdApi.SearchMessagesFilterVoiceNote()
        Music -> TdApi.SearchMessagesFilterAudio()
        Gifs -> TdApi.SearchMessagesFilterAnimation()
    }
}

/** Загруженная часть вкладки, от новых к старым. */
data class TabPage(
    val messages: List<Message> = emptyList(),
    val loading: Boolean = false,
    val reachedEnd: Boolean = false,
)

/**
 * Медиа-галерея чата (PRODUCT.md §4.4) — вкладки внизу профиля, как в Telegram iOS: сообщения по типам
 * через `searchChatMessages` с фильтром, постранично. Счётчики — `getChatMessageCount`; пустые вкладки
 * не показываются. Чат — из [ProfileRoute]: у пользователя без чата это личный чат с ним (id совпадают).
 */
@HiltViewModel
class GalleryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val td: TdClient,
    files: FilesRepository,
    private val audioPlayer: AudioPlayer,
) : ViewModel() {

    val chatId: Long = savedStateHandle.toRoute<ProfileRoute>().let { if (it.chatId != 0L) it.chatId else it.userId }

    private val _counts = MutableStateFlow<Map<GalleryTab, Int>?>(null)
    /** Сколько сообщений каждого типа; `null` — ещё считаем. */
    val counts: StateFlow<Map<GalleryTab, Int>?> = _counts.asStateFlow()

    private val _pages = MutableStateFlow<Map<GalleryTab, TabPage>>(emptyMap())
    val pages: StateFlow<Map<GalleryTab, TabPage>> = _pages.asStateFlow()

    private val documents = DocumentDownloads(files, viewModelScope)
    val downloads: StateFlow<Map<Int, FileState>> = documents.states
    val openFile: SharedFlow<OpenFile> = documents.open

    val audio: StateFlow<AudioState> = audioPlayer.state
        .map { if (it.track?.chatId == chatId) it else AudioState() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AudioState())

    init {
        viewModelScope.launch {
            _counts.value = GalleryTab.entries.associateWith { tab ->
                runCatching { td.send(TdApi.GetChatMessageCount(chatId, null, tab.filter(), false)).count }
                    .onFailure { Log.w(TAG, "count $tab failed", it) }
                    .getOrDefault(0)
            }
        }
    }

    /** Следующая страница вкладки; повторные вызовы во время загрузки игнорируются. */
    fun loadMore(tab: GalleryTab) {
        val page = _pages.value[tab] ?: TabPage()
        if (page.loading || page.reachedEnd) return
        _pages.update { it + (tab to page.copy(loading = true)) }
        viewModelScope.launch {
            val from = page.messages.lastOrNull()?.id ?: 0L
            runCatching {
                td.send(TdApi.SearchChatMessages(chatId, null, "", null, from, 0, PAGE_SIZE, tab.filter()))
            }.onSuccess { found ->
                val loaded = found.messages.map { it.toModel() }.filter { new -> page.messages.none { it.id == new.id } }
                _pages.update {
                    it + (tab to TabPage(page.messages + loaded, loading = false, reachedEnd = found.nextFromMessageId == 0L || loaded.isEmpty()))
                }
            }.onFailure {
                Log.w(TAG, "search $tab failed", it)
                _pages.update { it + (tab to page.copy(loading = false, reachedEnd = true)) }
            }
        }
    }

    fun onDocumentClick(document: DocumentInfo) = documents.onClick(document)

    /** Голосовое или трек из вкладки: играть его и дальше по списку (от новых к старым, как в списке). */
    fun onAudioToggle(tab: GalleryTab, message: Message) {
        val tracks = _pages.value[tab]?.messages.orEmpty().mapNotNull { it.track() }
        val index = tracks.indexOfFirst { it.messageId == message.id }.takeIf { it >= 0 } ?: return
        audioPlayer.toggleIn(tracks, index)
    }

    fun onAudioSeek(fraction: Float) = audioPlayer.seekTo(fraction)

    private fun Message.track(): AudioTrack? = when (val body = content) {
        is MessageBody.Voice -> AudioTrack(chatId, id, body.voice.file, isVoice = true, duration = body.voice.duration)
        is MessageBody.Audio -> body.audio.let {
            AudioTrack(chatId, id, it.file, isVoice = false, it.displayTitle, it.performer, it.duration, it.cover)
        }
        else -> null
    }

    private companion object {
        const val TAG = "Gallery"
        const val PAGE_SIZE = 50
    }
}
