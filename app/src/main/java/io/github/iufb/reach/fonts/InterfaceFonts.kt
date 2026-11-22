package io.github.iufb.reach.fonts

import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Шрифты интерфейса на процесс (#253): свой текстовый шрифт из Настройки → Шрифт или `null` — Inter.
 * Выбор применяется после перезапуска, как и [DeviceFonts].
 */
@Singleton
class InterfaceFonts @Inject constructor(fonts: DeviceFonts) {

    /** Свой текстовый шрифт; `null` — Inter (не выбран или не собрался). */
    val text: CustomTextFont? = fonts.textFile?.let { CustomTextFont.load(it, fonts.emojiFile, fonts.emojiFirst) }

    /** Жирный для надписей поверх фото (редактор фото рисует на `Canvas`, вне Compose). */
    fun bold(): Typeface = text?.typeface(OVERLAY_SIZE, BOLD) ?: Typeface.DEFAULT_BOLD

    private companion object {
        // Надписи на фото крупные — дизайн Display
        const val OVERLAY_SIZE = 28
        const val BOLD = 700
    }
}

/**
 * Свой текстовый шрифт: на каждый оптический размер — android-[Typeface] с начертаниями [WEIGHTS]
 * по оси `wght` и выбранным emoji-шрифтом в fallback (`CustomFallbackBuilder`, как в [FontStore.load]).
 * Свой Typeface нужен потому, что `Font(file)` из Compose берёт системный fallback и теряет emoji-шрифт.
 *
 * Для SF Pro ([isSfPro]) — метрики iOS из [SfProMetrics]: `opsz` по кеглю и веса Medium/Semibold как в SFNS.
 */
class CustomTextFont private constructor(
    val isSfPro: Boolean,
    private val base: Font,
    private val emoji: FontFamily?,
    private val emojiFirst: Boolean,
) {
    private val cache = ConcurrentHashMap<Int, Typeface>()

    /** Ключ кэша для кегля [size]: у SF — значение `opsz`, у прочих шрифтов кегль не важен. */
    fun opticalKey(size: Int): Int = if (isSfPro) SfProMetrics.opticalSize(size) else 0

    /** Typeface для кегля [size] с весом [weight] (для `Typeface.create`, начертание выберет Android). */
    fun typeface(size: Int, weight: Int): Typeface = Typeface.create(family(opticalKey(size)), weight, false)

    private fun family(key: Int): Typeface = cache.getOrPut(key) {
        val fonts = WEIGHTS.map { weight ->
            Font.Builder(base).setWeight(weight).setFontVariationSettings(axes(weight, key)).build()
        }
        val family = fonts.drop(1).fold(FontFamily.Builder(fonts.first())) { builder, font -> builder.addFont(font) }.build()
        // Emoji-шрифт первым, если он для этого подготовлен (DeviceFonts.emojiFirst, #254)
        val builder = when {
            emoji == null -> Typeface.CustomFallbackBuilder(family)
            emojiFirst -> Typeface.CustomFallbackBuilder(emoji).addCustomFallback(family)
            else -> Typeface.CustomFallbackBuilder(family).addCustomFallback(emoji)
        }
        builder
            .setSystemFallback("sans-serif")
            .build()
    }

    private fun axes(weight: Int, opsz: Int): String =
        if (isSfPro) "'wght' ${SfProMetrics.weightAxis(weight)}, 'opsz' $opsz" else "'wght' $weight"

    companion object {
        /** Начертания интерфейса: Regular, Medium, Semibold, Bold. */
        val WEIGHTS = listOf(400, 500, 600, 700)

        fun load(text: File, emoji: File?, emojiFirst: Boolean = false): CustomTextFont? = try {
            val info = SfntFont.inspect(text).getOrThrow()
            val emojiFamily = emoji?.let { FontFamily.Builder(Font.Builder(it).build()).build() }
            CustomTextFont(info.isSfPro, Font.Builder(text).build(), emojiFamily, emojiFirst).also {
                // Сразу собираем один размер: битый файл — сразу Inter, а не падение в первом кадре
                it.typeface(17, 400)
                Log.i(TAG, "fonts: interface text «${info.name}», SF Pro metrics: ${info.isSfPro}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "fonts: interface text font unusable, back to Inter", e)
            null
        }

        private const val TAG = "Reach"
    }
}
