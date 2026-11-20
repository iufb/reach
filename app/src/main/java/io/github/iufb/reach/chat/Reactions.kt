package io.github.iufb.reach.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.rememberLottieAnimatable
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.iufb.reach.media.rememberTgsComposition
import kotlinx.coroutines.delay

/** Чипы реакций под содержимым сообщения. [onClick] `null` — чипы не нажимаются. */
@Composable
internal fun ReactionChips(
    reactions: List<Reaction>,
    modifier: Modifier = Modifier,
    onClick: ((ReactionKey) -> Unit)? = null,
) {
    if (reactions.isEmpty()) return
    FlowRow(
        modifier = modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        reactions.forEach { reaction -> ReactionChip(reaction, onClick) }
    }
}

@Composable
private fun ReactionChip(reaction: Reaction, onClick: ((ReactionKey) -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    val background = if (reaction.isChosen) colors.primary else colors.primary.copy(alpha = 0.12f)
    val content = if (reaction.isChosen) colors.onPrimary else colors.primary
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .then(if (onClick != null) Modifier.clickable { onClick(reaction.key) } else Modifier)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChipIcon(reaction)
        Text(
            formatCount(reaction.count),
            modifier = Modifier.padding(start = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = content,
        )
    }
}

/**
 * Ряд реакций в меню сообщения; «▾» раскрывает все доступные. Поставленные отмечены фоном,
 * повторный выбор снимает реакцию.
 */
@Composable
internal fun ReactionPicker(available: AvailableReactions, current: List<Reaction>, onPick: (ReactionKey) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val chosen = current.filter { it.isChosen }.mapTo(HashSet()) { it.key }
    val shown = if (expanded) available.all else available.top
    FlowRow(
        modifier = Modifier.widthIn(max = 280.dp).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        shown.forEach { key ->
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (key in chosen) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { onPick(key) },
                contentAlignment = Alignment.Center,
            ) {
                PickerIcon(key)
            }
        }
        if (!expanded && available.all.size > available.top.size) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable { expanded = true },
                contentAlignment = Alignment.Center,
            ) {
                Text("▾", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Эмодзи реакции; кастомные эмодзи и звёзды пока заглушкой (PRODUCT.md §4.5, v0.6). */
internal fun reactionSymbol(key: ReactionKey): String = when (key) {
    is ReactionKey.Emoji -> key.emoji
    is ReactionKey.Custom -> "✨"
    ReactionKey.Paid -> "⭐"
}

/** 1234 → «1,2K», как в официальном клиенте. */
internal fun formatCount(count: Int): String = when {
    count < 1_000 -> count.toString()
    count < 1_000_000 -> compact(count / 100, "K")
    else -> compact(count / 100_000, "M")
}

private fun compact(tenths: Int, suffix: String): String {
    val whole = tenths / 10
    val fraction = tenths % 10
    return if (fraction == 0 || whole >= 100) "$whole$suffix" else "$whole,$fraction$suffix"
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ReactionsEntryPoint {
    fun reactionsRepository(): ReactionsRepository
}

/** Анимации эмодзи-реакции; `null` — кастомная/платная реакция, ещё грузится или анимаций нет. */
@Composable
private fun rememberReactionAnimations(key: ReactionKey): ReactionAnimations? {
    val emoji = (key as? ReactionKey.Emoji)?.emoji ?: return null
    val context = LocalContext.current.applicationContext
    val repository = remember(context) {
        EntryPointAccessors.fromApplication(context, ReactionsEntryPoint::class.java).reactionsRepository()
    }
    val animations by produceState<ReactionAnimations?>(null, emoji) { value = repository.animations(emoji) }
    return animations
}

/**
 * Значок в чипе. Обычно — эмодзи; когда реакция только что появилась или выросла у видимого
 * сообщения (а не при открытии истории), один раз проигрывается центральная анимация.
 */
@Composable
private fun ChipIcon(reaction: Reaction) {
    var previous by remember { mutableStateOf(reaction) }
    var plays by remember { mutableIntStateOf(0) }
    LaunchedEffect(reaction.count, reaction.isChosen) {
        val before = previous
        previous = reaction
        if (reaction.count > before.count || (reaction.isChosen && !before.isChosen)) plays++
    }
    val composition = rememberTgsComposition(if (plays > 0) rememberReactionAnimations(reaction.key)?.center else null)
    var playing by remember { mutableStateOf(false) }
    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        if (composition != null && playing) {
            val animatable = rememberLottieAnimatable()
            LaunchedEffect(composition, plays) {
                animatable.animate(composition, iterations = 1)
                playing = false
            }
            LottieAnimation(composition, progress = { animatable.progress }, modifier = Modifier.requiredSize(32.dp))
        } else {
            Text(reactionSymbol(reaction.key), style = MaterialTheme.typography.bodyMedium)
        }
    }
    LaunchedEffect(plays) { if (plays > 0) playing = true }
}

/** Значок в меню: анимация появления один раз, дальше — её последний кадр; без анимации — эмодзи. */
@Composable
private fun PickerIcon(key: ReactionKey) {
    val composition = rememberTgsComposition(rememberReactionAnimations(key)?.appear)
    if (composition == null) {
        Text(reactionSymbol(key), fontSize = 22.sp)
        return
    }
    val animatable = rememberLottieAnimatable()
    LaunchedEffect(composition) { animatable.animate(composition, iterations = 1) }
    LottieAnimation(composition, progress = { animatable.progress }, modifier = Modifier.size(32.dp))
}

/** Эффект своей реакции поверх сообщения; по окончании — [onDone]. Без анимации — сразу [onDone]. */
@Composable
internal fun ReactionEffect(key: ReactionKey, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val animations = rememberReactionAnimations(key)
    val composition = rememberTgsComposition(animations?.effect)
    val done by rememberUpdatedState(onDone)
    // Ничего не загрузилось за разумное время — не держим эффект вечно
    LaunchedEffect(key) {
        delay(EFFECT_TIMEOUT_MS)
        done()
    }
    if (composition == null) return
    val animatable = rememberLottieAnimatable()
    LaunchedEffect(composition) {
        animatable.animate(composition, iterations = 1)
        done()
    }
    LottieAnimation(composition, progress = { animatable.progress }, modifier = modifier)
}

private const val EFFECT_TIMEOUT_MS = 5000L
