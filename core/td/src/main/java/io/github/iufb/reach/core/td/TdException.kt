package io.github.iufb.reach.core.td

import org.drinkless.tdlib.TdApi

/** Ошибка, которую вернула TDLib в ответ на запрос (`TdApi.Error`). */
class TdException(val code: Int, override val message: String) : Exception("TDLib error $code: $message") {
    constructor(error: TdApi.Error) : this(error.code, error.message)

    /**
     * Сколько секунд ждать перед повтором, если это flood wait.
     * TDLib присылает его как код 429 с текстом `Too Many Requests: retry after N`.
     */
    val retryAfterSeconds: Int?
        get() = if (code == 429) RETRY_AFTER.find(message)?.groupValues?.get(1)?.toIntOrNull() else null

    private companion object {
        val RETRY_AFTER = Regex("""retry after (\d+)""")
    }
}
