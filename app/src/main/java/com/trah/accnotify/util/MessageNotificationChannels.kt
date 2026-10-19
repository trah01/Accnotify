package com.trah.accnotify.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import com.trah.accnotify.AccnotifyApp
import java.util.Locale

object MessageNotificationChannels {
    private data class ChannelDefinition(val id: String, val name: String, val group: String?)

    private val channels = listOf(
        ChannelDefinition(AccnotifyApp.CHANNEL_MESSAGES, "普通消息", null),
        ChannelDefinition("accnotify_messages_important", "重要提醒", "important"),
        ChannelDefinition("accnotify_messages_work", "工作通知", "work"),
        ChannelDefinition("accnotify_messages_life", "生活通知", "life")
    )

    fun register(manager: NotificationManager) {
        channels.forEach { definition ->
            val channel = NotificationChannel(
                definition.id, definition.name, NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "${definition.name}的声音、振动和显示方式"
                enableVibration(true)
                enableLights(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            // Stable IDs preserve the user's system notification settings.
            manager.createNotificationChannel(channel)
        }
    }

    fun channelIdForGroup(group: String?): String {
        val value = group?.trim()?.lowercase(Locale.ROOT)
        return channels.firstOrNull { it.group == value || it.name == value }?.id
            ?: AccnotifyApp.CHANNEL_MESSAGES
    }
}
