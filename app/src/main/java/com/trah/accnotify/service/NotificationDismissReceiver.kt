package com.trah.accnotify.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.trah.accnotify.util.NotificationHelper

class NotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        NotificationHelper.refreshGroups(context)
    }
}
