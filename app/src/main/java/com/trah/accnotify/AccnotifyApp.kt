package com.trah.accnotify

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import com.trah.accnotify.crypto.KeyManager
import com.trah.accnotify.data.AppDatabase
import com.trah.accnotify.util.MessageNotificationChannels

class AccnotifyApp : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var keyManager: KeyManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize database
        database = AppDatabase.getInstance(this)

        // Initialize key manager
        keyManager = KeyManager(this)
        keyManager.getCommonSenderEncryptionKey()
        keyManager.getCommonDeviceId()
        keyManager.ensureKeysExist()

        // Apply saved light/dark mode
        val nightMode = when (keyManager.themeMode) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)

        // Create notification channels
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Delete old channel if it exists with wrong importance (IMPORTANCE_MIN)
            // Channel importance can't be programmatically increased after creation,
            // so we need to delete and recreate it.
            notificationManager.deleteNotificationChannel(CHANNEL_SERVICE)

            // Service notification channel (low priority - shows icon but no sound)
            // Using IMPORTANCE_LOW instead of IMPORTANCE_MIN so Android treats the
            // foreground service as important enough to maintain network connections.
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
            }
            notificationManager.createNotificationChannel(serviceChannel)

            MessageNotificationChannels.register(notificationManager)
        }
    }

    companion object {
        const val CHANNEL_SERVICE = "accnotify_service"
        const val CHANNEL_MESSAGES = "accnotify_messages"

        @Volatile
        private var instance: AccnotifyApp? = null

        fun getInstance(): AccnotifyApp {
            return instance ?: throw IllegalStateException("Application not initialized")
        }
    }
}
