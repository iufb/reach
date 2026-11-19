package io.github.iufb.reach.chats

import org.drinkless.tdlib.TdApi

/**
 * Текст с форматированием — модель `FormattedText` из TDLib без зависимости UI от TdApi.
 * Смещения в UTF-16, как у `String` в Kotlin и у entities в TDLib.
 */
data class RichText(val text: String, val spans: List<TextSpan> = emptyList()) {
    fun isBlank(): Boolean = text.isBlank()

    companion object {
        val Empty = RichText("")
    }
}

/** Участок текста с [start] по [end] (не включая) с оформлением [kind]. */
data class TextSpan(val start: Int, val end: Int, val kind: SpanKind)

sealed interface SpanKind {
    data object Bold : SpanKind
    data object Italic : SpanKind
    data object Underline : SpanKind
    data object Strikethrough : SpanKind
    /** Пока рисуется обычным текстом; анимация скрытия — v0.5. */
    data object Spoiler : SpanKind
    data object Code : SpanKind
    /** Блок кода, [language] может быть пустым. */
    data class Pre(val language: String) : SpanKind
    data object Quote : SpanKind
    data class Link(val url: String) : SpanKind
}

internal fun TdApi.FormattedText?.toRichText(): RichText {
    if (this == null) return RichText.Empty
    val spans = entities.orEmpty().mapNotNull { entity ->
        val start = entity.offset.coerceIn(0, text.length)
        val end = (entity.offset + entity.length).coerceIn(start, text.length)
        if (start == end) return@mapNotNull null
        val kind = entity.type.toSpanKind(text.substring(start, end)) ?: return@mapNotNull null
        TextSpan(start, end, kind)
    }
    return RichText(text, spans)
}

/** [covered] — сам участок текста: у `Url`, почты и телефона адрес и есть текст. */
private fun TdApi.TextEntityType.toSpanKind(covered: String): SpanKind? = when (this) {
    is TdApi.TextEntityTypeBold -> SpanKind.Bold
    is TdApi.TextEntityTypeItalic -> SpanKind.Italic
    is TdApi.TextEntityTypeUnderline -> SpanKind.Underline
    is TdApi.TextEntityTypeStrikethrough -> SpanKind.Strikethrough
    is TdApi.TextEntityTypeSpoiler -> SpanKind.Spoiler
    is TdApi.TextEntityTypeCode -> SpanKind.Code
    is TdApi.TextEntityTypePre -> SpanKind.Pre("")
    is TdApi.TextEntityTypePreCode -> SpanKind.Pre(language.orEmpty())
    is TdApi.TextEntityTypeBlockQuote, is TdApi.TextEntityTypeExpandableBlockQuote -> SpanKind.Quote
    is TdApi.TextEntityTypeTextUrl -> SpanKind.Link(url)
    is TdApi.TextEntityTypeUrl -> SpanKind.Link(if ("://" in covered) covered else "https://$covered")
    is TdApi.TextEntityTypeEmailAddress -> SpanKind.Link("mailto:$covered")
    is TdApi.TextEntityTypePhoneNumber -> SpanKind.Link("tel:$covered")
    // Упоминания, хэштеги и остальное — пока обычный текст
    else -> null
}
