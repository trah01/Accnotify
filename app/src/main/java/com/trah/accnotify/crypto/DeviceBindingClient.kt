package com.trah.accnotify.crypto

import android.os.Build
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class BindingException(val status: Int, message: String) : IllegalStateException(message)
class DeviceBindingClient(private val keys: KeyManager) {
    private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    suspend fun request(server: String, path: String, body: JsonObject? = null, credential: String = requireNotNull(keys.getDeviceKey(server))): JsonObject = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(server.trimEnd('/') + path).header("Authorization", "Bearer $credential")
        if (body != null) builder.post(body.toString().toRequestBody("application/json".toMediaType()))
        client.newCall(builder.build()).execute().use { response ->
            val result = try { JsonParser.parseString(response.body?.string().orEmpty()).asJsonObject } catch (_: Exception) { null }
            if (!response.isSuccessful || result?.get("success")?.asBoolean != true) throw BindingException(response.code,
                if (response.code == 404 && path.startsWith("/binding/")) "服务端尚不支持多设备，请先升级服务端" else result?.get("error")?.asString ?: "操作失败，请稍后重试")
            requireNotNull(result)
        }
    }
    suspend fun status(server: String): JsonObject {
        val result = request(server, "/binding/status")
        keys.saveRouting(server, result.get("push_key").asString, result.get("endpoint_id").asString, result.getAsJsonArray("devices").size())
        return result
    }
    suspend fun register(server: String) {
        val body = JsonObject().apply { addProperty("device_key", keys.getDeviceKey(server)); addProperty("public_key", keys.exportPublicKeyPEM(server)); addProperty("name", Build.MODEL); addProperty("platform", "mobile") }
        request(server, "/register", body)
        keys.setRegistered(server, true)
        status(server)
    }
    suspend fun finishPending(server: String, recoverOnly: Boolean = false): Boolean {
        val text = keys.getPendingBinding(server) ?: return false
        val pending = JsonParser.parseString(text).asJsonObject
        if (!pending.has("code")) return false
        val newKey = pending.get("new_device_key").asString
        val result = try { request(server, "/binding/status", credential = newKey) } catch (e: BindingException) {
            if (e.status != 401) throw e
            // Background reconnects only recover a completed binding; they must
            // not consume verification attempts for a previously entered code.
            if (recoverOnly) return false
            request(server, "/binding/verify", pending, pending.get("old_key").asString)
        }
        keys.completeBinding(server, newKey, result.get("push_key").asString)
        status(server)
        return true
    }
}
