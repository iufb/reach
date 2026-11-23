package io.github.iufb.reach.chats

/**
 * Сколько эмодзи в тексте, если кроме них только пробелы и переводы строк; 0 — есть другой текст
 * или эмодзи нет. Кастомный эмодзи (участок [SpanKind.CustomEmoji]) — один, как бы ни был записан
 * его запасной текст. Для крупного показа эмодзи-сообщений, как в Telegram.
 */
fun RichText.emojiOnlyCount(): Int {
    val custom = spans.filter { it.kind is SpanKind.CustomEmoji }.sortedBy { it.start }
    var count = 0
    var pos = 0
    for (span in custom) {
        if (span.start < pos) return 0
        val plain = plainEmojiCount(text.substring(pos, span.start))
        if (plain < 0) return 0
        count += plain + 1
        pos = span.end
    }
    val rest = plainEmojiCount(text.substring(pos))
    return if (rest < 0) 0 else count + rest
}

/**
 * Число эмодзи-графем в [text], если кроме них только пробелы; -1 — есть другой текст.
 * Графемы разбираются по таблицам Unicode в коде, а не через `BreakIterator`: на JVM и старых
 * Android он делит ZWJ-последовательности и флаги по-разному.
 */
internal fun plainEmojiCount(text: String): Int {
    var count = 0
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        if (Character.isWhitespace(cp) || cp == NBSP) {
            i += Character.charCount(cp)
            continue
        }
        val end = emojiEnd(text, i)
        if (end < 0) return -1
        count++
        i = end
    }
    return count
}

/** Конец эмодзи-графемы, начатой в [start]; -1 — там не эмодзи. */
private fun emojiEnd(text: String, start: Int): Int {
    val first = text.codePointAt(start)
    var i = start + Character.charCount(first)
    // Флаг — пара региональных индикаторов
    if (isRegionalIndicator(first)) {
        if (i < text.length && isRegionalIndicator(text.codePointAt(i))) i += 2
        return i
    }
    // Keycap: цифра, # или *, затем необязательный VS16 и U+20E3; голая цифра — текст
    if (first in '0'.code..'9'.code || first == '#'.code || first == '*'.code) {
        if (i < text.length && text[i].code == VS16) i++
        return if (i < text.length && text[i].code == KEYCAP) i + 1 else -1
    }
    i = pictographTail(text, first, i) ?: return -1
    // ZWJ-последовательность: 👨‍👩‍👧, ❤️‍🔥
    while (i + 1 < text.length && text[i].code == ZWJ) {
        val next = text.codePointAt(i + 1)
        if (!isExtendedPictographic(next)) break
        i = pictographTail(text, next, i + 1 + Character.charCount(next), joined = true) ?: break
    }
    return i
}

/**
 * Хвост пиктограммы [cp], которая кончается перед [after]: VS16, оттенок кожи, теги флагов.
 * `null` — не эмодзи: не пиктограмма, VS15 (текстовое начертание) или символ вроде `©`, который
 * без VS16 рисуется текстом. Внутри ZWJ-последовательности ([joined]) VS16 не обязателен.
 */
private fun pictographTail(text: String, cp: Int, after: Int, joined: Boolean = false): Int? {
    if (!isExtendedPictographic(cp)) return null
    var i = after
    var emojiStyle = joined || hasEmojiPresentation(cp)
    if (i < text.length && text[i].code == VS15) return null
    if (i < text.length && text[i].code == VS16) {
        emojiStyle = true
        i++
    }
    if (i < text.length) {
        val next = text.codePointAt(i)
        if (next in SKIN_TONES) {
            emojiStyle = true
            i += 2
        }
    }
    // Флаги регионов: 🏴 + теги + конечный тег
    while (i < text.length && text.codePointAt(i) in TAGS) {
        emojiStyle = true
        i += 2
    }
    return if (emojiStyle) i else null
}

private fun isRegionalIndicator(cp: Int) = cp in 0x1F1E6..0x1F1FF

/**
 * Extended_Pictographic из emoji-data.txt (Unicode 15), без мелких дыр в незанятых блоках:
 * лишняя неназначенная точка кода здесь ничего не ломает.
 */
private fun isExtendedPictographic(cp: Int): Boolean {
    if (cp >= 0x1F000) {
        if (cp in 0x1F1E6..0x1F1FF || cp in SKIN_TONES) return false
        return PICTOGRAPHIC_SMP.any { cp in it }
    }
    return PICTOGRAPHIC_BMP.any { cp in it }
}

/**
 * Рисуется эмодзи и без VS16 (Emoji_Presentation). В дополнительных плоскостях почти все
 * пиктограммы такие; в BMP — короткий список, остальное (`©`, `❤`, `☺`) — текст без VS16.
 */
