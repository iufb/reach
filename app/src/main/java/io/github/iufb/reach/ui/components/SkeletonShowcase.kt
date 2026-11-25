package io.github.iufb.reach.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.iufb.reach.chat.ChatSkeleton
import io.github.iufb.reach.chats.SkeletonChatRow
import io.github.iufb.reach.contacts.SkeletonUserRow
import io.github.iufb.reach.forward.SkeletonTargetRow
import io.github.iufb.reach.gallery.SkeletonMessageRow
import io.github.iufb.reach.search.SkeletonResultRow
import io.github.iufb.reach.settings.SettingsSkeleton
import io.github.iufb.reach.stats.GraphSkeleton
import io.github.iufb.reach.stickers.StickerGridSkeleton
import io.github.iufb.reach.topics.SkeletonTopicRow
import io.github.iufb.reach.ui.theme.ReachTheme
import androidx.compose.foundation.isSystemInDarkTheme

/** Скелетоны для витрины в каталоге компонентов (#390): каждый — в раскладке своего экрана. */
enum class SkeletonShowcase(val title: String) {
    Chats("Список чатов"),
    ChatFeed("Лента чата"),
    ChannelFeed("Лента канала"),
    Topics("Темы форума"),
    Users("Контакты и участники"),
    Forward("Пересылка"),
    Search("Поиск"),
    Media("Медиа: файлы и ссылки"),
    StickerSet("Лист стикерпака"),
    Statistics("Графики статистики"),
    Settings("Настройки"),
    Grouped("Экран из карточек"),
}

/**
 * Скелетон [kind] во весь экран, бесконечно, в той же раскладке, что на его экране: число строк,
 * отступы, фон (у ленты — обои чата). Сверху — название и переключатель темы ☀/🌙, чтобы сравнивать
 * светлую и тёмную. Только для отладки: в обычной работе скелетон виден лишь при медленной загрузке.
 */
@Composable
fun SkeletonShowcaseDialog(kind: SkeletonShowcase, onDismiss: () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    var dark by remember { mutableStateOf(systemDark) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ReachTheme(darkTheme = dark) {
            // Значки статус-бара под тему витрины, а не системы
            DialogSystemBars()
            val colors = ReachTheme.colors
            val feed = kind == SkeletonShowcase.ChatFeed || kind == SkeletonShowcase.ChannelFeed
            val background = if (feed) {
                Modifier.background(Brush.verticalGradient(listOf(colors.wallpaperTop, colors.wallpaperBottom)))
            } else {
                Modifier.background(if (kind.grouped) colors.groupedBackground else colors.background)
            }
            Column(Modifier.fillMaxSize().then(background).statusBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        kind.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.label,
                    )
                    ShowcaseChip(if (dark) "☀︎ Светлая" else "☾ Тёмная") { dark = !dark }
                    ShowcaseChip("Закрыть", onDismiss)
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { ShowcaseContent(kind) }
            }
        }
    }
}

private val SkeletonShowcase.grouped: Boolean
    get() = this == SkeletonShowcase.Forward || this == SkeletonShowcase.Media ||
        this == SkeletonShowcase.Settings || this == SkeletonShowcase.Grouped || this == SkeletonShowcase.Statistics

@Composable
private fun ShowcaseContent(kind: SkeletonShowcase) {
    when (kind) {
        SkeletonShowcase.Chats -> LazyColumn(Modifier.fillMaxSize(), userScrollEnabled = false) {
            items(ROWS) { SkeletonChatRow(it) }
        }
        SkeletonShowcase.ChatFeed -> ChatSkeleton(isChannel = false, contentPadding = PaddingValues(vertical = 8.dp))
        SkeletonShowcase.ChannelFeed -> ChatSkeleton(isChannel = true, contentPadding = PaddingValues(vertical = 8.dp))
        SkeletonShowcase.Topics -> LazyColumn(Modifier.fillMaxSize(), userScrollEnabled = false) {
            items(ROWS) { SkeletonTopicRow(it) }
        }
        SkeletonShowcase.Users -> LazyColumn(Modifier.fillMaxSize(), userScrollEnabled = false) {
            items(ROWS) { SkeletonUserRow(it, divider = it < ROWS - 1) }
        }
        SkeletonShowcase.Forward -> LazyColumn(
            Modifier.fillMaxSize(),
            userScrollEnabled = false,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        ) {
            items(GROUPED_ROWS) { SkeletonTargetRow(it, first = it == 0, last = it == GROUPED_ROWS - 1) }
        }
        SkeletonShowcase.Search -> LazyColumn(Modifier.fillMaxSize(), userScrollEnabled = false) {
            items(ROWS) { SkeletonResultRow(it, divider = it < ROWS - 1) }
        }
        SkeletonShowcase.Media -> LazyColumn(
            Modifier.fillMaxSize(),
            userScrollEnabled = false,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            items(GROUPED_ROWS) { DelayedSkeleton { SkeletonMessageRow(it, first = it == 0, last = it == GROUPED_ROWS - 1) } }
        }
        SkeletonShowcase.StickerSet -> Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 40.dp)
                .background(ReachTheme.colors.background, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
        ) { StickerGridSkeleton() }
        SkeletonShowcase.Statistics -> Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            repeat(GRAPHS) { GraphSkeleton() }
        }
        SkeletonShowcase.Settings -> SettingsSkeleton()
        SkeletonShowcase.Grouped -> LazyColumn(Modifier.fillMaxSize(), userScrollEnabled = false) { groupedSkeleton() }
    }
}

@Composable
private fun ShowcaseChip(text: String, onClick: () -> Unit) {
    val colors = ReachTheme.colors
    Text(
        text,
        modifier = Modifier
            .background(colors.fill, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelLarge,
        color = colors.label,
    )
}

private const val ROWS = 14
private const val GROUPED_ROWS = 10
private const val GRAPHS = 2
