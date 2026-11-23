/*
 * MicMask — GPL-3.0-or-later
 *
 * shell 进程里创建 AudioRecord 的兜底路径。
 *
 * 部分国产 ROM（已知 vivo FuntouchOS/OriginOS）改了 AudioRecord 的构造/Builder，
 * 要求真实 App 环境里的 Context，在 app_process(shell) 里直接 build() 会抛 NPE 或初始化失败。
 * 兜底做法：用私有构造 AudioRecord(long) 造一个空对象，再按原构造的步骤用反射填字段，
 * 最后直接调 JNI 方法 native_setup，跳过 ROM 改过的 Java 层逻辑。
 *
 * 思路参考（未复制代码）：
 *  - scrcpy Workarounds.createAudioRecord（Apache-2.0，https://github.com/Genymobile/scrcpy）
 *  - ShizuCallRecorder ShellWorkarounds（GPL-3.0，https://github.com/kitsumed/ShizuCallRecorder）
 * native_setup 签名随版本变化（AOSP AudioRecord.java）：
 *  - API 30：…, int[] session, String opPackageName, long
 *  - API 31–33：…, int[] session, Parcel attributionSource, long, int maxSharedAudioHistoryMs
 *  - API 34–36：再多一个 int halInputFlags
 *  - API 37：channelMask/channelIndexMask 合并为 AudioFormat.ChannelMasks 对象（未在真机验证）
 * 这里不按 SDK 写死，而是读出本机实际的 native_setup 参数表按类型逐个填，ROM 多/少参数时也能对上或明确报错。
 */
package com.taocrypt.micmask.inject

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import android.os.Looper
import android.os.Parcel
import java.lang.ref.WeakReference

object ShellAudioRecord {

    /** 最近一次创建走的路径：builder / builder-mic / native_setup/apiNN/Np / 失败 */
    @Volatile var lastPath: String = ""
        private set

    class Result(val rec: AudioRecord?, val path: String, val error: String)

