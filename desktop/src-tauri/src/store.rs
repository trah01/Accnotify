use crate::{
    common::CommonInfo,
    crypto,
    policy::{validate_rules, Rules},
};
use anyhow::{bail, Context, Result};
use rand::{rngs::OsRng, RngCore};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    collections::BTreeMap,
    fs::{self, OpenOptions},
    io::Write,
    path::{Path, PathBuf},
};
use uuid::Uuid;

#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Config {
    pub server: String,
    pub name: String,
    pub rules: Rules,
    pub auto_start: bool,
    pub servers: Vec<String>,
    pub theme_mode: String,
    pub theme_color: String,
    pub official_consent: bool,
}
impl Default for Config {
    fn default() -> Self {
        Self {
            server: "https://an.trah.cn".into(),
            name: hostname::get()
                .unwrap_or_default()
                .to_string_lossy()
                .chars()
                .take(256)
                .collect(),
            rules: Rules::default(),
            auto_start: false,
            servers: vec!["https://an.trah.cn".into()],
            theme_mode: "system".into(),
            theme_color: "white".into(),
            official_consent: false,
        }
    }
}
#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Pending {
    #[serde(rename = "requestID")]
    pub request_id: String,
    pub old_key: String,
    pub new_key: String,
    pub mobile_key: String,
    pub confirm_name: String,
    pub expires_at: i64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub code: Option<String>,
}
#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PendingReset {
    pub action: String,
    pub device_key: String,
    pub expected_public_key: String,
    pub new_device_key: String,
    pub public_key: String,
    pub private_key: String,
}
#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Identity {
    pub device_key: String,
    pub public_key: String,
    pub private_key: String,
    pub history_scope: String,
    pub registered: bool,
    #[serde(default)]
    pub push_key: String,
    pub bound: bool,
    #[serde(default, rename = "endpointID")]
    pub endpoint_id: String,
    #[serde(default)]
    pub devices: Vec<Value>,
    #[serde(default)]
    pub sender_key: String,
    #[serde(default)]
    pub reuse_common_sender_key: bool,
    #[serde(default)]
    pub reuse_common_id: bool,
    #[serde(default)]
    pub independent_device_key: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub pending: Option<Pending>,
    pub pending_reset: Option<PendingReset>,
}
impl Identity {
    fn new() -> Result<Self> {
        let (public_key, private_key) = crypto::key_pair()?;
        Ok(Self {
            device_key: crypto::device_id(),
            public_key,
            private_key,
            history_scope: Uuid::new_v4().to_string(),
            registered: false,
            push_key: String::new(),
            bound: false,
            endpoint_id: String::new(),
            devices: vec![],
            sender_key: String::new(),
            reuse_common_sender_key: false,
            reuse_common_id: false,
            independent_device_key: String::new(),
            pending: None,
            pending_reset: None,
        })
    }
    pub fn reset(&mut self) {
        self.device_key = crypto::device_id();
        self.independent_device_key = self.device_key.clone();
        self.reuse_common_id = false;
        self.history_scope = Uuid::new_v4().to_string();
        self.registered = false;
        self.push_key.clear();
        self.bound = false;
        self.endpoint_id.clear();
        self.devices.clear();
        self.pending = None;
    }
}
#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Entry {
    pub id: String,
    pub sequence: u64,
    pub deleted: bool,
    pub received: i64,
    #[serde(rename = "deliveryID")]
    pub delivery_id: String,
    pub title: String,
    pub body: String,
    pub group: String,
    pub url: String,
    pub image: String,
    pub server: String,
}
impl Entry {
    fn clear_content(&mut self) {
        self.deleted = true;
        self.title.clear();
        self.body.clear();
        self.group.clear();
        self.url.clear();
        self.image.clear();
    }
}
type History = BTreeMap<String, BTreeMap<String, Entry>>;
pub struct Store {
    pub(crate) root: PathBuf,
    secret: [u8; 32],
    pub config: Config,
    pub identities: BTreeMap<String, Identity>,
    pub common: CommonInfo,
    history: History,
}
fn read<T: serde::de::DeserializeOwned + Default>(path: &Path) -> Result<T> {
    match fs::read(path) {
        Ok(data) => {
            serde_json::from_slice(&data).context("本地数据无法读取，请先备份数据后检查文件")
        }
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(T::default()),
        Err(e) => Err(e).context("本地数据无法读取，请先备份数据后检查文件"),
    }
}
fn atomic(root: &Path, name: &str, value: &impl Serialize) -> Result<()> {
    let target = root.join(name);
    let temp = root.join(format!("{name}.tmp"));
    let mut options = OpenOptions::new();
    options.create(true).write(true).truncate(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let mut file = options.open(&temp)?;
    file.write_all(&serde_json::to_vec(value)?)?;
    file.sync_all()?;
    fs::rename(temp, target).context("本地数据保存失败")?;
    #[cfg(unix)]
    std::fs::File::open(root)?.sync_all()?;
    Ok(())
}
impl Store {
    pub fn load(root: PathBuf) -> Result<Self> {
        fs::create_dir_all(&root)?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&root, fs::Permissions::from_mode(0o700))?;
        }
        let vault = keyring::Entry::new("cn.trah.accnotify.desktop", "desktop-client-storage-key")
            .context("无法访问系统密钥存储")?;
        let secret = match vault.get_password() {
            Ok(value) => {
                let bytes = hex::decode(value).context("系统密钥格式无效")?;
                bytes
                    .try_into()
                    .map_err(|_| anyhow::anyhow!("系统密钥格式无效"))?
            }
            Err(keyring::Error::NoEntry) if !root.join("identities-v2.json").exists() => {
                let mut key = [0u8; 32];
                OsRng.fill_bytes(&mut key);
                vault
                    .set_password(&hex::encode(key))
                    .context("无法保存系统密钥，请允许客户端使用系统密钥存储")?;
                key
            }
            Err(_) => bail!("无法读取系统密钥，请允许客户端访问密钥存储；不要删除本地数据"),
        };
        let mut store = Self {
            config: read(&root.join("settings.json"))?,
            identities: BTreeMap::new(),
            common: CommonInfo::default(),
            history: read(&root.join("history.json"))?,
            root,
            secret,
        };
        let current = store.root.join("identities-v2.json");
        let mut has_common = false;
        if current.exists() {
            let sealed: Value = read(&current)?;
            if sealed["version"] != 1 {
                bail!("本地身份版本不支持");
            }
            if let Some(value) = sealed["common"].as_str() {
                store.common = serde_json::from_slice(&crypto::unseal(&store.secret, value)?)
                    .context("公用信息格式无效")?;
                has_common = true;
            }
            let entries = sealed["identities"]
                .as_object()
                .context("本地身份格式无效")?;
            for (server, value) in entries {
                let bytes =
                    crypto::unseal(&store.secret, value.as_str().context("本地身份格式无效")?)?;
                store.identities.insert(
                    server.clone(),
                    serde_json::from_slice(&bytes).context("本地身份格式无效")?,
                );
            }
        }
        validate_rules(serde_json::to_value(&store.config.rules)?)?;
        let server = store.config.server.clone();
        store.ensure_identity(&server)?;
        if !has_common {
            store.save_identities()?;
        }
        Ok(store)
    }
    pub fn ensure_identity(&mut self, server: &str) -> Result<()> {
        if !self.identities.contains_key(server) {
            self.identities.insert(server.to_string(), Identity::new()?);
            self.save_identities()?;
        }
        Ok(())
    }
    pub fn identity(&self) -> &Identity {
        &self.identities[&self.config.server]
    }
    pub fn identity_mut(&mut self) -> &mut Identity {
        self.identities
            .get_mut(&self.config.server)
            .expect("active identity")
    }
    pub fn save_identities(&self) -> Result<()> {
        let mut sealed = BTreeMap::new();
        for (server, identity) in &self.identities {
            sealed.insert(
                server.clone(),
                crypto::seal(&self.secret, &serde_json::to_vec(identity)?)?,
            );
        }
        atomic(
            &self.root,
            "identities-v2.json",
            &json!({"version":1,"identities":sealed,"common":crypto::seal(&self.secret,&serde_json::to_vec(&self.common)?)?}),
        )
    }
    pub fn save_config(&self) -> Result<()> {
        atomic(&self.root, "settings.json", &self.config)
    }
    pub fn entries(&self) -> Vec<Entry> {
        let mut entries: Vec<_> = self
            .history
            .values()
            .flat_map(|bucket| bucket.values())
            .filter(|e| !e.deleted)
            .cloned()
            .collect();
        entries.sort_by_key(|e| std::cmp::Reverse(e.received));
        entries
    }
    pub fn apply(&mut self, delivery: &Value, payload: &Value) -> Result<Option<Entry>> {
        let delivery_id = delivery["id"]
            .as_str()
            .filter(|s| s.chars().count() <= 256)
            .context("消息编号无效")?;
        let sequence = delivery["sequence"]
            .as_u64()
            .filter(|n| *n > 0 && *n <= 9007199254740991)
            .context("消息编号无效")?;
        if !payload.is_object() {
            bail!("消息字段无效");
        }
        for field in ["title", "body", "markdown", "group", "url", "image"] {
            if !payload[field].is_null() && !payload[field].is_string() {
                bail!("消息字段无效");
            }
        }
        let external = match payload.get("id").filter(|v| !v.is_null()) {
            Some(v) => v
                .as_str()
                .filter(|s| s.len() <= 256)
                .context("消息 ID 无效")?,
            None => "",
        };
        let removing =
            [json!(true), json!(1), json!("1"), json!("true")].contains(&payload["delete"]);
        if removing && external.is_empty() {
            bail!("删除消息缺少 ID");
        }
        let id = format!(
            "{}:{}",
            self.identity().history_scope,
            if external.is_empty() {
                format!("delivery:{delivery_id}")
            } else {
                format!("external:{external}")
            }
        );
        let mut next = self.history.clone();
        let bucket = next.entry(self.config.server.clone()).or_default();
        if bucket.get(&id).is_some_and(|e| e.sequence >= sequence) {
            return Ok(None);
        }
        let text = |key: &str| payload[key].as_str().unwrap_or("").to_string();
        let entry = Entry {
            server: self.config.server.clone(),
            id: id.clone(),
            sequence,
            deleted: removing,
            received: chrono::Local::now().timestamp_millis(),
            delivery_id: delivery_id.into(),
            title: payload["title"]
                .as_str()
                .filter(|s| !s.is_empty())
                .unwrap_or("Accnotify")
                .into(),
            body: payload["markdown"]
                .as_str()
                .filter(|s| !s.is_empty())
                .unwrap_or_else(|| payload["body"].as_str().unwrap_or(""))
                .into(),
            group: text("group"),
            url: text("url"),
            image: text("image"),
        };
        bucket.insert(id, entry.clone());
        atomic(&self.root, "history.json", &next)?;
        self.history = next;
        Ok(Some(entry))
    }
    pub fn remove(&mut self, id: &str) -> Result<()> {
        let mut next = self.history.clone();
        for bucket in next.values_mut() {
            if let Some(entry) = bucket.get_mut(id) {
                entry.clear_content();
            }
        }
        atomic(&self.root, "history.json", &next)?;
        self.history = next;
        Ok(())
    }
    pub fn clear_messages(&mut self) -> Result<()> {
        let mut next = self.history.clone();
        for entry in next.values_mut().flat_map(|b| b.values_mut()) {
            entry.clear_content();
        }
        // Keep revision tombstones so a delayed delivery cannot restore cleared messages.
        atomic(&self.root, "history.json", &next)?;
        self.history = next;
        Ok(())
    }
    pub fn entry(&self, id: &str) -> Result<Entry> {
        self.entries()
            .into_iter()
            .find(|e| e.id == id)
            .context("消息不存在")
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn persisted_revisions_and_combined_history_keep_server_identity_separate() {
        let root = std::env::temp_dir().join(format!("accnotify-test-{}", Uuid::new_v4()));
        fs::create_dir_all(&root).unwrap();
        let mut identities = BTreeMap::new();
        identities.insert("a".into(), Identity::new().unwrap());
        identities.insert("b".into(), Identity::new().unwrap());
        let mut config = Config::default();
        config.server = "a".into();
        let mut store = Store {
            root: root.clone(),
            secret: [7; 32],
            config,
            identities,
            common: CommonInfo::default(),
            history: BTreeMap::new(),
        };
        let first = json!({"id":"1","sequence":1});
        let body = json!({"id":"job","body":"first"});
        assert!(store.apply(&first, &body).unwrap().is_some());
        assert!(fs::read_to_string(root.join("history.json"))
            .unwrap()
            .contains("first"));
        assert!(store.apply(&first, &body).unwrap().is_none());
        store
            .apply(
                &json!({"id":"2","sequence":2}),
                &json!({"id":"job","body":"second"}),
            )
            .unwrap();
        store.config.server = "b".into();
        store.apply(&first, &body).unwrap();
        store.config.server = "a".into();
        store
            .apply(
                &json!({"id":"3","sequence":3}),
                &json!({"id":"job","delete":"1"}),
            )
            .unwrap();
        assert_eq!(store.entries().len(), 1);
        assert_eq!(store.entries()[0].server, "b");
        assert!(store.apply(&first, &body).unwrap().is_none());
        store.config.server = "b".into();
        assert_eq!(store.entries().len(), 1);
        store.clear_messages().unwrap();
        assert!(store.entries().is_empty());
        let cleared = fs::read_to_string(root.join("history.json")).unwrap();
        assert!(!cleared.contains("first") && !cleared.contains("second"));
        assert!(store.apply(&first, &body).unwrap().is_none());
        assert!(store
            .apply(
                &json!({"id":"3","sequence":3}),
                &json!({"id":"job","body":"third"})
            )
            .unwrap()
            .is_some());
        let id = store.entries()[0].id.clone();
        store.config.server = "a".into();
        store.remove(&id).unwrap();
        assert!(store.entries().is_empty());
        // Shared keys follow later changes; per-server IDs and private keys stay independent.
        let common_id = store.common.device_id.clone();
        let private_key = "11".repeat(32);
        store.config.server = "a".into();
        store.set_sender_key(Some(&private_key), false).unwrap();
        store.select_device_id(true).unwrap();
        store.config.server = "b".into();
        store.set_sender_key(None, true).unwrap();
        let new_common_id = crypto::device_id();
        let shared_key = "22".repeat(32);
        store.set_common_info(&new_common_id, &shared_key).unwrap();
        assert_eq!(store.sender_key(), shared_key);
        assert_eq!(store.identities["a"].device_key, common_id);
        assert_eq!(store.identities["a"].sender_key, private_key);
        store.set_sender_key(None, false).unwrap(); // Leaving shared mode retains the effective value.
        store
            .set_common_info(&new_common_id, &"33".repeat(32))
            .unwrap();
        assert_eq!(store.sender_key(), shared_key);
        // A failed save rolls back both the common value and all server-specific changes.
        store.root = root.join("missing");
        assert!(store
            .set_common_info(&crypto::device_id(), &"44".repeat(32))
            .is_err());
        assert_eq!(store.common.device_id, new_common_id);
        assert_eq!(store.common.sender_key, "33".repeat(32));
        store.root = root.clone();
        store.save_identities().unwrap();
        assert!(!fs::read_to_string(root.join("identities-v2.json"))
            .unwrap()
            .contains("PRIVATE KEY"));
        let sealed = fs::read_to_string(root.join("identities-v2.json")).unwrap();
        assert!(!sealed.contains(&store.common.sender_key));
        assert!(!sealed.contains(&private_key));
        let vault: Value = serde_json::from_str(&sealed).unwrap();
        let recovered_common: CommonInfo = serde_json::from_slice(
            &crypto::unseal(&store.secret, vault["common"].as_str().unwrap()).unwrap(),
        )
        .unwrap();
        assert_eq!(recovered_common.device_id, new_common_id);
        let recovered: History = read(&root.join("history.json")).unwrap();
        assert_eq!(recovered["a"].values().filter(|e| !e.deleted).count(), 0);
        fs::remove_dir_all(root).unwrap();
    }
}
