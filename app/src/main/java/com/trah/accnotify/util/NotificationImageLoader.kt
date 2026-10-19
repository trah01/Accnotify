package com.trah.accnotify.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

object NotificationImageLoader {
    private const val MAX_BYTES = 8 * 1024 * 1024

    fun load(source: String): Bitmap? = try {
        val bytes = if (source.startsWith("https://") || source.startsWith("http://")) {
            download(source)
        } else {
            val encoded = source.substringAfter(',', source)
            require(encoded.length <= (MAX_BYTES + 2) / 3 * 4)
            Base64.decode(encoded, Base64.DEFAULT).also { require(it.size <= MAX_BYTES) }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0)
        val options = BitmapFactory.Options().apply {
            while (bounds.outWidth / inSampleSize > 512 || bounds.outHeight / inSampleSize > 512) {
                inSampleSize *= 2
            }
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    } catch (_: Exception) {
        null
    }

    private fun download(source: String): ByteArray {
        val connection = URL(source).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("Accept", "image/*")
            require(connection.responseCode == HttpURLConnection.HTTP_OK)
            require(connection.contentLengthLong <= MAX_BYTES)
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_BYTES)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }
}
