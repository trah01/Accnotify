package com.trah.accnotify.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.trah.accnotify.AccnotifyApp
import com.trah.accnotify.R
import com.trah.accnotify.databinding.ActivityMainBinding
import com.trah.accnotify.service.WebSocketService
import com.trah.accnotify.util.OfficialServerConsent
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val app by lazy { AccnotifyApp.getInstance() }
    private var officialConsentDialog: AlertDialog? = null

    private fun applyThemeColor() {
        val themeOverlay = when (app.keyManager.themeColor) {
            "blue" -> R.style.ThemeOverlay_Blue
            "green" -> R.style.ThemeOverlay_Green
            "red" -> R.style.ThemeOverlay_Red
            "pink" -> R.style.ThemeOverlay_Pink
            "white" -> R.style.ThemeOverlay_White
            "black" -> R.style.ThemeOverlay_Black
            else -> R.style.ThemeOverlay_White
        }
        theme.applyStyle(themeOverlay, true)
    }
    
    private lateinit var homeFragment: HomeFragment
    private lateinit var messagesFragment: MessagesFragment
    private lateinit var settingsFragment: SettingsFragment
    private lateinit var activeFragment: Fragment

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startWebSocketServiceIfRegistered()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply theme color overlay before super.onCreate
        applyThemeColor()
        
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupNavigation(savedInstanceState)

        val selectedTab = savedInstanceState?.getInt(STATE_SELECTED_TAB, R.id.nav_home)
            ?: intent.getIntExtra(EXTRA_SELECTED_TAB, R.id.nav_home)
        if (selectedTab != R.id.nav_home) {
            binding.bottomNavigation.post {
                binding.bottomNavigation.selectedItemId = selectedTab
            }
        }

        checkNotificationPermission()
        
        // 处理通知点击的 intent
        handleNotificationIntent(intent)
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleNotificationIntent(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_SELECTED_TAB, binding.bottomNavigation.selectedItemId)
    }

    override fun onDestroy() {
        officialConsentDialog?.dismiss()
        super.onDestroy()
    }
    
    private fun handleNotificationIntent(intent: Intent) {
        val openMessages = intent.getBooleanExtra("open_messages", false)
        val messageId = intent.getStringExtra("message_id")
        
        if (openMessages) {
            if (intent.hasExtra("message_group")) {
                messagesFragment.showGroupMessages(intent.getStringExtra("message_group"))
                intent.removeExtra("message_group")
            }
            // 切换到消息页面
            binding.bottomNavigation.post {
                binding.bottomNavigation.selectedItemId = R.id.nav_messages
            }
            
            // 如果有 messageId，让 MessagesFragment 显示该消息的详情
            if (!messageId.isNullOrEmpty()) {
                messagesFragment.showMessageById(messageId)
            }
            
            com.trah.accnotify.util.NotificationHelper.refreshGroups(this)

            // 清除 intent extras 防止重复处理
            intent.removeExtra("open_messages")
            intent.removeExtra("message_id")
        }
    }


    private fun setupNavigation(savedInstanceState: Bundle?) {

        if (savedInstanceState == null) {
            homeFragment = HomeFragment()
            messagesFragment = MessagesFragment()
            settingsFragment = SettingsFragment()
            activeFragment = homeFragment
            supportFragmentManager.beginTransaction()
                .add(R.id.fragmentContainer, messagesFragment, "messages").hide(messagesFragment)
                .add(R.id.fragmentContainer, settingsFragment, "settings").hide(settingsFragment)
                .add(R.id.fragmentContainer, homeFragment, "home")
                .commit()
        } else {
            homeFragment = (supportFragmentManager.findFragmentByTag("home") as? HomeFragment) ?: HomeFragment()
            messagesFragment = (supportFragmentManager.findFragmentByTag("messages") as? MessagesFragment) ?: MessagesFragment()
            settingsFragment = (supportFragmentManager.findFragmentByTag("settings") as? SettingsFragment) ?: SettingsFragment()
            // Restore activeFragment based on visibility
            activeFragment = when {
                !homeFragment.isHidden -> homeFragment
                !messagesFragment.isHidden -> messagesFragment
                !settingsFragment.isHidden -> settingsFragment
                else -> homeFragment
            }
        }

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            if (supportFragmentManager.isStateSaved) {
                return@setOnItemSelectedListener false
            }
            when (item.itemId) {
                R.id.nav_home -> {
                    if (activeFragment != homeFragment) {
                        supportFragmentManager.beginTransaction().hide(activeFragment).show(homeFragment).commit()
                        activeFragment = homeFragment
                    }
                    true
                }
                R.id.nav_messages -> {
                    if (activeFragment != messagesFragment) {
                        supportFragmentManager.beginTransaction().hide(activeFragment).show(messagesFragment).commit()
                        activeFragment = messagesFragment
                    }
                    true
                }
                R.id.nav_settings -> {
                    if (activeFragment != settingsFragment) {
                        supportFragmentManager.beginTransaction().hide(activeFragment).show(settingsFragment).commit()
                        activeFragment = settingsFragment
                    }
                    true
                }
                else -> false
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return super.onOptionsItemSelected(item)
    }


    fun openDeviceSettings() {
        binding.bottomNavigation.selectedItemId = R.id.nav_settings
    }

    fun withServerConsent(serverUrl: String, onDeclined: () -> Unit = {}, onAccepted: () -> Unit) {
        if (!OfficialServerConsent.isRequired(this, serverUrl)) {
            onAccepted()
            return
        }
        if (officialConsentDialog != null) return
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.official_server_disclaimer_title)
            .setMessage(R.string.official_server_disclaimer_message)
            .setPositiveButton(R.string.official_server_disclaimer_accept, null)
            .setNegativeButton(R.string.official_server_disclaimer_decline) { _, _ -> onDeclined() }
            .setCancelable(false)
            .create()
        officialConsentDialog = dialog
        dialog.setOnDismissListener { officialConsentDialog = null }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (OfficialServerConsent.accept(this)) {
                dialog.dismiss()
                onAccepted()
            } else {
                Toast.makeText(this, "确认未保存，请重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun saveAndRegister() {
        val keyManager = app.keyManager
        val serverUrl = keyManager.serverUrl.trimEnd('/')
        if (OfficialServerConsent.isRequired(this, serverUrl)) {
            withServerConsent(serverUrl) {
                if (app.keyManager.serverUrl.trimEnd('/') == serverUrl) saveAndRegister()
            }
            return
        }
        if (keyManager.hasPendingReset(serverUrl)) {
            Toast.makeText(this, "有尚未确认的重置操作，请在设置中重试该操作", Toast.LENGTH_LONG).show()
            return
        }
        keyManager.ensureKeysExist(serverUrl)

        val deviceKey = keyManager.getDeviceKey()
        if (deviceKey == null) {
            Toast.makeText(this, "设备密钥未初始化，请重启应用", Toast.LENGTH_SHORT).show()
            return
        }
        
        val publicKey = keyManager.exportPublicKeyPEM()
        if (publicKey == null) {
            Toast.makeText(this, "加密密钥未初始化，请重启应用", Toast.LENGTH_SHORT).show()
            return
        }

        // 显示加载提示
        Toast.makeText(this, "正在注册...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                    
                val requestBody = mapOf(
                    "device_key" to deviceKey,
                    "public_key" to publicKey,
                    "name" to Build.MODEL,
                    "platform" to "mobile"
                )
                val gson = com.google.gson.Gson()
                val json = gson.toJson(requestBody)
                val request = Request.Builder()
                    .url("$serverUrl/register")
                    .post(json.toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    try { com.trah.accnotify.crypto.DeviceBindingClient(keyManager).status(serverUrl) }
                    catch (e: com.trah.accnotify.crypto.BindingException) { if (e.status != 404) throw e }
                }

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        keyManager.setRegistered(serverUrl, true)
                        Toast.makeText(this@MainActivity, "注册成功！", Toast.LENGTH_SHORT).show()
                        if (keyManager.serverUrl.trimEnd('/') == serverUrl) restartWebSocketService()
                    } else {
                        val errorBody = response.body?.string() ?: "未知错误"
                        Toast.makeText(this@MainActivity, "注册失败: ${response.code} - $errorBody", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: java.net.UnknownHostException) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "无法连接服务器，请检查网络或服务器地址", Toast.LENGTH_LONG).show()
                }
            } catch (e: java.net.SocketTimeoutException) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "连接超时，请检查网络", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "注册错误: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun restartWebSocketService() {
        val stopIntent = Intent(this, WebSocketService::class.java).apply {
            action = WebSocketService.ACTION_DISCONNECT
        }
        stopService(stopIntent)

        WebSocketService.start(this)
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startWebSocketServiceIfRegistered()
            }
        } else {
            startWebSocketServiceIfRegistered()
        }
    }

    private fun startWebSocketServiceIfRegistered() {
        if (app.keyManager.isRegistered) {
            WebSocketService.start(this)
        }
    }

    companion object {
        private const val EXTRA_SELECTED_TAB = "selected_tab"
        private const val STATE_SELECTED_TAB = "state_selected_tab"
    }
}
