package io.github.iufb.reach.calls

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.draw.rotate
import androidx.core.content.ContextCompat
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iufb.reach.R
import io.github.iufb.reach.ui.components.Avatar
import kotlinx.coroutines.delay
import android.os.SystemClock

/**
 * Экран звонка как в Telegram iOS (#409): градиент по состоянию (фиолетово-синий — соединение,
 * зелёный — разговор, оранжевый — слабый сигнал), аватар, имя, статус или таймер, эмодзи ключа,
 * кнопки «динамик», «видео», «микрофон», «завершить». «Назад» сворачивает экран, звонок идёт дальше.
 */
@Composable
fun CallScreen(onBack: () -> Unit, viewModel: CallViewModel = hiltViewModel()) {
    val call by viewModel.call.collectAsStateWithLifecycle()
    val user by viewModel.user.collectAsStateWithLifecycle()
    val current = call
    // Звонок убран (итог показан, оценка дана) — экран больше не нужен
    LaunchedEffect(current == null) { if (current == null) onBack() }
    if (current == null) return
    BackHandler(onBack = onBack)

    val name = user?.fullName.orEmpty()
    var emojiInfo by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    // Ответ на входящий: без микрофона tgcalls не запустить — сначала разрешение (#410)
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.accept() else Toast.makeText(context, R.string.call_mic_permission, Toast.LENGTH_SHORT).show()
    }
    val onAccept = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            viewModel.accept()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Box(Modifier.fillMaxSize().background(backgroundBrush(current))) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            TopRow(current, onBack = onBack, onEmoji = { emojiInfo = true })
            Spacer(Modifier.weight(1f))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(AVATAR + 28.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Avatar(name, user?.photo, user?.accentColorId ?: 0, size = AVATAR)
                }
                Text(
                    name,
                    modifier = Modifier.padding(top = 24.dp, start = 24.dp, end = 24.dp),
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Status(current, Modifier.padding(top = 6.dp))
                AnimatedVisibility(current.remoteMuted && current.phase == CallPhase.Active, enter = fadeIn(), exit = fadeOut()) {
                    Hint(stringResource(R.string.call_remote_muted), Modifier.padding(top = 16.dp))
                }
            }
            Spacer(Modifier.weight(1.3f))
            if (current.phase == CallPhase.Incoming) {
                IncomingButtons(onDecline = viewModel::hangUp, onAccept = onAccept)
            } else {
                Buttons(
                    call = current,
                    onSpeaker = viewModel::toggleSpeaker,
                    onVideo = { Toast.makeText(context, R.string.call_video_soon, Toast.LENGTH_SHORT).show() },
                    onMute = viewModel::toggleMute,
                    onEnd = viewModel::hangUp,
                )
            }
        }
        if (emojiInfo && current.emojis.isNotEmpty()) {
            EmojiInfo(current.emojis, onDismiss = { emojiInfo = false })
        }
        if (current.isEnded && current.needRating) {
            Rating(onRate = viewModel::rate, onSkip = viewModel::dismiss)
        }
    }
}

@Composable
private fun TopRow(call: CallUi, onBack: () -> Unit, onEmoji: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(44.dp)) {
        Row(
            Modifier
                .align(Alignment.CenterStart)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onBack)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_chevron_left), null, tint = Color.White, modifier = Modifier.size(22.dp))
            Text(stringResource(R.string.call_back), color = Color.White, fontSize = 17.sp)
        }
        if (call.emojis.isNotEmpty() && !call.isEnded) {
            Text(
                call.emojis.joinToString(""),
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onEmoji)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                fontSize = 22.sp,
                letterSpacing = 2.sp,
            )
        }
    }
}

