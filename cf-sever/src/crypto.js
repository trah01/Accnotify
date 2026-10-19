import { constants, createCipheriv, createHash, createPublicKey, publicEncrypt, randomBytes, timingSafeEqual } from "node:crypto";
import { Buffer } from "node:buffer";
import { HttpError } from "./http.js";

export function equal(a, b) {
  if (typeof a !== "string" || typeof b !== "string") return false;
  const x = Buffer.from(a), y = Buffer.from(b);
  return x.length === y.length && timingSafeEqual(x, y);
}

export function keyHash(value) { return createHash("sha256").update(value).digest("hex"); }

export function parsePublicKey(pem) {
  try {
    if (typeof pem !== "string" || !pem.startsWith("-----BEGIN PUBLIC KEY-----")) throw new Error();
    const key = createPublicKey(pem);
    const bits = key.asymmetricKeyDetails?.modulusLength;
    if (key.asymmetricKeyType !== "rsa" || bits < 2048 || bits > 4096) throw new Error();
    return key;
  } catch { throw new HttpError(400, "请同步有效的 RSA 公钥（2048–4096 位）"); }
}

function xorMask(target, seed) {
  for (let offset = 0, count = 0; offset < target.length; count++) {
    const counter = Buffer.alloc(4);
    counter.writeUInt32BE(count);
    const mask = createHash("sha1").update(seed).update(counter).digest();
    for (let i = 0; i < mask.length && offset < target.length; i++, offset++) target[offset] ^= mask[i];
  }
}

// RFC 8017 §7.1.1: Android Keystore requires OAEP SHA-256 + MGF1 SHA-1.
// Delegate the RSA public operation to the runtime; no private keys are stored.
export function encryptAndroidKey(key, message) {
  const size = Math.ceil(key.asymmetricKeyDetails.modulusLength / 8);
  if (message.length > size - 66) throw new Error("OAEP message too large");
  const encoded = Buffer.alloc(size);
  const seed = encoded.subarray(1, 33), db = encoded.subarray(33);
  createHash("sha256").update(Buffer.alloc(0)).digest().copy(db);
  db[db.length - message.length - 1] = 1;
  message.copy(db, db.length - message.length);
  randomBytes(32).copy(seed);
  xorMask(db, seed);
  xorMask(seed, db);
  return publicEncrypt({ key: key.export({ type: "spki", format: "pem" }), padding: constants.RSA_NO_PADDING }, encoded);
}

export function encryptMessage(pem, fields, platform = "mobile") {
  return encryptMessageWithKey(parsePublicKey(pem), fields, platform);
}

export function encryptMessageWithKey(key, fields, platform = "mobile") {
  const aes = randomBytes(32), nonce = randomBytes(12);
  const wrapped = platform === "pc" ? publicEncrypt({ key: key.export({ type: "spki", format: "pem" }), padding: constants.RSA_PKCS1_OAEP_PADDING, oaepHash: "sha256" }, aes) : encryptAndroidKey(key, aes);
  const cipher = createCipheriv("aes-256-gcm", aes, nonce);
  const ciphertext = Buffer.concat([cipher.update(JSON.stringify(fields), "utf8"), cipher.final(), cipher.getAuthTag()]);
  const length = Buffer.alloc(2);
  length.writeUInt16BE(wrapped.length);
  return { encrypted_content: Buffer.concat([length, wrapped, nonce, ciphertext]).toString("base64") };
}

function decodeBase64(value) {
  if (typeof value !== "string" || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(value)) {
    throw new HttpError(400, "密文和 nonce 必须使用标准 Base64");
  }
  const decoded = Buffer.from(value, "base64");
  if (decoded.toString("base64") !== value) throw new HttpError(400, "Base64 编码无效");
  return decoded;
}

export function senderEnvelope(req) {
  if (req.encryption !== "aes-256-gcm") throw new HttpError(400, "加密算法必须为 aes-256-gcm");
  if (decodeBase64(req.nonce).length !== 12) throw new HttpError(400, "nonce 必须为 12 字节");
  const size = decodeBase64(req.ciphertext).length;
  if (size <= 16 || size > 128 * 1024) throw new HttpError(400, "密文必须包含认证标签，且不超过 128 KB");
  return { encryption: req.encryption, nonce: req.nonce, ciphertext: req.ciphertext };
}
