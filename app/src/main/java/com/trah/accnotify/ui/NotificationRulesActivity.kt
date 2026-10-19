package com.trah.accnotify.ui

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.trah.accnotify.util.HolidayCalendarClient
import android.view.View
import android.os.Bundle
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.JsonParser
import com.trah.accnotify.util.HolidayCalendar
import com.trah.accnotify.util.NotificationPolicy
import com.trah.accnotify.util.NotificationRules
import java.time.LocalDate

class NotificationRulesActivity : AppCompatActivity() {
    private lateinit var enabled: CheckBox
    private lateinit var scheduled: CheckBox
    private lateinit var workdays: CheckBox
    private lateinit var quiet: CheckBox
    private lateinit var holidays: CheckBox
    private lateinit var start: TextView
    private lateinit var end: TextView
    private lateinit var silentStart: TextView
    private lateinit var silentEnd: TextView
    private lateinit var dates: EditText
    private lateinit var workingDates: EditText
    private lateinit var calendarStatus: TextView
    private lateinit var fetchButton: TextView
    private var fetching = false
    private val weekdays = mutableListOf<CheckBox>()
    private var calendars = emptyList<HolidayCalendar>()
    private var rules = NotificationRules()
    private val importCalendar = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) try {
            val text = contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytesLimited(128 * 1024)
                bytes.toString(Charsets.UTF_8)
            } ?: error("无法打开文件")
            val json = JsonParser.parseString(text).asJsonObject
            fun list(name: String) = json.getAsJsonArray(name)?.map { it.asString }?.toSet() ?: emptySet()
            val calendar = NotificationRules.validateCalendar(HolidayCalendar(json.get("year").asInt, list("holidays"), list("workdays")))
            calendars = calendars.filter { it.year != calendar.year } + calendar
            updateCalendarStatus()
            Toast.makeText(this, "已导入 ${calendar.year} 年日历，点击保存后生效", Toast.LENGTH_LONG).show()
        } catch (_: Exception) { Toast.makeText(this, "日历无效，请检查年份和日期格式", Toast.LENGTH_LONG).show() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val overlay = when (com.trah.accnotify.AccnotifyApp.getInstance().keyManager.themeColor) {
            "blue" -> com.trah.accnotify.R.style.ThemeOverlay_Blue
            "green" -> com.trah.accnotify.R.style.ThemeOverlay_Green
            "red" -> com.trah.accnotify.R.style.ThemeOverlay_Red
            "pink" -> com.trah.accnotify.R.style.ThemeOverlay_Pink
            "black" -> com.trah.accnotify.R.style.ThemeOverlay_Black
            else -> com.trah.accnotify.R.style.ThemeOverlay_White
        }
        theme.applyStyle(overlay, true)
        super.onCreate(savedInstanceState)
        rules = NotificationPolicy.load(this)
        calendars = rules.calendars
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setBackgroundColor(com.google.android.material.color.MaterialColors.getColor(this, android.R.attr.colorBackground))
        }
        val toolbar = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(8), 0) }
        toolbar.addView(KeyDialogControls.icon(this, toolbar, com.trah.accnotify.R.drawable.ic_fluent_chevron_right, "返回") { finish() }.apply { rotation = 180f })
        toolbar.addView(NotificationUi.text(this, "消息提醒").apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(com.trah.accnotify.R.dimen.text_heading)); setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(NotificationUi.action(this, "保存").apply { setOnClickListener { save() } })
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(56)))
        val scroll = androidx.core.widget.NestedScrollView(this).apply {
            clipChildren = false
            val padding = resources.getDimensionPixelSize(com.trah.accnotify.R.dimen.spacing_page)
            setPadding(padding, resources.getDimensionPixelSize(com.trah.accnotify.R.dimen.spacing_gap), padding, padding); clipToPadding = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        var form = section(content)
        enabled = toggle(form, "显示消息通知", rules.enabled)
        form.addView(NotificationUi.text(this, "静默或关闭提醒时，消息仍保存到历史。", secondary = true).apply { setPadding(0, resources.getDimensionPixelSize(com.trah.accnotify.R.dimen.spacing_small), 0, dp(8)) })
        form = section(content)
        val advanced = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val advancedToggle = settingsButton("高级设置").apply {
            setOnClickListener {
                val expand = advanced.visibility != View.VISIBLE
                advanced.visibility = if (expand) View.VISIBLE else View.GONE
                text = if (expand) "收起高级设置" else "高级设置"
                contentDescription = text
            }
        }
        form.addView(advancedToggle)
        content.addView(advanced)
        form = section(advanced)
        scheduled = toggle(form, "仅在指定日期和时段提醒", rules.scheduleEnabled)
        workdays = toggle(form, "仅工作日（包含调休上班日）", rules.workdaysOnly)
        val dayRows = listOf(LinearLayout(this), LinearLayout(this))
        listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { index, label ->
            val box = com.google.android.material.checkbox.MaterialCheckBox(this).apply {
                text = "周$label"; contentDescription = "周$label"; setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(com.trah.accnotify.R.dimen.text_body))
                isChecked = index + 1 in rules.weekdays; minWidth = 0; minHeight = dp(48)
                setPadding(0, 0, 0, 0)
            }
            weekdays += box
            dayRows[if (index < 4) 0 else 1].addView(box, LinearLayout.LayoutParams(0, -2, 1f))
        }
        dayRows.forEach { form.addView(it) }
        start = timeButton(form, "提醒开始", rules.start)
        end = timeButton(form, "提醒结束", rules.end)
        form = section(advanced)
        quiet = toggle(form, "启用每日静默时段", rules.silentEnabled)
        silentStart = timeButton(form, "静默开始", rules.silentStart)
        silentEnd = timeButton(form, "静默结束", rules.silentEnd)
        form.addView(NotificationUi.text(this, "结束早于开始表示跨午夜；开始与结束相同表示全天。", secondary = true))
        form = section(advanced)
        holidays = toggle(form, "节假日静默", rules.holidaySilent)
        calendarStatus = NotificationUi.text(this, "", secondary = true).apply { setPadding(0, resources.getDimensionPixelSize(com.trah.accnotify.R.dimen.spacing_small), 0, resources.getDimensionPixelSize(com.trah.accnotify.R.dimen.spacing_small)) }
        form.addView(calendarStatus)
        fetchButton = settingsButton("获取本年度节假日").apply { setOnClickListener { fetchCalendar() } }
        form.addView(fetchButton)
        form.addView(settingsButton("导入本地节假日日历").apply { setOnClickListener { importCalendar.launch("application/json") } })
        form = section(advanced)
        dates = dateField(form, "自定义静默日期", rules.silentDates)
        workingDates = dateField(form, "自定义上班日期", rules.workingDates)
        updateCalendarStatus()
        fun updateStates() {
            workdays.isEnabled = scheduled.isChecked
            weekdays.forEach { it.isEnabled = scheduled.isChecked && !workdays.isChecked }
            start.isEnabled = scheduled.isChecked; end.isEnabled = scheduled.isChecked
            silentStart.isEnabled = quiet.isChecked; silentEnd.isEnabled = quiet.isChecked
        }
        scheduled.setOnCheckedChangeListener { _, _ -> updateStates() }
        workdays.setOnCheckedChangeListener { _, _ -> updateStates() }
        quiet.setOnCheckedChangeListener { _, _ -> updateStates() }
        updateStates()
        scroll.addView(content); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(root)
    }

    private fun section(content: LinearLayout): LinearLayout {
        val card = layoutInflater.inflate(com.trah.accnotify.R.layout.notification_settings_section, content, false)
        val form = card.findViewById<LinearLayout>(com.trah.accnotify.R.id.notificationSectionBody)
        content.addView(card)
        return form
    }

    private fun toggle(form: LinearLayout, label: String, value: Boolean) = com.google.android.material.checkbox.MaterialCheckBox(this).apply { text = label; setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(com.trah.accnotify.R.dimen.text_body)); isChecked = value; minHeight = dp(48); form.addView(this) }
    private fun settingsButton(label: String) = NotificationUi.action(this, label, primary = true).apply {
        layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply {
            topMargin = resources.getDimensionPixelSize(com.trah.accnotify.R.dimen.spacing_small)
            bottomMargin = resources.getDimensionPixelSize(com.trah.accnotify.R.dimen.spacing_small)
        }
    }

    private fun timeButton(form: LinearLayout, label: String, value: String) = settingsButton("").apply {
        tag = value; text = "$label  $value"; form.addView(this)
        setOnClickListener {
            val parts = (tag as String).split(':')
            val picker = com.google.android.material.timepicker.MaterialTimePicker.Builder()
                .setTitleText(label)
                .setTimeFormat(com.google.android.material.timepicker.TimeFormat.CLOCK_24H)
                .setInputMode(com.google.android.material.timepicker.MaterialTimePicker.INPUT_MODE_KEYBOARD)
                .setHour(parts[0].toInt()).setMinute(parts[1].toInt())
                .setPositiveButtonText("确定").setNegativeButtonText("取消").build()
            picker.addOnPositiveButtonClickListener {
                tag = String.format(java.util.Locale.ROOT, "%02d:%02d", picker.hour, picker.minute)
                text = "$label  $tag"
            }
            picker.show(supportFragmentManager, "notification-time")
        }
    }
    private fun dateField(form: LinearLayout, label: String, value: Set<String>): EditText {
        form.addView(NotificationUi.text(this, label).apply { setPadding(0, dp(8), 0, resources.getDimensionPixelSize(com.trah.accnotify.R.dimen.spacing_small)) })
        return NotificationUi.input(this, "YYYY-MM-DD，多个日期用逗号或换行分隔").apply { setText(value.sorted().joinToString(", ")); minLines = 2; form.addView(this) }
    }
    private fun updateCalendarStatus() {
        val year = LocalDate.now().year
        val current = calendars.firstOrNull { it.year == year }
        calendarStatus.text = if (current != null) {
            val fetched = if (current.fetchedAt > 0) "，获取时间：" + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(current.fetchedAt)) else ""
            "已保存 $year 年日历$fetched。仅手动获取，不自动联网更新。"
        } else "未导入 $year 年日历：工作日暂按周一至周五，节假日静默需先获取或导入日历。"
    }
    private fun fetchCalendar() {
        if (fetching) return
        val year = LocalDate.now().year
        fun fetch() {
            fetching = true; fetchButton.isEnabled = false; fetchButton.text = "正在获取…"
            lifecycleScope.launch {
                try {
                    val calendar = HolidayCalendarClient.fetch(year)
                    val persisted = NotificationPolicy.load(this@NotificationRulesActivity)
                    // Save only the calendar; other unsaved fields keep their original behavior.
                    NotificationPolicy.save(this@NotificationRulesActivity, persisted.copy(calendars = persisted.calendars.filter { it.year != year } + calendar))
                    calendars = calendars.filter { it.year != year } + calendar
                    updateCalendarStatus()
                    Toast.makeText(this@NotificationRulesActivity, "本年度节假日已获取并保存", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    val message = when (e) {
                        is java.net.UnknownHostException -> "无法解析 timor.tech，请检查手机网络或 DNS"
                        is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> "连接节假日网站超时，请稍后手动重试"
                        is javax.net.ssl.SSLException -> "无法建立节假日网站的安全连接，请检查手机时间和网络"
                        is java.io.IOException -> "连接节假日网站失败，请检查手机网络"
                        is com.google.gson.JsonParseException -> "节假日网站返回的数据不是有效日历"
                        else -> e.message ?: "节假日获取失败，请稍后手动重试"
                    }
                    updateCalendarStatus()
                    calendarStatus.append("\n$message")
                    Toast.makeText(this@NotificationRulesActivity, message, Toast.LENGTH_LONG).show()
                } finally { fetching = false; fetchButton.isEnabled = true; fetchButton.text = "获取本年度节假日" }
            }
        }
        if (calendars.any { it.year == year }) NotificationUi.dialog(this, "重新获取节假日？",
            message = "已保存 $year 年日历，重新获取会替换本年度数据。", negative = "取消",
            positive = "重新获取", onPositive = { fetch() }).show() else fetch()
    }
    private fun save() {
        if (fetching) { Toast.makeText(this, "请等待节假日获取完成", Toast.LENGTH_SHORT).show(); return }
        try {
            val silentDates = NotificationRules.parseDates(dates.text.toString())
            val workDates = NotificationRules.parseDates(workingDates.text.toString())
            require(silentDates.intersect(workDates).isEmpty()) { "静默日期与上班日期不能重复" }
            val selected = weekdays.mapIndexedNotNull { index, box -> if (box.isChecked) index + 1 else null }.toSet()
            require(!scheduled.isChecked || workdays.isChecked || selected.isNotEmpty()) { "请选择至少一天" }
            NotificationPolicy.save(this, NotificationRules(enabled.isChecked, scheduled.isChecked, workdays.isChecked,
                selected, start.tag as String, end.tag as String, quiet.isChecked, silentStart.tag as String,
                silentEnd.tag as String, holidays.isChecked, silentDates, workDates, calendars))
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show(); finish()
        } catch (e: Exception) { Toast.makeText(this, e.message ?: "请检查日期格式", Toast.LENGTH_LONG).show() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) { val count = read(buffer); if (count < 0) break; require(output.size() + count <= limit); output.write(buffer, 0, count) }
        return output.toByteArray()
    }
}
