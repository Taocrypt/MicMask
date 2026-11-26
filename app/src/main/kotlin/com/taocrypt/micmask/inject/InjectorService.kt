package com.taocrypt.micmask.inject

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Binder
import android.os.Build
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import java.io.FileInputStream

/**
 * 运行在 **Shizuku 拉起的 shell(uid 2000) 进程**里的麦克风注入服务。
 *
 * 原理（与 GameMicLab / MeKit 逆向结论一致）：
 *  1. AudioMixingRule: setTargetMixRole(MIX_ROLE_INJECTOR) + addMixRule(RULE_MATCH_UID, 目标 uid)
 *  2. AudioMix: ROUTE_FLAG_LOOP_BACK + 48k/PCM16/立体声
 *  3. AudioManager.registerAudioPolicy(policy)   —— 需要 MODIFY_AUDIO_ROUTING，shell 自带
 *  4. AudioPolicy.createAudioTrackSource(mix)    —— 往这个 AudioTrack 写的 PCM 就是目标 App 录到的声音
 *
 * 交互：App 先 LOAD 一段 48k 立体声 PCM16，再 ARM。ARM 后目标 App 的麦克风被接管（只听得到我们写的内容）；
 * 检测到目标 App 开始录音（getActiveRecordingConfigurations 里出现它的 uid）就开始放这段语音，
 * 放完继续写静音，直到目标 App 停止录音或超时，然后自动注销策略、把真麦克风还回去。
 *
 * 全部用反射：这些都是 @SystemApi，不在公开 android.jar 里。
 */
class InjectorService : Binder {

    private val ctx: Context?

    constructor() : super() { ctx = null }
    constructor(context: Context) : super() { ctx = context }

    private val fx = FxChain(SR)
    @Volatile private var fxLast: FloatArray? = null
    private val fxBuf = FloatArray(SR / 100)
    @Volatile private var sfx: ShortArray? = null
    @Volatile private var sfxPos = 0
    @Volatile private var sfxGain = 1f
    @Volatile private var sfxLoop = false
    /** 语音包混音：在效果链之后叠加（音乐/音效不吃变声效果），软限幅防爆 */
    private fun mixSfx(buf: FloatArray, n: Int) {
        val a = sfx ?: return
        var p = sfxPos
        val g = sfxGain / 32768f
        for (i in 0 until n) {
            if (p >= a.size) { if (sfxLoop) p = 0 else { sfx = null; break } }
            val y = buf[i] + a[p++] * g
            buf[i] = if (y > 0.95f) 0.95f + (y - 0.95f) / (1f + (y - 0.95f) * 20f) else if (y < -0.95f) -0.95f + (y + 0.95f) / (1f - (y + 0.95f) * 20f) else y
        }
        sfxPos = p
    }

    companion object {
        const val TAG = "MicMaskInjector"
        const val TX_PING = 1
        const val TX_LOAD = 2
        const val TX_ARM = 3
        const val TX_PLAY_NOW = 4
        const val TX_DISARM = 5
        const val TX_STATUS = 6
        /** 实时变声：uids[], semitones(float), source(int) */
        const val TX_LIVE_START = 8
        const val TX_LIVE_SET = 9
        /** 外部处理模式：App 拉真麦 PCM（48k mono s16le）/ 推回处理后的 PCM */
        const val TX_LIVE_PULL = 10
        const val TX_LIVE_PUSH = 11
        /** 取 shell 侧日志：sinceSeq(long) → nextSeq(long), lines(String[]) */
        const val TX_LOG_DUMP = 12
        /** 传模型：int n，n × (String name, FileDescriptor)；shell 侧 mmap，不拷贝 */
        const val TX_LIVE_MODELS = 13
        /** 性能测试：重新测速定档，返回摘要（阻塞，约 10~20 s） */
        const val TX_LIVE_BENCH = 14
        /** Shizuku IUserService.destroy 的事务码 */
        /** 耳返/实时试听：把变声后的声音同时放到本机耳机（int on） */
        const val TX_LIVE_MONITOR = 17
        /** 录制预览：录 N 秒变声结果（int sec），录完在本机回放 */
        const val TX_LIVE_CAPTURE = 18
        /** 效果链参数：int n, n×float（FxChain.P_*） */
        const val TX_LIVE_FX = 19
        /** 性能信息：返回 cpu%(float) cpuTemp(float, -1=未知) selfRssMb(int) underruns(long) */
        const val TX_SYS_STAT = 20
        /** 语音包：fd(48k mono s16le) + gain + loop；22 停止 */
        const val TX_SFX_PLAY = 21
        const val TX_SFX_STOP = 22
        /** 输出状态：距上次有人声输出的毫秒（-1=从未），输出缓冲毫秒 */
        const val TX_OUT_STATE = 23
        /** 实时 AI 高级参数：tier(int) gateDb(float) featThreads(int) synThreads(int) */
        const val TX_LIVE_TUNE = 24
        /** 变声中换音色：新合成器已由 TX_LIVE_MODELS 送到，重启推理（麦克风通道不断，换的这一两秒输出静音） */
        const val TX_LIVE_RELOAD = 25
        /** TX_LIVE_START 的 source 高位：强制兼容录音 */
        const val REC_COMPAT_FLAG = 0x10000
        const val TX_DESTROY = 16777115

        /** AudioAttributes.USAGE_VIRTUAL_SOURCE（隐藏常量） */
        const val USAGE_VIRTUAL_SOURCE = 15
        const val SR = 48000
        const val FRAME_BYTES = 4 // 立体声 16bit
        /** 每次写 10ms */
        const val CHUNK_BYTES = SR / 100 * FRAME_BYTES
        /** 目标开始录音后，先垫一小段静音再放（QQ 开头常会丢一点） */
        const val LEAD_SILENCE_MS = 150
    }

    // ------------------------------------------------------------ 状态

    @Volatile private var pcm: ByteArray = ByteArray(0)
    @Volatile private var policy: Any? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var running = false
    @Volatile private var targetUids = IntArray(0)
    @Volatile private var baseRecCount = 0
    @Volatile private var trigger = ""
    @Volatile private var autoStart = true
    @Volatile private var timeoutMs = 60_000L
    @Volatile private var playPos = -1          // -1 = 未开始；>=0 = 正在放；== size = 放完
    @Volatile private var phase = "IDLE"        // IDLE / WAITING / PLAYING / TAIL
    @Volatile private var lastError = ""
    @Volatile private var targetSeenRecording = false
    /** 自动识别范围：不按 uid，按录音源接管所有应用（排除 shell 和 MicMask 自己） */
    @Volatile private var scopeAll = false
    /** 自动范围但系统不支持排除规则：shell 自己改用 VOICE_PERFORMANCE 录音，避免录到自己注入的声音 */
    @Volatile private var autoNoExclude = false
    @Volatile private var callingUidNow = -1
    @Volatile private var callerUid = -1

    private val shellCtx: Context? by lazy { ctx?.let { ShellContext(it) } }
    private val am: AudioManager? by lazy {
        val c = shellCtx ?: return@lazy null
        // 用 shell 包名的 Context 构造 AudioManager，避免带着 com.taocrypt.micmask 的包名去和 uid 2000 对不上
        runCatching {
            AudioManager::class.java.getConstructor(Context::class.java).newInstance(c)
        }.getOrElse { c.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    }

    // ------------------------------------------------------------ Binder

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        // ⚠️ 必须清掉调用方身份：否则 AudioPolicy 内部 checkCallingOrSelfPermission 查的是
        // App 的 uid（没有 MODIFY_AUDIO_ROUTING），createAudioTrackSource 会直接返回 null。
        callingUidNow = Binder.getCallingUid()
        val ident = Binder.clearCallingIdentity()
        try { return transactInner(code, data, reply, flags) } finally { Binder.restoreCallingIdentity(ident) }
    }

