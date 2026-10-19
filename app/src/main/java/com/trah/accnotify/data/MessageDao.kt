package com.trah.accnotify.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: Message)

    @Query("SELECT sequence FROM notification_revisions WHERE messageId = :messageId")
    suspend fun lastSequence(messageId: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun recordRevision(revision: NotificationRevision)

    @Transaction
    suspend fun applyIncoming(messageId: String, sequence: Long, message: Message?): Boolean {
        if (sequence > 0 && (lastSequence(messageId) ?: 0) >= sequence) return false
        if (message == null) delete(messageId) else insert(message)
        if (sequence > 0) recordRevision(NotificationRevision(messageId, sequence))
        return true
    }

    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    fun getAllMessages(): Flow<List<Message>>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentMessages(limit: Int): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE messageId = :messageId")
    suspend fun getMessageById(messageId: String): Message?

    @Query("UPDATE messages SET isRead = 1 WHERE messageId = :messageId")
    suspend fun markAsRead(messageId: String)

    @Query("UPDATE messages SET isRead = 1")
    suspend fun markAllAsRead()

    @Query("DELETE FROM messages WHERE messageId = :messageId")
    suspend fun delete(messageId: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM messages WHERE isRead = 0")
    fun getUnreadCount(): Flow<Int>

    @Query("DELETE FROM messages WHERE timestamp < :cutoffDate")
    suspend fun deleteOlderThan(cutoffDate: Long)
}
