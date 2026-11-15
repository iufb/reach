package io.github.iufb.reach.core.td

import org.drinkless.tdlib.TdApi

/** Ошибка, которую вернула TDLib в ответ на запрос (`TdApi.Error`). */
class TdException(val code: Int, override val message: String) : Exception("TDLib error $code: $message") {
    constructor(error: TdApi.Error) : this(error.code, error.message)
}
