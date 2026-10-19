window.Features = (() => {
  const el = id => document.getElementById(id);
  let editingServer = null;
  const consentChannels = new Set(["connect", "save", "server-manage", "device-manage", "bind-request", "bind-verify", "unbind", "remove-device", "select-id", "regenerate-id", "test-push"]);
  async function consent(channel, value) {
    if (!current || !consentChannels.has(channel) || current.officialConsent) return true;
    if (channel === "server-manage" && value?.mode === "add") return true;
    const server = channel === "server-manage" ? (value?.mode === "delete" ? current.servers.find(url => url !== value.url) : value?.url) : channel === "save" ? value?.server : current.server;
    if (new URL(server).hostname !== "an.trah.cn") return true;
    return await window.accnotify.call("accept-consent") !== false;
  }
  function fill(state) {
    el("server").replaceChildren(...state.servers.map(server => { const option = document.createElement("option"); option.value = option.textContent = server; return option; }));
    el("server").value = state.server;
    el("theme-mode").value = state.themeMode; el("theme-color").value = state.themeColor;
  }
  function theme(mode, color) { document.documentElement.dataset.mode = mode; document.documentElement.dataset.color = color; }
  function render(state) {
    theme(state.themeMode, state.themeColor);
    const previous = el("group-filter").value;
    const groups = [...new Set(state.history.map(entry => entry.group))].sort();
    const all = document.createElement("option"); all.value = ""; all.textContent = "全部分组";
    el("group-filter").replaceChildren(all, ...groups.map(group => { const option = document.createElement("option"); option.value = "group:" + group; option.textContent = group || "未分组"; return option; }));
    if (groups.some(group => "group:" + group === previous)) el("group-filter").value = previous;
    for (const id of ["add-server", "edit-server", "delete-server", "server", "regenerate-id", "view-key", "clear-messages", "save-server", "send-test-push"]) el(id).disabled = state.busy;
    for (const [id, action] of [["reset-rsa", "reset_rsa"], ["reset-all", "reset_all"], ["unregister", "unregister"]]) el(id).disabled = state.busy || !state.registered || (state.hasPendingReset && state.pendingResetAction !== action);
    el("regenerate-id").disabled ||= state.bound || state.devices.length > 1 || state.hasBindingRequest || state.hasPendingReset;
    el("delete-server").disabled ||= state.servers.length <= 1;
    el("reset-status").textContent = state.hasPendingReset ? `“${({ reset_rsa: "仅重置 RSA 密钥", reset_all: "重置 ID 和 RSA 密钥", unregister: "注销设备" })[state.pendingResetAction]}”尚未确认，请点击对应按钮重试。` : "";
    // Refresh the server menu after add/edit/delete without overwriting unsaved form values.
    if (el("server").options.length !== state.servers.length || [...el("server").options].some((o, i) => o.value !== state.servers[i])) {
      el("server").replaceChildren(...state.servers.map(server => { const o = document.createElement("option"); o.value = o.textContent = server; return o; })); el("server").value = state.server;
    }
  }
  el("server").addEventListener("change", async () => {
    const url = el("server").value;
    if (!await run("server-manage", { mode: "select", url }, "已切换服务器")) el("server").value = current.server;
  });
  function serverDialog(edit) {
    editingServer = edit ? current.server : null;
    el("server-title").textContent = edit ? "编辑服务器" : "添加服务器";
    el("server-url").value = editingServer || "";
    el("server-reuse-id").checked = edit && current.reuseCommonId;
    el("server-reuse-key").checked = edit && current.reuseCommonKey;
    el("server-error").hidden = true; el("server-dialog").showModal();
  }
  el("add-server").addEventListener("click", () => serverDialog(false));
  el("edit-server").addEventListener("click", () => serverDialog(true));
  el("delete-server").addEventListener("click", () => run("server-manage", { mode: "delete", url: current.server }, "服务器已删除"));
  el("save-server").addEventListener("click", async () => {
    el("server-error").hidden = true;
    if (!el("server-url").reportValidity()) return;
    const value = { mode: editingServer ? "edit" : "add", oldURL: editingServer, url: el("server-url").value.trim(), reuseId: el("server-reuse-id").checked, reuseKey: el("server-reuse-key").checked };
    try {
      if (!await consent("server-manage", value)) return;
      if (await window.accnotify.call("server-manage", value)) { el("server-dialog").close(); feedback("服务器已保存"); }
    } catch (error) { el("server-error").textContent = error.message; el("server-error").hidden = false; }
  });
  for (const [id, action] of [["reset-rsa", "reset_rsa"], ["reset-all", "reset_all"], ["unregister", "unregister"]]) el(id).addEventListener("click", () => run("device-manage", action, action === "unregister" ? "设备已注销" : "设备已重置"));
  el("regenerate-id").addEventListener("click", () => run("regenerate-id", null, "独立 ID 已重新生成，请更新发送脚本"));
  for (const [id, kind] of [["copy-webhook", "webhook"], ["copy-key", "key"], ["copy-endpoint", "endpoint"]]) el(id).addEventListener("click", () => run("copy-info", kind, "已复制"));
  el("clear-messages").addEventListener("click", () => run("clear-messages", null, "历史消息已清空"));
  el("group-filter").addEventListener("change", () => { limit = 50; renderMessages(); });
  el("view-key").addEventListener("click", async () => {
    const key = await run("sender-key-info"); if (key === false) return;
    if (!key) { feedback("尚未配置当前服务器密钥"); return; }
    el("saved-key-status").textContent = ""; el("saved-key").value = key; el("saved-key").type = "password";
    el("show-saved-key").textContent = "显示密钥"; el("show-saved-key").setAttribute("aria-pressed", "false"); el("saved-key-dialog").showModal();
  });
  el("saved-key-dialog").addEventListener("close", () => { el("saved-key").value = ""; });
  el("show-saved-key").addEventListener("click", () => {
    const show = el("saved-key").type === "password"; el("saved-key").type = show ? "text" : "password";
    el("show-saved-key").textContent = show ? "隐藏密钥" : "显示密钥"; el("show-saved-key").setAttribute("aria-pressed", String(show));
  });
  el("copy-saved-key").addEventListener("click", async () => {
    try { if (await window.accnotify.call("copy-secret", { kind: "key", text: el("saved-key").value })) el("saved-key-status").textContent = "密钥已复制"; }
    catch (error) { el("saved-key-status").textContent = error.message; }
  });
  const privacy = `1. Accnotify 不收集 IMEI、手机号等个人身份信息。\n\n2. 普通推送由服务端接收明文，按设备公钥加密后投递；服务端可读取内容，请选择信任的服务器并使用 HTTPS。发送端加密需要脚本主动加密，各设备分别配置相同密钥。\n\n3. Push Key 是投递凭据，请妥善保管。\n\n4. 设备身份和密钥加密保存在本机，历史消息保存在本机。\n\n5. 本软件为开源工具，请在中国法律允许范围内使用。`;
  el("open-privacy").addEventListener("click", () => {
    el("help-title").textContent = "隐私说明"; MessageView.markdown(el("help-content"), privacy); el("test-push-form").hidden = true; el("help-dialog").showModal();
  });
  el("open-help").addEventListener("click", () => {
    el("help-title").textContent = "使用教程"; el("test-push-form").hidden = false; el("test-push-status").textContent = "";
    const push = current.pushLink;
    MessageView.markdown(el("help-content"), `## 连接与发送\n在设置中添加或选择服务器，点击“连接 / 同步”，再复制 Push 链接。\n\n向链接发送 POST JSON：\n\n\`\`\`json\n{"title":"标题","body":"内容"}\n\`\`\`\n\n也可使用 GET：\n\n\`\`\`text\n${push}/标题/内容\n\`\`\`\n\nGET 路径需进行 URL 编码。建议使用 HTTPS 和 POST，避免正文出现在地址中。\n\n## 指定设备\n不传 target 时发给同 ID 的全部设备；pc 表示全部电脑，mobile 表示全部手机，也可以填写某台设备标识。复制本机链接会附带 target。\n\n## 消息格式\n支持 body、markdown、title、group、url、image。image 可为网络图片或 Base64。相同 id 更新原消息；带 id 和 delete:true 删除消息。\n\n## Webhook\n可复制 Webhook 链接，接收通用 JSON 或 GitHub 等平台事件。\n\n## 绑定与密钥\n在同一服务器上粘贴已有设备的 Push 链接，发送验证码，在确认设备的历史消息中查看并输入。验证码 5 分钟有效。每台设备保留独立身份与 RSA 密钥。\n\n公用 ID、密钥供本机不同服务器复用，各设备间不会自动同步。发送端加密需要脚本主动加密，并在各设备配置相同密钥。\n\n## 提醒与历史\n高级设置支持提醒时段、工作日、静默和节假日日历。日历需手动获取或导入。静默期间消息仍保存到历史，结束后不补弹。历史汇总本机各服务器消息，可搜索、筛选分组、复制或清空。\n\n## 设备重置\n仅重置 RSA 保留推送地址；重新生成 ID 或注销后需更新发送脚本。重置结果未确认时，请重试同一种操作。\n\n[完整项目说明](https://github.com/trah01/Accnotify/blob/main/README.md)`);
    el("help-dialog").showModal();
  });
  el("send-test-push").addEventListener("click", async () => {
    el("test-push-status").textContent = "正在发送…";
    try {
      if (!await consent("test-push")) { el("test-push-status").textContent = "已取消发送。"; return; }
      await window.accnotify.call("test-push", el("test-payload").value);
      el("test-push-status").textContent = "测试消息已发送，请查看历史消息。";
    } catch (error) { el("test-push-status").textContent = error.message; }
  });
  return { fill, render, consent, theme };
})();
