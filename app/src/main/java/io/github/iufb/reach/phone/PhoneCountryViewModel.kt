package io.github.iufb.reach.phone

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Данные о странах для [PhoneNumberInput]: список, определение по номеру, страна SIM. */
@HiltViewModel
class PhoneCountryViewModel @Inject constructor(private val repository: CountryRepository) : ViewModel() {

    val countries: StateFlow<List<Country>> = flow { emit(repository.countries()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Все известные коды стран: решаем, закончен ли ввод кода. */
    fun callingCodes(): Set<String> = countries.value.mapTo(HashSet()) { it.callingCode }

    /** Страна, которую показать для [input]. */
    fun countryFor(input: PhoneInput): Country? {
        if (input.code.isEmpty()) return null
        val picked = input.iso?.let { iso -> countries.value.firstOrNull { it.iso == iso && it.callingCode == input.code } }
        // Пока номера нет, держим выбранную страну: у «+7» TDLib сам по себе скажет «Россия»
        if (input.national.isEmpty() && picked != null) return picked
        val detected = repository.detect(input.digits)?.takeIf { it.callingCode == input.code }
        return detected ?: picked ?: countries.value.firstOrNull { it.callingCode == input.code }
    }

    /** Длина кода страны в цифрах полного номера (вставка `+77012345678` в поле номера). */
    fun codeLength(digits: String): Int? = repository.detect(digits)?.callingCode?.length

    /** Страна SIM из списка, для подстановки в пустое поле. */
    fun simCountry(): Country? {
        val iso = repository.simCountryIso() ?: return null
        return countries.value.firstOrNull { it.iso == iso }
    }
}
