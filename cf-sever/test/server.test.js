import assert from "node:assert/strict";
import { test } from "node:test";
import { spawn, execFileSync } from "node:child_process";
import { generateKeyPairSync, createDecipheriv, randomBytes, createCipheriv } from "node:crypto";
import { mkdtempSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { encryptMessage } from "../src/crypto.js";

const root = mkdtempSync(join(tmpdir(), "accnotify-cf-test-"));
const initialKey = "a".repeat(32), nextKey = "b".repeat(32);
const otherKey = "c".repeat(32);
const base = "http://127.0.0.1:18987";
const generate = () => generateKeyPairSync("rsa", { modulusLength: 2048, publicKeyEncoding: { type: "spki", format: "pem" }, privateKeyEncoding: { type: "pkcs8", format: "pem" } });

function decrypt(data, privateKey) {
  const bytes = Buffer.from(data.encrypted_content, "base64");
  const size = bytes.readUInt16BE();
  const path = join(root, "private.pem");
  writeFileSync(path, privateKey, { mode: 0o600 });
  // OpenSSL is an independent implementation of Android's mixed OAEP digests.
  const aes = execFileSync("openssl", ["pkeyutl", "-decrypt", "-inkey", path,
    "-pkeyopt", "rsa_padding_mode:oaep", "-pkeyopt", "rsa_oaep_md:sha256", "-pkeyopt", "rsa_mgf1_md:sha1"], { input: bytes.subarray(2, 2 + size) });
  const cipher = createDecipheriv("aes-256-gcm", aes, bytes.subarray(2 + size, 14 + size));
  cipher.setAuthTag(bytes.subarray(-16));
  return JSON.parse(Buffer.concat([cipher.update(bytes.subarray(14 + size, -16)), cipher.final()]).toString());
}

async function call(path, body, headers = {}) {
  const response = await fetch(base + path, body === undefined ? {} : { method: "POST", headers: { "Content-Type": "application/json", ...headers }, body: JSON.stringify(body) });
  return { status: response.status, data: await response.json() };
}

async function connect(key) {
  const ws = new WebSocket(base.replace("http", "ws") + "/ws?key=" + key);
  const queue = [], waiting = [];
  ws.addEventListener("message", event => {
    const value = JSON.parse(event.data);
    if (waiting.length) waiting.shift()(value); else queue.push(value);
  });
  await new Promise((resolve, reject) => { ws.addEventListener("open", resolve, { once: true }); ws.addEventListener("error", reject, { once: true }); });
  return { ws, next: () => queue.length ? Promise.resolve(queue.shift()) : new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("WebSocket delivery timeout")), 5000);
    waiting.push(value => { clearTimeout(timer); resolve(value); });
  }) };
}

