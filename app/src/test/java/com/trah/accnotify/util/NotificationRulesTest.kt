package com.trah.accnotify.util

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class NotificationRulesTest {
    @Test fun workingDaysHolidaysAndBoundaries() {
        val calendar = HolidayCalendar(2026, setOf("2026-10-01"), setOf("2026-10-10"))
        val rules = NotificationRules(scheduleEnabled = true, workdaysOnly = true, holidaySilent = true, calendars = listOf(calendar))
        assertTrue(rules.shouldNotify(LocalDateTime.parse("2026-10-14T08:00:00")))
        assertFalse(rules.shouldNotify(LocalDateTime.parse("2026-10-14T17:00:00")))
        assertTrue(rules.shouldNotify(LocalDateTime.parse("2026-10-10T10:00:00")))
        assertFalse(rules.shouldNotify(LocalDateTime.parse("2026-10-01T10:00:00")))
        assertTrue(rules.copy(workingDates = setOf("2026-10-01")).shouldNotify(LocalDateTime.parse("2026-10-01T10:00:00")))
        assertFalse(rules.copy(silentDates = setOf("2026-10-14")).shouldNotify(LocalDateTime.parse("2026-10-14T10:00:00")))
        assertFalse(rules.copy(enabled = false).shouldNotify(LocalDateTime.parse("2026-10-14T10:00:00")))
    }
    @Test fun crossMidnightBelongsToPreviousDay() {
        val rules = NotificationRules(scheduleEnabled = true, weekdays = setOf(5), start = "22:00", end = "08:00")
        assertTrue(rules.shouldNotify(LocalDateTime.parse("2026-10-17T07:59:00")))
        assertFalse(rules.shouldNotify(LocalDateTime.parse("2026-10-17T08:00:00")))
        assertFalse(rules.copy(silentEnabled = true).shouldNotify(LocalDateTime.parse("2026-10-17T07:59:00")))
    }
    @Test fun timorParserDistinguishesHolidaysAndMakeupDays() {
        val calendar = HolidayCalendarClient.parse("""{"code":0,"holiday":{"01-01":{"holiday":true,"date":"2026-01-01"},"01-04":{"holiday":false,"date":"2026-01-04"}}}""", 2026)
        assertEquals(setOf("2026-01-01"), calendar.holidays)
        assertEquals(setOf("2026-01-04"), calendar.workdays)
        assertEquals("timor.tech", calendar.source)
    }
}
