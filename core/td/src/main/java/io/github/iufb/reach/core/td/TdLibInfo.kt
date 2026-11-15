package io.github.iufb.reach.core.td

import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

object TdLibInfo {
    /** Версия TDLib. `getOption("version")` можно вызывать синхронно; загружает libtdjni. */
    fun version(): String =
        (Client.execute(TdApi.GetOption("version")) as TdApi.OptionValueString).value
}
