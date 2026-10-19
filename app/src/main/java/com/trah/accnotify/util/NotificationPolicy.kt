package com.trah.accnotify.util

import android.content.Context
import com.google.gson.Gson
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

data class HolidayCalendar(val year: Int, val holidays: Set<String> = emptySet(), val workdays: Set<String> = emptySet(), val source: String = "", val fetchedAt: Long = 0)

data class NotificationRules(
    val enabled: Boolean = true,
    val scheduleEnabled: Boolean = false,
    val workdaysOnly: Boolean = false,
    val weekdays: Set<Int> = (1..7).toSet(),
    val start: String = "08:00",
    val end: String = "17:00",
    val silentEnabled: Boolean = false,
    val silentStart: String = "22:00",
    val silentEnd: String = "08:00",
    val holidaySilent: Boolean = false,
    val silentDates: Set<String> = emptySet(),
    val workingDates: Set<String> = emptySet(),
    val calendars: List<HolidayCalendar> = emptyList()
) {
    private fun working(date: LocalDate): Boolean {
        val day = date.toString()
        if (day in workingDates) return true
        val calendar = calendars.firstOrNull { it.year == date.year }
        if (day in calendar?.holidays.orEmpty()) return false
        if (day in calendar?.workdays.orEmpty()) return true
        return date.dayOfWeek.value <= 5
    }

    // Cross-midnight windows belong to the day on which the window starts.
    fun shouldNotify(now: LocalDateTime): Boolean {
        if (!enabled) return false
        val day = now.toLocalDate()
        if (day.toString() in silentDates) return false
        val calendar = calendars.firstOrNull { it.year == day.year }
        if (holidaySilent && day.toString() !in workingDates && day.toString() in calendar?.holidays.orEmpty()) return false
        val minute = now.hour * 60 + now.minute
        if (silentEnabled && inWindow(minute, minutes(silentStart), minutes(silentEnd))) return false
        if (!scheduleEnabled) return true
        val first = minutes(start)
        val last = minutes(end)
        if (!inWindow(minute, first, last)) return false
        val anchor = if (first > last && minute < last) day.minusDays(1) else day
        return if (workdaysOnly) working(anchor) else anchor.dayOfWeek.value in weekdays
    }

    companion object {
        fun minutes(value: String): Int { val t = LocalTime.parse(value); return t.hour * 60 + t.minute }
        fun inWindow(value: Int, start: Int, end: Int): Boolean = when {
            start == end -> true
            start < end -> value >= start && value < end
            else -> value >= start || value < end
        }
        fun parseDates(value: String): Set<String> = value.split(Regex("[\\s,，;；]+"))
            .filter { it.isNotBlank() }.map { LocalDate.parse(it).toString() }.toSet()
        fun validateCalendar(calendar: HolidayCalendar): HolidayCalendar {
            require(calendar.year in 2000..9999) { "日历年份无效" }
            require(calendar.holidays.intersect(calendar.workdays).isEmpty()) { "放假日期与调休上班日期不能重复" }
            (calendar.holidays + calendar.workdays).forEach { require(LocalDate.parse(it).year == calendar.year) { "日期与日历年份不一致" } }
            return calendar
        }
    }
}

object NotificationPolicy {
    private const val PREFS = "notification_rules"
    fun load(context: Context): NotificationRules = try {
        Gson().fromJson(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("rules", null), NotificationRules::class.java) ?: NotificationRules()
    } catch (_: Exception) { NotificationRules() }
    fun save(context: Context, rules: NotificationRules) {
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("rules", Gson().toJson(rules)).commit()) { "设置保存失败" }
    }
    fun shouldNotify(context: Context): Boolean = load(context).shouldNotify(LocalDateTime.now())
}
