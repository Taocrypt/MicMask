package com.taocrypt.beacon

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** 极简图片加载：只为自带弹窗和轻量接入准备，最大 5MB，按宽度降采样 */
internal object Images {
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(((Runtime.getRuntime().maxMemory() / 1024) / 16).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    fun load(url: String, maxWidth: Int, cb: (Bitmap?) -> Unit) {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return cb(null)
        val key = "$maxWidth|$url"
        cache.get(key)?.let { return cb(it) }
        pool.execute {
            val bmp = runCatching { fetch(url, maxWidth) }.getOrNull()
            if (bmp != null) cache.put(key, bmp)
            main.post { cb(bmp) }
        }
    }

    private fun fetch(url: String, maxWidth: Int): Bitmap? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 15000; c.instanceFollowRedirects = true
        try {
            if (c.responseCode !in 200..299) return null
            val bytes = c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16384); var total = 0
                while (true) { val n = input.read(buf); if (n < 0) break; total += n; if (total > 5 * 1024 * 1024) return null; out.write(buf, 0, n) }
                out.toByteArray()
            }
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var s = 1
            while (o.outWidth / (s * 2) >= maxWidth) s *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
        } finally { c.disconnect() }
    }
}
