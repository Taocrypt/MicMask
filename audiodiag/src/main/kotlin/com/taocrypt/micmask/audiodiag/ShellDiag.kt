package com.taocrypt.micmask.audiodiag

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Looper
import android.os.Process
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * shell 侧诊断进程（uid 2000），由 App 通过 Shizuku.newProcess 用 app_process 拉起（与 MicMask 自启注入进程同一方式）：
 *   CLASSPATH=<apk> app_process -Djava.class.path=<apk> /system/bin --nice-name=micmask-audiodiag \
 *       com.taocrypt.micmask.audiodiag.ShellDiag <目标uid> <本App包名> <轮次标签>
 * 结果逐行打到 stdout；"RESULT|编号|OK/FAIL/TIMEOUT|说明" 行供 App 汇总。
 */
object ShellDiag {
    private val out: (String) -> Unit = { synchronized(this) { println(it); System.out.flush() } }
    private lateinit var ctx: Context
    private var am: AudioManager? = null
    private const val USAGE_VIRTUAL_SOURCE = 15
    private val logRe = Regex("AudioFlinger|AudioPolicy|APM_|AudioTrack|AudioHardening|audio_hw|AudioSystem")

    @JvmStatic
    fun main(args: Array<String>) {
        val targetUid = args.getOrNull(0)?.toIntOrNull() ?: -1
        val pkg = args.getOrNull(1) ?: "com.taocrypt.micmask.audiodiag"
        val label = args.getOrNull(2) ?: "常规"
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            out("shell 进程崩溃：\n" + stack(e)); out("RESULT|SHELL-CRASH|FAIL|${oneLine(e)}"); System.exit(3)
        }
        out("== shell 进程（$label）uid=${Process.myUid()} pid=${Process.myPid()} 目标uid=$targetUid ==")
        // app_process 默认不限制隐藏 API；保险起见再放开一次
        runCatching {
            val vm = Class.forName("dalvik.system.VMRuntime")
            val rt = vm.getMethod("getRuntime").invoke(null)
            vm.getMethod("setHiddenApiExemptions", Array<String>::class.java).invoke(rt, arrayOf("L"))
        }
        Looper.prepareMainLooper()
        try {
            val at = Class.forName("android.app.ActivityThread")
            val thread = at.getMethod("systemMain").invoke(null)
            val sys = at.getMethod("getSystemContext").invoke(thread) as Context
            val base = runCatching { sys.createPackageContext(pkg, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY) }.getOrElse { sys }
            ctx = ShellContext(base)
            am = runCatching { AudioManager::class.java.getConstructor(Context::class.java).newInstance(ctx) }
                .getOrElse { ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
        } catch (t: Throwable) {
            out("拿不到系统 Context：\n" + stack(t)); out("RESULT|SHELL-CTX|FAIL|${oneLine(t)}"); System.exit(2)
        }
        Thread({
            try { runAll(targetUid) } catch (t: Throwable) { out("诊断线程异常：\n" + stack(t)) }
            out("== shell 进程（$label）结束 ==")
            System.exit(0)
        }, "audiodiag").start()
        Looper.loop()
    }

    // ------------------------------------------------------------------ 总流程

    private class Fmt(val id: String, val name: String, val sr: Int, val mask: Int, val enc: Int)
    private val FORMATS = listOf(
        Fmt("48s16", "48k 立体声 16bit", 48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT),
        Fmt("48m16", "48k 单声道 16bit", 48000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT),
        Fmt("16m16", "16k 单声道 16bit", 16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT),
        Fmt("48sF", "48k 立体声 float", 48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT),
    )

