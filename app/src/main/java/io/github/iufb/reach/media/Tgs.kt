package io.github.iufb.reach.media

import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import dagger.hilt.android.EntryPointAccessors
import io.github.iufb.reach.files.FilesRepository
import io.github.iufb.reach.files.TdFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TGS — это Lottie JSON в gzip (PRODUCT.md §4.5). Скачивает через TDLib, распаковывает, разбирает
 * в [LottieComposition] и держит готовую композицию в памяти по стабильному `uniqueId`: одни и те же
 * эмодзи, реакции и стикеры панели повторяются часто, а повторный разбор — заметная работа и мигание
 * (#289, #295). Рисует `lottie-compose`; на rlottie переходим, только если упрёмся в производительность.
 */
@Singleton
class TgsLoader @Inject constructor(private val files: FilesRepository) {

    private val cache = LruCache<String, LottieComposition>(CACHE_SIZE)

    /** Уже разобранная композиция — без ожидания, чтобы ячейка не мигала заменой. */
    fun cached(file: TdFile): LottieComposition? = cache.get(file.uniqueId)

    /** Композиция анимации; `null` — не скачалось или файл битый. */
    suspend fun load(file: TdFile): LottieComposition? {
        cache.get(file.uniqueId)?.let { return it }
        val composition = runCatching {
            val path = files.awaitDownloaded(file.id, FilesRepository.PRIORITY_AVATAR)
            withContext(Dispatchers.Default) {
                val json = GZIPInputStream(File(path).inputStream().buffered()).use { it.readBytes().decodeToString() }
                // Без cacheKey: у Lottie свой кеш на 20 штук, наш — больше и по uniqueId
                LottieCompositionFactory.fromJsonStringSync(json, null).value
            }
        }.getOrNull() ?: return null
        cache.put(file.uniqueId, composition)
        return composition
    }

    private companion object {
        /** Стикеры панели (экран — 20–30 ячеек), реакции и эмодзи ленты. */
        const val CACHE_SIZE = 96
    }
}

/**
 * Готовая Lottie-композиция TGS-файла; `null` — пока грузится или не вышло (показывайте замену).
 * Если композиция уже в кеше [TgsLoader], она возвращается сразу, в первом же кадре.
 */
@Composable
internal fun rememberTgsComposition(file: TdFile?): LottieComposition? {
    val context = LocalContext.current.applicationContext
    val loader = remember(context) { EntryPointAccessors.fromApplication(context, MediaEntryPoint::class.java).tgsLoader() }
    val key = file?.uniqueId
    val cached = remember(key) { file?.let(loader::cached) }
    // Пара «файл — композиция»: после смены файла прежняя анимация не показывается ни кадра
    val loaded by produceState<Pair<String, LottieComposition>?>(null, key) {
        if (cached == null && file != null) loader.load(file)?.let { value = file.uniqueId to it }
    }
    return cached ?: loaded?.takeIf { it.first == key }?.second
}
