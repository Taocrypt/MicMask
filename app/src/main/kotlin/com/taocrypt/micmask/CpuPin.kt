package com.taocrypt.micmask

import java.io.File

/**
 * 把 shell 注入进程的线程绑到大核（排除最低频的小核簇）。
 * ORT 的 intra_op_thread_affinities 在 Android 上不生效，Java 也没有 sched_setaffinity，
 * 所以用 toybox 的 `taskset -ap`（shell 进程可以改自己 uid 的线程）。只在 shell（uid 2000）里调用。
 */
object CpuPin {
    @Volatile private var logged = false

    /** 大核编号（按最高频率降序）；没法区分时返回空 */
    fun bigCores(): List<Int> = runCatching {
        val cpus = File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu\\d+")) }.orEmpty()
            .mapNotNull { d ->
                val f = File(d, "cpufreq/cpuinfo_max_freq").takeIf { it.canRead() }?.readText()?.trim()?.toLongOrNull() ?: return@mapNotNull null
                d.name.removePrefix("cpu").toInt() to f
            }
        if (cpus.isEmpty()) return emptyList()
        if (cpus.map { it.second }.distinct().size < 2) return emptyList()
        // 取最高频 70% 以上的核：骁龙 8 Elite/8 Elite Gen 5 是 2 超大核 + 6 大核、没有小核，
        // 旧规则「高于最低频」只剩 2 个核，4 线程挤 2 核反而更慢（PMS110 实测 NEON 声码器 367ms）
        val maxF = cpus.maxOf { it.second }
        val pick = cpus.filter { it.second * 10 >= maxF * 7 }
        if (pick.size == cpus.size) return emptyList()   // 全是性能核：不绑
        pick.sortedByDescending { it.second }.map { it.first }
    }.getOrDefault(emptyList())

    fun pinSelf(why: String) {
        if (android.os.Process.myUid() != 2000) return
        val big = bigCores()
        val status = runCatching { File("/proc/self/status").readLines().firstOrNull { it.startsWith("Cpus_allowed_list") }?.substringAfter(':')?.trim() }.getOrNull()
        val cpuset = runCatching { File("/proc/self/cpuset").readText().trim() }.getOrNull()
        if (big.size < 2) { if (!logged) { logged = true; L.i("绑大核：跳过（无法区分大小核） cpuset=$cpuset 允许=$status") }; return }
        val mask = big.fold(0L) { m, c -> m or (1L shl c) }
        val r = runCatching {
            val p = ProcessBuilder("taskset", "-ap", java.lang.Long.toHexString(mask), android.os.Process.myPid().toString())
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor() to out
        }
        val ok = r.getOrNull()?.first == 0
        val after = runCatching { File("/proc/self/status").readLines().firstOrNull { it.startsWith("Cpus_allowed_list") }?.substringAfter(':')?.trim() }.getOrNull()
        L.i("绑大核（$why）：cpu ${big.sorted().joinToString(",")} ${if (ok) "成功" else "失败 " + (r.exceptionOrNull()?.message ?: r.getOrNull()?.second?.lines()?.lastOrNull { it.isNotBlank() })} cpuset=$cpuset 允许=$status→$after")
    }
}
