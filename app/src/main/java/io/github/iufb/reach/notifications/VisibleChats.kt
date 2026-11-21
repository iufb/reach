package io.github.iufb.reach.notifications

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Чаты, открытые на экране прямо сейчас (экран чата в состоянии resumed). Их сообщения пользователь
 * и так видит, поэтому [Notifier] о них не уведомляет, как Telegram (#235). Свернули приложение или ушли
 * из чата — экран на паузе, уведомления снова приходят.
 *
 * Счётчик, а не флаг: один чат может быть открыт дважды (весь чат и тред в нём).
 */
@Singleton
class VisibleChats @Inject constructor() {
    private val counts = mutableMapOf<Long, Int>()

    @Synchronized
    fun shown(chatId: Long) {
        counts[chatId] = (counts[chatId] ?: 0) + 1
    }

    @Synchronized
    fun hidden(chatId: Long) {
        val left = (counts[chatId] ?: 0) - 1
        if (left > 0) counts[chatId] = left else counts.remove(chatId)
    }

    @Synchronized
    fun isVisible(chatId: Long): Boolean = chatId in counts
}
