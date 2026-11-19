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

/** Обратно в `FormattedText` для отправки и редактирования. */
internal fun RichText.toFormattedText(): TdApi.FormattedText {
    val entities = spans.mapNotNull { span ->
        val type = span.kind.toEntityType(text.substring(span.start, span.end)) ?: return@mapNotNull null
        TdApi.TextEntity(span.start, span.end - span.start, type)
    }
    return TdApi.FormattedText(text, entities.toTypedArray())
}

/** Автоссылки (адрес, почта, телефон — сам текст) не отправляем: сервер распознаёт их сам. */
private fun SpanKind.toEntityType(covered: String): TdApi.TextEntityType? = when (this) {
    SpanKind.Bold -> TdApi.TextEntityTypeBold()
    SpanKind.Italic -> TdApi.TextEntityTypeItalic()
    SpanKind.Underline -> TdApi.TextEntityTypeUnderline()
    SpanKind.Strikethrough -> TdApi.TextEntityTypeStrikethrough()
    SpanKind.Spoiler -> TdApi.TextEntityTypeSpoiler()
    SpanKind.Code -> TdApi.TextEntityTypeCode()
    is SpanKind.Pre -> if (language.isEmpty()) TdApi.TextEntityTypePre() else TdApi.TextEntityTypePreCode(language)
    SpanKind.Quote -> TdApi.TextEntityTypeBlockQuote()
    is SpanKind.Link -> when (url) {
        covered, "https://$covered", "mailto:$covered", "tel:$covered" -> null
        else -> TdApi.TextEntityTypeTextUrl(url)
    }
}

/**
 * Тот же текст после правки в простом поле ввода: оформление вне изменённого куска сохраняется.
 * Изменённый кусок — между общим началом и общим концом старого и нового текста; участок,
 * накрывающий его целиком, растягивается, задевающий частично — пропадает.
 */
internal fun RichText.withEditedText(newText: String): RichText {
    if (newText == text) return this
    val maxCommon = minOf(text.length, newText.length)
    var prefix = 0
    while (prefix < maxCommon && text[prefix] == newText[prefix]) prefix++
    var suffix = 0
    while (suffix < maxCommon - prefix && text[text.length - 1 - suffix] == newText[newText.length - 1 - suffix]) suffix++
    val oldEnd = text.length - suffix
    val delta = newText.length - text.length
    val kept = spans.mapNotNull { span ->
        when {
            span.end <= prefix -> span
            span.start >= oldEnd -> span.copy(start = span.start + delta, end = span.end + delta)
            span.start <= prefix && span.end >= oldEnd -> span.copy(end = span.end + delta)
            else -> null
        }
    }.filter { it.start < it.end }
    return RichText(newText, kept)
}

/** Без пробелов по краям; участки обрезаются и сдвигаются вместе с текстом. */
internal fun RichText.trimmed(): RichText {
    val start = text.indexOfFirst { !it.isWhitespace() }
    if (start < 0) return RichText.Empty
    val end = text.indexOfLast { !it.isWhitespace() } + 1
    if (start == 0 && end == text.length) return this
    val clipped = spans.mapNotNull { span ->
        val s = span.start.coerceIn(start, end)
        val e = span.end.coerceIn(start, end)
        if (s < e) span.copy(start = s - start, end = e - start) else null
    }
    return RichText(text.substring(start, end), clipped)
}

/** Тот же вид оформления: ссылки с разными адресами — один вид. */
private fun SpanKind.sameAs(other: SpanKind): Boolean = this::class == other::class

/**
 * Включает или выключает оформление [kind] на участке [start]..[end].
 * Если участок уже целиком так оформлен — оформление с него снимается (у краёв оно остаётся),
 * иначе добавляется. Ссылка всегда заменяет прежние ссылки на участке.
 */
internal fun RichText.toggle(kind: SpanKind, start: Int, end: Int): RichText {
    if (start >= end) return this
    val same = spans.filter { it.kind.sameAs(kind) }
    val covered = (start until end).all { i -> same.any { i >= it.start && i < it.end } }
    val withoutRange = spans.flatMap { span ->
        if (!span.kind.sameAs(kind) || span.end <= start || span.start >= end) {
            listOf(span)
        } else {
            listOfNotNull(
                span.copy(end = start).takeIf { it.start < it.end },
                span.copy(start = end).takeIf { it.start < it.end },
            )
        }
    }
    val turnOn = !covered || kind is SpanKind.Link
    return copy(spans = if (turnOn) withoutRange + TextSpan(start, end, kind) else withoutRange)
}
