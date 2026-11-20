package io.github.iufb.reach.media.webm

/**
 * Видеодорожка WebM, целиком в памяти: видеостикеры и кастомные эмодзи — это ≤ 256 КБ и ≤ 3 с.
 * [frames] в порядке декодирования; [hasAlpha] — у каждого кадра есть альфа-кадр (ADR-0008).
 */
class WebmVideo(
    val mime: String,
    val width: Int,
    val height: Int,
    val frames: List<WebmFrame>,
) {
    val hasAlpha: Boolean = frames.isNotEmpty() && frames.all { it.alpha != null }
}

/**
 * Кадр: [data] — кадр VP8/VP9, [alpha] — отдельный кадр того же кодека, чья яркость и есть
 * прозрачность (Matroska `BlockAdditional` с `BlockAddID` = 1, так пишут libvpx и ffmpeg).
 */
class WebmFrame(val ptsUs: Long, val isKey: Boolean, val data: ByteArray, val alpha: ByteArray?)

/**
 * Минимальный разборщик WebM (EBML/Matroska): только первая видеодорожка VP8/VP9, только то,
 * что нужно для проигрывания по кругу. Cues, теги, аудио и прочее пропускаются.
 * Segment и Cluster бывают неизвестного размера (запись потоком), поэтому их не пропускают,
 * а читают «насквозь».
 */
object WebmParser {

    /** `null` — не WebM или нет видеодорожки VP8/VP9. */
    fun parse(bytes: ByteArray): WebmVideo? = runCatching { Reader(bytes).run() }.getOrNull()

    private class Track(val number: Long, val mime: String, val width: Int, val height: Int)

    private class Reader(private val b: ByteArray) {
        private var timecodeScale = 1_000_000L
        private var clusterTimecode = 0L
        private var track: Track? = null
        private val frames = ArrayList<WebmFrame>()

        fun run(): WebmVideo? {
            var pos = 0
            while (pos < b.size) {
                val id = readId(pos) ?: break
                pos += id.second
                val size = readSize(pos) ?: break
                pos += size.second
                val payload = size.first
                when (id.first) {
                    // Контейнеры верхнего уровня: заходим внутрь, их размер может быть неизвестен
                    SEGMENT, CLUSTER -> Unit
                    else -> {
                        val end = if (payload < 0) b.size else (pos + payload).toInt().coerceAtMost(b.size)
                        when (id.first) {
                            TIMECODE_SCALE -> timecodeScale = uint(pos, end)
                            INFO -> parseInfo(pos, end)
                            TRACKS -> parseTracks(pos, end)
                            TIMECODE -> clusterTimecode = uint(pos, end)
                            SIMPLE_BLOCK -> block(pos, end, alpha = null, key = null)
                            BLOCK_GROUP -> parseBlockGroup(pos, end)
                        }
                        pos = end
                    }
                }
            }
            val t = track ?: return null
            if (frames.isEmpty()) return null
            return WebmVideo(t.mime, t.width, t.height, frames)
        }

        private fun parseInfo(start: Int, end: Int) = children(start, end) { id, s, e ->
            if (id == TIMECODE_SCALE) timecodeScale = uint(s, e)
        }

        private fun parseTracks(start: Int, end: Int) = children(start, end) { id, s, e ->
            if (id == TRACK_ENTRY && track == null) parseTrackEntry(s, e)
        }

        private fun parseTrackEntry(start: Int, end: Int) {
            var number = 0L
            var type = 0L
            var codec = ""
            var width = 0
            var height = 0
            children(start, end) { id, s, e ->
                when (id) {
                    TRACK_NUMBER -> number = uint(s, e)
                    TRACK_TYPE -> type = uint(s, e)
                    CODEC_ID -> codec = String(b, s, e - s, Charsets.US_ASCII).trimEnd('\u0000')
                    VIDEO -> children(s, e) { vid, vs, ve ->
                        when (vid) {
                            PIXEL_WIDTH -> width = uint(vs, ve).toInt()
                            PIXEL_HEIGHT -> height = uint(vs, ve).toInt()
                        }
                    }
                }
            }
            val mime = when (codec) {
                "V_VP9" -> "video/x-vnd.on2.vp9"
                "V_VP8" -> "video/x-vnd.on2.vp8"
                else -> return
            }
            if (type == TRACK_TYPE_VIDEO && width > 0 && height > 0) track = Track(number, mime, width, height)
        }

