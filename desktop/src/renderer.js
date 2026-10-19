const $ = id => document.getElementById(id);
let current, selected, limit = 50, initialized = false, formDirty = false, feedbackTimer;
const days = ["一", "二", "三", "四", "五", "六", "日"];
for (let index = 0; index < 7; index++) {
  const label = document.createElement("label"), input = document.createElement("input");
  input.type = "checkbox"; input.value = index + 1; input.setAttribute("aria-label", "周" + days[index]);
  label.append(input, "周" + days[index]); $("weekdays").append(label);
}
function feedback(text, failure = false) {
  clearTimeout(feedbackTimer); $("feedback").textContent = text; $("feedback").hidden = !text;
  $("feedback").classList.toggle("error", failure);
  if (!failure) feedbackTimer = setTimeout(() => { $("feedback").hidden = true; }, 6000);
}
async function run(channel, value, success) {
  try { if (!await Features.consent(channel, value)) return false; const result = await window.accnotify.call(channel, value); if (result !== false && success) feedback(success); return result; }
  catch (error) { feedback(error.message, true); return false; }
}
function tab(page) {
  if (typeof page === "boolean") page = page ? "settings" : "history";
  for (const item of ["history", "settings", "about"]) {
    $(item + "-page").hidden = item !== page;
    $(item + "-tab").classList.toggle("selected", item === page);
    $(item + "-tab").setAttribute("aria-current", item === page ? "page" : "false");
  }
}
function fillForm(state) {
  Features.fill(state); $("server").value = state.server; $("name").value = state.name; $("auto-start").checked = state.autoStart;
  const rules = state.rules;
  for (const [id, field] of Object.entries({ enabled: "enabled", scheduled: "scheduleEnabled", workdays: "workdaysOnly", quiet: "silentEnabled", holidays: "holidaySilent" })) $(id).checked = rules[field];
  for (const [id, field] of Object.entries({ start: "start", end: "end", "silent-start": "silentStart", "silent-end": "silentEnd" })) $(id).value = rules[field];
  for (const input of $("weekdays").querySelectorAll("input")) input.checked = rules.weekdays.includes(Number(input.value));
  $("silent-dates").value = rules.silentDates.join(", "); $("working-dates").value = rules.workingDates.join(", ");
  $("reuse-common-id").checked = !!state.reuseCommonId;
  $("reuse-common-key").checked = !!state.reuseCommonKey;
  $("sender-key").value = "";
  formDirty = false; updateControls();
}
function updateControls() {
  $("workdays").disabled = !$("scheduled").checked;
  for (const input of $("weekdays").querySelectorAll("input")) input.disabled = !$("scheduled").checked || $("workdays").checked;
  $("start").disabled = $("end").disabled = !$("scheduled").checked;
  $("silent-start").disabled = $("silent-end").disabled = !$("quiet").checked;
  const busy = !!current?.busy;
  $("sender-key").disabled = $("generate-key").disabled = busy || $("reuse-common-key").checked;
  $("reuse-common-key").disabled = busy;
  $("reuse-common-id").disabled = busy || !!current?.hasPendingReset || !!current?.bound || (current?.devices.length || 0) > 1 || !!current?.hasBindingRequest;
  for (const id of ["apply-id", "common-info", "save-common", "generate-common-id", "generate-common-key"]) $(id).disabled = busy;
  $("apply-id").disabled = $("reuse-common-id").disabled;
}
function render(state) {
  const changedServer = current && current.server !== state.server;
  const changedIdentity = current && (current.deviceKey !== state.deviceKey || current.bound !== state.bound);
  current = state;
  document.documentElement.dataset.platform = state.os;
  if (changedIdentity) $("reuse-common-id").checked = !!state.reuseCommonId;
  $("app-version").textContent = state.version; $("app-platform").textContent = state.platform;
  $("connection").textContent = state.connection;
  $("device-id").textContent = state.deviceKey;
  $("binding-state").textContent = state.bound || state.devices.length > 1 ? "共用推送 ID" : "独立 ID";
  $("endpoint-id").textContent = state.endpointID || "同步连接后显示";
  renderDeviceList(state);
  $("key-state").textContent = state.hasSenderKey ? "已配置" : "未配置";
  $("unbind").hidden = !state.bound && state.devices.length <= 1;
  $("send-code").disabled = state.busy || state.bound || state.devices.length > 1 || !state.registered;
  $("verify").disabled = state.busy || !state.hasBindingRequest || state.bound;
  $("cancel-bind").disabled = state.busy || !state.hasBindingRequest;
  $("phone-link").disabled = state.bound || state.busy;
  $("unbind").disabled = $("connect").disabled = $("save").disabled = $("save-key").disabled = $("clear-key").disabled = state.busy;
  $("fetch-calendar").disabled = state.holidayBusy;
  $("fetch-calendar").textContent = state.holidayBusy ? "正在获取…" : "获取本年度节假日";
  $("binding-hint").textContent = state.hasBindingRequest ? `请在 ${state.bindingHint || "确认设备"} 的历史消息中查看验证码，5 分钟内有效。` : "";
  const year = new Date().getFullYear(), calendar = state.rules.calendars.find(item => item.year === year);
  $("calendar-status").textContent = calendar ? `已保存 ${year} 年日历${calendar.fetchedAt ? "，获取时间：" + new Date(calendar.fetchedAt).toLocaleString() : ""}。仅手动获取，不自动联网更新。`
    : `未导入 ${year} 年日历：工作日暂按周一至周五，节假日静默需先获取或导入日历。`;
  if (!initialized || changedServer) { fillForm(state); initialized = true; if (changedServer) selected = null; }
  $("service-feedback").textContent = state.error || "";
  $("service-feedback").hidden = !state.error;
  updateControls();
  Features.render(state); renderMessages(); renderDetail();
}
function renderDeviceList(state) {
  $("device-list").replaceChildren();
  if (state.devices.length <= 1) return;
  const title = document.createElement("div"); title.className = "label"; title.textContent = "同 ID 的设备"; $("device-list").append(title);
  for (const member of state.devices) {
    const row = document.createElement("div"), info = document.createElement("div"), name = document.createElement("div"), id = document.createElement("code");
    row.className = "identity-row"; name.textContent = (member.name || (member.platform === "pc" ? "电脑" : "手机")) + (member.is_self ? "（本机）" : "");
    id.textContent = member.endpoint_id; info.append(name, id); row.append(info);
    const copy = document.createElement("button"); copy.textContent = "复制推送链接"; copy.addEventListener("click", () => run("copy-target", member.endpoint_id, "已复制该设备的推送链接")); row.append(copy);
    if (!member.is_self) { const remove = document.createElement("button"); remove.textContent = "移除"; remove.disabled = state.busy; remove.addEventListener("click", () => run("remove-device", member.id, "已移除设备")); row.append(remove); }
    $("device-list").append(row);
  }
}
function renderMessages() {
  const search = $("search").value.trim().toLowerCase();
  const group = $("group-filter").value;
  const entries = current.history.filter(item => (!group || "group:" + item.group === group) && `${item.title}\n${item.body}\n${item.group}`.toLowerCase().includes(search));
  $("messages").replaceChildren();
  if (!entries.length) {
    const empty = document.createElement("p"); empty.className = "empty"; empty.textContent = search ? "没有匹配的消息" : "暂无消息"; $("messages").append(empty);
  }
  for (const entry of entries.slice(0, limit)) {
    const button = document.createElement("button"); button.className = "message" + (selected === entry.id ? " selected" : ""); button.setAttribute("role", "listitem");
    const title = document.createElement("div"), preview = document.createElement("div"), meta = document.createElement("div"), date = document.createElement("span"), group = document.createElement("span");
    title.className = "message-title"; title.textContent = entry.title; preview.className = "message-preview"; preview.textContent = entry.body;
    meta.className = "message-meta"; date.textContent = new Date(entry.received).toLocaleString(); group.textContent = entry.group; meta.append(date, group);
    button.append(title, preview, meta); button.addEventListener("click", () => { selected = entry.id; renderMessages(); renderDetail(); }); $("messages").append(button);
  }
  $("more").hidden = entries.length <= limit;
}
function renderDetail() {
  const entry = current.history.find(item => item.id === selected); $("detail").replaceChildren();
  if (!entry) { const empty = document.createElement("p"); empty.className = "empty"; empty.textContent = "选择一条消息查看内容"; $("detail").append(empty); return; }
  const title = document.createElement("h2"), date = document.createElement("div"), body = document.createElement("div"), actions = document.createElement("div");
  title.textContent = entry.title; date.className = "muted"; date.textContent = new Date(entry.received).toLocaleString() + (entry.group ? " · " + entry.group : "") + " · " + entry.server; body.className = "message-body"; MessageView.body(body, entry.body);
  actions.className = "actions";
  if (entry.url) { const button = document.createElement("button"); button.textContent = "打开链接"; button.addEventListener("click", () => run("open-url", entry.url)); actions.append(button); }
  const copy = document.createElement("button"); copy.textContent = "复制内容"; copy.addEventListener("click", () => run("copy-message", entry.id, "消息内容已复制")); actions.append(copy);
  const remove = document.createElement("button"); remove.textContent = "删除消息"; remove.addEventListener("click", () => run("remove-message", entry.id)); actions.append(remove);
  $("detail").append(title, date, body, actions);
  if (entry.image) MessageView.image($("detail"), entry);
}
function rulesForm() {
  return { ...current.rules, enabled: $("enabled").checked, scheduleEnabled: $("scheduled").checked, workdaysOnly: $("workdays").checked,
    weekdays: [...$("weekdays").querySelectorAll("input:checked")].map(input => Number(input.value)),
    start: $("start").value, end: $("end").value, silentEnabled: $("quiet").checked, silentStart: $("silent-start").value,
    silentEnd: $("silent-end").value, holidaySilent: $("holidays").checked, silentDates: $("silent-dates").value, workingDates: $("working-dates").value };
}
$("history-tab").addEventListener("click", () => tab(false)); $("settings-tab").addEventListener("click", () => tab(true));
$("search").addEventListener("input", () => { limit = 50; renderMessages(); }); $("more").addEventListener("click", () => { limit += 50; renderMessages(); });
$("connect").addEventListener("click", () => run("connect")); $("copy-target").addEventListener("click", () => run("copy-target", undefined, "已复制本机推送链接"));
$("copy").addEventListener("click", () => run("copy", undefined, "已复制 Push 链接"));
$("save").addEventListener("click", async () => {
  const result = await run("save", { server: $("server").value.trim(), name: $("name").value, autoStart: $("auto-start").checked, rules: rulesForm(), themeMode: $("theme-mode").value, themeColor: $("theme-color").value }, "已保存");
  if (result) { fillForm(result); render(result); }
});
$("send-code").addEventListener("click", () => {
  if (formDirty && $("server").value.trim().replace(/\/+$/, "") !== current.server) { feedback("请先保存服务器设置", true); return; }
  run("bind-request", $("phone-link").value.trim(), "验证码已发送，请查看确认设备的历史消息");
});
$("verify").addEventListener("click", async () => { if (await run("bind-verify", $("code").value.trim(), "绑定成功")) $("code").value = ""; });
$("cancel-bind").addEventListener("click", () => run("bind-cancel", undefined, "已取消待处理请求"));
$("unbind").addEventListener("click", () => run("unbind", undefined, "已解除绑定"));
$("save-key").addEventListener("click", async () => {
  if (!savedServer()) return;
  if (await run("sender-key", { key: $("sender-key").value.trim() || null, reuseCommonKey: $("reuse-common-key").checked }, "密钥已保存")) $("sender-key").value = "";
});
$("clear-key").addEventListener("click", async () => {
  if (!savedServer()) return;
  if (await run("sender-key", { key: "", reuseCommonKey: false }, "密钥已清除")) { $("reuse-common-key").checked = false; $("sender-key").value = ""; updateControls(); }
});
$("generate-key").addEventListener("click", async () => { const key = await run("generate-secret", "key"); if (key) $("sender-key").value = key; });
$("apply-id").addEventListener("click", async () => {
  if (!savedServer()) return;
  if (!await run("select-id", $("reuse-common-id").checked, "ID 已应用，请确认发送脚本的推送地址")) $("reuse-common-id").checked = !!current.reuseCommonId;
});
$("import-calendar").addEventListener("click", () => run("import-calendar", undefined, "日历已保存"));
$("fetch-calendar").addEventListener("click", () => run("fetch-calendar", undefined, "本年度节假日已获取并保存"));
$("settings-page").addEventListener("input", event => { if (!["phone-link", "code", "sender-key", "reuse-common-key", "reuse-common-id"].includes(event.target.id)) formDirty = true; updateControls(); });
window.accnotify.onState(render);
window.accnotify.onMessage(id => { selected = id; tab(false); if (current) { renderMessages(); renderDetail(); } });
window.accnotify.call("state").then(render).catch(error => feedback(error.message, true));

