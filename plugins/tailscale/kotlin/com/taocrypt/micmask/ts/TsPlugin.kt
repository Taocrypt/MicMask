// Part of MicMask (GPL-3.0, see LICENSE at the repository root). Links tailscale.com (BSD-3-Clause).
package com.taocrypt.micmask.ts

import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

/**
 * Download-on-demand loader for the Tailscale plugin library. Pure JVM
 * (java.net + org.json, both present on Android and in the desktop build).
 *
 * Typical use (background thread):
 * ```
 * val dir = File(context.filesDir, "plugins/tailscale")        // Android: app-private
 * val ts = TsPlugin.ensure(dir, TsPlugin.DEFAULT_MANIFEST_URLS) { done, total -> ... }
 * val login = ts.start(File(dir, "state").path, "micmask-phone")
 * ```
 */
object TsPlugin {
    /** C/JNI ABI this Kotlin code speaks; must equal the library's abiVersion(). */
    const val ABI = 1

    /** Manifest candidates, tried in order. Release asset of the latest ts-plugin-v* tag is pinned by the app build. */
    val DEFAULT_MANIFEST_URLS: List<String> = listOf(
        "https://github.com/Taocrypt/MicMask/releases/download/ts-plugin-v0.1.0/manifest.json",
    )

    data class Asset(val platform: String, val file: String, val urls: List<String>, val sha256: String, val size: Long)
    data class Manifest(val version: String, val abi: Int, val tailscale: String, val assets: Map<String, Asset>) {
        fun forThisDevice(): Asset? = platformId()?.let { assets[it] }
    }
    data class Installed(val version: String, val platform: String, val sha256: String, val file: File)

    /** "android-arm64-v8a", "windows-x64", or null when no build exists for this runtime. */
    fun platformId(): String? {
        System.getProperty("micmask.ts.platform")?.let { return it } // tests / unusual runtimes
        val vm = (System.getProperty("java.vm.name") ?: "") + (System.getProperty("java.vendor") ?: "")
        val android = vm.contains("Dalvik", true) || vm.contains("Android", true)
        val arch = (System.getProperty("os.arch") ?: "").lowercase()
        val os = (System.getProperty("os.name") ?: "").lowercase()
        return when {
            android && (arch == "aarch64" || arch == "arm64") -> "android-arm64-v8a"
            !android && os.startsWith("windows") && (arch == "amd64" || arch == "x86_64") -> "windows-x64"
            else -> null
        }
    }

    private fun libName(platform: String) = if (platform.startsWith("windows")) "micmask_tailscale.dll" else "libmicmask_tailscale.so"

    fun parseManifest(js: String): Manifest {
        val o = JSONObject(js)
        val ps = o.getJSONObject("platforms")
        val assets = buildMap {
            for (k in ps.keys()) {
                val p = ps.getJSONObject(k)
                val urls = p.optJSONArray("urls")?.let { a -> List(a.length()) { a.getString(it) } }
                    ?: listOf(p.getString("url"))
                put(k, Asset(k, p.getString("file"), urls, p.getString("sha256").lowercase(), p.getLong("size")))
            }
        }
        return Manifest(o.getString("version"), o.getInt("abi"), o.optString("tailscale"), assets)
    }

    fun fetchManifest(urls: List<String>): Manifest {
        var last: Exception? = null
        for (u in urls) {
            try {
                return parseManifest(String(httpGet(u, 1 shl 20), Charsets.UTF_8))
            } catch (e: Exception) {
                last = e
            }
        }
        throw IOException("manifest unavailable: ${last?.message}", last)
    }

    fun installed(dir: File): Installed? = runCatching {
        val o = JSONObject(File(dir, "installed.json").readText())
        val f = File(dir, o.getString("path"))
        if (!f.isFile) null else Installed(o.getString("version"), o.getString("platform"), o.getString("sha256"), f)
    }.getOrNull()

    /**
     * Downloads [asset] into [dir]/<version>/, checks size + SHA-256, then records it
     * in installed.json. Older versions are deleted. [progress] gets (bytes, total);
     * return false from [keepGoing] to cancel.
     */
    fun install(dir: File, m: Manifest, asset: Asset, progress: (Long, Long) -> Unit = { _, _ -> }, keepGoing: () -> Boolean = { true }): Installed {
        if (m.abi != ABI) throw IOException("plugin ABI ${m.abi} != app ABI $ABI; update the app")
        val vdir = File(dir, m.version).apply { mkdirs() }
        val dst = File(vdir, libName(asset.platform))
        if (dst.isFile && dst.length() == asset.size && sha256(dst) == asset.sha256) return record(dir, m, asset, dst)
        val tmp = File(vdir, dst.name + ".part")
        var last: Exception? = null
        for (u in asset.urls) {
            try {
                tmp.delete()
                download(u, tmp, asset.size, progress, keepGoing)
                if (tmp.length() != asset.size) throw IOException("size ${tmp.length()} != ${asset.size}")
                val h = sha256(tmp)
                if (h != asset.sha256) throw IOException("sha256 mismatch: $h")
                dst.delete()
                if (!tmp.renameTo(dst)) throw IOException("rename failed")
                dst.setReadOnly()
                dir.listFiles()?.filter { it.isDirectory && it.name != m.version && it.name != "state" }?.forEach { it.deleteRecursively() }
                return record(dir, m, asset, dst)
            } catch (e: InterruptedException) {
                tmp.delete(); throw e
            } catch (e: Exception) {
                last = e
            }
        }
        tmp.delete()
        throw IOException("download failed: ${last?.message}", last)
    }

