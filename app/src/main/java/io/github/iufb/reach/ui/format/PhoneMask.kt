package io.github.iufb.reach.ui.format

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Маска номера телефона при вводе (#328): `+7 701 234 56 78`, `+1 415 555 0123`, `+44 7911 123456`.
 * В поле хранятся только цифры ([digits]), «+» и пробелы дорисовывает [PhoneVisualTransformation].
 * Группы — по коду страны из [PATTERNS]; для неизвестного кода — тройками.
 */
object PhoneMask {

    /** E.164: не больше 15 цифр вместе с кодом страны. */
    const val MAX_DIGITS = 15

    /** Только цифры ввода (вставка `+7 (701) 234-56-78` тоже работает), не длиннее [MAX_DIGITS]. */
    fun digits(input: String): String = input.filter(Char::isDigit).take(MAX_DIGITS)

    /** `77012345678` → `+7 701 234 56 78`; пустая строка остаётся пустой (виден плейсхолдер). */
    fun format(digits: String): String = if (digits.isEmpty()) "" else "+" + groups(digits).joinToString(" ")

    /** Национальная часть номера по маске страны [code]: `7`, `7012345678` → `701 234 56 78`. */
    fun formatNational(code: String, national: String): String =
        if (national.isEmpty()) "" else groups(code + national).drop(1).joinToString(" ")

    /** Шаблон национальной части для плейсхолдера: `7` → `000 000 00 00`; `null` — маски для кода нет. */
    fun nationalPlaceholder(code: String): String? =
        PATTERNS[code]?.joinToString(" ") { "0".repeat(it) }

    /** Код страны и группы национальной части. */
    internal fun groups(digits: String): List<String> {
        val code = PATTERNS.keys.filter(digits::startsWith).maxByOrNull { it.length }
        val codeLength = code?.length ?: ituCodeLength(digits)
        if (digits.length <= codeLength) return listOf(digits)
        val national = digits.substring(codeLength)
        val sizes = code?.let(PATTERNS::getValue) ?: emptyList()
        val result = mutableListOf(digits.take(codeLength))
        var i = 0
        for (size in sizes) {
            if (i >= national.length) break
            result += national.substring(i, minOf(i + size, national.length))
            i += size
        }
        // Сверх маски (или неизвестная страна) — тройками
        while (i < national.length) {
            result += national.substring(i, minOf(i + 3, national.length))
            i += 3
        }
        return result
    }

    /** Длина кода страны по ITU: «1» и «7» однозначные, перечисленные — двузначные, остальные — трёхзначные. */
    private fun ituCodeLength(digits: String): Int = when {
        digits.startsWith("1") || digits.startsWith("7") -> 1
        digits.take(2) in TWO_DIGIT_CODES -> 2
        else -> 3
    }

    private val TWO_DIGIT_CODES = setOf(
        "20", "27", "30", "31", "32", "33", "34", "36", "39", "40", "41", "43", "44", "45", "46", "47", "48", "49",
        "51", "52", "53", "54", "55", "56", "57", "58", "60", "61", "62", "63", "64", "65", "66",
        "81", "82", "84", "86", "90", "91", "92", "93", "94", "95", "98",
    )

    /** Код страны → размеры групп национальной части (как у Telegram). */
    private val PATTERNS: Map<String, List<Int>> = mapOf(
        "1" to listOf(3, 3, 4),
        "7" to listOf(3, 3, 2, 2),
        "20" to listOf(2, 4, 4),
        "27" to listOf(2, 3, 4),
        "30" to listOf(3, 3, 4),
        "31" to listOf(1, 2, 2, 2, 2),
        "32" to listOf(3, 2, 2, 2),
        "33" to listOf(1, 2, 2, 2, 2),
        "34" to listOf(3, 3, 3),
        "39" to listOf(3, 3, 4),
        "40" to listOf(3, 3, 3),
        "41" to listOf(2, 3, 2, 2),
        "44" to listOf(4, 6),
        "45" to listOf(4, 4),
        "46" to listOf(2, 3, 2, 2),
        "47" to listOf(4, 4),
        "48" to listOf(3, 3, 3),
        "49" to listOf(4, 7),
        "52" to listOf(2, 4, 4),
        "55" to listOf(2, 5, 4),
        "61" to listOf(3, 3, 3),
        "62" to listOf(3, 4, 4),
        "63" to listOf(3, 3, 4),
        "66" to listOf(1, 4, 4),
        "81" to listOf(2, 4, 4),
        "82" to listOf(2, 4, 4),
        "86" to listOf(3, 4, 4),
        "90" to listOf(3, 3, 4),
        "91" to listOf(5, 5),
        "92" to listOf(3, 7),
        "98" to listOf(3, 3, 4),
        "370" to listOf(3, 5),
        "371" to listOf(3, 5),
        "373" to listOf(2, 3, 3),
        "374" to listOf(2, 3, 3),
        "375" to listOf(2, 3, 2, 2),
        "380" to listOf(2, 3, 2, 2),
        "966" to listOf(2, 3, 4),
        "971" to listOf(2, 3, 4),
        "972" to listOf(2, 3, 4),
        "992" to listOf(2, 3, 4),
        "993" to listOf(2, 2, 2, 2),
        "994" to listOf(2, 3, 2, 2),
        "995" to listOf(3, 3, 3),
        "996" to listOf(3, 3, 3),
        "998" to listOf(2, 3, 2, 2),
        // Тестовые номера Telegram: +99966 X YYYY
        "99966" to listOf(1, 4),
    )
}

/** Показ цифр поля по [PhoneMask]; курсор и выделение переносятся между цифрами и маской. */
object PhoneVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val formatted = PhoneMask.format(raw)
        // Позиция i-й цифры в маске; последний элемент — конец строки
        val digitPositions = IntArray(raw.length + 1)
        var d = 0
        formatted.forEachIndexed { pos, c -> if (c.isDigit() && d < raw.length) digitPositions[d++] = pos }
        digitPositions[raw.length] = formatted.length
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = digitPositions[offset.coerceIn(0, raw.length)]
            override fun transformedToOriginal(offset: Int): Int =
                formatted.take(offset.coerceIn(0, formatted.length)).count(Char::isDigit)
        }
        return TransformedText(AnnotatedString(formatted), mapping)
    }
}

/** Показ национальной части номера по маске страны [code] (поле номера рядом с полем кода). */
class NationalPhoneTransformation(private val code: String) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val formatted = PhoneMask.formatNational(code, raw)
        val digitPositions = IntArray(raw.length + 1)
        var d = 0
        formatted.forEachIndexed { pos, c -> if (c.isDigit() && d < raw.length) digitPositions[d++] = pos }
        digitPositions[raw.length] = formatted.length
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = digitPositions[offset.coerceIn(0, raw.length)]
            override fun transformedToOriginal(offset: Int): Int =
                formatted.take(offset.coerceIn(0, formatted.length)).count(Char::isDigit)
        }
        return TransformedText(AnnotatedString(formatted), mapping)
    }

    override fun equals(other: Any?): Boolean = other is NationalPhoneTransformation && other.code == code
    override fun hashCode(): Int = code.hashCode()
}
