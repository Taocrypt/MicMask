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
 *       com.taocrypt.micmask.audiodiag.ShellDiag <目标uid> <本App包名> <轮次标签> <本App uid>
 * 结果逐行打到 stdout；"DESC|编号|分组｜说明" 与 "RESULT|编号|OK/PART/FAIL/TIMEOUT|说明" 行供 App 汇总。
 *
 * audiodiag-2 新增的 SH 身份、SRVR 式 mix、手建轨道的 setSystemUsage / 性能模式 / 紧凑缓冲变体，
 * 思路来自 SR-Voiceover-Roulette（https://github.com/assdazz/SR-Voiceover-Roulette--，MIT，
 * Copyright (c) 2026 ChiZhou (迟昼)），改写代码集中在 SrvrStyle.kt（文件头附 MIT 许可全文）。
 */
object ShellDiag {
    private val out: (String) -> Unit = { synchronized(this) { println(it); System.out.flush() } }
    /** PKG 身份：本包包上下文 + ShellContext（audiodiag-1 / MicMask 现用） */
    private lateinit var ctxPkg: Context
    /** SH 身份：SRVR 式 com.android.shell 包上下文 + ShellIdentityContext */
    private lateinit var ctxSh: Context
    private var amPkg: AudioManager? = null
    private var amSh: AudioManager? = null
    private var shBaseNote = ""
    private var appUid = -1
    private val logRe = Regex("AudioFlinger|AudioPolicy|APM_|AudioTrack|AudioHardening|audio_hw|AudioSystem|AudioAttributes")

    @JvmStatic
    fun main(args: Array<String>) {
        val targetUid = args.getOrNull(0)?.toIntOrNull() ?: -1
        val pkg = args.getOrNull(1) ?: "com.taocrypt.micmask.audiodiag"
        val label = args.getOrNull(2) ?: "常规"
        appUid = args.getOrNull(3)?.toIntOrNull() ?: -1
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            out("shell 进程崩溃：\n" + stack(e)); out("RESULT|SHELL-CRASH|FAIL|${oneLine(e)}"); System.exit(3)
        }
        out("== shell 进程（$label）uid=${Process.myUid()} pid=${Process.myPid()} 目标uid=$targetUid 本App uid=$appUid ==")
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
            ctxPkg = ShellContext(base)
            val (sh, note) = SrvrStyle.shellIdentityContext(sys)
            ctxSh = sh; shBaseNote = note
            amPkg = newAm(ctxPkg)
            amSh = newAm(ctxSh)
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

    private fun newAm(c: Context): AudioManager? =
        runCatching { AudioManager::class.java.getConstructor(Context::class.java).newInstance(c) }
            .getOrElse { c.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }

    private fun ctxOf(id: String) = if (id == "SH") ctxSh else ctxPkg
    private fun amOf(id: String) = if (id == "SH") amSh else amPkg

    // ------------------------------------------------------------------ 测试项定义

