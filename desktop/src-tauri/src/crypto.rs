use aes_gcm::{
    aead::{Aead, KeyInit},
    Aes256Gcm, Nonce,
};
use anyhow::{bail, Context, Result};
use base64::{engine::general_purpose::STANDARD, Engine};
use rand::{distributions::Alphanumeric, rngs::OsRng, Rng, RngCore};
use rsa::{
    pkcs8::{DecodePrivateKey, EncodePrivateKey, EncodePublicKey, LineEnding},
    Oaep, RsaPrivateKey,
};
use serde_json::Value;
use sha2::Sha256;

pub fn device_id() -> String {
    OsRng
        .sample_iter(&Alphanumeric)
        .take(32)
        .map(char::from)
        .collect()
}
pub fn sender_key() -> String {
    let mut key = [0u8; 32];
    OsRng.fill_bytes(&mut key);
    hex::encode(key)
}
pub fn key_pair() -> Result<(String, String)> {
    let private = RsaPrivateKey::new(&mut OsRng, 2048)?;
    Ok((
        private.to_public_key().to_public_key_pem(LineEnding::LF)?,
        private.to_pkcs8_pem(LineEnding::LF)?.to_string(),
    ))
}
fn decode(value: &Value) -> Result<Vec<u8>> {
    let text = value.as_str().context("密文格式无效")?;
    if text.len() > 12 * 1024 * 1024 {
        bail!("密文格式无效");
    }
    Ok(STANDARD.decode(text).context("密文格式无效")?)
}
pub fn open(key: &[u8], nonce: &[u8], data: &[u8]) -> Result<Vec<u8>> {
    if key.len() != 32 || nonce.len() != 12 || data.len() <= 16 {
        bail!("密文格式无效");
    }
    Aes256Gcm::new_from_slice(key)
        .map_err(|_| anyhow::anyhow!("密钥格式无效"))?
        .decrypt(Nonce::from_slice(nonce), data)
        .map_err(|_| anyhow::anyhow!("消息解密失败，请检查密钥"))
}
pub fn seal(key: &[u8], value: &[u8]) -> Result<String> {
    let mut nonce = [0u8; 12];
    OsRng.fill_bytes(&mut nonce);
    let encrypted = Aes256Gcm::new_from_slice(key)
        .map_err(|_| anyhow::anyhow!("密钥格式无效"))?
        .encrypt(Nonce::from_slice(&nonce), value)
        .map_err(|_| anyhow::anyhow!("本地身份加密失败"))?;
    Ok(STANDARD.encode([nonce.to_vec(), encrypted].concat()))
}
pub fn unseal(key: &[u8], value: &str) -> Result<Vec<u8>> {
    let data = STANDARD.decode(value).context("本地身份格式无效")?;
    if data.len() <= 28 {
        bail!("本地身份格式无效");
    }
    open(key, &data[..12], &data[12..])
}
pub fn decrypt(data: &Value, private_key: &str, sender_key: &str) -> Result<Value> {
    let plaintext = if data
        .get("encryption")
        .filter(|v| !v.is_null() && **v != "")
        .is_some()
    {
        if data["encryption"] != "aes-256-gcm" || sender_key.len() != 64 {
            bail!("请配置与发送端相同的加密密钥");
        }
        let key = hex::decode(sender_key).context("请配置与发送端相同的加密密钥")?;
        open(
            &key,
            &decode(&data["nonce"])?,
            &decode(&data["ciphertext"])?,
        )?
    } else {
        let bytes = decode(&data["encrypted_content"])?;
        if bytes.len() < 2 {
            bail!("密文格式无效");
        }
        let size = u16::from_be_bytes([bytes[0], bytes[1]]) as usize;
        if !(256..=512).contains(&size) || bytes.len() <= size + 30 {
            bail!("密文格式无效");
        }
        let private = RsaPrivateKey::from_pkcs8_pem(private_key).context("本地私钥无效")?;
        let key = private
            .decrypt(Oaep::new::<Sha256>(), &bytes[2..2 + size])
            .context("消息解密失败")?;
        open(&key, &bytes[2 + size..14 + size], &bytes[14 + size..])?
    };
    Ok(serde_json::from_slice(&plaintext).context("消息内容无效")?)
}

#[cfg(test)]
mod tests {
    use super::*;
    use rsa::{pkcs8::DecodePublicKey, RsaPublicKey};
    use serde_json::json;
    #[test]
    fn server_crypto_compatible_and_authenticated() {
        let (public, private) = key_pair().unwrap();
        let key = [7u8; 32];
        let nonce = [9u8; 12];
        let value = json!({"title":"测试","body":"电脑独立公钥","id":"job"});
        let ciphertext = Aes256Gcm::new_from_slice(&key)
            .unwrap()
            .encrypt(
                Nonce::from_slice(&nonce),
                serde_json::to_vec(&value).unwrap().as_slice(),
            )
            .unwrap();
        let wrapped = RsaPublicKey::from_public_key_pem(&public)
            .unwrap()
            .encrypt(&mut OsRng, Oaep::new::<Sha256>(), &key)
            .unwrap();
        let bytes = [
            (wrapped.len() as u16).to_be_bytes().to_vec(),
            wrapped,
            nonce.to_vec(),
            ciphertext.clone(),
        ]
        .concat();
        assert_eq!(
            decrypt(
                &json!({"encrypted_content":STANDARD.encode(bytes)}),
                &private,
                ""
            )
            .unwrap(),
            value
        );
        let encrypted = json!({"encryption":"aes-256-gcm","nonce":STANDARD.encode(nonce),"ciphertext":STANDARD.encode(&ciphertext)});
        assert_eq!(
            decrypt(&encrypted, &private, &hex::encode(key)).unwrap(),
            value
        );
        assert!(decrypt(&encrypted, &private, &hex::encode([8u8; 32])).is_err());
        assert!(decrypt(&encrypted, &private, "").is_err());
        let mut corrupt = ciphertext;
        corrupt[0] ^= 1;
        assert!(open(&key, &nonce, &corrupt).is_err());
        assert!(device_id().len() == 32 && device_id().chars().all(|c| c.is_ascii_alphanumeric()));
    }
}
