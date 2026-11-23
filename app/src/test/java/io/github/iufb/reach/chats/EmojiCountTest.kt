package io.github.iufb.reach.chats

import org.junit.Assert.assertEquals
import org.junit.Test

class EmojiCountTest {

    private fun count(text: String, spans: List<TextSpan> = emptyList()) = RichText(text, spans).emojiOnlyCount()

    @Test fun oneToFourEmoji() {
        assertEquals(1, count("😀"))
        assertEquals(2, count("😀😂"))
        assertEquals(3, count("😀😂🔥"))
        assertEquals(4, count("😀😂🔥👍"))
    }

    @Test fun zwjSequenceIsOne() {
        assertEquals(1, count("👨‍👩‍👧"))
        assertEquals(1, count("❤️‍🔥"))
        assertEquals(1, count("🏳️‍🌈"))
        assertEquals(1, count("🧑🏽‍💻"))
        assertEquals(2, count("👨‍👩‍👧👍"))
    }

    @Test fun flagIsOne() {
        assertEquals(1, count("🇰🇿"))
        assertEquals(2, count("🇰🇿🇺🇸"))
        // Флаг региона на тегах: Англия
        assertEquals(1, count("🏴󠁧󠁢󠁥󠁮󠁧󠁿"))
    }

    @Test fun skinToneIsOne() {
        assertEquals(1, count("👍🏽"))
        assertEquals(3, count("❤️👍🏽🇰🇿"))
    }

    @Test fun keycapIsOne() {
        assertEquals(1, count("1️⃣"))
        assertEquals(1, count("#⃣"))
        assertEquals(2, count("1️⃣2️⃣"))
    }

    @Test fun textStyleSymbolsAreText() {
        assertEquals(0, count("©"))
        assertEquals(0, count("1"))
        assertEquals(0, count("#"))
        assertEquals(0, count("❤"))
        assertEquals(1, count("©️"))
        assertEquals(1, count("❤️"))
        // VS15 — явное текстовое начертание
        assertEquals(0, count("😀︎"))
    }

    @Test fun emojiWithTextIsZero() {
        assertEquals(0, count("😀 hi"))
        assertEquals(0, count("ok👍"))
        assertEquals(0, count("😀."))
        assertEquals(0, count(""))
        assertEquals(0, count("   "))
        assertEquals(0, count("‍"))
    }

    @Test fun whitespaceAndNewlinesAreIgnored() {
        assertEquals(2, count(" 😀 \n😂 "))
        assertEquals(3, count("😀\n\n😂\t🔥"))
    }

    @Test fun customEmojiCountsAsOne() {
        val custom = "😎"
        assertEquals(1, count(custom, listOf(TextSpan(0, custom.length, SpanKind.CustomEmoji(1)))))
        // Кастомный + обычные
        val text = "😀$custom🔥"
        val span = TextSpan(2, 2 + custom.length, SpanKind.CustomEmoji(2))
        assertEquals(3, count(text, listOf(span)))
    }

    @Test fun customEmojiWithTextIsZero() {
        val text = "hi 😎"
        assertEquals(0, count(text, listOf(TextSpan(3, 5, SpanKind.CustomEmoji(1)))))
    }

    @Test fun customEmojiFallbackTextIsNotCounted() {
        // Запасной текст кастомного эмодзи может быть любым — считается один эмодзи
        val text = "ab"
        assertEquals(1, count(text, listOf(TextSpan(0, 2, SpanKind.CustomEmoji(1)))))
    }
}
