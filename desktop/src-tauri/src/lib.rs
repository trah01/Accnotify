mod client;
mod common;
mod crypto;
mod media;
mod operations;
mod policy;
mod store;
mod updates;

use client::{Client, Request, Worker};
use serde_json::Value;
use std::sync::atomic::Ordering;
use tauri::{Emitter, Manager, WebviewUrl, WebviewWindowBuilder};
use tauri_plugin_dialog::{DialogExt, MessageDialogKind};
use tauri_plugin_notification::NotificationExt;

fn show_window(app: &tauri::AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.unminimize();
        let _ = window.show();
        let _ = window.set_focus();
    }
}
fn local_url(url: &url::Url) -> bool {
    (url.scheme() == "tauri" && url.host_str() == Some("localhost"))
        || (matches!(url.scheme(), "http" | "https") && url.host_str() == Some("tauri.localhost"))
}
#[tauri::command]
async fn dispatch(
    window: tauri::WebviewWindow,
    state: tauri::State<'_, Client>,
    channel: String,
    value: Value,
) -> Result<Value, String> {
    if window.label() != "main" || !window.url().is_ok_and(|url| local_url(&url)) {
        return Err("请求来源无效".into());
    }
    if channel == "state" {
        return Ok(state
            .visible
            .read()
            .unwrap_or_else(|e| e.into_inner())
            .clone());
    }
    if channel == "check-updates" {
        return updates::check(&window.app_handle().package_info().version.to_string())
            .await
            .map_err(|error| error.to_string());
    }
    if channel == "load-image" || channel == "save-image" {
        let source = {
            let visible = state.visible.read().unwrap_or_else(|e| e.into_inner());
            visible["history"]
                .as_array()
                .and_then(|entries| entries.iter().find(|entry| entry["id"] == value))
                .and_then(|entry| entry["image"].as_str())
                .filter(|s| !s.is_empty())
                .map(String::from)
                .ok_or("消息图片不存在")?
        };
        return media::dispatch(window.app_handle(), &source, channel == "save-image")
            .await
            .map_err(|e| e.to_string());
    }
    if !matches!(
        channel.as_str(),
        "server-manage"
            | "device-manage"
            | "sender-key-info"
            | "copy-info"
            | "copy-message"
            | "clear-messages"
            | "regenerate-id"
            | "accept-consent"
            | "test-push"
            | "load-image"
            | "save-image"
            | "copy-target"
            | "remove-device"
            | "connect"
            | "save"
            | "copy"
            | "sender-key"
            | "common-info"
            | "common-save"
            | "generate-secret"
            | "copy-secret"
            | "select-id"
            | "bind-request"
            | "bind-verify"
            | "bind-cancel"
            | "unbind"
            | "remove-message"
            | "open-url"
            | "import-calendar"
            | "fetch-calendar"
    ) {
        return Err("操作无效".into());
    }
    if state
        .busy
        .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
        .is_err()
    {
        return Err("请等待当前操作完成".into());
    }
    let app = window.app_handle();
    set_busy(app, &state, true);
    let visible = state
        .visible
        .read()
        .unwrap_or_else(|e| e.into_inner())
        .clone();
    let value = match prepare_action(app, &visible, &channel, value).await {
        Ok(Some(value)) => value,
        Ok(None) => {
            set_busy(app, &state, false);
            return Ok(Value::Bool(false));
        }
        Err(error) => {
            set_busy(app, &state, false);
            return Err(error.to_string());
        }
    };
    let (reply, receiver) = tokio::sync::oneshot::channel();
    if state
        .sender
        .send(Request {
            channel,
            value,
            reply,
        })
        .await
        .is_err()
    {
        set_busy(app, &state, false);
        return Err("客户端已停止，请重新打开".into());
    }
    receiver
        .await
        .map_err(|_| "客户端操作中断，请重新打开".to_string())?
}
fn set_busy(app: &tauri::AppHandle, state: &Client, busy: bool) {
    state.busy.store(busy, Ordering::SeqCst);
    let mut visible = state.visible.write().unwrap_or_else(|e| e.into_inner());
    visible["busy"] = Value::Bool(busy);
    let _ = app.emit("state", visible.clone());
}
async fn prepare_action(
    app: &tauri::AppHandle,
    state: &Value,
    channel: &str,
    value: Value,
) -> anyhow::Result<Option<Value>> {
    // Native prompts run outside the connection worker so receiving and heartbeat continue.
    let accepted = match channel {
        "accept-consent" => client::confirm(app, "您正在使用官方服务器 https://an.trah.cn。\n\n运营者不主动查看或使用您发送的消息内容。消息内容及发送行为由发送者负责，与运营者无关。\n\n为完成投递和断线补发，服务器临时保存消息：收到确认后删除；未确认消息在 5 分钟后失效并清理。服务器只保留推送数量统计，不长期保存消息内容。\n\n确认后，本设备不再重复提醒。", "我已阅读并同意").await?,
        "clear-messages" => client::confirm(app, "清空全部服务器的本地历史消息？", "清空").await?,
        "regenerate-id" => client::confirm(app, "重新生成独立 ID？请同步更新发送脚本中的推送地址，本地历史保留。", "重新生成").await?,
        "device-manage" => {
            let message = match value.as_str() {
                Some("reset_rsa") => "仅重置 RSA 密钥？推送 ID 和地址保留，本地历史保留。",
                Some("reset_all") => "重置设备 ID 和 RSA 密钥？需要更新发送脚本中的推送地址，本地历史保留。",
                Some("unregister") => "注销当前服务器的设备？服务端注册、公钥及消息将删除，本地密钥和历史保留。",
                _ => anyhow::bail!("设备操作无效"),
            };
            client::confirm(app, message, "确认").await?
        }
        "server-manage" if value["mode"] == "delete" => client::confirm(app, "从服务器列表删除此地址？本地历史保留。", "删除").await?,
        "common-save" => client::confirm(app, "保存通用信息？\n公用密钥会更新所有复用它的服务器，请同步更新发送脚本。已保存的服务器 ID 不会自动更换。", "保存").await?,
        "select-id" => client::confirm(app, "切换当前服务器的推送 ID？\n切换后需要更新发送脚本的推送地址，本地历史保留。", "切换").await?,
        "remove-device" => {
            let target = state["devices"]
                .as_array()
                .and_then(|d| d.iter().find(|d| d["id"] == value && d["is_self"] != true))
                .ok_or_else(|| anyhow::anyhow!("请选择其他设备，本机请使用解除绑定"))?;
            client::confirm(
                app,
                &format!(
                    "移除 {}？\n{}\n移除后该设备恢复独立 ID，其他设备继续使用共用 ID。",
                    target["name"].as_str().unwrap_or("设备"),
                    target["endpoint_id"].as_str().unwrap_or("")
                ),
                "移除",
            )
            .await?
        }
        "unbind" => {
            client::confirm(
                app,
                "解除绑定？\n电脑将生成新的独立 ID，本地历史保留。",
                "解除绑定",
            )
            .await?
        }
        "fetch-calendar" => {
            use chrono::Datelike;
            let year = chrono::Local::now().year();
            if state["rules"]["calendars"]
                .as_array()
                .is_some_and(|c| c.iter().any(|c| c["year"] == year))
            {
                client::confirm(
                    app,
                    &format!("已保存 {year} 年日历，重新获取？"),
                    "重新获取",
                )
                .await?
            } else {
                true
            }
        }
        "import-calendar" => {
            let (sender, receiver) = tokio::sync::oneshot::channel();
            app.dialog()
                .file()
                .add_filter("节假日日历", &["json"])
                .pick_file(move |file| {
                    let _ = sender.send(file);
                });
            return match receiver.await? {
                Some(file) => Ok(Some(serde_json::to_value(
                    file.into_path()
                        .map_err(|_| anyhow::anyhow!("日历文件无效"))?,
                )?)),
                None => Ok(None),
            };
        }
        _ => true,
    };
    Ok(accepted.then_some(value))
}
fn reconnect(app: &tauri::AppHandle) {
    let Some(state) = app.try_state::<Client>() else {
        return;
    };
    if state
        .busy
        .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
        .is_err()
    {
        return;
    }
    let (reply, _) = tokio::sync::oneshot::channel();
    if state
        .sender
        .try_send(Request {
            channel: "connect".into(),
            value: Value::Null,
            reply,
        })
        .is_err()
    {
        state.busy.store(false, Ordering::SeqCst);
    }
}
#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let app = tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _, _| {
            show_window(app)
        }))
        .plugin(tauri_plugin_notification::init())
        .plugin(
            tauri_plugin_autostart::Builder::new()
                .macos_launcher(tauri_plugin_autostart::MacosLauncher::LaunchAgent)
                .arg("--hidden")
                .build(),
        )
        .plugin(tauri_plugin_clipboard_manager::init())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_opener::init())
        .invoke_handler(tauri::generate_handler![dispatch])
        .setup(|app| {
            let window = WebviewWindowBuilder::new(app, "main", WebviewUrl::App("index.html".into()))
                .title("Accnotify")
                .inner_size(1040.0, 740.0)
                .min_inner_size(680.0, 480.0)
                .visible(false)
                .on_navigation(local_url)
                .on_new_window(|_, _| tauri::webview::NewWindowResponse::Deny);
            #[cfg(target_os = "macos")]
            let window = window
                .title_bar_style(tauri::TitleBarStyle::Overlay)
                .hidden_title(true);
            window.build()?;
            // Storage schema generation; independent of the application release version.
            // Development builds with incompatible data start with a fresh directory.
            let root = app.path().app_data_dir()?.join("client-v1");
            let init = (|| -> anyhow::Result<_> {
                let mut store = store::Store::load(root.clone())?;
                if !tauri::is_dev() {
                    use tauri_plugin_autostart::ManagerExt;
                    store.config.auto_start = app.autolaunch().is_enabled()?;
                }
                Worker::new(app.handle().clone(), store)
            })();
            let (worker, state, receiver) = match init {
                Ok(value) => value,
                Err(error) => {
                    let handle = app.handle().clone();
                    app.dialog()
                        .message(format!("{error:#}"))
                        .title("Accnotify 启动失败")
                        .kind(MessageDialogKind::Error)
                        .show(move |_| handle.exit(1));
                    return Ok(());
                }
            };
            app.manage(state);
            use tauri_plugin_notification::{Action, ActionType};
            app.notification()
                .register_action_types(vec![ActionType::builder("message-link")
                    .actions(vec![Action::builder("open-url", "打开链接").build()])
                    .build()])?;
            let handle = app.handle().clone();
            app.notification().on_action(move |action| {
                if action.action_id() == "dismiss" {
                    return;
                }
                if action.action_id() == "open-url" {
                    use tauri_plugin_opener::OpenerExt;
                    if let Some(url) = action
                        .notification()
                        .and_then(|n| n.extra().get("url"))
                        .and_then(Value::as_str)
                        .and_then(|text| url::Url::parse(text).ok())
                        .filter(|u| {
                            matches!(u.scheme(), "http" | "https")
                                && u.username().is_empty()
                                && u.password().is_none()
                        })
                    {
                        let _ = handle.opener().open_url(url.to_string(), None::<&str>);
                    }
                    return;
                }
                show_window(&handle);
                if let Some(id) = action
                    .notification()
                    .and_then(|n| n.extra().get("messageID"))
                    .and_then(Value::as_str)
                {
                    let _ = handle.emit("open-message", id);
                }
            })?;
            use tauri::{
                menu::{Menu, MenuItem, PredefinedMenuItem},
                tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
            };
            let open = MenuItem::with_id(app, "open", "打开 Accnotify", true, None::<&str>)?;
            let connect = MenuItem::with_id(app, "connect", "重新连接", true, None::<&str>)?;
            let quit = MenuItem::with_id(app, "quit", "退出", true, None::<&str>)?;
            let separator = PredefinedMenuItem::separator(app)?;
            let menu = Menu::with_items(app, &[&open, &connect, &separator, &quit])?;
            let icon = if cfg!(target_os = "macos") {
                tauri::include_image!("icons/tray.png")
            } else {
                app.default_window_icon()
                    .ok_or_else(|| std::io::Error::other("应用图标缺失"))?
                    .clone()
            };
            TrayIconBuilder::new()
                .icon(icon)
                .icon_as_template(cfg!(target_os = "macos"))
                .tooltip("Accnotify")
                .menu(&menu)
                .show_menu_on_left_click(false)
                .on_menu_event(|app, event| match event.id.as_ref() {
                    "open" => show_window(app),
                    "connect" => reconnect(app),
                    "quit" => app.exit(0),
                    _ => {}
                })
                .on_tray_icon_event(|tray, event| {
                    if matches!(
                        event,
                        TrayIconEvent::Click {
                            button: MouseButton::Left,
                            button_state: MouseButtonState::Up,
                            ..
                        }
                    ) {
                        show_window(tray.app_handle());
                    }
                })
                .build(app)?;
            tauri::async_runtime::spawn(worker.run(receiver));
            if !std::env::args().any(|a| a == "--hidden") {
                show_window(app.handle());
            }
            Ok(())
        })
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                let _ = window.hide();
            }
        })
        .build(tauri::generate_context!())
        .expect("Accnotify 启动失败");
    app.run(|app, event| match event {
        #[cfg(target_os = "macos")]
        tauri::RunEvent::Reopen { .. } => show_window(app),
        _ => {}
    });
}
