window.MessageView = (() => {
  const imageCache = new Map();
  function safeURL(text) {
    try { const url = new URL(text); return ["http:", "https:"].includes(url.protocol) && !url.username && !url.password ? url.href : null; }
    catch { return null; }
  }
  function markdown(container, text) {
    container.replaceChildren(DOMPurify.sanitize(marked.parse(text, { breaks: true, gfm: true }), {
      RETURN_DOM_FRAGMENT: true, USE_PROFILES: { html: true }, FORBID_TAGS: ["img", "style", "form", "input", "button", "iframe", "video", "audio"], FORBID_ATTR: ["style", "id", "name"]
    }));
    for (const link of container.querySelectorAll("a")) {
      const href = safeURL(link.getAttribute("href"));
      link.removeAttribute("target"); link.removeAttribute("download");
      if (href) { link.href = href; link.addEventListener("click", event => { event.preventDefault(); run("open-url", href); }); }
      else link.removeAttribute("href");
    }
  }
  function jsonContent(text) {
    try {
      const value = JSON.parse(text);
      if (!value || typeof value !== "object") return null;
      return JSON.stringify(value, null, 2);
    } catch { return null; }
  }
  function body(container, text) {
    const marker = "--- 完整数据 ---", separator = text.indexOf(marker);
    const summary = separator > 0 ? text.slice(0, separator).trim() : text;
    const formatted = jsonContent(summary);
    if (formatted) { const pre = document.createElement("pre"); pre.textContent = formatted; container.append(pre); }
    else markdown(container, summary);
    if (formatted || separator > 0) {
      const details = document.createElement("details"), title = document.createElement("summary"), pre = document.createElement("pre");
      title.textContent = "原始内容"; pre.textContent = separator > 0 ? text.slice(separator + marker.length).trim() : text;
      details.append(title, pre); container.append(details);
    }
  }
  async function image(container, entry) {
    const section = document.createElement("div"), status = document.createElement("p"), actions = document.createElement("div");
    status.className = "muted"; status.setAttribute("role", "status"); status.textContent = "正在加载图片…"; actions.className = "actions";
    section.append(status); container.append(section);
    try {
      const cacheKey = `${entry.id}:${entry.sequence}`;
      let data = imageCache.get(cacheKey);
      if (!data) { data = await window.accnotify.call("load-image", entry.id); if (imageCache.size >= 3) imageCache.delete(imageCache.keys().next().value); imageCache.set(cacheKey, data); }
      if (!section.isConnected) return;
      const img = document.createElement("img"); img.alt = entry.title || "消息图片"; img.src = data.dataURL; img.className = "message-image";
      img.addEventListener("error", () => { status.textContent = "图片无法显示，请保存后查看"; });
      const save = document.createElement("button"); save.textContent = "保存图片"; save.addEventListener("click", () => run("save-image", entry.id, "图片已保存"));
      const share = document.createElement("button"); share.textContent = "分享图片";
      share.addEventListener("click", async () => {
        const encoded = atob(data.dataURL.split(",")[1]), bytes = Uint8Array.from(encoded, c => c.charCodeAt(0));
        const file = new File([bytes], `Accnotify.${data.extension}`, { type: data.mime });
        if (navigator.canShare?.({ files: [file] })) {
          try { await navigator.share({ files: [file], title: entry.title }); } catch (error) { if (error.name !== "AbortError") feedback("分享失败，请保存图片后分享", true); }
        } else { status.textContent = "当前系统不支持直接分享，请先保存图片后分享。"; await run("save-image", entry.id, "图片已保存"); }
      });
      const enlarge = document.createElement("button"); enlarge.textContent = "放大查看";
      enlarge.addEventListener("click", () => {
        const dialog = document.createElement("dialog"), close = document.createElement("button"), full = document.createElement("img");
        dialog.className = "image-dialog"; dialog.setAttribute("aria-label", "查看消息图片"); close.textContent = "关闭"; full.src = data.dataURL; full.alt = img.alt;
        close.addEventListener("click", () => dialog.close()); dialog.addEventListener("close", () => dialog.remove()); dialog.append(close, full); document.body.append(dialog); dialog.showModal(); close.focus();
      });
      status.textContent = ""; actions.append(enlarge, save, share); section.prepend(img); section.append(actions);
    } catch (error) {
      if (!section.isConnected) return;
      status.textContent = error.message;
      const retry = document.createElement("button"); retry.textContent = "重新加载图片"; retry.addEventListener("click", () => { section.remove(); image(container, entry); }); section.append(retry);
    }
  }
  return { body, markdown, image, safeURL };
})();
