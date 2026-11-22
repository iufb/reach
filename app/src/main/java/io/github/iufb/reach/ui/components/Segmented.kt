package io.github.iufb.reach.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.iufb.reach.ui.theme.ReachTheme

/**
 * Сегментный переключатель iOS: на дорожке плашка выбранного сегмента, которая переезжает к новому выбору.
 * Плашка светлее дорожки в обеих темах: днём белая `cell` на сером `fill`, ночью наоборот.
 */
@Composable
fun SegmentedControl(
    items: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ReachTheme.colors
    val track = RoundedCornerShape(percent = 50)
    val trackColor = if (colors.isDark) colors.cell else colors.fill
    val thumbColor = if (colors.isDark) colors.fill else colors.cell
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(SEGMENT_HEIGHT)
            .clip(track)
            .background(trackColor)
            .padding(SEGMENT_INSET),
    ) {
        val segment = maxWidth / items.size
        val offset by animateDpAsState(segment * selected, tween(SLIDE_MS), label = "segment")
        Box(
            Modifier
                .offset(x = offset)
                .width(segment)
                .fillMaxHeight()
                .shadow(1.dp, track)
                .background(thumbColor, track),
        )
        Row(Modifier.fillMaxWidth()) {
            items.forEachIndexed { index, label ->
                Box(
                    Modifier
                        .width(segment)
                        .fillMaxHeight()
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onSelect(index) }
                        .semantics { this.selected = index == selected },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (index == selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = colors.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private val SEGMENT_HEIGHT = 36.dp
private val SEGMENT_INSET = 3.dp
private const val SLIDE_MS = 220
