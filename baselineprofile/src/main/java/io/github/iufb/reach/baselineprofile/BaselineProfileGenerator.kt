package io.github.iufb.reach.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Горячий путь для baseline profile: холодный старт → список чатов → прокрутка → чат → прокрутка
 * ленты → назад. Приложение должно быть уже залогинено (test DC), иначе профиль покроет только вход.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = targetPackage(), includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()

        // Список чатов — первый прокручиваемый элемент на экране
        check(device.wait(Until.hasObject(By.scrollable(true)), TIMEOUT_MS)) { "нет списка чатов: приложение не залогинено?" }
        val chats = device.findObject(By.scrollable(true))
        chats.setGestureMargin(device.displayWidth / 5)
        chats.fling(Direction.DOWN)
        device.waitForIdle()
        chats.fling(Direction.UP)
        device.waitForIdle()

        // Первый чат: строка чата — кликабельный элемент списка ниже шапки с поиском
        device.findObject(By.scrollable(true)).findObjects(By.clickable(true)).getOrNull(1)?.click()
        device.waitForIdle()
        device.wait(Until.hasObject(By.scrollable(true)), TIMEOUT_MS)
        Thread.sleep(SETTLE_MS)
        device.findObject(By.scrollable(true))?.let { feed ->
            feed.setGestureMargin(device.displayWidth / 5)
            feed.fling(Direction.UP)
            device.waitForIdle()
            feed.fling(Direction.DOWN)
            device.waitForIdle()
        }
        device.pressBack()
        device.waitForIdle()
    }

    private fun targetPackage(): String =
        InstrumentationRegistry.getArguments().getString("targetAppId") ?: DEFAULT_PACKAGE

    private companion object {
        const val DEFAULT_PACKAGE = "io.github.iufb.reach.testdc"
        const val TIMEOUT_MS = 15_000L
        const val SETTLE_MS = 1_500L
    }
}
