package io.github.iufb.reach.calls

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.iufb.reach.R
import kotlinx.coroutines.delay

/** Плашка «Вернуться к звонку» над приложением, пока звонок свёрнут (Telegram iOS). */
@Composable
fun CallBar(call: CallUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = if (call.phase == CallPhase.Active) BAR_GREEN else BAR_BLUE
    Row(
        modifier
            .fillMaxWidth()
            .background(color)
            .clickable(onClick = onClick)
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(28.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.call_bar),
            modifier = Modifier.weight(1f),
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        val connected = call.connectedAt
        val status = if (call.phase == CallPhase.Active && connected != null) {
            val seconds by produceState(0L, connected) {
                while (true) {
                    value = (SystemClock.elapsedRealtime() - connected) / 1000
                    delay(1000)
                }
            }
            "%d:%02d".format(seconds / 60, seconds % 60)
        } else {
            stringResource(callStatusRes(call.phase))
        }
        Text(status, color = Color.White, fontSize = 14.sp, maxLines = 1)
    }
}

private val BAR_GREEN = Color(0xFF34C759)
private val BAR_BLUE = Color(0xFF5E8CE6)