    /**
     * 先走常规 Builder；失败（抛异常或未初始化）或 force=true 时走 native_setup 兜底。
     * 返回的 rec 为 null 表示两条路都失败，error 里带两边的原因。
     */
    @Suppress("MissingPermission")
    fun create(source: Int, sampleRate: Int, bufBytes: Int, ctx: Context?, force: Boolean, log: (Char, String) -> Unit): Result {
        var builderErr = ""
        if (!force) {
            val r = runCatching {
                val fmt = AudioFormat.Builder().setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
                val b = AudioRecord.Builder().setAudioSource(source).setAudioFormat(fmt).setBufferSizeInBytes(bufBytes)
                if (Build.VERSION.SDK_INT >= 31 && ctx != null) runCatching { b.setContext(ctx) }
                b.build()
            }
            val rec = r.getOrNull()
            if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) {
                lastPath = "builder"
                return Result(rec, lastPath, "")
            }
            runCatching { rec?.release() }
            builderErr = r.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "state=${rec?.state}"
            log('W', "常规 AudioRecord 失败（src=$source）：$builderErr，改走 native_setup 兜底")
        } else {
            log('I', "已开启「兼容录音」，跳过常规 AudioRecord，直接走 native_setup")
        }
        return try {
            val (rec, path) = viaNativeSetup(source, sampleRate, bufBytes)
            lastPath = path
            log('I', "AudioRecord 兜底成功：$path session=${rec.audioSessionId}")
            Result(rec, path, "")
        } catch (t: Throwable) {
            val e = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            val msg = "${e.javaClass.simpleName}: ${e.message}"
            lastPath = "失败"
            log('E', "native_setup 兜底也失败：$msg")
            Result(null, lastPath, listOf(builderErr.takeIf { it.isNotEmpty() }?.let { "builder=$it" }, "native=$msg").filterNotNull().joinToString("; "))
        }
    }

    private fun field(name: String) = AudioRecord::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun viaNativeSetup(source: Int, sampleRate: Int, bufBytes: Int): Pair<AudioRecord, String> {
        val cls = AudioRecord::class.java
        val rec = cls.getDeclaredConstructor(Long::class.javaPrimitiveType).apply { isAccessible = true }.newInstance(0L)
        field("mRecordingState").setInt(rec, AudioRecord.RECORDSTATE_STOPPED)
        field("mInitializationLooper").set(rec, Looper.myLooper() ?: Looper.getMainLooper())

        val ab = AudioAttributes.Builder()
        AudioAttributes.Builder::class.java.getMethod("setInternalCapturePreset", Int::class.javaPrimitiveType).invoke(ab, source)
        val attrs = ab.build()
        field("mAudioAttributes").set(rec, attrs)

        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val chMask = AudioFormat.CHANNEL_IN_MONO
        cls.getDeclaredMethod("audioParamCheck", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(rec, source, sampleRate, encoding)

        val setup = cls.declaredMethods.filter { it.name == "native_setup" }.maxByOrNull { it.parameterTypes.size }
            ?: throw NoSuchMethodException("native_setup")
        setup.isAccessible = true
        val pt = setup.parameterTypes

        // mChannelMask 在 API 37 起是 ChannelMasks 对象；老版本是 int，另有 mChannelCount
        var channelMasksObj: Any? = null
        val cmType = pt.firstOrNull { !it.isPrimitive && it.name.endsWith("ChannelMasks") }
        if (cmType != null) {
            channelMasksObj = cmType.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.newInstance(chMask, AudioFormat.CHANNEL_INVALID, AudioFormat.CHANNEL_INVALID)
            runCatching { field("mChannelMask").set(rec, channelMasksObj) }
        } else {
            runCatching { field("mChannelCount").setInt(rec, 1) }
            runCatching { field("mChannelMask").setInt(rec, chMask) }
        }
        cls.getDeclaredMethod("audioBuffSizeCheck", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(rec, bufBytes)

        val srArr = intArrayOf(sampleRate)
        val session = intArrayOf(AudioManager.AUDIO_SESSION_ID_GENERATE)
        val fmt = runCatching { rec.audioFormat }.getOrDefault(encoding)
        // session 之前的 int：老版本依次是 channelMask, channelIndexMask, audioFormat, bufSize；ChannelMasks 版本只剩 audioFormat, bufSize
        val preInts = ArrayDeque(if (cmType != null) listOf(fmt, bufBytes) else listOf(chMask, 0, fmt, bufBytes))

        var scoped: AutoCloseable? = null
        try {
            val args = arrayOfNulls<Any>(pt.size)
            var intArrays = 0
            for (i in pt.indices) {
                val t = pt[i]
                args[i] = when {
                    i == 0 -> WeakReference(rec)
                    i == 1 -> attrs
                    t == IntArray::class.java -> if (intArrays++ == 0) srArr else session
                    t == Int::class.javaPrimitiveType -> if (intArrays < 2) (preInts.removeFirstOrNull()
                        ?: throw IllegalStateException("native_setup 参数表对不上：${sig(pt)}")) else 0
                    t == Long::class.javaPrimitiveType -> 0L
                    t == String::class.java -> SHELL_PKG
                    t == Parcel::class.java -> {
                        val st = attributionParcelState()
                        scoped = st
                        st.javaClass.getDeclaredMethod("getParcel").apply { isAccessible = true }.invoke(st) as Parcel
                    }
                    t == cmType -> channelMasksObj
                    t == Boolean::class.javaPrimitiveType -> false
                    else -> throw IllegalStateException("native_setup 有未知参数类型 ${t.name}：${sig(pt)}")
                }
            }
            if (preInts.isNotEmpty()) throw IllegalStateException("native_setup 参数表对不上：${sig(pt)}")
            val r = setup.invoke(rec, *args) as Int
            if (r != AudioRecord.SUCCESS) throw IllegalStateException("native_setup 返回 $r（${sig(pt)}）")
        } finally {
            runCatching { scoped?.close() }
        }
        field("mSampleRate").setInt(rec, srArr[0])
        field("mSessionId").setInt(rec, session[0])
        field("mState").setInt(rec, AudioRecord.STATE_INITIALIZED)
        return rec to "native_setup/api${Build.VERSION.SDK_INT}/${pt.size}p"
    }

    private const val SHELL_PKG = "com.android.shell"

    @android.annotation.SuppressLint("NewApi")
    /** AttributionSource(uid=2000, com.android.shell).asScopedParcelState()，调用方负责 close */
    private fun attributionParcelState(): AutoCloseable {
        val src = android.content.AttributionSource.Builder(android.os.Process.myUid()).setPackageName(SHELL_PKG).build()
        return android.content.AttributionSource::class.java.getDeclaredMethod("asScopedParcelState")
            .apply { isAccessible = true }.invoke(src) as AutoCloseable
    }

    private fun sig(pt: Array<Class<*>>) = pt.joinToString(",", "(", ")") { it.simpleName }
}
