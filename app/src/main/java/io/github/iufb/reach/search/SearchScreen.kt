package io.github.iufb.reach.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iufb.reach.R
import io.github.iufb.reach.chats.Chat
import io.github.iufb.reach.chats.ChatAvatar
import io.github.iufb.reach.chats.chatTitle
import io.github.iufb.reach.chats.bodySummary
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import io.github.iufb.reach.ui.components.Avatar
import io.github.iufb.reach.ui.format.Dates
import io.github.iufb.reach.ui.theme.reachBarColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenChat: (Long) -> Unit,
    onOpenMessage: (chatId: Long, messageId: Long) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val open = { chatId: Long ->
        viewModel.onChatOpened(chatId)
        onOpenChat(chatId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = reachBarColors(),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
                title = {
                    TextField(
                        value = query,
                        onValueChange = viewModel::onQueryChange,
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        placeholder = { Text(stringResource(R.string.search_hint)) },
                        singleLine = true,
                        trailingIcon = if (query.isNotEmpty()) {
                            {
                                IconButton(onClick = { viewModel.onQueryChange("") }) {
                                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.search_clear))
                                }
                            }
                        } else {
                            null
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (results.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                results.isEmpty && results.loading -> Unit
                results.isEmpty -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(if (results.isRecent) R.string.search_recent_empty else R.string.search_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    val chatsTitle = stringResource(if (results.isRecent) R.string.search_recent else R.string.search_chats)
                    val publicTitle = stringResource(R.string.search_global)
                    val messagesTitle = stringResource(R.string.search_messages)
                    val listState = rememberLazyListState()
                    LoadMoreMessagesEffect(listState, results.canLoadMoreMessages, viewModel::loadMoreMessages)
                    LazyColumn(Modifier.fillMaxSize(), state = listState) {
                        if (results.chats.isNotEmpty()) section(chatsTitle, results.chats, open)
                        if (results.publicChats.isNotEmpty()) section(publicTitle, results.publicChats, open)
                        if (results.messages.isNotEmpty()) {
                            item(key = "header:messages") { SectionHeader(messagesTitle) }
                            items(results.messages, key = { "message:${it.chat.id}:${it.messageId}" }) { message ->
                                MessageRow(message, results.query) { onOpenMessage(message.chat.id, message.messageId) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Подгрузка сообщений, когда до конца списка осталось несколько строк. */
@Composable
private fun LoadMoreMessagesEffect(listState: LazyListState, enabled: Boolean, onLoadMore: () -> Unit) {
    val load by rememberUpdatedState(onLoadMore)
    LaunchedEffect(listState, enabled) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - LOAD_MORE_THRESHOLD
        }
            .distinctUntilChanged()
            .filter { it }
            .collect { load() }
    }
}

private const val LOAD_MORE_THRESHOLD = 5

/** Сообщение: чат, дата и фрагмент текста с подсвеченным запросом. */
@Composable
private fun MessageRow(message: FoundMessage, query: String, onClick: () -> Unit) {
    val highlight = MaterialTheme.colorScheme.primary
    val text = bodySummary(message.body).replace('\n', ' ')
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChatAvatar(message.chat, size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    chatTitle(message.chat),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    Dates.chatListTime(message.date.toLong()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                highlighted(snippet(text, query), query, highlight),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Фрагмент вокруг первого совпадения: длинный текст не прячет найденное за многоточием. */
internal fun snippet(text: String, query: String, before: Int = 30): String {
    val at = text.indexOf(query, ignoreCase = true)
    if (at <= before) return text
    val start = text.lastIndexOf(' ', at - before).takeIf { it >= 0 }?.plus(1) ?: (at - before)
    return "…" + text.substring(start)
}

/** Все вхождения [query] без учёта регистра — цветом и жирным. */
internal fun highlighted(text: String, query: String, color: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    if (query.isBlank()) return@buildAnnotatedString
    var from = 0
    while (true) {
        val at = text.indexOf(query, from, ignoreCase = true)
        if (at < 0) break
        addStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold), at, at + query.length)
        from = at + query.length
    }
}

internal fun LazyListScope.section(title: String, chats: List<Chat>, onClick: (Long) -> Unit) {
    item(key = "header:$title") { SectionHeader(title) }
    items(chats, key = { "chat:${it.id}" }) { chat -> SearchChatRow(chat) { onClick(chat.id) } }
}

@Composable
internal fun SectionHeader(title: String) {
    Text(
        title,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
internal fun SearchChatRow(chat: Chat, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChatAvatar(chat, size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                chatTitle(chat),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
