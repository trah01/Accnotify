use anyhow::{bail, Context, Result};
use futures_util::StreamExt;
use semver::Version;
use serde_json::{json, Value};
use std::time::Duration;

pub const PROJECT_URL: &str = "https://github.com/trah01/Accnotify";
pub const RELEASES_URL: &str = "https://github.com/trah01/Accnotify/releases";
const API_URL: &str = "https://api.github.com/repos/trah01/Accnotify/releases?per_page=30";

pub fn platform_label() -> &'static str {
    match (std::env::consts::OS, std::env::consts::ARCH) {
        ("macos", "aarch64") => "macOS · Apple 芯片",
        ("macos", "x86_64") => "macOS · Intel",
        ("windows", "x86_64") => "Windows · 64 位",
        ("windows", "aarch64") => "Windows · ARM64",
        _ => std::env::consts::OS,
    }
}
pub async fn check(current: &str) -> Result<Value> {
    // Only the explicit "check-updates" action calls this function.
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(15))
        .redirect(reqwest::redirect::Policy::none())
        .build()?;
    let response = client
        .get(API_URL)
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", format!("Accnotify/{current}"))
        .send()
        .await
        .context("检查更新失败，请检查网络后重试")?;
    match response.status().as_u16() {
        200 => {}
        403 | 429 => bail!("更新服务暂时拒绝请求，请稍后重试或打开发布页面"),
        _ => bail!("更新服务暂时不可用，请稍后重试或打开发布页面"),
    }
    let mut bytes = Vec::new();
    let mut stream = response.bytes_stream();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.context("检查更新失败，请检查网络后重试")?;
        if bytes.len() + chunk.len() > 5 * 1024 * 1024 {
            bail!("更新信息过大，请打开发布页面查看");
        }
        bytes.extend_from_slice(&chunk);
    }
    let releases: Value = serde_json::from_slice(&bytes).context("更新服务返回的数据无效")?;
    select_release(
        &releases,
        current,
        std::env::consts::OS,
        std::env::consts::ARCH,
    )
}
fn release_version(tag: &str) -> Option<Version> {
    let tag = tag.strip_prefix("desktop-").unwrap_or(tag);
    Version::parse(tag.strip_prefix('v').unwrap_or(tag))
        .ok()
        .filter(|v| v.pre.is_empty())
}
fn matching_asset(name: &str, os: &str, arch: &str) -> bool {
    let name = name.to_ascii_lowercase();
    if !name.contains("accnotify") {
        return false;
    }
    let platform = match os {
        "macos" => {
            name.ends_with(".dmg")
                || (name.ends_with(".zip") && (name.contains("mac") || name.contains("darwin")))
                || name.ends_with(".app.tar.gz")
        }
        "windows" => {
            name.ends_with(".exe")
                || name.ends_with(".msi")
                || (name.ends_with(".zip") && name.contains("win"))
        }
        _ => false,
    };
    if !platform {
        return false;
    }
    let arm = name.contains("arm64") || name.contains("aarch64");
    let intel = name.contains("x86_64") || name.contains("x64") || name.contains("amd64");
    if name.contains("universal") {
        return true;
    }
    match arch {
        "aarch64" => !intel,
        "x86_64" => !arm,
        _ => !intel && !arm,
    }
}
fn select_release(releases: &Value, current: &str, os: &str, arch: &str) -> Result<Value> {
    let releases = releases.as_array().context("更新服务返回的数据无效")?;
    let current = Version::parse(current).context("当前版本号无效")?;
    let mut latest: Option<(Version, String)> = None;
    for release in releases {
        if release["draft"] == true || release["prerelease"] == true {
            continue;
        }
        let Some(version) = release["tag_name"].as_str().and_then(release_version) else {
            continue;
        };
        if !release["assets"].as_array().is_some_and(|assets| {
            assets.iter().any(|a| {
                a["name"]
                    .as_str()
                    .is_some_and(|n| matching_asset(n, os, arch))
            })
        }) {
            continue;
        }
        let Some(link) = release["html_url"]
            .as_str()
            .and_then(|s| url::Url::parse(s).ok())
            .filter(|url| {
                url.scheme() == "https"
                    && url.host_str() == Some("github.com")
                    && url.username().is_empty()
                    && url.password().is_none()
                    && url.path().starts_with("/trah01/Accnotify/releases/tag/")
            })
        else {
            continue;
        };
        if latest
            .as_ref()
            .is_none_or(|(old, _)| version.cmp_precedence(old).is_gt())
        {
            latest = Some((version, link.to_string()));
        }
    }
    Ok(match latest {
        Some((version, url)) if version.cmp_precedence(&current).is_gt() => {
            json!({"status":"available","version":version.to_string(),"url":url})
        }
        Some(_) => json!({"status":"current","url":RELEASES_URL}),
        None => json!({"status":"unpublished","url":RELEASES_URL}),
    })
}
#[cfg(test)]
mod tests {
    use super::*;
    fn release(tag: &str, asset: &str) -> Value {
        json!({"tag_name":tag,"html_url":format!("{RELEASES_URL}/tag/{tag}"),"assets":[{"name":asset}],"draft":false,"prerelease":false})
    }
    #[test]
    fn update_matches_desktop_platform_architecture_and_numeric_versions() {
        let releases = json!([
            release("v9.0.0", "Accnotify.apk"),
            release("v8.0.0", "Accnotify_8.0.0_x64.dmg"),
            release("v0.9.0", "Accnotify_0.9.0_aarch64.dmg"),
            release("desktop-v0.10.0", "Accnotify_0.10.0_aarch64.dmg")
        ]);
        let result = select_release(&releases, "0.2.0", "macos", "aarch64").unwrap();
        assert_eq!(result["status"], "available");
        assert_eq!(result["version"], "0.10.0");
        assert_eq!(
            select_release(&releases, "0.10.0", "macos", "aarch64").unwrap()["status"],
            "current"
        );
    }
    #[test]
    fn android_only_unpublished_and_prereleases_are_not_desktop_updates() {
        let mut preview = release("v9.0.0", "Accnotify_9.0.0_aarch64.dmg");
        preview["prerelease"] = json!(true);
        let mut draft = release("v8.0.0", "Accnotify_8.0.0_aarch64.dmg");
        draft["draft"] = json!(true);
        assert_eq!(
            select_release(
                &json!([release("v7.0.0", "Accnotify.apk"), preview, draft]),
                "0.2.0",
                "macos",
                "aarch64"
            )
            .unwrap()["status"],
            "unpublished"
        );
        assert!(select_release(&json!({}), "0.2.0", "macos", "aarch64").is_err());
    }
    #[test]
    fn unexpected_release_links_are_ignored() {
        let mut value = release("v9.0.0", "Accnotify_9.0.0_aarch64.dmg");
        value["html_url"] = json!("https://example.com/download");
        assert_eq!(
            select_release(&json!([value]), "0.2.0", "macos", "aarch64").unwrap()["status"],
            "unpublished"
        );
    }
}
