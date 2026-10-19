use crate::{
    crypto,
    store::{Identity, Store},
};
use anyhow::{bail, Result};
use serde::{Deserialize, Serialize};

#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CommonInfo {
    pub device_id: String,
    pub sender_key: String,
}
impl Default for CommonInfo {
    fn default() -> Self {
        Self {
            device_id: crypto::device_id(),
            sender_key: crypto::sender_key(),
        }
    }
}
pub fn validate_sender_key(value: &str, allow_empty: bool) -> Result<String> {
    if !(allow_empty && value.is_empty())
        && (value.len() != 64 || !value.bytes().all(|c| c.is_ascii_hexdigit()))
    {
        bail!("密钥必须是 64 位十六进制字符");
    }
    Ok(value.to_ascii_lowercase())
}
impl Store {
    pub fn sender_key(&self) -> &str {
        let item = self.identity();
        if item.reuse_common_sender_key {
            &self.common.sender_key
        } else {
            &item.sender_key
        }
    }
    pub fn set_common_info(&mut self, device_id: &str, sender_key: &str) -> Result<()> {
        if device_id.len() != 32
            || !device_id
                .bytes()
                .all(|c| c.is_ascii_alphanumeric() || c == b'_' || c == b'-')
        {
            bail!("公用 ID 必须是 32 位字母、数字、下划线或短横线");
        }
        let sender_key = validate_sender_key(sender_key, false)?;
        self.update_secrets(|store| {
            store.common = CommonInfo {
                device_id: device_id.into(),
                sender_key,
            };
            // Existing server IDs are snapshots. Only sender keys follow the shared value.
            Ok(())
        })
    }
    pub fn set_sender_key(&mut self, key: Option<&str>, reuse_common: bool) -> Result<()> {
        let key = key.map(|key| validate_sender_key(key, true)).transpose()?;
        self.update_secrets(|store| {
            let previous = store.sender_key().to_string();
            let item = store.identity_mut();
            item.sender_key = if reuse_common {
                String::new()
            } else {
                key.unwrap_or(previous)
            };
            item.reuse_common_sender_key = reuse_common;
            Ok(())
        })
    }
    pub fn select_device_id(&mut self, reuse_common: bool) -> Result<bool> {
        self.update_secrets(|store| {
            let common_id = store.common.device_id.clone();
            select_id(store.identity_mut(), &common_id, reuse_common)
        })
    }
    pub fn update_secrets<T>(&mut self, update: impl FnOnce(&mut Self) -> Result<T>) -> Result<T> {
        let old_common = self.common.clone();
        let old_identities = self.identities.clone();
        let result = update(self).and_then(|result| {
            self.save_identities()?;
            Ok(result)
        });
        if result.is_err() {
            self.common = old_common;
            self.identities = old_identities;
        }
        result
    }
}
fn select_id(item: &mut Identity, common_id: &str, reuse_common: bool) -> Result<bool> {
    let independent = if item.independent_device_key.is_empty() {
        if item.reuse_common_id {
            crypto::device_id()
        } else {
            item.device_key.clone()
        }
    } else {
        item.independent_device_key.clone()
    };
    let next = if reuse_common {
        common_id
    } else {
        &independent
    };
    let changed = item.device_key != next;
    if changed {
        if item.bound || item.devices.len() > 1 {
            bail!("请先解除当前服务器的设备绑定，再切换 ID");
        }
        if item.pending.is_some() || item.pending_reset.is_some() {
            bail!("请先完成或取消待处理的绑定");
        }
        item.device_key = next.into();
        item.registered = false;
        item.history_scope = uuid::Uuid::new_v4().to_string();
        item.push_key.clear();
        item.endpoint_id.clear();
        item.devices.clear();
    }
    item.independent_device_key = independent;
    item.reuse_common_id = reuse_common;
    Ok(changed)
}
#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    fn identity() -> Identity {
        // Synthetic identity: switching IDs must retain its RSA material.
        serde_json::from_value(json!({"deviceKey":"independent","publicKey":"public-test","privateKey":"private-test","historyScope":"old-scope","registered":true,"pushKey":"independent","bound":false,"endpointID":"pc-1","devices":[],"pendingReset":null})).unwrap()
    }
    #[test]
    fn switching_ids_preserves_private_id_keys_and_starts_new_revision_scope() {
        let mut item = identity();
        assert!(select_id(&mut item, "common", true).unwrap());
        assert_eq!(item.device_key, "common");
        assert_eq!(item.independent_device_key, "independent");
        assert_eq!(item.private_key, "private-test");
        assert!(!item.registered);
        assert!(item.push_key.is_empty());
        assert!(item.endpoint_id.is_empty());
        assert_ne!(item.history_scope, "old-scope");
        // Changing the common ID does not rewrite the saved server ID until explicitly applied.
        assert_eq!(item.device_key, "common");
        assert!(select_id(&mut item, "new-common", true).unwrap());
        assert_eq!(item.device_key, "new-common");
        assert!(select_id(&mut item, "new-common", false).unwrap());
        assert_eq!(item.device_key, "independent");
    }
    #[test]
    fn bound_devices_and_pending_bindings_cannot_switch_id() {
        let mut item = identity();
        item.bound = true;
        assert!(select_id(&mut item, "common", true).is_err());
        assert_eq!(item.device_key, "independent");
        item.bound = false;
        item.devices = vec![json!({}), json!({})];
        assert!(select_id(&mut item, "common", true).is_err());
        item.devices.clear();
        item.pending=Some(serde_json::from_value(json!({"requestID":"request","oldKey":"old","newKey":"new","mobileKey":"mobile","confirmName":"phone","expiresAt":1})).unwrap());
        assert!(select_id(&mut item, "common", true).is_err());
    }
}
