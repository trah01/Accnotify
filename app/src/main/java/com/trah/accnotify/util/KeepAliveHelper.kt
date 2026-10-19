package com.trah.accnotify.util

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Comprehensive keep-alive helper for various Android manufacturers
 * Provides quick jumps to system settings for background protection
 */
object KeepAliveHelper {

    private const val TAG = "KeepAliveHelper"

    /**
     * Device manufacturer types
     */
    enum class Manufacturer {
        XIAOMI, HUAWEI, OPPO, VIVO, SAMSUNG, ONEPLUS, MEIZU, REALME, ASUS, LENOVO, SONY, LG, GOOGLE, OTHER
    }

    /**
     * Get current device manufacturer
     */
    fun getManufacturer(): Manufacturer {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        
        return when {
            manufacturer.contains("xiaomi") || brand.contains("xiaomi") || 
            manufacturer.contains("redmi") || brand.contains("redmi") ||
            brand.contains("poco") -> Manufacturer.XIAOMI
            
            manufacturer.contains("huawei") || brand.contains("huawei") ||
            manufacturer.contains("honor") || brand.contains("honor") -> Manufacturer.HUAWEI
            
            manufacturer.contains("oppo") || brand.contains("oppo") ||
            brand.contains("realme") || manufacturer.contains("realme") -> {
                if (brand.contains("realme") || manufacturer.contains("realme")) 
                    Manufacturer.REALME 
                else 
                    Manufacturer.OPPO
            }
            
            manufacturer.contains("vivo") || brand.contains("vivo") ||
            brand.contains("iqoo") -> Manufacturer.VIVO
            
            manufacturer.contains("samsung") || brand.contains("samsung") -> Manufacturer.SAMSUNG
            
            manufacturer.contains("oneplus") || brand.contains("oneplus") -> Manufacturer.ONEPLUS
            
            manufacturer.contains("meizu") || brand.contains("meizu") -> Manufacturer.MEIZU
            
            manufacturer.contains("asus") || brand.contains("asus") -> Manufacturer.ASUS
            
            manufacturer.contains("lenovo") || brand.contains("lenovo") ||
            brand.contains("zuk") -> Manufacturer.LENOVO
            
            manufacturer.contains("sony") || brand.contains("sony") -> Manufacturer.SONY
            
            manufacturer.contains("lge") || manufacturer.contains("lg") -> Manufacturer.LG
            
            manufacturer.contains("google") || brand.contains("google") -> Manufacturer.GOOGLE
            
            else -> Manufacturer.OTHER
        }
    }

    /**
     * Get manufacturer display name
     */
    fun getManufacturerName(): String {
        return when (getManufacturer()) {
            Manufacturer.XIAOMI -> "小米/Redmi"
            Manufacturer.HUAWEI -> "华为/荣耀"
            Manufacturer.OPPO -> "OPPO"
            Manufacturer.VIVO -> "vivo/iQOO"
            Manufacturer.SAMSUNG -> "三星"
            Manufacturer.ONEPLUS -> "一加"
            Manufacturer.MEIZU -> "魅族"
            Manufacturer.REALME -> "Realme"
            Manufacturer.ASUS -> "华硕"
            Manufacturer.LENOVO -> "联想"
            Manufacturer.SONY -> "索尼"
            Manufacturer.LG -> "LG"
            Manufacturer.GOOGLE -> "Google Pixel"
            Manufacturer.OTHER -> Build.MANUFACTURER
        }
    }

    // ========== Status Check Methods ==========