private fun hasEmojiPresentation(cp: Int): Boolean =
    if (cp >= 0x1F000) cp !in TEXT_DEFAULT_SMP else EMOJI_PRESENTATION_BMP.any { cp in it }

private const val NBSP = 0x00A0
private const val ZWJ = 0x200D
private const val VS15 = 0xFE0E
private const val VS16 = 0xFE0F
private const val KEYCAP = 0x20E3
private val SKIN_TONES = 0x1F3FB..0x1F3FF
private val TAGS = 0xE0020..0xE007F

private val PICTOGRAPHIC_BMP = listOf(
    0x00A9..0x00A9, 0x00AE..0x00AE, 0x203C..0x203C, 0x2049..0x2049, 0x2122..0x2122, 0x2139..0x2139,
    0x2194..0x2199, 0x21A9..0x21AA, 0x231A..0x231B, 0x2328..0x2328, 0x2388..0x2388, 0x23CF..0x23CF,
    0x23E9..0x23F3, 0x23F8..0x23FA, 0x24C2..0x24C2, 0x25AA..0x25AB, 0x25B6..0x25B6, 0x25C0..0x25C0,
    0x25FB..0x25FE, 0x2600..0x2605, 0x2607..0x2612, 0x2614..0x2685, 0x2690..0x2705, 0x2708..0x2712,
    0x2714..0x2714, 0x2716..0x2716, 0x271D..0x271D, 0x2721..0x2721, 0x2728..0x2728, 0x2733..0x2734,
    0x2744..0x2744, 0x2747..0x2747, 0x274C..0x274C, 0x274E..0x274E, 0x2753..0x2755, 0x2757..0x2757,
    0x2763..0x2767, 0x2795..0x2797, 0x27A1..0x27A1, 0x27B0..0x27B0, 0x27BF..0x27BF, 0x2934..0x2935,
    0x2B05..0x2B07, 0x2B1B..0x2B1C, 0x2B50..0x2B50, 0x2B55..0x2B55, 0x3030..0x3030, 0x303D..0x303D,
    0x3297..0x3297, 0x3299..0x3299,
)

private val PICTOGRAPHIC_SMP = listOf(
    0x1F000..0x1F0FF, 0x1F10D..0x1F10F, 0x1F12F..0x1F12F, 0x1F16C..0x1F171, 0x1F17E..0x1F17F,
    0x1F18E..0x1F18E, 0x1F191..0x1F19A, 0x1F1AD..0x1F1E5, 0x1F201..0x1F20F, 0x1F21A..0x1F21A,
    0x1F22F..0x1F22F, 0x1F232..0x1F23A, 0x1F23C..0x1F23F, 0x1F249..0x1F3FA, 0x1F400..0x1F53D,
    0x1F546..0x1F64F, 0x1F680..0x1F6FF, 0x1F774..0x1F77F, 0x1F7D5..0x1F7FF, 0x1F80C..0x1F80F,
    0x1F848..0x1F84F, 0x1F85A..0x1F85F, 0x1F888..0x1F88F, 0x1F8AE..0x1F8FF, 0x1F90C..0x1F93A,
    0x1F93C..0x1F945, 0x1F947..0x1FAFF, 0x1FC00..0x1FFFD,
)

/** Пиктограммы выше U+1F000 с текстовым начертанием по умолчанию: 🅰 🅱 🅾 🅿 🈂 🈷. */
private val TEXT_DEFAULT_SMP = setOf(0x1F170, 0x1F171, 0x1F17E, 0x1F17F, 0x1F202, 0x1F237)

private val EMOJI_PRESENTATION_BMP = listOf(
    0x231A..0x231B, 0x23E9..0x23EC, 0x23F0..0x23F0, 0x23F3..0x23F3, 0x25FD..0x25FE, 0x2614..0x2615,
    0x2648..0x2653, 0x267F..0x267F, 0x2693..0x2693, 0x26A1..0x26A1, 0x26AA..0x26AB, 0x26BD..0x26BE,
    0x26C4..0x26C5, 0x26CE..0x26CE, 0x26D4..0x26D4, 0x26EA..0x26EA, 0x26F2..0x26F3, 0x26F5..0x26F5,
    0x26FA..0x26FA, 0x26FD..0x26FD, 0x2705..0x2705, 0x270A..0x270B, 0x2728..0x2728, 0x274C..0x274C,
    0x274E..0x274E, 0x2753..0x2755, 0x2757..0x2757, 0x2795..0x2797, 0x27B0..0x27B0, 0x27BF..0x27BF,
    0x2B1B..0x2B1C, 0x2B50..0x2B50, 0x2B55..0x2B55,
)
