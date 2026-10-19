package com.trah.accnotify.util

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object OfficialServerConsent {
    private const val PREFS_NAME = "official_server_consent"
    private const val PREF_ACCEPTED = "accepted"

    fun isRequired(context: Context, serverUrl: String): Boolean =
        serverUrl.trim().toHttpUrlOrNull()?.host == "an.trah.cn" &&
            !context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_ACCEPTED, false)

    fun accept(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_ACCEPTED, true).commit()
}
