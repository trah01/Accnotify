package com.trah.accnotify.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.trah.accnotify.AccnotifyApp

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Only start if device is registered
            try {
                val app = AccnotifyApp.getInstance()
                if (app.keyManager.isRegistered != true) {
                    return
                }
            } catch (e: Exception) {
                return
            }

            // Start WebSocket service after boot
            try {
                WebSocketService.start(context)
            } catch (e: Exception) {
                Log.e("BootReceiver", "Failed to start WebSocket service after boot", e)
            }
        }
    }
}
