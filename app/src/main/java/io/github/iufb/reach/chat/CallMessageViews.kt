package io.github.iufb.reach.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.iufb.reach.R
import io.github.iufb.reach.chats.MessageBody
import io.github.iufb.reach.chats.callTitleRes
import io.github.iufb.reach.ui.theme.ReachTheme

/**
 * Звонок в истории чата как в Telegram iOS (#412): заголовок («Исходящий звонок», «Пропущенный звонок»…),
 * под ним стрелка направления (зелёная — состоялся, красная — нет) и длительность; справа трубка —
 * перезвонить.
 */
@Composable
internal fun CallMessageView(call: MessageBody.Call, isOutgoing: Boolean, onCallBack: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ReachTheme.colors
    val style = bubbleStyle()
    val happened = call.duration > 0
    Row(modifier.widthIn(min = 200.dp).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f, fill = false)) {
            Text(
                stringResource(callTitleRes(call, isOutgoing)),
                color = colors.label,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(R.drawable.ic_arrow_back),
                    contentDescription = null,
                    tint = if (happened) CALL_GREEN else colors.destructive,
                    // ← повёрнутая: ↗ исходящий, ↙ входящий
                    modifier = Modifier.size(14.dp).rotate(if (isOutgoing) 135f else -45f),
                )
                if (call.duration > 0) {
                    Spacer(Modifier.width(4.dp))
                    Text(durationText(call.duration), color = style.secondary, fontSize = 14.sp)
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Icon(
            painterResource(if (call.isVideo) R.drawable.ic_videocam else R.drawable.ic_call),
            contentDescription = stringResource(R.string.call_back_action),
            tint = style.accent,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onCallBack)
                .padding(6.dp)
                .size(26.dp),
        )
    }
}

@Composable
private fun durationText(seconds: Int): String =
    if (seconds >= 60) stringResource(R.string.call_duration_min, seconds / 60) else stringResource(R.string.call_duration_sec, seconds)

private val CALL_GREEN = Color(0xFF34C759)
