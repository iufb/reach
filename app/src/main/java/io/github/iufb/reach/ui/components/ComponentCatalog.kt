package io.github.iufb.reach.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.iufb.reach.R
import io.github.iufb.reach.ui.theme.ReachTheme

/**
 * Каталог компонентов Telegram iOS (#197): то, из чего собираются экраны. В debug-сборках открывается
 * из настроек — так компоненты и размытие проверяются на устройстве до переноса экранов.
 */
@Composable
fun ComponentCatalogScreen(onBack: () -> Unit) {
    val colors = ReachTheme.colors
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var sheet by remember { mutableStateOf(false) }
    var alert by remember { mutableStateOf(false) }
    var toggle by remember { mutableStateOf(true) }
    GlassScope {
        Box(Modifier.fillMaxSize().background(colors.groupedBackground)) {
            LazyColumn(
                Modifier.fillMaxSize().glassSource(),
                contentPadding = PaddingValues(top = TopBarHeight + 40.dp, bottom = TabBarHeight + 64.dp),
            ) {
                item { SearchField(query, { query = it }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                item {
                    GroupedSection(header = "Сгруппированный список", footer = "Пояснение под карточкой.") {
                        GroupedCell(
                            "Уведомления и звуки",
                            onClick = {},
                            icon = { CellIcon(painterResource(R.drawable.ic_notification), Color(0xFFFF3B30)) },
                        )
                        GroupedCell(
                            "Конфиденциальность",
                            onClick = {},
                            icon = { CellIcon(painterResource(R.drawable.ic_lock), Color(0xFF8E8E93)) },
                        )
                        GroupedCell(
                            "Язык",
                            onClick = {},
                            value = "Русский",
                            icon = { CellIcon(painterResource(R.drawable.ic_link), Color(0xFFAF52DE)) },
                        )
                        GroupedCell(
                            "Без звука",
                            divider = false,
                            icon = { CellIcon(painterResource(R.drawable.ic_muted), Color(0xFF34C759)) },
                            trailing = { Switch(toggle, { toggle = it }) },
                        )
                    }
                }
                item {
                    GroupedSection {
                        GroupedCell("Добавить участника", onClick = {}, accent = true, chevron = false)
                        GroupedCell("Выйти", onClick = { alert = true }, destructive = true, chevron = false, divider = false)
                    }
                }
                item {
                    GroupedSection(header = "Кнопки") {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AccentPillButton("Открыть", {}, compact = true)
                            AccentPillButton("Action sheet", { sheet = true })
                        }
                    }
                }
                item { BubblesDemo() }
                // Длинная лента, чтобы было что размывать под шапкой и tab bar
                items(30) { i ->
                    Text(
                        "Строка $i — прокрутите под шапку и tab bar",
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (i % 3 == 0) colors.accent else colors.label,
                    )
                }
            }
            ReachTopBar(
                title = "Компоненты",
                subtitle = "каталог #197",
                navigation = { BackPill(onBack) },
                actions = {
                    PillGroup {
                        PillGroupIcon(painterResource(R.drawable.ic_search), null, {})
                        PillGroupIcon(painterResource(R.drawable.ic_more), null, {})
                    }
                },
            )
            ReachTabBar(
                items = listOf(
                    TabItem(painterResource(R.drawable.ic_bookmark), "Контакты"),
                    TabItem(painterResource(R.drawable.ic_mic), "Звонки"),
                    TabItem(painterResource(R.drawable.ic_send), "Чаты", badge = 3),
                    TabItem(painterResource(R.drawable.ic_more), "Настройки"),
                ),
                selected = tab,
                onSelect = { tab = it },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
    if (sheet) {
        ActionSheet(
            title = "Удалить чат «Audit Group»?",
            actions = listOf(
                SheetAction("Удалить только у меня", destructive = true) {},
                SheetAction("Удалить у всех", destructive = true) {},
            ),
            onDismiss = { sheet = false },
        )
    }
    if (alert) {
        IosAlert(
            title = "Выйти из аккаунта?",
            message = "Черновики и настройки на этом устройстве сохранятся.",
            buttons = listOf(SheetAction("Отмена") {}, SheetAction("Выйти", destructive = true, bold = true) {}),
            onDismiss = { alert = false },
        )
    }
}

@Composable
private fun BubblesDemo() {
    val colors = ReachTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp)
            .background(Brush.verticalGradient(listOf(colors.wallpaperTop, colors.wallpaperBottom)))
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                "Сегодня",
                modifier = Modifier.background(colors.serviceBubble, MaterialTheme.shapes.extraLarge)
                    .padding(horizontal = 10.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onServiceBubble,
            )
        }
        Spacer(Modifier.height(6.dp))
        BubbleLine(outgoing = false, tail = false, "Привет! Как дела?")
        BubbleLine(outgoing = false, tail = true, "Давно не виделись")
        Spacer(Modifier.height(6.dp))
        BubbleLine(outgoing = true, tail = false, "Отлично!")
        BubbleLine(outgoing = true, tail = true, "Хвостик только у последнего в группе")
    }
}

@Composable
private fun BubbleLine(outgoing: Boolean, tail: Boolean, text: String) {
    val colors = ReachTheme.colors
    Box(Modifier.fillMaxWidth(), contentAlignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart) {
        MessageBubble(outgoing, tail, Modifier.widthIn(max = 280.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(text, style = MaterialTheme.typography.bodyLarge, color = colors.label, modifier = Modifier.weight(1f, fill = false))
                Text(
                    "12:54",
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (outgoing) colors.bubbleOutgoingMeta else colors.bubbleIncomingMeta,
                )
            }
        }
    }
}

@Preview(name = "Каталог — день", heightDp = 1400)
@Preview(name = "Каталог — ночь", heightDp = 1400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ComponentCatalogPreview() {
    ReachTheme { ComponentCatalogScreen(onBack = {}) }
}

@Preview(name = "Action sheet")
@Composable
private fun ActionSheetPreview() {
    ReachTheme {
        Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            ActionSheet(listOf(SheetAction("Удалить", destructive = true) {}), onDismiss = {}, title = "Удалить сообщение?")
        }
    }
}