    private class Fmt(val id: String, val name: String, val sr: Int, val mask: Int, val enc: Int)
    private val F48S = Fmt("48s16", "48k 立体声 16bit", 48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
    private val F48M = Fmt("48m16", "48k 单声道 16bit", 48000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    private val F48IM = Fmt("48mi16", "48k IN_MONO 16bit（mix 用输入掩码，轨道换 OUT_MONO）", 48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    private val F16M = Fmt("16m16", "16k 单声道 16bit", 16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    private val F48F = Fmt("48sF", "48k 立体声 float", 48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)

    private val MIX_NAMES = mapOf(
        "UID" to "MIX_ROLE_INJECTOR + RULE_MATCH_UID(目标uid)",
        "PRE5" to "MIX_ROLE_INJECTOR + CAPTURE_PRESET(1,5,6,7,9)",
        "TUID" to "setTargetMixType(MIX_TYPE_RECORDERS) + RULE_MATCH_UID(目标uid)",
        "TPRE5" to "setTargetMixType(MIX_TYPE_RECORDERS) + CAPTURE_PRESET(1,5,6,7,9)",
        "SRVR" to "SRVR 式：MIX_ROLE_INJECTOR + CAPTURE_PRESET(0,1,5,6,7,9,10) + 排除自身/App uid",
    )
    private val ID_NAMES = mapOf("PKG" to "PKG 身份（本包包上下文）", "SH" to "SH 身份（SRVR 式 com.android.shell）")
    private val PATH_NAMES = mapOf(
        "SRC" to "createAudioTrackSource",
        "CTOR" to "手建 AudioTrack 构造函数（无 Context / 无性能模式）",
        "BLD" to "手建 AudioTrack.Builder（不设 Context）",
        "BLDCTX" to "手建 AudioTrack.Builder + setContext",
    )

    /**
     * pol = AudioPolicy.Builder / AudioManager 用的身份；trk = AudioTrack.Builder.setContext 的身份（仅 BLDCTX）；
     * sys = true 反射 setSystemUsage / false 公开 setUsage；perf = null 不设；compact = 紧凑缓冲，否则 minBuf
     */
    private class Spec(
        val group: String, val path: String, val pol: String, val trk: String?, val mix: String,
        val sys: Boolean, val perf: Int?, val compact: Boolean, val fmt: Fmt,
    ) {
        val idTok get() = if (path == "BLDCTX" && trk != null && trk != pol) "${pol}x$trk" else pol
        val id: String get() = if (path == "SRC") "SRC-$idTok-$mix-${fmt.id}" else
            "MAN-$idTok-$mix-$path-${if (sys) "SYS" else "USG"}-${perfTok()}-${if (compact) "CB" else "MIN"}-${fmt.id}"
        fun perfTok() = when (perf) { null -> "DEF"; AudioTrack.PERFORMANCE_MODE_LOW_LATENCY -> "LL"; else -> "NO" }
    }

    private fun title(s: Spec): String {
        val idn = if (s.path == "BLDCTX" && s.trk != null && s.trk != s.pol) "策略 ${ID_NAMES[s.pol]}，轨道 ${ID_NAMES[s.trk]}" else ID_NAMES[s.pol]!!
        val base = "${PATH_NAMES[s.path]}｜$idn｜${MIX_NAMES[s.mix]}｜mix 格式 ${s.fmt.name}"
        if (s.path == "SRC") return base
        val usage = if (s.sys) "setSystemUsage(VIRTUAL_SOURCE)（反射）" else "setUsage(VIRTUAL_SOURCE)（公开 API）"
        val perf = if (s.path == "CTOR") "" else "｜性能模式 ${SrvrStyle.perfName(s.perf)}"
        return "$base｜$usage$perf｜缓冲 ${if (s.compact) "紧凑 max(minBuf, 2×10ms)" else "minBuf"}｜轨道格式取 mix.getFormat()"
    }

    private fun specs(): List<Spec> {
        val LL = AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
        val NO = AudioTrack.PERFORMANCE_MODE_NONE
        val l = ArrayList<Spec>()
        // createAudioTrackSource（兜底路径）：身份 × mix，48k 立体声
        for (id in listOf("PKG", "SH")) for (m in listOf("UID", "PRE5", "SRVR")) l += Spec("A 系统接口", "SRC", id, null, m, false, null, false, F48S)
        l += Spec("A 系统接口", "SRC", "PKG", null, "TUID", false, null, false, F48S)
        l += Spec("A 系统接口", "SRC", "PKG", null, "TPRE5", false, null, false, F48S)
        for (f in listOf(F48M, F16M, F48F)) l += Spec("A 系统接口", "SRC", "SH", null, "SRVR", false, null, false, f)
        // G1 SRVR 复刻：SH 身份（策略 + 轨道 Context），SRVR mix，紧凑缓冲
        for (sys in listOf(true, false)) for (p in listOf(LL, NO)) l += Spec("B SRVR 复刻", "BLDCTX", "SH", "SH", "SRVR", sys, p, true, F48S)
        // G2 同上但用 PKG 身份
        for (sys in listOf(true, false)) for (p in listOf(LL, NO)) l += Spec("C PKG 身份", "BLDCTX", "PKG", "PKG", "SRVR", sys, p, true, F48S)
        // G3 同上但 mix 用 UID 规则（MicMask 现用规则）
        for (sys in listOf(true, false)) for (p in listOf(LL, NO)) l += Spec("D UID 规则", "BLDCTX", "SH", "SH", "UID", sys, p, true, F48S)
        // G4 交叉身份
        l += Spec("E 交叉身份", "BLDCTX", "SH", "PKG", "SRVR", true, LL, true, F48S)
        l += Spec("E 交叉身份", "BLDCTX", "PKG", "SH", "SRVR", true, LL, true, F48S)
        // G5 建轨道路径
        l += Spec("F 建轨路径", "CTOR", "SH", null, "SRVR", true, null, true, F48S)
        l += Spec("F 建轨路径", "CTOR", "SH", null, "SRVR", false, null, true, F48S)
        l += Spec("F 建轨路径", "BLD", "SH", null, "SRVR", true, LL, true, F48S)
        l += Spec("F 建轨路径", "BLD", "SH", null, "SRVR", true, NO, true, F48S)
        // G6 缓冲 / 性能模式
        l += Spec("G 缓冲/性能", "BLDCTX", "SH", "SH", "SRVR", true, LL, false, F48S)
        l += Spec("G 缓冲/性能", "BLDCTX", "SH", "SH", "SRVR", true, NO, false, F48S)
        l += Spec("G 缓冲/性能", "BLDCTX", "SH", "SH", "SRVR", true, null, true, F48S)
        // G7 格式
        for (f in listOf(F48M, F48IM, F16M, F48F)) l += Spec("H 格式", "BLDCTX", "SH", "SH", "SRVR", true, LL, true, f)
        // G8 audiodiag-1 的 MAN 项（PKG 身份 + UID 规则 + minBuf + 不设性能模式），修 usage 后与 setUsage 对照
        for (p in listOf("CTOR", "BLD", "BLDCTX")) for (sys in listOf(true, false))
            l += Spec("I 旧版对照", p, "PKG", if (p == "BLDCTX") "PKG" else null, "UID", sys, null, false, F48S)
        return l
    }

    // ------------------------------------------------------------------ 总流程

    private fun runAll(targetUid: Int) {
        shellHeader()
        // 0) usage 对照：只建 AudioAttributes，看 setSystemUsage / setUsage 哪个被拒
        runItem("ATTR-SYS", "0 usage 对照", "只建 AudioAttributes：反射 setSystemUsage(USAGE_VIRTUAL_SOURCE) + addTag") {
            val a = SrvrStyle.injectionAttributes("addr=diag-probe", true); Res("OK", SrvrStyle.describeAttrs(a))
        }
        runItem("ATTR-USG", "0 usage 对照", "只建 AudioAttributes：公开 setUsage(USAGE_VIRTUAL_SOURCE) + addTag") {
            val a = SrvrStyle.injectionAttributes("addr=diag-probe", false); Res("OK", SrvrStyle.describeAttrs(a))
        }
        // 1) 对照：shell 里普通 USAGE_MEDIA 播放轨道
        runItem("S-MEDIA", "1 对照", "shell 普通 USAGE_MEDIA AudioTrack（Builder，不设 Context）") { mediaTrack(null) }
        runItem("S-MEDIA-PKG", "1 对照", "shell 普通 USAGE_MEDIA AudioTrack（Builder + setContext(PKG 身份)）") { mediaTrack(ctxPkg) }
        runItem("S-MEDIA-SH", "1 对照", "shell 普通 USAGE_MEDIA AudioTrack（Builder + setContext(SH 身份)）") { mediaTrack(ctxSh) }
        // 2) 注入轨道各组合
        for (s in specs()) runItem(s.id, s.group, title(s)) { policyTrack(s, targetUid) }
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
        out("PKG 身份 AttributionSource：" + runCatching { ctxPkg.attributionSource.toString() }.getOrElse { "取不到：${oneLine(it)}" })
        out("SH 身份基底：$shBaseNote")
        out("SH 身份 AttributionSource：" + runCatching { ctxSh.attributionSource.toString() }.getOrElse { "取不到：${oneLine(it)}" })
        out("权限检查（Context.checkPermission，pid/uid=当前进程，PKG 身份）：")
        for (p in listOf("MODIFY_AUDIO_ROUTING", "CAPTURE_AUDIO_OUTPUT", "MODIFY_AUDIO_SETTINGS", "RECORD_AUDIO", "MODIFY_PHONE_STATE")) {
            val r = runCatching { ctxPkg.checkPermission("android.permission.$p", Process.myPid(), Process.myUid()) }.getOrDefault(-99)
            out("  $p = ${if (r == 0) "已授予" else "未授予($r)"}")
        }
        out("AudioManager：PKG=${amPkg?.javaClass?.name ?: "null"} SH=${amSh?.javaClass?.name ?: "null"}")
        out("AudioAttributes.Builder.setSystemUsage：" + (runCatching { AudioAttributes.Builder::class.java.getMethod("setSystemUsage", Integer.TYPE); "存在" }.getOrElse { "不存在（${oneLine(it)}）" }))
        out("AudioMixingRule.Builder.excludeMixRule：" + (runCatching { Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder").getMethod("excludeMixRule", Integer.TYPE, Any::class.java); "存在" }.getOrElse { "不存在（${oneLine(it)}）" }))
        out("-- 音频加固（hardening）相关 --")
        out("cmd audio help 中的 hardening：")
        sh("cmd audio help 2>&1 | grep -i -E 'harden' | head -n 10").ifEmpty { listOf("（无匹配）") }.forEach { out("  $it") }
        out("dumpsys audio 中的 hardening：")
        sh("dumpsys audio 2>&1 | grep -i -E 'harden' | head -n 30").ifEmpty { listOf("（无匹配）") }.forEach { out("  $it") }
    }

    // ------------------------------------------------------------------ 单项执行

    private class Fail(msg: String, cause: Throwable? = null) : Exception(msg, cause)
    private class Res(val status: String, val text: String)

    /** 每项单独线程跑，15 秒没回就记超时继续（防止某次 binder 调用卡死整个诊断） */
    private fun runItem(id: String, group: String, title: String, body: (MutableList<String>) -> Res) {
        out("")
        out("DESC|$id|$group｜$title")
        out("### [$id] $title")
        val start = logTime()
        val lines = java.util.Collections.synchronizedList(ArrayList<String>())
        var result: Res? = null
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
                val r = result!!
                out("  结果：${if (r.status == "OK") "成功" else "部分成功"}｜${r.text}")
                out("RESULT|$id|${r.status}|${r.text.replace('|', '/')}")
                if (r.status != "OK") dumpLogcat(start)
            }
        }
    }

    // ------------------------------------------------------------------ 测试体

    private fun mediaTrack(c: Context?): Res {
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val fmt = AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
        val b = AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(fmt).setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(3840))
        if (c != null) setContext(b, c)
        return exercise(b.build(), AudioFormat.ENCODING_PCM_16BIT, 2)
    }

    /** 建规则 → 建 mix(LOOP_BACK) → 用 pol 身份注册策略 → 按 path 建注入轨道 → 播放/写 20ms 静音 → 释放 → 注销策略 */
    private fun policyTrack(s: Spec, targetUid: Int): Res {
        val I = Integer.TYPE
        val ruleCls = Class.forName("android.media.audiopolicy.AudioMixingRule")
        val ruleB = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
        val mixCls = Class.forName("android.media.audiopolicy.AudioMix")
        val mixB = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
        val polCls = Class.forName("android.media.audiopolicy.AudioPolicy")
        val polB = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
        val log = currentLines()

        // ---- 规则
        val rb = ruleB.getConstructor().newInstance()
        val addMixRule = ruleB.getMethod("addMixRule", I, Any::class.java)
        if (s.mix.startsWith("T")) {
            val m = runCatching { ruleB.getMethod("setTargetMixType", I) }.getOrNull()
                ?: runCatching { ruleB.getDeclaredMethod("setTargetMixType", I).also { it.isAccessible = true } }.getOrNull()
                ?: throw Fail("本系统 AudioMixingRule.Builder 没有 setTargetMixType 方法")
            m.invoke(rb, mixCls.getField("MIX_TYPE_RECORDERS").getInt(null))
        } else {
            ruleB.getMethod("setTargetMixRole", I).invoke(rb, ruleCls.getField("MIX_ROLE_INJECTOR").getInt(null))
        }
        var exclNote: String? = null
        when (s.mix) {
            "UID", "TUID" -> {
                if (targetUid < 0) throw Fail("没有目标 uid")
                addMixRule.invoke(rb, ruleCls.getField("RULE_MATCH_UID").getInt(null), Integer.valueOf(targetUid))
            }
            else -> {
                val preset = ruleCls.getField("RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET").getInt(null)
                val presets = if (s.mix == "SRVR") SrvrStyle.CAPTURE_PRESETS else intArrayOf(1, 5, 6, 7, 9)
                val skipped = ArrayList<Int>()
                for (p in presets) {
                    val r = runCatching {
                        val b = AudioAttributes.Builder()
                        AudioAttributes.Builder::class.java.getMethod("setCapturePreset", I).invoke(b, p)
                        addMixRule.invoke(rb, preset, b.build())
                    }
                    if (r.isFailure) skipped += p
                }
                if (skipped.isNotEmpty()) log.add("这些 CAPTURE_PRESET 加不进规则，已跳过：$skipped")
                if (s.mix == "SRVR") {
                    val uids = listOf(Process.myUid(), appUid).filter { it > 0 }.distinct()
                    exclNote = SrvrStyle.applyUidExclusion(ruleB, rb, ruleCls, uids)
                    log.add("排除 uid：$exclNote")
                }
            }
        }
        val rule = ruleB.getMethod("build").invoke(rb)

        // ---- mix
        val fmt = AudioFormat.Builder().setSampleRate(s.fmt.sr).setChannelMask(s.fmt.mask).setEncoding(s.fmt.enc).build()
        val mb = mixB.getConstructor(ruleCls).newInstance(rule)
        mixB.getMethod("setFormat", AudioFormat::class.java).invoke(mb, fmt)
        mixB.getMethod("setRouteFlags", I).invoke(mb, mixCls.getField("ROUTE_FLAG_LOOP_BACK").getInt(null))
        val mix = mixB.getMethod("build").invoke(mb)
        val mixType = runCatching { mixCls.getMethod("getMixType").invoke(mix) }.getOrNull()
        val mixRole = runCatching { ruleCls.getMethod("getTargetMixRole").invoke(mixCls.getMethod("getRule").invoke(mix)) }.getOrNull()
        val mixFmt = runCatching { mixCls.getMethod("getFormat").invoke(mix) as AudioFormat? }.getOrNull() ?: fmt
        log.add("mix：mixType=$mixType targetMixRole=$mixRole getFormat()=$mixFmt")

        // ---- 策略（pol 身份）
        val pctx = ctxOf(s.pol)
        val pb = polB.getConstructor(Context::class.java).newInstance(pctx)
        polB.getMethod("addMix", mixCls).invoke(pb, mix)
        runCatching { polB.getMethod("setLooper", Looper::class.java).invoke(pb, Looper.getMainLooper()) }
        val pol = polB.getMethod("build").invoke(pb)
        val amgr = amOf(s.pol) ?: throw Fail("AudioManager（${s.pol}）不可用")
        val rc = AudioManager::class.java.getMethod("registerAudioPolicy", polCls).invoke(amgr, pol) as Int
        log.add("registerAudioPolicy（${s.pol} 身份）= $rc")
        if (rc != 0) throw Fail("registerAudioPolicy 返回 $rc")
        try {
            val reg = runCatching { mixCls.getMethod("getRegistration").invoke(mix) as String? }.getOrNull()
            log.add("mix registration = $reg")
            val (track, ch, enc) = when (s.path) {
                "SRC" -> {
                    val t = polCls.getMethod("createAudioTrackSource", mixCls).invoke(pol, mix) as AudioTrack?
                        ?: throw Fail("createAudioTrackSource 返回 null（策略未就绪或权限检查没过，见 logcat）")
                    Triple(t, if (s.fmt.mask == AudioFormat.CHANNEL_OUT_STEREO) 2 else 1, s.fmt.enc)
                }
                else -> {
                    if (reg.isNullOrEmpty()) throw Fail("拿不到 mix registration，无法手建")
                    val attrs = try { SrvrStyle.injectionAttributes(reg, s.sys) } catch (e: Throwable) {
                        val u = unwrap(e)
                        throw Fail("构建 AudioAttributes 失败（${if (s.sys) "反射 setSystemUsage" else "公开 setUsage"} 被拒）：${u.javaClass.name}: ${u.message}", u)
                    }
                    val tf = SrvrStyle.trackFormatFromMix(mixFmt)
                    val minBuf = AudioTrack.getMinBufferSize(tf.sampleRate, tf.channelMask, tf.encoding)
                    val buf = if (s.compact) SrvrStyle.compactBuffer(tf, minBuf) else if (minBuf > 0) minBuf else 3840
                    log.add("attrs：${SrvrStyle.describeAttrs(attrs)}")
                    log.add("轨道格式=$tf minBuf=$minBuf 实际缓冲=$buf 性能模式=${if (s.path == "CTOR") "（构造函数不可设）" else SrvrStyle.perfName(s.perf)}")
                    val t = if (s.path == "CTOR") {
                        @Suppress("DEPRECATION")
                        AudioTrack(attrs, tf, buf, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
                    } else {
                        val b = AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(tf)
                            .setBufferSizeInBytes(buf).setTransferMode(AudioTrack.MODE_STREAM)
                        if (s.perf != null) b.setPerformanceMode(s.perf)
                        if (s.path == "BLDCTX") setContext(b, ctxOf(s.trk ?: s.pol))
                        b.build()
                    }
                    Triple(t, if (tf.channelMask == AudioFormat.CHANNEL_OUT_STEREO) 2 else 1, tf.encoding)
                }
            }
            val r = exercise(track, enc, ch)
            return if (exclNote != null && !exclNote.contains("成功")) Res(r.status, r.text + "（uid 未排除）") else r
        } finally {
            runCatching { AudioManager::class.java.getMethod("unregisterAudioPolicy", polCls).invoke(amgr, pol) }
                .onFailure { log.add("unregisterAudioPolicy 失败：${oneLine(it)}") }
        }
    }

    /**
     * 记录 track state；已初始化就 play + 写 20ms 静音，记 playState / 写入返回值 / 路由，然后释放。
     * OK = 初始化成功 且 write 返回 >0 且 playState=PLAYING；初始化成功但写入/播放异常记 PART。
     */
    private fun exercise(t: AudioTrack, enc: Int, ch: Int): Res {
        val log = currentLines()
        try {
            val st = t.state
            log.add("track state=$st（${if (st == AudioTrack.STATE_INITIALIZED) "INITIALIZED" else "未初始化"}）sr=${t.sampleRate} ch=${t.channelCount} session=${t.audioSessionId} buf=${runCatching { t.bufferSizeInFrames }.getOrNull()} perf=${runCatching { SrvrStyle.perfName(t.performanceMode) }.getOrNull()}")
            runCatching { log.add("track attrs：${SrvrStyle.describeAttrs(t.audioAttributes)}") }
            if (st != AudioTrack.STATE_INITIALIZED) throw Fail("AudioTrack state=$st，未初始化")
            t.play()
            val frames = t.sampleRate / 50
            val w = if (enc == AudioFormat.ENCODING_PCM_FLOAT) t.write(FloatArray(frames * ch), 0, frames * ch, AudioTrack.WRITE_BLOCKING)
                    else t.write(ShortArray(frames * ch), 0, frames * ch)
            Thread.sleep(60)
            val ps = t.playState
            val dev = runCatching { t.routedDevice?.let { "${it.type}/${it.productName}" } }.getOrNull()
            log.add("play 后 playState=$ps write(20ms，期望 ${frames * ch})=$w routed=$dev underruns=${runCatching { t.underrunCount }.getOrNull()}")
            runCatching { t.stop() }
            val text = "state=$st playState=$ps write=$w/${frames * ch}"
            return if (w > 0 && ps == AudioTrack.PLAYSTATE_PLAYING) Res("OK", text) else Res("PART", "初始化成功但写入/播放异常：$text")
        } finally {
            runCatching { t.release() }
        }
    }

    private fun setContext(b: AudioTrack.Builder, c: Context) {
        val m = runCatching { AudioTrack.Builder::class.java.getMethod("setContext", Context::class.java) }.getOrNull()
            ?: throw Fail("本系统 AudioTrack.Builder 没有 setContext（API 34+ 才有）")
        m.invoke(b, c)
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
