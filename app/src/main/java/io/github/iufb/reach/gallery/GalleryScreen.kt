package io.github.iufb.reach.gallery

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iufb.reach.R
import io.github.iufb.reach.chat.DocumentRow
import io.github.iufb.reach.chat.MediaViewer
import io.github.iufb.reach.chat.Message
import io.github.iufb.reach.chat.PhotoThumbnail
import io.github.iufb.reach.chat.RichTextView
import io.github.iufb.reach.chat.VideoThumbnail
import io.github.iufb.reach.chat.ViewerItem
import io.github.iufb.reach.chat.VoiceNoteView
import io.github.iufb.reach.chat.openFile
import io.github.iufb.reach.chats.MessageBody
import io.github.iufb.reach.chats.bodySummary
import io.github.iufb.reach.files.FileState
import io.github.iufb.reach.media.AudioState
import io.github.iufb.reach.ui.theme.reachBarColors
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/** Медиа, файлы, ссылки, голосовые, музыка и GIF чата — по вкладкам. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    onBack: () -> Unit,
    onShowInChat: (chatId: Long, messageId: Long) -> Unit,
    viewModel: GalleryViewModel = hiltViewModel(),
) {
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    val pages by viewModel.pages.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val audio by viewModel.audio.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) { viewModel.openFile.collect { openFile(context, it) } }
    var viewer by remember { mutableStateOf<Pair<List<ViewerItem>, Int>?>(null) }
    viewer?.let { (items, index) -> MediaViewer(items, index) { viewer = null } }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = reachBarColors(),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
                title = { Text(stringResource(R.string.gallery_title)) },
            )
        },
    ) { padding ->
        val known = counts
        if (known == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val tabs = GalleryTab.entries.filter { (known[it] ?: 0) > 0 }
        if (tabs.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.gallery_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }
        var selectedName by rememberSaveable { mutableStateOf(tabs.first().name) }
        val selected = tabs.firstOrNull { it.name == selectedName } ?: tabs.first()
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryScrollableTabRow(selectedTabIndex = tabs.indexOf(selected), edgePadding = 8.dp) {
                tabs.forEach { tab ->
                    Tab(
                        selected = tab == selected,
                        onClick = { selectedName = tab.name },
                        text = { Text("${stringResource(tab.label)} ${known[tab] ?: 0}") },
                    )
                }
            }
            val page = pages[selected] ?: TabPage()
            LaunchedEffect(selected) { if (page.messages.isEmpty()) viewModel.loadMore(selected) }
            val showInChat = { message: Message -> onShowInChat(viewModel.chatId, message.id) }
            when (selected) {
                GalleryTab.Media, GalleryTab.Gifs -> MediaGrid(
                    page,
                    onLoadMore = { viewModel.loadMore(selected) },
                    onOpen = { items, index -> viewer = items to index },
                    onShowInChat = showInChat,
                )
                else -> MessageList(
                    page,
                    downloads = downloads,
                    audio = audio,
                    onLoadMore = { viewModel.loadMore(selected) },
                    onDocumentClick = viewModel::onDocumentClick,
                    onAudioToggle = { viewModel.onAudioToggle(selected, it) },
                    onAudioSeek = viewModel::onAudioSeek,
                    onShowInChat = showInChat,
                )
            }
        }
    }
}

/** Сетка миниатюр; тап — просмотр с листанием по всем загруженным. */
@Composable
private fun MediaGrid(
    page: TabPage,
    onLoadMore: () -> Unit,
    onOpen: (List<ViewerItem>, Int) -> Unit,
    onShowInChat: (Message) -> Unit,
) {
    val state = rememberLazyGridState()
    LoadMoreEffect(state, page, onLoadMore)
    val media = page.messages.mapNotNull { m -> m.viewerItem()?.let { m to it } }
    val items = media.map { it.second }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = state,
        contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(media, key = { it.first.id }) { (message, item) ->
            WithShowInChat(
                onClick = { onOpen(items, items.indexOf(item)) },
                onShowInChat = { onShowInChat(message) },
            ) { modifier ->
                val cell = modifier.fillMaxWidth().aspectRatio(1f)
                Box(cell.background(MaterialTheme.colorScheme.surfaceVariant)) {
                    when (item) {
                        is ViewerItem.Photo -> PhotoThumbnail(item.photo, square = true)
                        is ViewerItem.Video -> VideoThumbnail(item.video, square = true)
                        is ViewerItem.Animation -> PhotoThumbnail(item.animation.poster, square = true)
                    }
                }
            }
        }
    }
}

