package io.github.iufb.reach.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.iufb.reach.R
import io.github.iufb.reach.ui.theme.ReachTheme

/**
 * Круглая отметка выбора iOS: пустое кольцо или акцентный круг с галочкой.
 * [enabled] `false` — отмечено, но не снимается (уже участник): приглушена.
 */
@Composable
fun RoundCheck(selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = ReachTheme.colors
    Box(
        modifier
            .size(RoundCheckSize)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(CircleShape)
            .then(if (selected) Modifier.background(colors.accent) else Modifier.border(1.5.dp, colors.tertiaryLabel, CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(painterResource(R.drawable.ic_check), contentDescription = null, Modifier.size(16.dp), tint = colors.onAccent)
    }
}

val RoundCheckSize = 24.dp
private const val DISABLED_ALPHA = 0.5f
