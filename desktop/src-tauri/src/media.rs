use anyhow::{bail, Context, Result};
use base64::{engine::general_purpose::STANDARD, Engine};
use futures_util::StreamExt;
use serde_json::{json, Value};
use tauri_plugin_dialog::DialogExt;
const LIMIT: usize = 8 * 1024 * 1024;

pub fn image_type(bytes: &[u8]) -> Result<(&'static str, &'static str)> {
    if bytes.starts_with(b"\x89PNG\r\n\x1a\n") {
        Ok(("image/png", "png"))
    } else if bytes.starts_with(b"\xff\xd8\xff") {
        Ok(("image/jpeg", "jpg"))
    } else if bytes.starts_with(b"GIF87a") || bytes.starts_with(b"GIF89a") {
        Ok(("image/gif", "gif"))
    } else if bytes.len() >= 12 && &bytes[..4] == b"RIFF" && &bytes[8..12] == b"WEBP" {
        Ok(("image/webp", "webp"))
    } else {
        bail!("仅支持 PNG、JPEG、GIF 和 WebP 图片");
    }
}
pub async fn load(source: &str) -> Result<Vec<u8>> {
    let bytes = if source.starts_with("http://") || source.starts_with("https://") {
        let url = url::Url::parse(source)?;
        if !url.username().is_empty() || url.password().is_some() {
            bail!("图片地址无效");
        }
        let response = reqwest::Client::builder()
            .timeout(std::time::Duration::from_secs(15))
            .redirect(reqwest::redirect::Policy::limited(5))
            .build()?
            .get(url)
            .send()
            .await
            .context("图片加载失败，请检查网络后重试")?
            .error_for_status()
            .context("图片下载失败")?;
        let mut stream = response.bytes_stream();
        let mut bytes = vec![];
        while let Some(chunk) = stream.next().await {
            let chunk = chunk?;
            if bytes.len() + chunk.len() > LIMIT {
                bail!("图片不能超过 8 MB");
            }
            bytes.extend_from_slice(&chunk);
        }
        bytes
    } else {
        let encoded = if source.starts_with("data:") {
            source.split_once(',').context("图片格式无效")?.1
        } else {
            source
        };
        if encoded.len() > LIMIT * 4 / 3 + 4 {
            bail!("图片不能超过 8 MB");
        }
        STANDARD
            .decode(
                encoded
                    .chars()
                    .filter(|c| !c.is_whitespace())
                    .collect::<String>(),
            )
            .context("图片格式无效")?
    };
    if bytes.len() > LIMIT {
        bail!("图片不能超过 8 MB");
    }
    image_type(&bytes)?;
    Ok(bytes)
}
pub async fn dispatch(app: &tauri::AppHandle, source: &str, save: bool) -> Result<Value> {
    let bytes = load(source).await?;
    let (mime, extension) = image_type(&bytes)?;
    if !save {
        return Ok(
            json!({"dataURL":format!("data:{mime};base64,{}", STANDARD.encode(&bytes)),"mime":mime,"extension":extension}),
        );
    }
    let (sender, receiver) = tokio::sync::oneshot::channel();
    app.dialog()
        .file()
        .set_file_name(format!("Accnotify.{}", extension))
        .add_filter("图片", &[extension])
        .save_file(move |path| {
            let _ = sender.send(path);
        });
    let Some(path) = receiver.await? else {
        return Ok(json!(false));
    };
    std::fs::write(
        path.into_path()
            .map_err(|_| anyhow::anyhow!("保存位置无效"))?,
        bytes,
    )
    .context("图片保存失败")?;
    Ok(json!(true))
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_active_and_unknown_image_formats() {
        assert!(image_type(b"<svg onload='alert(1)'></svg>").is_err());
        assert!(image_type(b"<html>image</html>").is_err());
        assert_eq!(image_type(b"\x89PNG\r\n\x1a\n").unwrap().0, "image/png");
    }
}