/** Файлы, ссылки, голосовые, музыка — списком. */
@Composable
private fun MessageList(
    page: TabPage,
    downloads: Map<Int, FileState>,
    audio: AudioState,
    onLoadMore: () -> Unit,
    onDocumentClick: (io.github.iufb.reach.chats.DocumentInfo) -> Unit,
    onAudioToggle: (Message) -> Unit,
    onAudioSeek: (Float) -> Unit,
    onShowInChat: (Message) -> Unit,
) {
    val state = rememberLazyListState()
    LoadMoreEffect(state, page, onLoadMore)
    LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
        items(page.messages, key = { it.id }) { message ->
            WithShowInChat(
                onClick = {
                    when (val body = message.content) {
                        is MessageBody.Document -> onDocumentClick(body.document)
                        is MessageBody.Voice -> onAudioToggle(message)
                        else -> onShowInChat(message)
                    }
                },
                onShowInChat = { onShowInChat(message) },
            ) { modifier ->
                Box(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    when (val body = message.content) {
                        is MessageBody.Document -> DocumentRow(body.document, downloads[body.document.file.id])
                        is MessageBody.Voice -> VoiceNoteView(
                            body.voice,
                            isListened = true,
                            playback = audio.takeIf { it.track?.messageId == message.id },
                            onToggle = { onAudioToggle(message) },
                            onSeek = onAudioSeek,
                        )
                        is MessageBody.Text -> RichTextView(body.text)
                        else -> Text(bodySummary(body))
                    }
                }
            }
            HorizontalDivider()
        }
        if (page.loading) {
            item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        }
    }
}

/** Элемент с тапом и меню по долгому тапу («Показать в чате»). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WithShowInChat(onClick: () -> Unit, onShowInChat: () -> Unit, content: @Composable (Modifier) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        content(Modifier.combinedClickable(onClick = onClick, onLongClick = { menu = true }))
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.gallery_show_in_chat)) },
                onClick = {
                    menu = false
                    onShowInChat()
                },
            )
        }
    }
}

@Composable
private fun LoadMoreEffect(state: LazyListState, page: TabPage, onLoadMore: () -> Unit) {
    LaunchedEffect(state, page.messages.size, page.reachedEnd) {
        if (page.reachedEnd) return@LaunchedEffect
        snapshotFlow { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= state.layoutInfo.totalItemsCount - LOAD_AHEAD }
            .distinctUntilChanged().filter { it }.collect { onLoadMore() }
    }
}

@Composable
private fun LoadMoreEffect(state: LazyGridState, page: TabPage, onLoadMore: () -> Unit) {
    LaunchedEffect(state, page.messages.size, page.reachedEnd) {
        if (page.reachedEnd) return@LaunchedEffect
        snapshotFlow { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= state.layoutInfo.totalItemsCount - LOAD_AHEAD }
            .distinctUntilChanged().filter { it }.collect { onLoadMore() }
    }
}

private fun Message.viewerItem(): ViewerItem? = when (val body = content) {
    is MessageBody.Photo -> ViewerItem.Photo(body.photo)
    is MessageBody.Video -> ViewerItem.Video(body.video)
    is MessageBody.Animation -> ViewerItem.Animation(body.animation)
    else -> null
}

private const val LOAD_AHEAD = 12
