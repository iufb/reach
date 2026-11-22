package io.github.iufb.reach.fonts

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.text.TextRunShaper
import java.io.File

/**
 * Какие эмодзи свой emoji-шрифт рисует сам и одним знаком (#254). Порядок пикера взят у Noto,
 * и эмодзи новее выбранного шрифта иначе показались бы системным шрифтом или распались бы на части.
 * [typeface] — шрифт с emoji-шрифтом [emojiFile] в fallback ([DeviceFonts.text]).
 */
class EmojiCoverage(typeface: Typeface, emojiFile: File) {
    private val path = emojiFile.absolutePath
    private val paint = Paint().apply {
        this.typeface = typeface
        textSize = SIZE
    }

    @Synchronized
    fun covers(emoji: String): Boolean {
        val glyphs = TextRunShaper.shapeTextRun(emoji, 0, emoji.length, 0, emoji.length, 0f, 0f, false, paint)
        if (glyphs.glyphCount() == 0) return false
        // Каждый знак — из emoji-шрифта, а не из системного fallback
        for (i in 0 until glyphs.glyphCount()) {
            if (glyphs.getFont(i).file?.absolutePath != path) return false
        }
        // ZWJ-последовательность без лигатуры рисуется несколькими эмодзи подряд
        return paint.measureText(emoji) < SIZE * MAX_WIDTH
    }

    private companion object {
        const val SIZE = 100f
        const val MAX_WIDTH = 1.5f
    }
}