        private fun parseBlockGroup(start: Int, end: Int) {
            var blockStart = -1
            var blockEnd = -1
            var alpha: ByteArray? = null
            var references = false
            children(start, end) { id, s, e ->
                when (id) {
                    REFERENCE_BLOCK -> references = true
                    BLOCK -> {
                        blockStart = s
                        blockEnd = e
                    }
                    BLOCK_ADDITIONS -> children(s, e) { mid, ms, me ->
                        if (mid == BLOCK_MORE) {
                            var addId = 1L
                            var data: ByteArray? = null
                            children(ms, me) { aid, as_, ae ->
                                when (aid) {
                                    BLOCK_ADD_ID -> addId = uint(as_, ae)
                                    BLOCK_ADDITIONAL -> data = b.copyOfRange(as_, ae)
                                }
                            }
                            if (addId == 1L && data != null) alpha = data
                        }
                    }
                }
            }
            if (blockStart >= 0) block(blockStart, blockEnd, alpha, key = !references)
        }

        /**
         * Block / SimpleBlock: номер дорожки, 16-битное смещение времени, флаги, кадр (без лейсинга).
         * [key] — для Block: нет ссылок на другие кадры (`ReferenceBlock`); у SimpleBlock — флаг.
         */
        private fun block(start: Int, end: Int, alpha: ByteArray?, key: Boolean?) {
            val t = track ?: return
            val trackNo = readSize(start) ?: return
            if (trackNo.first != t.number) return
            var pos = start + trackNo.second
            if (pos + 3 > end) return
            val relative = ((b[pos].toInt() shl 8) or (b[pos + 1].toInt() and 0xff)).toShort().toLong()
            val flags = b[pos + 2].toInt() and 0xff
            pos += 3
            if (flags and LACING_MASK != 0) return // видео не пакуют лейсингом; такие блоки не поддерживаем
            val ptsUs = (clusterTimecode + relative) * timecodeScale / 1000
            val isKey = key ?: (flags and KEYFRAME_FLAG != 0)
            frames += WebmFrame(ptsUs, isKey, b.copyOfRange(pos, end), alpha)
        }

        /** Обход дочерних элементов известного размера. */
        private inline fun children(start: Int, end: Int, onChild: (id: Long, start: Int, end: Int) -> Unit) {
            var pos = start
            while (pos < end) {
                val id = readId(pos) ?: return
                pos += id.second
                val size = readSize(pos) ?: return
                pos += size.second
                val childEnd = if (size.first < 0) end else (pos + size.first).toInt().coerceAtMost(end)
                onChild(id.first, pos, childEnd)
                pos = childEnd
            }
        }

        /** ID элемента вместе с битом-маркером длины и его длина в байтах. */
        private fun readId(pos: Int): Pair<Long, Int>? {
            val len = vintLength(pos) ?: return null
            if (len > 4 || pos + len > b.size) return null
            var v = 0L
            for (i in 0 until len) v = (v shl 8) or (b[pos + i].toLong() and 0xff)
            return v to len
        }

        /** Размер (без маркера) и длина в байтах; -1 — «неизвестный размер» (все биты единицы). */
        private fun readSize(pos: Int): Pair<Long, Int>? {
            val len = vintLength(pos) ?: return null
            if (pos + len > b.size) return null
            var v = (b[pos].toLong() and 0xff) and (0xffL shr len)
            var allOnes = v == (0xffL shr len)
            for (i in 1 until len) {
                val byte = b[pos + i].toLong() and 0xff
                if (byte != 0xffL) allOnes = false
                v = (v shl 8) or byte
            }
            return (if (allOnes) -1L else v) to len
        }

        private fun vintLength(pos: Int): Int? {
            if (pos >= b.size) return null
            val first = b[pos].toInt() and 0xff
            if (first == 0) return null
            return Integer.numberOfLeadingZeros(first) - 23
        }

        private fun uint(start: Int, end: Int): Long {
            var v = 0L
            for (i in start until end) v = (v shl 8) or (b[i].toLong() and 0xff)
            return v
        }
    }

    private const val SEGMENT = 0x18538067L
    private const val INFO = 0x1549A966L
    private const val TIMECODE_SCALE = 0x2AD7B1L
    private const val TRACKS = 0x1654AE6BL
    private const val TRACK_ENTRY = 0xAEL
    private const val TRACK_NUMBER = 0xD7L
    private const val TRACK_TYPE = 0x83L
    private const val CODEC_ID = 0x86L
    private const val VIDEO = 0xE0L
    private const val PIXEL_WIDTH = 0xB0L
    private const val PIXEL_HEIGHT = 0xBAL
    private const val CLUSTER = 0x1F43B675L
    private const val TIMECODE = 0xE7L
    private const val SIMPLE_BLOCK = 0xA3L
    private const val BLOCK_GROUP = 0xA0L
    private const val BLOCK = 0xA1L
    private const val BLOCK_ADDITIONS = 0x75A1L
    private const val BLOCK_MORE = 0xA6L
    private const val BLOCK_ADD_ID = 0xEEL
    private const val BLOCK_ADDITIONAL = 0xA5L
    private const val REFERENCE_BLOCK = 0xFBL

    private const val TRACK_TYPE_VIDEO = 1L
    private const val KEYFRAME_FLAG = 0x80
    private const val LACING_MASK = 0x06
}
