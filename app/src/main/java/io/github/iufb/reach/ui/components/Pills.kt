package io.github.iufb.reach.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.iufb.reach.R
import io.github.iufb.reach.ui.theme.ReachTheme

/** Высота плавающих пилюль шапки и tab bar. */
val PillHeight = 44.dp

private val Pill = RoundedCornerShape(percent = 50)

/** Круглая стеклянная кнопка со значком: «назад», «поиск», действия шапки. */
@Composable
fun PillIconButton(
    icon: Painter,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = ReachTheme.colors.label,
) {
    Box(
        modifier
            .size(PillHeight)
            .glass(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(22.dp), tint = tint)
    }
}

/** «Назад» шапки — шеврон в круглой пилюле. */
@Composable
fun BackPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    PillIconButton(painterResource(R.drawable.ic_chevron_left), stringResource(R.string.back), onClick, modifier)
}

/** Стеклянная пилюля с текстом: «Изменить», «Готово». [bold] — основное действие. */
@Composable
fun PillTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, bold: Boolean = false, enabled: Boolean = true) {
    val colors = ReachTheme.colors
    Box(
        modifier
            .height(PillHeight)
            .defaultMinSize(minWidth = PillHeight)
            .glass(Pill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Medium,
            color = if (enabled) colors.label else colors.tertiaryLabel,
        )
    }
}

/** Несколько значков в одной стеклянной пилюле (действия справа в шапке). */
@Composable
fun PillGroup(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.height(PillHeight).glass(Pill).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

/** Значок внутри [PillGroup]. */
@Composable
fun PillGroupIcon(icon: Painter, contentDescription: String?, onClick: () -> Unit, tint: Color = ReachTheme.colors.label) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(22.dp), tint = tint)
    }
}

/**
 * Заливная кнопка-пилюля: «Открыть» в списке, крупные кнопки внизу экрана.
 * [compact] — маленькая, как «OPEN» у бота в списке чатов.
 */
@Composable
fun AccentPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = ReachTheme.colors
    Box(
        modifier
            .height(if (compact) 28.dp else 50.dp)
            .clip(Pill)
            .background(if (enabled) colors.accent else colors.fill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (compact) 12.dp else 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) colors.onAccent else colors.tertiaryLabel,
        )
    }
}
