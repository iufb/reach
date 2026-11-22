package io.github.iufb.reach.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iufb.reach.R
import io.github.iufb.reach.chats.Chat
import io.github.iufb.reach.chats.ChatAvatar
import io.github.iufb.reach.chats.ChatKind
import io.github.iufb.reach.chats.bodySummary
import io.github.iufb.reach.chats.chatTitle
import io.github.iufb.reach.ui.components.ListSectionHeader
import io.github.iufb.reach.ui.components.SearchField
import io.github.iufb.reach.ui.format.Dates
import io.github.iufb.reach.ui.theme.ReachTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * Глобальный поиск как в Telegram iOS: сверху поле поиска и «Отмена»; без запроса — ряд недавних
 * чатов аватарами, с запросом — секции «Чаты», «Глобальный поиск», «Сообщения» с подписями.
 */
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
    val colors = ReachTheme.colors

    Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchField(
                query,
                onQueryChange = viewModel::onQueryChange,
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            Text(
                stringResource(R.string.cancel),
                modifier = Modifier.clip(RoundedCornerShape(percent = 50)).clickable(onClick = onBack).padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.accent,
            )
        }
        // Полоса загрузки занимает место всегда: список не сдвигается, когда она появляется
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (results.loading) LinearProgressIndicator(Modifier.fillMaxSize(), color = colors.accent, trackColor = colors.background)
        }
        when {
            results.isEmpty && results.loading -> Unit
            results.isEmpty -> Text(
                stringResource(if (results.isRecent) R.string.search_recent_empty else R.string.search_empty),
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                color = colors.secondaryLabel,
                textAlign = TextAlign.Center,
            )
            else -> {
                val listState = rememberLazyListState()
                LoadMoreMessagesEffect(listState, results.canLoadMoreMessages, viewModel::loadMoreMessages)
                LazyColumn(Modifier.fillMaxSize().imePadding(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                    if (results.isRecent) {
                        item(key = "header:recent") { ListSectionHeader(stringResource(R.string.search_recent)) }
                        item(key = "recent") { RecentRow(results.chats, open) }
                    } else {
                        if (results.chats.isNotEmpty()) section(stringResource(R.string.search_chats), results.chats, results.details, open)
                        if (results.publicChats.isNotEmpty()) {
                            section(stringResource(R.string.search_global), results.publicChats, results.details, open)
                        }
                    }
                    if (results.messages.isNotEmpty()) {
                        item(key = "header:messages") { ListSectionHeader(stringResource(R.string.search_messages)) }
                        items(results.messages, key = { "message:${it.chat.id}:${it.messageId}" }) { message ->
                            MessageRow(message, results.query, divider = message != results.messages.last()) {
                                onOpenMessage(message.chat.id, message.messageId)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Недавние чаты рядом аватаров с подписью, как в iOS. */
@Composable
private fun RecentRow(chats: List<Chat>, onClick: (Long) -> Unit) {
    val colors = ReachTheme.colors
    LazyRow(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)) {
        items(chats, key = { it.id }) { chat ->
            Column(
                Modifier.width(RECENT_WIDTH).clip(RoundedCornerShape(12.dp)).clickable { onClick(chat.id) }.padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ChatAvatar(chat, size = RECENT_AVATAR)
                Text(
                    chatTitle(chat),
                    modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.label,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val RECENT_WIDTH = 76.dp
private val RECENT_AVATAR = 56.dp
private val ROW_AVATAR = 40.dp
private val ROW_TEXT_INSET = 16.dp + ROW_AVATAR + 12.dp

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
private fun MessageRow(message: FoundMessage, query: String, divider: Boolean, onClick: () -> Unit) {
    val colors = ReachTheme.colors
    val text = bodySummary(message.body).replace('\n', ' ')
    ResultRow(
        avatar = { ChatAvatar(message.chat, size = ROW_AVATAR) },
        title = chatTitle(message.chat),
        trailing = Dates.chatListTime(message.date.toLong()),
        subtitle = highlighted(snippet(text, query), query, colors.label),
        subtitleLines = 2,
        divider = divider,
        onClick = onClick,
    )
}

/** Строка результата: аватар, заголовок (справа дата), подпись; разделитель от текста. */
@Composable
private fun ResultRow(
    avatar: @Composable () -> Unit,
    title: String,
    subtitle: AnnotatedString?,
    divider: Boolean,
    onClick: () -> Unit,
    trailing: String? = null,
    subtitleLines: Int = 1,
) {
    val colors = ReachTheme.colors
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            avatar()
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (trailing != null) {
                        Text(trailing, modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelMedium, color = colors.secondaryLabel)
                    }
                }
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.secondaryLabel,
                        maxLines = subtitleLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (divider) HorizontalDivider(Modifier.padding(start = ROW_TEXT_INSET), thickness = 0.5.dp, color = colors.separator)
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

private fun LazyListScope.section(title: String, chats: List<Chat>, details: Map<Long, ChatDetails>, onClick: (Long) -> Unit) {
    item(key = "header:$title") { ListSectionHeader(title) }
    items(chats, key = { "chat:${it.id}" }) { chat ->
        ResultRow(
            avatar = { ChatAvatar(chat, size = ROW_AVATAR) },
            title = chatTitle(chat),
            subtitle = details[chat.id]?.let { chatSubtitle(chat, it) }?.let(::AnnotatedString),
            divider = chat != chats.last(),
            onClick = { onClick(chat.id) },
        )
    }
}

/** «@username, 1 234 подписчика» — то, по чему результат найден и насколько он большой. */
@Composable
private fun chatSubtitle(chat: Chat, details: ChatDetails): String? {
    val count = details.memberCount.takeIf { it > 0 }?.let {
        pluralStringResource(if (chat.kind == ChatKind.Channel) R.plurals.subscribers else R.plurals.members, it, it)
    }
    return listOfNotNull(details.username.takeIf { it.isNotBlank() }?.let { "@$it" }, count).joinToString(", ").ifEmpty { null }
}
