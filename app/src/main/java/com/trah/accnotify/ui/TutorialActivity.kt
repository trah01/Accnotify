package com.trah.accnotify.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.trah.accnotify.AccnotifyApp
import com.trah.accnotify.R
import com.trah.accnotify.databinding.ActivityTutorialBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class TutorialActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTutorialBinding
    private var isGetRequest = false

    private val examples = listOf(
        "选择示例模板...",
        "GET 请求示例",
        "POST 简单消息",
        "POST 带标题消息",
        "POST 多行消息",
        "POST 仅电脑",
        "POST 仅手机",
        "Webhook 通用",
        "Webhook GitHub",
        "POST 图片推送 (Base64)"
    )

    // Map to track which examples are GET requests
    private val getRequestExamples = setOf("GET 请求示例")
    // Map to track which examples are webhook requests
    private val webhookExamples = mapOf(
        "Webhook 通用" to "",
        "Webhook GitHub" to "/github"
    )

    private fun getExampleMessage(exampleName: String): String {
        val pushUrl = getPushUrl()
        return when (exampleName) {
            "GET 请求示例" -> "${pushUrl}/测试标题/这是一条测试消息"
            "POST 简单消息" -> """{
  "body": "这是一条简单的推送消息"
}"""
            "POST 带标题消息" -> """{
  "title": "服务器告警",
  "body": "CPU 使用率已达 95%"
}"""
            "POST 多行消息" -> """{
  "title": "任务完成",
  "body": "备份任务已完成\n耗时: 5分钟\n大小: 1.2GB"
}"""
            "POST 仅电脑" -> """{
  "body": "发送给同一推送 ID 下的全部电脑",
  "target": "pc"
}"""
            "POST 仅手机" -> """{
  "body": "发送给同一推送 ID 下的全部手机",
  "target": "mobile"
}"""
            "Webhook 通用" -> """{
  "title": "部署通知",
  "body": "生产环境部署完成\n版本: v2.1.0\n状态: 成功"
}"""
            "Webhook GitHub" -> """{
  "ref": "refs/heads/main",
  "commits": [
    {
      "message": "fix: resolve crash bug",
      "author": { "name": "developer" }
    }
  ],
  "repository": {
    "name": "accnotify",
    "full_name": "user/accnotify",
    "html_url": "https://github.com/user/accnotify"
  },
  "pusher": { "name": "developer" },
  "head_commit": {
    "message": "fix: resolve crash bug",
    "author": { "name": "developer" }
  },
  "compare": "https://github.com/user/accnotify/compare/abc...def"
}"""
            "POST 图片推送 (Base64)" -> run {
                val base64Image = getAppLogoBase64()
                """{
  "title": "图片推送测试",
  "body": "这是一条带图片的推送\n图片为应用 Logo",
  "image": "$base64Image"
}"""
            }
            else -> ""
        }
    }

    /**
     * 将 App logo 转换为 Base64 字符串
     */
    private fun getAppLogoBase64(): String {
        val drawable = ContextCompat.getDrawable(this, R.drawable.ic_launcher_foreground) ?: return ""
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, 128, 128)
        drawable.draw(canvas)
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
    }



    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply theme color overlay
        val themeOverlay = when (AccnotifyApp.getInstance().keyManager.themeColor) {
            "blue" -> R.style.ThemeOverlay_Blue
            "green" -> R.style.ThemeOverlay_Green
            "red" -> R.style.ThemeOverlay_Red
            "pink" -> R.style.ThemeOverlay_Pink
            "white" -> R.style.ThemeOverlay_White
            "black" -> R.style.ThemeOverlay_Black
            else -> R.style.ThemeOverlay_Blue
        }
        theme.applyStyle(themeOverlay, true)
        
        super.onCreate(savedInstanceState)
        binding = ActivityTutorialBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        setupInstructions()
    }


    private fun setupUI() {
        // Back button
        binding.btnBack.setOnClickListener {
            finish()
        }

        // Spinner setup
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, examples)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerExamples.adapter = adapter

        binding.spinnerExamples.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position > 0) {
                    val selectedExample = examples[position]
                    isGetRequest = getRequestExamples.contains(selectedExample)
                    val isWebhook = webhookExamples.containsKey(selectedExample)
                    val message = getExampleMessage(selectedExample)
                    binding.etMessage.setText(message)

                    // Update button text based on request type
                    binding.btnSendTest.text = when {
                        isGetRequest -> "发送 GET 请求"
                        isWebhook -> "发送 Webhook"
                        else -> "发送 POST 请求"
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Send test button
        binding.btnSendTest.setOnClickListener {
            sendTestPush()
        }
    }


    private fun getPushUrl(): String {
        val keyManager = AccnotifyApp.getInstance().keyManager
        val serverUrl = keyManager.serverUrl
        val deviceKey = keyManager.getPushKey() ?: "YOUR_DEVICE_KEY"
        return "$serverUrl/push/$deviceKey"
    }

    private fun getWebhookUrl(): String {
        val keyManager = AccnotifyApp.getInstance().keyManager
        val serverUrl = keyManager.serverUrl
        val deviceKey = keyManager.getPushKey() ?: "YOUR_DEVICE_KEY"
        return "$serverUrl/webhook/$deviceKey"
    }

    private fun getCurrentWebhookSuffix(): String {
        val selected = binding.spinnerExamples.selectedItem?.toString() ?: ""
        return webhookExamples[selected] ?: ""
    }

    private fun setupInstructions() {
        val pushUrl = getPushUrl()
        val markdown = """
## 快速开始

1. 在首页点击 **注册设备 / 同步连接**。
2. 点击 ID 旁的复制按钮，复制 **Push 链接（全部设备）**。
3. 向链接发送 GET 或 POST 请求，在历史消息中查看结果。

## 请求方式

**GET**：把标题和内容接在推送地址后，特殊字符需要进行 URL 编码。
```
${pushUrl}/标题/内容
```

**POST**：向推送地址提交 JSON。
```
POST ${pushUrl}
Content-Type: application/json

{"title":"标题","body":"内容"}
```

## 接收设备

不传 `target` 或设置为 `all`，发送给这个推送 ID 下的全部设备。

- `pc`：全部电脑。
- `mobile`：全部手机。
- 某台设备的标识：只发送给该设备。

只推送给全部电脑：
```json
{"body":"任务完成","target":"pc"}
```

可在首页复制 **Push 链接（仅本机）**。它使用 `?target=设备标识` 指定本机；其他设备的标识在 **设置 → 设备与消息提醒 → 同 ID 的设备** 中查看。

## 请求参数

- `body`：消息内容，必填。
- `title`：标题，可选。
- `image`：图片 URL 或 Base64，可选。
- `target`：接收设备，可选，默认 `all`。

带图片的推送：
```json
{"title":"图片通知","body":"正文","image":"<Base64 图片数据>"}
```

## 绑定手机和电脑

每台手机和电脑可独立使用，也可在 **同一服务器** 上共用推送 ID。

1. 在要加入的设备上打开 **设置 → 设备与消息提醒 → 绑定已有 ID**。
2. 粘贴已有设备的 Push 链接，点击 **发送绑定验证码**。
3. 在确认设备的历史消息中查看验证码，在发起设备上输入并点击 **确认绑定**。

验证码 5 分钟内有效。多个手机和电脑可以使用同一推送 ID，每台设备仍有独立标识和 RSA 密钥。在 **同 ID 的设备** 中解除绑定或移除一台设备，不影响其他成员。

## 消息提醒规则

在 **设置 → 设备与消息提醒 → 消息提醒规则** 中设置工作日、指定星期、提醒时段、每日静默时段和自定义日期，点击 **保存** 生效。

关闭通知或处于静默时段，消息仍保存到历史，恢复提醒后不会补发旧通知。结束早于开始表示跨午夜；开始与结束相同表示全天。

启用 **节假日静默** 或 **仅工作日** 时，在页面手动获取本年度节假日，或导入本地日历。获取成功会缓存全年数据，不会自动联网更新；没有本年度日历时，工作日按周一至周五判断。

## 发送端加密

在首页打开 **发送端加密密钥**，配置与发送脚本相同的密钥。每台设备需要分别配置；绑定不会自动同步密钥。此功能需要脚本主动加密，配置密钥不会自动加密普通推送。

详细步骤见 [项目 README](https://github.com/trah01/Accnotify/blob/main/README.md#发送端加密)。

## ID 与安全

- 首页点击 ID 可切换本机的公用或独立 ID；共用推送 ID 的设备需先解除绑定。
- 普通推送由服务端按各设备公钥分别加密，服务端仍可读取原始消息。
- 推送地址请妥善保管，建议使用 HTTPS。敏感内容使用 POST，避免正文出现在 URL 中。
- 仅重置 RSA 密钥保留推送地址；重置或移除某台设备不会使其他成员的共用地址失效。
        """.trimIndent()

        val markwon = io.noties.markwon.Markwon.builder(this)
            .usePlugin(object : io.noties.markwon.AbstractMarkwonPlugin() {
                override fun configureTheme(builder: io.noties.markwon.core.MarkwonTheme.Builder) {
                    builder.headingTextSizeMultipliers(floatArrayOf(1.35f, 1.2f, 1.1f, 1.05f, 1f, 1f))
                        .headingBreakHeight(0)
                }
            }).build()
        markwon.setMarkdown(binding.tvInstructions, markdown)
    }


    private fun sendTestPush() {
        val message = binding.etMessage.text.toString().trim()
        if (message.isEmpty()) {
            Toast.makeText(this, "请输入消息内容", Toast.LENGTH_SHORT).show()
            return
        }

        val selectedExample = binding.spinnerExamples.selectedItem?.toString() ?: ""
        val isWebhook = webhookExamples.containsKey(selectedExample)

        binding.btnSendTest.isEnabled = false
        binding.btnSendTest.text = "发送中..."

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val responseCode: Int
                
                if (isGetRequest) {
                    // GET request - message is the full URL
                    val url = URL(message)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000
                    responseCode = connection.responseCode
                    connection.disconnect()
                } else if (isWebhook) {
                    // Webhook request
                    val suffix = getCurrentWebhookSuffix()
                    val url = URL(getWebhookUrl() + suffix)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    // Add event header for specific webhook types
                    if (suffix == "/github") {
                        connection.setRequestProperty("X-GitHub-Event", "push")
                    } else if (suffix == "/gitea") {
                        connection.setRequestProperty("X-Gitea-Event", "push")
                    }
                    connection.doOutput = true
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000
                    connection.outputStream.use { os ->
                        os.write(message.toByteArray(Charsets.UTF_8))
                    }
                    responseCode = connection.responseCode
                    connection.disconnect()
                } else {
                    // POST request - message is JSON body
                    val url = URL(getPushUrl())
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    connection.doOutput = true
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000

                    connection.outputStream.use { os ->
                        os.write(message.toByteArray(Charsets.UTF_8))
                    }
                    responseCode = connection.responseCode
                    connection.disconnect()
                }

                withContext(Dispatchers.Main) {
                    binding.btnSendTest.isEnabled = true
                    binding.btnSendTest.text = when {
                        isGetRequest -> "发送 GET 请求"
                        isWebhook -> "发送 Webhook"
                        else -> "发送 POST 请求"
                    }

                    if (responseCode in 200..299) {
                        Toast.makeText(this@TutorialActivity, "推送成功，请查看历史消息", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@TutorialActivity, "推送失败: HTTP $responseCode", Toast.LENGTH_SHORT).show()
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.btnSendTest.isEnabled = true
                    binding.btnSendTest.text = when {
                        isGetRequest -> "发送 GET 请求"
                        isWebhook -> "发送 Webhook"
                        else -> "发送 POST 请求"
                    }
                    Toast.makeText(this@TutorialActivity, "发送失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