    private fun runAll(targetUid: Int) {
        shellHeader()
        // 1) 对照：shell 里普通 USAGE_MEDIA 播放轨道
        runItem("S-MEDIA", "对照：shell 普通 USAGE_MEDIA AudioTrack（Builder，不设 Context）") { mediaTrack(false) }
        runItem("S-MEDIA-CTX", "对照：shell 普通 USAGE_MEDIA AudioTrack（Builder + setContext(shell 身份)）") { mediaTrack(true) }
        // 2) createAudioTrackSource：规则 × 匹配 × 格式
        for (role in listOf("ROLE", "TYPE")) for (match in listOf("UID", "PRESET")) for (f in FORMATS) {
            val roleName = if (role == "ROLE") "setTargetMixRole(MIX_ROLE_INJECTOR)" else "setTargetMixType(MIX_TYPE_RECORDERS)"
            val matchName = if (match == "UID") "按 UID($targetUid)" else "按录音源 CAPTURE_PRESET(1,5,6,7,9)"
            runItem("SRC-$role-$match-${f.id}", "createAudioTrackSource｜$roleName｜$matchName｜${f.name}") {
                policyTrack(role, match, targetUid, f, "SRC")
            }
        }
        // 3) 绕开 createAudioTrackSource：按 AOSP 实现手建（USAGE_VIRTUAL_SOURCE + addTag("addr=<registration>")）
        val paths = listOf(
            "CTOR" to "手建：AudioTrack(attrs, fmt, minBuf, MODE_STREAM, SESSION_GENERATE)（同 AOSP 主线实现，不带 Context）",
            "BLD" to "手建：AudioTrack.Builder（不设 Context）",
            "BLDCTX" to "手建：AudioTrack.Builder + setContext(shell 身份)",
        )
        for ((p, pn) in paths) for (f in FORMATS) {
            runItem("MAN-$p-${f.id}", "$pn｜MIX_ROLE_INJECTOR｜按 UID($targetUid)｜${f.name}") {
                policyTrack("ROLE", "UID", targetUid, f, p)
            }
        }
    }

    private fun shellHeader() {
        out("-- shell 环境 --")
        out("Build：${Build.MANUFACTURER} ${Build.MODEL}（${Build.DEVICE}）Android ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}")
        for (k in listOf("ro.product.model", "ro.product.marketname", "ro.soc.manufacturer", "ro.soc.model", "ro.board.platform",
            "ro.build.version.release", "ro.build.version.sdk", "ro.build.fingerprint", "ro.build.display.id",
            "ro.build.version.ota", "ro.build.version.oplusrom", "ro.build.version.security_patch")) {
            out("  $k = ${sh("getprop $k").joinToString(" ").trim()}")
        }
        out("myAttributionSource()：" + runCatching { AttributionSource::class.java.getMethod("myAttributionSource").invoke(null).toString() }.getOrElse { "取不到：${oneLine(it)}" })
        out("shell Context AttributionSource：" + runCatching { ctx.attributionSource.toString() }.getOrElse { "取不到：${oneLine(it)}" })
        out("权限检查（Context.checkPermission，pid/uid=当前进程）：")
        for (p in listOf("MODIFY_AUDIO_ROUTING", "CAPTURE_AUDIO_OUTPUT", "MODIFY_AUDIO_SETTINGS", "RECORD_AUDIO", "MODIFY_PHONE_STATE")) {
            val r = runCatching { ctx.checkPermission("android.permission.$p", Process.myPid(), Process.myUid()) }.getOrDefault(-99)
            out("  $p = ${if (r == 0) "已授予" else "未授予($r)"}")
        }
        out("AudioManager：${am?.javaClass?.name ?: "null"}")
        out("-- 音频加固（hardening）相关 --")
        out("cmd audio help 中的 hardening：")
        sh("cmd audio help 2>&1 | grep -i -E 'harden' | head -n 10").ifEmpty { listOf("（无匹配）") }.forEach { out("  $it") }
        out("dumpsys audio 中的 hardening：")
        sh("dumpsys audio 2>&1 | grep -i -E 'harden' | head -n 30").ifEmpty { listOf("（无匹配）") }.forEach { out("  $it") }
    }

    // ------------------------------------------------------------------ 单项执行

