package com.trah.accnotify.ui

import android.content.Intent
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.trah.accnotify.AccnotifyApp
import com.trah.accnotify.crypto.DeviceBindingClient
import com.trah.accnotify.service.WebSocketService
import kotlinx.coroutines.launch

object BoundDevicesDialog {
    fun show(fragment: Fragment) {
        val keys = AccnotifyApp.getInstance().keyManager
        val server = keys.serverUrl.trimEnd('/')
        val client = DeviceBindingClient(keys)
        val loading = NotificationUi.dialog(fragment.requireContext(), "同 ID 的设备", message = "正在读取…")
        loading.show()
        fragment.lifecycleScope.launch {
            try {
                val result = client.status(server)
                loading.dismiss()
                if (!fragment.isAdded) return@launch
                val devices = result.getAsJsonArray("devices")
                val context = fragment.requireContext()
                val list = android.widget.LinearLayout(context).apply { orientation = android.widget.LinearLayout.VERTICAL }
                val scroll = android.widget.ScrollView(context).apply { addView(list) }
                val dialog = NotificationUi.dialog(context, "同 ID 的设备", scroll)
                devices.forEach { value ->
                    val device = value.asJsonObject
                    val name = device.get("name").asString.ifBlank { if (device.get("platform").asString == "pc") "电脑" else "手机" }
                    val row = android.widget.LinearLayout(context).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        setPadding(NotificationUi.dp(context, 8), NotificationUi.dp(context, 8), NotificationUi.dp(context, 8), NotificationUi.dp(context, 8))
                        minimumHeight = NotificationUi.dp(context, 48)
                        isClickable = true; isFocusable = true
                        val attr = android.util.TypedValue()
                        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, attr, true)
                        setBackgroundResource(attr.resourceId)
                        addView(NotificationUi.text(context, name + if (device.get("is_self").asBoolean) "（本机）" else ""))
                        addView(NotificationUi.text(context, (if (device.get("platform").asString == "pc") "电脑" else "手机") + " · " + device.get("endpoint_id").asString, secondary = true))
                        contentDescription = "$name，${device.get("endpoint_id").asString}，点击管理"
                    }
                    list.addView(row)
                    row.setOnClickListener {
                        dialog.dismiss()
                        val selected = device
                        val isSelf = selected.get("is_self").asBoolean
                        NotificationUi.dialog(context, if (isSelf) "解除本机绑定？" else "移除 $name？",
                            message = "这台设备将恢复独立 ID，本地历史保留；其他设备继续使用共用 ID。",
                            negative = "取消", positive = if (isSelf) "解除绑定" else "移除", onPositive = {
                                fragment.lifecycleScope.launch {
                                    try {
                                        val body = com.google.gson.JsonObject().apply { addProperty("device_id", selected.get("id").asLong) }
                                        client.request(server, "/binding/remove", body)
                                        if (isSelf) {
                                            keys.completeUnregister(server); client.register(server)
                                            if (fragment.isAdded) fragment.requireContext().startService(Intent(fragment.requireContext(), WebSocketService::class.java).apply { action = WebSocketService.ACTION_CONNECT })
                                        }
                                        if (fragment.isAdded) { Toast.makeText(fragment.requireContext(), if (isSelf) "已恢复独立 ID" else "已移除", Toast.LENGTH_SHORT).show(); show(fragment) }
                                    } catch (e: Exception) { if (fragment.isAdded) Toast.makeText(fragment.requireContext(), e.message, Toast.LENGTH_LONG).show() }
                                }
                            }).show()
                    }
                }
                dialog.show()
            } catch (e: Exception) { loading.dismiss(); if (fragment.isAdded) Toast.makeText(fragment.requireContext(), e.message ?: "读取失败", Toast.LENGTH_LONG).show() }
        }
    }
}
