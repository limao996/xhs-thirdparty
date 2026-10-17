package com.thirdparty.xhs.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放列表解析的单测：把本项目真实源的形状（相对切片名 + `#EXT-X-KEY` 相对 URI + IV）钉住，
 * 同时覆盖"没有 IV 时用媒体序号"与"遇到不支持的特性要明确报错"。
 */
class HlsPlaylistTest {

    private val realPlaylist = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:10
        #EXT-X-MEDIA-SEQUENCE:0
        #EXT-X-PLAYLIST-TYPE:VOD
        #EXT-X-KEY:METHOD=AES-128,URI="/static/m3u8/enc.key",IV=0x425dc5396bf13533f48428d2bdb27978
        #EXTINF:10.000000,
        localhost.t0.ts
        #EXTINF:10.000000,
        localhost.t1.ts
        #EXTINF:10.000000,
        localhost.t2.ts
        #EXT-X-ENDLIST
    """.trimIndent()

    private val url = "https://example.com/static/m3u8/20201209/afqbZZ/p.m3u8"

    @Test
    fun parses_real_shape_with_relative_segments_and_key() {
        val p = HlsPlaylist.parse(realPlaylist, url)

        assertEquals(3, p.segments.size)
        assertEquals(
            "https://example.com/static/m3u8/20201209/afqbZZ/localhost.t0.ts",
            p.segments[0].uri
        )
        assertEquals(
            "https://example.com/static/m3u8/20201209/afqbZZ/localhost.t2.ts",
            p.segments[2].uri
        )
        assertTrue("应当识别为加密流", p.encrypted)
        assertEquals("https://example.com/static/m3u8/enc.key", p.keyUri)
        assertEquals(
            "425dc5396bf13533f48428d2bdb27978",
            p.keyIv!!.joinToString("") { "%02x".format(it) }
        )
        assertEquals(listOf(0L, 1L, 2L), p.segments.map { it.sequence })
    }

    @Test
    fun absolute_uris_pass_through_and_unencrypted_has_no_key() {
        val text = """
            #EXTM3U
            #EXTINF:5.0,
            https://cdn.example.com/a/0.ts
            #EXTINF:5.0,
            https://cdn.example.com/a/1.ts
        """.trimIndent()
        val p = HlsPlaylist.parse(text, url)
        assertEquals("https://cdn.example.com/a/0.ts", p.segments[0].uri)
        assertTrue(!p.encrypted)
        assertEquals(null, p.keyUri)
    }

    @Test
    fun media_sequence_shifts_iv_fallback() {
        val text = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:7
            #EXTINF:5.0,
            0.ts
        """.trimIndent()
        val p = HlsPlaylist.parse(text, "https://e.com/x/p.m3u8")
        assertEquals(7L, p.segments[0].sequence)
        // 没有 IV 时用媒体序号当 IV（RFC 8216）
        val iv = HlsPlaylist.ivForSequence(p.segments[0].sequence)
        assertEquals("00000000000000000000000000000007", iv.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun rejects_unsupported_map_and_byterange() {
        val map = """
            #EXTM3U
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:5.0,
            0.m4s
        """.trimIndent()
        assertTrue(
            runCatching { HlsPlaylist.parse(map, url) }.exceptionOrNull()
                is HlsPlaylist.UnsupportedPlaylistException
        )

        val range = """
            #EXTM3U
            #EXT-X-BYTERANGE:1000@0
            #EXTINF:5.0,
            all.ts
        """.trimIndent()
        assertTrue(
            runCatching { HlsPlaylist.parse(range, url) }.exceptionOrNull()
                is HlsPlaylist.UnsupportedPlaylistException
        )
    }

    @Test
    fun rejects_playlist_without_segments() {
        val text = "#EXTM3U\n#EXT-X-VERSION:3"
        assertTrue(
            runCatching { HlsPlaylist.parse(text, url) }.exceptionOrNull()
                is HlsPlaylist.UnsupportedPlaylistException
        )
    }
}
