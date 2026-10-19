package com.trah.accnotify.util

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.trah.accnotify.AccnotifyApp
import com.trah.accnotify.R
import com.trah.accnotify.ui.MainActivity
import android.app.Notification
import android.os.Bundle
import com.trah.accnotify.service.NotificationDismissReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object NotificationHelper {

    private const val MESSAGE_NOTIFICATION_ID = 100
    private const val SUMMARY_NOTIFICATION_ID = 101
    private const val SUMMARY_PREFIX = "accnotify_summary:"
    private const val EXTRA_MESSAGE_ID = "accnotify_message_id"
    private const val EXTRA_REVISION = "accnotify_revision"
    private const val EXTRA_GROUP_NAME = "accnotify_group_name"
    private val imageScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Synchronized
    fun showNotification(
        context: Context,
        messageId: String,
        title: String,
        body: String,
        group: String? = null,
        url: String? = null,
        image: String? = null,
        revision: String = messageId
    ) {
        if (!NotificationPolicy.shouldNotify(context)) {
            cancelNotification(context, messageId)
            return
        }
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationId = messageId.hashCode()

        // 处理消息体 - 简化 JSON 显示
        val displayBody = formatBodyForNotification(body)

        // Content intent - open app to view full message
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            putExtra("message_id", messageId)
            putExtra("open_messages", true)
            data = Uri.parse("accnotify://message/${Uri.encode(messageId)}")
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            contentIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // URL 打开动作（如果有）
        val urlIntent = if (!url.isNullOrEmpty()) {
            PendingIntent.getActivity(
                context,
                notificationId + 10000,
                Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else null

        val groupName = group?.trim()?.takeIf { it.isNotEmpty() }
        val groupKey = groupName?.let { "accnotify_group:$it" }
        val dismissIntent = PendingIntent.getBroadcast(
            context, notificationId,
            Intent(context, NotificationDismissReceiver::class.java).apply {
                data = Uri.parse("accnotify://dismiss/${Uri.encode(messageId)}")
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, MessageNotificationChannels.channelIdForGroup(group))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(displayBody)
            // 使用 BigTextStyle 显示更多内容
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(displayBody)
                .setBigContentTitle(title))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .setDeleteIntent(dismissIntent)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .addExtras(Bundle().apply {
                putString(EXTRA_MESSAGE_ID, messageId)
                putString(EXTRA_REVISION, revision)
                putString(EXTRA_GROUP_NAME, groupName)
            })
        if (groupKey != null) builder.setGroup(groupKey)

        // 添加 URL 打开按钮
        if (urlIntent != null) {
            builder.addAction(
                R.drawable.ic_fluent_link,
                "打开链接",
                urlIntent
            )
        }

        notificationManager.notify(messageId, MESSAGE_NOTIFICATION_ID, builder.build())
        refreshGroups(context)

        if (!image.isNullOrBlank()) {
            imageScope.launch {
                val bitmap = NotificationImageLoader.load(image) ?: return@launch
                synchronized(NotificationHelper) {
                    val current = notificationManager.activeNotifications.firstOrNull {
                        it.tag == messageId && it.id == MESSAGE_NOTIFICATION_ID
                    }
                    // A delayed download must not restore a deleted or superseded notification.
                    if (current?.notification?.extras?.getString(EXTRA_REVISION) == revision && NotificationPolicy.shouldNotify(context)) {
                        builder.setStyle(NotificationCompat.BigPictureStyle()
                            .bigPicture(bitmap)
                            .setBigContentTitle(title)
                            .setSummaryText(displayBody))
                            .setOnlyAlertOnce(true)
                        notificationManager.notify(messageId, MESSAGE_NOTIFICATION_ID, builder.build())
                    }
                }
            }
        }
    }

    @Synchronized
    fun cancelNotification(context: Context, messageId: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.activeNotifications.filter {
            it.tag == messageId || it.notification.extras.getString(EXTRA_MESSAGE_ID) == messageId
        }.forEach { manager.cancel(it.tag, it.id) }
        refreshGroups(context)
    }

    @Synchronized
    fun cancelAllMessageNotifications(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.activeNotifications.filter {
            it.notification.extras.containsKey(EXTRA_MESSAGE_ID) || it.tag?.startsWith(SUMMARY_PREFIX) == true
        }.forEach { manager.cancel(it.tag, it.id) }
    }

    @Synchronized
    fun refreshGroups(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val active = manager.activeNotifications
        val groups = active.filter {
            it.notification.extras.containsKey(EXTRA_MESSAGE_ID) && it.notification.group != null
        }.groupBy { it.notification.group }
        val summaryTags = mutableSetOf<String>()
        groups.forEach { (key, messages) ->
            if (messages.size < 2) return@forEach
            val tag = SUMMARY_PREFIX + key
            summaryTags.add(tag)
            val newest = messages.maxByOrNull { it.postTime }!!.notification
            val name = newest.extras.getString(EXTRA_GROUP_NAME).orEmpty()
            val contentIntent = PendingIntent.getActivity(
                context, key.hashCode(), Intent(context, MainActivity::class.java).apply {
                    putExtra("open_messages", true)
                    putExtra("message_group", name)
                    data = Uri.parse("accnotify://group/${Uri.encode(name)}")
                }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val style = NotificationCompat.InboxStyle().setSummaryText("${messages.size} 条消息")
            messages.sortedByDescending { it.postTime }.take(5).forEach {
                style.addLine(it.notification.extras.getCharSequence(Notification.EXTRA_TITLE) ?: "")
            }
            val summary = NotificationCompat.Builder(context, newest.channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(name)
                .setContentText("${messages.size} 条消息")
                .setStyle(style)
                .setGroup(key)
                .setGroupSummary(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setSilent(true)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .build()
            manager.notify(tag, SUMMARY_NOTIFICATION_ID, summary)
        }
        active.filter { it.tag?.startsWith(SUMMARY_PREFIX) == true && it.tag !in summaryTags }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    /**
     * 格式化消息体用于通知显示
     * 对于长 JSON 数据进行简化处理
     */
    private fun formatBodyForNotification(body: String): String {
        val trimmed = body.trim()
        
        // 检查是否包含 "--- 完整数据 ---" 分隔符，只显示摘要部分
        val separatorIndex = trimmed.indexOf("--- 完整数据 ---")
        if (separatorIndex > 0) {
            val summary = trimmed.substring(0, separatorIndex).trim()
            // 尝试将摘要部分的 JSON 转为可读文本
            val readable = try {
                val json = com.google.gson.JsonParser.parseString(summary)
                if (json.isJsonObject) {
                    json.asJsonObject.entrySet().joinToString("\n") { (k, v) ->
                        val value = if (v.isJsonPrimitive && v.asJsonPrimitive.isString) v.asString else v.toString()
                        "$k: $value"
                    }
                } else summary
            } catch (_: Exception) { summary }
            return readable + "\n(点击查看完整数据)"
        }
        
        // 检查是否是 JSON 格式
        if ((trimmed.startsWith("{") && trimmed.endsWith("}")) ||
            (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            // JSON 数据 - 尝试提取关键信息或简化显示
            return try {
                val gson = com.google.gson.Gson()
                val json = gson.fromJson(trimmed, com.google.gson.JsonObject::class.java)
                
                // 尝试提取常见的消息字段
                val messageField = json.get("message")?.asString
                    ?: json.get("text")?.asString
                    ?: json.get("content")?.asString
                    ?: json.get("body")?.asString
                
                if (messageField != null) {
                    messageField
                } else {
                    // 如果太长，截断并提示
                    if (trimmed.length > 300) {
                        trimmed.take(300) + "...\n(点击查看完整内容)"
                    } else {
                        trimmed
                    }
                }
            } catch (e: Exception) {
                // JSON 解析失败，直接显示
                if (trimmed.length > 300) {
                    trimmed.take(300) + "...\n(点击查看完整内容)"
                } else {
                    trimmed
                }
            }
        }
        
        // 非 JSON 数据
        return if (body.length > 500) {
            body.take(500) + "...\n(点击查看完整内容)"
        } else {
            body
        }
    }
}

