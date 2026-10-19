package com.trah.accnotify.crypto

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class DeviceManagementClient(private val keys: KeyManager) {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()

    suspend fun execute(server: String, action: String) = withContext(Dispatchers.IO + NonCancellable) {
        val deviceKey = requireNotNull(keys.getDeviceKey(server)) { "请先同步当前服务器" }
        val values = mutableMapOf("action" to action, "device_key" to deviceKey)
        if (action == "unregister") {
            check(!keys.hasPendingReset(server)) { "有尚未确认的重置操作，请先重试该操作" }
            values["expected_public_key"] = requireNotNull(keys.exportPublicKeyPEM(server))
        } else {
            val reset = keys.prepareReset(server, action == "reset_all")
            values["expected_public_key"] = reset.expectedPublicKey
            values["new_device_key"] = reset.newDeviceKey
            values["public_key"] = reset.publicKey
        }
        val request = Request.Builder().url(server.trimEnd('/') + "/device/manage")
            .header("Authorization", "Bearer $deviceKey")
            .post(Gson().toJson(values).toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            val result = try { Gson().fromJson(response.body?.string(), JsonObject::class.java) } catch (_: Exception) { null }
            if (!response.isSuccessful || result?.get("success")?.asBoolean != true) {
                val message = if (response.code == 404) "服务端尚不支持此操作，请先升级服务端" else
                    result?.get("error")?.asString ?: "服务器未确认操作，请稍后重试同一操作"
                throw IllegalStateException(message)
            }
            if (action == "unregister") keys.completeUnregister(server) else keys.completeReset(server)
        }
    }
}