function savedServer() {
  if ($("server").value.trim().replace(/\/+$/, "") !== current.server) { feedback("请先保存服务器地址", true); return false; }
  return true;
}
async function commonCall(channel, value) {
  $("common-error").hidden = true;
  try { return await window.accnotify.call(channel, value); }
  catch (error) { $("common-error").textContent = error.message; $("common-error").hidden = false; return false; }
}
$("common-info").addEventListener("click", async () => {
  const info = await run("common-info"); if (!info) return;
  $("common-id").value = info.deviceId; $("common-key").value = info.senderKey;
  $("common-key").type = "password"; $("show-common-key").textContent = "显示密钥"; $("show-common-key").setAttribute("aria-pressed", "false");
  $("common-error").hidden = true; $("common-status").textContent = ""; $("common-dialog").showModal();
});
$("common-dialog").addEventListener("close", () => { $("common-key").value = ""; $("common-error").hidden = true; });
$("generate-common-id").addEventListener("click", async () => { const id = await commonCall("generate-secret", "id"); if (id && $("common-dialog").open) $("common-id").value = id; });
$("generate-common-key").addEventListener("click", async () => { const key = await commonCall("generate-secret", "key"); if (key && $("common-dialog").open) $("common-key").value = key; });
$("copy-common-id").addEventListener("click", async () => { if (await commonCall("copy-secret", { kind: "id", text: $("common-id").value })) $("common-status").textContent = "公用 ID 已复制"; });
$("copy-common-key").addEventListener("click", async () => { if (await commonCall("copy-secret", { kind: "key", text: $("common-key").value.trim() })) $("common-status").textContent = "公用密钥已复制"; });
$("show-common-key").addEventListener("click", () => {
  const show = $("common-key").type === "password"; $("common-key").type = show ? "text" : "password";
  $("show-common-key").textContent = show ? "隐藏密钥" : "显示密钥"; $("show-common-key").setAttribute("aria-pressed", String(show));
});
$("save-common").addEventListener("click", async () => {
  if (await commonCall("common-save", { deviceId: $("common-id").value, senderKey: $("common-key").value.trim() })) { $("common-dialog").close(); feedback("通用信息已保存"); }
});

let checkingUpdates = false, updateURL;
$("about-tab").addEventListener("click", () => tab("about"));
$("open-project").addEventListener("click", () => run("open-url", current.projectURL));
$("open-releases").addEventListener("click", () => run("open-url", current.releasesURL));
$("download-update").addEventListener("click", () => { if (updateURL) run("open-url", updateURL); });
$("check-updates").addEventListener("click", async () => {
  if (checkingUpdates) return;
  checkingUpdates = true; $("check-updates").disabled = true; $("check-updates").textContent = "正在检查…";
  $("update-status").textContent = "正在检查更新…"; $("download-update").hidden = true; updateURL = undefined;
  try {
    const result = await window.accnotify.call("check-updates");
    if (result.status === "available") {
      $("update-status").textContent = `发现新版本 ${result.version}`; updateURL = result.url; $("download-update").hidden = false;
    } else if (result.status === "current") $("update-status").textContent = `当前已是最新版本（${current.version}）`;
    else $("update-status").textContent = "暂未发布适用于这台电脑的安装包，可前往发布页面查看。";
  } catch (error) { $("update-status").textContent = error.message; }
  finally { checkingUpdates = false; $("check-updates").disabled = false; $("check-updates").textContent = "检查更新"; }
});
