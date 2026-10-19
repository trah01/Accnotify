import { HttpError, readText } from "./http.js";
import { finishPush, formPushFields, parsePush } from "./push.js";

const providers = { github: "GitHub", gitlab: "GitLab", docker: "Docker Hub", gitea: "Gitea", forgejo: "Forgejo", feishu: "飞书", dingtalk: "钉钉", wecom: "企业微信" };
function field(data, path) { return path.split(".").reduce((v, k) => v?.[k], data); }
function text(data, ...paths) {
  for (const path of paths) {
    const value = field(data, path);
    if ((typeof value === "string" || typeof value === "number") && String(value).trim()) return String(value).trim();
  }
  return "";
}

// Keep the complete payload for event types that do not have a short summary.
export async function parseWebhook(request, route = "") {
  if (route && !providers[route]) throw new HttpError(404, "Webhook 类型不存在");
  if (request.method === "GET" || (request.headers.get("content-type") || "").includes("multipart/form-data")) return parsePush(request, []);
  const finish = req => finishPush(request, req);
  let raw = await readText(request);
  if (!raw.trim()) {
    if (new URL(request.url).search) return finish({});
    throw new HttpError(400, "Webhook 内容为空");
  }
  const type = request.headers.get("content-type") || "";
  if (type.includes("application/x-www-form-urlencoded")) {
    const form = new URLSearchParams(raw);
    if (!form.get("payload") && !route) return finish(formPushFields(Object.fromEntries(form)));
    raw = form.get("payload") || JSON.stringify(Object.fromEntries(form));
  }
  let data;
  try { data = JSON.parse(raw); } catch {
    if (route || type.includes("json") || /^[\s]*[\[{]/.test(raw)) throw new HttpError(400, "Webhook JSON 无效");
    return finish({ title: "Webhook", body: raw, group: "webhook" });
  }
  if (!data || typeof data !== "object" || Array.isArray(data)) {
    if (route) throw new HttpError(400, "Webhook JSON 必须是对象");
    return finish({ title: "Webhook", body: JSON.stringify(data, null, 2), group: "webhook" });
  }
  let provider = providers[route] || "";
  let event = text(data, "object_kind", "event_type");
  for (const [header, name] of [["X-GitHub-Event", "GitHub"], ["X-Gitea-Event", "Gitea"], ["X-Forgejo-Event", "Forgejo"], ["X-Gitlab-Event", "GitLab"]]) {
    if (request.headers.has(header)) { provider ||= name; event = request.headers.get(header); break; }
  }
  if (!provider && data.push_data) provider = "Docker Hub";
  if (!provider && data.msg_type) provider = "飞书";
  if (!provider && data.object_kind && data.project) provider = "GitLab";
  if (!provider && data.repository && ["ref", "pull_request", "issue", "release", "workflow_run"].some(key => data[key] != null)) provider = "GitHub";
  if (!provider && data.msgtype && data.msgtype !== "text") provider = data.markdown?.content || ["news", "textcard", "template_card"].includes(data.msgtype) ? "企业微信" : "钉钉";
  const pushFields = ["title", "body", "markdown", "id", "delete", "target", "group", "icon", "url", "image", "sound", "badge", "encryption", "nonce", "ciphertext", "encrypted_content"];
  if ((!provider && pushFields.some(key => Object.hasOwn(data, key))) || ["encryption", "nonce", "ciphertext"].some(key => Object.hasOwn(data, key))) return finish(data);
  const title = text(data, "title", "subject", "markdown.title", "link.title", "textcard.title", "card.header.title.content", "name", "event");
  const repo = text(data, "repository.full_name", "repository.repo_name", "repository.name", "project.path_with_namespace", "project.name");
  let body = text(data, "body", "message", "content", "text", "description", "summary", "text.content", "content.text", "markdown.text", "markdown.content", "link.text", "textcard.description");
  const details = [repo && `仓库：${repo}`, event && `事件：${event}`, text(data, "ref") && `引用：${text(data, "ref")}`, text(data, "push_data.tag") && `标签：${text(data, "push_data.tag")}`, text(data, "head_commit.message", "object_attributes.title", "release.body", "pull_request.title", "issue.title")];
  body = [...details, body].filter(Boolean).join("\n");
  const complete = JSON.stringify(data, null, 2);
  body = body ? `${body}\n\n--- 完整数据 ---\n${complete}` : complete;
  return finish({
    title: title || [provider || "Webhook", event, repo].filter(Boolean).join(" · "),
    body, group: "webhook",
    url: text(data, "url", "link.messageUrl", "textcard.url", "compare", "pull_request.html_url", "issue.html_url", "release.html_url", "repository.html_url", "repository.repo_url", "project.web_url"),
    image: text(data, "image", "link.picUrl", "repository.logo_url"),
  });
}
