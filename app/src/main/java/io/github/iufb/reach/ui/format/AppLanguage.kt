package io.github.iufb.reach.ui.format

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Язык интерфейса, который выбирают в «Настройки → Язык» (#405). [System] — как в системе:
 * русский, если система на русском, иначе английский (он же запасной для всех остальных языков).
 */
enum class AppLanguage(val tag: String?) {
    System(null),
    English("en"),
    Russian("ru"),
}

/** Локаль интерфейса сейчас: дат, чисел, заглавных букв. Только русская или английская. */
val AppLocale: Locale get() = interfaceLocale(Locale.getDefault())

private val RU: Locale = Locale.forLanguageTag("ru")
private val EN: Locale = Locale.forLanguageTag("en")

private fun interfaceLocale(locale: Locale): Locale = if (locale.language == RU.language) RU else EN

/**
 * Где хранится выбранный язык. С Android 13 — «Язык приложения» в системе ([LocaleManager]): система
 * сама применяет его к конфигурации и к `Locale.getDefault()` и пересоздаёт активити. На Android 12
 * его нет — язык лежит в своих настройках, конфигурацию активити подменяет
 * [withInterfaceLanguage], а приложения — [applyAtStartup].
 */
object AppLanguages {

    fun current(context: Context): AppLanguage {
        val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales?.takeIf { !it.isEmpty }?.get(0)?.language
        } else {
            prefs(context).getString(KEY, null)
        }
        return AppLanguage.entries.firstOrNull { it.tag != null && it.tag == tag } ?: AppLanguage.System
    }

    /** Выбрать язык; активити пересоздаётся на нём. */
    fun set(activity: Activity, language: AppLanguage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = language.tag?.let(LocaleList::forLanguageTags) ?: LocaleList.getEmptyLocaleList()
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales = locales
            return
        }
        prefs(activity).edit().apply { if (language.tag == null) remove(KEY) else putString(KEY, language.tag) }.apply()
        applyAtStartup(activity.applicationContext)
        activity.recreate()
    }

    /**
     * Android 12: язык процесса и ресурсы приложения (уведомления, ошибки из ViewModel) — на выбранном
     * языке. С Android 13 это делает система.
     */
    fun applyAtStartup(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        val locale = legacyLocale(context)
        Locale.setDefault(locale)
        val resources = context.resources
        val config = Configuration(resources.configuration).apply { setLocales(LocaleList(locale)) }
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    /** Android 12: выбранный язык или язык системы. */
    internal fun legacyLocale(context: Context): Locale =
        prefs(context).getString(KEY, null)?.let(Locale::forLanguageTag)
            ?: Resources.getSystem().configuration.locales[0]

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "app_language"
    private const val KEY = "language"
}

/**
 * Android 12 (до LocaleManager): контекст активити на выбранном языке — и строки, и системные
 * компоненты (пикеры дат и времени, формат чисел). С Android 13 язык назначает система.
 */
fun Context.withInterfaceLanguage(): Context {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return this
    val config = Configuration(resources.configuration).apply { setLocales(LocaleList(AppLanguages.legacyLocale(this@withInterfaceLanguage))) }
    return createConfigurationContext(config)
}
