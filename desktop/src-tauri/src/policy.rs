use anyhow::{bail, Context, Result};
use chrono::{Datelike, Duration, Local, NaiveDate, NaiveDateTime, Timelike};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::BTreeSet;

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Calendar {
    pub year: i32,
    pub holidays: Vec<String>,
    pub workdays: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub source: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub fetched_at: Option<i64>,
}
#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Rules {
    pub enabled: bool,
    pub schedule_enabled: bool,
    pub workdays_only: bool,
    pub weekdays: Vec<u32>,
    pub start: String,
    pub end: String,
    pub silent_enabled: bool,
    pub silent_start: String,
    pub silent_end: String,
    pub holiday_silent: bool,
    pub silent_dates: Vec<String>,
    pub working_dates: Vec<String>,
    pub calendars: Vec<Calendar>,
}
impl Default for Rules {
    fn default() -> Self {
        Self {
            enabled: true,
            schedule_enabled: false,
            workdays_only: false,
            weekdays: (1..=7).collect(),
            start: "08:00".into(),
            end: "17:00".into(),
            silent_enabled: false,
            silent_start: "22:00".into(),
            silent_end: "08:00".into(),
            holiday_silent: false,
            silent_dates: vec![],
            working_dates: vec![],
            calendars: vec![],
        }
    }
}
fn valid_date(value: &str) -> bool {
    value.len() == 10
        && NaiveDate::parse_from_str(value, "%Y-%m-%d")
            .is_ok_and(|date| date.format("%Y-%m-%d").to_string() == value)
}
fn dates(value: &Value) -> Result<Vec<String>> {
    let items: Vec<String> = match value {
        Value::Null => vec![],
        Value::String(text) => text
            .split(|c: char| c.is_whitespace() || ",，;；".contains(c))
            .filter(|s| !s.is_empty())
            .map(String::from)
            .collect(),
        Value::Array(items) => items
            .iter()
            .map(|v| {
                v.as_str()
                    .map(String::from)
                    .context("日期请使用 YYYY-MM-DD 格式")
            })
            .collect::<Result<_>>()?,
        _ => bail!("日期请使用 YYYY-MM-DD 格式"),
    };
    if items.len() > 4000 || !items.iter().all(|s| valid_date(s)) {
        bail!("日期请使用 YYYY-MM-DD 格式");
    }
    Ok(items
        .into_iter()
        .collect::<BTreeSet<_>>()
        .into_iter()
        .collect())
}
pub fn validate_calendar(value: Value) -> Result<Calendar> {
    let year = value["year"].as_i64().context("日历年份无效")?;
    if !(2000..=9999).contains(&year) {
        bail!("日历年份无效");
    }
    let holidays = dates(&value["holidays"])?;
    let workdays = dates(&value["workdays"])?;
    if !holidays
        .iter()
        .chain(workdays.iter())
        .all(|s| s.starts_with(&format!("{year}-")))
    {
        bail!("日期与日历年份不一致");
    }
    if holidays.iter().any(|s| workdays.contains(s)) {
        bail!("放假日期与调休上班日期不能重复");
    }
    Ok(Calendar {
        year: year as i32,
        holidays,
        workdays,
        source: (value["source"] == "timor.tech").then(|| "timor.tech".into()),
        fetched_at: value["fetchedAt"].as_i64().filter(|n| *n > 0),
    })
}
fn minutes(value: &str) -> Result<u32> {
    let bytes = value.as_bytes();
    if bytes.len() != 5
        || bytes[2] != b':'
        || ![bytes[0], bytes[1], bytes[3], bytes[4]]
            .iter()
            .all(u8::is_ascii_digit)
    {
        bail!("时间请使用 HH:MM 格式");
    }
    let hour: u32 = value[..2].parse()?;
    let minute: u32 = value[3..].parse()?;
    if hour > 23 || minute > 59 {
        bail!("时间请使用 HH:MM 格式");
    }
    Ok(hour * 60 + minute)
}
pub fn validate_rules(mut value: Value) -> Result<Rules> {
    value["silentDates"] = serde_json::to_value(dates(&value["silentDates"])?)?;
    value["workingDates"] = serde_json::to_value(dates(&value["workingDates"])?)?;
    let mut result: Rules = serde_json::from_value(value).context("提醒设置无效")?;
    for time in [
        &result.start,
        &result.end,
        &result.silent_start,
        &result.silent_end,
    ] {
        minutes(time)?;
    }
    if result.weekdays.iter().any(|d| !(1..=7).contains(d)) {
        bail!("请选择有效的星期");
    }
    result.weekdays.sort();
    result.weekdays.dedup();
    if result.schedule_enabled && !result.workdays_only && result.weekdays.is_empty() {
        bail!("请选择至少一天");
    }
    if result
        .silent_dates
        .iter()
        .any(|s| result.working_dates.contains(s))
    {
        bail!("静默日期与上班日期不能重复");
    }
    if result.calendars.len() > 100 {
        bail!("日历无效");
    }
    let mut years = BTreeSet::new();
    for calendar in &mut result.calendars {
        *calendar = validate_calendar(serde_json::to_value(&*calendar)?)?;
        if !years.insert(calendar.year) {
            bail!("日历年份不能重复");
        }
    }
    Ok(result)
}
fn in_window(value: u32, start: u32, end: u32) -> bool {
    start == end
        || if start < end {
            value >= start && value < end
        } else {
            value >= start || value < end
        }
}
pub fn should_notify(rules: &Rules) -> bool {
    should_notify_at(rules, Local::now().naive_local())
}
fn should_notify_at(rules: &Rules, now: NaiveDateTime) -> bool {
    if !rules.enabled {
        return false;
    }
    let day = now.format("%Y-%m-%d").to_string();
    if rules.silent_dates.contains(&day) {
        return false;
    }
    let calendar = rules.calendars.iter().find(|c| c.year == now.year());
    if rules.holiday_silent
        && !rules.working_dates.contains(&day)
        && calendar.is_some_and(|c| c.holidays.contains(&day))
    {
        return false;
    }
    let minute = now.hour() * 60 + now.minute();
    if rules.silent_enabled
        && in_window(
            minute,
            minutes(&rules.silent_start).unwrap_or(0),
            minutes(&rules.silent_end).unwrap_or(0),
        )
    {
        return false;
    }
    if !rules.schedule_enabled {
        return true;
    }
    let start = minutes(&rules.start).unwrap_or(0);
    let end = minutes(&rules.end).unwrap_or(0);
    if !in_window(minute, start, end) {
        return false;
    }
    let anchor = if start > end && minute < end {
        now - Duration::days(1)
    } else {
        now
    };
    let weekday = anchor.weekday().number_from_monday();
    if !rules.workdays_only {
        return rules.weekdays.contains(&weekday);
    }
    let key = anchor.format("%Y-%m-%d").to_string();
    if rules.working_dates.contains(&key) {
        return true;
    }
    if let Some(c) = rules.calendars.iter().find(|c| c.year == anchor.year()) {
        if c.holidays.contains(&key) {
            return false;
        }
        if c.workdays.contains(&key) {
            return true;
        }
    }
    weekday <= 5
}
pub fn parse_holiday(value: Value, year: i32) -> Result<Calendar> {
    if value["code"] != 0 {
        bail!("节假日接口返回的数据无效");
    }
    let entries = value["holiday"]
        .as_object()
        .context("节假日接口返回的数据无效")?;
    let mut holidays = vec![];
    let mut workdays = vec![];
    for (key, item) in entries {
        let holiday = item["holiday"].as_bool().context("节假日日期类型无效")?;
        let date = item["date"]
            .as_str()
            .map(String::from)
            .unwrap_or_else(|| format!("{year}-{key}"));
        if holiday {
            holidays.push(date);
        } else {
            workdays.push(date);
        }
    }
    if holidays.is_empty() {
        bail!("该年度节假日数据尚未提供");
    }
    validate_calendar(
        serde_json::json!({"year":year,"holidays":holidays,"workdays":workdays,"source":"timor.tech","fetchedAt":Local::now().timestamp_millis()}),
    )
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn calendar_and_midnight_policy() {
        let mut r = Rules::default();
        r.schedule_enabled = true;
        r.workdays_only = true;
        r.holiday_silent = true;
        r.calendars = vec![validate_calendar(
            serde_json::json!({"year":2026,"holidays":["2026-10-01"],"workdays":["2026-10-10"]}),
        )
        .unwrap()];
        let at = |s| NaiveDateTime::parse_from_str(s, "%Y-%m-%dT%H:%M:%S").unwrap();
        assert!(should_notify_at(&r, at("2026-10-14T08:00:00")));
        assert!(!should_notify_at(&r, at("2026-10-14T17:00:00")));
        assert!(should_notify_at(&r, at("2026-10-10T10:00:00")));
        assert!(!should_notify_at(&r, at("2026-10-01T10:00:00")));
        r.working_dates.push("2026-10-01".into());
        assert!(should_notify_at(&r, at("2026-10-01T10:00:00")));
        r.workdays_only = false;
        r.weekdays = vec![5];
        r.start = "22:00".into();
        r.end = "08:00".into();
        assert!(should_notify_at(&r, at("2026-10-17T07:59:00")));
        assert!(!should_notify_at(&r, at("2026-10-17T08:00:00")));
        assert!(dates(&Value::String("2026-02-30".into())).is_err());
        assert!(parse_holiday(serde_json::json!({"code":0,"holiday":{}}), 2026).is_err());
    }
}
