import { Buffer } from "node:buffer";
import { HttpError, parseObject, readText } from "./http.js";
import { senderEnvelope } from "./crypto.js";

const strings = ["title", "body", "group", "icon", "url", "image", "sound", "markdown", "encryption", "nonce", "ciphertext", "encrypted_content"];
export async function parsePush(request, path) {
  let req = {};
  if (request.method === "POST") {
    const type = request.headers.get("content-type") || "";
    const text = await readText(request);
    if (type.includes("multipart/form-data")) {
      let form;
      try { form = await new Response(text, { headers: { "Content-Type": type } }).formData(); }
      catch { throw new HttpError(400, "表单格式无效"); }
      req = Object.fromEntries([...form].filter(([, value]) => typeof value === "string"));
    } else if (type.includes("application/x-www-form-urlencoded")) req = Object.fromEntries(new URLSearchParams(text));
    else if (text.trim()) req = parseObject(text);
  }
  if (path.length) {
    req.title ||= path.length === 1 ? "Accnotify" : path[0];
    req.body ||= path.length === 1 ? path[0] : path.slice(1).join("/");
  }
  for (const [key, value] of new URL(request.url).searchParams) if (value) req[key] = value;
  return normalizePush(req);
}

export function normalizePush(req) {
  const target = req.target || "all";
  if (typeof target !== "string" || target.length > 64) throw new HttpError(400, "target 必须为设备标识或 all");
  for (const field of strings) {
    if (req[field] != null && typeof req[field] !== "string") throw new HttpError(400, `${field} 必须是字符串`);
  }
  const id = typeof req.id === "string" ? req.id : "";
  const remove = req.delete === "1" || req.delete === 1 || req.delete === true;
  if (Buffer.byteLength(id) > 256) throw new HttpError(400, "id 不能超过 256 字节");
  if (remove && !id) throw new HttpError(400, "删除消息必须提供字符串 id");
  const fields = Object.fromEntries(strings.slice(0, 8).map(key => [key, req[key] || ""]));
  fields.badge = req.badge ?? 0;
  if (!Number.isSafeInteger(fields.badge)) throw new HttpError(400, "badge 必须为整数");
  fields.body = (req.markdown || fields.body).replaceAll("\\n", "\n");
  fields.title = fields.title.replaceAll("\\n", "\n");
  if (id) fields.id = id;
  if (remove) fields.delete = "1";
  if (req.encryption || req.nonce || req.ciphertext) {
    if (Object.values(fields).some(Boolean) || req.markdown || req.encrypted_content) {
      throw new HttpError(400, "加密请求不能同时包含明文字段，请将消息字段放入密文");
    }
    return { target, fields: null, envelope: senderEnvelope(req) };
  }
  if (req.encrypted_content) throw new HttpError(400, "发送端加密请使用 encryption、nonce 和 ciphertext");
  if (Buffer.byteLength(JSON.stringify(fields)) > 128 * 1024) throw new HttpError(413, "消息内容不能超过 128 KB");
  return { target, fields, envelope: null };
}
