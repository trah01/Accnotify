package com.trah.accnotify.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.trah.accnotify.AccnotifyApp
import com.trah.accnotify.R
import com.trah.accnotify.databinding.ActivityKeepAliveSettingsBinding
import com.trah.accnotify.util.KeepAliveHelper

class KeepAliveSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val overlay = when (AccnotifyApp.getInstance().keyManager.themeColor) {
            "blue" -> R.style.ThemeOverlay_Blue
            "green" -> R.style.ThemeOverlay_Green
            "red" -> R.style.ThemeOverlay_Red
            "pink" -> R.style.ThemeOverlay_Pink
            "black" -> R.style.ThemeOverlay_Black
            else -> R.style.ThemeOverlay_White
        }
        theme.applyStyle(overlay, true)
        super.onCreate(savedInstanceState)

        val binding = ActivityKeepAliveSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnBack.setOnClickListener { finish() }
        binding.btnAppSettings.setOnClickListener {
            if (!KeepAliveHelper.openAppSettings(this)) {
                showSettingsError()
            }
        }
        binding.btnAccessibilitySettings.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                showSettingsError()
            } catch (_: SecurityException) {
                showSettingsError()
            }
        }
        binding.btnBatteryOptimization.setOnClickListener {
            if (!KeepAliveHelper.requestIgnoreBatteryOptimization(this)) {
                showSettingsError()
            }
        }
    }

    private fun showSettingsError() {
        Toast.makeText(this, "无法打开设置，请在手机设置中手动操作", Toast.LENGTH_LONG).show()
    }
}