    /**
     * Check if battery optimization is ignored
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Check if accessibility service is enabled
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expectedComponentName = ComponentName(context, "com.trah.accnotify.service.KeepAliveAccessibilityService")
        val enabledServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabledServices?.contains(expectedComponentName.flattenToString()) == true
    }

    /**
     * Open accessibility settings
     */
    fun openAccessibilitySettings(context: Context): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open accessibility settings", e)
            false
        }
    }

    // ========== System Intent Methods ==========

    /**
     * Request to be excluded from battery optimization (Doze whitelist)
     */
    fun requestIgnoreBatteryOptimization(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        
        if (isIgnoringBatteryOptimizations(context)) {
            Toast.makeText(context, "✓ 已忽略电池优化", Toast.LENGTH_SHORT).show()
            return true
        }

        return try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request battery optimization exemption", e)
            openBatterySettings(context)
        }
    }

    /**
     * Open battery optimization settings list
     */
    fun openBatterySettings(context: Context): Boolean {
        return tryStartActivity(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    /**
     * Open app details settings
     */
    fun openAppSettings(context: Context): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app settings", e)
            false
        }
    }

    // ========== Manufacturer-Specific Methods ==========

    /**
     * Open manufacturer auto-start settings
     */
    fun openAutoStartSettings(context: Context): Boolean {
        val intents = getAutoStartIntents()
        
        for (intent in intents) {
            if (tryStartActivity(context, intent)) {
                return true
            }
        }
        
        // Fallback to app settings
        Toast.makeText(context, "请在应用设置中启用自启动权限", Toast.LENGTH_LONG).show()
        return openAppSettings(context)
    }

    /**
     * Open manufacturer battery/power management settings
     */
    fun openBatteryManagementSettings(context: Context): Boolean {
        val intents = getBatteryManagementIntents()
        
        for (intent in intents) {
            if (tryStartActivity(context, intent)) {
                return true
            }
        }
        
        // Fallback to standard battery settings
        return openBatterySettings(context)
    }

    /**
     * Open background activity settings (restrict/allow)
     */
    fun openBackgroundSettings(context: Context): Boolean {
        val intents = getBackgroundSettingsIntents(context)
        
        for (intent in intents) {
            if (tryStartActivity(context, intent)) {
                return true
            }
        }
        
        // Fallback to app settings
        return openAppSettings(context)
    }

    /**
     * Open notification settings for the app
     */
    fun openNotificationSettings(context: Context): Boolean {
        return try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open notification settings", e)
            false
        }
    }

    // ========== Intent Lists by Manufacturer ==========

    private fun getAutoStartIntents(): List<Intent> {
        val intents = mutableListOf<Intent>()
        
        when (getManufacturer()) {
            Manufacturer.XIAOMI -> {
                intents.add(Intent().setComponent(ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )))
                intents.add(Intent().setComponent(ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.powercenter.PowerSettings"
                )))
            }
            
            Manufacturer.HUAWEI -> {
                intents.add(Intent().setComponent(ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )))
                intents.add(Intent().setComponent(ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
                )))
            }
            
            Manufacturer.OPPO, Manufacturer.REALME -> {
                intents.add(Intent().setComponent(ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                )))
                intents.add(Intent().setComponent(ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity"
                )))
                intents.add(Intent().setComponent(ComponentName(
                    "com.oplus.safecenter",
                    "com.oplus.safecenter.permission.startup.StartupAppListActivity"
                )))
                intents.add(Intent().setComponent(ComponentName(
                    "com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity"
                )))
            }
            
            Manufacturer.ONEPLUS -> {
                intents.add(Intent().setComponent(ComponentName(
                    "com.oneplus.security",
                    "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
                )))
                // OnePlus uses ColorOS now - add ColorOS intents directly
                intents.add(Intent().setComponent(ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                )))
                intents.add(Intent().setComponent(ComponentName(
                    "com.oplus.safecenter",
                    "com.oplus.safecenter.permission.startup.StartupAppListActivity"
                )))
            }
            
            Manufacturer.VIVO -> {
                intents.add(Intent().setComponent(ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                )))
                intents.add(Intent().setComponent(ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
                )))
            }
            
            Manufacturer.SAMSUNG -> {
                intents.add(Intent().setComponent(ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.battery.ui.BatteryActivity"
                )))
                intents.add(Intent().setComponent(ComponentName(
                    "com.samsung.android.sm",
                    "com.samsung.android.sm.battery.ui.BatteryActivity"
                )))
            }
            
            Manufacturer.MEIZU -> {
                intents.add(Intent().setComponent(ComponentName(
                    "com.meizu.safe",
                    "com.meizu.safe.permission.SmartBGActivity"
                )))
            }
            
            Manufacturer.ASUS -> {
                intents.add(Intent().setComponent(ComponentName(
                    "com.asus.mobilemanager",
                    "com.asus.mobilemanager.autostart.AutoStartActivity"
                )))
            }
            
            Manufacturer.LENOVO -> {
                intents.add(Intent().setComponent(ComponentName(
                    "com.lenovo.security",
                    "com.lenovo.security.purebackground.PureBackgroundActivity"
                )))
            }
            
            else -> {}
        }
        
        return intents
    }

    private fun getBatteryManagementIntents(): List<Intent> {
        val intents = mutableListOf<Intent>()
        
        when (getManufacturer()) {
            Manufacturer.XIAOMI -> {
                intents.add(Intent().setComponent(ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                )))
            }
            
            Manufacturer.HUAWEI -> {
                intents.add(Intent().setComponent(ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.power.ui.HwPowerManagerActivity"
                )))
            }
            
            Manufacturer.OPPO, Manufacturer.ONEPLUS, Manufacturer.REALME -> {
                intents.add(Intent().setComponent(ComponentName(
                    "com.coloros.oppoguardelf",
                    "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"
                )))
                intents.add(Intent().setComponent(ComponentName(
                    "com.oplus.battery",
                    "com.oplus.battery.BatteryGuardActivity"
                )))
            }
            
            Manufacturer.VIVO -> {
                intents.add(Intent().setComponent(ComponentName(
                    "com.vivo.abe",
                    "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"
                )))
            }
            
            Manufacturer.SAMSUNG -> {
                intents.add(Intent().setComponent(ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity"
                )))
            }
            
            else -> {}
        }
        
        return intents
    }

    private fun getBackgroundSettingsIntents(context: Context): List<Intent> {
        val intents = mutableListOf<Intent>()
        val packageName = context.packageName
        
        // App-specific background settings
        when (getManufacturer()) {
            Manufacturer.HUAWEI -> {
                intents.add(Intent().apply {
                    component = ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.power.ui.HwPowerManagerActivity"
                    )
                    putExtra("package", packageName)
                })
            }
            
            Manufacturer.SAMSUNG -> {
                intents.add(Intent().apply {
                    component = ComponentName(
                        "com.samsung.android.lool",
                        "com.samsung.android.sm.battery.ui.AppSleepSettingActivity"
                    )
                })
            }
            
            else -> {}
        }
        
        return intents
    }

    // ========== Utility Methods ==========

    private fun tryStartActivity(context: Context, intent: Intent): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            
            // Check if activity exists
            val resolveInfo = context.packageManager.resolveActivity(
                intent, 
                PackageManager.MATCH_DEFAULT_ONLY
            )
            
            if (resolveInfo != null) {
                context.startActivity(intent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to start activity: ${intent.component}", e)
            false
        }
    }

    /**
     * Get a summary of current keep-alive status
     */
    fun getStatusSummary(context: Context): String {
        val sb = StringBuilder()
        
        sb.append("设备: ${getManufacturerName()} ${Build.MODEL}\n")
        sb.append("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n\n")
        
        val batteryOpt = if (isIgnoringBatteryOptimizations(context)) "✓ 已忽略" else "✗ 未忽略"
        sb.append("电池优化: $batteryOpt\n")
        
        return sb.toString()
    }

    /**
     * Get recommended actions based on device
     */
    fun getRecommendedActions(): List<String> {
        val actions = mutableListOf<String>()
        
        actions.add("关闭电池优化")
        
        when (getManufacturer()) {
            Manufacturer.XIAOMI -> {
                actions.add("开启自启动权限")
                actions.add("锁定后台（任务卡片下拉锁定）")
                actions.add("省电策略设为无限制")
            }
            Manufacturer.HUAWEI -> {
                actions.add("开启自启动权限")
                actions.add("关联启动：允许被其他应用启动")
                actions.add("电池管理：手动管理，允许后台运行")
            }
            Manufacturer.OPPO, Manufacturer.ONEPLUS, Manufacturer.REALME -> {
                actions.add("允许自启动")
                actions.add("允许后台运行")
                actions.add("电池优化：无限制")
            }
            Manufacturer.VIVO -> {
                actions.add("允许后台运行")
                actions.add("允许自启动")
                actions.add("耗电异常优化：关闭")
            }
            Manufacturer.SAMSUNG -> {
                actions.add("电池优化：不受监视的应用")
                actions.add("绝不休眠应用")
            }
            else -> {
                actions.add("允许自启动（如有）")
                actions.add("锁定后台")
            }
        }
        
        return actions
    }
}
