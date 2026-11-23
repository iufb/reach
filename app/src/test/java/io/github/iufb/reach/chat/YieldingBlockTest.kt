package io.github.iufb.reach.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** Панель → клавиатура: высота нижнего блока (а с ней и место поля ввода) по кадрам (#314). */
class YieldingBlockTest {

    private val nav = 130

    @Test fun holdsPanelHeightUntilKeyboardStarts() {
        // Клавиатура ещё не тронулась: ни цели, ни сдвига
        assertEquals(831, yieldingBlockPx(fromPx = 831, imePx = 0, sourcePx = 0, targetPx = 0, navPx = nav))
        // Цель уже известна, сдвига ещё нет
        assertEquals(831, yieldingBlockPx(fromPx = 831, imePx = 0, sourcePx = 0, targetPx = 831, navPx = nav))
    }

    @Test fun sameHeightNeverMoves() {
        for (ime in listOf(0, 36, 193, 565, 811, 831)) {
            assertEquals(831, yieldingBlockPx(fromPx = 831, imePx = ime, sourcePx = 0, targetPx = 831, navPx = nav))
        }
    }

    @Test fun differentHeightFollowsKeyboardProgress() {
        // Панель по умолчанию выше клавиатуры: блок сходится к высоте клавиатуры по ходу её анимации
        assertEquals(935, yieldingBlockPx(fromPx = 935, imePx = 0, sourcePx = 0, targetPx = 831, navPx = nav))
        assertEquals(883, yieldingBlockPx(fromPx = 935, imePx = 415, sourcePx = 0, targetPx = 831, navPx = nav))
        assertEquals(831, yieldingBlockPx(fromPx = 935, imePx = 831, sourcePx = 0, targetPx = 831, navPx = nav))
    }

    @Test fun keyboardAlreadyInPlace() {
        // Поиск эмодзи: клавиатура уже стоит, уходит только строка результатов
        assertEquals(831, yieldingBlockPx(fromPx = 1139, imePx = 831, sourcePx = 831, targetPx = 831, navPx = nav))
    }

    @Test fun neverBelowInsets() {
        assertEquals(nav, yieldingBlockPx(fromPx = 0, imePx = 0, sourcePx = 0, targetPx = 0, navPx = nav))
        assertEquals(900, yieldingBlockPx(fromPx = 500, imePx = 900, sourcePx = 0, targetPx = 900, navPx = nav))
    }
}