@Composable
private fun Status(call: CallUi, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (call.phase == CallPhase.Active) {
            SignalBars(call.signalBars ?: 4)
            Spacer(Modifier.width(6.dp))
            Text(timer(call.connectedAt), color = Color.White, fontSize = 17.sp)
        } else {
            Text(stringResource(callStatusRes(call.phase)), color = Color.White, fontSize = 17.sp)
            if (!call.isEnded) LoadingDots(Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun timer(connectedAt: Long?): String {
    val seconds by produceState(0L, connectedAt) {
        while (connectedAt != null) {
            value = (SystemClock.elapsedRealtime() - connectedAt) / 1000
            delay(1000 - (SystemClock.elapsedRealtime() - connectedAt) % 1000)
        }
    }
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Четыре «палочки» качества связи, как в статус-баре iOS. */
@Composable
private fun SignalBars(bars: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(1.5.dp), verticalAlignment = Alignment.Bottom) {
        repeat(4) { index ->
            Box(
                Modifier
                    .width(3.dp)
                    .height((4 + index * 2.5).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(Color.White.copy(alpha = if (index < bars) 1f else 0.4f)),
            )
        }
    }
}

@Composable
private fun LoadingDots(modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "dots")
    val phase by transition.animateFloat(0f, 3f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "phase")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { index ->
            val active = phase.toInt() == index
            Box(Modifier.size(4.dp).clip(CircleShape).background(Color.White.copy(alpha = if (active) 1f else 0.45f)))
        }
    }
}

@Composable
private fun Hint(text: String, modifier: Modifier) {
    Text(
        text,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.18f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        color = Color.White,
        fontSize = 15.sp,
    )
}

@Composable
private fun Buttons(call: CallUi, onSpeaker: () -> Unit, onVideo: () -> Unit, onMute: () -> Unit, onEnd: () -> Unit) {
    val ended = call.isEnded
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        val speakerLabel = if (call.route?.kind == AudioRoute.Kind.Bluetooth) R.string.call_audio else R.string.call_speaker
        CallButton(R.drawable.ic_speaker, speakerLabel, active = call.isSpeaker, enabled = !ended, onClick = onSpeaker)
        CallButton(R.drawable.ic_videocam, R.string.call_video, active = false, enabled = !ended, onClick = onVideo)
        CallButton(
            if (call.muted) R.drawable.ic_mic_off else R.drawable.ic_mic,
            if (call.muted) R.string.call_unmute else R.string.call_mute,
            active = call.muted,
            enabled = !ended,
            onClick = onMute,
        )
        CallButton(
            R.drawable.ic_close,
            if (ended) R.string.call_close else R.string.call_end,
            active = false,
            enabled = true,
            background = END_RED,
            onClick = onEnd,
        )
    }
}

/** Входящий: «отклонить» (красная, трубка вниз) и «принять» (зелёная). */
@Composable
private fun IncomingButtons(onDecline: () -> Unit, onAccept: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        CallButton(R.drawable.ic_call, R.string.call_decline, active = false, enabled = true, background = END_RED, iconRotation = 135f, onClick = onDecline)
        CallButton(R.drawable.ic_call, R.string.call_accept, active = false, enabled = true, background = ACCEPT_GREEN, onClick = onAccept)
    }
}

@Composable
private fun CallButton(
    @DrawableRes icon: Int,
    @StringRes label: Int,
    active: Boolean,
    enabled: Boolean,
    background: Color? = null,
    iconRotation: Float = 0f,
    onClick: () -> Unit,
) {
    Column(
        Modifier.width(72.dp).alpha(if (enabled) 1f else 0.5f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val fill by animateColorAsState(background ?: if (active) Color.White else Color.White.copy(alpha = 0.22f), label = "fill")
        val tint by animateColorAsState(if (active && background == null) ACTIVE_ICON else Color.White, label = "tint")
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(fill)
                .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), null, tint = tint, modifier = Modifier.size(28.dp).rotate(iconRotation))
        }
        Text(
            stringResource(label),
            modifier = Modifier.padding(top = 6.dp),
            color = Color.White,
            fontSize = 13.sp,
            maxLines = 1,
        )
    }
}

