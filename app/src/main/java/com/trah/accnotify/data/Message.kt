package com.trah.accnotify.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

@Entity(tableName = "messages")
data class Message(
    @PrimaryKey
    val messageId: String,
    
    val title: String?,
    val body: String?,
    val group: String? = null,
    val icon: String? = null,
    val url: String? = null,
    val image: String? = null,
    // Legacy columns retained for database compatibility; incoming sound/badge are ignored.
    val sound: String? = null,
    val badge: Int = 0,
    val encryptedContent: String? = null,
    val decryptedContent: String? = null,
    val timestamp: Date = Date(),
    val isRead: Boolean = false
)
