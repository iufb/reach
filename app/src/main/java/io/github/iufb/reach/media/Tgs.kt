package io.github.iufb.reach.media

import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.rememberLottieComposition
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
 * TGS — это Lottie JSON в gzip (PRODUCT.md §4.5). Скачивает через TDLib, распаковывает и держит
 * готовый JSON в памяти по стабильному `uniqueId`: одни и те же эмодзи и реакции повторяются часто.
 * Рисует `lottie-compose`; на rlottie переходим, только если упрёмся в производительность.
 */
@Singleton
class TgsLoader @Inject constructor(private val files: FilesRepository) {

    private val cache = LruCache<String, String>(CACHE_SIZE)

    /** Lottie JSON анимации; `null` — не скачалось или файл битый. */
    suspend fun load(file: TdFile): String? {
        cache.get(file.uniqueId)?.let { return it }
        val json = runCatching {
            val path = files.awaitDownloaded(file.id, FilesRepository.PRIORITY_AVATAR)
            withContext(Dispatchers.IO) {
                GZIPInputStream(File(path).inputStream().buffered()).use { it.readBytes().decodeToString() }
            }
        }.getOrNull() ?: return null
        cache.put(file.uniqueId, json)
        return json
    }

    private companion object {
        const val CACHE_SIZE = 64
    }
}

/** Готовая Lottie-композиция TGS-файла; `null` — пока грузится или не вышло (показывайте замену). */
@Composable
internal fun rememberTgsComposition(file: TdFile?): LottieComposition? {
    val context = LocalContext.current.applicationContext
    val loader = remember(context) { EntryPointAccessors.fromApplication(context, MediaEntryPoint::class.java).tgsLoader() }
    val json by produceState<String?>(null, file) { value = file?.let { loader.load(it) } }
    val ready = json ?: return null
    val composition by rememberLottieComposition(LottieCompositionSpec.JsonString(ready), cacheKey = "tgs:${file?.uniqueId}")
    return composition
}
