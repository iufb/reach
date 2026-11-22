package io.github.iufb.reach.ui.theme

import android.content.Context
import android.graphics.fonts.SystemFonts
import android.util.Log
import androidx.compose.material3.Typography
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import io.github.iufb.reach.R
import io.github.iufb.reach.fonts.DeviceFonts
import java.io.File
import android.graphics.Typeface as PlatformTypeface
import android.graphics.fonts.Font as PlatformFont
import android.graphics.fonts.FontFamily as PlatformFontFamily
import android.graphics.fonts.FontStyle as PlatformFontStyle

/** Шрифт для кода (`code`, `pre`); со своим emoji-шрифтом — моноширинный с ним в fallback. */
val LocalMonospace = staticCompositionLocalOf<FontFamily> { FontFamily.Monospace }

/**
 * Compose-семейства со своим emoji-шрифтом (#254). Compose `Font(resId)` и `Font(file)` дают Typeface
 * с системным fallback, и эмодзи брались бы из системного шрифта. Поэтому каждое начертание
 * собирается через `Typeface.CustomFallbackBuilder` (emoji-шрифт и основной) и отдаётся
 * Compose как [AndroidFont]. Без своего emoji-шрифта не нужно: Noto ставит EmojiCompat.
 */
class DeviceFontFamilies(context: Context, emojiFile: File, private val emojiFirst: Boolean) {
    private val emoji by lazy { PlatformFontFamily.Builder(PlatformFont.Builder(emojiFile).build()).build() }

    /** Inter по весам через ось `wght` (свой текстовый шрифт несёт emoji-шрифт сам, CustomTextFont). */
    val text: FontFamily = FontFamily(
        WEIGHTS.map { weight ->
            TypefaceFont(FontWeight(weight)) {
                withEmoji(
                    PlatformFont.Builder(context.resources, R.font.inter_variable)
                        .setFontVariationSettings("'wght' $weight").setWeight(weight).build(),
                )
            }
        },
    )

    /** Системный моноширинный шрифт; если его файла не нашлось — обычный моноширинный без своих эмодзи. */
    val monospace: FontFamily = FontFamily(
        TypefaceFont(FontWeight.Normal) { systemMonospace()?.let(::withEmoji) ?: PlatformTypeface.MONOSPACE },
    )

    // Emoji-шрифт первым, если он для этого подготовлен (DeviceFonts.emojiFirst, emojiGoesFirst)
    private fun withEmoji(main: PlatformFont): PlatformTypeface {
        val text = PlatformFontFamily.Builder(main).build()
        val builder = if (emojiFirst) {
            PlatformTypeface.CustomFallbackBuilder(emoji).addCustomFallback(text)
        } else {
            PlatformTypeface.CustomFallbackBuilder(text).addCustomFallback(emoji)
        }
        return builder
            .setStyle(PlatformFontStyle(main.style.weight, PlatformFontStyle.FONT_SLANT_UPRIGHT))
            .setSystemFallback("sans-serif")
            .build()
    }

    private fun systemMonospace(): PlatformFont? = try {
        val fonts = SystemFonts.getAvailableFonts().filter { it.file != null && it.axes.isNullOrEmpty() }
        fonts.firstOrNull { it.file?.name == MONOSPACE_FILE } ?: fonts.firstOrNull { it.file?.name?.contains("Mono") == true }
    } catch (e: Exception) {
        Log.w("Reach", "fonts: no system monospace", e)
        null
    }

    /** Начертание, которое Compose загружает через [load] (лениво, при первом использовании веса). */
    private class TypefaceFont(override val weight: FontWeight, load: () -> PlatformTypeface) :
        AndroidFont(FontLoadingStrategy.Blocking, Loader, FontVariation.Settings()) {
        override val style: FontStyle = FontStyle.Normal
        val typeface by lazy(load)

        override fun toString() = "TypefaceFont(weight=$weight)"
    }

    private object Loader : AndroidFont.TypefaceLoader {
        override fun loadBlocking(context: Context, font: AndroidFont) = (font as TypefaceFont).typeface
        override suspend fun awaitLoad(context: Context, font: AndroidFont) = (font as TypefaceFont).typeface
    }

    companion object {
        private val WEIGHTS = (100..900 step 100).toList()
        private const val MONOSPACE_FILE = "DroidSansMono.ttf"

        /** `null` — свой emoji-шрифт не выбран. */
        fun of(context: Context, fonts: DeviceFonts?): DeviceFontFamilies? =
            fonts?.emojiFile?.let { DeviceFontFamilies(context.applicationContext, it, fonts.emojiFirst) }
    }
}

/** Та же шкала, но с другим семейством шрифтов во всех стилях. */
fun Typography.withFontFamily(family: FontFamily): Typography {
    fun TextStyle.f() = copy(fontFamily = family)
    return copy(
        displayLarge = displayLarge.f(), displayMedium = displayMedium.f(), displaySmall = displaySmall.f(),
        headlineLarge = headlineLarge.f(), headlineMedium = headlineMedium.f(), headlineSmall = headlineSmall.f(),
        titleLarge = titleLarge.f(), titleMedium = titleMedium.f(), titleSmall = titleSmall.f(),
        bodyLarge = bodyLarge.f(), bodyMedium = bodyMedium.f(), bodySmall = bodySmall.f(),
        labelLarge = labelLarge.f(), labelMedium = labelMedium.f(), labelSmall = labelSmall.f(),
    )
}
