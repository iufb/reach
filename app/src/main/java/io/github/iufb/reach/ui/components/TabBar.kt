package io.github.iufb.reach.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.iufb.reach.ui.theme.ReachTheme

/** Вкладка нижней панели; [badge] — счётчик в красном кружке (0 — нет). */
class TabItem(val icon: Painter, val label: String, val badge: Int = 0)

/** Высота плавающего tab bar без навигационной полосы. */
val TabBarHeight = 62.dp

/**
 * Нижний tab bar Telegram iOS 26: стеклянная пилюля над содержимым, выбранная вкладка
 * подсвечена капсулой и акцентом. Содержимое под ним прокручивается (`navigationBarsPadding` внутри).
 */
@Composable
fun ReachTabBar(items: List<TabItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = ReachTheme.colors
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().height(TabBarHeight).glass(RoundedCornerShape(percent = 50)).padding(4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            items.forEachIndexed { index, item ->
                val isSelected = index == selected
                val highlight by animateColorAsState(if (isSelected) colors.fill else Color.Transparent, label = "tab")
                val tint = if (isSelected) colors.accent else colors.label
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(percent = 50))
                        .background(highlight)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab,
                        ) { onSelect(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box {
                        Icon(item.icon, contentDescription = null, Modifier.size(26.dp), tint = tint)
                        if (item.badge > 0) {
                            Box(
                                Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-4).dp)
                                    .clip(CircleShape).background(colors.destructive).padding(horizontal = 5.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    if (item.badge > 99) "99+" else item.badge.toString(),
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                    Text(
                        item.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = tint,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