    private class Fail(msg: String) : Exception(msg)

    /** 每项单独线程跑，15 秒没回就记超时继续（防止某次 binder 调用卡死整个诊断） */
    private fun runItem(id: String, title: String, body: (MutableList<String>) -> String) {
        out("")
        out("### [$id] $title")
        val start = logTime()
        val lines = java.util.Collections.synchronizedList(ArrayList<String>())
        var result: String? = null
        var err: Throwable? = null
        val t = Thread({
            tl.set(lines)
            try { result = body(lines) } catch (e: Throwable) { err = unwrap(e) }
        }, "item-$id")
        t.isDaemon = true
        t.start(); t.join(15_000)
        synchronized(lines) { lines.forEach { out("  $it") } }
        when {
            t.isAlive -> {
                out("  结果：超时（15 秒无返回，线程仍卡住）")
                out("RESULT|$id|TIMEOUT|15 秒无返回")
                dumpLogcat(start)
            }
            err != null -> {
                val e = err!!
                out("  结果：失败")
                out("  异常全文：")
                stack(e).lines().forEach { out("    $it") }
                out("RESULT|$id|FAIL|${oneLine(e)}")
                dumpLogcat(start)
            }
            else -> {
                out("  结果：成功｜$result")
                out("RESULT|$id|OK|$result")
            }
        }
    }

    // ------------------------------------------------------------------ 测试体

