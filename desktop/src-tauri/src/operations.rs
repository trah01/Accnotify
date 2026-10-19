use crate::{
    client::{normalize_server, string, Worker},
    crypto,
    store::PendingReset,
};
use anyhow::{bail, Context, Result};
use serde_json::{json, Value};
use tauri_plugin_clipboard_manager::ClipboardExt;
use tauri_plugin_notification::NotificationExt;

pub fn theme(value: &Value, choices: &[&str]) -> Result<String> {
    let text = string(value)?;
    if !choices.contains(&text) {
        bail!("外观设置无效");
    }
    Ok(text.into())
}
impl Worker {
    pub(crate) async fn extra_action(&mut self, channel: &str, value: Value) -> Result<Value> {
        match channel {
            "accept-consent" => {
                let previous = self.store.config.official_consent;
                self.store.config.official_consent = true;
                if let Err(error) = self.store.save_config() {
                    self.store.config.official_consent = previous;
                    return Err(error);
                }
            }
            "server-manage" => {
                let mode = string(&value["mode"])?;
                let url = normalize_server(string(&value["url"])?)?;
                let old = self.store.config.clone();
                if self.store.identity().pending.is_some()
                    || self.store.identity().pending_reset.is_some()
                {
                    bail!("请先完成或取消当前待处理的设备操作");
                }
                match mode {
                    "add" | "edit" => {
                        if mode == "add" && self.store.config.servers.contains(&url) {
                            bail!("服务器已存在");
                        }
                        self.store.ensure_identity(&url)?;
                        // Configure the target identity without changing the active connection.
                        let active = self.store.config.server.clone();
                        self.store.config.server = url.clone();
                        let result = (|| -> Result<()> {
                            self.store
                                .select_device_id(value["reuseId"].as_bool().unwrap_or(false))?;
                            self.store
                                .set_sender_key(None, value["reuseKey"].as_bool().unwrap_or(false))
                        })();
                        self.store.config.server = active.clone();
                        if let Err(error) = result {
                            if active == url {
                                self.disconnect();
                            }
                            return Err(error);
                        }
                        if mode == "edit" {
                            let from = normalize_server(string(&value["oldURL"])?)?;
                            if !self.store.config.servers.contains(&from) {
                                bail!("服务器不存在");
                            }
                            self.store.config.servers.retain(|s| s != &from);
                            if self.store.config.server == from {
                                self.store.config.server = url.clone();
                            }
                        }
                        if !self.store.config.servers.contains(&url) {
                            self.store.config.servers.push(url);
                        }
                    }
                    "select" => {
                        if !self.store.config.servers.contains(&url) {
                            bail!("服务器不存在");
                        }
                        self.store.config.server = url;
                    }
                    "delete" => {
                        if self.store.config.servers.len() <= 1 {
                            bail!("至少保留一个服务器");
                        }
                        self.store.config.servers.retain(|s| s != &url);
                        if self.store.config.server == url {
                            self.store.config.server = self.store.config.servers[0].clone();
                        }
                    }
                    _ => bail!("操作无效"),
                }
                if let Err(error) = self.store.save_config() {
                    self.store.config = old;
                    self.disconnect();
                    return Err(error);
                }
                if self.store.config.server != old.server
                    || (mode == "edit" && value["oldURL"] == old.server)
                {
                    self.connect().await;
                }
            }
            "sender-key-info" => return Ok(json!(self.store.sender_key())),
            "regenerate-id" => {
                let item = self.store.identity();
                if item.bound
                    || item.devices.len() > 1
                    || item.pending.is_some()
                    || item.pending_reset.is_some()
                {
                    bail!("请先解除绑定或完成待处理的设备操作");
                }
                self.store.update_secrets(|store| {
                    store.identity_mut().reset();
                    Ok(())
                })?;
                self.connect().await;
            }
            "device-manage" => {
                let action = string(&value)?;
                if !["reset_rsa", "reset_all", "unregister"].contains(&action) {
                    bail!("设备操作无效");
                }
                if self.store.identity().pending.is_some() {
                    bail!("请先完成或取消待处理的绑定");
                }
                if let Some(pending) = &self.store.identity().pending_reset {
                    if pending.action != action {
                        bail!("上次操作尚未确认，请重试同一种设备操作");
                    }
                } else {
                    if !self.store.identity().registered {
                        bail!("请先同步当前服务器");
                    }
                    let item = self.store.identity();
                    let (public_key, private_key) = if action == "unregister" {
                        (String::new(), String::new())
                    } else {
                        crypto::key_pair()?
                    };
                    let pending = PendingReset {
                        action: action.into(),
                        device_key: item.device_key.clone(),
                        expected_public_key: item.public_key.clone(),
                        new_device_key: if action == "reset_all" {
                            crypto::device_id()
                        } else {
                            item.device_key.clone()
                        },
                        public_key,
                        private_key,
                    };
                    self.store.update_secrets(|store| {
                        store.identity_mut().pending_reset = Some(pending);
                        Ok(())
                    })?;
                }
                self.disconnect();
                self.finish_reset().await?;
                if action != "unregister" {
                    self.connect().await;
                }
            }
            "copy-info" => {
                let state = self.state_for_copy();
                let text = match string(&value)? {
                    "webhook" => format!("{}/webhook/{}", self.store.config.server, state),
                    "key" => state,
                    "endpoint" => self.store.identity().endpoint_id.clone(),
                    _ => bail!("复制内容无效"),
                };
                if text.is_empty() {
                    bail!("请先同步连接");
                }
                self.app.clipboard().write_text(text)?;
            }
            "copy-message" => {
                let entry = self.store.entry(string(&value)?)?;
                self.app
                    .clipboard()
                    .write_text(format!("{}\n{}", entry.title, entry.body).trim())?;
            }
            "clear-messages" => {
                let ids = self
                    .store
                    .entries()
                    .iter()
                    .map(|e| crate::client::notification_id(&e.id))
                    .collect();
                self.store.clear_messages()?;
                let _ = self.app.notification().remove_active(ids);
            }
            "test-push" => {
                let body = string(&value)?;
                let payload: Value =
                    serde_json::from_str(body).context("请输入有效的 JSON 消息")?;
                if !payload.is_object() {
                    bail!("消息内容必须是 JSON 对象");
                }
                self.api(
                    &format!("/push/{}", self.state_for_copy()),
                    Some(payload),
                    None,
                )
                .await?;
            }
            _ => bail!("操作无效"),
        }
        Ok(json!(true))
    }
    fn state_for_copy(&self) -> String {
        let item = self.store.identity();
        if item.push_key.is_empty() {
            item.device_key.clone()
        } else {
            item.push_key.clone()
        }
    }
    pub(crate) async fn finish_reset(&mut self) -> Result<()> {
        let Some(pending) = self.store.identity().pending_reset.clone() else {
            return Ok(());
        };
        self.api("/device/manage", Some(json!({"action":pending.action,"device_key":pending.device_key,"expected_public_key":pending.expected_public_key,"new_device_key":pending.new_device_key,"public_key":pending.public_key})), Some(&pending.device_key)).await?;
        self.store.update_secrets(|store| {
            complete_identity_reset(store.identity_mut(), pending);
            Ok(())
        })
    }
}

