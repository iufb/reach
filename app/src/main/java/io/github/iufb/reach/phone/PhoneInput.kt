package io.github.iufb.reach.phone

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import io.github.iufb.reach.ui.format.PhoneMask

/**
 * Значение поля телефона (#330): код страны и национальная часть отдельно, только цифры.
 * [iso] — страна, выбранная вручную или по SIM: при общем коде (`+7`: RU и KZ) показываем её,
 * пока номер не говорит о другой.
 */
data class PhoneInput(val code: String = "", val national: String = "", val iso: String? = null) {
    /** Все цифры с кодом страны, для `+`-номера в TDLib. */
    val digits: String get() = code + national

    companion object {
        /** Самый длинный код страны (`+1 684` и т.п. TDLib отдаёт кодом из 4 цифр). */
        const val MAX_CODE = 4

        val Saver: Saver<PhoneInput, Any> = listSaver(
            save = { listOf(it.code, it.national, it.iso.orEmpty()) },
            restore = { PhoneInput(it[0], it[1], it[2].ifEmpty { null }) },
        )

        /** Номер из цифр с кодом: длина кода — из [codeLength] (TDLib), иначе по ITU из [PhoneMask]. */
        fun fromDigits(digits: String, codeLength: Int? = null): PhoneInput {
            val clean = PhoneMask.digits(digits)
            val length = (codeLength ?: PhoneMask.groups(clean).first().length).coerceIn(0, minOf(MAX_CODE, clean.length))
            return PhoneInput(clean.take(length), clean.drop(length))
        }
    }
}