    private fun mediaTrack(withCtx: Boolean): String {
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val fmt = AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
        val b = AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(fmt).setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(3840))
        if (withCtx) setContext(b)
        return exercise(b.build(), AudioFormat.ENCODING_PCM_16BIT, 2)
    }

    /**
     * 建规则 → 建 mix(LOOP_BACK) → 注册策略 → 按 path 建注入轨道 → 播放/写 20ms 静音 → 释放 → 注销策略
     * path：SRC = AudioPolicy.createAudioTrackSource；CTOR/BLD/BLDCTX = 手建
     */
    private fun policyTrack(role: String, match: String, targetUid: Int, f: Fmt, path: String, ): String {
        val I = Integer.TYPE
        val ruleCls = Class.forName("android.media.audiopolicy.AudioMixingRule")
        val ruleB = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
        val mixCls = Class.forName("android.media.audiopolicy.AudioMix")
        val mixB = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
        val polCls = Class.forName("android.media.audiopolicy.AudioPolicy")
        val polB = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
        val log = currentLines()

        val rb = ruleB.getConstructor().newInstance()
        if (role == "ROLE") {
            ruleB.getMethod("setTargetMixRole", I).invoke(rb, ruleCls.getField("MIX_ROLE_INJECTOR").getInt(null))
        } else {
            val m = runCatching { ruleB.getMethod("setTargetMixType", I) }.getOrNull()
                ?: runCatching { ruleB.getDeclaredMethod("setTargetMixType", I).also { it.isAccessible = true } }.getOrNull()
                ?: throw Fail("本系统 AudioMixingRule.Builder 没有 setTargetMixType 方法")
            m.invoke(rb, mixCls.getField("MIX_TYPE_RECORDERS").getInt(null))
        }
        if (match == "UID") {
            if (targetUid < 0) throw Fail("没有目标 uid")
            ruleB.getMethod("addMixRule", I, Any::class.java).invoke(rb, ruleCls.getField("RULE_MATCH_UID").getInt(null), Integer.valueOf(targetUid))
        } else {
            val preset = ruleCls.getField("RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET").getInt(null)
            val aab = AudioAttributes.Builder::class.java
            for (p in intArrayOf(1, 5, 6, 7, 9)) {
                val b = AudioAttributes.Builder()
                aab.getMethod("setCapturePreset", I).invoke(b, p)
                ruleB.getMethod("addMixRule", I, Any::class.java).invoke(rb, preset, b.build())
            }
        }
        val rule = ruleB.getMethod("build").invoke(rb)
        val fmt = AudioFormat.Builder().setSampleRate(f.sr).setChannelMask(f.mask).setEncoding(f.enc).build()
        val mb = mixB.getConstructor(ruleCls).newInstance(rule)
        mixB.getMethod("setRouteFlags", I).invoke(mb, mixCls.getField("ROUTE_FLAG_LOOP_BACK").getInt(null))
        mixB.getMethod("setFormat", AudioFormat::class.java).invoke(mb, fmt)
        val mix = mixB.getMethod("build").invoke(mb)
        val mixType = runCatching { mixCls.getMethod("getMixType").invoke(mix) }.getOrNull()
        val mixRole = runCatching { ruleCls.getMethod("getTargetMixRole").invoke(mixCls.getMethod("getRule").invoke(mix)) }.getOrNull()
        log.add("mix：mixType=$mixType targetMixRole=$mixRole format=$fmt")

        val pb = polB.getConstructor(Context::class.java).newInstance(ctx)
        polB.getMethod("addMix", mixCls).invoke(pb, mix)
        val pol = polB.getMethod("build").invoke(pb)
        val amgr = am ?: throw Fail("AudioManager 不可用")
        val rc = AudioManager::class.java.getMethod("registerAudioPolicy", polCls).invoke(amgr, pol) as Int
        log.add("registerAudioPolicy = $rc")
        if (rc != 0) throw Fail("registerAudioPolicy 返回 $rc")
        try {
            val reg = runCatching { mixCls.getMethod("getRegistration").invoke(mix) as String? }.getOrNull()
            log.add("mix registration = $reg")
            val track: AudioTrack? = when (path) {
                "SRC" -> polCls.getMethod("createAudioTrackSource", mixCls).invoke(pol, mix) as AudioTrack?
                    ?: throw Fail("createAudioTrackSource 返回 null（策略未就绪或权限检查没过，见 logcat）")
                else -> {
                    if (reg.isNullOrEmpty()) throw Fail("拿不到 mix registration，无法手建")
                    val ab = AudioAttributes.Builder().setUsage(USAGE_VIRTUAL_SOURCE)
                    AudioAttributes.Builder::class.java.getMethod("addTag", String::class.java).invoke(ab, "addr=$reg")
                    val attrs = ab.build()
                    val minBuf = AudioTrack.getMinBufferSize(f.sr, f.mask, f.enc)
                    log.add("attrs=$attrs minBuf=$minBuf")
                    if (path == "CTOR") {
                        @Suppress("DEPRECATION")
                        AudioTrack(attrs, fmt, if (minBuf > 0) minBuf else 3840, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
                    } else {
                        val b = AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(fmt).setTransferMode(AudioTrack.MODE_STREAM)
                        if (minBuf > 0) b.setBufferSizeInBytes(minBuf)
                        if (path == "BLDCTX") setContext(b)
                        b.build()
                    }
                }
            }
            return exercise(track!!, f.enc, if (f.mask == AudioFormat.CHANNEL_OUT_STEREO) 2 else 1)
        } finally {
            runCatching { AudioManager::class.java.getMethod("unregisterAudioPolicy", polCls).invoke(amgr, pol) }
                .onFailure { log.add("unregisterAudioPolicy 失败：${oneLine(it)}") }
        }
    }

    /** 记录 track state；已初始化就 play + 写 20ms 静音，记 playState / 写入返回值 / 路由，然后释放 */
    private fun exercise(t: AudioTrack, enc: Int, ch: Int): String {
        val log = currentLines()
        try {
            val st = t.state
            log.add("track state=$st（${if (st == AudioTrack.STATE_INITIALIZED) "INITIALIZED" else "未初始化"}）sr=${t.sampleRate} ch=${t.channelCount} session=${t.audioSessionId} buf=${runCatching { t.bufferSizeInFrames }.getOrNull()}")
            if (st != AudioTrack.STATE_INITIALIZED) throw Fail("AudioTrack state=$st，未初始化")
            t.play()
            val frames = t.sampleRate / 50
            val w = if (enc == AudioFormat.ENCODING_PCM_FLOAT) t.write(FloatArray(frames * ch), 0, frames * ch, AudioTrack.WRITE_BLOCKING)
                    else t.write(ShortArray(frames * ch), 0, frames * ch)
            Thread.sleep(60)
            val ps = t.playState
            val dev = runCatching { t.routedDevice?.let { "${it.type}/${it.productName}" } }.getOrNull()
            log.add("play 后 playState=$ps write(20ms)=$w routed=$dev underruns=${runCatching { t.underrunCount }.getOrNull()}")
            runCatching { t.stop() }
            return "state=$st playState=$ps write=$w"
        } finally {
            runCatching { t.release() }
        }
    }

    private fun setContext(b: AudioTrack.Builder) {
        val m = runCatching { AudioTrack.Builder::class.java.getMethod("setContext", Context::class.java) }.getOrNull()
            ?: throw Fail("本系统 AudioTrack.Builder 没有 setContext（API 34+ 才有）")
        m.invoke(b, ctx)
    }

    // 当前测试项的附加行（每项一个线程，用 ThreadLocal 传）
    private val tl = ThreadLocal<MutableList<String>>()
    private fun currentLines(): MutableList<String> = tl.get() ?: ArrayList<String>().also { tl.set(it) }

    // ------------------------------------------------------------------ logcat / shell 工具

    private fun logTime(): String = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(System.currentTimeMillis() - 500))

    /** 本项开始后的相关 logcat 行；时间窗内没有就退回最后 30 行相关日志 */
    private fun dumpLogcat(start: String) {
        Thread.sleep(300)
        val all = sh("logcat -d -v threadtime -t 2000").filter { logRe.containsMatchIn(it) }
        val win = all.filter { it.length >= 18 && it.substring(0, 18) >= start }
        val pick = if (win.isNotEmpty()) win.takeLast(80) else all.takeLast(30)
        out("  相关 logcat（${if (win.isNotEmpty()) "本项开始后 ${win.size} 行，显示最后 ${pick.size} 行" else "本项时间窗内无匹配，显示最近 ${pick.size} 行"}）：")
        if (pick.isEmpty()) out("    （无匹配行；若全为空，可能 logcat 读不到）")
        pick.forEach { out("    $it") }
    }

    private fun sh(cmd: String): List<String> = runCatching {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val r = p.inputStream.bufferedReader().readLines(); p.waitFor(); r
    }.getOrElse { listOf("（$cmd 失败：${oneLine(it)}）") }

    private fun unwrap(e: Throwable): Throwable =
        if (e is java.lang.reflect.InvocationTargetException && e.targetException != null) unwrap(e.targetException) else e

    private fun stack(e: Throwable): String {
        val sw = java.io.StringWriter(); e.printStackTrace(java.io.PrintWriter(sw)); return sw.toString().trimEnd()
    }

    private fun oneLine(e: Throwable): String = "${e.javaClass.name}: ${e.message ?: ""}".replace('\n', ' ').replace('|', '/')

    /** 包名伪装成 com.android.shell，和 uid 2000 对得上（同 MicMask InjectorService.ShellContext） */
    private class ShellContext(base: Context) : ContextWrapper(base) {
        private val isShell = Process.myUid() == 2000
        override fun getPackageName(): String = if (isShell) "com.android.shell" else super.getPackageName()
        override fun getOpPackageName(): String = if (isShell) "com.android.shell" else super.getOpPackageName()
        override fun getAttributionSource(): AttributionSource {
            if (!isShell || Build.VERSION.SDK_INT < 31) return super.getAttributionSource()
            return AttributionSource.Builder(Process.myUid()).setPackageName("com.android.shell").build()
        }
        override fun getApplicationContext(): Context = this
    }
}