    private fun record(dir: File, m: Manifest, a: Asset, f: File): Installed {
        File(dir, "installed.json").writeText(
            JSONObject().put("version", m.version).put("platform", a.platform).put("sha256", a.sha256)
                .put("path", f.relativeTo(dir).path).put("tailscale", m.tailscale).toString()
        )
        return Installed(m.version, a.platform, a.sha256, f)
    }

    @Volatile private var loadedPath: String? = null
    @Volatile private var bridge: TsBridge? = null

    /**
     * Loads an installed library (re-verifying its SHA-256 by default) and
     * returns the bridge. A process can load only one plugin version; a newer
     * one takes effect after an app restart.
     */
    @Synchronized
    fun load(inst: Installed, verify: Boolean = true): TsBridge {
        val path = inst.file.absolutePath
        bridge?.let { if (loadedPath == path) return it else throw IllegalStateException("another plugin version is loaded; restart the app") }
        if (verify && sha256(inst.file) != inst.sha256) throw IOException("installed plugin is corrupted; reinstall")
        System.load(path)
        val abi = TsNative.abiVersion()
        if (abi != ABI) throw IOException("plugin ABI $abi != app ABI $ABI")
        loadedPath = path
        return NativeTsBridge().also { bridge = it }
    }

    /** The bridge if the library is already loaded in this process. */
    fun loadedOrNull(): TsBridge? = bridge

    /**
     * Installs the manifest's build for this device if missing/outdated, then loads it.
     * If the manifest cannot be fetched but a verified copy is installed, that copy is used.
     */
    fun ensure(dir: File, manifestUrls: List<String> = DEFAULT_MANIFEST_URLS, progress: (Long, Long) -> Unit = { _, _ -> }): TsBridge {
        bridge?.let { return it }
        val have = installed(dir)
        val m = try { fetchManifest(manifestUrls) } catch (e: IOException) {
            if (have != null) return load(have) else throw e
        }
        val asset = m.forThisDevice() ?: throw IOException("no Tailscale plugin build for ${platformId() ?: System.getProperty("os.name") + "/" + System.getProperty("os.arch")}")
        val inst = if (have != null && have.version == m.version && have.sha256 == asset.sha256) have else install(dir, m, asset, progress)
        return load(inst)
    }

    /** Removes downloaded libraries (keeps the "state" dir unless [keepState] is false). Effective for loading after restart. */
    fun uninstall(dir: File, keepState: Boolean = true) {
        dir.listFiles()?.forEach { if (!(keepState && it.name == "state")) it.deleteRecursively() }
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf); if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun open(u: String): HttpURLConnection {
        var url: URL = URI(u).toURL()
        repeat(6) {
            val c = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "MicMask-TsPlugin")
            }
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("redirect without Location")
                c.disconnect(); url = url.toURI().resolve(loc).toURL(); return@repeat
            }
            if (code != 200) { c.disconnect(); throw IOException("HTTP $code for $url") }
            return c
        }
        throw IOException("too many redirects")
    }

    private fun httpGet(u: String, max: Int): ByteArray {
        if (u.startsWith("file:")) return File(URI(u)).readBytes()
        val c = open(u)
        try {
            return c.inputStream.use { ins ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = ins.read(buf); if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > max) throw IOException("response too large")
                }
                out.toByteArray()
            }
        } finally { c.disconnect() }
    }

    private fun download(u: String, to: File, expected: Long, progress: (Long, Long) -> Unit, keepGoing: () -> Boolean) {
        if (u.startsWith("file:")) { File(URI(u)).copyTo(to, true); progress(to.length(), expected); return }
        val c = open(u)
        try {
            c.inputStream.use { ins ->
                to.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    while (true) {
                        if (!keepGoing()) throw IOException("cancelled")
                        val n = ins.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (done > expected) throw IOException("larger than manifest size")
                        progress(done, expected)
                    }
                }
            }
        } finally { c.disconnect() }
    }
}
