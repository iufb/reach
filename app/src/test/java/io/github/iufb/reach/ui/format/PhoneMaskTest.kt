package io.github.iufb.reach.ui.format

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneMaskTest {

    @Test fun formatsKnownCountries() {
        assertEquals("+7 701 234 56 78", PhoneMask.format("77012345678"))
        assertEquals("+1 415 555 0123", PhoneMask.format("14155550123"))
        assertEquals("+44 7911 123456", PhoneMask.format("447911123456"))
        assertEquals("+375 29 123 45 67", PhoneMask.format("375291234567"))
        assertEquals("+998 90 123 45 67", PhoneMask.format("998901234567"))
        assertEquals("+99966 1 2345", PhoneMask.format("999661 2345".filter(Char::isDigit)))
    }

    @Test fun formatsWhileTyping() {
        assertEquals("", PhoneMask.format(""))
        assertEquals("+7", PhoneMask.format("7"))
        assertEquals("+7 70", PhoneMask.format("770"))
        assertEquals("+7 701 2", PhoneMask.format("77012"))
        assertEquals("+37", PhoneMask.format("37"))
        assertEquals("+375 2", PhoneMask.format("3752"))
    }

    @Test fun unknownCountryGroupsByThree() {
        assertEquals("+359 881 234 56", PhoneMask.format("35988123456"))
        assertEquals("+43 664 123 456", PhoneMask.format("43664123456"))
    }

    @Test fun extraDigitsBeyondPatternStayVisible() {
        assertEquals("+7 701 234 56 78 9", PhoneMask.format("770123456789"))
    }

    @Test fun digitsDropsSeparatorsAndCapsLength() {
        assertEquals("77012345678", PhoneMask.digits("+7 (701) 234-56-78"))
        assertEquals(PhoneMask.MAX_DIGITS, PhoneMask.digits("1".repeat(20)).length)
    }

    @Test fun offsetMappingSkipsMaskCharacters() {
        val transformed = PhoneVisualTransformation.filter(AnnotatedString("77012"))
        assertEquals("+7 701 2", transformed.text.text)
        val mapping = transformed.offsetMapping
        assertEquals(1, mapping.originalToTransformed(0))
        assertEquals(3, mapping.originalToTransformed(1))
        assertEquals(8, mapping.originalToTransformed(5))
        assertEquals(0, mapping.transformedToOriginal(0))
        assertEquals(1, mapping.transformedToOriginal(3))
        assertEquals(5, mapping.transformedToOriginal(8))
    }
}
