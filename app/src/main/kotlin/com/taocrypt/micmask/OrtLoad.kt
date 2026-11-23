package com.taocrypt.micmask

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * 模型加载：首次把 .onnx 优化后另存为 .ort（ORT 格式），之后 mmap 直接加载。
 * mmap 的好处：权重直接用文件映射页，不再整份读进内存再解析，切音色/重开实时更快、占用更少。
 * 缓存放在模型同目录的 .ortcache/，文件名带原模型大小+修改时间+ORT 版本，任何一项变了都会重新生成；
 * mmap 加载失败自动删缓存回退原 .onnx。
 */
object OrtLoad {
    private const val DIR = ".ortcache"
    // 映射缓冲必须活得比会话久：用会话作弱键，会话被回收后映射才释放
    private val keep = java.util.WeakHashMap<OrtSession, ByteBuffer>()

    private fun ver() = runCatching { OrtEnvironment.getEnvironment().version }.getOrDefault("x").replace(Regex("[^0-9A-Za-z.]"), "")
    private fun cacheOf(f: File) = File(File(f.parentFile, DIR), "${f.name}.${f.length()}.${f.lastModified()}.v${ver()}.ort")

    /** 删掉某个模型的全部 .ort 缓存（删模型时调用，不然缓存会孤零零留着占空间） */
    fun dropCache(f: File) {
        File(f.parentFile, DIR).listFiles()?.filter { it.name.startsWith(f.name + ".") }?.forEach { it.delete() }
    }

    /** mk 每次都要返回一个新的 SessionOptions（这里会往里加配置项） */
    fun create(env: OrtEnvironment, f: File, mk: () -> OrtSession.SessionOptions): OrtSession {
        val c = cacheOf(f)
        if (c.isFile && c.length() > 0) {
            val t = System.nanoTime()
            val s = runCatching {
                val buf = RandomAccessFile(c, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }
                val o = mk().apply {
                    addConfigEntry("session.use_ort_model_bytes_directly", "1")
                    addConfigEntry("session.use_ort_model_bytes_for_initializers", "1")
                }
                env.createSession(buf, o).also { synchronized(keep) { keep[it] = buf } }
            }.onFailure { L.e("ort 缓存加载失败，删除并回退 onnx：${c.name}", it); c.delete() }.getOrNull()
            if (s != null) { L.i("ort mmap 加载 ${f.name} 用时 ${(System.nanoTime() - t) / 1_000_000}ms"); return s }
        }
        // 首次：正常加载，顺便另存 .ort
        val t = System.nanoTime()
        val dir = c.parentFile
        val tmp = File(dir, c.name + ".tmp")
        val canSave = runCatching { dir!!.mkdirs(); dir.isDirectory && dir.canWrite() }.getOrDefault(false)
        if (canSave) cleanup(f, dir!!)
        val s = runCatching {
            env.createSession(f.absolutePath, mk().apply {
                if (canSave) { setOptimizedModelFilePath(tmp.absolutePath); addConfigEntry("session.save_model_format", "ORT") }
            })
        }.getOrElse {
            // 另存失败不应影响使用：不带另存再加载一次
            L.e("ort 另存失败，直接加载 ${f.name}", it); tmp.delete()
            return env.createSession(f.absolutePath, mk())
        }
        if (canSave) { if (tmp.isFile && tmp.length() > 0 && tmp.renameTo(c)) L.i("ort 已生成缓存 ${c.name}（${c.length() shr 20}MB）") else tmp.delete() }
        L.i("ort 首次加载 ${f.name} 用时 ${(System.nanoTime() - t) / 1_000_000}ms")
        return s
    }

    /** 删掉本模型的旧缓存，以及原模型已不存在的缓存 */
    private fun cleanup(f: File, dir: File) = runCatching {
        val keepName = cacheOf(f).name
        dir.listFiles()?.forEach { x ->
            val base = x.name.substringBefore(".onnx.", "")
            val stale = base.isEmpty() || !File(f.parentFile, "$base.onnx").exists() || (x.name.startsWith(f.name + ".") && x.name != keepName)
            if (stale) x.delete()
        }
    }

    /** 所有缓存占用（字节），给设置页显示/清理用 */
    fun size(modelDir: File) = File(modelDir, DIR).listFiles()?.sumOf { it.length() } ?: 0L
    fun clear(modelDir: File) = File(modelDir, DIR).deleteRecursively()
}
