package com.taocrypt.beacon

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.SecureRandom
import java.net.HttpURLConnection
import java.net.URL

internal class HttpResult(val code: Int, val body: String, val etag: String?)

internal object Http {
    fun request(url: String, method: String, key: String, body: String? = null, etag: String? = null): HttpResult {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 8000
            c.readTimeout = 10000
            c.useCaches = false
            c.setRequestProperty("X-Client-Key", key)
            c.setRequestProperty("Accept", "application/json")
            if (etag != null) c.setRequestProperty("If-None-Match", etag)
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
            }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else if (code == 304) null else c.inputStream
            val text = stream?.use { s -> ByteArrayOutputStream().also { s.copyTo(it) }.toString("UTF-8") } ?: ""
            return HttpResult(code, text, c.getHeaderField("ETag"))
        } finally {
            c.disconnect()
        }
    }

    /** multipart/form-data（反馈附件）。parts 为文本字段，files 为 (文件名, 打开流)；分块流式上传，不整体读进内存 */
    fun multipart(url: String, key: String, fields: Map<String, String>, files: List<Pair<String, () -> InputStream>>): HttpResult {
        val boundary = "----beacon" + java.lang.Long.toHexString(SecureRandom().nextLong())
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 8000
            c.readTimeout = 60000
            c.useCaches = false
            c.doOutput = true
            c.setChunkedStreamingMode(64 * 1024)
            c.setRequestProperty("X-Client-Key", key)
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            c.outputStream.use { out ->
                fun w(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
                for ((k, v) in fields) {
                    w("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n"); w(v); w("\r\n")
                }
                for ((name, open) in files) {
                    val safe = name.replace("\"", "_").replace("\r", "").replace("\n", "")
                    w("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$safe\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                    open().use { it.copyTo(out, 64 * 1024) }
                    w("\r\n")
                }
                w("--$boundary--\r\n")
            }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val text = stream?.use { s -> ByteArrayOutputStream().also { s.copyTo(it) }.toString("UTF-8") } ?: ""
            return HttpResult(code, text, null)
        } finally {
            c.disconnect()
        }
    }
}
