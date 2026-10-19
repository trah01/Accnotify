package com.trah.accnotify.ui

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.trah.accnotify.AccnotifyApp
import com.trah.accnotify.BuildConfig
import com.trah.accnotify.R
import com.trah.accnotify.databinding.FragmentSettingsBinding
import com.trah.accnotify.databinding.ItemServerBinding
import com.google.android.material.color.MaterialColors
import com.trah.accnotify.service.WebSocketService
import com.trah.accnotify.crypto.DeviceManagementClient
import com.trah.accnotify.util.UpdateChecker
import com.trah.accnotify.util.KeepAliveHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val app by lazy { AccnotifyApp.getInstance() }
    private var deviceOperationInProgress = false
    private val serviceNotificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (_binding != null) {
            if (granted) enableForegroundNotification() else {
                setForegroundNotificationSwitch(false)
                Toast.makeText(context, "未允许通知权限，无法显示常驻通知", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupServerList()
        setupActions()
        setupDisplayModeSelector()
        setupThemeColorPicker()
        setupForegroundNotificationSwitch()
        setupVersionInfo()
    }

    private fun setupVersionInfo() {
        // Set current version
        binding.tvVersion.text = "当前版本 ${BuildConfig.VERSION_NAME}"

        // Check for update click
        binding.btnCheckUpdate.setOnClickListener {
            checkForUpdate()
        }
    }

    private fun checkForUpdate() {
        binding.btnCheckUpdate.isEnabled = false
        val originalText = binding.tvVersion.text
        binding.tvVersion.text = "正在检查更新..."

        lifecycleScope.launch {
            try {
                val update = UpdateChecker.check(Build.SUPPORTED_ABIS.toList())
                val latestVersion = update.version
                val currentVersion = BuildConfig.VERSION_NAME

                withContext(Dispatchers.Main) {
                    binding.btnCheckUpdate.isEnabled = true

                    if (latestVersion != null) {
                        if (update.newerThan(currentVersion)) {
                            // New version available
                            binding.tvVersion.text = "发现新版本 $latestVersion"
                            showUpdateDialog(latestVersion, update.url)
                        } else {
                            // Up to date
                            binding.tvVersion.text = "已是最新版本 ($currentVersion)"
                            showCleanDialog(
                                title = "检查更新",
                                message = "当前已是最新版本 ($currentVersion)",
                                positiveText = "好的",
                                negativeText = null
                            )
                        }
                    } else {
                        // Failed to check
                        binding.tvVersion.text = originalText
                        showCleanDialog(
                            title = "检查更新",
                            message = "暂未发布适用于这台手机的安装包，可前往发布页面查看。",
                            positiveText = "发布页面",
                            onPositive = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.RELEASES_URL))) }
                        )
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.btnCheckUpdate.isEnabled = true
                    binding.tvVersion.text = originalText
                    showCleanDialog(
                        title = "检查更新",
                        message = "检查更新失败: ${e.message}",
                        positiveText = "好的",
                        negativeText = null
                    )
                }
            }
        }
    }

    private fun showUpdateDialog(latestVersion: String, releaseURL: String) {
        showCleanDialog(
            title = "发现新版本",
            message = "当前版本: ${BuildConfig.VERSION_NAME}\n最新版本: $latestVersion\n\n是否前往 GitHub 下载？",
            positiveText = "前往下载",
            onPositive = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(releaseURL))
                startActivity(intent)
            }
        )
    }

    private fun setupServerList() {
        binding.serverListContainer.removeAllViews()
        val keyManager = app.keyManager
        val servers = keyManager.getServers()
        val currentUrl = keyManager.serverUrl

        servers.forEach { url ->
            val itemBinding = ItemServerBinding.inflate(layoutInflater, binding.serverListContainer, false)
            itemBinding.tvServerUrl.text = url

            if (url == currentUrl) {
                itemBinding.ivSelected.visibility = View.VISIBLE
                itemBinding.ivSelected.setImageResource(R.drawable.ic_fluent_globe)
                itemBinding.tvServerUrl.setTextColor(MaterialColors.getColor(itemBinding.root, com.google.android.material.R.attr.colorPrimary))
                itemBinding.tvServerUrl.setTypeface(null, android.graphics.Typeface.BOLD)
            } else {
                itemBinding.ivSelected.visibility = View.INVISIBLE
                itemBinding.tvServerUrl.setTextColor(MaterialColors.getColor(itemBinding.root, com.google.android.material.R.attr.colorOnSurface))
                itemBinding.tvServerUrl.setTypeface(null, android.graphics.Typeface.NORMAL)
            }

            // Click to select
            itemBinding.root.setOnClickListener {
                if (!isAdded) return@setOnClickListener
                (activity as? MainActivity)?.withServerConsent(url) {
                    if (isAdded) {
                        keyManager.serverUrl = url
                        Toast.makeText(context, "已切换服务器", Toast.LENGTH_SHORT).show()
                        setupServerList()
                        view?.postDelayed({ restartWebSocketService() }, 300)
                    }
                }
            }

            // Edit
            itemBinding.btnEdit.setOnClickListener {
                showEditServerDialog(url)
            }

            // Delete
            itemBinding.btnDelete.setOnClickListener {
                if (keyManager.getServers().size <= 1) {
                    Toast.makeText(context, "至少保留一个服务器", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                showCleanDialog(
                    title = "删除服务器",
                    message = "确定要删除 $url 吗？",
                    positiveText = "删除",
                    onPositive = {
                        try {
                            val wasSelected = keyManager.serverUrl == url
                            if (keyManager.removeServer(url)) {
                                setupServerList()
                                if (wasSelected) restartWebSocketService()
                                Toast.makeText(context, "服务器已删除", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "至少保留一个服务器", Toast.LENGTH_SHORT).show()
                            }
                        } catch (_: Exception) {
                            Toast.makeText(context, "服务器删除失败，请重试", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }

            binding.serverListContainer.addView(itemBinding.root)
        }
    }

    private fun showEditServerDialog(currentUrl: String) {
        val et = EditText(requireContext())
        et.setText(currentUrl)
        et.setSelection(currentUrl.length)
        et.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_clean_input)
        resources.getDimensionPixelSize(R.dimen.spacing_control).let { padding -> et.setPadding(padding, padding, padding, padding) }
        val reuseKey = com.google.android.material.checkbox.MaterialCheckBox(requireContext()).apply {
            text = "复用公用秘钥"
            isChecked = app.keyManager.isUsingCommonSenderKey(currentUrl)
        }
        val reuseId = com.google.android.material.checkbox.MaterialCheckBox(requireContext()).apply {
            text = "复用公用 ID"
            isChecked = app.keyManager.isUsingCommonDeviceId(currentUrl)
        }
        val content = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(et)
            addView(reuseKey)
            addView(reuseId)
        }

        showCleanDialog(
            title = "编辑服务器地址",
            message = "ID 和密钥按服务器分别保存，可选择复用本机的公用值。更换设备 ID 后，请在首页同步连接并更新发送脚本的推送地址。",
            positiveText = "保存",
            customView = content,
            onPositive = {
                val newUrl = et.text.toString().trim().trimEnd('/')
                if (newUrl.isNotEmpty()) {
                    val keyManager = app.keyManager
                    val wasSelected = keyManager.serverUrl == currentUrl
                    val updateServer = updateServer@{
                        if (isAdded) {
                            try {
                                keyManager.updateServerAddress(currentUrl, newUrl, reuseKey.isChecked, reuseId.isChecked)
                            } catch (e: Exception) {
                                Toast.makeText(context, e.message ?: "服务器配置保存失败，请重试", Toast.LENGTH_SHORT).show()
                                return@updateServer
                            }
                            setupServerList()
                            if (wasSelected) restartWebSocketService()
                            val needsSync = keyManager.serverUrl == newUrl && !keyManager.isRegistered
                            Toast.makeText(context, if (needsSync) "已保存，请在首页同步连接" else "服务器配置已保存", Toast.LENGTH_SHORT).show()
                        }
                    }
                    if (wasSelected) {
                        (activity as? MainActivity)?.withServerConsent(newUrl, onAccepted = updateServer)
                    } else {
                        updateServer()
                    }
                }
            }
        )
    }

    private fun setupActions() {
        binding.btnNotificationRules.setOnClickListener { startActivity(Intent(requireContext(), NotificationRulesActivity::class.java)) }
        binding.btnBindIdentity.setOnClickListener { DeviceBindingDialog.show(this) }
        binding.btnBoundDevices.setOnClickListener { BoundDevicesDialog.show(this) }
        binding.btnCommonInfo.setOnClickListener {
            CommonInfoDialog.show(requireContext(), app.keyManager) { restartWebSocketService() }
        }

        binding.btnAddServer.setOnClickListener {
            val et = EditText(requireContext())
            et.setHint("https://xx.com")
            et.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_clean_input)
            resources.getDimensionPixelSize(R.dimen.spacing_control).let { padding -> et.setPadding(padding, padding, padding, padding) }
            val reuseKey = com.google.android.material.checkbox.MaterialCheckBox(requireContext()).apply {
                text = "复用公用秘钥"
                isChecked = false
            }
            val reuseId = com.google.android.material.checkbox.MaterialCheckBox(requireContext()).apply {
                text = "复用公用 ID"
                isChecked = false
            }
            val content = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                addView(et)
                addView(reuseKey)
                addView(reuseId)
            }

            showCleanDialog(
                title = "添加服务器",
                message = "请输入服务器地址：",
                positiveText = "添加",
                customView = content,
                onPositive = {
                    val newUrl = et.text.toString().trim().trimEnd('/')
                    if (newUrl.isNotEmpty()) {
                        try {
                            app.keyManager.addServer(newUrl, reuseKey.isChecked, reuseId.isChecked)
                            setupServerList()
                            if (app.keyManager.serverUrl == newUrl) restartWebSocketService()
                        } catch (_: Exception) {
                            Toast.makeText(context, "服务器保存失败，请重试", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        }

        binding.linkGithub.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/trah01/Accnotify"))
            startActivity(intent)
        }

        binding.btnResetEncryption.setOnClickListener { confirmDeviceOperation("reset_rsa") }
        binding.btnResetDeviceAndEncryption.setOnClickListener { confirmDeviceOperation("reset_all") }
        binding.btnUnregisterDevice.setOnClickListener { confirmDeviceOperation("unregister") }
    }

    private fun confirmDeviceOperation(action: String) {
        if (deviceOperationInProgress) return
        if (com.trah.accnotify.util.OfficialServerConsent.isRequired(requireContext(), app.keyManager.serverUrl)) {
            (activity as? MainActivity)?.withServerConsent(app.keyManager.serverUrl) {
                if (isAdded) confirmDeviceOperation(action)
            }
            return
        }
        if (!app.keyManager.isRegistered) {
            Toast.makeText(context, "请先在首页同步当前服务器", Toast.LENGTH_LONG).show()
            return
        }
        val server = app.keyManager.serverUrl
        val title = when (action) {
            "reset_rsa" -> "仅重置 RSA 密钥"
            "reset_all" -> "重置设备 ID 和 RSA 密钥"
            else -> "注销设备 ID 和服务端公钥"
        }
        val effect = when (action) {
            "reset_rsa" -> "保留设备 ID 和推送地址，自动同步新公钥。本地历史保留。"
            "reset_all" -> "旧推送地址将失效，自动同步新的设备 ID 和公钥。本地历史保留。"
            else -> "删除此服务器上的设备注册、公钥及消息，旧推送地址失效。本地私钥和历史保留，再次同步时使用新的设备 ID。"
        }
        showCleanDialog(title = title, message = "服务器：$server\n\n$effect", positiveText = if (action == "unregister") "注销" else "重置", onPositive = {
            performDeviceOperation(server, action)
        })
    }

    private fun setDeviceOperationBusy(busy: Boolean) {
        deviceOperationInProgress = busy
        val currentBinding = _binding ?: return
        for (button in listOf(currentBinding.btnResetEncryption, currentBinding.btnResetDeviceAndEncryption,
            currentBinding.btnUnregisterDevice, currentBinding.btnAddServer)) button.isEnabled = !busy
        fun enableTree(view: View) {
            view.isEnabled = !busy
            if (view is ViewGroup) for (i in 0 until view.childCount) enableTree(view.getChildAt(i))
        }
        enableTree(currentBinding.serverListContainer)
    }

    private fun performDeviceOperation(server: String, action: String) {
        setDeviceOperationBusy(true)
        val ctx = requireContext().applicationContext
        if (app.keyManager.serverUrl == server) ctx.stopService(Intent(ctx, WebSocketService::class.java))
        Toast.makeText(ctx, "正在处理，请稍候", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                DeviceManagementClient(app.keyManager).execute(server, action)
                if (app.keyManager.serverUrl == server && action != "unregister") {
                    try { WebSocketService.start(ctx) } catch (e: Exception) {
                        android.util.Log.e("SettingsFragment", "Device updated but connection restart failed", e)
                    }
                }
                if (_binding != null) {
                    setupServerList()
                    Toast.makeText(ctx, if (action == "unregister") "已注销当前服务器的设备" else "已重置并同步当前服务器", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                if (isAdded) Toast.makeText(ctx, e.message ?: "操作结果未确认，请重试同一操作", Toast.LENGTH_LONG).show()
            } finally {
                setDeviceOperationBusy(false)
            }
        }
    }

    private fun setupForegroundNotificationSwitch() {
        binding.switchForegroundNotification.setOnCheckedChangeListener(null)
        binding.switchForegroundNotification.isChecked = app.keyManager.showForegroundNotification
        binding.switchForegroundNotification.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                enableForegroundNotification()
            } else {
                applyForegroundNotificationSetting(false)
            }
        }
    }

    private fun setForegroundNotificationSwitch(enabled: Boolean) {
        app.keyManager.showForegroundNotification = enabled
        _binding?.let {
            it.switchForegroundNotification.setOnCheckedChangeListener(null)
            it.switchForegroundNotification.isChecked = enabled
            setupForegroundNotificationSwitch()
        }
    }

    private fun enableForegroundNotification() {
        val ctx = context ?: return
        val keys = app.keyManager
        if (!keys.isRegistered || keys.hasPendingReset(keys.serverUrl)) {
            setForegroundNotificationSwitch(false)
            Toast.makeText(ctx, if (keys.hasPendingReset(keys.serverUrl)) "请先完成待确认的重置操作" else "请先在首页同步当前服务器", Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            setForegroundNotificationSwitch(false)
            serviceNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val manager = ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelBlocked = manager.getNotificationChannel(AccnotifyApp.CHANNEL_SERVICE)?.importance == NotificationManager.IMPORTANCE_NONE
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled() || channelBlocked) {
            setForegroundNotificationSwitch(false)
            showCleanDialog(title = "允许常驻通知", message = "请在系统设置中允许 Accnotify 的服务通知。", positiveText = "去设置", onPositive = {
                val intent = Intent(if (channelBlocked) Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS else Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                if (channelBlocked) intent.putExtra(Settings.EXTRA_CHANNEL_ID, AccnotifyApp.CHANNEL_SERVICE)
                startActivity(intent)
            })
            return
        }
        applyForegroundNotificationSetting(true)
    }

    private fun applyForegroundNotificationSetting(enabled: Boolean) {
        val ctx = context ?: return
        setForegroundNotificationSwitch(enabled)
        try {
            if (app.keyManager.isRegistered && !app.keyManager.hasPendingReset(app.keyManager.serverUrl)) {
                WebSocketService.refreshNotificationPreference(ctx)
            } else if (!enabled) {
                ctx.stopService(Intent(ctx, WebSocketService::class.java))
            }
        } catch (e: Exception) {
            setForegroundNotificationSwitch(false)
            Toast.makeText(ctx, "无法启动常驻通知，请重新打开应用后重试", Toast.LENGTH_LONG).show()
            android.util.Log.e("SettingsFragment", "Failed to apply service notification preference", e)
        }
    }

    private fun setupDisplayModeSelector() {
        val currentMode = app.keyManager.themeMode

        // 先清除 listener，防止设置 isChecked 时触发回调
        binding.themeModeGroup.setOnCheckedChangeListener(null)

        when (currentMode) {
            "light" -> binding.radioLightMode.isChecked = true
            "dark" -> binding.radioDarkMode.isChecked = true
            else -> binding.radioFollowSystem.isChecked = true
        }

        binding.themeModeGroup.setOnCheckedChangeListener { _, checkedId ->
            val newMode = when (checkedId) {
                R.id.radioLightMode -> "light"
                R.id.radioDarkMode -> "dark"
                else -> "system"
            }
            if (newMode != app.keyManager.themeMode) {
                app.keyManager.themeMode = newMode
                val mode = when (newMode) {
                    "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                    "dark" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                    else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                // 清除 listener 防止重建过程中再次触发
                binding.themeModeGroup.setOnCheckedChangeListener(null)
                // post 到下一帧，避免 OPPO/OnePlus ViewMirrorManager 在当前布局过程中冲突
                view?.post {
                    androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(mode)
                }
            }
        }
    }

    private fun setupThemeColorPicker() {
        val container = binding.root.findViewById<android.widget.LinearLayout>(R.id.themeColorContainer)
        container.removeAllViews()
        val keyManager = app.keyManager
        val currentTheme = keyManager.themeColor

        // Color definitions: key -> (display color hex, name)
        val colors = listOf(
            "white" to Pair(android.graphics.Color.WHITE, "白色"),
            "blue" to Pair(resources.getColor(R.color.theme_blue_primary, null), "蓝色"),
            "green" to Pair(resources.getColor(R.color.theme_green_primary, null), "绿色"),
            "red" to Pair(resources.getColor(R.color.theme_red_primary, null), "红色"),
            "pink" to Pair(resources.getColor(R.color.theme_pink_primary, null), "粉色"),
            "black" to Pair(resources.getColor(R.color.theme_black_primary, null), "深色")
        )

        val density = resources.displayMetrics.density
        val circleSize = (40 * density).toInt()
        val margin = (8 * density).toInt()

        for ((key, pair) in colors) {
            val (color, name) = pair
            val outerLayout = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = margin
                    marginEnd = margin
                }
            }

            // Color circle
            val circleView = View(requireContext()).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(circleSize, circleSize)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(color)
                    if (key == "white") {
                        setStroke((density + 0.5f).toInt(), android.graphics.Color.LTGRAY)
                    } else if (key == currentTheme) {
                        setStroke((3 * density).toInt(), color)
                    }
                }
            }

            // Checkmark overlay for selected
            val frameLayout = FrameLayout(requireContext()).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(circleSize, circleSize)
            }
            frameLayout.addView(circleView)

            if (key == currentTheme) {
                val checkMark = TextView(requireContext()).apply {
                    text = "✓"
                    setTextColor(if (MaterialColors.isColorLight(color)) android.graphics.Color.DKGRAY else android.graphics.Color.WHITE)
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(com.trah.accnotify.R.dimen.text_body))
                    gravity = android.view.Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
                frameLayout.addView(checkMark)
            }

            // Label
            val label = TextView(requireContext()).apply {
                text = name
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(com.trah.accnotify.R.dimen.text_caption))
                setTextColor(resources.getColor(R.color.clean_text_secondary, null))
                gravity = android.view.Gravity.CENTER
                setPadding(0, (4 * density).toInt(), 0, 0)
            }

            outerLayout.addView(frameLayout)
            outerLayout.addView(label)

            outerLayout.setOnClickListener {
                if (key != currentTheme) {
                    keyManager.themeColor = key
                    // Recreate activity to apply new theme
                    activity?.recreate()
                    return@setOnClickListener // 主题色切换后立即 return，防止 recreate 后 fragment 操作
                }
            }

            container.addView(outerLayout)
        }
    }

    private fun showCleanDialog(
        title: String,
        message: String,
        positiveText: String = "确定",
        negativeText: String? = "取消",
        customView: View? = null,
        onPositive: (() -> Unit)? = null
    ) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_clean, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.dialogTitle)
        val tvMessage = dialogView.findViewById<TextView>(R.id.dialogMessage)
        val btnPositive = dialogView.findViewById<TextView>(R.id.btnPositive)
        val btnNegative = dialogView.findViewById<TextView>(R.id.btnNegative)
        val customContainer = dialogView.findViewById<FrameLayout>(R.id.dialogCustomContainer)

        tvTitle.text = title
        tvMessage.text = message
        btnPositive.text = positiveText

        if (negativeText != null) {
            btnNegative.text = negativeText
            btnNegative.visibility = View.VISIBLE
        } else {
            btnNegative.visibility = View.GONE
        }

        if (customView != null) {
            customContainer.addView(customView)
            customContainer.visibility = View.VISIBLE
        }

        val alertDialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        alertDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnPositive.setOnClickListener {
            onPositive?.invoke()
            alertDialog.dismiss()
        }

        btnNegative.setOnClickListener {
            alertDialog.dismiss()
        }

        alertDialog.show()
        KeyDialogControls.compactWindow(requireContext(), alertDialog.window)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun restartWebSocketService() {
        val ctx = context ?: return
        if (!isAdded) return
        try {
            val intent = android.content.Intent(ctx, com.trah.accnotify.service.WebSocketService::class.java)
            ctx.stopService(intent)
            com.trah.accnotify.service.WebSocketService.start(ctx)
        } catch (e: Exception) {
            android.util.Log.e("SettingsFragment", "Failed to restart WebSocket service", e)
        }
    }
}

