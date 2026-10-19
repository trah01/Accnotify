package com.trah.accnotify.ui

import android.content.Intent
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.trah.accnotify.AccnotifyApp
import com.trah.accnotify.crypto.BindingException
import com.trah.accnotify.crypto.DeviceBindingClient
import com.trah.accnotify.service.WebSocketService
import kotlinx.coroutines.launch
import java.net.URI

object DeviceBindingDialog {
    fun show(fragment: Fragment) {
        val context = fragment.requireContext()
        val keys = AccnotifyApp.getInstance().keyManager
        val server = keys.serverUrl.trimEnd('/')
        val client = DeviceBindingClient(keys)
        val form = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, 0) }
        val link = NotificationUi.input(context, "同服务器的 Push 链接").apply { setSingleLine(true); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI }
        val code = NotificationUi.input(context, "6 位验证码").apply { setSingleLine(true); inputType = android.text.InputType.TYPE_CLASS_NUMBER; filters = arrayOf(android.text.InputFilter.LengthFilter(6)) }
        val send = NotificationUi.action(context, "发送验证码", primary = true)
        val verify = NotificationUi.action(context, "确认绑定", primary = true)
        val cancel = NotificationUi.action(context, "取消绑定请求")
        fun inputRow(input: android.widget.EditText, button: android.widget.TextView) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = context.resources.getDimensionPixelSize(com.trah.accnotify.R.dimen.spacing_gap)
                }
            }
            input.gravity = android.view.Gravity.CENTER_VERTICAL
            input.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            button.minWidth = context.resources.getDimensionPixelSize(com.trah.accnotify.R.dimen.control_action_width)
            button.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = context.resources.getDimensionPixelSize(com.trah.accnotify.R.dimen.spacing_control)
            }
            row.addView(input)
            row.addView(button)
            form.addView(row)
        }
        inputRow(link, send)
        inputRow(code, verify)
        form.addView(cancel)
        val scroll = android.widget.ScrollView(context).apply { addView(form) }
        val dialog = NotificationUi.dialog(context, "绑定已有 ID", scroll)
        fun feedback(message: String) { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
        fun busy(value: Boolean) { send.isEnabled = !value; verify.isEnabled = !value; cancel.isEnabled = !value; link.isEnabled = !value; code.isEnabled = !value }
        fun reconnect() {
            context.startService(Intent(context, WebSocketService::class.java).apply { action = WebSocketService.ACTION_CONNECT })
        }
        send.setOnClickListener {
            busy(true)
            fragment.lifecycleScope.launch {
                try {
                    val current = client.status(server)
                    check(!current.get("bound").asBoolean && current.getAsJsonArray("devices").size() == 1) { "请先解除当前绑定；已有多个成员的 ID 不能整体合并" }
                    check(keys.getPendingBinding(server)?.let { JsonParser.parseString(it).asJsonObject.has("code") } != true) { "请先完成或取消待确认的绑定" }
                    val uri = URI(link.text.toString().trim())
                    val match = Regex("^(.*)/push/([A-Za-z0-9_-]{32})/?$").matchEntire(uri.path) ?: error("推送链接无效")
                    val base = URI(uri.scheme, uri.authority, match.groupValues[1], null, null).toString().trimEnd('/')
                    check(base == server && uri.userInfo == null && uri.fragment == null) { "请填写同一服务器的 Push 链接" }
                    val target = uri.rawQuery?.split('&')?.firstOrNull { it.startsWith("target=") }?.substringAfter('=')
                        ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                    val body = JsonObject().apply { addProperty("group_key", match.groupValues[2]); if (!target.isNullOrEmpty()) addProperty("confirm_endpoint", target) }
                    val result = client.request(server, "/binding/request", body)
                    val pending = JsonObject().apply { addProperty("request_id", result.get("request_id").asString); addProperty("old_key", keys.getDeviceKey(server)); addProperty("new_device_key", keys.generateDeviceId()) }
                    keys.setPendingBinding(server, pending.toString())
                    feedback("验证码已发送到 ${result.get("confirm_name").asString}，请查看历史消息，5 分钟有效")
                } catch (e: Exception) { feedback(e.message ?: "发送失败") }
                finally { busy(false) }
            }
        }
        verify.setOnClickListener {
            busy(true)
            fragment.lifecycleScope.launch {
                try {
                    check(code.text.toString().matches(Regex("[0-9]{6}"))) { "请输入 6 位验证码" }
                    val pending = JsonParser.parseString(keys.getPendingBinding(server) ?: error("请先发送验证码")).asJsonObject
                    pending.addProperty("code", code.text.toString())
                    keys.setPendingBinding(server, pending.toString())
                    client.finishPending(server)
                    reconnect(); dialog.dismiss(); feedback("绑定成功")
                } catch (e: Exception) {
                    if (e is BindingException && e.status == 400) {
                        keys.getPendingBinding(server)?.let { val pending = JsonParser.parseString(it).asJsonObject; pending.remove("code"); keys.setPendingBinding(server, pending.toString()) }
                    }
                    feedback(e.message ?: "绑定失败")
                } finally { busy(false) }
            }
        }
        cancel.setOnClickListener {
            busy(true)
            fragment.lifecycleScope.launch {
                try {
                    try { client.finishPending(server) } catch (e: BindingException) { if (e.status != 400) throw e }
                    keys.setPendingBinding(server, null); reconnect(); feedback("已取消待处理请求")
                } catch (e: Exception) { feedback(e.message ?: "取消失败") }
                finally { busy(false) }
            }
        }
        dialog.show()
        if (keys.getPendingBinding(server) != null) feedback("请在确认设备的历史消息中查看验证码")
    }
}
