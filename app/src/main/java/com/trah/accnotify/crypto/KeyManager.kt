package com.trah.accnotify.crypto

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyPair
import java.security.MessageDigest
import java.util.UUID
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom

class KeyManager(private val context: Context) {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    private val prefs by lazy { createEncryptedPrefs() }

    private fun createEncryptedPrefs() = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private fun identityPrefix(server: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(server.trim().trimEnd('/').toByteArray(Charsets.UTF_8))
        return "identity_" + digest.joinToString("") { "%02x".format(it.toInt() and 0xff) } + "_"
    }

    // Snapshot every known server before any reset. Legacy servers may share
    // the original key, which must remain available until each is rotated.
    @Synchronized
    private fun migrateIdentities() {
        if (prefs.getBoolean("identities_migrated", false)) return
        val editor = prefs.edit()
        for (server in getServers() + serverUrl) {
            val prefix = identityPrefix(server)
            val alias = if (keyStore.containsAlias(RSA_KEY_ALIAS)) RSA_KEY_ALIAS else newRSAKeyAlias()
            editor.putString(prefix + "alias", alias)
                .putString(prefix + "device_key", prefs.getString(PREF_DEVICE_KEY, null) ?: generateDeviceId())
                .putBoolean(prefix + "registered", prefs.getBoolean(PREF_IS_REGISTERED, false))
        }
        check(editor.putBoolean("identities_migrated", true).commit()) { "Unable to migrate server identities" }
    }

    @Synchronized
    private fun ensureIdentity(server: String) {
        migrateIdentities()
        val prefix = identityPrefix(server)
        if (prefs.getString(prefix + "alias", null) == null) {
            check(prefs.edit().putString(prefix + "alias", newRSAKeyAlias())
                .putString(prefix + "device_key", generateDeviceId())
                .putBoolean(prefix + "registered", false).commit()) { "Unable to save server identity" }
        }
    }

    fun ensureKeysExist(server: String = serverUrl) {
        ensureIdentity(server)
        val prefix = identityPrefix(server)
        if (prefs.getString(prefix + "device_key", null) == null) {
            check(prefs.edit().putString(prefix + "device_key", generateDeviceId()).commit())
        }
    }

    private fun activeAlias(server: String): String {
        ensureIdentity(server)
        return requireNotNull(prefs.getString(identityPrefix(server) + "alias", null))
    }

