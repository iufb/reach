package io.github.iufb.reach.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import io.github.iufb.reach.R
import io.github.iufb.reach.ui.theme.ReachTheme

/** Скругление карточки сгруппированного списка (iOS 26). */
val GroupedCorner = 26.dp

/**
 * Карточка сгруппированного списка: ячейки на фоне `cell` со скруглением, над ней подзаголовок,
 * под ней пояснение. Экран под карточками — `groupedBackground`.
 */
@Composable
fun GroupedSection(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = ReachTheme.colors
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (header != null) {
            Text(
                header.uppercase(),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = colors.secondaryLabel,
            )
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(GroupedCorner)).background(colors.cell),
            content = content,
        )
        if (footer != null) {
            Text(
                footer,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = colors.secondaryLabel,
            )
        }
    }
}

/** Подзаголовок секции простого списка («КОНТАКТЫ», «СООБЩЕНИЯ»): серая полоса во всю ширину. */
@Composable
fun ListSectionHeader(text: String, modifier: Modifier = Modifier) {
    val colors = ReachTheme.colors
    Text(
        text.uppercase(),
        modifier = modifier.fillMaxWidth().background(colors.groupedBackground).padding(horizontal = 16.dp, vertical = 6.dp),
        style = MaterialTheme.typography.bodySmall,
        color = colors.secondaryLabel,
    )
}

/**
 * Шапка шторки во весь экран: слева «Отмена», по центру заголовок (многоточие, если не влез), справа
 * действие. Заголовок не заезжает под кнопки.
 */
@Composable
fun SheetHeader(
    title: String,
    cancel: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    action: String? = null,
    actionEnabled: Boolean = true,
    onAction: () -> Unit = {},
) {
    DialogSystemBars()
    Row(
        modifier.fillMaxWidth().heightIn(min = TopBarHeight).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PillTextButton(cancel, onClick = onCancel)
        Text(
            title,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            style = MaterialTheme.typography.titleMedium,
            color = ReachTheme.colors.label,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (action != null) PillTextButton(action, onClick = onAction, bold = true, enabled = actionEnabled)
        else Spacer(Modifier.width(PillHeight))
    }
}

/**
 * Цвет значков статус-бара у окна диалога-шторки: у диалога своё окно, и без этого значки
 * остаются светлыми на светлом фоне. Вне диалога ничего не делает.
 */
@Composable
fun DialogSystemBars() {
    val view = LocalView.current
    val dark = ReachTheme.colors.isDark
    val window = (view.parent as? DialogWindowProvider)?.window ?: return
    SideEffect {
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}

/** Цветной квадрат со значком слева в ячейке, как в настройках iOS. */
@Composable
fun CellIcon(icon: Painter, background: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.size(CELL_ICON).clip(RoundedCornerShape(8.dp)).background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, Modifier.size(20.dp), tint = Color.White)
    }
}

/**
 * Ячейка сгруппированного списка: [icon] слева, [title], справа [value] и шеврон.
 * [divider] — разделитель снизу (у последней ячейки карточки — `false`); он начинается от текста.
 * [destructive] — красный заголовок («Выйти»), [accent] — синий («Добавить участника»).
 */
@Composable
fun GroupedCell(
    title: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    subtitle: String? = null,
    value: String? = null,
    chevron: Boolean = onClick != null && value == null,
    destructive: Boolean = false,
    accent: Boolean = false,
    divider: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = ReachTheme.colors
    Column(modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = CELL_HEIGHT).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                icon()
                Spacer(Modifier.width(ICON_GAP))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = when {
                        destructive -> colors.destructive
                        accent -> colors.accent
                        else -> colors.label
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.secondaryLabel)
                }
            }
            if (value != null) {
                Text(
                    value,
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.secondaryLabel,
                    maxLines = 1,
                )
            }
            trailing?.invoke()
            if (chevron || (value != null && onClick != null)) {
                Icon(
                    painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    modifier = Modifier.padding(start = 6.dp).size(16.dp),
                    tint = colors.tertiaryLabel,
                )
            }
        }
        if (divider) {
            val inset: Dp = if (icon != null) 16.dp + CELL_ICON + ICON_GAP else 16.dp
            HorizontalDivider(Modifier.padding(start = inset), thickness = 0.5.dp, color = colors.separator)
        }
    }
}

/**
 * Поле ввода в карточке сгруппированного списка (название группы, описание): без рамки, плейсхолдер серым,
 * [prefix] — неизменяемое начало («t.me/»). [divider] — как у [GroupedCell].
 */
@Composable
fun GroupedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    prefix: String? = null,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else 6,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    divider: Boolean = true,
    /** Фокус на само поле (открыть клавиатуру сразу). */
    focusRequester: FocusRequester? = null,
) {
    val colors = ReachTheme.colors
    val style = MaterialTheme.typography.bodyLarge.copy(color = colors.label)
    Column(modifier.fillMaxWidth()) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier).heightIn(min = CELL_HEIGHT).padding(horizontal = 16.dp, vertical = 14.dp),
            textStyle = style,
            singleLine = singleLine,
            maxLines = maxLines,
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            cursorBrush = SolidColor(colors.accent),
            decorationBox = { inner ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (prefix != null) Text(prefix, style = style.copy(color = colors.secondaryLabel))
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) Text(placeholder, style = style.copy(color = colors.tertiaryLabel))
                        inner()
                    }
                }
            },
        )
        if (divider) HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = colors.separator)
    }
}

private val CELL_HEIGHT = 52.dp
private val CELL_ICON = 30.dp
private val ICON_GAP = 14.dp
