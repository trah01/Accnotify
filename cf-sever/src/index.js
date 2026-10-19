import { DurableObject } from "cloudflare:workers";
import { randomUUID } from "node:crypto";
import { equal, encryptMessage, encryptMessageWithKey, keyHash, parsePublicKey } from "./crypto.js";
import { HttpError, json, parseObject, readText } from "./http.js";
import { parsePush } from "./push.js";
import { parseWebhook } from "./webhook.js";

import { initBinding, binding, recipients, removeMember, groupFor, endpointID } from "./binding.js";

const retention = 5 * 60 * 1000;
const maxPending = 256;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") return json(null, 204);
    if (url.pathname === "/health" && request.method === "GET") return json({ status: "ok", timestamp: Math.floor(Date.now() / 1000) });
    // All devices share one database and one connection hub.
    return env.DEVICE.get(env.DEVICE.idFromName("public-service")).fetch(request);
  },
};

export class PersonalDevice extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    this.sql.exec(`CREATE TABLE IF NOT EXISTS devices (id INTEGER PRIMARY KEY AUTOINCREMENT, device_key TEXT UNIQUE NOT NULL, public_key TEXT NOT NULL, name TEXT NOT NULL DEFAULT '')`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS revoked_keys (hash TEXT PRIMARY KEY)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS messages (sequence INTEGER PRIMARY KEY AUTOINCREMENT, device_id INTEGER NOT NULL, message_id TEXT UNIQUE NOT NULL, fields TEXT, envelope TEXT, created_at INTEGER NOT NULL)`);
    this.sql.exec(`CREATE INDEX IF NOT EXISTS messages_created_at ON messages(created_at)`);
    this.sql.exec(`CREATE INDEX IF NOT EXISTS messages_device_sequence ON messages(device_id, sequence)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS rate_limits (scope TEXT PRIMARY KEY, window INTEGER NOT NULL, hits INTEGER NOT NULL)`);
    this.sql.exec(`CREATE INDEX IF NOT EXISTS rate_limits_window ON rate_limits(window)`);
    initBinding.call(this);
  }

  device(key) { return this.sql.exec("SELECT * FROM devices WHERE device_key=?", key || "").toArray()[0]; }
  revoked(key) { return this.sql.exec("SELECT 1 FROM revoked_keys WHERE hash=?", keyHash(key)).toArray().length > 0; }

  rateLimit(scope, limit) {
    const window = Math.floor(Date.now() / 60000);
    this.sql.exec("DELETE FROM rate_limits WHERE window<?", window - 1);
    const result = this.sql.exec(`INSERT INTO rate_limits(scope,window,hits) VALUES(?,?,1)
      ON CONFLICT(scope) DO UPDATE SET window=excluded.window,
      hits=CASE WHEN rate_limits.window=excluded.window THEN rate_limits.hits+1 ELSE 1 END
      WHERE rate_limits.window<>excluded.window OR rate_limits.hits<? RETURNING hits`, scope, window, limit).toArray();
    if (!result.length) throw new HttpError(429, "操作过于频繁，请稍后重试");
  }

  async fetch(request) {
    try { return await this.handle(request); }
    catch (error) {
      // Never log request URLs, credentials, plaintext, ciphertext or public keys.
      if (!(error instanceof HttpError)) console.error("Accnotify 请求处理失败", error.name);
      return json({ success: false, error: error instanceof HttpError ? error.message : "服务暂时不可用，请稍后重试" }, error.status || 500);
    }
  }

  async handle(request) {
    const url = new URL(request.url);
    const parts = url.pathname.split("/").slice(1).map(part => {
      try { return decodeURIComponent(part); } catch { throw new HttpError(400, "路径编码无效"); }
    });
    if (url.pathname === "/register" && request.method === "POST") {
      this.rateLimit("register:" + keyHash(request.headers.get("CF-Connecting-IP") || "local"), 30);
      return this.register(parseObject(await readText(request, 32 * 1024)));
    }
    if (["/binding/request", "/binding/verify", "/binding/remove"].includes(url.pathname) && request.method === "POST") return this.binding(url.pathname, parseObject(await readText(request, 4096)), request);
    if (url.pathname === "/binding/status" && request.method === "GET") return this.binding(url.pathname, {}, request);
    if (url.pathname === "/device/manage" && request.method === "POST") return this.manage(parseObject(await readText(request, 32 * 1024)), request);
    if (url.pathname === "/ws" && request.method === "GET") return this.connect(request, url.searchParams.get("key"));
    if (parts[0] === "push" && parts[1] && ["GET", "POST"].includes(request.method)) {
      this.recipients(parts[1]);
      const payload = await parsePush(request, parts.slice(2));
      return this.push(parts[1], payload);
    }
    if (parts[0] === "webhook" && parts[1] && parts.length <= 3 && request.method === "POST") {
      this.recipients(parts[1]);
      return this.push(parts[1], { ...await parseWebhook(request, parts[2]), target: url.searchParams.get("target") || "all" });
    }
    throw new HttpError(404, "接口不存在");
  }

  authorize(key) {
    const device = this.device(key);
    if (!device || !equal(device.device_key, key)) throw new HttpError(401, "设备凭据无效或尚未同步");
    return device;
  }

  register(req) {
    if (typeof req.device_key !== "string" || !/^[A-Za-z0-9_-]{32}$/.test(req.device_key)) throw new HttpError(400, "设备 ID 无效");
    if (this.revoked(req.device_key)) throw new HttpError(409, "设备 ID 已注销，请使用新的设备 ID");
    req.platform ||= "mobile";
    if (!["mobile", "pc"].includes(req.platform)) throw new HttpError(400, "platform 必须为 mobile 或 pc");
    const device = this.device(req.device_key);
    if (device && device.platform !== req.platform) throw new HttpError(409, "设备 ID 已由其他端使用，请生成独立 ID 后绑定");
    const publicKey = req.public_key || device?.public_key;
    parsePublicKey(publicKey);
    if (req.name != null && (typeof req.name !== "string" || req.name.length > 256)) throw new HttpError(400, "设备名称无效");
    this.ctx.storage.transactionSync(() => {
      this.sql.exec("INSERT INTO devices(device_key,public_key,name,platform,endpoint_id) VALUES(?,?,?,?,?) ON CONFLICT(device_key) DO UPDATE SET public_key=excluded.public_key,name=excluded.name", req.device_key, publicKey, req.name || device?.name || "", req.platform, device?.endpoint_id || endpointID(req.platform));
      if (!device) {
        const group = this.sql.exec("INSERT INTO push_groups(push_key) VALUES(?) RETURNING id", req.device_key).one();
        const created = this.device(req.device_key);
        this.sql.exec("INSERT INTO device_groups(device_id,group_id) VALUES(?,?)", created.id, group.id);
      }
    });
    if (device && device.public_key !== publicKey) this.disconnect(device.id);
    return json({ success: true, device_key: req.device_key, endpoint_id: this.device(req.device_key).endpoint_id, message: device ? "Device updated" : "Device registered" });
  }

  manage(req, request) {
    if (typeof req.device_key !== "string" || req.device_key.length > 256 || !equal(request.headers.get("Authorization"), `Bearer ${req.device_key}`)) throw new HttpError(401, "设备凭据无效");
    if (!["reset_rsa", "reset_all", "unregister"].includes(req.action)) throw new HttpError(400, "设备操作无效");
    if (req.action !== "unregister") {
      parsePublicKey(req.public_key);
      if (req.public_key === req.expected_public_key) throw new HttpError(400, "请提供新的 RSA 公钥");
      if (req.action === "reset_all" && (!/^[A-Za-z0-9]{32}$/.test(req.new_device_key || "") || req.new_device_key === req.device_key)) throw new HttpError(400, "新的设备 ID 无效");
    }
    const device = this.device(req.device_key);
    if (!device) {
      if (req.action === "unregister" && this.revoked(req.device_key)) return json({ success: true });
      const updated = req.action === "reset_all" ? this.device(req.new_device_key) : null;
      if (updated && this.revoked(req.device_key) && updated.public_key === req.public_key) return json({ success: true });
      throw new HttpError(409, "设备状态已变化，请重试原操作");
    }
    if (req.action === "reset_rsa" && device.public_key === req.public_key) return json({ success: true });
    if (device.public_key !== req.expected_public_key) throw new HttpError(409, "设备公钥已变化，请重试原操作");
    if (req.action === "reset_all" && this.revoked(req.new_device_key)) throw new HttpError(409, "新的设备 ID 曾被注销");
    if (req.action === "reset_all" && this.device(req.new_device_key)) throw new HttpError(409, "新的设备 ID 已被注册");
    this.ctx.storage.transactionSync(() => {
      if (req.action === "unregister") this.removeMember(device);
      else {
        this.sql.exec("UPDATE devices SET device_key=?,public_key=? WHERE id=?", req.action === "reset_all" ? req.new_device_key : req.device_key, req.public_key, device.id);
        if (req.action === "reset_all") {
          const previous = this.groupFor(device.id);
          const group = this.sql.exec("INSERT INTO push_groups(push_key) VALUES(?) RETURNING id", req.new_device_key).one();
          this.sql.exec("UPDATE device_groups SET group_id=? WHERE device_id=?", group.id, device.id);
          this.sql.exec("DELETE FROM push_groups WHERE id=? AND NOT EXISTS(SELECT 1 FROM device_groups WHERE group_id=?)", previous.id, previous.id);
          this.sql.exec("DELETE FROM binding_requests WHERE pc_id=? OR mobile_id=?", device.id, device.id);
        }
      }
      if (req.action !== "reset_rsa") this.sql.exec("INSERT OR IGNORE INTO revoked_keys(hash) VALUES(?)", keyHash(req.device_key));
    });
    this.disconnect(device.id);
    return json({ success: true });
  }

  disconnect(deviceID) {
    for (const socket of this.ctx.getWebSockets(String(deviceID))) { try { socket.close(1000, "设备连接已更新"); } catch { /* Already closed. */ } }
  }

  async push(key, payload) {
    // Recheck after body parsing: registration or key reset may have interleaved.
    const devices = this.recipients(key, payload.target);
    this.rateLimit("push:" + devices[0].push_group_id, 120);
    const now = Date.now();
    this.sql.exec("DELETE FROM messages WHERE created_at<=?", now - retention);
    const deliveries = devices.map(recipient => {
      const count = this.sql.exec("SELECT COUNT(*) AS count FROM messages WHERE device_id=?", recipient.id).one().count;
      if (count >= maxPending) throw new HttpError(429, "待确认消息过多，请等待设备接收或消息过期后重试");
      const sockets = this.ctx.getWebSockets(String(recipient.id)).filter(socket =>
        socket.readyState === WebSocket.OPEN && equal(socket.deserializeAttachment()?.key, recipient.device_key));
      // Offline messages are persisted below and encrypted on reconnect.
      const data = sockets.length ? payload.envelope || encryptMessage(recipient.public_key, payload.fields, recipient.platform) : null;
      return { recipient, id: randomUUID(), sockets, data };
    });
    this.ctx.storage.transactionSync(() => {
      for (const delivery of deliveries) {
        const row = this.sql.exec("INSERT INTO messages(device_id,message_id,fields,envelope,created_at) VALUES(?,?,?,?,?) RETURNING sequence", delivery.recipient.id, delivery.id, payload.fields ? JSON.stringify(payload.fields) : null, payload.envelope ? JSON.stringify(payload.envelope) : null, now).one();
        delivery.sequence = row.sequence;
      }
    });
    for (const delivery of deliveries) {
      const message = { type: "message", id: delivery.id, timestamp: Math.floor(now / 1000), sequence: delivery.sequence, data: delivery.data };
      for (const socket of delivery.sockets) {
        try { socket.send(JSON.stringify(message)); } catch { try { socket.close(1011, "请重新连接"); } catch { /* Closed. */ } }
      }
    }
    await this.scheduleCleanup();
    return json({ success: true, message_id: deliveries[0].id, target_count: deliveries.length });
  }

  connect(request, key) {
    const device = this.authorize(key);
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") throw new HttpError(426, "请使用 WebSocket 连接");
    this.disconnect(device.id);
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    this.ctx.acceptWebSocket(server, [String(device.id)]);
    server.serializeAttachment({ key, deviceID: device.id, publicKeyHash: keyHash(device.public_key) });
    const now = Date.now();
    this.sql.exec("DELETE FROM messages WHERE created_at<=?", now - retention);
    try {
      // Preserve insertion order and re-encrypt with the current RSA key after reset.
      let publicKey;
      for (const row of this.sql.exec("SELECT * FROM messages WHERE device_id=? ORDER BY sequence", device.id)) {
        const data = row.envelope ? JSON.parse(row.envelope) : encryptMessageWithKey(publicKey ||= parsePublicKey(device.public_key), JSON.parse(row.fields), device.platform);
        server.send(JSON.stringify({ type: "message", id: row.message_id, sequence: row.sequence, timestamp: Math.floor(row.created_at / 1000), data }));
      }
    } catch {
      server.close(1011, "消息补发失败，请重新连接");
    }
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(socket, value) {
    if (typeof value !== "string" || value.length > 4096) { socket.close(1009, "消息过大"); return; }
    const attachment = socket.deserializeAttachment();
    const device = this.device(attachment?.key);
    if (!device || device.id !== attachment.deviceID || keyHash(device.public_key) !== attachment.publicKeyHash) { socket.close(1008, "设备凭据失效"); return; }
    try {
      const message = JSON.parse(value);
      if (message.type === "ack" && typeof message.id === "string") {
        this.sql.exec("DELETE FROM messages WHERE device_id=? AND message_id=?", device.id, message.id);
        await this.scheduleCleanup();
      }
    } catch { /* Invalid messages never acknowledge pending deliveries. */ }
  }

  webSocketClose(socket, code) { try { socket.close(code); } catch { /* Closed. */ } }
  webSocketError(socket) { try { socket.close(1011, "请重新连接"); } catch { /* Closed. */ } }

  async scheduleCleanup() {
    const next = this.sql.exec("SELECT MIN(created_at) AS oldest FROM messages").one().oldest;
    if (next == null) await this.ctx.storage.deleteAlarm();
    else await this.ctx.storage.setAlarm(Math.max(Date.now() + 1, next + retention));
  }

  async alarm() {
    this.sql.exec("DELETE FROM binding_requests WHERE expires_at<=?", Date.now());
    this.sql.exec("DELETE FROM messages WHERE created_at<=?", Date.now() - retention);
    this.sql.exec("DELETE FROM rate_limits WHERE window<?", Math.floor(Date.now() / 60000) - 1);
    await this.scheduleCleanup();
  }
}

Object.assign(PersonalDevice.prototype, { binding, recipients, removeMember, groupFor });
