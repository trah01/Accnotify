const channels = new Set(["server-manage", "device-manage", "sender-key-info", "copy-info", "copy-message", "clear-messages", "regenerate-id", "accept-consent", "test-push", "load-image", "save-image", "state", "copy-target", "remove-device", "connect", "save", "copy", "sender-key", "common-info", "check-updates", "common-save", "generate-secret", "copy-secret", "select-id", "bind-request", "bind-verify", "bind-cancel", "unbind", "remove-message", "open-url", "import-calendar", "fetch-calendar"]);
window.accnotify = {
  call: async (channel, value) => {
    if (!channels.has(channel)) throw new Error("操作无效");
    try { return await window.__TAURI__.core.invoke("dispatch", { channel, value: value ?? null }); }
    catch (error) { throw new Error(typeof error === "string" ? error : error.message || "操作失败"); }
  },
  onState: callback => subscribe("state", callback),
  onMessage: callback => subscribe("open-message", callback)
};
function subscribe(event, callback) {
  let disposed = false, unlisten;
  window.__TAURI__.event.listen(event, event => { if (!disposed) callback(event.payload); }).then(value => {
    unlisten = value; if (disposed) unlisten();
  }).catch(error => console.error("无法监听客户端事件", error));
  return () => { disposed = true; if (unlisten) unlisten(); };
}
