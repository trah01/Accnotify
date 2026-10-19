#!/usr/bin/env python3
"""发送 AES-256-GCM 加密消息；配置来自环境变量或已忽略的本地 JSON 文件。"""

import base64
import json
import os
import re
import sys
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

from cryptography.hazmat.primitives.ciphers.aead import AESGCM


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def main():
    config = {}
    config_path = Path(__file__).with_name("send-encrypted.local.json")
    if config_path.exists():
        config = json.loads(config_path.read_text(encoding="utf-8"))
        if not isinstance(config, dict):
            raise ValueError("本地测试配置必须是 JSON 对象")
    key_hex = os.environ.get(
        "ACCNOTIFY_ENCRYPTION_KEY", config.get("encryption_key", "")
    )
    if not isinstance(key_hex, str):
        raise ValueError("加密密钥必须是字符串")
    key_hex = key_hex.strip()
    if not re.fullmatch(r"[0-9a-fA-F]{64}", key_hex):
        raise ValueError("请配置 App 中保存的 64 位十六进制加密密钥")
    key = bytes.fromhex(key_hex)

    endpoint = os.environ.get("ACCNOTIFY_PUSH_URL", config.get("push_url", ""))
    if not isinstance(endpoint, str):
        raise ValueError("Push 链接必须是字符串")
    endpoint = endpoint.strip()
    url = urlsplit(endpoint)
    if (url.scheme not in ("http", "https") or not url.hostname
            or url.query or url.fragment or url.username or url.password):
        raise ValueError("请配置首页复制的 Push 链接，不附带查询参数")

    message = {
        "title": "Python 加密测试",
        "body": "这是一条 Python 发送端加密测试消息。",
        "group": "加密测试",
        # 可按需添加 url、image 等消息字段，它们也会一起加密。
    }
    if message.get("delete") in ("1", 1, True):
        if not isinstance(message.get("id"), str) or not message["id"]:
            raise ValueError("删除消息必须提供字符串 id")
    else:
        content = message.get("markdown") or message.get("body")
        if not isinstance(content, str) or not content.strip():
            raise ValueError("消息需要非空的 body 或 markdown 字符串")
    plaintext = json.dumps(message, ensure_ascii=False).encode("utf-8")
    if len(plaintext) + 16 > 4 * 1024 * 1024:
        raise ValueError("加密后的消息不能超过 4 MB")

    nonce = os.urandom(12)
    # encrypt 返回密文和末尾的 16 字节认证标签；None 表示不使用 AAD。
    ciphertext = AESGCM(key).encrypt(nonce, plaintext, None)
    payload = {
        "encryption": "aes-256-gcm",
        "nonce": base64.b64encode(nonce).decode("ascii"),
        "ciphertext": base64.b64encode(ciphertext).decode("ascii"),
    }
    request = Request(
        endpoint,
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json",
            "User-Agent": "Accnotify-Python/1.0",
        },
        method="POST",
    )
    # 不跟随重定向，避免将请求转发到其他地址。
    with build_opener(NoRedirect()).open(request, timeout=30) as response:
        if response.status != 200:
            raise ValueError(f"推送失败，HTTP 状态码：{response.status}")
        result = json.load(response)
    if not isinstance(result, dict) or result.get("success") is not True:
        raise ValueError("服务器响应异常")
    print("加密消息已提交，消息 ID：", result.get("message_id", ""))


if __name__ == "__main__":
    try:
        main()
    except HTTPError as error:
        print(f"推送失败，HTTP 状态码：{error.code}", file=sys.stderr)
        sys.exit(1)
    except (URLError, TimeoutError, OSError):
        print("推送请求失败，请检查服务器地址和网络", file=sys.stderr)
        sys.exit(1)
    except ValueError as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
