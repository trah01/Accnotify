package com.trah.accnotify.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.trah.accnotify.AccnotifyApp
import com.trah.accnotify.R
import com.trah.accnotify.crypto.E2ECrypto
import com.trah.accnotify.crypto.SenderEncryption
import com.trah.accnotify.data.Message
import com.trah.accnotify.ui.MainActivity
import com.trah.accnotify.util.NotificationHelper
import com.trah.accnotify.util.MessageIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Date
import java.util.concurrent.TimeUnit
import android.app.NotificationManager
import android.provider.Settings
import android.text.TextUtils

class WebSocketService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private val gson = Gson()
    private val messageMutex = Mutex()

    private var webSocket: WebSocket? = null
    @Volatile
    private var isConnected = false
    @Volatile
    private var isConnecting = false  // Prevent concurrent connection attempts
    private var reconnectAttempt = 0
    private val maxReconnectDelay = 60_000L // 1 minute
    private val connectionLock = Any()  // Lock for connection operations

    // WakeLock to keep CPU awake during critical operations (not persistent)
    private var wakeLock: PowerManager.WakeLock? = null
    private val wakeLockTimeout = 30_000L // 30 seconds max for message processing
    
    // Notification update
    private var lastMessageTime: Long = 0L
    private var messageCount: Int = 0
    
    // Network callback for monitoring connectivity
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var hasEnteredForeground = false
    @Volatile
    private var startedAsForeground = false
    private var connectionServerUrl: String? = null

    private val client by lazy {
        OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MINUTES) // No timeout for WebSocket
            .retryOnConnectionFailure(true)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service onCreate")

        // Register network callback
        registerNetworkCallback()

        ensureForegroundState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: action=${intent?.action}")

        // Track if this was started via startForegroundService
        if (intent?.getBooleanExtra(EXTRA_FOREGROUND, false) == true) {
            startedAsForeground = true
        }
        ensureForegroundState()
        
        when (intent?.action) {
            ACTION_CONNECT -> {
                reconnectAttempt = 0
                connect()
            }
            ACTION_DISCONNECT -> disconnect()
            ACTION_REFRESH_NOTIFICATION -> {
                if (!isConnected && !isConnecting) connect()
            }
            ACTION_SEND_ACK -> {
                val messageId = intent.getStringExtra(EXTRA_MESSAGE_ID)
                messageId?.let { sendAck(it) }
            }
            null -> {
                // Service restarted by system (START_STICKY), auto-reconnect
                Log.i(TAG, "Service restarted by system, auto-connecting")
                connect()
            }
        }
        
        // Return START_STICKY to restart service if killed
        // Return START_REDELIVER_INTENT to also redeliver the last intent
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.w(TAG, "Service onDestroy")
        
        // Unregister network callback
        unregisterNetworkCallback()
        
        // Release WakeLock
        releaseWakeLock()
        
        disconnect()
        scope.cancel()
        removeForegroundNotification()
        
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(TAG, "onTaskRemoved - app was swiped away")
        super.onTaskRemoved(rootIntent)
    }

    /**
     * Acquire a temporary WakeLock for critical operations like message processing.
     * The WakeLock automatically releases after timeout to prevent battery drain.
     */
    private fun acquireTemporaryWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "$TAG::MessageWakeLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            wakeLock?.acquire(wakeLockTimeout)
            Log.d(TAG, "Temporary WakeLock acquired for ${wakeLockTimeout}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "WakeLock released")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release WakeLock", e)
        }
    }
    
    private fun registerNetworkCallback() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Network available - reconnecting")
                scope.launch {
                    delay(1000) // Wait a bit for network to stabilize
                    if (!isConnected) {
                        connect()
                    }
                }
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "Network lost")
                isConnected = false
                broadcastConnectionStatus(false)
            }
        }

        try {
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
            Log.i(TAG, "Network callback registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try {
                val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister network callback", e)
            }
        }
        networkCallback = null
    }

    private fun ensureForegroundState() {
        try {
            val showNotification = AccnotifyApp.getInstance().keyManager.showForegroundNotification
            if (showNotification || startedAsForeground) {
                // Always promote on an explicit refresh. The in-memory flag
                // does not prove the system still displays this notification.
                val notification = createForegroundNotification()
                startForeground(NOTIFICATION_ID, notification)
                hasEnteredForeground = true
                if (showNotification) {
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(NOTIFICATION_ID, notification)
                }
            }
            startedAsForeground = false
            if (!showNotification) removeForegroundNotification()
        } catch (e: Exception) {
            hasEnteredForeground = false
            Log.e(TAG, "Failed to update foreground state", e)
        }
    }

    private fun connect() {
        synchronized(connectionLock) {
            // Prevent concurrent connection attempts
            if (isConnected || isConnecting) {
                Log.d(TAG, "Already connected or connecting, skipping")
                return
            }
            isConnecting = true
        }

        val app = AccnotifyApp.getInstance()
        val keyManager = app.keyManager
        if (!keyManager.isRegistered || keyManager.hasPendingReset(keyManager.serverUrl)) {
            synchronized(connectionLock) { isConnecting = false }
            return
        }
        val deviceKey = keyManager.getDeviceKey()
        if (deviceKey == null) {
            synchronized(connectionLock) { isConnecting = false }
            return
        }
        val serverUrl = keyManager.serverUrl
        connectionServerUrl = serverUrl

        // Convert HTTP to WS
        val wsUrl = serverUrl
            .replace("http://", "ws://")
            .replace("https://", "wss://")
            .trimEnd('/') + "/ws?key=$deviceKey"

        Log.i(TAG, "Connecting to WebSocket: ${wsUrl.substringBefore("?")}")

        // Close existing connection before creating a new one
        synchronized(connectionLock) {
            webSocket?.let {
                try {
                    it.close(1000, "Reconnecting")
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing old WebSocket", e)
                }
            }
            webSocket = null
        }

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        val newWebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(connectionLock) {
                    // Only accept this connection if it's still the current one
                    if (this@WebSocketService.webSocket == webSocket) {
                        Log.i(TAG, "WebSocket connected")
                        isConnected = true
                        isConnecting = false
                        reconnectAttempt = 0
                        broadcastConnectionStatus(true)
                        scope.launch {
                            try {
                                val bindingClient = com.trah.accnotify.crypto.DeviceBindingClient(keyManager)
                                if (keyManager.getPendingBinding(serverUrl) != null && bindingClient.finishPending(serverUrl, recoverOnly = true)) {
                                    disconnect(); connect()
                                } else {
                                    bindingClient.status(serverUrl)
                                    if (connectionServerUrl == serverUrl) broadcastConnectionStatus(true)
                                }
                            } catch (_: Exception) { /* Legacy servers do not expose routing metadata. */ }
                        }
                    } else {
                        Log.w(TAG, "Ignoring stale onOpen callback")
                        webSocket.close(1000, "Stale connection")
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                synchronized(connectionLock) {
                    if (this@WebSocketService.webSocket == webSocket) handleMessage(text)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(connectionLock) {
                    if (this@WebSocketService.webSocket == webSocket) {
                        Log.i(TAG, "WebSocket closed: $code $reason")
                        isConnected = false
                        isConnecting = false
                        this@WebSocketService.webSocket = null
                        broadcastConnectionStatus(false)
                        scheduleReconnect()
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                synchronized(connectionLock) {
                    if (this@WebSocketService.webSocket == webSocket) {
                        val message = t.message.orEmpty()
                        val isExpectedDisconnect =
                            t is java.io.EOFException ||
                            message.contains("Software caused connection abort", ignoreCase = true) ||
                            message.contains("Socket closed", ignoreCase = true)

                        if (isExpectedDisconnect) {
                            Log.w(TAG, "WebSocket disconnected: ${t.message}")
                        } else {
                            Log.e(TAG, "WebSocket failure", t)
                        }
                        isConnected = false
                        isConnecting = false
                        this@WebSocketService.webSocket = null
                        broadcastConnectionStatus(false)
                        if (response?.code == 401 && !keyManager.hasPendingReset(serverUrl) && keyManager.getDeviceKey(serverUrl) == deviceKey) {
                            scope.launch {
                                try {
                                    val bindingClient = com.trah.accnotify.crypto.DeviceBindingClient(keyManager)
                                    if (!bindingClient.finishPending(serverUrl, recoverOnly = true) && keyManager.getDeviceKey(serverUrl) == deviceKey) {
                                        keyManager.completeUnregister(serverUrl)
                                        bindingClient.register(serverUrl)
                                    }
                                } catch (_: Exception) { }
                                scheduleReconnect()
                            }
                        } else scheduleReconnect()
                    } else {
                        Log.d(TAG, "Ignoring failure from stale connection")
                    }
                }
            }
        })

        synchronized(connectionLock) {
            webSocket = newWebSocket
        }
    }

    private fun disconnect() {
        synchronized(connectionLock) {
            webSocket?.close(1000, "User requested disconnect")
            webSocket = null
            isConnected = false
            isConnecting = false
        }
    }

    private fun scheduleReconnect() {
        val delay = minOf(
            1000L * (1 shl minOf(reconnectAttempt, 6)), // Exponential backoff
            maxReconnectDelay
        )
        reconnectAttempt++

        Log.i(TAG, "Scheduling reconnect in ${delay}ms (attempt $reconnectAttempt)")

        scope.launch {
            delay(delay)
            if (!isConnected) {
                connect()
            }
        }
    }

    private fun handleMessage(text: String) {
        Log.d(TAG, "Received WebSocket message")
        
        // Acquire temporary WakeLock to ensure message is fully processed
        acquireTemporaryWakeLock()
        
        try {
            val json = gson.fromJson(text, JsonObject::class.java)
            val type = json.get("type")?.asString ?: return

            Log.d(TAG, "Message type: $type")
            when (type) {
                "message" -> handlePushMessage(json)
                "ping" -> sendPong()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message", e)
        } finally {
            // Note: WakeLock has timeout, no need to explicitly release here
            // This ensures message processing completes even if there are delays
        }
    }

    private fun handlePushMessage(json: JsonObject) {
        Log.i(TAG, "Handling push message: ${json.get("id")}")
        val app = AccnotifyApp.getInstance()
        val messageId = json.get("id")?.asString ?: return
        val data = json.getAsJsonObject("data") ?: return
        
        // Update last message time and count for notification display
        lastMessageTime = System.currentTimeMillis()
        messageCount++
        updateForegroundNotification()

        val senderEncrypted = data.has("encryption") || data.has("ciphertext") || data.has("nonce")
        val encryptedContent = if (senderEncrypted) gson.toJson(data) else data.get("encrypted_content")?.asString
        var decryptedContent: String? = null
        val payload = if (senderEncrypted || !encryptedContent.isNullOrEmpty()) {
            val server = connectionServerUrl ?: app.keyManager.serverUrl
            val decryptedPayload = try {
                decryptedContent = if (senderEncrypted) {
                    val key = requireNotNull(app.keyManager.getSenderEncryptionKey(server))
                    SenderEncryption.decrypt(data.get("encryption").asString, data.get("nonce").asString,
                        data.get("ciphertext").asString, key)
                } else {
                    E2ECrypto.tryDecrypt(encryptedContent, app.keyManager.getPrivateKey(server))
                }
                decryptedContent?.let { gson.fromJson(it, JsonObject::class.java) }?.also { fields ->
                    if (senderEncrypted) {
                        val deleting = fields.get("delete")?.takeIf { it.isJsonPrimitive }?.asString in listOf("1", "true")
                        val externalId = fields.get("id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                        if (deleting) {
                            require(!externalId.isNullOrEmpty())
                        } else {
                            val content = fields.get("markdown")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.isNotEmpty() }
                                ?: fields.get("body")
                            require(content != null && content.isJsonPrimitive && content.asJsonPrimitive.isString)
                        }
                        for (field in listOf("title", "body", "markdown", "group", "icon", "url", "image")) {
                            val value = fields.get(field)
                            require(value == null || value.isJsonNull || value.isJsonPrimitive && value.asJsonPrimitive.isString)
                        }
                    }
                }
            } catch (e: Exception) {
                null
            }
            if (decryptedPayload == null) {
                Log.e(TAG, "Unable to decrypt message $messageId")
                NotificationHelper.showNotification(
                    context = this,
                    messageId = messageId,
                    title = "消息无法解密",
                    body = if (senderEncrypted) "请在首页配置当前服务器的发送端加密密钥，并确认与发送脚本一致。"
                        else "请确认当前设备的加密密钥，并重新注册设备。"
                )
                return
            }
            decryptedPayload
        } else {
            // Retain compatibility with older servers sending plaintext messages.
            data
        }

        fun stringField(name: String) = payload.get(name)?.takeUnless { it.isJsonNull }?.asString
        val title = stringField("title")
        val body = stringField("markdown")?.takeIf { it.isNotEmpty() } ?: stringField("body")
        val group = stringField("group")
        val icon = stringField("icon")
        val url = stringField("url")
        val image = stringField("image")

        val externalId = payload.get("id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        val deleting = payload.get("delete")?.takeIf { it.isJsonPrimitive }?.asString in listOf("1", "true")
        if (deleting && externalId.isNullOrEmpty()) {
            Log.e(TAG, "Deletion command is missing its notification ID")
            return
        }
        val server = connectionServerUrl ?: app.keyManager.serverUrl
        val historyId = MessageIdentity.localId(server, app.keyManager.getDeviceKey(server).orEmpty(), externalId, messageId)
        val sequence = json.get("sequence")?.takeUnless { it.isJsonNull }?.asLong ?: 0L
        val message = if (deleting) null else Message(
            messageId = historyId,
            title = title,
            body = body,
            group = group,
            icon = icon,
            url = url,
            image = image,
            encryptedContent = encryptedContent,
            decryptedContent = decryptedContent,
            timestamp = Date()
        )

        scope.launch {
            messageMutex.withLock {
                try {
                    val applied = app.database.messageDao().applyIncoming(historyId, sequence, message)
                    // ACK the unique delivery, never the caller's replaceable notification ID.
                    sendAck(messageId)
                    if (applied) {
                        if (deleting) {
                            NotificationHelper.cancelNotification(this@WebSocketService, historyId)
                        } else {
                            NotificationHelper.showNotification(
                                context = this@WebSocketService,
                                messageId = historyId,
                                title = title ?: "Accnotify",
                                body = body.orEmpty(),
                                group = group,
                                url = url,
                                image = image,
                                revision = messageId
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Unable to apply received message: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun sendAck(messageId: String) {
        val ack = JsonObject().apply {
            addProperty("type", "ack")
            addProperty("id", messageId)
        }
        webSocket?.send(gson.toJson(ack))
    }

    private fun sendPong() {
        val pong = JsonObject().apply {
            addProperty("type", "pong")
            addProperty("timestamp", System.currentTimeMillis() / 1000)
        }
        webSocket?.send(gson.toJson(pong))
    }

    private fun broadcastConnectionStatus(connected: Boolean) {
        val intent = Intent(ACTION_CONNECTION_STATUS).apply {
            putExtra(EXTRA_CONNECTED, connected)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun createForegroundNotification(): Notification {
        return buildForegroundNotification()
    }
    
    private fun updateForegroundNotification() {
        scope.launch(Dispatchers.Main.immediate) {
            if (!AccnotifyApp.getInstance().keyManager.showForegroundNotification) {
                removeForegroundNotification()
                return@launch
            }
            if (!hasEnteredForeground) return@launch
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, buildForegroundNotification())
        }
    }

    private fun removeForegroundNotification() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        hasEnteredForeground = false
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
    }
    
    private fun buildForegroundNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        // Simple status text - keep it minimal
        val statusText = if (isConnected) "运行中" else "连接中..."
        
        // Build minimal notification
        return NotificationCompat.Builder(this, AccnotifyApp.CHANNEL_SERVICE)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)  // 低优先级，保持服务存活
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)
            .setSilent(true)  // 静音
            .build()
    }
    
    /**
     * Check if the accessibility service is enabled
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityEnabled = try {
            Settings.Secure.getInt(
                contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED
            )
        } catch (e: Settings.SettingNotFoundException) {
            0
        }
        
        if (accessibilityEnabled != 1) return false
        
        val serviceString = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(serviceString)
        
        val expectedService = "${packageName}/${KeepAliveAccessibilityService::class.java.canonicalName}"
        
        while (colonSplitter.hasNext()) {
            val service = colonSplitter.next()
            if (service.equals(expectedService, ignoreCase = true)) {
                return true
            }
        }
        
        return false
    }

    companion object {
        private const val TAG = "WebSocketService"
        private const val NOTIFICATION_ID = 1

        const val ACTION_CONNECT = "com.trah.accnotify.action.CONNECT"
        private const val ACTION_REFRESH_NOTIFICATION = "com.trah.accnotify.action.REFRESH_NOTIFICATION"
        const val ACTION_DISCONNECT = "com.trah.accnotify.action.DISCONNECT"
        const val ACTION_SEND_ACK = "com.trah.accnotify.action.SEND_ACK"
        const val ACTION_CONNECTION_STATUS = "com.trah.accnotify.action.CONNECTION_STATUS"

        const val EXTRA_MESSAGE_ID = "message_id"
        const val EXTRA_CONNECTED = "connected"
        const val EXTRA_FOREGROUND = "extra_foreground"

        /**
         * Helper to start the service properly
         */
        fun start(context: Context) {
            startWithAction(context, ACTION_CONNECT)
        }

        fun refreshNotificationPreference(context: Context) {
            startWithAction(context, ACTION_REFRESH_NOTIFICATION)
        }

        private fun startWithAction(context: Context, serviceAction: String) {
            val keys = AccnotifyApp.getInstance().keyManager
            if (!keys.isRegistered || keys.hasPendingReset(keys.serverUrl)) return
            val intent = Intent(context, WebSocketService::class.java).apply {
                action = serviceAction
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val showNotification = try {
                    AccnotifyApp.getInstance().keyManager.showForegroundNotification
                } catch (_: Exception) { false }
                if (showNotification) {
                    intent.putExtra(EXTRA_FOREGROUND, true)
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } else {
                context.startService(intent)
            }
        }
    }
}
