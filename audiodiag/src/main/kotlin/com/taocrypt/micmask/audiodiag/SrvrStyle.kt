/*
 * 本文件的思路与部分代码改写自 SR-Voiceover-Roulette（SRVR）项目：
 *   https://github.com/assdazz/SR-Voiceover-Roulette--
 *   app/src/main/java/com/srvr/mobile/ShizukuVirtualMicService.java
 *   （resolvePrivilegedContext、ShellIdentityContext、registerInjectorPolicy / applyUidExclusion、
 *     createManualInjectionTrack；原为 Java，这里改写为 Kotlin 并拆成可参数化的诊断变体）
 *
 * 原项目许可证如下（MIT）：
 *
 * MIT License
 *
 * Copyright (c) 2026 ChiZhou (迟昼)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.taocrypt.micmask.audiodiag

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Process

/** SRVR 式的身份、规则、手建轨道工具 */
object SrvrStyle {
    const val USAGE_VIRTUAL_SOURCE = 15

    /** SRVR 的 CAPTURE_PRESET 列表 */
    val CAPTURE_PRESETS = intArrayOf(
        MediaRecorder.AudioSource.DEFAULT,
        MediaRecorder.AudioSource.MIC,
        MediaRecorder.AudioSource.CAMCORDER,
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        MediaRecorder.AudioSource.UNPROCESSED,
        MediaRecorder.AudioSource.VOICE_PERFORMANCE,
    )

