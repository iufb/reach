package io.github.iufb.reach.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.iufb.reach.R
import io.github.iufb.reach.ui.theme.ReachTheme

/** Пункт [ActionSheet] или кнопка [IosAlert]. */
class SheetAction(val label: String, val destructive: Boolean = false, val bold: Boolean = false, val onClick: () -> Unit)

/**
 * Action sheet iOS: карточка с заголовком и пунктами, под ней отдельная карточка «Отмена».
 * Пункт закрывает лист и выполняет действие.
 */
@Composable
fun ActionSheet(actions: List<SheetAction>, onDismiss: () -> Unit, title: String? = null) {
    val colors = ReachTheme.colors
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                // Тап по самим карточкам не закрывает лист
                Modifier.navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp)
                    .clickable(remember { MutableInteractionSource() }, indication = null) {},
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(SHEET_CORNER)).background(colors.cell)) {
                    if (title != null) {
                        Text(
                            title,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.secondaryLabel,
                            textAlign = TextAlign.Center,
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = colors.separator)
                    }
                    actions.forEachIndexed { i, action ->
                        if (i > 0) HorizontalDivider(thickness = 0.5.dp, color = colors.separator)
                        SheetRow(action.label, if (action.destructive) colors.destructive else colors.accent, action.bold) {
                            onDismiss()
                            action.onClick()
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(SHEET_CORNER)).background(colors.cell)) {
                    SheetRow(stringResource(R.string.cancel), colors.accent, bold = true, onClick = onDismiss)
                }
            }
        }
    }
}

@Composable
private fun SheetRow(label: String, color: Color, bold: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(57.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            color = color,
        )
    }
}

/**
 * Диалог-подтверждение iOS: заголовок, текст, кнопки. Две кнопки — в ряд, больше — столбиком.
 * Кнопка закрывает диалог и выполняет действие.
 */
@Composable
fun IosAlert(title: String, onDismiss: () -> Unit, buttons: List<SheetAction>, message: String? = null) {
    val colors = ReachTheme.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(ALERT_WIDTH).clip(RoundedCornerShape(ALERT_CORNER)).background(colors.cell),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.label,
                    textAlign = TextAlign.Center,
                )
                if (message != null) {
                    Text(
                        message,
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.label,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = colors.separator)
            val click = { action: SheetAction -> { onDismiss(); action.onClick() } }
            if (buttons.size == 2) {
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                    buttons.forEachIndexed { i, action ->
                        if (i > 0) VerticalDivider(Modifier.height(44.dp), thickness = 0.5.dp, color = colors.separator)
                        AlertButton(action, Modifier.weight(1f), click(action))
                    }
                }
            } else {
                buttons.forEachIndexed { i, action ->
                    if (i > 0) HorizontalDivider(thickness = 0.5.dp, color = colors.separator)
                    AlertButton(action, Modifier.fillMaxWidth(), click(action))
                }
            }
        }
    }
}

@Composable
private fun AlertButton(action: SheetAction, modifier: Modifier, onClick: () -> Unit) {
    val colors = ReachTheme.colors
    Box(modifier.height(44.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(
            action.label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (action.bold) FontWeight.SemiBold else FontWeight.Normal,
            color = if (action.destructive) colors.destructive else colors.accent,
        )
    }
}

private val SHEET_CORNER = 20.dp
private val ALERT_CORNER = 20.dp
private val ALERT_WIDTH = 280.dp
