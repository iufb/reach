package io.github.iufb.reach.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.iufb.reach.R
import io.github.iufb.reach.chats.AudioInfo
import io.github.iufb.reach.chats.PhotoInfo
import io.github.iufb.reach.chats.formatDuration
import io.github.iufb.reach.media.AudioState
import io.github.iufb.reach.media.rememberAudioPlayer

/** Трек в пузыре: обложка с ▶/⏸, название, исполнитель, прогресс и время. */
@Composable
internal fun AudioRow(
    audio: AudioInfo,
    /** Состояние плеера, если играет этот трек; иначе `null`. */
    playback: AudioState?,
    onToggle: () -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val playing = playback?.isPlaying == true
    Row(modifier.padding(vertical = 2.dp).widthIn(max = 280.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onToggle), contentAlignment = Alignment.Center) {
            Cover(audio.cover, 48.dp)
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = if (audio.cover != null) 0.45f else 1f)))
            Icon(
                painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                contentDescription = stringResource(if (playing) R.string.video_pause else R.string.video_play),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Column(Modifier.padding(start = 10.dp).weight(1f, fill = false)) {
            Text(audio.displayTitle, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (audio.performer.isNotBlank()) {
                Text(
                    audio.performer,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (playback != null) {
                Slider(
                    value = playback.progress,
                    onValueChange = onSeek,
                    modifier = Modifier.height(24.dp).width(200.dp),
                )
            }
            Text(
                if (playback != null) {
                    "${formatDuration(playback.positionMs / 1000)} / ${formatDuration(playback.durationMs / 1000)}"
                } else {
                    formatDuration(audio.duration.toLong())
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Cover(cover: PhotoInfo?, size: Dp) {
    if (cover == null) {
        Box(Modifier.size(size).background(MaterialTheme.colorScheme.primary))
        return
    }
    AsyncImage(cover.preview ?: cover.minithumbnail, null, Modifier.size(size), contentScale = ContentScale.Crop)
}

/**
 * Мини-плеер под верхней панелью, пока что-то играет (на любом экране, где он размещён).
 * Тап — полный плеер: обложка, перемотка, предыдущий/следующий.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NowPlayingBar() {
    val player = rememberAudioPlayer()
    val state by player.state.collectAsState()
    val track = state.track ?: return
    var expanded by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().clickable { expanded = true }) {
        Column {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (state.isPlaying) player.pause() else player.resume() }) {
                    Icon(
                        painterResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                        contentDescription = stringResource(if (state.isPlaying) R.string.video_pause else R.string.video_play),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(trackTitle(track.title, track.isVoice), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (track.performer.isNotBlank()) {
                        Text(
                            track.performer,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                IconButton(onClick = player::stop) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.player_close))
                }
            }
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth().height(2.dp))
        }
    }
    if (expanded) {
        ModalBottomSheet(onDismissRequest = { expanded = false }) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.fillMaxWidth(0.7f).aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primary)) {
                    track.cover?.let {
                        AsyncImage(it.full ?: it.preview ?: it.minithumbnail, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(trackTitle(track.title, track.isVoice), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                if (track.performer.isNotBlank()) {
                    Text(track.performer, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Slider(value = state.progress, onValueChange = player::seekTo, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatDuration(state.positionMs / 1000), style = MaterialTheme.typography.labelMedium)
                    Text(formatDuration(state.durationMs / 1000), style = MaterialTheme.typography.labelMedium)
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 24.dp),
                ) {
                    IconButton(onClick = player::previous) {
                        Icon(painterResource(R.drawable.ic_skip_previous), contentDescription = stringResource(R.string.player_previous))
                    }
                    FilledIconButton(
                        onClick = { if (state.isPlaying) player.pause() else player.resume() },
                        modifier = Modifier.size(64.dp),
                        shape = CircleShape,
                    ) {
                        Icon(
                            painterResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                            contentDescription = stringResource(if (state.isPlaying) R.string.video_pause else R.string.video_play),
                        )
                    }
                    IconButton(onClick = player::next, enabled = state.hasNext) {
                        Icon(painterResource(R.drawable.ic_skip_next), contentDescription = stringResource(R.string.player_next))
                    }
                }
            }
        }
    }
}

@Composable
private fun trackTitle(title: String, isVoice: Boolean): String = when {
    title.isNotBlank() -> title
    isVoice -> stringResource(R.string.content_voice_note)
    else -> stringResource(R.string.content_audio)
}
