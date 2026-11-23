package io.github.iufb.reach.chat

import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import io.github.iufb.reach.media.CustomEmojiView
import io.github.iufb.reach.chats.RichText
import io.github.iufb.reach.chats.SpanKind
import io.github.iufb.reach.ui.theme.LocalMonospace
import androidx.compose.ui.unit.TextUnit

/** Текст сообщения с форматированием; ссылки открываются по тапу (LocalUriHandler). */
@Composable
internal fun RichTextView(
    text: RichText,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    /** Невидимый заполнитель такой ширины в конце текста — место под время сообщения. */
    trailingSpace: TextUnit? = null,
    /** Сторона кастомного эмодзи в em шрифта. */
    customEmojiEm: Float = CUSTOM_EMOJI_EM,
) {
    val colors = richTextColors()
    val mentions = LocalMentionHandler.current
    // Тап по любому спойлеру раскрывает все спойлеры текста
    var revealed by remember(text) { mutableStateOf(false) }
    val hide = !revealed && text.spans.any { it.kind == SpanKind.Spoiler }
    val spoilers = if (hide) Spoilers.Hidden(onReveal = { revealed = true }) else Spoilers.Plain
    val annotated = remember(text, colors, mentions, hide, trailingSpace) {
        val base = text.toAnnotatedString(colors, mentions = mentions, spoilers = spoilers, inlineEmoji = true)
        if (trailingSpace == null) base else buildAnnotatedString {
            append(base)
            appendInlineContent(TRAILING_SPACE_KEY, " ")
        }
    }
    val inlineContent = remember(text, trailingSpace, customEmojiEm) {
        val emoji = customEmojiContent(text, customEmojiEm)
        if (trailingSpace == null) emoji else emoji + (TRAILING_SPACE_KEY to InlineTextContent(
            Placeholder(trailingSpace, 1.em, PlaceholderVerticalAlign.TextBottom),
        ) {})
    }
    val uriHandler = LocalUriHandler.current
    val safeHandler = remember(uriHandler) { SafeUriHandler(uriHandler) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    CompositionLocalProvider(LocalUriHandler provides safeHandler) {
        Text(
            annotated,
            modifier = modifier.then(if (hide) Modifier.spoilerDust(text, layout) else Modifier),
            style = style,
            inlineContent = inlineContent,
            onTextLayout = { layout = it },
        )
    }
}

/** Вставки для кастомных эмодзи текста: квадрат чуть больше строки, по центру строки. */
private const val TRAILING_SPACE_KEY = "reach:trailing-space"

private fun customEmojiContent(text: RichText, em: Float): Map<String, InlineTextContent> =
    text.spans.mapNotNull { (it.kind as? SpanKind.CustomEmoji)?.id }.distinct().associate { id ->
        customEmojiKey(id) to InlineTextContent(
            Placeholder(em.em, em.em, PlaceholderVerticalAlign.TextCenter),
        ) { fallback -> CustomEmojiView(id, fallback, Modifier.fillMaxSize()) }
    }

private fun customEmojiKey(id: Long) = "custom_emoji:$id"

private const val CUSTOM_EMOJI_EM = 1.2f

/**
 * Кастомный эмодзи в крупном эмодзи-сообщении: размер глифа Noto (≈1.07 em на устройстве), чтобы
 * обычные и кастомные эмодзи одного количества были одного размера (#294).
 */
internal const val BIG_CUSTOM_EMOJI_EM = 1.07f

/** Частицы поверх скрытых участков; текст под ними прозрачный (см. [Spoilers.Hidden]). */
@Composable
private fun Modifier.spoilerDust(text: RichText, layout: TextLayoutResult?): Modifier {
    val phase = rememberSpoilerPhase()
    val color = MaterialTheme.colorScheme.onSurface
    val areas = remember(text, layout) {
        val l = layout ?: return@remember emptyList()
        text.spans.filter { it.kind == SpanKind.Spoiler }.map { span ->
            val end = span.end.coerceAtMost(l.layoutInput.text.length)
            val start = span.start.coerceAtMost(end)
            l.getPathForRange(start, end)
        }
    }
    return drawWithContent {
        drawContent()
        for (path in areas) {
            clipPath(path) { drawSpoilerParticles(path.getBounds(), phase.value, color) }
        }
    }
}

/** Как рисовать спойлеры. */
internal sealed interface Spoilers {
    /** Обычным текстом: раскрытые в сообщении. */
    data object Plain : Spoilers

    /** Подсветкой фона: поле ввода, где автор видит, что спрятал. */
    data object Marked : Spoilers

    /** Скрыты: прозрачный текст, по тапу — [onReveal]. */
    class Hidden(val onReveal: () -> Unit) : Spoilers
}

/** Что делать по тапу на упоминание; задаёт экран чата. */
internal interface MentionHandler {
    fun openUsername(username: String)
    fun openUser(userId: Long)
    /** Тап по хэштегу: поиск по нему (#221). */
    fun openHashtag(tag: String) {}
}

/** `null` — упоминания только выделены цветом. */
internal val LocalMentionHandler = staticCompositionLocalOf<MentionHandler?> { null }

/** Ссылка, которую нечем открыть (кривой адрес, нет приложения), не должна ронять экран. */
private class SafeUriHandler(private val delegate: UriHandler) : UriHandler {
    override fun openUri(uri: String) {
        runCatching { delegate.openUri(uri) }.onFailure { Log.w("RichTextView", "can't open $uri", it) }
    }
}

/** [code] — шрифт кода: моноширинный, со своим emoji-шрифтом в fallback, если он выбран. */
internal data class RichTextColors(
    val link: Color,
    val codeBackground: Color,
    val quote: Color,
    val spoiler: Color,
    val code: FontFamily = FontFamily.Monospace,
)

@Composable
internal fun richTextColors() = RichTextColors(
    link = MaterialTheme.colorScheme.primary,
    codeBackground = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
    quote = MaterialTheme.colorScheme.primary,
    spoiler = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
    code = LocalMonospace.current,
)

/**
 * Entities → AnnotatedString. Блоки (код, цитата) оформляются абзацем; абзацы в Compose
 * не могут пересекаться частично, поэтому блок, задевающий уже оформленный, остаётся без абзаца.
 */
internal fun RichText.toAnnotatedString(
    colors: RichTextColors,
    clickableLinks: Boolean = true,
    mentions: MentionHandler? = null,
    spoilers: Spoilers = Spoilers.Plain,
    inlineEmoji: Boolean = false,
): AnnotatedString = buildAnnotatedString {
    val hidden = if (spoilers is Spoilers.Hidden) spans.filter { it.kind == SpanKind.Spoiler } else emptyList()
    // Ссылка внутри скрытого не должна открываться случайным тапом
    fun isHidden(start: Int, end: Int) = hidden.any { it.start < end && start < it.end }
    // Кастомные эмодзи — вставки поверх своего запасного символа: длина текста и смещения те же
    var pos = 0
    if (inlineEmoji) {
        for (span in spans.sortedBy { it.start }) {
            val kind = span.kind as? SpanKind.CustomEmoji ?: continue
            if (span.start < pos || isHidden(span.start, span.end)) continue
            append(text, pos, span.start)
            appendInlineContent(customEmojiKey(kind.id), text.substring(span.start, span.end))
            pos = span.end
        }
    }
    append(text, pos, text.length)
    val paragraphs = mutableListOf<IntRange>()
    fun paragraph(start: Int, end: Int, style: ParagraphStyle) {
        val range = start until end
        val overlaps = paragraphs.any { it.first < end && start <= it.last }
        if (overlaps) return
        paragraphs += range
        addStyle(style, start, end)
    }
    for (span in spans) {
        val (start, end) = span.start to span.end
        when (val kind = span.kind) {
            SpanKind.Bold -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
            SpanKind.Italic -> addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, end)
            SpanKind.Underline -> addStyle(SpanStyle(textDecoration = TextDecoration.Underline), start, end)
            SpanKind.Strikethrough -> addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, end)
            SpanKind.Spoiler -> when (spoilers) {
                Spoilers.Plain -> Unit
                Spoilers.Marked -> addStyle(SpanStyle(background = colors.spoiler), start, end)
                // Стиль добавляется последним (ниже), чтобы прозрачность перекрыла цвета ссылок и цитат
                is Spoilers.Hidden -> Unit
            }
            SpanKind.Code -> addStyle(SpanStyle(fontFamily = colors.code, background = colors.codeBackground), start, end)
            is SpanKind.Pre -> {
                addStyle(SpanStyle(fontFamily = colors.code, background = colors.codeBackground), start, end)
                paragraph(start, end, ParagraphStyle())
            }
            SpanKind.Quote -> {
                addStyle(SpanStyle(fontStyle = FontStyle.Italic, color = colors.quote), start, end)
                paragraph(start, end, ParagraphStyle(textIndent = TextIndent(firstLine = QUOTE_INDENT, restLine = QUOTE_INDENT)))
            }
            is SpanKind.Link -> {
                val style = SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline)
                // В поле ввода ссылка только выглядит ссылкой: тап ставит курсор, а не открывает её
                if (clickableLinks && !isHidden(start, end)) {
                    addLink(LinkAnnotation.Url(kind.url, TextLinkStyles(style)), start, end)
                } else {
                    addStyle(style, start, end)
                }
            }
            is SpanKind.Mention, is SpanKind.MentionUser -> {
                val style = SpanStyle(color = colors.link)
                val open: (() -> Unit)? = mentions?.let { handler ->
                    when (kind) {
                        is SpanKind.Mention -> ({ handler.openUsername(kind.username) })
                        is SpanKind.MentionUser -> ({ handler.openUser(kind.userId) })
                        else -> null
                    }
                }
                if (clickableLinks && open != null && !isHidden(start, end)) {
                    addLink(LinkAnnotation.Clickable("mention", TextLinkStyles(style)) { open() }, start, end)
                } else {
                    addStyle(style, start, end)
                }
            }
            // По тапу пока ничего: поиск по хэштегу — вместе с поиском (v0.3)
            is SpanKind.Hashtag -> {
                val style = SpanStyle(color = colors.link)
                val handler = mentions
                if (clickableLinks && handler != null && !isHidden(start, end)) {
                    addLink(LinkAnnotation.Clickable("hashtag", TextLinkStyles(style)) { handler.openHashtag(kind.tag) }, start, end)
                } else {
                    addStyle(style, start, end)
                }
            }
            is SpanKind.CustomEmoji -> Unit
        }
    }
    if (spoilers is Spoilers.Hidden) {
        val invisible = SpanStyle(color = Color.Transparent, background = colors.spoiler)
        for (span in hidden) {
            addLink(
                LinkAnnotation.Clickable(SPOILER_TAG, TextLinkStyles(invisible)) { spoilers.onReveal() },
                span.start,
                span.end,
            )
        }
    }
}

private const val SPOILER_TAG = "spoiler"

private val QUOTE_INDENT = 12.sp
