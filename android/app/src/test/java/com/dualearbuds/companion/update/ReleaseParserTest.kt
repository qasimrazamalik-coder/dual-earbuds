package com.dualearbuds.companion.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ReleaseParserTest {

    private val sample = """
        {
          "url": "https://api.github.com/repos/qasimrazamalik-coder/dual-earbuds/releases/1",
          "html_url": "https://github.com/qasimrazamalik-coder/dual-earbuds/releases/tag/v3.1.0",
          "id": 123456789,
          "tag_name": "v3.1.0",
          "name": "Dual Earbuds 3.1.0",
          "draft": false,
          "prerelease": false,
          "body": "## What's new\r\n- Faster start\r\n- Fix: \"Party\" chip ✓\r\n",
          "author": {"login": "qasimrazamalik-coder", "id": 1},
          "assets": [
            {
              "name": "DualEarbuds-Setup.exe",
              "size": 51234567,
              "browser_download_url": "https://github.com/qasimrazamalik-coder/dual-earbuds/releases/download/v3.1.0/DualEarbuds-Setup.exe"
            },
            {
              "name": "DualEarbuds.apk",
              "size": 4567890,
              "content_type": "application/vnd.android.package-archive",
              "download_count": 0,
              "browser_download_url": "https://github.com/qasimrazamalik-coder/dual-earbuds/releases/download/v3.1.0/DualEarbuds.apk"
            }
          ]
        }
    """.trimIndent()

    private fun ok(json: String): ReleaseInfo = when (val r = ReleaseParser.parse(json)) {
        is ReleaseParse.Ok -> r.release
        is ReleaseParse.Invalid -> {
            fail("Expected Ok, got ${r.reason}")
            throw AssertionError()
        }
    }

    @Test
    fun parsesGithubLatestRelease() {
        val r = ok(sample)
        assertEquals(SemVer(3, 1, 0), r.version)
        assertEquals("v3.1.0", r.tag)
        assertEquals("Dual Earbuds 3.1.0", r.title)
        assertEquals("## What's new\n- Faster start\n- Fix: \"Party\" chip ✓", r.notes)
        assertEquals("https://github.com/qasimrazamalik-coder/dual-earbuds/releases/tag/v3.1.0", r.pageUrl)
        assertEquals(
            "https://github.com/qasimrazamalik-coder/dual-earbuds/releases/download/v3.1.0/DualEarbuds.apk",
            r.apk!!.url
        )
        assertEquals(4_567_890L, r.apk!!.size)
    }

    @Test
    fun missingApkAssetGivesNullApk() {
        val json = """{"tag_name":"v3.0.1","name":null,"body":null,"assets":[{"name":"DualEarbuds-Setup.exe","browser_download_url":"https://github.com/x/y/z.exe"}]}"""
        val r = ok(json)
        assertNull(r.apk)
        assertEquals("v3.0.1", r.title)
        assertEquals("", r.notes)
    }

    @Test
    fun assetNameMustMatchExactly() {
        val json = """{"tag_name":"v3.0.1","assets":[{"name":"dualearbuds.apk","browser_download_url":"https://github.com/a/b/releases/download/v3.0.1/dualearbuds.apk"}]}"""
        assertNull(ok(json).apk)
    }

    @Test
    fun apkOnForeignHostIsIgnored() {
        val json = """{"tag_name":"v3.0.1","assets":[{"name":"DualEarbuds.apk","browser_download_url":"https://evil.example.com/DualEarbuds.apk"}]}"""
        assertNull(ok(json).apk)
    }

    @Test
    fun unknownSizeIsMinusOne() {
        val json = """{"tag_name":"v3.0.1","assets":[{"name":"DualEarbuds.apk","browser_download_url":"https://github.com/a/b/releases/download/v3.0.1/DualEarbuds.apk"}]}"""
        assertEquals(-1L, ok(json).apk!!.size)
    }

    @Test
    fun invalidInputs() {
        assertTrue(ReleaseParser.parse("") is ReleaseParse.Invalid)
        assertTrue(ReleaseParser.parse("<html>rate limited</html>") is ReleaseParse.Invalid)
        assertTrue(ReleaseParser.parse("[]") is ReleaseParse.Invalid)
        assertTrue(ReleaseParser.parse("""{"message":"Not Found"}""") is ReleaseParse.Invalid)
        assertTrue(ReleaseParser.parse("""{"tag_name":"nightly"}""") is ReleaseParse.Invalid)
        assertTrue(ReleaseParser.parse("""{"tag_name":"v3.0.1","draft":true}""") is ReleaseParse.Invalid)
        assertTrue(ReleaseParser.parse("""{"tag_name":"v3.0.1",}""") is ReleaseParse.Invalid)
    }

    @Test
    fun downloadUrlPolicy() {
        assertTrue(UpdatePolicy.isAllowedDownloadUrl("https://github.com/a/b/releases/download/v1/DualEarbuds.apk"))
        assertTrue(UpdatePolicy.isAllowedDownloadUrl("https://objects.githubusercontent.com/github-production-release-asset/1?x=y"))
        assertTrue(UpdatePolicy.isAllowedDownloadUrl("https://GitHub.com:443/a"))
        assertFalse(UpdatePolicy.isAllowedDownloadUrl("http://github.com/a"))
        assertFalse(UpdatePolicy.isAllowedDownloadUrl("https://github.com.evil.com/a"))
        assertFalse(UpdatePolicy.isAllowedDownloadUrl("https://evil.com/github.com/a"))
        assertFalse(UpdatePolicy.isAllowedDownloadUrl("https://github.com@evil.com/a"))
        assertFalse(UpdatePolicy.isAllowedDownloadUrl("https://user@github.com/a"))
        assertFalse(UpdatePolicy.isAllowedDownloadUrl("https://github.com:8443/a"))
        assertFalse(UpdatePolicy.isAllowedDownloadUrl("https://api.github.com/a"))
        assertFalse(UpdatePolicy.isAllowedDownloadUrl(""))
        assertFalse(UpdatePolicy.isAllowedDownloadUrl(null))
        assertFalse(UpdatePolicy.isAllowedDownloadUrl("not a url"))
    }

    @Test
    fun autoCheckAtMostOncePerDay() {
        val day = UpdatePolicy.AUTO_CHECK_INTERVAL_MS
        assertTrue(UpdatePolicy.isAutoCheckDue(nowMs = 1_000, lastCheckMs = 0, enabled = true))
        assertFalse(UpdatePolicy.isAutoCheckDue(nowMs = 1_000 + day - 1, lastCheckMs = 1_000, enabled = true))
        assertTrue(UpdatePolicy.isAutoCheckDue(nowMs = 1_000 + day, lastCheckMs = 1_000, enabled = true))
        assertFalse(UpdatePolicy.isAutoCheckDue(nowMs = 1_000 + day, lastCheckMs = 1_000, enabled = false))
        assertTrue(UpdatePolicy.isAutoCheckDue(nowMs = 500, lastCheckMs = 1_000, enabled = true))
    }

    @Test
    fun miniJsonValues() {
        @Suppress("UNCHECKED_CAST")
        val o = MiniJson.parse("""{"a":[1,-2.5,1e3,true,false,null,"x\\y\/z\n"],"b":{}}""") as Map<String, Any?>
        assertEquals(listOf(1L, -2.5, 1000.0, true, false, null, "x\\y/z\n"), o["a"])
        assertEquals(emptyMap<String, Any?>(), o["b"])
    }
}