pub fn notification_body(body: &str) -> String {
    let summary = body.split("--- 完整数据 ---").next().unwrap_or(body).trim();
    let text = match serde_json::from_str::<Value>(summary) {
        Ok(Value::Object(fields)) => fields
            .iter()
            .map(|(key, value)| {
                format!(
                    "{}: {}",
                    key,
                    value
                        .as_str()
                        .map(String::from)
                        .unwrap_or_else(|| value.to_string())
                )
            })
            .collect::<Vec<_>>()
            .join("\n"),
        _ => summary.to_string(),
    };
    text.chars().take(2000).collect()
}

fn complete_identity_reset(item: &mut crate::store::Identity, pending: PendingReset) {
    if pending.action == "unregister" {
        item.reset();
    } else {
        item.device_key = pending.new_device_key;
        item.public_key = pending.public_key;
        item.private_key = pending.private_key;
        if pending.action == "reset_all" {
            item.independent_device_key = item.device_key.clone();
            item.reuse_common_id = false;
            item.history_scope = uuid::Uuid::new_v4().to_string();
            item.bound = false;
            item.push_key.clear();
            item.devices.clear();
        }
    }
    item.pending_reset = None;
}
#[cfg(test)]
mod tests {
    use super::*;
    fn identity() -> crate::store::Identity {
        serde_json::from_value(json!({"deviceKey":"original","publicKey":"public-old","privateKey":"private-old","historyScope":"scope","registered":true,"pushKey":"shared","bound":true,"endpointID":"pc-abc123","devices":[{},{}],"pendingReset":null})).unwrap()
    }
    fn pending(action: &str) -> PendingReset {
        PendingReset {
            action: action.into(),
            device_key: "original".into(),
            expected_public_key: "public-old".into(),
            new_device_key: if action == "reset_all" {
                "new"
            } else {
                "original"
            }
            .into(),
            public_key: "public-new".into(),
            private_key: "private-new".into(),
        }
    }
    #[test]
    fn resetting_rsa_preserves_routing_while_reset_all_starts_independent_identity() {
        let mut rsa = identity();
        complete_identity_reset(&mut rsa, pending("reset_rsa"));
        assert_eq!(rsa.device_key, "original");
        assert_eq!(rsa.push_key, "shared");
        assert_eq!(rsa.history_scope, "scope");
        assert!(rsa.bound);
        assert_eq!(rsa.private_key, "private-new");
        assert_eq!(rsa.public_key, "public-new");
        let mut all = identity();
        complete_identity_reset(&mut all, pending("reset_all"));
        assert_eq!(all.device_key, "new");
        assert_eq!(all.independent_device_key, "new");
        assert!(!all.bound);
        assert!(all.push_key.is_empty());
        assert_ne!(all.history_scope, "scope");
        assert!(all.pending_reset.is_none());
    }
    #[test]
    fn unregister_preserves_local_private_key_and_disables_registration() {
        let mut item = identity();
        complete_identity_reset(&mut item, pending("unregister"));
        assert_eq!(item.private_key, "private-old");
        assert_eq!(item.public_key, "public-old");
        assert_ne!(item.device_key, "original");
        assert!(!item.registered);
        assert!(item.devices.is_empty());
        assert!(item.pending_reset.is_none());
    }
}
