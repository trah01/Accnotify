use crate::{
    crypto, policy,
    store::{Pending, Store},
};
use anyhow::{bail, Context, Result};
use chrono::{Datelike, Local};
use futures_util::{SinkExt, StreamExt};
use serde_json::{json, Value};
use std::{
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, RwLock,
    },
    time::Duration,
};
use tauri::{AppHandle, Emitter};
use tauri_plugin_autostart::ManagerExt as _;
use tauri_plugin_clipboard_manager::ClipboardExt;
use tauri_plugin_dialog::{DialogExt, MessageDialogButtons, MessageDialogKind};
use tauri_plugin_notification::NotificationExt;
use tauri_plugin_opener::OpenerExt;
use tokio::{
    sync::{mpsc, oneshot},
    time::Instant,
};
use tokio_tungstenite::{
    connect_async_with_config,
    tungstenite::{protocol::WebSocketConfig, Message},
    MaybeTlsStream, WebSocketStream,
};
use url::Url;

pub struct Request {
    pub channel: String,
    pub value: Value,
    pub reply: oneshot::Sender<Result<Value, String>>,
}
pub struct Client {
    pub sender: mpsc::Sender<Request>,
    pub visible: Arc<RwLock<Value>>,
    pub busy: Arc<AtomicBool>,
}
type Socket = WebSocketStream<MaybeTlsStream<tokio::net::TcpStream>>;
pub struct Worker {
    pub(crate) app: AppHandle,
    pub(crate) store: Store,
    visible: Arc<RwLock<Value>>,
    busy: Arc<AtomicBool>,
    pub(crate) http: reqwest::Client,
    socket: Option<Socket>,
    retry: Option<Instant>,
    last_frame: Instant,
    pub(crate) connection: String,
    pub(crate) error: String,
    holiday_busy: bool,
}
#[derive(Debug)]
struct ApiError {
    status: u16,
    message: String,
}
impl std::fmt::Display for ApiError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.message)
    }
}
impl std::error::Error for ApiError {}
pub(crate) fn status(error: &anyhow::Error) -> u16 {
    error.downcast_ref::<ApiError>().map_or(0, |e| e.status)
}
pub(crate) fn string(value: &Value) -> Result<&str> {
    value.as_str().context("操作参数无效")
}
pub fn normalize_server(value: &str) -> Result<String> {
    if value.len() > 2048 {
        bail!("服务器地址无效");
    }
    let url = Url::parse(value).context("请输入 HTTP 或 HTTPS 服务器地址")?;
    if !matches!(url.scheme(), "https" | "http")
        || url.host_str().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
    {
        bail!("请输入 HTTP 或 HTTPS 服务器地址");
    }
    Ok(url.to_string().trim_end_matches('/').to_string())
}
impl Worker {
    pub fn new(
        app: AppHandle,
        mut store: Store,
    ) -> Result<(Self, Client, mpsc::Receiver<Request>)> {
        store.config.server = normalize_server(&store.config.server)?;
        store.ensure_identity(&store.config.server.clone())?;
        let (sender, receiver) = mpsc::channel(8);
        let visible = Arc::new(RwLock::new(Value::Null));
        let busy = Arc::new(AtomicBool::new(false));
        let worker = Self {
            app,
            store,
            visible: visible.clone(),
            busy: busy.clone(),
            http: reqwest::Client::builder()
                .timeout(Duration::from_secs(15))
                .redirect(reqwest::redirect::Policy::none())
                .build()?,
            socket: None,
            retry: None,
            last_frame: Instant::now(),
            connection: "未连接".into(),
            error: String::new(),
            holiday_busy: false,
        };
        worker.publish();
        Ok((
            worker,
            Client {
                sender,
                visible,
                busy,
            },
            receiver,
        ))
    }
    fn state(&self) -> Value {
        let item = self.store.identity();
        let server = &self.store.config.server;
        let key = if item.push_key.is_empty() {
            &item.device_key
        } else {
            &item.push_key
        };
        let mut state = serde_json::to_value(&self.store.config).unwrap_or_else(|_| json!({}));
        let object = state.as_object_mut().expect("configuration object");
        for (key, value) in json!({"servers":self.store.config.servers,"hasPendingReset":item.pending_reset.is_some(),"pendingResetAction":item.pending_reset.as_ref().map(|p|p.action.as_str()),"deviceKey":key,"pushLink":format!("{server}/push/{key}"),"endpointID":item.endpoint_id,"devices":item.devices,"bindingHint":item.pending.as_ref().map(|p|p.confirm_name.clone()).unwrap_or_default(),"bound":item.bound,"registered":item.registered,"hasSenderKey":!self.store.sender_key().is_empty(),"reuseCommonId":item.reuse_common_id,"reuseCommonKey":item.reuse_common_sender_key,"hasBindingRequest":item.pending.is_some(),"connection":self.connection,"error":self.error,"busy":self.busy.load(Ordering::SeqCst),"holidayBusy":self.holiday_busy,"history":self.store.entries(),"packaged":!tauri::is_dev(),"version":self.app.package_info().version.to_string(),"platform":crate::updates::platform_label(),"os":std::env::consts::OS,"projectURL":crate::updates::PROJECT_URL,"releasesURL":crate::updates::RELEASES_URL}).as_object().unwrap() { object.insert(key.clone(), value.clone()); }
        state
    }
    fn publish(&self) {
        let state = self.state();
        *self.visible.write().unwrap_or_else(|e| e.into_inner()) = state.clone();
        let _ = self.app.emit("state", state);
    }
    pub(crate) async fn api(
        &self,
        route: &str,
        body: Option<Value>,
        key: Option<&str>,
    ) -> Result<Value> {
        let url = format!("{}{route}", self.store.config.server);
        let mut request = if let Some(body) = body {
            self.http.post(url).json(&body)
        } else {
            self.http.get(url)
        };
        if let Some(key) = key {
            request = request.bearer_auth(key);
        }
        let response = request
            .send()
            .await
            .map_err(|_| anyhow::anyhow!("无法连接服务器，请检查地址和网络"))?;
        let code = response.status().as_u16();
        let value: Value = limited_json(response, 2 * 1024 * 1024)
            .await
            .context("服务器返回的数据无效")?;
        if !(200..300).contains(&code) || value["success"] != true {
            let message = if code == 404 && route.starts_with("/binding/") {
                "服务端尚不支持电脑端，请先升级服务端"
            } else {
                value["error"].as_str().unwrap_or("请求失败")
            };
            return Err(ApiError {
                status: code,
                message: message.into(),
            }
            .into());
        }
        Ok(value)
    }
    pub(crate) fn disconnect(&mut self) {
        self.socket = None;
        self.retry = None;
        self.connection = "未连接".into();
    }
    async fn recover_pending(&mut self) -> Result<()> {
        let Some(pending) = self
            .store
            .identity()
            .pending
            .clone()
            .filter(|p| p.code.is_some())
        else {
            return Ok(());
        };
        let result = match self.api("/binding/status", None, Some(&pending.new_key)).await {
            Ok(state) => {
                if state["bound"] != true || (!pending.mobile_key.is_empty() && state["push_key"] != pending.mobile_key) { bail!("绑定状态不一致，请检查当前手机 ID"); }
                json!({"device_key":pending.new_key,"push_key":state["push_key"]})
            }
            Err(error) if status(&error) == 401 => self.api("/binding/verify", Some(json!({"request_id":pending.request_id,"code":pending.code,"new_device_key":pending.new_key})), Some(&pending.old_key)).await?,
            Err(error) => return Err(error),
        };
        let device_key = result["device_key"]
            .as_str()
            .context("服务器返回的绑定身份无效")?
            .to_string();
        let push_key = result["push_key"]
            .as_str()
            .context("服务器返回的推送 ID 无效")?
            .to_string();
        let previous = self.store.identity().clone();
        let item = self.store.identity_mut();
        item.device_key = device_key.clone();
        item.independent_device_key = device_key;
        item.reuse_common_id = false;
        item.push_key = push_key;
        item.bound = true;
        item.history_scope = uuid::Uuid::new_v4().to_string();
        item.pending = None;
        if let Err(error) = self.store.save_identities() {
            *self.store.identity_mut() = previous;
            return Err(error);
        }
        Ok(())
    }
    async fn connect_inner(&mut self) -> Result<()> {
        if url::Url::parse(&self.store.config.server)?.host_str() == Some("an.trah.cn")
            && !self.store.config.official_consent
        {
            bail!("请先确认官方服务器使用说明");
        }
        if self
            .store
            .identity()
            .pending_reset
            .as_ref()
            .is_some_and(|p| p.action == "unregister")
        {
            bail!("注销结果尚未确认，请重试注销操作");
        }
        self.finish_reset().await?;
        self.recover_pending().await?;
        let current = self.store.identity().clone();
        if let Err(error) = self.api("/register", Some(json!({"device_key":current.device_key,"public_key":current.public_key,"name":self.store.config.name,"platform":"pc"})), None).await {
            if status(&error) != 409 || !current.registered { return Err(error); }
            self.store.identity_mut().reset(); self.store.save_identities()?;
            let replacement = self.store.identity();
            self.api("/register", Some(json!({"device_key":replacement.device_key,"public_key":replacement.public_key,"name":self.store.config.name,"platform":"pc"})), None).await?;
            self.error = "原设备已被移除，现已恢复独立 ID。".into();
        }
        let status = self
            .api(
                "/binding/status",
                None,
                Some(&self.store.identity().device_key),
            )
            .await?;
        let push_key = status["push_key"]
            .as_str()
            .context("服务器返回的推送 ID 无效")?
            .to_string();
        let endpoint_id = status["endpoint_id"]
            .as_str()
            .context("服务器返回的设备标识无效")?
            .to_string();
        let devices = status["devices"]
            .as_array()
            .context("服务器返回的设备列表无效")?
            .clone();
        let item = self.store.identity_mut();
        item.registered = true;
        item.push_key = push_key;
        item.bound = status["bound"].as_bool().unwrap_or(false);
        item.endpoint_id = endpoint_id;
        item.devices = devices;
        self.store.save_identities()?;
        let mut url = Url::parse(&format!("{}/ws", self.store.config.server))?;
        let scheme = if url.scheme() == "https" { "wss" } else { "ws" };
        url.set_scheme(scheme)
            .map_err(|_| anyhow::anyhow!("服务器地址无效"))?;
        url.query_pairs_mut()
            .append_pair("key", &self.store.identity().device_key);
        let config = WebSocketConfig::default()
            .max_message_size(Some(12 * 1024 * 1024))
            .max_frame_size(Some(12 * 1024 * 1024));
        let (socket, _) = tokio::time::timeout(
            Duration::from_secs(15),
            connect_async_with_config(url.as_str(), Some(config), false),
        )
        .await
        .map_err(|_| anyhow::anyhow!("连接超时，正在重试"))?
        .map_err(|_| anyhow::anyhow!("连接失败，正在重试"))?;
        self.socket = Some(socket);
        self.last_frame = Instant::now();
        self.connection = "已连接".into();
        Ok(())
    }
    pub(crate) async fn connect(&mut self) {
        self.disconnect();
        self.connection = "连接中".into();
        self.error.clear();
        self.publish();
        if let Err(error) = self.connect_inner().await {
            self.connection = "连接失败".into();
            self.error = error.to_string();
            if !matches!(status(&error), 400 | 404)
                && self.store.identity().pending_reset.is_none()
                && !(url::Url::parse(&self.store.config.server)
                    .is_ok_and(|u| u.host_str() == Some("an.trah.cn"))
                    && !self.store.config.official_consent)
            {
                self.retry = Some(Instant::now() + Duration::from_secs(10));
            }
        }
        self.publish();
    }
    fn retry_connection(&mut self) {
        self.socket = None;
        self.connection = "连接中".into();
        self.error = "连接断开，正在重试".into();
        self.retry = Some(Instant::now() + Duration::from_secs(5));
        self.publish();
    }
    async fn message(&mut self, text: &str) -> Result<()> {
        let delivery: Value = serde_json::from_str(text).context("消息格式无效")?;
        if delivery["type"] == "ping" {
            self.send(json!({"type":"pong"})).await?;
            return Ok(());
        }
        if delivery["type"] != "message" {
            return Ok(());
        }
        let identity = self.store.identity();
        let payload = match crypto::decrypt(
            &delivery["data"],
            &identity.private_key,
            self.store.sender_key(),
        ) {
            Ok(payload) => payload,
            Err(error) => {
                if policy::should_notify(&self.store.config.rules) {
                    let _ = self.app.notification().builder()
                        .id(notification_id(&format!("decrypt-error:{}", self.store.config.server)))
                        .title("消息无法解密")
                        .body("请检查当前服务器的发送端加密密钥，确认与发送脚本一致；或重新同步设备密钥。")
                        .show();
                }
                return Err(error);
            }
        };
        let applied = self.store.apply(&delivery, &payload)?;
        // Acknowledge only after the history revision has been durably persisted.
        self.send(json!({"type":"ack","id":delivery["id"]})).await?;
        self.error.clear();
        if let Some(entry) = applied {
            let notice_id = notification_id(&entry.id);
            let _ = self.app.notification().remove_active(vec![notice_id]);
            if !entry.deleted && policy::should_notify(&self.store.config.rules) {
                let mut notice = self
                    .app
                    .notification()
                    .builder()
                    .id(notice_id)
                    .title(&entry.title)
                    .body(crate::operations::notification_body(&entry.body))
                    .extra("messageID", &entry.id);
                if !entry.url.is_empty() {
                    notice = notice
                        .action_type_id("message-link")
                        .extra("url", &entry.url);
                }
                if let Err(error) = notice.show() {
                    self.error = format!("消息已保存，但系统通知未能显示：{error}");
                }
            }
        }
        self.publish();
        Ok(())
    }
    async fn send(&mut self, value: Value) -> Result<()> {
        let socket = self.socket.as_mut().context("连接已断开")?;
        tokio::time::timeout(
            Duration::from_secs(10),
            socket.send(Message::Text(value.to_string().into())),
        )
        .await
        .context("连接超时")?
        .context("连接已断开")
    }
    pub async fn run(mut self, mut receiver: mpsc::Receiver<Request>) {
        if self.store.identity().registered {
            self.connect().await;
        }
        loop {
            enum Event {
                Request(Option<Request>),
                Frame(Option<Result<Message, tokio_tungstenite::tungstenite::Error>>),
                Tick,
            }
            let event = tokio::select! {
                request = receiver.recv() => Event::Request(request),
                frame = async { match self.socket.as_mut() { Some(socket) => socket.next().await, None => std::future::pending().await } } => Event::Frame(frame),
                _ = tokio::time::sleep(Duration::from_secs(1)) => Event::Tick,
            };
            match event {
                Event::Request(Some(request)) => {
                    self.publish();
                    let result = self
                        .action(&request.channel, request.value)
                        .await
                        .map_err(|e| e.to_string());
                    self.busy.store(false, Ordering::SeqCst);
                    self.holiday_busy = false;
                    let result = result.map(|value| {
                        if request.channel == "save" {
                            self.state()
                        } else {
                            value
                        }
                    });
                    self.publish();
                    let _ = request.reply.send(result);
                }
                Event::Request(None) => break,
                Event::Frame(Some(Ok(Message::Text(text)))) => {
                    self.last_frame = Instant::now();
                    if let Err(error) = self.message(&text).await {
                        self.error = error.to_string();
                        self.publish();
                    }
                }
                Event::Frame(Some(Ok(Message::Ping(_))))
                | Event::Frame(Some(Ok(Message::Pong(_)))) => {
                    self.last_frame = Instant::now();
                }
                Event::Frame(Some(Ok(Message::Close(_))))
                | Event::Frame(None)
                | Event::Frame(Some(Err(_))) => self.retry_connection(),
                Event::Frame(_) => {}
                Event::Tick => {
                    if self.retry.is_some_and(|when| Instant::now() >= when) {
                        self.connect().await;
                    } else if self.socket.is_some()
                        && self.last_frame.elapsed() > Duration::from_secs(75)
                    {
                        self.retry_connection();
                    }
                }
            }
        }
    }
    async fn action(&mut self, channel: &str, value: Value) -> Result<Value> {
        if self.store.identity().pending_reset.is_some()
            && [
                "save",
                "server-manage",
                "select-id",
                "bind-request",
                "bind-verify",
                "bind-cancel",
                "unbind",
                "remove-device",
                "regenerate-id",
            ]
            .contains(&channel)
        {
            bail!("上次设备操作尚未确认，请重试同一种操作");
        }
        match channel {
            "connect" => {
                self.connect().await;
                Ok(Value::Null)
            }
            "save" => {
                let name = value["name"]
                    .as_str()
                    .filter(|s| !s.trim().is_empty() && s.chars().count() <= 256)
                    .context("请输入电脑名称（最多 256 字符）")?
                    .trim()
                    .to_string();
                let server = normalize_server(string(&value["server"])?)?;
                let mut rules = value["rules"].clone();
                rules["calendars"] = serde_json::to_value(&self.store.config.rules.calendars)?;
                let rules = policy::validate_rules(rules)?;
                let auto_start = value["autoStart"].as_bool().context("启动设置无效")?;
                let theme_mode =
                    crate::operations::theme(&value["themeMode"], &["system", "light", "dark"])?;
                let theme_color = crate::operations::theme(
                    &value["themeColor"],
                    &["white", "blue", "green", "red", "pink", "black"],
                )?;
                if auto_start && tauri::is_dev() {
                    bail!("安装电脑端后才能启用开机启动");
                }
                if self
                    .store
                    .identity()
                    .pending
                    .as_ref()
                    .is_some_and(|p| p.code.is_some())
                {
                    bail!("请先完成或取消待确认的绑定");
                }
                if self.store.identity().pending_reset.is_some() {
                    bail!("请先完成待确认的设备操作");
                }
                self.store.ensure_identity(&server)?;
                let previous = self.store.config.clone();
                if !tauri::is_dev() {
                    if auto_start {
                        self.app.autolaunch().enable()?;
                    } else {
                        self.app.autolaunch().disable()?;
                    }
                }
                if !self.store.config.servers.contains(&server) {
                    self.store.config.servers.push(server.clone());
                }
                self.store.config.name = name;
                self.store.config.server = server;
                self.store.config.rules = rules;
                self.store.config.auto_start = auto_start;
                self.store.config.theme_mode = theme_mode;
                self.store.config.theme_color = theme_color;
                if let Err(error) = self.store.save_config() {
                    self.store.config = previous;
                    if !tauri::is_dev() {
                        let _ = if self.store.config.auto_start {
                            self.app.autolaunch().enable()
                        } else {
                            self.app.autolaunch().disable()
                        };
                    }
                    return Err(error);
                }
                self.connect().await;
                Ok(self.state())
            }
            "server-manage" | "device-manage" | "sender-key-info" | "copy-info"
            | "copy-message" | "clear-messages" | "regenerate-id" | "accept-consent"
            | "test-push" => self.extra_action(channel, value).await,
            "copy" => {
                self.app
                    .clipboard()
                    .write_text(string(&self.state()["pushLink"])?)?;
                Ok(json!(true))
            }
            "copy-target" => {
                let item = self.store.identity();
                let endpoint = value
                    .as_str()
                    .filter(|s| !s.is_empty())
                    .unwrap_or(&item.endpoint_id);
                if endpoint.is_empty() {
                    bail!("请先同步连接");
                }
                if value.as_str().filter(|s| !s.is_empty()).is_some()
                    && !item.devices.iter().any(|d| d["endpoint_id"] == endpoint)
                {
                    bail!("设备不存在");
                }
                let mut url = Url::parse(string(&self.state()["pushLink"])?)?;
                url.query_pairs_mut().append_pair("target", endpoint);
                self.app.clipboard().write_text(url.to_string())?;
                Ok(json!(true))
            }
            "sender-key" => {
                let (key, reuse) = if value.is_string() {
                    (value.as_str(), false)
                } else {
                    (
                        value["key"].as_str(),
                        value["reuseCommonKey"].as_bool().context("密钥设置无效")?,
                    )
                };
                self.store.set_sender_key(key, reuse)?;
                Ok(json!(true))
            }
            "common-info" => Ok(serde_json::to_value(&self.store.common)?),
            "common-save" => {
                self.store
                    .set_common_info(string(&value["deviceId"])?, string(&value["senderKey"])?)?;
                Ok(json!(true))
            }
            "generate-secret" => match string(&value)? {
                "id" => Ok(json!(crypto::device_id())),
                "key" => Ok(json!(crypto::sender_key())),
                _ => bail!("操作无效"),
            },
            "copy-secret" => {
                let text = string(&value["text"])?;
                match string(&value["kind"])? {
                    "key" => {
                        crate::common::validate_sender_key(text, false)?;
                    }
                    "id" if text.len() == 32
                        && text
                            .bytes()
                            .all(|c| c.is_ascii_alphanumeric() || c == b'_' || c == b'-') => {}
                    _ => bail!("复制内容无效"),
                }
                self.app.clipboard().write_text(text)?;
                Ok(json!(true))
            }
            "select-id" => {
                let reuse = value.as_bool().context("ID 设置无效")?;
                if self.store.select_device_id(reuse)? {
                    self.connect().await;
                }
                Ok(json!(true))
            }
            "remove-device" => {
                let item = self.store.identity().clone();
                if !item
                    .devices
                    .iter()
                    .any(|d| d["id"] == value && d["is_self"] != true)
                {
                    bail!("请选择其他设备，本机请使用解除绑定");
                }
                self.api(
                    "/binding/remove",
                    Some(json!({"device_id":value})),
                    Some(&item.device_key),
                )
                .await?;
                self.connect().await;
                Ok(json!(true))
            }
            "bind-request" => {
                let item = self.store.identity().clone();
                if !item.registered
                    || item.bound
                    || item.devices.len() > 1
                    || item.pending.as_ref().is_some_and(|p| p.code.is_some())
                {
                    bail!("请先连接服务器并完成或解除已有绑定");
                }
                let link = string(&value)?;
                if link.len() > 2048 {
                    bail!("手机链接无效");
                }
                let mut url = Url::parse(link).context("手机链接无效")?;
                if !url.username().is_empty()
                    || url.password().is_some()
                    || url.fragment().is_some()
                {
                    bail!("请填写同一服务器上的手机 Push 链接");
                }
                let target = url
                    .query_pairs()
                    .find(|(k, _)| k == "target")
                    .map(|(_, v)| v.into_owned())
                    .unwrap_or_default();
                let path = url.path().trim_end_matches('/').to_string();
                let (prefix, key) = path
                    .rsplit_once("/push/")
                    .context("请填写同一服务器上的手机 Push 链接")?;
                if key.len() != 32
                    || !key
                        .bytes()
                        .all(|c| c.is_ascii_alphanumeric() || c == b'_' || c == b'-')
                {
                    bail!("手机链接无效");
                }
                let key = key.to_string();
                url.set_path(prefix);
                url.set_query(None);
                if normalize_server(url.as_str())? != self.store.config.server {
                    bail!("请填写同一服务器上的手机 Push 链接");
                }
                let result = self
                    .api(
                        "/binding/request",
                        Some(json!({"group_key":key,"confirm_endpoint":target})),
                        Some(&item.device_key),
                    )
                    .await?;
                self.store.identity_mut().pending = Some(Pending {
                    request_id: string(&result["request_id"])?.into(),
                    old_key: item.device_key,
                    new_key: crypto::device_id(),
                    mobile_key: key,
                    confirm_name: format!(
                        "{}（{}）",
                        result["confirm_name"].as_str().unwrap_or("确认设备"),
                        result["confirm_endpoint"].as_str().unwrap_or("")
                    ),
                    expires_at: Local::now().timestamp_millis() + 300000,
                    code: None,
                });
                self.store.save_identities()?;
                Ok(json!(true))
            }
            "bind-verify" => {
                let code = string(&value)?;
                if code.len() != 6
                    || !code.bytes().all(|c| c.is_ascii_digit())
                    || self.store.identity().pending.is_none()
                {
                    bail!("请先发送验证码，再输入手机收到的 6 位验证码");
                }
                self.disconnect();
                self.store.identity_mut().pending.as_mut().unwrap().code = Some(code.into());
                self.store.save_identities()?;
                if let Err(error) = self.recover_pending().await {
                    if status(&error) == 400 {
                        self.store.identity_mut().pending.as_mut().unwrap().code = None;
                        self.store.save_identities()?;
                    }
                    self.retry = Some(Instant::now() + Duration::from_secs(10));
                    return Err(error);
                }
                self.connect().await;
                Ok(json!(true))
            }
            "bind-cancel" => {
                if self
                    .store
                    .identity()
                    .pending
                    .as_ref()
                    .is_some_and(|p| p.code.is_some())
                {
                    if let Err(error) = self.recover_pending().await {
                        if status(&error) != 400 {
                            return Err(error);
                        }
                    }
                }
                self.store.identity_mut().pending = None;
                self.store.save_identities()?;
                self.connect().await;
                Ok(json!(true))
            }
            "unbind" => {
                let item = self.store.identity().clone();
                if !item.bound && item.devices.len() <= 1 {
                    bail!("当前电脑为独立 ID");
                }
                self.disconnect();
                if let Err(error) = self
                    .api("/binding/remove", Some(json!({})), Some(&item.device_key))
                    .await
                {
                    if status(&error) != 401 {
                        self.connect().await;
                        return Err(error);
                    }
                }
                self.store.identity_mut().reset();
                self.store.save_identities()?;
                self.connect().await;
                Ok(json!(true))
            }
            "remove-message" => {
                let id = string(&value)?;
                self.store.remove(id)?;
                let _ = self
                    .app
                    .notification()
                    .remove_active(vec![notification_id(id)]);
                Ok(json!(true))
            }
            "open-url" => {
                let url = Url::parse(string(&value)?).context("链接无效")?;
                if !matches!(url.scheme(), "http" | "https")
                    || !url.username().is_empty()
                    || url.password().is_some()
                {
                    bail!("仅支持打开 HTTP 或 HTTPS 链接");
                }
                self.app.opener().open_url(url.to_string(), None::<&str>)?;
                Ok(json!(true))
            }
            "import-calendar" => {
                let path = string(&value)?;
                // Read through a bounded stream, including when a selected file changes size.
                use std::io::Read;
                let mut bytes = vec![];
                std::fs::File::open(path)?
                    .take(128 * 1024 + 1)
                    .read_to_end(&mut bytes)?;
                if bytes.len() > 128 * 1024 {
                    bail!("日历文件过大");
                }
                let calendar = policy::validate_calendar(
                    serde_json::from_slice(&bytes).context("日历文件格式无效")?,
                )?;
                self.save_calendar(calendar)?;
                Ok(json!(true))
            }
            "fetch-calendar" => {
                let year = Local::now().year();
                self.holiday_busy = true;
                self.publish();
                let client = reqwest::Client::builder()
                    .timeout(Duration::from_secs(15))
                    .redirect(reqwest::redirect::Policy::limited(5))
                    .build()?;
                let response = client
                    .get(format!("https://timor.tech/api/holiday/year/{year}"))
                    .header("Accept", "application/json")
                    .header("User-Agent", concat!("Mozilla/5.0 Accnotify/", env!("CARGO_PKG_VERSION")))
                    .send()
                    .await
                    .context("节假日获取失败，请稍后手动重试")?;
                if !response.status().is_success() {
                    bail!(
                        "节假日网站返回错误（{}），请稍后手动重试",
                        response.status().as_u16()
                    );
                }
                let calendar =
                    policy::parse_holiday(limited_json(response, 128 * 1024).await?, year)?;
                self.save_calendar(calendar)?;
                Ok(json!(true))
            }
            _ => bail!("操作无效"),
        }
    }
    fn save_calendar(&mut self, calendar: policy::Calendar) -> Result<()> {
        let previous = self.store.config.rules.calendars.clone();
        self.store
            .config
            .rules
            .calendars
            .retain(|c| c.year != calendar.year);
        self.store.config.rules.calendars.push(calendar);
        if let Err(error) = self.store.save_config() {
            self.store.config.rules.calendars = previous;
            return Err(error);
        }
        Ok(())
    }
}
async fn limited_json(response: reqwest::Response, limit: usize) -> Result<Value> {
    let mut bytes = Vec::new();
    let mut stream = response.bytes_stream();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk?;
        if chunk.len() > limit.saturating_sub(bytes.len()) {
            bail!("返回数据过大");
        }
        bytes.extend_from_slice(&chunk);
    }
    Ok(serde_json::from_slice(&bytes).context("返回数据格式无效")?)
}
pub(crate) async fn confirm(app: &AppHandle, message: &str, button: &str) -> Result<bool> {
    let (sender, receiver) = oneshot::channel();
    app.dialog()
        .message(message)
        .title("Accnotify")
        .kind(MessageDialogKind::Warning)
        .buttons(MessageDialogButtons::OkCancelCustom(
            button.into(),
            "取消".into(),
        ))
        .show(move |answer| {
            let _ = sender.send(answer);
        });
    Ok(receiver.await?)
}
pub(crate) fn notification_id(id: &str) -> i32 {
    // Stable identifiers let newer revisions replace a delivered notification where supported.
    use sha2::{Digest, Sha256};
    let hash = Sha256::digest(id.as_bytes());
    i32::from_be_bytes(hash[..4].try_into().unwrap()) & 0x7fffffff
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn server_urls_reject_credentials_and_non_http() {
        assert_eq!(
            normalize_server("https://example.com/base/").unwrap(),
            "https://example.com/base"
        );
        for url in [
            "file:///etc/passwd",
            "https://a:b@example.com",
            "https://example.com?key=x",
            "https://example.com/#x",
        ] {
            assert!(normalize_server(url).is_err());
        }
    }
}
