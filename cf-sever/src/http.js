export class HttpError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

export function json(value, status = 200) {
  const options = { status, headers: {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, Authorization",
    "Cache-Control": "no-store",
  } };
  return status === 204 ? new Response(null, options) : Response.json(value, options);
}

// Read incrementally so a missing/forged Content-Length cannot bypass the limit.
export async function readText(request, limit = 256 * 1024) {
  if (Number(request.headers.get("content-length")) > limit) throw new HttpError(413, "请求内容过大");
  if (!request.body) return "";
  const reader = request.body.getReader();
  const decoder = new TextDecoder();
  let size = 0, text = "";
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > limit) { await reader.cancel(); throw new HttpError(413, "请求内容过大"); }
      text += decoder.decode(value, { stream: true });
    }
    return text + decoder.decode();
  } finally { reader.releaseLock(); }
}

export function parseObject(text) {
  try {
    const data = JSON.parse(text);
    if (!data || typeof data !== "object" || Array.isArray(data)) throw new Error();
    return data;
  } catch { throw new HttpError(400, "JSON 必须是有效的对象"); }
}
