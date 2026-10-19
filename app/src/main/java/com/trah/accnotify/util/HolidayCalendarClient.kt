package com.trah.accnotify.util

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object HolidayCalendarClient {
    private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(false).retryOnConnectionFailure(false).build()

    // Called only by the explicit settings button; never from a receiver or service.
    suspend fun fetch(year: Int): HolidayCalendar = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("https://timor.tech/api/holiday/year/$year")
            .header("Accept", "application/json")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { when (response.code) {
                403 -> "节假日网站拒绝了请求（403），请稍后手动重试"
                429 -> "节假日网站请求过于频繁（429），请稍后手动重试"
                else -> "节假日网站返回错误（${response.code}），请稍后手动重试"
            } }
            val body = requireNotNull(response.body) { "节假日接口返回的数据为空" }
            val text = body.byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) { val count = input.read(buffer); if (count < 0) break
                    check(output.size() + count <= 128 * 1024) { "节假日接口返回的数据过大" }; output.write(buffer, 0, count) }
                output.toString("UTF-8")
            }
            parse(text, year)
        }
    }

    fun parse(text: String, year: Int): HolidayCalendar {
        check(!text.trimStart().startsWith("<")) { "节假日网站返回了网页，未返回日历数据" }
        val json = JsonParser.parseString(text).asJsonObject
        check(json.get("code")?.asInt == 0) { "节假日接口返回的数据无效" }
        val data = requireNotNull(json.getAsJsonObject("holiday")) { "节假日接口返回的数据无效" }
        val holidays = mutableSetOf<String>()
        val workdays = mutableSetOf<String>()
        data.entrySet().forEach { (key, element) ->
            val value = element.asJsonObject
            val holiday = requireNotNull(value.get("holiday"))
            require(holiday.isJsonPrimitive && holiday.asJsonPrimitive.isBoolean) { "节假日日期类型无效" }
            val date = value.get("date")?.asString ?: "$year-$key"
            if (holiday.asBoolean) holidays += date else workdays += date
        }
        check(holidays.isNotEmpty()) { "该年度节假日数据尚未提供" }
        return NotificationRules.validateCalendar(HolidayCalendar(year, holidays, workdays, "timor.tech", System.currentTimeMillis()))
    }
}
