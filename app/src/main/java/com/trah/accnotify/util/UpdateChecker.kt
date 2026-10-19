package com.trah.accnotify.util

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

data class AppUpdate(val version: String?, val url: String) {
    fun newerThan(current: String): Boolean = version != null && UpdateChecker.compare(version, current) > 0
}

object UpdateChecker {
    const val RELEASES_URL = "https://github.com/trah01/Accnotify/releases"
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).followRedirects(false).build()
    private val versionPattern = Regex("^(?:android-)?v?(\\d+)\\.(\\d+)\\.(\\d+)(?:\\+[0-9A-Za-z.-]+)?$")

    private fun parts(version: String): List<Long>? = versionPattern.matchEntire(version)?.let { match ->
        (1..3).map { match.groupValues[it].toLongOrNull() ?: return null }
    }
    fun compare(left: String, right: String): Int {
        val a = requireNotNull(parts(left)) { "版本号无效" }
        val b = requireNotNull(parts(right)) { "当前版本号无效" }
        for (i in 0..2) if (a[i] != b[i]) return a[i].compareTo(b[i])
        return 0
    }
    fun select(releases: JsonArray, abis: List<String>): AppUpdate {
        var latest: AppUpdate? = null
        for (item in releases) {
            if (!item.isJsonObject) continue
            val release = item.asJsonObject
            try {
                if (release.get("draft")?.asBoolean == true || release.get("prerelease")?.asBoolean == true) continue
                val tag = release.get("tag_name")?.asString ?: continue
                if (parts(tag) == null) continue
                val apk = release.getAsJsonArray("assets")?.any { asset ->
                    val name = asset.asJsonObject.get("name").asString.lowercase(java.util.Locale.ROOT)
                    val arm64 = name.contains("arm64") || name.contains("aarch64")
                    val arm32 = name.contains("armeabi") || name.contains("armv7")
                    val x64 = name.contains("x86_64") || name.contains("x64") || name.contains("amd64")
                    val x86 = !x64 && name.contains("x86")
                    name.contains("accnotify") && name.endsWith(".apk") &&
                        (name.contains("universal") || (!arm64 && !arm32 && !x64 && !x86) ||
                            (arm64 && "arm64-v8a" in abis) || (arm32 && "armeabi-v7a" in abis) ||
                            (x64 && "x86_64" in abis) || (x86 && "x86" in abis))
                } == true
                if (!apk) continue
                val link = release.get("html_url")?.asString ?: continue
                val uri = URI(link)
                if (uri.scheme != "https" || uri.host != "github.com" || uri.userInfo != null ||
                    !uri.path.startsWith("/trah01/Accnotify/releases/tag/")) continue
                val version = tag.removePrefix("android-").removePrefix("v")
                if (latest == null || compare(version, requireNotNull(latest.version)) > 0) latest = AppUpdate(version, link)
            } catch (_: Exception) { continue }
        }
        return latest ?: AppUpdate(null, RELEASES_URL)
    }
    suspend fun check(abis: List<String>): AppUpdate = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("https://api.github.com/repos/trah01/Accnotify/releases?per_page=30")
            .header("Accept", "application/vnd.github+json").header("User-Agent", "Accnotify-Android").build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { if (response.code in listOf(403, 429)) "更新服务暂时拒绝请求，请稍后重试或打开发布页面" else "更新服务暂时不可用，请稍后重试" }
            val input = requireNotNull(response.body) { "更新服务返回的数据无效" }.byteStream()
            val bytes = java.io.ByteArrayOutputStream()
            input.use { stream ->
                val chunk = ByteArray(8192)
                while (true) {
                    val count = stream.read(chunk)
                    if (count < 0) break
                    check(bytes.size() + count <= 5 * 1024 * 1024) { "更新信息过大，请打开发布页面查看" }
                    bytes.write(chunk, 0, count)
                }
            }
            val json = JsonParser.parseString(bytes.toString("UTF-8"))
            check(json.isJsonArray) { "更新服务返回的数据无效" }
            select(json.asJsonArray, abis)
        }
    }
}
