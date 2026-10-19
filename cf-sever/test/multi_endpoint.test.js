import assert from "node:assert/strict";
import { test } from "node:test";
import * as simulator from "miniflare";
import { generateKeyPairSync, privateDecrypt, createDecipheriv, constants } from "node:crypto";
import { execFileSync } from "node:child_process";
import { mkdtempSync, writeFileSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const generate = () => generateKeyPairSync("rsa", { modulusLength: 2048, publicKeyEncoding: { type: "spki", format: "pem" }, privateKeyEncoding: { type: "pkcs8", format: "pem" } });

test("本地 CF：绑定、mobile/pc 公钥隔离、按端路由与独立 ACK", { timeout: 45000 }, async () => {
  const root = mkdtempSync(join(tmpdir(), "accnotify-cf-binding-"));
  // Direct Miniflare avoids Wrangler's network update checks and authentication.
  const worker = { name: "accnotify-binding-test", modules: ["index.js", "http.js", "crypto.js", "push.js", "webhook.js", "binding.js"].map(name => ({ type: "ESModule", path: new URL("../src/" + name, import.meta.url).pathname,
    contents: readFileSync(new URL("../src/" + name, import.meta.url), "utf8").replace('console.error("Accnotify 请求处理失败", error.name)', 'console.error("测试异常位置", error.stack)') })),
    modulesRoot: new URL("../src", import.meta.url).pathname, compatibilityDate: "2025-08-01", compatibilityFlags: ["nodejs_compat"],
    durableObjects: { DEVICE: { className: "PersonalDevice", useSQLite: true } },
    outboundService: () => { throw new Error("测试禁止外网请求"); } };
  const options = simulator.convertV4MiniflareOptions
    ? simulator.convertV4MiniflareOptions({ workers: [worker], cf: false, telemetry: { enabled: false }, unsafeInspectDurableObjects: true, resourcePersistencePath: join(root, "state") })
    : { ...worker, cf: false, durableObjectsPersist: join(root, "state") };
  const mf = new simulator.Miniflare(options);
  const sockets = [];
  const call = async (route, body, key) => {
    const response = await mf.dispatchFetch("http://local" + route, { method: body === undefined ? "GET" : "POST",
      headers: { "Content-Type": "application/json", ...(key ? { Authorization: `Bearer ${key}` } : {}) }, ...(body !== undefined ? { body: JSON.stringify(body) } : {}) });
    return { status: response.status, data: await response.json() };
  };
  const connect = async key => {
    const response = await mf.dispatchFetch(`http://local/ws?key=${key}`, { headers: { Upgrade: "websocket" } });
    assert.equal(response.status, 101); const socket = response.webSocket; sockets.push(socket);
    const queue = [], waiters = [];
    socket.addEventListener("message", event => { const value = JSON.parse(event.data); if (waiters.length) waiters.shift()(value); else queue.push(value); });
    socket.accept();
    return { socket, next: () => queue.length ? Promise.resolve(queue.shift()) : new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("本地消息超时")), 5000); waiters.push(value => { clearTimeout(timer); resolve(value); });
    }) };
  };
  const decrypt = (wire, pair, pc = false) => {
    const bytes = Buffer.from(wire.data.encrypted_content, "base64"), size = bytes.readUInt16BE();
    let aes;
    if (pc) aes = privateDecrypt({ key: pair.privateKey, padding: constants.RSA_PKCS1_OAEP_PADDING, oaepHash: "sha256" }, bytes.subarray(2, 2 + size));
    else {
      const file = join(root, "phone.pem"); writeFileSync(file, pair.privateKey, { mode: 0o600 });
      aes = execFileSync("openssl", ["pkeyutl", "-decrypt", "-inkey", file, "-pkeyopt", "rsa_padding_mode:oaep", "-pkeyopt", "rsa_oaep_md:sha256", "-pkeyopt", "rsa_mgf1_md:sha1"], { input: bytes.subarray(2, 2 + size) });
    }
    const cipher = createDecipheriv("aes-256-gcm", aes, bytes.subarray(2 + size, 14 + size)); cipher.setAuthTag(bytes.subarray(-16));
    return JSON.parse(Buffer.concat([cipher.update(bytes.subarray(14 + size, -16)), cipher.final()]).toString());
  };
  try {
    const phone = generate(), computer = generate(), mobileKey = "M".repeat(32), pcKey = "P".repeat(32), newKey = "N".repeat(32);
    assert.equal((await call("/register", { device_key: mobileKey, public_key: phone.publicKey })).status, 200);
    assert.equal((await call("/register", { device_key: pcKey, public_key: computer.publicKey, platform: "pc" })).status, 200);
    assert.equal((await call("/register", { device_key: mobileKey, public_key: computer.publicKey, platform: "pc" })).status, 409);
    assert.equal((await call("/binding/request", { mobile_key: mobileKey })).status, 401);
    const mobile = await connect(mobileKey);
    const challenge = await call("/binding/request", { mobile_key: mobileKey }, pcKey);
    assert.equal(challenge.status, 200); assert.equal(challenge.data.code, undefined);
    const verificationMessage = await mobile.next(), fields = decrypt(verificationMessage, phone);
    const code = fields.body.match(/绑定验证码：(\d{6})/)[1];
    mobile.socket.send(JSON.stringify({ type: "ack", id: verificationMessage.id }));
    const verify = { request_id: challenge.data.request_id, code, new_device_key: newKey };
    assert.equal((await call("/binding/verify", verify, pcKey)).status, 200);
    assert.equal((await call("/binding/verify", verify, pcKey)).status, 200);
    assert.equal((await call("/register", { device_key: pcKey, public_key: computer.publicKey, platform: "pc" })).status, 409);
    const pc = await connect(newKey);
    const sent = await call(`/push/${mobileKey}`, { body: "all", id: "job" });
    assert.equal(sent.status, 200); assert.equal(sent.data.target_count, 2);
    const phoneWire = await mobile.next(), pcWire = await pc.next();
    assert.equal(decrypt(phoneWire, phone).body, "all"); assert.equal(decrypt(pcWire, computer, true).body, "all"); assert.notEqual(phoneWire.id, pcWire.id);
    mobile.socket.send(JSON.stringify({ type: "ack", id: phoneWire.id }));
    pc.socket.close();
    const replay = await connect(newKey), replayed = await replay.next();
    assert.equal(replayed.id, pcWire.id); assert.equal(decrypt(replayed, computer, true).body, "all");
    replay.socket.send(JSON.stringify({ type: "ack", id: replayed.id }));
    const onlyPC = await call(`/push/${mobileKey}?target=pc`, { body: "pc" });
    assert.equal(onlyPC.status, 200); assert.equal(onlyPC.data.target_count, 1); assert.equal(decrypt(await replay.next(), computer, true).body, "pc");
    assert.equal((await call(`/push/${mobileKey}?target=invalid`, { body: "bad" })).status, 404);
    assert.equal((await call("/binding/remove", {}, newKey)).status, 200);
    assert.equal((await call("/binding/status", undefined, newKey)).status, 401);
    assert.equal((await call(`/push/${mobileKey}?target=pc`, { body: "missing" })).status, 404);
    // Add another phone and another PC, then remove the initial phone. The
    // shared public ID must continue to address the remaining physical members.
    const storage = await mf.unsafeGetDurableObjectStorage("accnotify-binding-test", "PersonalDevice", { name: "public-service" });
    const members = [];
    for (const [letter, platform] of [["C", "mobile"], ["D", "pc"]]) {
      await storage.exec("DELETE FROM rate_limits WHERE scope LIKE 'bind:%'");
      const pair = generate(), key = letter.repeat(32), replacement = letter.toLowerCase().repeat(32);
      assert.equal((await call("/register", { device_key: key, public_key: pair.publicKey, platform })).status, 200);
      const request = await call("/binding/request", { group_key: mobileKey }, key);
      assert.equal(request.status, 200);
      const message = decrypt(await mobile.next(), phone), verification = message.body.match(/绑定验证码：(\d{6})/)[1];
      assert.equal((await call("/binding/verify", { request_id: request.data.request_id, code: verification, new_device_key: replacement }, key)).status, 200);
      const status = await call("/binding/status", undefined, replacement);
      members.push({ pair, platform, key: replacement, endpoint: status.data.endpoint_id });
    }
    assert.notEqual(members[0].endpoint, members[1].endpoint);
    const secondPhone = await connect(members[0].key), secondPC = await connect(members[1].key);
    const exact = await call(`/push/${mobileKey}`, { body: "one phone", target: members[0].endpoint });
    assert.equal(exact.data.target_count, 1); assert.equal(decrypt(await secondPhone.next(), members[0].pair).body, "one phone");
    assert.equal((await call("/device/manage", { action: "unregister", device_key: mobileKey, expected_public_key: phone.publicKey }, mobileKey)).status, 200);
    const afterRemoval = await call(`/push/${mobileKey}`, { body: "remaining" });
    assert.equal(afterRemoval.status, 200); assert.equal(afterRemoval.data.target_count, 2);
    assert.equal(decrypt(await secondPhone.next(), members[0].pair).body, "remaining");
    assert.equal(decrypt(await secondPC.next(), members[1].pair, true).body, "remaining");
    // Offline messages must survive a key reset and use the current public key.
    for (const [letter, platform] of [["E", "mobile"], ["F", "pc"]]) {
      const original = generate(), replacement = generate(), key = letter.repeat(32);
      assert.equal((await call("/register", { device_key: key, public_key: original.publicKey, platform })).status, 200);
      const pending = [];
      for (const body of ["离线消息一", "离线消息二"]) {
        const result = await call(`/push/${key}`, { body });
        assert.equal(result.status, 200);
        pending.push({ id: result.data.message_id, body });
      }
      assert.equal((await call("/device/manage", { action: "reset_rsa", device_key: key, expected_public_key: original.publicKey, public_key: replacement.publicKey }, key)).status, 200);
      const connection = await connect(key);
      for (const expected of pending) {
        const message = await connection.next();
        assert.equal(message.id, expected.id);
        assert.equal(decrypt(message, replacement, platform === "pc").body, expected.body);
        connection.socket.send(JSON.stringify({ type: "ack", id: message.id }));
      }
    }
  } finally { for (const socket of sockets) try { socket.close(); } catch {} await mf.dispose(); rmSync(root, { recursive: true, force: true }); }
});
