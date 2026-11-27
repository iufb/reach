package io.github.iufb.reach.files

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import okio.FileSystem
import okio.Path.Companion.toPath

/** Coil-загрузчик для [TdFile]: скачивает через TDLib и отдаёт файл с диска. */
class TdFileFetcher(
    private val file: TdFile,
    private val priority: Int,
    private val files: FilesRepository,
) : Fetcher {

    override suspend fun fetch(): FetchResult = SourceFetchResult(
        source = ImageSource(files.awaitDownloaded(file.id, priority).toPath(), FileSystem.SYSTEM),
        mimeType = null,
        dataSource = DataSource.DISK,
    )

    class Factory(private val files: FilesRepository) : Fetcher.Factory<TdFile> {
        override fun create(data: TdFile, options: Options, imageLoader: ImageLoader): Fetcher =
            TdFileFetcher(data, FilesRepository.PRIORITY_AVATAR, files)
    }

    /**
     * Ключ кэша памяти — стабильный `uniqueId`, а не id сессии. У файла, который ещё выгружается,
     * `uniqueId` пустой: тогда ключ — id сессии, иначе все отправляемые фото делили бы один ключ
     * и новое показывалось бы картинкой прошлого (#435).
     */
    object Keyer : coil3.key.Keyer<TdFile> {
        override fun key(data: TdFile, options: Options): String =
            if (data.uniqueId.isEmpty()) "td-local:${data.id}" else "td:${data.uniqueId}"
    }
}
