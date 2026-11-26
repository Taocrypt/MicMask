package com.taocrypt.micmask.qnntest

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * qnntest-8：档位压测（shell 进程）。按实时节拍模拟 MicMask 的流式管线：
 *  - 每 B ms 来一块：特征线程跑 hubert_dyn8（1.02 s 窗口 = 16320 点）+ rmvpe_q8（128 帧 mel），
 *    合成线程跑对应块长的流式合成器（ORT 整图）；在途 ≥ 2 时丢块（与 LiveRvc 相同）。
 *  - 预缓冲按 App 的规则取 B + 80 ms；端到端超过预缓冲记为「估计断流」。
 * 合成器用 assets/tier 里随机权重的 L35/L30/L25 模型（只计时，不含音色）；
 * 正式 App 的合成走 NEON/NPU 声码器，比这里的 ORT 整图快，所以合成数据偏保守，主要看特征。
 */
object TierStress {
    private val env by lazy { OrtEnvironment.getEnvironment() }
    /** 块长 ms → 合成器帧数（LEN = 块 + 5 帧交叉淡化） */
    val TIERS = listOf(300 to 35, 250 to 30, 200 to 25)

    private fun opts(th: Int) = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT); setIntraOpNumThreads(th); setInterOpNumThreads(1)
    }

    private fun pct(a: List<Long>, p: Double): Long { if (a.isEmpty()) return 0; val s = a.sorted(); return s[minOf(s.size - 1, (s.size * p).toInt())] }

    fun run(modelDir: File, synthDir: File, secs: Int, featTh: Int, synTh: Int, out: (String) -> Unit) {
        Probe.header(out, "shell 进程 · 档位压测")
        val hubF = File(modelDir, "hubert_dyn8.onnx"); val rmF = File(modelDir, "rmvpe_q8.onnx")
        if (!hubF.isFile || !rmF.isFile) { out("缺 hubert_dyn8.onnx / rmvpe_q8.onnx：先点 1 从 MicMask 复制模型"); return }
        out("参数：每档 ${secs}s（另预热 5s） · 特征线程 $featTh · 合成线程 $synTh · 预缓冲 = 块长 + 80ms")
        val hub = env.createSession(hubF.absolutePath, opts(featTh))
        val rm = env.createSession(rmF.absolutePath, opts(featTh))
        val wav = FloatArray(16320) { ((Math.random() - 0.5) * 0.2).toFloat() }
        val mel = FloatArray(128 * 128) { (Math.random().toFloat() - 0.5f) * 4f - 4f }
        fun feat(): Pair<Long, Long> {
            val a = System.nanoTime()
            OnnxTensor.createTensor(env, FloatBuffer.wrap(wav), longArrayOf(1, 16320)).use { t -> hub.run(mapOf(hub.inputNames.first() to t)).close() }
            val b = System.nanoTime()
            OnnxTensor.createTensor(env, FloatBuffer.wrap(mel), longArrayOf(1, 128, 128)).use { t -> rm.run(mapOf(rm.inputNames.first() to t)).close() }
            val c = System.nanoTime()
            return (b - a) / 1_000_000 to (c - b) / 1_000_000
        }
        repeat(3) { feat() }
        val summary = ArrayList<String>()
        for ((block, len) in TIERS) {
            val sf = File(synthDir, "synth_L${len}_qdq.onnx")
            if (!sf.isFile) { out("缺 ${sf.name}，跳过 ${block}ms 档"); continue }
            val syn = env.createSession(sf.absolutePath, opts(synTh))
            val emb = 768
            val phone = OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(100 * emb) { (Math.random().toFloat() - 0.5f) * 0.3f }), longArrayOf(1, 100, emb.toLong()))
            val pitch = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(100) { 120L }), longArrayOf(1, 100))
            val pitchf = OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(100) { 300f }), longArrayOf(1, 100))
            val ins = mapOf("phone" to phone, "pitch" to pitch, "pitchf" to pitchf)
            fun synth(): Long { val a = System.nanoTime(); syn.run(ins).close(); return (System.nanoTime() - a) / 1_000_000 }
            repeat(3) { synth() }
            val pre = block + 80
            out("== ${block}ms 档（合成器 ${len} 帧）开始，预缓冲 ${pre}ms ==")
            val featEx = Executors.newSingleThreadExecutor { r -> Thread(r, "feat") }
            val synEx = Executors.newSingleThreadExecutor { r -> Thread(r, "syn") }
            val tick = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tick") }
            val inflight = AtomicInteger(0)
            val fs = java.util.Collections.synchronizedList(ArrayList<Long>())
            val hs = java.util.Collections.synchronizedList(ArrayList<Long>())
            val rs = java.util.Collections.synchronizedList(ArrayList<Long>())
            val ss = java.util.Collections.synchronizedList(ArrayList<Long>())
            val es = java.util.Collections.synchronizedList(ArrayList<Long>())
            val drops = AtomicInteger(0); val ticks = AtomicInteger(0)
            val t0 = System.nanoTime(); val warm = 5_000_000_000L; val end = t0 + warm + secs * 1_000_000_000L
            var lastReport = t0
            tick.scheduleAtFixedRate({
                val now = System.nanoTime()
                if (now > end) return@scheduleAtFixedRate
                val counted = now - t0 > warm
                if (counted) ticks.incrementAndGet()
                if (inflight.get() >= 2) { if (counted) drops.incrementAndGet(); return@scheduleAtFixedRate }
                inflight.incrementAndGet()
                featEx.execute {
                    val (h, r) = runCatching { feat() }.getOrDefault(-1L to -1L)
                    val f = (System.nanoTime() - now) / 1_000_000
                    synEx.execute {
                        val s = runCatching { synth() }.getOrDefault(-1L)
                        val e = (System.nanoTime() - now) / 1_000_000
                        inflight.decrementAndGet()
                        if (counted) { fs += f; hs += h; rs += r; ss += s; es += e }
                    }
                }
            }, 0, block.toLong(), TimeUnit.MILLISECONDS)
            while (System.nanoTime() < end) {
                Thread.sleep(500)
                val now = System.nanoTime()
                if (now - lastReport > 10_000_000_000L && now - t0 > warm) {
                    lastReport = now
                    val f = synchronized(fs) { fs.toList() }
                    out("  ${(now - t0 - warm) / 1_000_000_000}s：块 ${f.size} · 特征 p50 ${pct(f, .5)} max ${f.maxOrNull() ?: 0}ms · 丢块 ${drops.get()}")
                }
            }
            tick.shutdownNow(); tick.awaitTermination(2, TimeUnit.SECONDS)
            featEx.shutdown(); featEx.awaitTermination(5, TimeUnit.SECONDS); synEx.shutdown(); synEx.awaitTermination(5, TimeUnit.SECONDS)
            val f = fs.toList(); val e = es.toList(); val s = ss.toList()
            val over = f.count { it > block }; val late = e.count { it > pre }
            val n = f.size.coerceAtLeast(1)
            out("  特征（含排队） p50 ${pct(f, .5)} · p95 ${pct(f, .95)} · p99 ${pct(f, .99)} · max ${f.maxOrNull() ?: 0}ms（hubert p50 ${pct(hs.toList(), .5)} · rmvpe p50 ${pct(rs.toList(), .5)}）")
            out("  合成（ORT 整图） p50 ${pct(s, .5)} · p95 ${pct(s, .95)} · max ${s.maxOrNull() ?: 0}ms")
            out("  端到端 p50 ${pct(e, .5)} · p95 ${pct(e, .95)} · max ${e.maxOrNull() ?: 0}ms")
            out("  节拍 ${ticks.get()} · 完成 ${f.size} · 丢块 ${drops.get()} · 特征超块长 $over（${"%.1f".format(over * 100.0 / n)}%）· 端到端超预缓冲 $late（${"%.1f".format(late * 100.0 / n)}%）")
            summary += "TIER|$block|blocks=${f.size}|drop=${drops.get()}|over=${"%.1f".format(over * 100.0 / n)}%|late=${"%.1f".format(late * 100.0 / n)}%|featP95=${pct(f, .95)}|featMax=${f.maxOrNull() ?: 0}|synP50=${pct(s, .5)}|e2eP95=${pct(e, .95)}"
            ins.values.forEach { it.close() }; syn.close()
            System.gc(); Thread.sleep(2000)   // 档与档之间歇一下，减少发热影响
        }
        hub.close(); rm.close()
        out("== qnntest-8 汇总（块长ms｜完成块｜丢块｜特征超块长｜端到端超预缓冲｜特征p95/max｜合成p50｜端到端p95）==")
        summary.forEach(out)
    }
}
