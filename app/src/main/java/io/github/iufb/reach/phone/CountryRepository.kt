package io.github.iufb.reach.phone

import android.content.Context
import android.telephony.TelephonyManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.iufb.reach.core.td.TdClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.TdApi
import java.text.Collator
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Страна для поля телефона: ISO-код, название на языке интерфейса, код страны без «+». */
data class Country(val iso: String, val name: String, val callingCode: String) {
    /** Флаг эмодзи из ISO-кода: `KZ` → 🇰🇿. */
    val flag: String
        get() = if (iso.length == 2 && iso.all { it in 'A'..'Z' }) {
            String(Character.toChars(REGIONAL_A + (iso[0] - 'A'))) + String(Character.toChars(REGIONAL_A + (iso[1] - 'A')))
        } else {
            ""
        }

    private companion object {
        const val REGIONAL_A = 0x1F1E6
    }
}

/**
 * Страны для поля телефона (#330): список из TDLib `getCountries`, определение страны по номеру через
 * `getPhoneNumberInfoSync` (отличает KZ от RU при общем коде `+7`), страна SIM для подстановки.
 */
@Singleton
class CountryRepository @Inject constructor(
    private val td: TdClient,
    @ApplicationContext private val context: Context,
) {
    private val mutex = Mutex()
    private var cache: List<Country>? = null

    /** Все страны и их коды, по алфавиту; у страны с несколькими кодами — строка на каждый код. */
    suspend fun countries(): List<Country> = mutex.withLock {
        cache ?: runCatching { td.send(TdApi.GetCountries()).countries }
            .getOrDefault(emptyArray())
            .filterNot { it.isHidden }
            .flatMap { info -> info.callingCodes.distinct().map { Country(info.countryCode, localizedName(info), it) } }
            .sortedWith(compareBy(Collator.getInstance(Locale.getDefault())) { it.name })
            .also { if (it.isNotEmpty()) cache = it }
    }

    /** Страна и код страны по цифрам номера (с кодом); `null` — код не распознан. */
    fun detect(digits: String): Country? {
        if (digits.isEmpty()) return null
        val info = td.execute(TdApi.GetPhoneNumberInfoSync(Locale.getDefault().language, digits)) ?: return null
        val country = info.country ?: return null
        if (info.countryCallingCode.isEmpty()) return null
        return Country(country.countryCode, localizedName(country), info.countryCallingCode)
    }

    /** ISO-код страны SIM (или сети), иначе страна системной локали. */
    fun simCountryIso(): String? {
        val tm = context.getSystemService(TelephonyManager::class.java)
        return listOfNotNull(tm?.simCountryIso, tm?.networkCountryIso, Locale.getDefault().country)
            .map { it.uppercase(Locale.ROOT) }
            .firstOrNull { it.length == 2 }
    }

    /** Название на языке интерфейса; если Android его не знает — из TDLib. */
    private fun localizedName(info: TdApi.CountryInfo): String {
        val local = Locale("", info.countryCode).getDisplayCountry(Locale.getDefault())
        return if (local.isBlank() || local.equals(info.countryCode, ignoreCase = true)) info.name else local
    }
}
