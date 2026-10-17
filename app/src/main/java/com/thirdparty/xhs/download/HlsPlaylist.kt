package com.thirdparty.xhs.download

/**
 * HLS 播放列表解析（够用即可：本项目的视频源统一是 `#EXTM3U` + `#EXT-X-KEY` + `localhost.tN.ts`）。
 *
 * 纯逻辑、不碰 android 类，所以可以放进 JVM 单元测试（`app/src/test`）。
 *
 * 支持：
 * - 相对/绝对切片地址（相对地址按播放列表 URL 解析）；
 * - `#EXT-X-KEY:METHOD=AES-128,URI=…,IV=0x…`（URI 同样支持相对与绝对）；
 * - 没有 `IV` 时按 RFC 8216 用**媒体序号**当 IV（本项目实际都有 IV，这里只是兜底）；
 * - 识别 `#EXT-X-MAP`（初始化段）与 `#EXT-X-BYTERANGE`：本项目源里没有，遇到就明确报错而不是悄悄下错。
 */
object HlsPlaylist {

    data class Segment(val uri: String, val sequence: Long, val byteRange: Pair<Long, Long>?)

    data class Playlist(
        val segments: List<Segment>,
        val keyUri: String?,
        val keyIv: ByteArray?,
        val initSegmentUri: String?
    ) {
        val encrypted: Boolean get() = keyUri != null
    }

    class UnsupportedPlaylistException(message: String) : Exception(message)

    /** [text] 是播放列表内容，[playlistUrl] 用于把相对地址补成绝对地址。 */
    fun parse(text: String, playlistUrl: String): Playlist {
        val base = playlistUrl.substringBeforeLast('/', "")
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

        var sequence = 0L
        var nextSequence = 0L
        var keyUri: String? = null
        var keyIv: ByteArray? = null
        var initUri: String? = null
        val segments = mutableListOf<Segment>()
        var pendingRange: Pair<Long, Long>? = null

        for (line in lines) {
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                    sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                    nextSequence = sequence
                }

                line.startsWith("#EXT-X-KEY:") -> {
                    val attrs = parseAttributes(line.substringAfter(':'))
                    keyUri = attrs["URI"]?.let { absolute(it, base) }
                    keyIv = attrs["IV"]?.removePrefix("0x")?.removePrefix("0X")?.let { hexToBytes(it) }
                }

                line.startsWith("#EXT-X-MAP:") -> {
                    val attrs = parseAttributes(line.substringAfter(':'))
                    initUri = attrs["URI"]?.let { absolute(it, base) }
                }

                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    val spec = line.substringAfter(':').trim()
                    val parts = spec.split('@')
                    val length = parts[0].toLongOrNull()
                    val offset = parts.getOrNull(1)?.toLongOrNull()
                    if (length != null && offset != null) pendingRange = offset to length
                }

                line.startsWith("#") -> Unit

                else -> {
                    segments.add(
                        Segment(
                            uri = absolute(line, base),
                            sequence = nextSequence++,
                            byteRange = pendingRange
                        )
                    )
                    pendingRange = null
                }
            }
        }

        if (segments.isEmpty()) throw UnsupportedPlaylistException("播放列表里没有切片")
        if (initUri != null) throw UnsupportedPlaylistException("源带了 EXT-X-MAP 初始化段，暂不支持")
        if (segments.any { it.byteRange != null }) {
            throw UnsupportedPlaylistException("源带了 EXT-X-BYTERANGE 分段，暂不支持")
        }
        return Playlist(segments, keyUri, keyIv, null)
    }

    private fun parseAttributes(s: String): Map<String, String> =
        s.split(',').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim().trim('"')
        }.toMap()

    private fun absolute(uri: String, base: String): String =
        if (uri.startsWith("http://") || uri.startsWith("https://")) uri
        else if (uri.startsWith("/")) base.substringBefore("://").let { scheme ->
            val host = base.substringAfter("://").substringBefore('/')
            "$scheme://$host$uri"
        } else "$base/$uri"

    /** 没有 IV 时按 RFC 8216 用媒体序号当 IV（16 字节大端）。 */
    fun ivForSequence(sequence: Long): ByteArray {
        val iv = ByteArray(16)
        var value = sequence
        for (i in 15 downTo 0) {
            iv[i] = (value and 0xFF).toByte()
            value = value shr 8
        }
        return iv
    }

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
}
