# Accnotify Cloudflare 公共服务端

使用 Cloudflare Workers 和一个共享的 SQLite 型 Durable Object，支持公开注册和多个设备，不设置设备数量限制，不为每个设备创建实例，无需 D1、Docker 或自建服务器。在免费额度内可免费运行；连接、存储和免费用量仍受 Cloudflare 平台限制，额度耗尽时请求可能失败。本目录独立于现有 Go 服务端。

## 部署

需要 Node.js 22 或更新版本，以及 Cloudflare 账户。

```sh
cd cf-sever
npm ci
npx wrangler login
npm run deploy
```

首次部署会自动创建 Durable Object 数据库，不需要手动建库。记下输出中的 `https://accnotify-personal.<你的子域>.workers.dev` 地址，也可以在 Cloudflare 控制台为 Worker 添加自定义域名。

手机或电脑客户端添加这个 HTTPS 地址，选中后同步连接即可使用。也支持多台手机和电脑通过验证码共用推送 ID。

## 支持的接口

| 接口 | 用途 |
| --- | --- |
| `GET /health` | 检查服务是否运行 |
| `POST /register` | 公开注册或更新设备 ID 和 RSA 公钥 |
| `POST /device/manage` | 重置 RSA、重置全部身份、注销；与 Android 客户端兼容 |
| `GET /ws?key=设备凭据` | 每台设备独立连接、断线补发和 ACK 确认 |
| `POST /binding/request` | 发起绑定，只向确认设备发送验证码 |
| `POST /binding/verify` | 输入验证码，加入已有推送 ID |
| `GET /binding/status` | 查看推送 ID 和关联设备的标识 |
| `POST /binding/remove` | 解除本机绑定或移除同组设备 |
| `GET/POST /push/设备ID` | JSON、表单、查询参数推送 |
| `GET /push/设备ID/正文` | 简单推送 |
| `GET /push/设备ID/标题/正文` | 带标题推送 |
| `POST /webhook/设备ID` | JSON、表单、纯文本 Webhook |
| `POST /webhook/设备ID/类型` | `github`、`gitlab`、`docker`、`gitea`、`forgejo`、`feishu`、`dingtalk`、`wecom` |

示例（将地址和设备 ID 替换为自己的值）：

```sh
curl -X POST 'https://YOUR_WORKER.workers.dev/push/YOUR_DEVICE_ID' \
  -H 'Content-Type: application/json' \
  -d '{"title":"提醒","body":"测试通知","id":"reminder-1"}'
```

同一个字符串 `id` 可以更新通知；`{"id":"reminder-1","delete":1}` 删除通知。`markdown` 优先作为正文。每次发送有独立的 `message_id`、ACK 和递增的 `sequence`，操作通知的 `id` 在加密内容内。

手机保留 RSA-OAEP SHA-256 / MGF1 SHA-1 格式，电脑使用 SHA-256 / MGF1 SHA-256，消息内容均使用 AES-256-GCM。各设备独立保存公钥和待确认消息，某台设备的 ACK 不影响其他设备。已有发送端加密脚本可以继续使用 `encryption`、`nonce`、`ciphertext`；发送端密文原样转发，不与明文字段混用。

## 多设备推送

注册时 `platform` 为 `mobile` 或 `pc`，省略时按手机处理。每台有独立设备凭据和 `endpoint_id`；验证码绑定后共用 `push_key`，发送消息用共用的推送地址，连接和确认收件仍用本机凭据。

`target` 不填或为 `all` 时发给同 ID 的全部设备，`pc` 发给全部电脑，`mobile` 发给全部手机，也可以填某台设备的标识。JSON、表单和链接查询参数都支持，Webhook 可以加 `?target=pc`。只发电脑的例子：

```json
{"body":"任务完成","target":"pc"}
```

绑定需要同一服务器，验证码 5 分钟内有效。解除或移除只影响选中的设备，其他成员的共用推送地址保留。新推送 ID 为 32 位大小写字母和数字，旧 ID 仍兼容。

## 与 Go 版的差异

- 支持多个设备共享一个服务，但不包含 Go 版的管理后台、统计、实时日志和群发。
- Webhook 提取常用字段并保留完整 JSON；复杂卡片和部分事件的摘要排版与 Go 版不同，内容仍可在通知正文查看。
- 请求体上限 256 KB，明文消息或原始发送端密文上限 128 KB，每个设备最多保留 256 条待确认消息。超过限制返回错误，防止离线积压消耗资源。
- 注册及注册更新按来源 IP 限制每分钟 30 次；普通推送及 Webhook 合计按设备限制每分钟 120 次，使用固定分钟窗口，超出返回 429。计数保存在 SQLite 中，休眠或重启不清零。不同设备的推送计数独立，共用出口 IP 的注册次数共享。此限制用于控制正常使用和简单滥用，不保证抵御分布式攻击，公共运营需监控账户额度。
- 使用 WebSocket 休眠接口，不主动发送每 30 秒一次的 JSON 心跳。现有 Android 客户端的 WebSocket 协议 Ping 由 Cloudflare 自动回复，不影响休眠。
- ACK 后立即删除消息；未确认消息 5 分钟后停止补发，并由 Alarm 清理。平台可能延迟执行 Alarm，因此物理删除不保证精确到秒。SQLite 恢复历史属于 Cloudflare 平台能力，删除当前记录不代表恢复历史中的内容也立即消失。
- 为保持 RSA 重置后补发兼容，普通推送的待确认内容暂存在数据库中，发送时加密；离线设备上线后再加密补发，同一次补发复用公钥解析结果。发送端加密内容只保存密文。设备 ID 是推送凭据，请勿公开。
- 重部署不会清空设备或消息。不要随意修改 Durable Object 类名、绑定和对象名称，否则可能切换到新的数据空间。
- 本次开发版本切换到共享对象 `public-service`，不迁移旧单设备对象中的数据。Worker 名称、类名和绑定保持现有配置，以免误创建多个部署；`PersonalDevice` 是保留的内部类名，已经支持多设备。
- `/health` 仅检查 Worker 运行状态，不验证设备注册或消息投递。中国大陆网络下需实际确认 HTTPS、WebSocket 及手机后台连接稳定性。

## 本地运行和验证

```sh
npm run dev
```

本地数据保存在 `.wrangler/`，不会与线上共享。私有配置、数据库缓存和依赖已在本目录忽略。

```sh
npm run check
```

验证需要本机 OpenSSL，会启动本地 Wrangler，使用临时数据库和临时 RSA 密钥，检查多设备注册、消息隔离、跨设备 ACK 拒绝、加密、推送、WebSocket、重启持久化，以及设备重置和注销对其他设备的隔离。不会部署或连接 Cloudflare 账户。