/** Пояснение к эмодзи ключа: тап по эмодзи сверху. */
@Composable
private fun EmojiInfo(emojis: List<String>, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(32.dp)
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(emojis.joinToString(" "), fontSize = 44.sp)
            Text(
                stringResource(R.string.call_emoji_title),
                modifier = Modifier.padding(top = 16.dp),
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.call_emoji_text),
                modifier = Modifier.padding(top = 8.dp),
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.call_emoji_ok),
                modifier = Modifier
                    .padding(top = 20.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.22f))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 40.dp, vertical = 10.dp),
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** Оценка после звонка (`callStateDiscarded.needRating`): звёзды 1..5. */
@Composable
private fun Rating(onRate: (Int) -> Unit, onSkip: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .padding(32.dp)
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.call_rate_title), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.call_rate_text),
                modifier = Modifier.padding(top = 6.dp),
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
            )
            var selected by remember { mutableIntStateOf(0) }
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (star in 1..5) {
                    Icon(
                        painterResource(R.drawable.ic_star),
                        contentDescription = star.toString(),
                        tint = if (star <= selected) STAR_GOLD else Color.White.copy(alpha = 0.5f),
                        modifier = Modifier
                            .size(36.dp)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                selected = star
                                onRate(star)
                            },
                    )
                }
            }
            Text(
                stringResource(R.string.call_rate_skip),
                modifier = Modifier
                    .padding(top = 16.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onSkip)
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                color = Color.White,
                fontSize = 17.sp,
            )
        }
    }
}

@Composable
private fun backgroundBrush(call: CallUi): Brush {
    val palette = when {
        call.phase == CallPhase.Active && (call.signalBars ?: 4) <= 1 -> WEAK
        call.phase == CallPhase.Active -> ACTIVE
        call.isEnded -> ENDED
        else -> CONNECTING
    }
    val top by animateColorAsState(palette.first, tween(700), label = "top")
    val bottom by animateColorAsState(palette.second, tween(700), label = "bottom")
    return Brush.linearGradient(listOf(top, bottom), start = androidx.compose.ui.geometry.Offset(Float.POSITIVE_INFINITY, 0f), end = androidx.compose.ui.geometry.Offset(0f, Float.POSITIVE_INFINITY))
}

@StringRes
fun callStatusRes(phase: CallPhase): Int = when (phase) {
    CallPhase.Incoming -> R.string.call_status_incoming
    CallPhase.Requesting -> R.string.call_status_requesting
    CallPhase.Waiting -> R.string.call_status_waiting
    CallPhase.Ringing -> R.string.call_status_ringing
    CallPhase.ExchangingKeys -> R.string.call_status_exchanging_keys
    CallPhase.Connecting, CallPhase.Active -> R.string.call_status_connecting
    CallPhase.Reconnecting -> R.string.call_status_reconnecting
    CallPhase.HangingUp -> R.string.call_status_hanging_up
    is CallPhase.Ended -> when (phase.reason) {
        EndReason.HungUp -> R.string.call_status_ended
        EndReason.Declined -> R.string.call_status_busy
        EndReason.Missed -> R.string.call_status_missed
        EndReason.Disconnected -> R.string.call_status_disconnected
        EndReason.Failed -> R.string.call_status_failed
    }
}

private val AVATAR = 112.dp
private val END_RED = Color(0xFFFF3B30)
private val ACCEPT_GREEN = Color(0xFF34C759)
private val ACTIVE_ICON = Color(0xFF5B6FD6)
private val STAR_GOLD = Color(0xFFFFCC00)

// Градиенты Telegram iOS: верх-право → низ-лево
private val CONNECTING = Color(0xFF9D6FE0) to Color(0xFF5E8CE6)
private val ACTIVE = Color(0xFF52A6C4) to Color(0xFF8DBB63)
private val WEAK = Color(0xFFE6A24C) to Color(0xFFDB6649)
private val ENDED = Color(0xFF8E8E93) to Color(0xFF636366)