    private fun transactInner(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        callerUid = callingUidNow
        try {
            when (code) {
                TX_DESTROY -> {
                    disarm("destroy")
                    System.exit(0)
                }
                TX_PING -> {
                    if (Process.myUid() == 0 && !rootProbed) { rootProbed = true; Thread { rootProbe { slog('I', it) } }.start() }
                    reply?.writeNoException()
                    reply?.writeInt(Process.myUid())
                    reply?.writeInt(if (hasRoutingPermission()) 1 else 0)
                    return true
                }
                TX_LOAD -> {
                    val pfd = data.readFileDescriptor()
                    val bytes = pfd?.let { readAll(it) } ?: ByteArray(0)
                    pcm = bytes.copyOf(bytes.size - bytes.size % FRAME_BYTES)
                    slog('I', "LOAD ${pcm.size} bytes (${pcm.size / FRAME_BYTES * 1000L / SR} ms)")
                    reply?.writeNoException()
                    reply?.writeInt(pcm.size)
                    return true
                }
                TX_ARM -> {
                    val n = data.readInt().coerceIn(0, 16)
                    val uids = IntArray(n) { data.readInt() }
                    val auto = data.readInt() != 0
                    val tmo = data.readLong()
                    val err = arm(uids, auto, tmo)
                    reply?.writeNoException()
                    reply?.writeString(err)
                    return true
                }
                TX_PLAY_NOW -> {
                    if (running) startPlayback("manual")
                    reply?.writeNoException()
                    reply?.writeInt(if (running) 1 else 0)
                    return true
                }
                TX_DISARM -> {
                    disarm("client")
                    reply?.writeNoException()
                    return true
                }
                TX_LIVE_START -> {
                    val n = data.readInt().coerceIn(0, 16)
                    val uids = IntArray(n) { data.readInt() }
                    liveSemis = data.readFloat()
                    val srcRaw = data.readInt()
                    liveSource = srcRaw and 0xffff
                    recCompat = (srcRaw and REC_COMPAT_FLAG) != 0
                    liveExternal = data.dataAvail() >= 4 && data.readInt() == 1
                    livePrimeMs = maxOf(if (data.dataAvail() >= 4) data.readInt().coerceIn(20, 1500) else 100, tierPrimeMs)
                    val rvcHere = data.dataAvail() >= 4 && data.readInt() == 1
                    if (data.dataAvail() >= 16) readTune(data)
                    micRing.clear(); outRing.clear()
                    val err = arm(uids, auto = false, tmo = 0L, live = true)
                    if (err == null && liveExternal && rvcHere) startEngine()
                    reply?.writeNoException()
                    reply?.writeString(err)
                    return true
                }
                TX_LIVE_PULL -> {
                    val max = data.readInt().coerceIn(0, SR * 2)
                    val tmp = ShortArray(max)
                    val k = micRing.read(tmp, 0, max)
                    val bytes = ByteArray(k * 2)
                    for (i in 0 until k) { val v = tmp[i].toInt(); bytes[2 * i] = v.toByte(); bytes[2 * i + 1] = (v shr 8).toByte() }
                    reply?.writeNoException()
                    reply?.writeByteArray(bytes)
                    return true
                }
                TX_LIVE_PUSH -> {
                    val bytes = data.createByteArray() ?: ByteArray(0)
                    val k = bytes.size / 2
                    val tmp = ShortArray(k) { i -> ((bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)).toShort() }
                    outRing.write(tmp, 0, k)
                    reply?.writeNoException()
                    return true
                }
                TX_LIVE_MODELS -> {
                    val n = data.readInt().coerceIn(0, 16)
                    val got = ArrayList<String>()
                    // 换音色时合成器整套换掉：上一个音色多出来的档位不能留着参与测速
                    val oldSynth = models.filterKeys { it.contains("_stream") }.mapValues { it.value.absolutePath }
                    models.keys.filter { it.contains("_stream") }.forEach { models.remove(it) }
                    repeat(n) {
                        val name = data.readString() ?: ""
                        val pfd = data.readFileDescriptor()
                        if (pfd != null && name.isNotEmpty()) {
                            // shell 读不了 App 私有目录（/proc/self/fd 重开也被 SELinux 拒），拷到 shell 自己的目录
                            pfd.use { p ->
                                val size = p.statSize
                                val ch = FileInputStream(p.fileDescriptor).channel
                                // 合成器按内容分目录存（s_<抽样校验>），每个音色一份、修改时间不变：
                                // .ort 缓存和测速结果都能跨音色复用（之前所有音色共用一个文件名，切一次就全部失效重建）
                                val dir = if (name.contains("_stream")) java.io.File(MODEL_DIR, "s_" + java.lang.Long.toHexString(sampleCrc(ch, size)) + "_" + size) else java.io.File(MODEL_DIR)
                                val dst = java.io.File(dir, name)
                                val same = dst.length() == size && (name.contains("_stream") ||
                                    sampleCrc(ch, size) == dst.takeIf { it.exists() }?.let { f -> java.io.RandomAccessFile(f, "r").use { sampleCrc(it.channel, size) } })
                                if (!same) {
                                    dir.mkdirs()
                                    val t0 = System.currentTimeMillis()
                                    val tmpF = java.io.File(dir, "$name.part")
                                    ch.position(0)
                                    FileInputStream(p.fileDescriptor).use { i -> java.io.FileOutputStream(tmpF).use { o -> i.channel.position(0); i.copyTo(o, 1 shl 20) } }
                                    if (!tmpF.renameTo(dst)) throw java.io.IOException("rename $name")
                                    slog('I', "拷贝模型 $name ${size shr 20}MB 用时 ${System.currentTimeMillis() - t0}ms")
                                    // 基础模型（HuBERT/RMVPE）内容变了才整个重建引擎
                                    if (!name.contains("_stream") && name in loadedNames) engine?.let { e -> engine = null; Thread { e.release() }.start() }
                                }
                                if (dir.name.startsWith("s_")) dir.setLastModified(System.currentTimeMillis())
                                models[name] = dst
                            }
                            got += name
                        }
                    }
                    // 合成器换了（文件不同，哪怕名字一样，或 流式↔兼容）：标记，下次启动推理时先丢掉旧合成器会话
                    val newSynth = models.filterKeys { it.contains("_stream") }.mapValues { it.value.absolutePath }
                    if (newSynth != oldSynth && engine != null) synthDirty = true
                    pruneSynthDirs()
                    slog('I', "收到模型 ${got.joinToString()}")
                    reply?.writeNoException()
                    reply?.writeString(null)
                    return true
                }
                TX_LIVE_BENCH -> {
                    if (data.dataAvail() >= 16 && readTune(data)) engine?.let { it.release(); engine = null }   // 线程/档位变了：连特征模型一起按新线程数重建
                    val r = runCatching {
                        val e = ensureEngine()
                        synthDirty = false
                        e.load({ msg -> engineState = msg }, force = true)
                        engineState = ""
                        e.benchSummary + (if (e.benchCfg.isNotEmpty()) "§" + e.benchCfg else "")
                    }.getOrElse { slog('E', "性能测试失败", it); "失败：" + describe(it) }
                    reply?.writeNoException()
                    reply?.writeString(r)
                    return true
                }
                TX_LOG_DUMP -> {
                    val since = data.readLong()
                    val (next, lines) = logSince(since)
                    reply?.writeNoException()
                    reply?.writeLong(next)
                    reply?.writeStringArray(lines)
                    return true
                }
                TX_LIVE_MONITOR -> {
                    setMonitor(data.readInt() == 1)
                    reply?.writeNoException()
                    return true
                }
                TX_LIVE_CAPTURE -> {
                    // sec>0：开始录（最长 sec 秒）；0：松手 —— 再多录一段管线延迟（变声还没吐完的尾巴），录满后回放；-1：取消
                    // 回包：尾巴还要多久(ms)、回放时长(ms)；没录到东西两个都是 0
                    val sec = data.readInt()
                    var tailMs = 0; var durMs = 0
                    synchronized(capLock) {
                        when {
                            sec > 0 -> { val tail = SR * 2; capBuf = ShortArray(SR * sec.coerceAtMost(30) + tail); capPos = 0; capStopAt = Int.MAX_VALUE }
                            sec == 0 -> {
                                val cb = capBuf
                                if (cb != null && capPos > SR / 5) {
                                    if (capStopAt == Int.MAX_VALUE) capStopAt = minOf(cb.size, capPos + SR * previewTailMs() / 1000)
                                    tailMs = ((capStopAt - capPos).coerceAtLeast(0) * 1000L / SR).toInt(); durMs = (capStopAt * 1000L / SR).toInt()
                                } else capBuf = null
                            }
                            else -> capBuf = null
                        }
                    }
                    slog('I', "录制预览 cmd=$sec 尾巴=${tailMs}ms 时长=${durMs}ms")
                    reply?.writeNoException()
                    reply?.writeInt(tailMs); reply?.writeInt(durMs)
                    return true
                }
                TX_LIVE_FX -> {
                    val n = data.readInt().coerceIn(0, 64)
                    val a = FloatArray(n) { data.readFloat() }
                    fx.set(a); fxLast = a
                    reply?.writeNoException()
                    return true
                }
                TX_LIVE_RELOAD -> {
                    val ok = running && liveExternal && engine != null
                    slog('I', "变声中换音色：${if (ok) "重启推理" else "未在 AI 变声，忽略"}")
                    if (ok) startEngine()
                    reply?.writeNoException()
                    reply?.writeInt(if (ok) 1 else 0)
                    return true
                }
                TX_LIVE_TUNE -> {
                    val reload = readTune(data)
                    if (reload && engine != null) {
                        slog('I', "高级参数变了，重新加载模型")
                        val wasRunning = running && liveExternal
                        val old = engine; engine = null
                        Thread({
                            runCatching { old?.stop() }
                            runCatching { Thread.sleep(600) }   // 等在途推理结束再关会话
                            runCatching { old?.release() }
                            if (wasRunning) startEngine()
                        }, "engine-retune").start()
                    }
                    reply?.writeNoException()
                    return true
                }
                TX_LIVE_SET -> {
                    liveSemis = data.readFloat()
                    engine?.semis = liveSemis.toInt()
                    reply?.writeNoException()
                    return true
                }
                TX_SFX_PLAY -> {
                    val pfd = data.readFileDescriptor()
                    val gain = data.readFloat().coerceIn(0f, 2f)
                    val loop = data.readInt() == 1
                    val bytes = pfd?.let { readAll(it) } ?: ByteArray(0)
                    val n = bytes.size / 2
                    val a = ShortArray(n) { i -> ((bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)).toShort() }
                    sfxPos = 0; sfxGain = gain; sfxLoop = loop; sfx = if (n > 0) a else null
                    slog('I', "语音包 播放 ${n * 1000L / SR}ms 音量=$gain 循环=$loop 注入中=$running")
                    reply?.writeNoException()
                    reply?.writeInt(n)
                    return true
                }
                TX_SFX_STOP -> {
                    sfx = null
                    reply?.writeNoException()
                    return true
                }
                TX_OUT_STATE -> {
                    reply?.writeNoException()
                    val t = lastVoicedNs
                    reply?.writeInt(if (t == 0L) -1 else ((System.nanoTime() - t) / 1_000_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    reply?.writeInt(outRing.size() * 1000 / SR)
                    return true
                }
                TX_SYS_STAT -> {
                    reply?.writeNoException()
                    reply?.writeFloat(cpuPct()); reply?.writeFloat(cpuTemp())
                    reply?.writeInt(selfRssMb()); reply?.writeLong(underruns)
                    return true
                }
                TX_STATUS -> {
                    reply?.writeNoException()
                    reply?.writeString(status())
                    return true
                }
            }
        } catch (t: Throwable) {
            slog('E', "transact $code failed", t)
            lastError = describe(t)
            reply?.writeNoException()
            reply?.writeString("ERROR|$lastError")
            return true
        }
        return super.onTransact(code, data, reply, flags)
    }

    // ------------------------------------------------------------ 核心

    private fun hasRoutingPermission(): Boolean {
        val c = ctx ?: return false
        return c.checkPermission(
            "android.permission.MODIFY_AUDIO_ROUTING", Process.myPid(), Process.myUid()
        ) == 0
    }

    @Synchronized
    private fun arm(uids: IntArray, auto: Boolean, tmo: Long, live: Boolean = false): String? {
        disarm("re-arm")
        if (uids.any { it < 10000 }) return "目标 uid 无效：${uids.joinToString()}"
        if (Build.VERSION.SDK_INT < 29) return "需要 Android 10 及以上"
        if (!hasRoutingPermission()) return "当前 ROM 的 shell 没有 MODIFY_AUDIO_ROUTING 权限"
        val c = shellCtx ?: return "服务没有拿到 Context（Shizuku 版本过低？）"
        val audioManager = am ?: return "AudioManager 不可用"

        try {
            val ruleCls = Class.forName("android.media.audiopolicy.AudioMixingRule")
            val ruleB = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
            val mixCls = Class.forName("android.media.audiopolicy.AudioMix")
            val mixB = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
            val polCls = Class.forName("android.media.audiopolicy.AudioPolicy")
            val polB = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
            val I = Integer.TYPE

            val rb = ruleB.getConstructor().newInstance()
            // 新系统：setTargetMixRole(MIX_ROLE_INJECTOR)；老系统回退 setTargetMixType(MIX_TYPE_RECORDERS)
            val roleOk = runCatching {
                ruleB.getMethod("setTargetMixRole", I)
                    .invoke(rb, ruleCls.getField("MIX_ROLE_INJECTOR").getInt(null))
            }.isSuccess
            if (!roleOk) {
                ruleB.getMethod("setTargetMixType", I)
                    .invoke(rb, mixCls.getField("MIX_TYPE_RECORDERS").getInt(null))
            }
            val ruleUid = ruleCls.getField("RULE_MATCH_UID").getInt(null)
            scopeAll = uids.isEmpty()
            autoNoExclude = false
            val rule: Any
            if (!scopeAll) {
                for (u in uids) ruleB.getMethod("addMixRule", I, Any::class.java).invoke(rb, ruleUid, Integer.valueOf(u))
                rule = ruleB.getMethod("build").invoke(rb)
            } else {
                // 自动识别：按录音源接管（MIC / CAMCORDER / VOICE_RECOGNITION / VOICE_COMMUNICATION / UNPROCESSED）
                val rulePreset = ruleCls.getField("RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET").getInt(null)
                val aab = Class.forName("android.media.AudioAttributes\$Builder")
                // 0 DEFAULT、10 VOICE_PERFORMANCE 也接管（SRVR 同款）；自己的录音靠下面的 uid 排除
                for (p in intArrayOf(0, 1, 5, 6, 7, 9, 10)) {
                    val b = aab.getConstructor().newInstance()
                    aab.getMethod("setCapturePreset", I).invoke(b, p)
                    val attr = aab.getMethod("build").invoke(b)
                    ruleB.getMethod("addMixRule", I, Any::class.java).invoke(rb, rulePreset, attr)
                }
                // 排除 shell（我们自己的真麦录音）和 MicMask App，否则会把注入的声音又录回来
                val ex = runCatching {
                    for (u in listOf(Process.myUid(), ShellAudioRecord.REC_UID, callerUid).filter { it >= 0 }.distinct())
                        ruleB.getMethod("excludeMixRule", I, Any::class.java).invoke(rb, ruleUid, Integer.valueOf(u))
                }
                val built = if (ex.isSuccess) runCatching { ruleB.getMethod("build").invoke(rb) }.getOrNull() else null
                if (built == null) autoNoExclude = true
                rule = built ?: buildPresetRuleNoExclude(ruleB, ruleCls, mixCls)
                slog('I', "自动识别范围：排除规则=${if (autoNoExclude) "不支持（shell 改用 VOICE_PERFORMANCE 录音）" else "已排除 uid ${Process.myUid()},${ShellAudioRecord.REC_UID},$callerUid"}")
            }

            val fmt = AudioFormat.Builder()
                .setSampleRate(SR)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()
            val mb = mixB.getConstructor(ruleCls).newInstance(rule)
            mixB.getMethod("setRouteFlags", I).invoke(mb, mixCls.getField("ROUTE_FLAG_LOOP_BACK").getInt(null))
            mixB.getMethod("setFormat", AudioFormat::class.java).invoke(mb, fmt)
            val mix = mixB.getMethod("build").invoke(mb)

            val pb = polB.getConstructor(Context::class.java).newInstance(c)
            polB.getMethod("addMix", mixCls).invoke(pb, mix)
            val pol = polB.getMethod("build").invoke(pb)

            val rc = AudioManager::class.java.getMethod("registerAudioPolicy", polCls)
                .invoke(audioManager, pol) as Int
            if (rc != 0) return "registerAudioPolicy 返回 $rc"
            policy = pol
            mixObj = mix

            val t = newInjectTrack(pol, mix)
            if (t == null || t.state != AudioTrack.STATE_INITIALIZED) {
                runCatching { t?.release() }
                unregister()
                return lastTrackErr.ifEmpty { "注入通道没有初始化（state=${t?.state}）" }
            }
            t.play()
            track = t
        } catch (t: Throwable) {
            unregister()
            lastError = describe(t)
            slog('E', "arm failed", t)
            dumpAudioDiag()
            return lastError
        }

        targetUids = uids
        baseRecCount = recCount()
        trigger = ""
        autoStart = auto
        timeoutMs = if (tmo > 0) tmo else 60_000L
        playPos = -1
        targetSeenRecording = false
        phase = "WAITING"
        lastError = ""
        running = true
        if (live) phase = "LIVE"
        worker = Thread({ if (live) liveLoop() else loop() }, "micmask-inject-pcm").apply { isDaemon = true; start() }
        slog('I', "ARMED uids=${uids.joinToString()} baseRec=$baseRecCount auto=$auto pcm=${pcm.size}")
        return null
    }

    /** 不带排除规则的录音源规则（系统不支持 excludeMixRule 时） */
    private fun buildPresetRuleNoExclude(ruleB: Class<*>, ruleCls: Class<*>, mixCls: Class<*>): Any {
        val I = Integer.TYPE
        val rb = ruleB.getConstructor().newInstance()
        val roleOk = runCatching { ruleB.getMethod("setTargetMixRole", I).invoke(rb, ruleCls.getField("MIX_ROLE_INJECTOR").getInt(null)) }.isSuccess
        if (!roleOk) ruleB.getMethod("setTargetMixType", I).invoke(rb, mixCls.getField("MIX_TYPE_RECORDERS").getInt(null))
        val rulePreset = ruleCls.getField("RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET").getInt(null)
        val aab = Class.forName("android.media.AudioAttributes\$Builder")
        for (p in intArrayOf(0, 1, 5, 6, 7, 9)) {
            val b = aab.getConstructor().newInstance()
            aab.getMethod("setCapturePreset", I).invoke(b, p)
            ruleB.getMethod("addMixRule", I, Any::class.java).invoke(rb, rulePreset, aab.getMethod("build").invoke(b))
        }
        return ruleB.getMethod("build").invoke(rb)
    }

    private fun startPlayback(why: String) {
        if (playPos < 0) {
            playPos = 0
            phase = "PLAYING"
            slog('I', "开始注入（$why）")
        }
    }

    private fun loop() {
        // ⚠️ 不能写纯 0：微信会把全零数据当成「麦克风被禁用/录音出错」直接中断。
        // 用 ±3 LSB 的白噪（约 -80 dBFS，听不见）当「舒适噪声」垫底。
        val rnd = java.util.Random()
        val noise = ByteArray(SR * FRAME_BYTES) // 1 秒，循环用
        run {
            var i = 0
            while (i < noise.size) {
                val v = rnd.nextInt(7) - 3
                noise[i] = (v and 0xff).toByte(); noise[i + 1] = ((v shr 8) and 0xff).toByte()
                noise[i + 2] = noise[i]; noise[i + 3] = noise[i + 1]
                i += FRAME_BYTES
            }
        }
        var noisePos = 0
        val armedAt = System.currentTimeMillis()
        var lastPoll = 0L
        var recording = false
        var leadLeft = 0
        var finishedAt = 0L
        try {
            while (running) {
                val now = System.currentTimeMillis()
                if (now - lastPoll >= 80) {
                    lastPoll = now
                    val was = recording
                    recording = isTargetRecording()
                    if (recording) targetSeenRecording = true
                    if (recording && !was && autoStart && playPos < 0) {
                        leadLeft = LEAD_SILENCE_MS / 10
                        phase = "LEAD"
                    }
                    // 目标停止录音（用户松手发送）且我们已经放过 → 收工，还原真麦克风
                    if (!recording && was && playPos >= 0) {
                        slog('I', "目标停止录音，结束注入")
                        break
                    }
                }
                if ((now - armedAt > timeoutMs && !recording) || now - armedAt > 300_000L) {
                    slog('I', "超时 ${timeoutMs}ms，结束注入")
                    break
                }
                if (phase == "LEAD") {
                    if (leadLeft-- <= 0) startPlayback("目标开始录音")
                }

                val t = track ?: break
                val data = pcm
                val pos = playPos
                if (pos in 0 until data.size) {
                    val n = minOf(CHUNK_BYTES, data.size - pos)
                    val w = t.write(data, pos, n)
                    if (w < 0) { lastError = "AudioTrack.write=$w"; break }
                    playPos = pos + w
                    if (playPos >= data.size) {
                        phase = "TAIL"
                        finishedAt = System.currentTimeMillis()
                    }
                } else {
                    if (noisePos + CHUNK_BYTES > noise.size) noisePos = 0
                    t.write(noise, noisePos, CHUNK_BYTES)
                    noisePos += CHUNK_BYTES
                    // 手动模式 / 目标根本没在录：放完 1.5 秒后自己收工
                    if (phase == "TAIL" && !recording && now - finishedAt > 1500) break
                }
            }
        } catch (t: Throwable) {
            lastError = describe(t)
            slog('E', "loop", t)
        }
        if (running) disarm("loop-end")
    }

    // ------------------------------------------------------------ shell 侧日志

    private val logRing = ArrayDeque<Pair<Long, String>>()
    private var logSeq = 0L
    private val logFmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)

    private var a11yFailed = false
    private var a11yPrevSrc = -1
    private var a11yFloor = -60f
    private var a11yPeak = -200f
    private var a11yChkSec = -1
    private var tgtSilSec = 0
    private var a11yIdle = 0
    private val lvlHist = ArrayDeque<Float>()

    /** 别的应用（非我们自己）有录音被系统静音 */
    private fun targetSilenced(): Boolean = runCatching {
        val list = am?.activeRecordingConfigurations ?: return false
        val c = android.media.AudioRecordingConfiguration::class.java
        val gu = c.getMethod("getClientUid"); val gs = c.getMethod("isClientSilenced")
        list.any { val u = gu.invoke(it) as Int; u >= 10000 && u != callerUid && u != Process.myUid() && u != ShellAudioRecord.REC_UID && gs.invoke(it) == true }
    }.getOrDefault(false)

    /** 有别的应用（uid≥10000，非我们自己）正在录音 */
    private fun appRecording(): Boolean = runCatching {
        val list = am?.activeRecordingConfigurations ?: return false
        val gu = android.media.AudioRecordingConfiguration::class.java.getMethod("getClientUid")
        list.any { val u = gu.invoke(it) as Int; u >= 10000 && u != callerUid && u != Process.myUid() && u != ShellAudioRecord.REC_UID }
    }.getOrDefault(false)

    @Volatile private var a11yThread: Thread? = null
    @Volatile private var a11yOn = false
    private var a11yLast = ""

    /** 系统已启用的无障碍服务 uid（AccessibilityManagerService 也只登记这些） */
    private fun sysA11yUids(): List<Int> = runCatching {
        val c = shellCtx ?: return emptyList()
        val v = android.provider.Settings.Secure.getString(c.contentResolver, "enabled_accessibility_services") ?: ""
        v.split(':').mapNotNull { it.substringBefore('/').takeIf { p -> p.isNotBlank() } }.distinct()
            .mapNotNull { p -> runCatching { c.packageManager.getPackageUid(p, 0) }.getOrNull() ?: runCatching { c.packageManager.getApplicationInfo(p, 0).uid }.getOrNull() }
    }.getOrDefault(emptyList())

    /** 调 AudioSystem.setA11yServicesUids（隐藏 API）把自己加进/移出无障碍 uid 列表；系统改无障碍设置时会覆盖，所以定时重登 */
    private fun a11yBoost(on: Boolean, quiet: Boolean = false): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < 29) return false
        val mine = listOf(Process.myUid(), ShellAudioRecord.REC_UID).distinct()
        val uids = (sysA11yUids() + if (on) mine else emptyList()).distinct()
        val key = uids.joinToString(",")
        if (quiet && key == a11yLast) {
            // 列表没变也要重登：系统可能刚覆盖过
        }
        val m = Class.forName("android.media.AudioSystem").getDeclaredMethod("setA11yServicesUids", IntArray::class.java)
        m.isAccessible = true
        val r = m.invoke(null, uids.toIntArray())
        a11yOn = on
        if (!quiet || key != a11yLast) slog('I', "无障碍 uid ${if (on) "登记" else "还原"} [$key] → $r")
        a11yLast = key
        (r as? Int ?: 0) == 0
    }.getOrElse { if (!quiet) slog('W', "无障碍 uid 登记失败：${describe(it)}"); false }

    @Volatile private var rootProbed = false
    /** root 调试：打印身份与可用的 root 方案，为以后直接支持 root 模式（不经 Shizuku）摸底 */
    private fun rootProbe(out: (String) -> Unit) {
        fun sh(c: String) = runCatching {
            val p = ProcessBuilder("timeout", "4", "sh", "-c", c).redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText().trim().take(300).also { p.waitFor() }
        }.getOrElse { "ERR ${it.message}" }
        fun f(path: String) = java.io.File(path).exists()
        out("ROOT 录音身份 uid=${ShellAudioRecord.REC_UID}（MICMASK_ROOT_ID=${System.getenv("MICMASK_ROOT_ID")}）")
        out("ROOT id: ${sh("id")}")
        out("ROOT selinux: ${sh("cat /proc/self/attr/current; echo; getenforce")}")
        out("ROOT 方案: magisk=${f("/data/adb/magisk")} ksu=${f("/data/adb/ksu")} apatch=${f("/data/adb/ap")} su=${sh("command -v su")}")
        out("ROOT su 版本: ${sh("su -v 2>&1; su -V 2>&1")}")
        out("ROOT su 切 shell: ${sh("su 2000 -c id 2>&1 || su shell -c id 2>&1")}")
        out("ROOT shizuku 进程: ${sh("ps -A -o USER,PID,NAME 2>/dev/null | grep -i shizuku")}")
        out("ROOT audio 策略: ${sh("dumpsys media.audio_policy 2>/dev/null | grep -iE 'silenc|Capture|uid 10' | head -15")}")
    }

    private fun slog(level: Char, msg: String, t: Throwable? = null) {
        when (level) { 'E' -> Log.e(TAG, msg, t); 'W' -> Log.w(TAG, msg, t); else -> Log.i(TAG, msg) }
        val line = synchronized(logFmt) { logFmt.format(java.util.Date()) } + " $level [shell/${Thread.currentThread().name}] " + msg +
            (if (t != null) "\n" + Log.getStackTraceString(t) else "")
        synchronized(logRing) {
            logRing.addLast(++logSeq to line)
            while (logRing.size > 2000) logRing.removeFirst()
        }
    }


    private fun logSince(since: Long): Pair<Long, Array<String>> = synchronized(logRing) {
        val out = logRing.filter { it.first > since }.take(500)
        (out.lastOrNull()?.first ?: maxOf(since, logSeq - logRing.size)) to out.map { it.second }.toTypedArray()
    }

    // ------------------------------------------------------------ shell 内推理（不受 App 后台冻结/降频影响）

    private val MODEL_DIR = "/data/local/tmp/micmask-models"
    /** 抽样指纹：在 1/4、1/2、3/4 和末尾各读 64KB 算 CRC32（不移动流位置），用来识别大小相同的不同模型 */
    private fun sampleCrc(ch: java.nio.channels.FileChannel, size: Long): Long {
        val crc = java.util.zip.CRC32(); val buf = java.nio.ByteBuffer.allocate(64 * 1024)
        for (pos in longArrayOf(size / 4, size / 2, size * 3 / 4, maxOf(0L, size - buf.capacity()))) {
            buf.clear(); var p = pos
            while (buf.hasRemaining()) { val k = ch.read(buf, p); if (k <= 0) break; p += k }
            buf.flip(); crc.update(buf)
        }
        return crc.value
    }
    private val loadedNames = HashSet<String>()
    /** 合成器文件变了，下次启动推理前要 dropSynth */
    @Volatile private var synthDirty = false
    private val engineLock = Any()
    @Volatile private var startSeq = 0

    /** 只留最近用过的 8 个音色的合成器目录（连同里面的 .ortcache），并清掉旧版平铺在根目录的合成器 */
    private fun pruneSynthDirs() = runCatching {
        val root = java.io.File(MODEL_DIR)
        val inUse = models.values.map { it.parentFile?.absolutePath }.toSet()
        root.listFiles()?.filter { it.isFile && it.name.contains("_stream") }?.forEach { it.delete() }
        root.listFiles()?.filter { it.isDirectory && it.name.startsWith("s_") && it.absolutePath !in inUse }
            ?.sortedByDescending { it.lastModified() }?.drop(7)?.forEach { it.deleteRecursively(); slog('I', "清理旧音色缓存 ${it.name}") }
        java.io.File(root, ".ortcache").listFiles()?.filter { it.name.contains("_stream") }?.forEach { it.delete() }
    }
    private val models = java.util.concurrent.ConcurrentHashMap<String, java.io.File>()
    @Volatile private var engine: com.taocrypt.micmask.LiveRvc? = null
    @Volatile private var engineState = ""
    /** 引擎每次真正跑起来（模型加载完、开始推理）+1；App 据此判断「换音色」是否已生效 */
    @Volatile private var engGen = 0

    @Volatile private var tuneTier = 0
    @Volatile private var tuneGate = com.taocrypt.micmask.LiveRvc.GATE_DB
    @Volatile private var tuneFeat = com.taocrypt.micmask.LiveRvc.FEAT_THREADS
    @Volatile private var tuneSyn = com.taocrypt.micmask.LiveRvc.SYN_THREADS
    @Volatile private var tunePin = true
    @Volatile private var tuneIdx = 0.3f
    /** 声码器 0=自动 1=ORT 整图 2=NEON fp16（旧版 App 不传 → 自动） */
    @Volatile private var tuneVoc = 0
    @Volatile private var tuneRms = 0f
    @Volatile private var tuneFormant = 0f
    @Volatile private var tunePl = false
    /** 读高级参数并套到现有引擎；返回是否需要重载 */
    private fun readTune(d: android.os.Parcel): Boolean {
        tuneTier = d.readInt(); tuneGate = d.readFloat(); tuneFeat = d.readInt(); tuneSyn = d.readInt()
        if (d.dataAvail() >= 4) tunePin = d.readInt() != 0
        if (d.dataAvail() >= 4) tuneIdx = d.readFloat()
        if (d.dataAvail() >= 4) tuneVoc = d.readInt()
        if (d.dataAvail() >= 4) tuneRms = d.readFloat()
        if (d.dataAvail() >= 4) tuneFormant = d.readFloat()
        if (d.dataAvail() >= 4) tunePl = d.readInt() != 0
        val plChanged = engine?.let { it.phaseLock != tunePl } ?: false
        engine?.let { it.rmsFollow = tuneRms; it.formant = tuneFormant; it.phaseLock = tunePl }
        slog('I', "音色参数：响度跟随=$tuneRms 共振峰=$tuneFormant 相位锁定=$tunePl")
        engine?.pinBig = tunePin; engine?.indexRate = tuneIdx
        slog('I', "高级参数：档位=${listOf("自动", "标准", "省电").getOrElse(tuneTier) { "?" }} 静音门限=${tuneGate}dB 线程=$tuneFeat/$tuneSyn 绑大核=$tunePin 检索=$tuneIdx 声码器=${listOf("自动", "ORT", "NEON fp16", "NPU").getOrElse(tuneVoc) { "?" }}")
        val vocChanged = engine?.setVocoder(tuneVoc) ?: false
        return (engine?.tune(tuneTier, tuneGate, tuneFeat, tuneSyn) ?: false) || vocChanged || plChanged
    }

    @Synchronized private fun ensureEngine(): com.taocrypt.micmask.LiveRvc {
        com.taocrypt.micmask.L.sink = { lv, m -> slog(lv, m) }
        return engine ?: com.taocrypt.micmask.LiveRvc { n -> models[n]?.takeIf { it.exists() }?.also { synchronized(loadedNames) { loadedNames += n } } }.also {
            it.pickCache = java.io.File(MODEL_DIR, "pick.txt")
            it.tune(tuneTier, tuneGate, tuneFeat, tuneSyn)
            it.pinBig = tunePin; it.indexRate = tuneIdx; it.setVocoder(tuneVoc)
            it.rmsFollow = tuneRms; it.formant = tuneFormant; it.phaseLock = tunePl
            it.qnnDirProvider = { com.taocrypt.micmask.QnnPack.shellDir(ctx?.packageName) }
            it.onConfig = { blockMs -> tierPrimeMs = blockMs + 80; livePrimeMs = tierPrimeMs; slog('I', "预缓冲跟随档位 → ${livePrimeMs}ms") }
            engine = it
        }
    }

    private fun startEngine() {
        com.taocrypt.micmask.L.sink = { lv, m -> slog(lv, m) }
        engine?.stop()
        val my = ++startSeq
        Thread({
            // 串行：上一次启动（比如还在加载模型时就又切了音色）做完再开始，避免两个线程同时 load 同一个引擎
            synchronized(engineLock) {
                if (my != startSeq) return@synchronized
                try {
                    runCatching { android.os.Process.setThreadPriority(-16) }
                    val e = ensureEngine().also { engine = it }
                    if (synthDirty) { synthDirty = false; e.dropSynth() }
                    e.semis = liveSemis.toInt()
                    e.io = object : com.taocrypt.micmask.LiveRvc.Io {
                        override fun pull(max: Int): ShortArray {
                            val tmp = ShortArray(minOf(max, micRing.size()))
                            val k = micRing.read(tmp, 0, tmp.size)
                            return if (k == tmp.size) tmp else tmp.copyOf(k)
                        }
                        override fun push(pcm: ShortArray) { outRing.write(pcm, 0, pcm.size) }
                    }
                    engineState = "加载模型"
                    e.load({ msg -> engineState = msg })
                    if (!running) { engineState = ""; return@synchronized }
                    if (my != startSeq) { slog('I', "推理启动被新的切换取代"); return@synchronized }
                    micRing.clear()
                    e.start()
                    engineState = "运行"
                    engGen++
                    slog('I', "shell 推理已启动（进程内，不经 App）")
                } catch (t: Throwable) {
                    if (my != startSeq) { slog('W', "旧的推理启动失败（已被新的切换取代）：${describe(t)}"); return@synchronized }
                    engineState = "失败：" + describe(t)
                    slog('E', "shell 推理启动失败", t)
                }
            }
        }, "engine-start").start()
    }

    // ------------------------------------------------------------ 实时变声

    @Volatile private var liveSemis = 0f
    @Volatile private var liveSource = 1 // MediaRecorder.AudioSource.MIC
    /** 强制走 native_setup 兜底录音（App 设置「兼容录音」，经 TX_LIVE_START 的 source 高位传入） */
    @Volatile private var recCompat = false
    @Volatile private var recPath = ""
    /** 麦克风健康提示（空=正常）：没收到数据 / 纯静音 / 被系统静音；App 状态行显示 */
    @Volatile private var micHint = ""
    @Volatile private var lastReadNs = 0L
    @Volatile private var curRec: android.media.AudioRecord? = null
    @Volatile private var mixObj: Any? = null
    private var recRebuilds = 0
    private var aec: android.media.audiofx.AcousticEchoCanceler? = null
    private var ns: android.media.audiofx.NoiseSuppressor? = null
    @Volatile private var liveExternal = false
    @Volatile private var livePrimeMs = 100
    /** 引擎定档后的预缓冲；第二次开始实时变声时引擎已在，不会再回调 onConfig，要沿用它（1.23 日志：退回 380ms 后连续断流） */
    @Volatile private var tierPrimeMs = 0
    private val micRing = ShortRing(SR * 3)
    private val outRing = ShortRing(SR * 3)
    /** 外部（AI）输出里最近一次出现人声的时刻；峰值约 -30 dBFS 以上算有声 */
    @Volatile private var lastVoicedNs = 0L
    private val VOICED_PEAK = 1000
    @Volatile private var underruns = 0L
    @Volatile private var liveLevelDb = -120f
    @Volatile private var liveFrames = 0L

    // ------------------------------------------------------------ 耳返 / 录制预览
    @Volatile private var monTrack: AudioTrack? = null
    private val capLock = Any()
    private var capBuf: ShortArray? = null
    private var capPos = 0
    /** 录到这个位置就结束并回放（松手后 = 松手位置 + 管线延迟） */
    private var capStopAt = Int.MAX_VALUE
    /** 松手后还要多录多久：预缓冲 + 一块推理，AI 档约 0.5~1 s，普通变调约 0.3 s */
    private fun previewTailMs() = (livePrimeMs * 2 + 100).coerceIn(250, 1500)

    private fun newMediaTrack(bufMs: Int): AudioTrack {
        val attrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val fmt = AudioFormat.Builder().setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
        val min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val b = AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(fmt)
            .setBufferSizeInBytes(maxOf(min, SR * bufMs / 1000 * 2))
        if (Build.VERSION.SDK_INT >= 26) b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        // Android 17：shell 进程建 AudioTrack 不带 Context 会抛 Cannot create AudioTrack（Redmi 24117RK2CC 实测），带上 shellCtx
        if (Build.VERSION.SDK_INT >= 34) shellCtx?.let { c ->
            runCatching { AudioTrack.Builder::class.java.getMethod("setContext", Context::class.java).invoke(b, c) }
        }
        return b.build()
    }

    private fun setMonitor(on: Boolean) {
        if (on) {
            if (monTrack != null) return
            runCatching { newMediaTrack(60).also { it.play(); monTrack = it } }
                .onSuccess { slog('I', "耳返已开（建议戴耳机，外放会啸叫）") }
                .onFailure { slog('E', "耳返打开失败", it) }
        } else {
            val t = monTrack ?: return
            monTrack = null
            runCatching { t.stop() }; runCatching { t.release() }
            slog('I', "耳返已关")
        }
    }

    private val monBuf = ShortArray(SR / 100)
    /** 耳返/录制预览只取人声（音效混入之前），音效只进麦克风；要本机听音效用悬浮窗长按外放 */
    private fun voiceTap(buf: FloatArray, n: Int) {
        for (i in 0 until n) monBuf[i] = (buf[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        tap(monBuf, n)
    }

    /** 每 10 ms 一帧变声结果都过一遍这里：耳返 + 录制预览 */
    private fun tap(mono: ShortArray, n: Int) {
        monTrack?.let { t -> if (t.write(mono, 0, n, AudioTrack.WRITE_NON_BLOCKING) < 0) setMonitor(false) }
        var done: ShortArray? = null
        synchronized(capLock) {
            val cb = capBuf ?: return@synchronized
            val end = minOf(cb.size, capStopAt)
            val k = minOf(n, end - capPos)
            if (k > 0) { System.arraycopy(mono, 0, cb, capPos, k); capPos += k }
            if (capPos >= end) { done = cb.copyOf(capPos); capBuf = null }
        }
        done?.let { playPreview(it) }
    }

    private fun playPreview(pcm: ShortArray) {
        Thread({
            try {
                slog('I', "录制预览完成，开始回放 ${pcm.size / SR}s")
                val t = newMediaTrack(200)
                t.play()
                var o = 0
                while (o < pcm.size) { val w = t.write(pcm, o, minOf(4800, pcm.size - o)); if (w <= 0) break; o += w }
                Thread.sleep(300)
                runCatching { t.stop() }; t.release()
            } catch (t: Throwable) { slog('E', "预览回放失败", t) }
        }, "preview").start()
    }

    /**
     * 实时链路：在 shell(uid 2000) 进程里录真麦 → 变调 → 写进注入通道。
     * 非 App uid 不受「后台 App 录音被静音」策略影响（inferred），所以 QQ/微信在前台录音时这里照样能拿到真麦。
     */
    private fun liveLoop() {
        val c = shellCtx
        var rec: android.media.AudioRecord? = null
        var watchdog: Thread? = null
        try {
            val minBuf = android.media.AudioRecord.getMinBufferSize(SR,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufBytes = maxOf(minBuf, SR / 50 * 2 * 2)
            val log: (Char, String) -> Unit = { lv, m -> slog(lv, m) }
            /** 建录音 + AEC/NS + 开始；失败返回 null（lastError 已写） */
            fun openRec(): android.media.AudioRecord? {
                // 常规 Builder → 失败则 native_setup 兜底（vivo 等 ROM 改了构造）；recCompat=强制兜底，用于测试
                // 自动范围且无法排除自己：shell 用不在接管列表里的 VOICE_PERFORMANCE 录真麦
                if (scopeAll && autoNoExclude && Build.VERSION.SDK_INT >= 29 && liveSource != 10) { slog('I', "录音源改为 VOICE_PERFORMANCE（避免录到注入声）"); liveSource = 10 }
                var res = ShellAudioRecord.create(liveSource, SR, bufBytes, c, recCompat, log)
                if (res.rec == null && !(scopeAll && autoNoExclude) && liveSource != android.media.MediaRecorder.AudioSource.MIC) {
                    slog('W', "录音源 $liveSource 初始化失败，退回 MIC")
                    liveSource = android.media.MediaRecorder.AudioSource.MIC
                    res = ShellAudioRecord.create(liveSource, SR, bufBytes, c, recCompat, log)
                }
                recPath = res.path
                val r = res.rec
                if (r == null || r.state != android.media.AudioRecord.STATE_INITIALIZED) {
                    runCatching { r?.release() }
                    lastError = "shell 录音初始化失败（${res.error}）"
                    return null
                }
                slog('I', "LIVE 录音路径=${res.path} 强制兼容=$recCompat sdk=${Build.VERSION.SDK_INT} ${Build.MANUFACTURER}/${Build.MODEL}")
                // 回声消除 / 降噪：扬声器外放的声音会被真麦录进去，用系统（多为高通硬件）AEC 去掉
                val sid = r.audioSessionId
                runCatching { aec?.release() }; runCatching { ns?.release() }
                aec = if (android.media.audiofx.AcousticEchoCanceler.isAvailable())
                    runCatching { android.media.audiofx.AcousticEchoCanceler.create(sid)?.apply { enabled = true } }.getOrNull() else null
                ns = if (android.media.audiofx.NoiseSuppressor.isAvailable())
                    runCatching { android.media.audiofx.NoiseSuppressor.create(sid)?.apply { enabled = true } }.getOrNull() else null
                slog('I', "LIVE 回声消除 可用=${android.media.audiofx.AcousticEchoCanceler.isAvailable()} 已开=${aec?.enabled} 系统降噪效果 已开=${ns?.enabled}（录音源=$liveSource）")
                // 指定真麦：小米 A13 实测自己的录音被路由到注入通道（设备 25 remote submix），录到的是静音，还和目标应用抢通道
                runCatching {
                    val mics = (am ?: return@runCatching).getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
                    val want = mics.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET || it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET }
                        ?: mics.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC }
                    if (want != null) slog('I', "LIVE 指定输入设备 ${want.type}/${want.productName} → ${r.setPreferredDevice(want)}")
                }
                r.startRecording()
                lastReadNs = System.nanoTime()
                val rd = runCatching { r.routedDevice }.getOrNull()
                slog(if (rd?.type == android.media.AudioDeviceInfo.TYPE_REMOTE_SUBMIX) 'W' else 'I', "LIVE 开始：src=$liveSource semis=$liveSemis dev=${rd?.type}/${rd?.productName}" +
                    (if (rd?.type == android.media.AudioDeviceInfo.TYPE_REMOTE_SUBMIX) "（录到了注入通道！）" else ""))
                return r
            }
            // 并发录音仲裁：系统只放行「最上层/最近开始」的一路，我们和前台 TT 谁后开谁赢（realme uid 2000 实测）。
            // 只在目标应用被静音时才把自己登记为无障碍 uid（不参与仲裁）+ VOICE_RECOGNITION；vivo 上一开始就登记会录到静音（test9 实测）
            a11yFailed = false; a11yPrevSrc = -1; a11yIdle = 0
            // alpha.6：非 vivo 机型一开始就登记无障碍 uid + 录音源 6。等目标应用被静音再切要 0.2–1.3 s，
            // 微信短语音根本来不及（M332BF 日志）；realme test9、小米 2604FRK1EC 上源 6 实测可用。vivo 上一开始就登记会录到静音，仍走延迟切换
            // alpha.7：按场景选——外放且正在通话（mode=IN_COMMUNICATION/IN_CALL、没有耳机）时源 6 没有回声消除，容易啸叫，
            // 这时保留原录音源（多为带 AEC 的通话源 7），被静音了再走原来的延迟切换；通话结束或插上耳机后再换回源 6
            val isVivo = Build.MANUFACTURER.equals("vivo", true) || Build.BRAND.equals("vivo", true) || Build.BRAND.equals("iqoo", true)
            val preOk = !isVivo && Build.VERSION.SDK_INT >= 29 && liveSource != 10 && !(scopeAll && autoNoExclude)
            fun headsetOut(): Boolean = runCatching {
                val t = setOf(android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET, android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    android.media.AudioDeviceInfo.TYPE_USB_HEADSET, android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, 26 /* TYPE_BLE_HEADSET（API 31） */)
                (am ?: return@runCatching false).getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any { it.type in t }
            }.getOrDefault(false)
            fun echoRisk(): Boolean = runCatching {
                val m = am?.mode
                (m == android.media.AudioManager.MODE_IN_COMMUNICATION || m == android.media.AudioManager.MODE_IN_CALL) && !headsetOut()
            }.getOrDefault(false)
            var preOn = false; var preRevoked = false; var riskSec = 0; var safeSec = 0
            /** 开局（或通话结束后）改无障碍录音 + 源 6，不重建录音（调用方负责） */
            fun preEnable(why: String): Boolean {
                if (!a11yBoost(true)) return false
                a11yPrevSrc = liveSource
                slog('I', "$why：录音源 $liveSource → 6（无障碍录音）")
                liveSource = 6; a11yChkSec = -1; preOn = true; preRevoked = false
                a11yThread?.interrupt()
                a11yThread = Thread({ try { while (true) { Thread.sleep(3000); a11yBoost(true, quiet = true) } } catch (_: InterruptedException) {} }, "a11y-keep").apply { isDaemon = true; start() }
                return true
            }
            if (preOk) {
                if (echoRisk()) { preRevoked = true; slog('I', "外放且正在通话，保留录音源 $liveSource（回声消除），被静音时再改无障碍录音") }
                else preEnable("开始即用无障碍录音（非 vivo，${if (headsetOut()) "戴耳机" else "未在通话"}）")
            }
            rec = openRec() ?: return
            curRec = rec
            micHint = ""; recRebuilds = 0
            // 看门狗：0.6 s 读不到麦克风数据（流失效、切路由、音频服务重启）就停掉当前录音，让主循环重建
            watchdog = Thread({
                while (running) {
                    runCatching { Thread.sleep(200) }
                    val r = curRec ?: continue
                    if (System.nanoTime() - lastReadNs > 600_000_000L) {
                        micHint = "没有收到麦克风数据，正在重建录音"
                        slog('W', "0.6 s 没有麦克风数据，停止当前录音以重建")
                        lastReadNs = System.nanoTime()
                        runCatching { r.stop() }
                    }
                }
            }, "micmask-mic-watchdog").apply { isDaemon = true; start() }
            var rebuildWindow = System.currentTimeMillis()
            /** 重建录音：1 分钟内最多 5 次，超了才结束变声 */
            fun rebuildRec(why: String): Boolean {
                val now = System.currentTimeMillis()
                if (now - rebuildWindow > 60_000L) { rebuildWindow = now; recRebuilds = 0 }
                if (++recRebuilds > 9) { lastError = "麦克风反复失效（$why），已停止"; return false }
                slog('W', "重建录音（$why）第 $recRebuilds 次")
                curRec = null
                runCatching { rec?.stop() }; runCatching { rec?.release() }
                rec = null
                runCatching { Thread.sleep(150) }
                if (!running) return false
                rec = openRec() ?: return false
                curRec = rec
                return true
            }
            var zeroFrames = 0
            // alpha.6：录到的一直是平的数字静音（vivo V2148A 2.0.0：shell 录音源 7 连续十几分钟恒为 -87 dB，说话也不变）→ 依次换录音源。
            // 判据：连续 10 s 最大电平 < -84 dB 且波动 ≤ 1.5 dB；真实底噪会起伏（小米源 7 静默时 -80~-85 来回跳）
            // 纯 0（-120 dB）不算：通话降噪不说话时本来就输出全 0（2410DPN6CC），那是被系统静音的检测管的
            var stuckSec = 0; var stuckMax = -200f; var stuckMin = 0f; val stuckTried = LinkedHashSet<Int>(); var stuckOrig = -1; var stuckGaveUp = false
            var stuckT = System.nanoTime()
            var lastTgtSil: Boolean? = null
            // 被系统静音检测（AI / 普通两种模式都查）：连续 2 s 被静音且当前不是原始麦克风 → 改用原始麦克风重建一次
            var silChkT = System.nanoTime(); var silSec = 0
            // 0 DEFAULT、1 MIC、5 CAMCORDER、6 VOICE_RECOGNITION、9 UNPROCESSED、10 VOICE_PERFORMANCE
            val silOrder = intArrayOf(1, 6, 5, 9, 10, 0); var silTry = 0; val silTried = LinkedHashSet<Int>(); var silGood = true
            fun silWho(): String = runCatching {
                am?.activeRecordingConfigurations?.filter { it.clientAudioSessionId != (rec?.audioSessionId ?: -1) }
                    ?.joinToString(" ") { "src=${it.clientAudioSource}" + (runCatching { " uid=" + it.javaClass.getMethod("getClientUid").invoke(it) }.getOrDefault("")) }
            }.getOrNull().orEmpty().ifEmpty { "未知应用" }
            val shifter = PitchShifter(SR)
            val hop = SR / 100            // 10 ms
            val inBuf = ShortArray(hop)
            val outBytes = ByteArray(hop * FRAME_BYTES)
            val rnd = java.util.Random()
            val ext = ShortArray(hop)
            val tapBuf = ShortArray(hop)
            var statT = System.nanoTime(); var rdMax = 0L; var wrMax = 0L; var obMin = Int.MAX_VALUE; var obMax = 0
            var lastUnder = 0L; var lastRec = -1
            var primed = false
            // 外部模式先攒够再开始放：App 每 300 ms 推一整块，缓冲必须 > 一块，否则每块之间都会断
            var prime = SR * livePrimeMs / 1000
            slog('I', "LIVE 外部模式=${liveExternal} 预缓冲=${livePrimeMs}ms")
            while (running) {
                var got = 0
                val tr0 = System.nanoTime()
                var zeroReads = 0
                while (got < hop && running) {
                    val rr = rec ?: break
                    val r = rr.read(inBuf, got, hop - got)
                    if (r < 0 || (r == 0 && ++zeroReads > 3)) {
                        if (!rebuildRec("read=$r")) return
                        got = 0; zeroReads = 0; continue
                    }
                    if (r > 0) { got += r; lastReadNs = System.nanoTime() }
                }
                if (!running) break
                // 原始麦克风不会给出连续的纯数字 0：连续 3 s 全零 = 被系统静音或被别的录音占用。
                // 降噪模式（VOICE_COMMUNICATION）不说话时降噪本身就会输出全 0，不能据此提示（alpha101 真机误报）
                var allZero = liveSource != android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION
                if (allZero) for (i in 0 until hop) if (inBuf[i].toInt() != 0) { allZero = false; break }
                if (allZero) { if (++zeroFrames == 300) { micHint = "采集到的是纯静音（麦克风可能被系统静音或被占用）"; slog('W', micHint) } }
                else { if (zeroFrames >= 300 || micHint.startsWith("没有收到")) micHint = ""; zeroFrames = 0 }
                if (System.nanoTime() - stuckT >= 1_000_000_000L) {
                    stuckT = System.nanoTime()
                    val lv = liveLevelDb
                    if (lv < -84f) {
                        stuckMax = if (stuckSec == 0) lv else maxOf(stuckMax, lv); stuckMin = if (stuckSec == 0) lv else minOf(stuckMin, lv)
                        if (++stuckSec >= 10 && !stuckGaveUp && stuckMax - stuckMin <= 1.5f && stuckMin > -100f && a11yChkSec !in 0..3 && !micHint.startsWith("麦克风被系统静音")) {
                            stuckSec = 0
                            if (stuckOrig < 0) stuckOrig = liveSource
                            stuckTried += liveSource
                            val next = intArrayOf(1, 6, 0).firstOrNull { it != liveSource && it !in stuckTried }
                            if (next != null) {
                                slog('W', "录音源 $liveSource 连续 10 s 是平的数字静音（${"%.0f".format(stuckMax)}dB），改用录音源 $next")
                                micHint = "麦克风只录到静音，正在换录音方式"
                                liveSource = next
                                if (!rebuildRec("数字静音换源")) return
                                continue
                            } else {
                                slog('W', "录音源 ${stuckTried.joinToString(",")} 都只录到数字静音，退回录音源 $stuckOrig，不再自动切换")
                                micHint = "麦克风只录到静音：请检查系统的麦克风权限/隐私开关，或在设置里换录音方式"
                                stuckGaveUp = true
                                if (liveSource != stuckOrig) { liveSource = stuckOrig; if (!rebuildRec("数字静音换源失败")) return; continue }
                            }
                        }
                    } else {
                        if (stuckTried.isNotEmpty() && !stuckGaveUp && lv > -70f) { slog('I', "录音源 $liveSource 有声音了（${"%.0f".format(lv)}dB），之前数字静音：${stuckTried.joinToString(",")}"); stuckTried.clear(); stuckOrig = -1; if (micHint.startsWith("麦克风只录到静音")) micHint = "" }
                        stuckSec = 0
                    }
                }
                if (Build.VERSION.SDK_INT >= 29 && System.nanoTime() - silChkT >= 1_000_000_000L) {
                    silChkT = System.nanoTime()
                    val sil = runCatching {
                        val sid = rec?.audioSessionId ?: -1
                        am?.activeRecordingConfigurations?.firstOrNull { it.clientAudioSessionId == sid }?.isClientSilenced == true
                    }.getOrDefault(false)
                    // 我们没被静音、目标应用却被静音 2 s → 改无障碍录音；改完 3 s 输入比改前底噪低 15 dB 以上 → 判定无效，退回
                    lvlHist.addLast(liveLevelDb); while (lvlHist.size > 3) lvlHist.removeFirst()
                    if (preOk && (preOn || preRevoked)) {
                        val risk = echoRisk()
                        if (risk) { riskSec++; safeSec = 0 } else { safeSec++; riskSec = 0 }
                        if (preOn && a11yOn && riskSec >= 2 && a11yPrevSrc >= 0) {
                            slog('W', "外放通话开始，退回录音源 $a11yPrevSrc（回声消除），被静音时再改无障碍录音")
                            a11yThread?.interrupt(); a11yThread = null; a11yBoost(false)
                            liveSource = a11yPrevSrc; preOn = false; preRevoked = true; a11yFailed = false; a11yChkSec = -1; riskSec = 0
                            if (!rebuildRec("外放通话退回回声消除")) return
                            continue
                        }
                        if (preRevoked && !a11yOn && safeSec >= 3) {
                            safeSec = 0
                            if (preEnable(if (headsetOut()) "已戴耳机" else "通话已结束")) { if (!rebuildRec("改回无障碍录音")) return; continue }
                            else preRevoked = false
                        }
                    }
                    // alpha.6：目标应用的静音状态一变就记一行（以前只在录音数变化时打印，看不出切换后微信是否解除静音）
                    val ts = targetSilenced()
                    if (lastTgtSil != null && ts != lastTgtSil) slog('I', "目标应用静音 ${lastTgtSil} → $ts ${recDetail()}")
                    lastTgtSil = ts
                    if (!sil && !a11yOn && !a11yFailed && targetSilenced()) {
                        if (++tgtSilSec >= 2) {
                            tgtSilSec = 0
                            a11yFloor = lvlHist.maxOrNull() ?: -60f
                            if (a11yBoost(true)) {
                                a11yPrevSrc = liveSource
                                slog('W', "目标应用被系统静音${recDetail()}，改无障碍录音：录音源 $liveSource → 6，改前输入 ${"%.0f".format(a11yFloor)}dB")
                                liveSource = 6; a11yChkSec = 0; a11yPeak = -200f
                                a11yThread = Thread({ try { while (true) { Thread.sleep(3000); a11yBoost(true, quiet = true) } } catch (_: InterruptedException) {} }, "a11y-keep").apply { isDaemon = true; start() }
                                if (!rebuildRec("改无障碍录音")) return
                                continue
                            } else a11yFailed = true
                        }
                    } else tgtSilSec = 0
                    // alpha.6：只在目标应用还在录音时计入判定窗口；目标停录了（用户也不说话），拿那段电平和切换前比必然偏低，会误判无效（M332BF 4 次误判 3 次）
                    if (a11yOn && a11yChkSec in 0..3 && !appRecording()) {
                        if (++a11yIdle >= 10) { slog('I', "无障碍录音：目标应用已停止录音，无法判定，保持源 6"); a11yChkSec = -1; a11yIdle = 0 }
                    } else if (a11yOn && a11yChkSec in 0..3) {
                        a11yIdle = 0
                        if (a11yChkSec > 0) a11yPeak = maxOf(a11yPeak, liveLevelDb)
                        if (++a11yChkSec > 3) {
                            if (a11yPeak < a11yFloor - 15f || a11yPeak < -80f) {
                                slog('W', "无障碍录音输入只有 ${"%.0f".format(a11yPeak)}dB（改前 ${"%.0f".format(a11yFloor)}dB），退回录音源 $a11yPrevSrc")
                                a11yThread?.interrupt(); a11yThread = null; a11yBoost(false); a11yFailed = true
                                if (a11yPrevSrc >= 0) liveSource = a11yPrevSrc
                                if (!rebuildRec("无障碍录音无效")) return
                                continue
                            } else { slog('I', "无障碍录音正常：输入 ${"%.0f".format(a11yPeak)}dB"); a11yChkSec = -1 }
                        }
                    }
                    if (sil) {
                        silSec++
                        if (!micHint.startsWith("麦克风被系统静音")) { micHint = "麦克风被系统静音（其他应用可能占用了录音）"; slog('W', micHint) }
                        if (silSec >= 2 && !a11yOn && !a11yFailed && appRecording()) {
                            // 被别的应用（微信等）抢了麦：先改无障碍录音，两边都不被静音；若再抢回来会让对方被静音（显示麦克风被占用）
                            silSec = 0
                            a11yFloor = lvlHist.maxOrNull() ?: -60f
                            if (a11yBoost(true)) {
                                a11yPrevSrc = liveSource
                                slog('W', "录音被其他应用抢占（${silWho()}），改无障碍录音：录音源 $liveSource → 6，改前输入 ${"%.0f".format(a11yFloor)}dB")
                                liveSource = 6; a11yChkSec = 0; a11yPeak = -200f
                                a11yThread = Thread({ try { while (true) { Thread.sleep(3000); a11yBoost(true, quiet = true) } } catch (_: InterruptedException) {} }, "a11y-keep").apply { isDaemon = true; start() }
                                if (!rebuildRec("改无障碍录音")) return
                                micHint = "麦克风被系统静音，正在重新接管"
                                continue
                            } else a11yFailed = true
                        }
                        if (silSec >= 2) {
                            // 被静音 2 s：重建录音（重建后 shell 成为最新开始的录音，实测 OPPO/一加 即可恢复）。
                            // 上次重建后恢复过 → 同一录音源再重建；上次重建没用 → 换下一种录音源（全试完就只重建）
                            silSec = 0
                            var next = liveSource
                            if (liveSource == android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION || !silGood) {
                                while (silTry < silOrder.size) { val c = silOrder[silTry++]; if (c != liveSource && c !in silTried) { next = c; break } }
                            }
                            silTried += liveSource
                            silGood = false
                            slog('W', "录音源 $liveSource 被系统静音（${silWho()}），" + (if (next == liveSource) "原录音源重建" else "改用录音源 $next 重建"))
                            liveSource = next
                            if (!rebuildRec("被系统静音")) return
                            micHint = "麦克风被系统静音，正在重新接管"
                            continue
                        }
                    } else {
                        if (silTried.isNotEmpty() && !silGood) { silGood = true; slog('I', "录音源 $liveSource 没有被静音（之前被静音：${silTried.joinToString(",")}）") }
                        silSec = 0
                        if (micHint.startsWith("麦克风被系统静音")) micHint = ""
                    }
                }
                rdMax = maxOf(rdMax, System.nanoTime() - tr0)
                if (liveExternal) {
                    micRing.write(inBuf, 0, hop)
                    var e2 = 0.0
                    for (i in 0 until hop) { val x = inBuf[i] / 32768f; e2 += x * x }
                    liveLevelDb = (10 * Math.log10(e2 / hop + 1e-12)).toFloat()
                    liveFrames++
                    prime = SR * livePrimeMs / 1000   // 引擎定档后会改
                    if (!primed && outRing.size() >= prime) primed = true
                    var k = if (primed) outRing.read(ext, 0, hop) else 0
                    val ob = outRing.size(); obMin = minOf(obMin, ob); obMax = maxOf(obMax, ob)
                    if (primed && k < hop) { underruns++; primed = false; slog('W', "断流#$underruns：输出缓冲只剩 ${k * 1000 / SR}ms"); engine?.takeIf { engineState == "运行" }?.noteTrouble("断流") }
                    // 积压超过「预缓冲 + 一块」就丢旧的回到预缓冲：启动时的积压不会一直挂着变成延迟（1.13 实测稳定在 0.7~1 s）
                    // alpha104：只在积压超 1 s 且最近 0.5 s 输出没有人声时才裁，裁掉的是静音而不是半句话
                    // alpha.6：阈值从「预缓冲 + 1 s」降到「预缓冲 + 一块」（至少 250 ms）。OnePlus 0.5 s 档 outBuf 长期 600–1150 ms，一直没到 1580 ms，多挂约半秒
                    val blkS = maxOf(SR / 4, SR * (livePrimeMs - 80).coerceAtLeast(0) / 1000)
                    if (outRing.size() > prime + blkS && System.nanoTime() - lastVoicedNs > 500_000_000L) { outRing.trimTo(prime); slog('I', "输出积压过多（静音段），丢到预缓冲 ${livePrimeMs}ms") }
                    var pk = 0
                    for (i in 0 until hop) { fxBuf[i] = if (i < k) ext[i] / 32768f else 0f; if (i < k) pk = maxOf(pk, kotlin.math.abs(ext[i].toInt())) }
                    if (pk > VOICED_PEAK) lastVoicedNs = System.nanoTime()
                    fx.process(fxBuf, hop); voiceTap(fxBuf, hop); mixSfx(fxBuf, hop)
                    for (i in 0 until hop) {
                        var v = (fxBuf[i] * 32767f).toInt().coerceIn(-32768, 32767)
                        if (v == 0) v = rnd.nextInt(5) - 2
                        tapBuf[i] = v.toShort()
                        val lo = (v and 0xff).toByte(); val hi = ((v shr 8) and 0xff).toByte()
                        val o = i * FRAME_BYTES
                        outBytes[o] = lo; outBytes[o + 1] = hi; outBytes[o + 2] = lo; outBytes[o + 3] = hi
                    }
                    val tw0 = System.nanoTime()
                    if (!writeOut(outBytes)) break
                    wrMax = maxOf(wrMax, System.nanoTime() - tw0)
                    val now = System.nanoTime()
                    if (now - statT >= 1_000_000_000L) {
                        val rc = recCount()
                        slog('M', "live/s mic=${"%.0f".format(liveLevelDb)}dB readMax=${rdMax / 1_000_000}ms writeMax=${wrMax / 1_000_000}ms " +
                            "outBuf=${obMin * 1000 / SR}~${obMax * 1000 / SR}ms 断流+${underruns - lastUnder} 积压mic=${micRing.size() * 1000 / SR}ms 目标在录=$rc")
                        if (rc != lastRec) { slog('I', "目标录音数 $lastRec → $rc ${recDetail()}"); lastRec = rc }
                        statT = now; rdMax = 0; wrMax = 0; obMin = Int.MAX_VALUE; obMax = 0; lastUnder = underruns
                    }
                    continue
                }
                var e = 0.0
                val ratio = Math.pow(2.0, liveSemis / 12.0).toFloat()
                for (i in 0 until hop) {
                    val x = inBuf[i] / 32768f
                    e += x * x
                    fxBuf[i] = if (ratio == 1f) x else shifter.process(x, ratio)
                }
                fx.process(fxBuf, hop); voiceTap(fxBuf, hop); mixSfx(fxBuf, hop)
                for (i in 0 until hop) {
                    val y = fxBuf[i]
                    // 微弱抖动，避免出现整段纯 0（微信会判定录音异常）
                    var v = (y * 32767f).toInt().coerceIn(-32768, 32767)
                    if (v == 0) v = rnd.nextInt(5) - 2
                    tapBuf[i] = v.toShort()
                    val lo = (v and 0xff).toByte(); val hi = ((v shr 8) and 0xff).toByte()
                    val o = i * FRAME_BYTES
                    outBytes[o] = lo; outBytes[o + 1] = hi; outBytes[o + 2] = lo; outBytes[o + 3] = hi
                }
                liveLevelDb = (10 * Math.log10(e / hop + 1e-12)).toFloat()
                liveFrames++
                if (!writeOut(outBytes)) break
            }
        } catch (t: Throwable) {
            lastError = describe(t)
            slog('E', "liveLoop", t)
        } finally {
            curRec = null
            a11yThread?.interrupt(); a11yThread = null
            if (a11yOn) a11yBoost(false)
            watchdog?.interrupt()
            runCatching { rec?.stop() }
            runCatching { aec?.release() }; runCatching { ns?.release() }; aec = null; ns = null
            runCatching { rec?.release() }
            if (running) disarm("live-end")
        }
    }

    // ------------------------------------------------------------ 注入轨道

    /** 本进程里建成功过的方式（manual-LL / manual-NO / src），下次优先用 */
    @Volatile private var trackWay: String? = null
    @Volatile private var lastTrackErr = ""

    /**
     * 建注入轨道。两种方式：
     *  - src：AudioPolicy.createAudioTrackSource(mix)。它内部建 AudioTrack 时不带 Context，
     *    Android 17（一加 PMS110、Redmi 24117RK2CC 实测）在 shell 进程里会抛 Cannot create AudioTrack。
     *  - manual：照 createAudioTrackSource 的做法手建，但带上 Context（AudioTrack.Builder.setContext，API 34+）：
     *    公开 setUsage(USAGE_VIRTUAL_SOURCE) + addTag("addr=<mix registration>")，格式取 mix 的格式，
     *    缓冲 max(minBuf, 2×10ms)，先 LOW_LATENCY 后 NONE（思路来自 SR-Voiceover-Roulette，MIT）。
     *    反射 setSystemUsage(VIRTUAL_SOURCE) 在 Android 17 上报 Invalid system usage 15，所以用公开 setUsage。
     * Android 17+ 先 manual 后 src；34–36 先 src（原有行为）后 manual；更老只有 src。
     */
    private fun newInjectTrack(pol: Any, mix: Any): AudioTrack? {
        lastTrackErr = ""
        val ways = ArrayList<String>()
        val manualOk = Build.VERSION.SDK_INT >= 34
        if (Build.VERSION.SDK_INT >= 37) { if (manualOk) ways += listOf("manual-LL", "manual-NO"); ways += "src" }
        else { ways += "src"; if (manualOk) ways += listOf("manual-LL", "manual-NO") }
        trackWay?.let { w -> if (ways.remove(w)) ways.add(0, w) }
        val errs = ArrayList<String>()
        for (w in ways) {
            val t = runCatching { if (w == "src") srcTrack(pol, mix) else manualTrack(mix, w == "manual-LL") }
                .onFailure { errs += "$w: ${describe(it)}" }.getOrNull()
            if (t != null && t.state == AudioTrack.STATE_INITIALIZED) {
                if (trackWay != w) slog('I', "注入轨道建立方式：$w" + if (errs.isEmpty()) "" else "（之前失败：${errs.joinToString("；")}）")
                trackWay = w
                return t
            }
            if (t != null) { errs += "$w: state=${t.state}"; runCatching { t.release() } }
        }
        lastTrackErr = errs.joinToString("；")
        slog('E', "注入轨道建立失败：$lastTrackErr")
        return null
    }

    /** getRegistration / setContext 走反射，保险起见放开隐藏 API（shell 进程一般本来就不限制） */
    private val hiddenApiOnce by lazy {
        runCatching {
            val vm = Class.forName("dalvik.system.VMRuntime")
            val rt = vm.getMethod("getRuntime").invoke(null)
            vm.getMethod("setHiddenApiExemptions", Array<String>::class.java).invoke(rt, arrayOf("L"))
        }
        Unit
    }

    private fun srcTrack(pol: Any, mix: Any): AudioTrack? {
        val polCls = Class.forName("android.media.audiopolicy.AudioPolicy")
        val mixCls = Class.forName("android.media.audiopolicy.AudioMix")
        return polCls.getMethod("createAudioTrackSource", mixCls).invoke(pol, mix) as AudioTrack?
    }

    private fun manualTrack(mix: Any, lowLatency: Boolean): AudioTrack {
        val c = shellCtx ?: throw IllegalStateException("没有 Context")
        hiddenApiOnce
        val mixCls = Class.forName("android.media.audiopolicy.AudioMix")
        val reg = mixCls.getMethod("getRegistration").invoke(mix) as String?
        if (reg.isNullOrEmpty()) throw IllegalStateException("拿不到 mix registration")
        val mf = mixCls.getMethod("getFormat").invoke(mix) as AudioFormat
        val ch = if (mf.channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val tf = AudioFormat.Builder().setSampleRate(mf.sampleRate).setChannelMask(ch).setEncoding(mf.encoding).build()
        val ab = android.media.AudioAttributes.Builder().setUsage(USAGE_VIRTUAL_SOURCE)
        android.media.AudioAttributes.Builder::class.java.getMethod("addTag", String::class.java)
            .invoke(ab, if (reg.startsWith("addr=")) reg else "addr=$reg")
        val minBuf = AudioTrack.getMinBufferSize(tf.sampleRate, ch, tf.encoding)
        val bps = if (tf.encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
        val buf = maxOf(minBuf, tf.sampleRate / 100 * (if (ch == AudioFormat.CHANNEL_OUT_MONO) 1 else 2) * bps * 2)
        val b = AudioTrack.Builder().setAudioAttributes(ab.build()).setAudioFormat(tf)
            .setBufferSizeInBytes(buf).setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(if (lowLatency) AudioTrack.PERFORMANCE_MODE_LOW_LATENCY else AudioTrack.PERFORMANCE_MODE_NONE)
        AudioTrack.Builder::class.java.getMethod("setContext", Context::class.java).invoke(b, c)
        return b.build()
    }

    /** 注入轨道失效（系统音频服务重启等）时从同一个策略重建一条；失败返回 false */
    private fun rebuildTrack(): Boolean = runCatching {
        val pol = policy ?: return false
        val mix = mixObj ?: return false
        runCatching { track?.stop() }; runCatching { track?.release() }
        val t = newInjectTrack(pol, mix)
        if (t == null || t.state != AudioTrack.STATE_INITIALIZED) { runCatching { t?.release() }; track = null; false }
        else { t.play(); track = t; slog('W', "注入轨道已重建"); true }
    }.getOrElse { slog('W', "注入轨道重建失败", it); false }

    /** 写注入轨道；写失败先重建一次再写，仍失败返回 false（结束变声） */
    private fun writeOut(b: ByteArray): Boolean {
        var t = track ?: return false
        var w = t.write(b, 0, b.size)
        if (w < 0) {
            slog('W', "AudioTrack.write=$w，尝试重建注入轨道")
            if (!rebuildTrack()) { lastError = "AudioTrack.write=$w"; return false }
            t = track ?: return false
            w = t.write(b, 0, b.size)
            if (w < 0) { lastError = "AudioTrack.write=$w"; return false }
        }
        return true
    }

    @Synchronized
    private fun disarm(why: String) {
        if (!running && policy == null) return
        running = false
        setMonitor(false)
        synchronized(capLock) { capBuf = null }
        sfx = null
        slog('I', "DISARM ($why)")
        micHint = ""
        engine?.stop()
        val w = worker
        worker = null
        if (w != null && w !== Thread.currentThread()) runCatching { w.join(500) }
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        unregister()
        phase = "IDLE"
    }

    /** 注入失败时把系统音频相关 logcat 与音频加固状态写进日志（shell 才能读到 audioserver 的日志） */
    private var diagDumped = 0
    private fun dumpAudioDiag() {
        if (diagDumped >= 2) return
        diagDumped++
        fun run(vararg cmd: String, max: Int): List<String> = runCatching {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readLines()
            p.waitFor(); out.takeLast(max)
        }.getOrElse { listOf("（${cmd.joinToString(" ")} 失败：${it.message}）") }
        val re = Regex("AudioFlinger|AudioPolicy|APM_|AudioTrack|AudioHardening|audio_hw|AudioSystem|MicMask", RegexOption.IGNORE_CASE)
        val lc = run("logcat", "-d", "-v", "threadtime", "-t", "1500", max = 1500).filter { re.containsMatchIn(it) }.takeLast(80)
        slog('W', "系统音频诊断（注入失败时）：sdk=${Build.VERSION.SDK_INT} 相关 logcat ${lc.size} 行\n" + lc.joinToString("\n"))
        if (Build.VERSION.SDK_INT >= 37) {
            val hd = run("sh", "-c", "dumpsys audio | grep -i -m 20 -E 'hardening|AudioHardening'", max = 20)
            slog('W', "音频加固状态（Android 17）：\n" + hd.joinToString("\n"))
        }
    }

    private fun unregister() {
        val p = policy ?: return
        policy = null
        runCatching {
            val polCls = Class.forName("android.media.audiopolicy.AudioPolicy")
            AudioManager::class.java.getMethod("unregisterAudioPolicy", polCls).invoke(am, p)
        }.onFailure { slog('W', "unregisterAudioPolicy failed", it) }
    }

    /** 目标 uid 现在有没有在录音（shell 进程能看到 clientUid） */
    /** 其他应用的录音数（不算 shell 自己和 MicMask；系统隐藏 uid 时只能全算上） */
    /** 当前所有录音会话：uid、包名、录音源、是否被系统静音（排查 QQ 提示「麦克风没有声音」用） */
    private fun recDetail(): String = runCatching {
        val list = am?.activeRecordingConfigurations ?: return ""
        val c = android.media.AudioRecordingConfiguration::class.java
        fun g(o: Any, m: String) = runCatching { c.getMethod(m).invoke(o) }.getOrNull()
        val pm = shellCtx?.packageManager
        list.joinToString(prefix = "[", postfix = "]") { r ->
            val uid = g(r, "getClientUid") as? Int ?: -1
            val pkg = g(r, "getClientPackageName") ?: runCatching { pm?.getNameForUid(uid) }.getOrNull()
            "uid=$uid $pkg src=${g(r, "getClientAudioSource")} 静音=${g(r, "isClientSilenced")} 设备=${(g(r, "getAudioDevice") as? android.media.AudioDeviceInfo)?.type}"
        }
    }.getOrDefault("")

    private fun recCount(): Int = runCatching {
        val list = am?.activeRecordingConfigurations ?: return 0
        val getUid = android.media.AudioRecordingConfiguration::class.java.getMethod("getClientUid")
        list.count { val u = getUid.invoke(it) as Int; u != Process.myUid() && u != ShellAudioRecord.REC_UID && (u != callerUid || u < 0) }
    }.getOrDefault(0)

    /**
     * 目标有没有在录音。优先按 uid 认；如果系统把 uid 藏起来了（全是 -1），
     * 退而求其次：ARM 之后录音会话数变多了，就当是目标开始录了。
     */
    private fun isTargetRecording(): Boolean {
        val m = am ?: return false
        return runCatching {
            val list = m.activeRecordingConfigurations
            val getUid = android.media.AudioRecordingConfiguration::class.java.getMethod("getClientUid")
            val us = list.map { getUid.invoke(it) as Int }
            when {
                scopeAll && us.any { it >= 10000 && it != callerUid } -> { trigger = "auto"; true }
                us.any { it in targetUids } -> { trigger = "uid"; true }
                us.none { it >= 10000 } && list.size > baseRecCount -> { trigger = "count"; true }
                else -> false
            }
        }.getOrDefault(false)
    }

    // ---- 性能信息（shell 身份可读 /proc/stat 与 thermal；App 进程在 Android 8+ 读不到）----
    private var cpuPrevIdle = 0L; private var cpuPrevTotal = 0L
    private fun cpuPct(): Float = runCatching {
        val f = java.io.File("/proc/stat").bufferedReader().use { it.readLine() }.trim().split(Regex("\\s+")).drop(1).map { it.toLong() }
        val idle = f[3] + f.getOrElse(4) { 0 }; val total = f.sum()
        val di = idle - cpuPrevIdle; val dt = total - cpuPrevTotal
        cpuPrevIdle = idle; cpuPrevTotal = total
        if (dt <= 0) -1f else (100f * (dt - di) / dt)
    }.getOrDefault(-1f)
    private val tempZones: List<java.io.File> by lazy {
        (java.io.File("/sys/class/thermal").listFiles() ?: emptyArray()).filter { it.name.startsWith("thermal_zone") }.filter { z ->
            val t = runCatching { java.io.File(z, "type").readText().trim().lowercase() }.getOrDefault("")
            t.contains("cpu") || t.contains("soc") || t.contains("tsens") || t.contains("mtktscpu")
        }.map { java.io.File(it, "temp") }
    }
    private fun cpuTemp(): Float {
        var m = -1f
        for (z in tempZones) { val v = runCatching { z.readText().trim().toFloat() }.getOrNull() ?: continue
            val c = if (v > 1000) v / 1000f else v; if (c in 1f..150f && c > m) m = c }
        return m
    }
    private fun selfRssMb(): Int = runCatching {
        java.io.File("/proc/self/status").readLines().first { it.startsWith("VmRSS") }.filter { it.isDigit() }.toInt() / 1024
    }.getOrDefault(-1)

    private fun status(): String {
        val total = pcm.size
        val pos = playPos.coerceAtLeast(0)
        return listOf(
            "phase=$phase",
            "uid=${Process.myUid()}/rec${ShellAudioRecord.REC_UID}",
            "perm=${if (hasRoutingPermission()) 1 else 0}",
            "target=${if (scopeAll) "auto" else targetUids.joinToString(",")}",
            "trig=$trigger",
            "recs=${recCount()}",
            "seenRec=${if (targetSeenRecording) 1 else 0}",
            "pos=$pos",
            "total=$total",
            "live=${"%.0f".format(liveLevelDb)}dB/${liveFrames}",
            "ext=${if (liveExternal) 1 else 0}",
            "outBuf=${outRing.size()}",
            "under=$underruns",
            "eng=${engine?.takeIf { engineState == "运行" }?.stats() ?: engineState}",
            "bench=${engine?.benchSummary ?: ""}",
            "perf=${engine?.takeIf { engineState == "运行" }?.perf() ?: ""}",
            "gen=$engGen",
            "recPath=$recPath",
            "mic=$micHint",
            "err=$lastError",
        ).joinToString("|")
    }

    private fun readAll(pfd: ParcelFileDescriptor): ByteArray =
        pfd.use { FileInputStream(it.fileDescriptor).use { s -> s.readBytes() } }

    private fun describe(t: Throwable): String {
        val c = generateSequence(t) { it.cause }.last()
        return "${c.javaClass.simpleName}: ${c.message ?: ""}"
    }

    /** 包名伪装成 com.android.shell，和 uid 2000 对得上（GameMicLab 同款做法） */
    private class ShellContext(base: Context) : ContextWrapper(base) {
        private val isShell = ShellAudioRecord.REC_UID == 2000
        override fun getPackageName(): String = if (isShell) "com.android.shell" else super.getPackageName()
        override fun getOpPackageName(): String = if (isShell) "com.android.shell" else super.getOpPackageName()
        override fun getAttributionSource(): AttributionSource {
            if (!isShell || Build.VERSION.SDK_INT < 31) return super.getAttributionSource()
            return AttributionSource.Builder(ShellAudioRecord.REC_UID).setPackageName("com.android.shell").build()
        }
        override fun getApplicationContext(): Context = this
    }
}
