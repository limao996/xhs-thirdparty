package com.thirdparty.xhs.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 检查更新的纯逻辑测试：版本比较、API 应答解析、**atom feed 解析**（现在的主路径）。
 *
 * 「有更新」这条分支没法靠真机稳定复现（除非真发一个更高的版本），所以这里用
 * GitHub 的**真实应答形状**把解析规则钉住。
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
              "browser_download_url": "https://github.com/limao996/xhs-thirdparty/releases/download/v1.3.0/checksums.txt"
            },
            {
              "name": "小黄书-1.3.0-release.apk",
              "browser_download_url": "https://github.com/limao996/xhs-thirdparty/releases/download/v1.3.0/app-release.apk"
            }
          ]
        }
    """.trimIndent()

    /** 真实 atom feed 的形状（节选自本仓库 releases.atom）。 */
    private val atomFeed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom" xml:lang="en-US">
          <title>Release notes from xhs-thirdparty</title>
          <entry>
            <id>tag:github.com,2008:Repository/1404180347/v1.3.0</id>
            <link rel="alternate" type="text/html" href="https://github.com/limao996/xhs-thirdparty/releases/tag/v1.3.0"/>
            <title>小黄书 v1.3.0</title>
            <content type="html">&lt;h2&gt;小黄书 v1.3.0&lt;/h2&gt;
        &lt;p&gt;自 v1.2.1 以来的修复与打磨&amp;quot;重点是小窗&amp;quot;。&lt;/p&gt;</content>
          </entry>
        </feed>
    """.trimIndent()

    @Test
    fun `remote tag newer than current is an update`() {
        val r = UpdateChecker.parse(releaseJson, "1.2.0")
        assertTrue(r is UpdateChecker.Result.Newer)
        r as UpdateChecker.Result.Newer
        assertEquals("1.3.0", r.version)
        // 只挑 .apk 资产，不能拿 checksums.txt 的地址当下载链接。
        // fixture 必须用**可信域名**（GitHub）：`isTrustedDownloadUrl` 会把别的域名过滤掉。
        assertEquals(
            "https://github.com/limao996/xhs-thirdparty/releases/download/v1.3.0/app-release.apk",
            r.apkUrl
        )
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
    fun `release without apk asset falls back to the asset naming convention`() {
        // 资产名是本仓库的固定约定（xhs-thirdparty-<version>-release.apk），所以 API 应答里
        // 即使没列 assets，也能拼出下载地址（仍然要过可信域名检查）。
        val noAsset = """{"tag_name":"v9.0.0","html_url":"https://example.invalid/r","assets":[]}"""
        val r = UpdateChecker.parse(noAsset, "1.2.0")
        r as UpdateChecker.Result.Newer
        assertEquals(
            "https://github.com/limao996/xhs-thirdparty/releases/download/v9.0.0/" +
                "xhs-thirdparty-9.0.0-release.apk",
            r.apkUrl
        )
        assertEquals("https://example.invalid/r", r.pageUrl)
    }

    @Test
    fun `missing tag name fails instead of pretending to be up to date`() {
        assertTrue(UpdateChecker.parse("""{"html_url":"x"}""", "1.2.0") is UpdateChecker.Result.Failed)
    }

    // ---- atom feed：现在的主路径（不占 API 额度）---------------------------------

    @Test
    fun `atom entry newer than current is an update`() {
        val r = UpdateChecker.parseAtom(atomFeed, "1.2.0")
        assertTrue(r is UpdateChecker.Result.Newer)
        r as UpdateChecker.Result.Newer
        assertEquals("1.3.0", r.version)
        assertEquals("https://github.com/limao996/xhs-thirdparty/releases/tag/v1.3.0", r.pageUrl)
        // 说明是从 content 的 HTML 里剥出来的纯文本（转义字符也要还原）
        assertTrue(r.notes.contains("小黄书 v1.3.0"))
        assertTrue(r.notes.contains("重点是小窗"))
        assertFalse(r.notes.contains("<h2>"))
        // 下载地址按命名约定拼出来
        assertEquals(
            "https://github.com/limao996/xhs-thirdparty/releases/download/v1.3.0/" +
                "xhs-thirdparty-1.3.0-release.apk",
            r.apkUrl
        )
    }

    @Test
    fun `atom entry same version is up to date`() {
        assertTrue(UpdateChecker.parseAtom(atomFeed, "1.3.0") is UpdateChecker.Result.UpToDate)
    }

    @Test
    fun `atom feed without entries reports no release`() {
        val empty = """<?xml version="1.0"?><feed xmlns="http://www.w3.org/2005/Atom"></feed>"""
        assertTrue(UpdateChecker.parseAtom(empty, "1.2.0") is UpdateChecker.Result.NoRelease)
    }

    @Test
    fun `html to text strips tags and unescapes entities`() {
        // 单行输入：标签去掉、实体还原、多余空白压成一个空格
        assertEquals(
            "标题 正文 结束",
            UpdateChecker.htmlToText("&lt;h2&gt;标题&lt;/h2&gt; &lt;p&gt;正文&lt;/p&gt; 结束")
        )
        // 段落仍在（换行保留，但连续空行压到最多两个）
        val multi = UpdateChecker.htmlToText("&lt;p&gt;第一段&lt;/p&gt;\n\n\n\n&lt;p&gt;第二段&lt;/p&gt;")
        assertTrue(multi.contains("第一段"))
        assertTrue(multi.contains("第二段"))
        assertFalse(multi.contains("\n\n\n"))
        // 实体
        assertTrue(UpdateChecker.htmlToText("a &amp;amp; b").contains("&"))
    }

    @Test
    fun `asset url follows the naming convention and stays on github`() {
        val url = UpdateChecker.assetUrl("v1.3.0")
        assertNotNull(url)
        assertEquals(
            "https://github.com/limao996/xhs-thirdparty/releases/download/v1.3.0/" +
                "xhs-thirdparty-1.3.0-release.apk",
            url
        )
        // 非 GitHub 地址一律不接受（应答是外部数据）
        assertNull(UpdateChecker.assetUrl("v1.3.0", repoUrl = "https://evil.example.com/repo"))
        assertTrue(UpdateChecker.isTrustedDownloadUrl(url!!))
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
