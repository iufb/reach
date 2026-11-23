package io.github.iufb.reach.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingTimeTest {

    @Test fun hundredthsLikeTelegramIos() {
        assertEquals("0:01,74", formatRecordingTime(1_745))
        assertEquals("0:00,00", formatRecordingTime(0))
        assertEquals("0:00,09", formatRecordingTime(99))
    }

    @Test fun minutesRollOver() {
        assertEquals("1:01,00", formatRecordingTime(61_005))
        assertEquals("10:00,50", formatRecordingTime(600_500))
    }

    @Test fun negativeIsZero() {
        assertEquals("0:00,00", formatRecordingTime(-30))
    }
}