    private fun newRSAKeyAlias(): String {
        val alias = "accnotify_rsa_" + UUID.randomUUID().toString()
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_DECRYPT or KeyProperties.PURPOSE_ENCRYPT)
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            .build()
        generator.initialize(spec)
        generator.generateKeyPair()
        return alias
    }

    fun getKeyPair(): KeyPair? = try {
        val alias = activeAlias(serverUrl)
        val privateKey = keyStore.getKey(alias, null) as? PrivateKey
        val publicKey = keyStore.getCertificate(alias)?.publicKey
        if (privateKey != null && publicKey != null) KeyPair(publicKey, privateKey) else null
    } catch (_: Exception) { null }

    fun getPrivateKey(server: String = serverUrl): PrivateKey? =
        keyStore.getKey(activeAlias(server), null) as? PrivateKey

    fun getPublicKey(server: String = serverUrl): PublicKey? = keyStore.getCertificate(activeAlias(server))?.publicKey

    private fun publicKeyPEM(alias: String): String {
        val publicKey = requireNotNull(keyStore.getCertificate(alias)?.publicKey)
        val encoded = Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
        return "-----BEGIN PUBLIC KEY-----\n" + encoded.chunked(64).joinToString("\n") + "\n-----END PUBLIC KEY-----"
    }

    fun exportPublicKeyPEM(server: String = serverUrl): String? = publicKeyPEM(activeAlias(server))

    fun generateDeviceId(): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val random = SecureRandom()
        return CharArray(32) { alphabet[random.nextInt(alphabet.length)] }.concatToString()
    }

    fun getDeviceKey(server: String = serverUrl): String? {
        ensureIdentity(server)
        return prefs.getString(identityPrefix(server) + "device_key", null)
    }

    fun getPushKey(server: String = serverUrl): String? {
        val own = getDeviceKey(server)
        return if (prefs.getString(identityPrefix(server) + "routing_device_key", null) == own)
            prefs.getString(identityPrefix(server) + "push_key", own) else own
    }
    fun getEndpointId(server: String = serverUrl): String? = if (prefs.getString(identityPrefix(server) + "routing_device_key", null) == getDeviceKey(server))
        prefs.getString(identityPrefix(server) + "endpoint_id", null) else null
    fun getMemberCount(server: String = serverUrl): Int = if (prefs.getString(identityPrefix(server) + "routing_device_key", null) == getDeviceKey(server))
        prefs.getInt(identityPrefix(server) + "member_count", 1) else 1
    fun saveRouting(server: String, pushKey: String, endpointId: String, memberCount: Int) {
        val prefix = identityPrefix(server)
        check(prefs.edit().putString(prefix + "routing_device_key", getDeviceKey(server)).putString(prefix + "push_key", pushKey)
            .putString(prefix + "endpoint_id", endpointId).putInt(prefix + "member_count", memberCount).commit())
    }
    fun getPendingBinding(server: String): String? = prefs.getString(identityPrefix(server) + "binding_pending", null)
    fun setPendingBinding(server: String, value: String?) { check(prefs.edit().putString(identityPrefix(server) + "binding_pending", value).commit()) }
    fun completeBinding(server: String, ownKey: String, pushKey: String) {
        val prefix = identityPrefix(server)
        check(prefs.edit().putString(prefix + "device_key", ownKey).putString(prefix + "private_device_id", ownKey)
            .putBoolean(prefix + "reuse_common_device_id", false).putBoolean(prefix + "registered", true)
            .putString(prefix + "routing_device_key", ownKey).putString(prefix + "push_key", pushKey).remove(prefix + "binding_pending").commit())
    }

    @Synchronized
    fun getCommonDeviceId(): String {
        prefs.getString(PREF_COMMON_DEVICE_ID, null)?.let { return it }
        val id = generateDeviceId()
        check(prefs.edit().putString(PREF_COMMON_DEVICE_ID, id).commit()) { "公用 ID 保存失败" }
        return id
    }

    @Synchronized
    fun isUsingCommonDeviceId(server: String = serverUrl): Boolean {
        ensureKeysExist(server)
        val flag = identityPrefix(server) + "reuse_common_device_id"
        if (prefs.contains(flag)) return prefs.getBoolean(flag, false)
        val usingCommon = getDeviceKey(server) == getCommonDeviceId()
        check(prefs.edit().putBoolean(flag, usingCommon).commit()) { "设备 ID 配置保存失败" }
        return usingCommon
    }

    private fun configureDeviceId(editor: SharedPreferences.Editor, server: String, reuseCommonId: Boolean,
        newPrivateId: String? = null): Boolean {
        val prefix = identityPrefix(server)
        val currentId = requireNotNull(getDeviceKey(server))
        val wasUsingCommon = isUsingCommonDeviceId(server)
        val privateId = newPrivateId ?: prefs.getString(prefix + "private_device_id", null)
            ?: if (wasUsingCommon) generateDeviceId() else currentId
        val nextId = if (reuseCommonId) getCommonDeviceId() else privateId
        if (nextId != currentId) {
            check(!hasPendingReset(server)) { "请先完成此服务器待确认的重置操作" }
            editor.putString(prefix + "device_key", nextId).putBoolean(prefix + "registered", false)
        }
        editor.putString(prefix + "private_device_id", privateId)
            .putBoolean(prefix + "reuse_common_device_id", reuseCommonId)
        return nextId != currentId
    }

    @Synchronized
    fun selectDeviceId(server: String, reuseCommonId: Boolean, regeneratePrivate: Boolean = false): Boolean {
        require(!reuseCommonId || !regeneratePrivate) { "公用 ID 只能在通用信息设置中重置" }
        check(!hasPendingReset(server)) { "请先完成此服务器待确认的重置操作" }
        ensureKeysExist(server)
        val editor = prefs.edit()
        val changed = configureDeviceId(editor, server, reuseCommonId,
            if (regeneratePrivate) generateDeviceId() else null)
        check(editor.commit()) { "设备 ID 保存失败" }
        return changed
    }

    @Synchronized
    private fun migrateCommonSenderKeyUsage() {
        if (prefs.getBoolean("common_sender_key_usage_migrated", false)) return
        val commonKey = prefs.getString(PREF_COMMON_SENDER_KEY, null)
        val editor = prefs.edit()
        for ((name, value) in prefs.all) {
            if (name.startsWith("identity_") && name.endsWith("sender_encryption_key")) {
                val flag = name.removeSuffix("sender_encryption_key") + "reuse_common_sender_key"
                if (!prefs.contains(flag)) editor.putBoolean(flag, commonKey != null && value == commonKey)
            }
        }
        check(editor.putBoolean("common_sender_key_usage_migrated", true).commit()) { "密钥配置迁移失败" }
    }

    @Synchronized
    fun isUsingCommonSenderKey(server: String = serverUrl): Boolean {
        migrateCommonSenderKeyUsage()
        return prefs.getBoolean(identityPrefix(server) + "reuse_common_sender_key", false)
    }

    @Synchronized
    fun getSenderEncryptionKey(server: String = serverUrl): String? =
        if (isUsingCommonSenderKey(server)) getCommonSenderEncryptionKey()
        else prefs.getString(identityPrefix(server) + "sender_encryption_key", null)

    private fun configureSenderKey(editor: SharedPreferences.Editor, server: String, reuseCommonKey: Boolean) {
        val prefix = identityPrefix(server)
        val name = prefix + "sender_encryption_key"
        if (reuseCommonKey) editor.remove(name)
        else if (isUsingCommonSenderKey(server)) editor.putString(name, getCommonSenderEncryptionKey())
        editor.putBoolean(prefix + "reuse_common_sender_key", reuseCommonKey)
    }

    @Synchronized
    fun getCommonSenderEncryptionKey(): String {
        prefs.getString(PREF_COMMON_SENDER_KEY, null)?.let { return it }
        val key = SenderEncryption.generateKey()
        check(prefs.edit().putString(PREF_COMMON_SENDER_KEY, key).commit()) { "公用秘钥保存失败" }
        return key
    }

    @Synchronized
    fun setCommonInfo(deviceId: String, encryptionKey: String): Boolean {
        require(deviceId.matches(Regex("[A-Za-z0-9_-]{32}"))) { "设备 ID 格式无效" }
        val key = SenderEncryption.normalizeKey(encryptionKey)
        val previousKey = getSenderEncryptionKey()
        check(prefs.edit().putString(PREF_COMMON_DEVICE_ID, deviceId)
            .putString(PREF_COMMON_SENDER_KEY, key).commit()) { "通用信息保存失败" }
        return previousKey != getSenderEncryptionKey()
    }

    @Synchronized
    fun setSenderEncryptionKey(server: String, value: String, reuseCommonKey: Boolean = false) {
        migrateCommonSenderKeyUsage()
        val prefix = identityPrefix(server)
        val name = prefix + "sender_encryption_key"
        val editor = prefs.edit().putBoolean(prefix + "reuse_common_sender_key", reuseCommonKey)
        if (reuseCommonKey || value.isBlank()) editor.remove(name)
        else editor.putString(name, SenderEncryption.normalizeKey(value))
        check(editor.commit()) { "密钥保存失败" }
    }

    data class PendingReset(val deviceKey: String, val expectedPublicKey: String,
        val newDeviceKey: String, val publicKey: String, val resetDeviceId: Boolean)

    // Keep the candidate key on disk until the server confirms the change.
    // A timeout must not destroy either the old private key or the candidate.
    @Synchronized
    fun prepareReset(server: String, resetDeviceId: Boolean): PendingReset {
        ensureIdentity(server)
        val prefix = identityPrefix(server)
        var alias = prefs.getString(prefix + "pending_alias", null)
        if (alias == null) {
            alias = newRSAKeyAlias()
            val deviceKey = requireNotNull(getDeviceKey(server))
            check(prefs.edit().putString(prefix + "pending_alias", alias)
                .putString(prefix + "pending_device_key", if (resetDeviceId) generateDeviceId() else deviceKey)
                .putBoolean(prefix + "pending_reset_id", resetDeviceId).commit())
        }
        check(prefs.getBoolean(prefix + "pending_reset_id", false) == resetDeviceId) {
            "上次重置结果尚未确认，请先重试同一种重置操作"
        }
        return PendingReset(requireNotNull(getDeviceKey(server)), publicKeyPEM(activeAlias(server)),
            requireNotNull(prefs.getString(prefix + "pending_device_key", null)), publicKeyPEM(requireNotNull(alias)), resetDeviceId)
    }

    @Synchronized
    fun completeReset(server: String) {
        val prefix = identityPrefix(server)
        val oldAlias = activeAlias(server)
        val newAlias = requireNotNull(prefs.getString(prefix + "pending_alias", null))
        val newDeviceKey = requireNotNull(prefs.getString(prefix + "pending_device_key", null))
        val editor = prefs.edit().putString(prefix + "alias", newAlias).putString(prefix + "device_key", newDeviceKey)
        if (prefs.getBoolean(prefix + "pending_reset_id", false)) {
            editor.putString(prefix + "private_device_id", newDeviceKey)
                .putBoolean(prefix + "reuse_common_device_id", false)
        }
        check(editor
            .putBoolean(prefix + "registered", true).remove(prefix + "pending_alias")
            .remove(prefix + "pending_device_key").remove(prefix + "pending_reset_id").commit())
        deleteUnusedAlias(oldAlias)
    }

    fun hasPendingReset(server: String): Boolean = prefs.contains(identityPrefix(server) + "pending_alias")

    @Synchronized
    fun completeUnregister(server: String) {
        val prefix = identityPrefix(server)
        check(prefs.edit().remove(prefix + "device_key").remove(prefix + "private_device_id")
            .remove(prefix + "binding_pending").remove(prefix + "routing_device_key")
            .remove(prefix + "push_key").remove(prefix + "endpoint_id").remove(prefix + "member_count")
            .putBoolean(prefix + "reuse_common_device_id", false)
            .putBoolean(prefix + "registered", false).commit())
        // Retain the local RSA key and local message history; only unregister
        // the ID and the public key stored on the selected server.
    }

    private fun deleteUnusedAlias(alias: String) {
        if (alias != RSA_KEY_ALIAS && prefs.all.values.none { it == alias }) {
            try { keyStore.deleteEntry(alias) } catch (_: Exception) { }
        }
    }

    /**
     * Get or set server URL
     */
    var serverUrl: String
        get() = prefs.getString(PREF_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) {
            ensureKeysExist(value)
            prefs.edit().putString(PREF_SERVER_URL, value).apply()
            addServer(value) // Automatically add to history list
        }

    fun getServers(): Set<String> {
        return prefs.getStringSet(PREF_SERVER_LIST, setOf(DEFAULT_SERVER_URL)) ?: setOf(DEFAULT_SERVER_URL)
    }

    @Synchronized
    fun addServer(url: String, reuseCommonKey: Boolean? = null, reuseCommonId: Boolean? = null) {
        // A null ID choice preserves the saved ID when selecting a server.
        ensureKeysExist(url)
        val current = getServers().toMutableSet()
        current.add(url)
        val editor = prefs.edit().putStringSet(PREF_SERVER_LIST, current)
        if (reuseCommonId != null) configureDeviceId(editor, url, reuseCommonId)
        if (reuseCommonKey != null) configureSenderKey(editor, url, reuseCommonKey)
        check(editor.commit()) { "服务器保存失败" }
    }

    @Synchronized
    fun updateServerAddress(oldUrl: String, newUrl: String, reuseCommonKey: Boolean = false,
        reuseCommonId: Boolean? = null) {
        val wasSelected = serverUrl == oldUrl
        ensureKeysExist(newUrl)
        val servers = getServers().toMutableSet().apply {
            remove(oldUrl)
            add(newUrl)
        }
        val editor = prefs.edit().putStringSet(PREF_SERVER_LIST, servers)
        if (reuseCommonId != null) configureDeviceId(editor, newUrl, reuseCommonId)
        configureSenderKey(editor, newUrl, reuseCommonKey)
        if (wasSelected) editor.putString(PREF_SERVER_URL, newUrl)
        check(editor.commit()) { "服务器地址保存失败" }
    }

    @Synchronized
    fun removeServer(url: String): Boolean {
        val current = getServers().toMutableSet()
        if (url !in current) return true
        if (current.size <= 1) return false
        current.remove(url)
        val editor = prefs.edit().putStringSet(PREF_SERVER_LIST, current)
        if (serverUrl == url) {
            val nextServer = current.first()
            ensureKeysExist(nextServer)
            editor.putString(PREF_SERVER_URL, nextServer)
        }
        check(editor.commit()) { "服务器删除失败" }
        return true
    }

    /**
     * Check if device is registered with server
     */
    var isRegistered: Boolean
        get() {
            ensureIdentity(serverUrl)
            return prefs.getBoolean(identityPrefix(serverUrl) + "registered", false)
        }
        set(value) {
            ensureIdentity(serverUrl)
            prefs.edit().putBoolean(identityPrefix(serverUrl) + "registered", value).apply()
        }

    fun setRegistered(server: String, registered: Boolean) {
        ensureIdentity(server)
        prefs.edit().putBoolean(identityPrefix(server) + "registered", registered).apply()
    }

    /**
     * Control whether to show foreground notification for keep-alive
     * Default is true (show notification) for better reliability
     * When disabled, the app relies on AccessibilityService + JobService for keep-alive
     */
    var showForegroundNotification: Boolean
        get() = prefs.getBoolean(PREF_SHOW_FOREGROUND_NOTIFICATION, false)
        set(value) = prefs.edit().putBoolean(PREF_SHOW_FOREGROUND_NOTIFICATION, value).apply()

    /**
     * Theme color preference
     * Values: "white" (default), "blue", "green", "red", "pink", "black"
     */
    var themeColor: String
        get() = prefs.getString(PREF_THEME_COLOR, "white") ?: "white"
        set(value) = prefs.edit().putString(PREF_THEME_COLOR, value).apply()

    /**
     * Display mode preference: system / light / dark
     */
    var themeMode: String
        get() = prefs.getString(PREF_THEME_MODE, "system") ?: "system"
        set(value) = prefs.edit().putString(PREF_THEME_MODE, value).apply()

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val RSA_KEY_ALIAS = "accnotify_rsa_key"
        private const val PREFS_NAME = "accnotify_secure_prefs"
        private const val PREF_COMMON_SENDER_KEY = "common_sender_encryption_key"
        private const val PREF_COMMON_DEVICE_ID = "common_device_id"
        private const val PREF_DEVICE_KEY = "device_key"
        private const val PREF_SERVER_URL = "server_url"
        private const val PREF_SERVER_LIST = "server_list"
        private const val PREF_IS_REGISTERED = "is_registered"
        private const val PREF_SHOW_FOREGROUND_NOTIFICATION = "show_foreground_notification"
        private const val PREF_THEME_COLOR = "theme_color"
        private const val PREF_THEME_MODE = "theme_mode"
        private const val DEFAULT_SERVER_URL = "https://an.trah.cn"

        // Accent colors match the light and dark theme resources.
        val THEME_COLORS = mapOf(
            "white" to ThemeColorDef("#000000", "#FFFFFF", "白色"),
            "blue" to ThemeColorDef("#2563B8", "#80B2FF", "蓝色"),
            "green" to ThemeColorDef("#21843F", "#79D36C", "绿色"),
            "red" to ThemeColorDef("#B83D32", "#FF8F82", "红色"),
            "pink" to ThemeColorDef("#B73870", "#FF87B9", "粉色"),
            "black" to ThemeColorDef("#000000", "#FFFFFF", "深色")
        )
    }

    data class ThemeColorDef(
        val lightPrimary: String,   // Light mode primary color
        val darkPrimary: String,    // Dark mode primary color
        val displayName: String     // Chinese display name
    )

}
