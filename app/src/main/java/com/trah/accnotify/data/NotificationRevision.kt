package com.trah.accnotify.data

import androidx.room.Entity
import androidx.room.PrimaryKey

// Retain only IDs and delivery order, including deletion tombstones for replay protection.
@Entity(tableName = "notification_revisions")
data class NotificationRevision(@PrimaryKey val messageId: String, val sequence: Long)
