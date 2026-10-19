package com.trah.accnotify.util

import java.util.UUID

object MessageIdentity {
    fun localId(server: String, deviceKey: String, externalId: String?, deliveryId: String): String {
        if (externalId.isNullOrEmpty()) return deliveryId
        val identity = listOf(server.trim().trimEnd('/'), deviceKey, externalId).joinToString("\u0000")
        return "external:" + UUID.nameUUIDFromBytes(identity.toByteArray(Charsets.UTF_8))
    }
}
