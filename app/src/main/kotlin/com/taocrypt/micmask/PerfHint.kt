package com.taocrypt.micmask

import android.content.Context
import android.os.Build
import java.io.File

/**
 * ADPF（PerformanceHintManager，API 31+）：把推理线程登记成一个 hint 会话，每块上报实际耗时、目标耗时 = 块长，
 * 让系统按需提频（2.1.1-alpha.6，参考 Alkove Input 的做法）。
 *
 * 线程：特征 / 合成两个 Java 线程 + ORT / NEON 的原生工作线程（它们在加载模型的线程里创建，线程名继承为
 * engine-start / live-downshift，从 /proc/self/task 按名字找）。
 * shell 进程能否建会话因 ROM 而异：任何一步不可用（API < 31、拿不到服务、系统不支持返回 null、抛异常）都只记一行日志后跳过，
 * 本次运行不再尝试；上报出错也只记一次并停用。只在合成线程里调用。
 */
class PerfHint(private val ctx: () -> Context?) {
    private var tried = false
    private var session: Any? = null
    private var targetNs = 0L
    private var reports = 0L
    @Volatile var featTid = -1

    /** 合成线程每块调一次：[actualNs] = 本块关键路径耗时，[targetNs] = 块长 */
    fun report(actualNs: Long, target: Long) {
        if (Build.VERSION.SDK_INT < 31) { if (!tried) { tried = true; L.i("ADPF：系统 API ${Build.VERSION.SDK_INT} < 31，跳过 PerformanceHint") }; return }
        if (!tried) { tried = true; open(target) }
        val s = session as? android.os.PerformanceHintManager.Session ?: return
        try {
            if (target != targetNs && target > 0) { s.updateTargetWorkDuration(target); targetNs = target }
            if (actualNs > 0) s.reportActualWorkDuration(actualNs)
            if (++reports == 1L) L.i("ADPF：首次上报 实际 ${actualNs / 1_000_000}ms / 目标 ${target / 1_000_000}ms")
        } catch (t: Throwable) {
            L.w("ADPF：上报失败，本次运行停用：${t.javaClass.simpleName}: ${t.message}")
            close()
        }
    }

    private fun open(target: Long) {
        if (Build.VERSION.SDK_INT < 31) return
        try {
            val c = ctx() ?: run { L.i("ADPF：没有 Context，跳过"); return }
            val pm = c.getSystemService(android.os.PerformanceHintManager::class.java) ?: run { L.i("ADPF：拿不到 PerformanceHintManager，跳过"); return }
            val tids = LinkedHashSet<Int>()
            tids += android.os.Process.myTid()
            featTid.takeIf { it > 0 }?.let { tids += it }
            File("/proc/self/task").listFiles().orEmpty().forEach { d ->
                val name = runCatching { File(d, "comm").readText().trim() }.getOrNull() ?: return@forEach
                if (name == "engine-start" || name == "live-downshift" || name == "live-feat" || name == "live-syn") d.name.toIntOrNull()?.let { tids += it }
            }
            val arr = tids.take(24).toIntArray()
            val rate = pm.preferredUpdateRateNanos
            val s = pm.createHintSession(arr, target)
            if (s == null) { L.i("ADPF：系统不支持 PerformanceHint（createHintSession 返回 null，preferredRate=$rate），跳过"); return }
            session = s; targetNs = target
            L.i("ADPF：已建 PerformanceHint 会话，目标 ${target / 1_000_000}ms，线程 ${arr.size} 个（uid=${android.os.Process.myUid()}，preferredRate=${rate / 1000}us）")
        } catch (t: Throwable) {
            L.i("ADPF：不可用，跳过（${t.javaClass.simpleName}: ${t.message}）")
            session = null
        }
    }

    fun close() {
        if (Build.VERSION.SDK_INT >= 31) runCatching { (session as? android.os.PerformanceHintManager.Session)?.close() }
        session = null
    }

    /** 下次开始变声重新尝试（线程可能换了） */
    fun reset() { close(); tried = false; reports = 0; targetNs = 0 }
}