test("Cloudflare 多设备隔离、加密、持久化和设备管理", { timeout: 90000 }, async () => {
  let process;
  let output = "";
  const start = async () => {
    process = spawn(processExec(), ["node_modules/wrangler/bin/wrangler.js", "dev", "--local", "--ip", "127.0.0.1", "--port", "18987", "--inspector-port", "0", "--persist-to", join(root, "state")], {
      cwd: new URL("..", import.meta.url), env: { ...globalThis.process.env, WRANGLER_SEND_METRICS: "false", XDG_CONFIG_HOME: root }, stdio: ["ignore", "pipe", "pipe"],
    });
    output = "";
    process.stdout.on("data", chunk => { output += chunk; });
    process.stderr.on("data", chunk => { output += chunk; });
    for (let i = 0; i < 200; i++) {
      if (process.exitCode != null) throw new Error(output);
      try { if ((await fetch(base + "/health")).ok) return; } catch { /* Starting. */ }
      await new Promise(resolve => setTimeout(resolve, 100));
    }
    throw new Error("Wrangler startup timeout: " + output);
  };
  const stop = async () => {
    if (process && process.exitCode == null) { process.kill("SIGTERM"); await new Promise(resolve => process.once("exit", resolve)); }
  };
  const sockets = [];
  try {
    const pair = generate(), replacement = generate(), otherPair = generate();
    const sample = { title: "中文标题", body: "第一行\n第二行", id: "通知1", delete: "1" };
    assert.deepEqual(decrypt(encryptMessage(pair.publicKey, sample), pair.privateKey), sample);
    await start();
    assert.equal((await fetch(base + "/register", { method: "OPTIONS" })).status, 204);
    assert.equal((await call("/register", { device_key: initialKey, public_key: pair.publicKey })).status, 200);
    assert.equal((await call("/register", { device_key: otherKey, public_key: otherPair.publicKey })).status, 200);
    assert.equal((await call(`/push/${initialKey}`, { delete: true })).status, 400);
    assert.equal((await call(`/push/${initialKey}`, { encryption: "aes-256-gcm", nonce: "bad", ciphertext: "bad" })).status, 400);
    const offline = await call(`/push/${initialKey}`, { id: "通知1", markdown: "**更新内容**" });
    if (offline.status !== 200) await new Promise(resolve => setTimeout(resolve, 200));
    assert.equal(offline.status, 200, output);
    const otherOffline = await call(`/push/${otherKey}`, { body: "另一设备的消息" });
    assert.equal(otherOffline.status, 200);
    await stop();
    await start();
    const other = await connect(otherKey); sockets.push(other.ws);
    const otherReplay = await other.next();
    assert.equal(otherReplay.id, otherOffline.data.message_id);
    assert.equal(decrypt(otherReplay.data, otherPair.privateKey).body, "另一设备的消息");
    // A device cannot acknowledge another device's pending notification.
    other.ws.send(JSON.stringify({ type: "ack", id: offline.data.message_id }));
    const connection = await connect(initialKey); sockets.push(connection.ws);
    const replay = await connection.next();
    assert.equal(replay.id, offline.data.message_id);
    const fields = decrypt(replay.data, pair.privateKey);
    assert.equal(fields.id, "通知1"); assert.equal(fields.body, "**更新内容**");
    connection.ws.send(JSON.stringify({ type: "ack", id: otherReplay.id }));
    await new Promise(resolve => setTimeout(resolve, 100));
    const otherCurrent = await connect(otherKey); sockets.push(otherCurrent.ws);
    assert.equal((await otherCurrent.next()).id, otherReplay.id);
    otherCurrent.ws.send(JSON.stringify({ type: "ack", id: otherReplay.id }));
    connection.ws.send(JSON.stringify({ type: "ack", id: replay.id }));
    const result = await call(`/push/${initialKey}`, { id: "通知1", delete: true });
    const removal = await connection.next();
    assert.equal(removal.id, result.data.message_id);
    assert.ok(removal.sequence > replay.sequence);
    assert.equal(decrypt(removal.data, pair.privateKey).delete, "1");
    connection.ws.send(JSON.stringify({ type: "ack", id: removal.id }));
    const nonce = randomBytes(12), aes = randomBytes(32), cipher = createCipheriv("aes-256-gcm", aes, nonce);
    const envelope = { encryption: "aes-256-gcm", nonce: nonce.toString("base64"), ciphertext: Buffer.concat([cipher.update('{"body":"发送端加密"}'), cipher.final(), cipher.getAuthTag()]).toString("base64") };
    assert.equal((await call(`/push/${initialKey}`, { ...envelope, title: "混合明文" })).status, 400);
    await call(`/push/${initialKey}`, envelope);
    const encrypted = await connection.next(); assert.deepEqual(encrypted.data, envelope);
    connection.ws.send(JSON.stringify({ type: "ack", id: encrypted.id }));
    const webhook = await call(`/webhook/${initialKey}/github`, { repository: { full_name: "test/repo" }, ref: "refs/heads/main", head_commit: { message: "更新" } }, { "X-GitHub-Event": "push" });
    assert.equal(webhook.status, 200);
    const hook = await connection.next(); assert.match(decrypt(hook.data, pair.privateKey).body, /完整数据/);
    connection.ws.send(JSON.stringify({ type: "ack", id: hook.id }));
    const simple = await call(`/push/${initialKey}/标题/正文`);
    assert.equal(simple.status, 200);
    const simpleMsg = await connection.next(); assert.equal(decrypt(simpleMsg.data, pair.privateKey).body, "正文");
    connection.ws.send(JSON.stringify({ type: "ack", id: simpleMsg.id }));
    await new Promise(resolve => setTimeout(resolve, 100));
    const second = await connect(initialKey); sockets.push(second.ws);
    const pending = await call(`/push/${initialKey}`, { body: "重置后补发" });
    const beforeReset = await second.next(); assert.equal(beforeReset.id, pending.data.message_id);
    const reset = { action: "reset_all", device_key: initialKey, expected_public_key: pair.publicKey, new_device_key: nextKey, public_key: replacement.publicKey };
    const auth = { Authorization: `Bearer ${initialKey}` };
    assert.equal((await call("/device/manage", reset)).status, 401);
    assert.equal((await call("/device/manage", { ...reset, expected_public_key: "stale" }, auth)).status, 409);
    assert.equal((await call("/device/manage", { ...reset, new_device_key: otherKey }, auth)).status, 409);
    assert.equal((await call("/device/manage", reset, auth)).status, 200);
    assert.equal((await call("/device/manage", reset, auth)).status, 200);
    assert.equal((await call(`/push/${initialKey}`, { body: "失效" })).status, 401);
    const current = await connect(nextKey); sockets.push(current.ws);
    const rekeyed = await current.next(); assert.equal(rekeyed.id, pending.data.message_id);
    assert.equal(decrypt(rekeyed.data, replacement.privateKey).body, "重置后补发");
    current.ws.send(JSON.stringify({ type: "ack", id: rekeyed.id }));
    const unregister = { action: "unregister", device_key: nextKey, expected_public_key: replacement.publicKey };
    assert.equal((await call("/device/manage", unregister, { Authorization: `Bearer ${nextKey}` })).status, 200);
    assert.equal((await call("/device/manage", unregister, { Authorization: `Bearer ${nextKey}` })).status, 200);
    assert.equal((await call("/register", { device_key: initialKey, public_key: pair.publicKey })).status, 409);
    // Other devices remain connected and registered after a peer resets/unregisters.
    assert.equal(otherCurrent.ws.readyState, WebSocket.OPEN);
    const survivor = await call(`/push/${otherKey}`, { body: "其他设备仍然在线" });
    const survivingMessage = await otherCurrent.next();
    assert.equal(survivingMessage.id, survivor.data.message_id);
    assert.equal(decrypt(survivingMessage.data, otherPair.privateKey).body, "其他设备仍然在线");
    otherCurrent.ws.send(JSON.stringify({ type: "ack", id: survivingMessage.id }));
    assert.equal((await call("/register", { device_key: "d".repeat(32), public_key: pair.publicKey })).status, 200);
    let limited;
    for (let i = 0; i < 241; i++) {
      limited = await call(`/push/${otherKey}`, { body: "频率限制验证" });
      if (limited.status === 429) break;
      assert.equal(limited.status, 200);
    }
    assert.equal(limited.status, 429);
    assert.match(limited.data.error, /频繁/);
    assert.equal((await call(`/push/${"d".repeat(32)}`, { body: "独立计数" })).status, 200);
    for (let i = 0; i < 61; i++) {
      limited = await call("/register", { device_key: "d".repeat(32), public_key: pair.publicKey });
      if (limited.status === 429) break;
      assert.equal(limited.status, 200);
    }
    assert.equal(limited.status, 429);
  } finally {
    for (const socket of sockets) socket.close();
    await stop();
    rmSync(root, { recursive: true, force: true });
  }
});

function processExec() { return globalThis.process.execPath; }
