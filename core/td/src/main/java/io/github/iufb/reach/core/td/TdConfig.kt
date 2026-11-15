package io.github.iufb.reach.core.td

/** Параметры запуска TDLib. Предоставляется модулем `app` через Hilt. */
data class TdConfig(
    val apiId: Int,
    val apiHash: String,
    val databaseDirectory: String,
    val filesDirectory: String,
    val applicationVersion: String,
    /** Писать все апдейты в Logcat (только debug-сборки). */
    val logUpdates: Boolean,
    val useTestDc: Boolean = false,
) {
    val hasCredentials: Boolean get() = apiId != 0 && apiHash.isNotEmpty()

    // api_hash не должен попадать в логи
    override fun toString() = "TdConfig(apiId=$apiId, db=$databaseDirectory, testDc=$useTestDc)"
}