    /**
     * SRVR resolvePrivilegedContext：以 systemContext.createPackageContext("com.android.shell", CONTEXT_IGNORE_SECURITY)
     * 为基底（失败退回 systemContext），外面包一层 [ShellIdentityContext]。
     * 返回 (context, 基底说明)。
     */
    fun shellIdentityContext(sys: Context): Pair<Context, String> {
        val base = runCatching { sys.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY) }
        val note = base.fold({ "createPackageContext(com.android.shell) 成功" }, { "createPackageContext(com.android.shell) 失败，退回 systemContext：${it.javaClass.simpleName}: ${it.message}" })
        return ShellIdentityContext(base.getOrElse { sys }, Process.myUid(), "com.android.shell") to note
    }

    /** SRVR ShellIdentityContext：AttributionSource.Builder(uid).setPackageName(shell)，API 34+ 再 setPid(myPid) */
    class ShellIdentityContext(base: Context, private val uid: Int, private val shellPkg: String) : ContextWrapper(base) {
        override fun getPackageName(): String = shellPkg
        override fun getOpPackageName(): String = shellPkg
        override fun getAttributionSource(): AttributionSource {
            if (Build.VERSION.SDK_INT < 31) return super.getAttributionSource()
            val b = AttributionSource.Builder(uid).setPackageName(shellPkg)
            if (Build.VERSION.SDK_INT >= 34) {
                // 反射调用，避免不同 SDK 存根里可见性不一致
                runCatching { AttributionSource.Builder::class.java.getMethod("setPid", Integer.TYPE).invoke(b, Process.myPid()) }
            }
            return b.build()
        }
        override fun getApplicationContext(): Context = this
    }

    /**
     * SRVR applyUidExclusion：优先 excludeMixRule(RULE_MATCH_UID, uid)，没有就 addMixRule(RULE_EXCLUDE_UID, uid)。
     * 返回说明文字（成功/回退/失败），不抛异常（与 SRVR 一致：排除失败也继续）。
     */
    fun applyUidExclusion(ruleB: Class<*>, rb: Any, ruleCls: Class<*>, uids: List<Int>): String {
        val I = Integer.TYPE
        val matchUid = runCatching { ruleCls.getField("RULE_MATCH_UID").getInt(null) }.getOrDefault(0x4)
        val excludeUid = runCatching { ruleCls.getField("RULE_EXCLUDE_UID").getInt(null) }.getOrDefault(0x8004)
        val ex = runCatching { ruleB.getMethod("excludeMixRule", I, Any::class.java) }.getOrNull()
        if (ex != null) {
            val r = runCatching { uids.forEach { ex.invoke(rb, matchUid, Integer.valueOf(it)) } }
            if (r.isSuccess) return "excludeMixRule(RULE_MATCH_UID, $uids) 成功"
            val e1 = r.exceptionOrNull()!!.let { (it as? java.lang.reflect.InvocationTargetException)?.targetException ?: it }
            // 继续尝试回退
            val add = runCatching { ruleB.getMethod("addMixRule", I, Any::class.java) }.getOrNull()
                ?: return "excludeMixRule 失败（${e1.javaClass.simpleName}: ${e1.message}），且无 addMixRule，未排除"
            val r2 = runCatching { uids.forEach { add.invoke(rb, excludeUid, Integer.valueOf(it)) } }
            return if (r2.isSuccess) "excludeMixRule 失败（${e1.javaClass.simpleName}: ${e1.message}），回退 addMixRule(RULE_EXCLUDE_UID, $uids) 成功"
            else "excludeMixRule 与 addMixRule(RULE_EXCLUDE_UID) 都失败，未排除：${r2.exceptionOrNull()}"
        }
        val add = runCatching { ruleB.getMethod("addMixRule", I, Any::class.java) }.getOrNull() ?: return "无 excludeMixRule / addMixRule，未排除"
        val r2 = runCatching { uids.forEach { add.invoke(rb, excludeUid, Integer.valueOf(it)) } }
        return if (r2.isSuccess) "无 excludeMixRule，addMixRule(RULE_EXCLUDE_UID, $uids) 成功"
        else "addMixRule(RULE_EXCLUDE_UID) 失败，未排除：${r2.exceptionOrNull()}"
    }

    /** SRVR normalizeOutChannelMask：mix 的 IN_MONO 等掩码换成播放端能用的 OUT 掩码 */
    fun normalizeOutMask(mask: Int): Int = when (mask) {
        AudioFormat.CHANNEL_IN_MONO -> AudioFormat.CHANNEL_OUT_MONO
        AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.CHANNEL_OUT_MONO -> mask
        else -> AudioFormat.CHANNEL_OUT_STEREO
    }

    /** 由 mix.getFormat() 推出播放端格式（SRVR 同款：编码/采样率缺省时回退 PCM16/48k） */
    fun trackFormatFromMix(mixFmt: AudioFormat): AudioFormat = AudioFormat.Builder()
        .setEncoding(if (mixFmt.encoding != 0) mixFmt.encoding else AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(if (mixFmt.sampleRate > 0) mixFmt.sampleRate else 48000)
        .setChannelMask(normalizeOutMask(mixFmt.channelMask))
        .build()

    /** 紧凑缓冲：max(minBuf, 2×10ms 帧)（SRVR 为减少 r_submix 起播延迟用的大小） */
    fun compactBuffer(f: AudioFormat, minBuf: Int): Int {
        val ch = if (f.channelMask == AudioFormat.CHANNEL_OUT_STEREO) 2 else 1
        val bps = when (f.encoding) { AudioFormat.ENCODING_PCM_FLOAT -> 4; AudioFormat.ENCODING_PCM_8BIT -> 1; else -> 2 }
        val frame10ms = f.sampleRate / 100 * ch * bps
        return maxOf(minBuf, frame10ms * 2)
    }

    /**
     * 注入轨道的 AudioAttributes：usage = USAGE_VIRTUAL_SOURCE，tag = "addr=<mix registration>"。
     * system=true 时反射 setSystemUsage（SRVR 的 sysUsage 分支），否则用公开 setUsage。
     * 抛出的异常原样上抛，由调用方记录。
     */
    fun injectionAttributes(reg: String, system: Boolean): AudioAttributes {
        val ab = AudioAttributes.Builder()
        if (system) {
            val m = runCatching { AudioAttributes.Builder::class.java.getMethod("setSystemUsage", Integer.TYPE) }.getOrNull()
                ?: runCatching { AudioAttributes.Builder::class.java.getDeclaredMethod("setSystemUsage", Integer.TYPE).also { it.isAccessible = true } }.getOrNull()
                ?: throw IllegalStateException("本系统 AudioAttributes.Builder 没有 setSystemUsage")
            m.invoke(ab, USAGE_VIRTUAL_SOURCE)
        } else {
            ab.setUsage(USAGE_VIRTUAL_SOURCE)
        }
        AudioAttributes.Builder::class.java.getMethod("addTag", String::class.java)
            .invoke(ab, if (reg.startsWith("addr=")) reg else "addr=$reg")
        return ab.build()
    }

    /** 读 AudioAttributes 的 usage / systemUsage，便于报告对照 */
    fun describeAttrs(a: AudioAttributes): String {
        val sys = runCatching { AudioAttributes::class.java.getMethod("getSystemUsage").invoke(a) }.getOrElse { "?" }
        val tags = runCatching { AudioAttributes::class.java.getMethod("getTags").invoke(a) }.getOrElse { "?" }
        return "usage=${a.usage} systemUsage=$sys tags=$tags"
    }

    fun perfName(p: Int?): String = when (p) {
        null -> "不设"
        AudioTrack.PERFORMANCE_MODE_LOW_LATENCY -> "LOW_LATENCY"
        AudioTrack.PERFORMANCE_MODE_NONE -> "NONE"
        AudioTrack.PERFORMANCE_MODE_POWER_SAVING -> "POWER_SAVING"
        else -> "$p"
    }
}
