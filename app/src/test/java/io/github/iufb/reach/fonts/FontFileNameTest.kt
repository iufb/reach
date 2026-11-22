package io.github.iufb.reach.fonts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FontFileNameTest {

    @Test fun ttfAndOtfAnyCase() {
        listOf("Inter.ttf", "SF-Pro.TTF", "Font.otf", "a.OtF").forEach { assertTrue(it, isFontFileName(it)) }
    }

    @Test fun otherFilesAreNotFonts() {
        listOf("fonts.ttc", "report.pdf", "ttf", "font.ttf.zip", "").forEach { assertFalse(it, isFontFileName(it)) }
    }
}
