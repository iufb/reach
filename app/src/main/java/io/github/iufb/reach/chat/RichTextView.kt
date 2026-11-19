package io.github.iufb.reach.chat

import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
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
import io.github.iufb.reach.chats.RichText
import io.github.iufb.reach.chats.SpanKind

/** Текст сообщения с форматированием; ссылки открываются по тапу (LocalUriHandler). */
@Composable
internal fun RichTextView(
    text: RichText,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val colors = RichTextColors(
        link = MaterialTheme.colorScheme.primary,
        codeBackground = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        quote = MaterialTheme.colorScheme.primary,
    )
    val annotated = remember(text, colors) { text.toAnnotatedString(colors) }
    val uriHandler = LocalUriHandler.current
    val safeHandler = remember(uriHandler) { SafeUriHandler(uriHandler) }
    CompositionLocalProvider(LocalUriHandler provides safeHandler) {
        Text(annotated, modifier = modifier, style = style)
    }
}

/** Ссылка, которую нечем открыть (кривой адрес, нет приложения), не должна ронять экран. */
private class SafeUriHandler(private val delegate: UriHandler) : UriHandler {
    override fun openUri(uri: String) {
        runCatching { delegate.openUri(uri) }.onFailure { Log.w("RichTextView", "can't open $uri", it) }
    }
}

internal data class RichTextColors(val link: Color, val codeBackground: Color, val quote: Color)

/**
 * Entities → AnnotatedString. Блоки (код, цитата) оформляются абзацем; абзацы в Compose
 * не могут пересекаться частично, поэтому блок, задевающий уже оформленный, остаётся без абзаца.
 */
internal fun RichText.toAnnotatedString(colors: RichTextColors): AnnotatedString = buildAnnotatedString {
    append(text)
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
            SpanKind.Spoiler -> Unit
            SpanKind.Code -> addStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = colors.codeBackground), start, end)
            is SpanKind.Pre -> {
                addStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = colors.codeBackground), start, end)
                paragraph(start, end, ParagraphStyle())
            }
            SpanKind.Quote -> {
                addStyle(SpanStyle(fontStyle = FontStyle.Italic, color = colors.quote), start, end)
                paragraph(start, end, ParagraphStyle(textIndent = TextIndent(firstLine = QUOTE_INDENT, restLine = QUOTE_INDENT)))
            }
            is SpanKind.Link -> addLink(
                LinkAnnotation.Url(kind.url, TextLinkStyles(SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline))),
                start,
                end,
            )
        }
    }
}

private val QUOTE_INDENT = 12.sp
