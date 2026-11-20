package io.github.iufb.reach.media

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import io.github.iufb.reach.core.td.TdClient
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile

/**
 * Источник данных Media3 поверх файла TDLib — стриминг без ожидания полной загрузки.
 *
 * TDLib качает файл с нужного смещения (`downloadFile` с `offset`) прямо в локальный файл;
 * сколько байт подряд уже есть с текущей позиции, говорит `getFileDownloadedPrefixSize`.
 * Читаем с диска то, что есть, а если впереди пусто — переводим загрузку на текущую позицию
 * и ждём `UpdateFile`. Перемотка — это просто новый [open] с другой позицией.
 *
 * Плеер вызывает [open] / [read] в своём потоке загрузки, поэтому блокирующие вызовы здесь допустимы.
 * Uri — [uriFor]: `tdfile:///<file id>`.
 */
@OptIn(UnstableApi::class)
class TdFileDataSource(private val td: TdClient) : BaseDataSource(/* isNetwork = */ true) {

    private var uri: Uri? = null
    private var fileId = 0
    private var position = 0L
    /** [C.LENGTH_UNSET] — размер файла ещё неизвестен. */
    private var bytesRemaining = 0L
    /** Смещение, с которого мы последний раз просили TDLib качать. */
    private var requestedOffset = -1L
    private var reader: RandomAccessFile? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        fileId = dataSpec.uri.lastPathSegment?.toIntOrNull()
            ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        transferInitializing(dataSpec)
        position = dataSpec.position
        val file = call(TdApi.GetFile(fileId))
        val size = file.size
        if (size > 0 && position > size) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            size > 0 -> size - position
            else -> C.LENGTH_UNSET.toLong()
        }
        requestedOffset = -1
        if (!file.local.isDownloadingCompleted) requestDownload()
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val available = awaitAvailable()
        if (available == 0L) return C.RESULT_END_OF_INPUT
        var toRead = minOf(length.toLong(), available)
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) toRead = minOf(toRead, bytesRemaining)
        val read = readAt(buffer, offset, toRead.toInt())
        if (read <= 0) return C.RESULT_END_OF_INPUT
        position += read
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            reader?.close()
        } finally {
            reader = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    /** Сколько байт подряд уже на диске с [position]; ждёт, пока появится хоть что-то. 0 — конец файла. */
    private fun awaitAvailable(): Long {
        while (true) {
            if (Thread.interrupted()) throw InterruptedIOException()
            val prefix = call(TdApi.GetFileDownloadedPrefixSize(fileId, position)).size
            if (prefix > 0) return prefix
            val file = call(TdApi.GetFile(fileId))
            if (file.local.isDownloadingCompleted) return 0
            if (requestedOffset != position || !file.local.isDownloadingActive) requestDownload()
            // Апдейт мог прийти между проверкой и подпиской — поэтому ждём с таймаутом и проверяем снова
            runBlocking {
                withTimeoutOrNull(WAIT_MS) {
                    td.updates.filterIsInstance<TdApi.UpdateFile>().first { it.file.id == fileId }
                }
            }
        }
    }

    /** Переводит загрузку на текущую позицию: TDLib качает файл одним куском с этого смещения. */
    private fun requestDownload() {
        call(TdApi.DownloadFile(fileId, PRIORITY_STREAM, position, 0, false))
        requestedOffset = position
    }

    private fun readAt(buffer: ByteArray, offset: Int, length: Int): Int {
        // Путь может смениться (по окончании загрузки TDLib переносит файл) — тогда открываем заново
        repeat(2) { attempt ->
            val file = reader ?: openReader()
            try {
                file.seek(position)
                return file.read(buffer, offset, length)
            } catch (e: IOException) {
                reader?.close()
                reader = null
                if (attempt == 1) throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
            }
        }
        return C.RESULT_END_OF_INPUT
    }

    private fun openReader(): RandomAccessFile {
        val path = call(TdApi.GetFile(fileId)).local.path
        if (path.isEmpty()) throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        return RandomAccessFile(path, "r").also { reader = it }
    }

    private fun <T : TdApi.Object> call(function: TdApi.Function<T>): T = try {
        runBlocking { td.send(function) }
    } catch (e: IOException) {
        throw e
    } catch (e: InterruptedException) {
        throw InterruptedIOException()
    } catch (e: Exception) {
        throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
    }

    class Factory(private val td: TdClient) : DataSource.Factory {
        override fun createDataSource(): DataSource = TdFileDataSource(td)
    }

    companion object {
        /** Видео, которое смотрят прямо сейчас, — выше всех остальных загрузок. */
        const val PRIORITY_STREAM = 32
        private const val WAIT_MS = 500L

        fun uriFor(fileId: Int): Uri = Uri.parse("tdfile:///$fileId")
    }
}
