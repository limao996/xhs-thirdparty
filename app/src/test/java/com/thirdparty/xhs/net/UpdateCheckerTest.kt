package com.thirdparty.xhs.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 检查更新的纯逻辑测试：版本比较与 GitHub 应答解析。
 *
 * 「有更新」这条分支没法靠真机验证（除非真发一个更高的版本），所以这里用
 * GitHub 的真实应答形状来钉住解析与比较规则。
 */
class UpdateCheckerTest {

    private val releaseJson = """
        {
          "tag_name": "v1.3.0",
          "html_url": "https://github.com/limao996/xhs-thirdparty/releases/tag/v1.3.0",
          "body": "## 更新\n- 修了点东西",
          "assets": [
            {
              "name": "checksums.txt",
              "browser_download_url": "https://example.invalid/checksums.txt"
            },
            {
              "name": "小黄书-1.3.0-release.apk",
              "browser_download_url": "https://example.invalid/app-release.apk"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `remote tag newer than current is an update`() {
        val r = UpdateChecker.parse(releaseJson, "1.2.0")
        assertTrue(r is UpdateChecker.Result.Newer)
        r as UpdateChecker.Result.Newer
        assertEquals("1.3.0", r.version)
        // 只挑 .apk 资产，不能拿 checksums.txt 的地址当下载链接
        assertEquals("https://example.invalid/app-release.apk", r.apkUrl)
        assertEquals("https://github.com/limao996/xhs-thirdparty/releases/tag/v1.3.0", r.pageUrl)
        assertTrue(r.notes.contains("修了点东西"))
    }

    @Test
    fun `same version is up to date`() {
        val r = UpdateChecker.parse(releaseJson, "1.3.0")
        assertTrue(r is UpdateChecker.Result.UpToDate)
    }

    @Test
    fun `higher local version is up to date`() {
        assertTrue(UpdateChecker.parse(releaseJson, "1.4.0") is UpdateChecker.Result.UpToDate)
    }

    @Test
    fun `release without apk asset still reports the page`() {
        val noAsset = """{"tag_name":"v9.0.0","html_url":"https://example.invalid/r","assets":[]}"""
        val r = UpdateChecker.parse(noAsset, "1.2.0")
        r as UpdateChecker.Result.Newer
        assertEquals(null, r.apkUrl)
        assertEquals("https://example.invalid/r", r.pageUrl)
    }

    @Test
    fun `missing tag name fails instead of pretending to be up to date`() {
        assertTrue(UpdateChecker.parse("""{"html_url":"x"}""", "1.2.0") is UpdateChecker.Result.Failed)
    }

    @Test
    fun `numeric comparison is per segment, not lexicographic`() {
        assertTrue(UpdateChecker.isNewer("1.10.0", "1.9.2"))
        assertFalse(UpdateChecker.isNewer("1.9.2", "1.10.0"))
        assertTrue(UpdateChecker.isNewer("v2", "1.9.9"))
        assertTrue(UpdateChecker.isNewer("1.2.1-beta", "1.2.0"))
        assertFalse(UpdateChecker.isNewer("1.2.0", "1.2.0"))
        // 解析不了就当作"不更新"，不能反过来骗用户说有新版本
        assertFalse(UpdateChecker.isNewer("nightly", "1.2.0"))
        assertFalse(UpdateChecker.isNewer("1.2.0", "unknown"))
    }
}
