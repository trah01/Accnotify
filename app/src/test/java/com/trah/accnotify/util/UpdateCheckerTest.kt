package com.trah.accnotify.util

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class UpdateCheckerTest {
    private fun release(tag: String, asset: String, prerelease: Boolean = false, link: String = "https://github.com/trah01/Accnotify/releases/tag/$tag") =
        """{"tag_name":"$tag","draft":false,"prerelease":$prerelease,"html_url":"$link","assets":[{"name":"$asset"}]}"""
    @Test fun ignoresDesktopAndWrongArchitectureAndComparesNumericVersions() {
        val items = listOf(release("v9.0.0", "Accnotify_9.0.0_aarch64.dmg"), release("android-v0.10.0", "Accnotify-universal.apk"), release("v0.9.0", "Accnotify-arm64.apk"), release("v8.0.0", "Accnotify-x86_64.apk"))
        val result = UpdateChecker.select(JsonParser.parseString(items.joinToString(",", "[", "]")).asJsonArray, listOf("arm64-v8a"))
        assertEquals("0.10.0", result.version)
        assertTrue(result.newerThan("0.9.0"))
        assertFalse(result.newerThan("0.10.0"))
    }
    @Test fun ignoresPrereleasesAndUnexpectedDownloadPages() {
        val items = listOf(release("v1.0.0", "Accnotify.apk", prerelease = true), release("v2.0.0-beta.1", "Accnotify.apk"), release("v3.0.0", "Accnotify.apk", link = "https://example.com/download"))
        val result = UpdateChecker.select(JsonParser.parseString(items.joinToString(",", "[", "]")).asJsonArray, listOf("arm64-v8a"))
        assertNull(result.version)
        assertEquals(UpdateChecker.RELEASES_URL, result.url)
    }
}
