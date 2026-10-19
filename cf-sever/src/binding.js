import { randomInt, randomBytes } from "node:crypto";
import { equal, keyHash } from "./crypto.js";
import { HttpError, json } from "./http.js";

export function endpointID(platform) { return `${platform}-${randomBytes(12).toString("hex")}`; }
export function initBinding() {
  const columns = this.sql.exec("PRAGMA table_info(devices)").toArray();
  if (!columns.some(c => c.name === "platform")) this.sql.exec("ALTER TABLE devices ADD COLUMN platform TEXT NOT NULL DEFAULT 'mobile' CHECK(platform IN ('mobile','pc'))");
  if (!columns.some(c => c.name === "endpoint_id")) this.sql.exec("ALTER TABLE devices ADD COLUMN endpoint_id TEXT NOT NULL DEFAULT ''");
  for (const row of this.sql.exec("SELECT id,platform FROM devices WHERE endpoint_id=''")) this.sql.exec("UPDATE devices SET endpoint_id=? WHERE id=?", endpointID(row.platform), row.id);
  this.sql.exec("CREATE UNIQUE INDEX IF NOT EXISTS device_endpoint ON devices(endpoint_id)");
  this.sql.exec("CREATE TABLE IF NOT EXISTS push_groups(id INTEGER PRIMARY KEY AUTOINCREMENT,push_key TEXT UNIQUE NOT NULL)");
  this.sql.exec("CREATE TABLE IF NOT EXISTS device_groups(device_id INTEGER PRIMARY KEY,group_id INTEGER NOT NULL)");
  this.sql.exec("CREATE INDEX IF NOT EXISTS device_group ON device_groups(group_id)");
  this.sql.exec("INSERT OR IGNORE INTO push_groups(push_key) SELECT device_key FROM devices WHERE id NOT IN (SELECT device_id FROM device_groups)");
  this.sql.exec("INSERT OR IGNORE INTO device_groups(device_id,group_id) SELECT d.id,g.id FROM devices d JOIN push_groups g ON g.push_key=d.device_key");
  this.sql.exec("CREATE TABLE IF NOT EXISTS binding_requests(id TEXT PRIMARY KEY,pc_id INTEGER,mobile_id INTEGER,group_id INTEGER,credential_hash TEXT,code_hash TEXT,expires_at INTEGER,attempts INTEGER DEFAULT 0,consumed INTEGER DEFAULT 0,result_key TEXT DEFAULT '',created_at INTEGER)");
}
export function groupFor(deviceID) {
  const group = this.sql.exec("SELECT g.* FROM push_groups g JOIN device_groups m ON m.group_id=g.id WHERE m.device_id=?", deviceID).toArray()[0];
  if (!group) throw new HttpError(409, "设备状态已变化，请重新同步");
  return group;
}
export function recipients(key, target = "all") {
  target ||= "all"; if (target === "desktop") target = "pc";
  const devices = this.sql.exec("SELECT d.*,g.id AS push_group_id FROM devices d JOIN device_groups m ON m.device_id=d.id JOIN push_groups g ON g.id=m.group_id WHERE g.push_key=? AND (?='all' OR d.platform=? OR d.endpoint_id=?) ORDER BY d.id", key, target, target, target).toArray();
  if (!devices.length) throw new HttpError(404, "指定的接收设备不存在，或推送地址已失效");
  return devices;
}
function actor(request) {
  const header = request.headers.get("Authorization") || "";
  if (!header.startsWith("Bearer ")) throw new HttpError(401, "设备凭据无效");
  return this.authorize(header.slice(7));
}
export async function binding(path, req, request) {
  this.sql.exec("DELETE FROM binding_requests WHERE expires_at<?", Date.now());
  if (path === "/binding/verify") {
    if (typeof req.request_id !== "string" || typeof req.code !== "string" || !/^\d{6}$/.test(req.code) || !/^[A-Za-z0-9]{32}$/.test(req.new_device_key || "")) throw new HttpError(400, "验证码或设备参数无效");
    const header = request.headers.get("Authorization") || ""; if (!header.startsWith("Bearer ")) throw new HttpError(401, "设备凭据无效");
    const key = header.slice(7), row = this.sql.exec("SELECT * FROM binding_requests WHERE id=?", req.request_id).toArray()[0];
    const invalid = () => new HttpError(400, "绑定请求无效、已过期或验证码错误");
    if (!row || row.attempts >= 5 || !equal(row.credential_hash, keyHash(key))) throw invalid();
    if (!equal(row.code_hash, keyHash(row.id + ":" + req.code))) { this.sql.exec("UPDATE binding_requests SET attempts=attempts+1 WHERE id=?", row.id); throw invalid(); }
    const group = this.sql.exec("SELECT * FROM push_groups WHERE id=?", row.group_id).toArray()[0]; if (!group) throw invalid();
    if (row.consumed) {
      const current = this.sql.exec("SELECT d.device_key,m.group_id FROM devices d JOIN device_groups m ON m.device_id=d.id WHERE d.id=?", row.pc_id).toArray()[0];
      if (row.result_key !== req.new_device_key || current?.device_key !== row.result_key || current?.group_id !== row.group_id) throw invalid();
    } else {
      const candidate = this.device(key), confirming = this.sql.exec("SELECT group_id FROM device_groups WHERE device_id=?", row.mobile_id).toArray()[0];
      if (candidate?.id !== row.pc_id || confirming?.group_id !== row.group_id || this.device(req.new_device_key) || this.revoked(req.new_device_key) || this.sql.exec("SELECT 1 FROM push_groups WHERE push_key=?", req.new_device_key).toArray().length) throw invalid();
      const previous = this.groupFor(candidate.id);
      if (this.sql.exec("SELECT COUNT(*) AS n FROM device_groups WHERE group_id=?", previous.id).one().n !== 1) throw invalid();
      this.ctx.storage.transactionSync(() => {
        this.sql.exec("UPDATE device_groups SET group_id=? WHERE device_id=?", group.id, candidate.id);
        this.sql.exec("UPDATE devices SET device_key=? WHERE id=?", req.new_device_key, candidate.id);
        this.sql.exec("INSERT OR IGNORE INTO revoked_keys(hash) VALUES(?)", keyHash(key));
        this.sql.exec("DELETE FROM messages WHERE device_id=?", candidate.id);
        this.sql.exec("DELETE FROM push_groups WHERE id=?", previous.id);
        this.sql.exec("UPDATE binding_requests SET consumed=1,result_key=? WHERE id=?", req.new_device_key, row.id);
      });
      this.disconnect(candidate.id);
    }
    return json({ success: true, device_key: req.new_device_key, push_key: group.push_key });
  }
  const device = actor.call(this, request), ownGroup = this.groupFor(device.id);
  if (path === "/binding/status") {
    const devices = this.sql.exec("SELECT d.id,d.name,d.platform,d.endpoint_id FROM devices d JOIN device_groups m ON m.device_id=d.id WHERE m.group_id=? ORDER BY d.id", ownGroup.id).toArray().map(item => ({ ...item, is_self: item.id === device.id }));
    return json({ success: true, push_key: ownGroup.push_key, bound: ownGroup.push_key !== device.device_key, endpoint_id: device.endpoint_id, devices });
  }
  if (path === "/binding/remove") {
    const target = this.sql.exec("SELECT * FROM devices WHERE id=?", req.device_id || device.id).toArray()[0];
    if (!target || this.groupFor(target.id).id !== ownGroup.id) throw new HttpError(400, "设备不存在或无权移除");
    this.ctx.storage.transactionSync(() => this.removeMember(target)); this.disconnect(target.id); return json({ success: true });
  }
  if (ownGroup.push_key !== device.device_key || this.sql.exec("SELECT COUNT(*) AS n FROM device_groups WHERE group_id=?", ownGroup.id).one().n !== 1) throw new HttpError(409, "请先解除当前绑定；已有多个成员的 ID 不能整体合并");
  const targetKey = req.group_key || req.mobile_key;
  const targets = this.recipients(targetKey, req.confirm_endpoint);
  const confirming = req.confirm_endpoint ? targets[0] : targets.find(item => item.platform === "mobile") || targets[0];
  if (confirming.push_group_id === ownGroup.id) throw new HttpError(400, "不能绑定当前设备所属的 ID");
  this.rateLimit("bind:ip:" + keyHash(request.headers.get("CF-Connecting-IP") || "local"), 5);
  this.rateLimit("bind:device:" + device.id, 1); this.rateLimit("bind:group:" + confirming.push_group_id, 1);
  const id = crypto.randomUUID(), code = String(randomInt(1000000)).padStart(6, "0"), now = Date.now();
  this.sql.exec("INSERT INTO binding_requests(id,pc_id,mobile_id,group_id,credential_hash,code_hash,expires_at,created_at) VALUES(?,?,?,?,?,?,?,?)", id, device.id, confirming.id, confirming.push_group_id, keyHash(device.device_key), keyHash(id + ":" + code), now + 300000, now);
  await this.push(targetKey, { target: confirming.endpoint_id, fields: { title: "绑定设备", body: `设备：${device.name}（${device.endpoint_id}）\n绑定验证码：${code}\n5 分钟内有效。请在发起绑定的设备输入；如果不是你操作，请忽略。`, group: "device" }, envelope: null });
  return json({ success: true, request_id: id, expires_in: 300, confirm_name: confirming.name, confirm_endpoint: confirming.endpoint_id });
}
export function removeMember(device) {
  const group = this.groupFor(device.id);
  this.sql.exec("INSERT OR IGNORE INTO revoked_keys(hash) VALUES(?)", keyHash(device.device_key));
  this.sql.exec("DELETE FROM messages WHERE device_id=?", device.id);
  this.sql.exec("DELETE FROM binding_requests WHERE pc_id=? OR mobile_id=?", device.id, device.id);
  this.sql.exec("DELETE FROM device_groups WHERE device_id=?", device.id);
  this.sql.exec("DELETE FROM devices WHERE id=?", device.id);
  this.sql.exec("DELETE FROM push_groups WHERE id=? AND NOT EXISTS(SELECT 1 FROM device_groups WHERE group_id=?)", group.id, group.id);
}
