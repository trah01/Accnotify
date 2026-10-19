package com.trah.accnotify.ui

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.trah.accnotify.AccnotifyApp
import com.trah.accnotify.R
import com.trah.accnotify.databinding.FragmentHomeBinding
import com.trah.accnotify.service.WebSocketService
import com.trah.accnotify.util.KeepAliveHelper

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val app by lazy { AccnotifyApp.getInstance() }

    private val connectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WebSocketService.ACTION_CONNECTION_STATUS) {
                val connected = intent.getBooleanExtra(WebSocketService.EXTRA_CONNECTED, false)
                updateConnectionStatus(connected)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
        registerConnectionReceiver()
        animateCardsEntry()
    }

    private fun animateCardsEntry() {
        val views = listOf(
            binding.statusContainer,
            binding.cardTutorial
        )

        views.forEachIndexed { index, cardView ->
            cardView.apply {
                alpha = 0f
                translationY = 40f
                animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(350)
                    .setStartDelay((index * 60).toLong())
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
        }
    }


    override fun onResume() {
        super.onResume()
        // Force refresh status if needed or check connection
        updateDeviceInfo()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            // Refresh device info when fragment becomes visible (e.g. after switching server in Settings)
            updateDeviceInfo()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        requireContext().unregisterReceiver(connectionReceiver)
        _binding = null
    }

    private fun setupUI() {
        updateDeviceInfo()

        binding.btnRegister.setOnClickListener {
            (activity as? MainActivity)?.saveAndRegister()
        }

        binding.tvDeviceKey.setOnClickListener {
            val ctx = requireContext()
            val server = app.keyManager.serverUrl
            if (app.keyManager.getMemberCount() > 1 || app.keyManager.getPushKey() != app.keyManager.getDeviceKey()) {
                Toast.makeText(ctx, "请先在设置中解除绑定，再切换设备 ID", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            DeviceIdSelector.show(ctx, binding.tvDeviceKey, app.keyManager) { changed ->
                if (_binding != null) updateDeviceInfo()
                if (changed && app.keyManager.serverUrl == server) {
                    ctx.stopService(Intent(ctx, WebSocketService::class.java))
                    updateConnectionStatus(false)
                    Toast.makeText(ctx, "设备 ID 已更新，请同步连接并更新推送地址", Toast.LENGTH_LONG).show()
                }
            }
        }

        binding.btnSenderEncryption.setOnClickListener {
            SenderEncryptionDialog.show(requireContext(), app.keyManager) {
                if (_binding != null) updateDeviceInfo()
                val ctx = context ?: return@show
                if (app.keyManager.isRegistered && !app.keyManager.hasPendingReset(app.keyManager.serverUrl)) {
                    try {
                        ctx.stopService(Intent(ctx, WebSocketService::class.java))
                        WebSocketService.start(ctx)
                    } catch (e: Exception) {
                        android.util.Log.e("HomeFragment", "Failed to reconnect after saving sender key", e)
                        Toast.makeText(ctx, "密钥已保存，请点击同步连接重试", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        binding.btnCopyPushUrl.setOnClickListener {
            val base = app.keyManager.serverUrl.trimEnd('/')
            val key = app.keyManager.getPushKey() ?: return@setOnClickListener
            val pushUrl = "$base/push/$key"
            val webhookUrl = "$base/webhook/$key"

            showCopyOptionsDialog(key, pushUrl, webhookUrl)
        }

        binding.cardKeepAliveSettings.setOnClickListener {
            startActivity(Intent(requireContext(), KeepAliveSettingsActivity::class.java))
        }

        binding.cardNotificationPermission.setOnClickListener {
            KeepAliveHelper.openNotificationSettings(requireContext())
        }

        binding.cardTutorial.setOnClickListener {
            startActivity(Intent(requireContext(), TutorialActivity::class.java))
        }

        binding.tvPrivacyPolicy.setOnClickListener {
            showPrivacyDialog()
        }
        
        // Server URL configuration moved to SettingsFragment


    }

    private fun showCopyOptionsDialog(key: String, pushUrl: String, webhookUrl: String) {
        val density = resources.displayMetrics.density
        val container = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }

        val options = mutableListOf("Push 链接（全部设备）" to pushUrl)
        app.keyManager.getEndpointId()?.let { options += "Push 链接（仅本机）" to "$pushUrl?target=$it" }
        options += "Webhook 链接" to webhookUrl
        options += "仅复制 Key" to key

        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_copy_options, null)
        (dialogView as com.google.android.material.card.MaterialCardView).apply {
            minimumWidth = 0
            setCardBackgroundColor(com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface))
        }
        val tvTitle = dialogView.findViewById<android.widget.TextView>(R.id.dialogTitle)
        val btnNegative = dialogView.findViewById<android.widget.TextView>(R.id.btnNegative)
        val customContainer = dialogView.findViewById<android.widget.FrameLayout>(R.id.dialogCustomContainer)

        tvTitle.text = "复制链接"
        tvTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_dialog_title))
        btnNegative.text = "关闭"
        btnNegative.visibility = View.VISIBLE
        btnNegative.layoutParams.height = resources.getDimensionPixelSize(R.dimen.action_touch_size)

        val alertDialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()
        for ((index, option) in options.withIndex()) {
            val (label, value) = option
            if (index > 0) {
                container.addView(View(requireContext()).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (density + 0.5f).toInt())
                    setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.clean_divider))
                })
            }
            val item = LayoutInflater.from(requireContext()).inflate(R.layout.item_copy_option, container, false)
            item.findViewById<android.widget.TextView>(R.id.optionTitle).text = label
            item.findViewById<android.widget.TextView>(R.id.optionValue).text = value
            item.contentDescription = "复制$label"
            item.setOnClickListener {
                copyToClipboard(label, value)
                alertDialog.dismiss()
            }
            container.addView(item)
        }

        val scrollView = android.widget.ScrollView(requireContext()).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(container)
        }
        customContainer.addView(scrollView)
        customContainer.visibility = View.VISIBLE

        btnNegative.setOnClickListener { alertDialog.dismiss() }
        alertDialog.show()
        KeyDialogControls.compactWindow(requireContext(), alertDialog.window)
        alertDialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    private fun showPrivacyDialog() {
        val message = """
            1. 数据收集：Accnotify 不收集任何个人身份信息（如 IMEI、手机号等）。
            2. 消息安全：服务端接收消息明文，使用设备公钥加密后投递，客户端用本地私钥解密。服务端可以读取并保存消息内容，请使用您信任的服务端，并通过 HTTPS 发送请求。
            3. Push Key：它是您的唯一投递凭证，请妥善保管。一旦重置，旧链接将立即失效。
            4. 无障碍服务：本应用申请无障碍权限仅用于增强后台运行稳定性及自动处理通知，不读取您的私人数据。
            5. 免责声明：本软件为开源工具，请在中国法律允许范围内使用。
        """.trimIndent()

        showCleanDialog(
            title = "隐私政策与服务协议",
            message = message,
            positiveText = "我已知晓"
        )
    }

    private fun updateDeviceInfo() {
        val keyManager = app.keyManager
        binding.tvDeviceKey.text = keyManager.getPushKey() ?: "未注册"
        binding.tvDeviceKeyHint.text = if (keyManager.getMemberCount() > 1 || keyManager.getPushKey() != keyManager.getDeviceKey()) {
            "共用 ID，切换前请在设置中解除绑定。"
        } else "此 ID 用于接收推送消息，点击上方 ID 可切换。"
        binding.tvEndpointId.text = keyManager.getEndpointId()?.let { "设备标识：$it" } ?: "同步连接后显示设备标识"
        binding.tvEndpointId.setOnClickListener {
            keyManager.getEndpointId()?.let { id ->
                val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("设备标识", id))
                Toast.makeText(requireContext(), "已复制设备标识", Toast.LENGTH_SHORT).show()
            }
        }
        binding.tvServerUrl.text = keyManager.serverUrl
        binding.btnSenderEncryption.text = if (keyManager.getSenderEncryptionKey().isNullOrEmpty()) {
            "发送端加密密钥 · 未配置"
        } else {
            "发送端加密密钥 · 已配置"
        }
    }

    private fun updateConnectionStatus(connected: Boolean) {
        val connectedColor = ContextCompat.getColor(requireContext(), R.color.status_clean_connected_bg)
        val connectedText = ContextCompat.getColor(requireContext(), R.color.status_clean_connected_text)
        val disconnectedColor = ContextCompat.getColor(requireContext(), R.color.status_clean_disconnected_bg)
        val disconnectedText = ContextCompat.getColor(requireContext(), R.color.status_clean_disconnected_text)

        if (connected) {
            binding.tvStatus.text = "服务已连接"
            binding.statusContainer.backgroundTintList = android.content.res.ColorStateList.valueOf(connectedColor)
            binding.tvStatus.setTextColor(connectedText)
            // Switch to connected Lottie animation
            binding.lottieStatus.setAnimation("anim_connected.json")
            binding.lottieStatus.playAnimation()
        } else {
            binding.tvStatus.text = "服务未连接"
            binding.statusContainer.backgroundTintList = android.content.res.ColorStateList.valueOf(disconnectedColor)
            binding.tvStatus.setTextColor(disconnectedText)
            // Switch to disconnected Lottie animation
            binding.lottieStatus.setAnimation("anim_disconnected.json")
            binding.lottieStatus.playAnimation()
        }
    }


    private fun copyToClipboard(label: String, text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
    }

    private fun registerConnectionReceiver() {
        val filter = IntentFilter(WebSocketService.ACTION_CONNECTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(connectionReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            requireContext().registerReceiver(connectionReceiver, filter)
        }
    }

    // Server Dialog logic moved to SettingsFragment

    private fun showCleanDialog(
        title: String,
        message: String,
        positiveText: String = "OK",
        negativeText: String? = "取消",
        customView: View? = null,
        onPositive: (() -> Unit)? = null
    ) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_clean, null)
        val tvTitle = dialogView.findViewById<android.widget.TextView>(R.id.dialogTitle)
        val tvMessage = dialogView.findViewById<android.widget.TextView>(R.id.dialogMessage)
        val btnPositive = dialogView.findViewById<android.widget.TextView>(R.id.btnPositive)
        val btnNegative = dialogView.findViewById<android.widget.TextView>(R.id.btnNegative)
        val customContainer = dialogView.findViewById<android.widget.FrameLayout>(R.id.dialogCustomContainer)

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
            // If custom view is present, message acts as subtitle, often good to keep visible
        }

        val alertDialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        // Make window transparent so rounded corners show
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
}

