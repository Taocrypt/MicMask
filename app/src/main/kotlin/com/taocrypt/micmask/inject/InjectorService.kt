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
    /**
     * 系统权限模式（[inApp]=true）：App 已是 priv-app、自带 MODIFY_AUDIO_ROUTING，服务直接在 App 进程里实例化。
     * 与 shell 进程的差别只在：模型目录改到 App 私有目录、不 System.exit、不接管 App 日志、本机试听不把自己设成目标。
     * Shizuku / ROOT 模式走上面两个构造，inApp=false，行为不变。
     */
    constructor(context: Context, inApp: Boolean) : super() {
        ctx = context; this.inApp = inApp
        if (inApp) { ShellAudioRecord.pkgOverride = context.packageName; runCatching { java.io.File(MODEL_DIR).mkdirs() } }
    }
    @Volatile private var inApp = false

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
        /** 实时 AI 高级参数：tier(int) gateDb(float) featThreads(int) synThreads(int) …（见 readTune；末尾可带声纹门限 on(int) thr(float) n(int) n×float，其后可带软件回声消除 aecMode(int 0关 1开)，再其后可带声纹聚焦 focus(int 0关 1开) printJson(String，可为 null)） */
        const val TX_LIVE_TUNE = 24
        /** 变声中换音色：新合成器已由 TX_LIVE_MODELS 送到，热切换推理：旧音色继续出声到新合成器就绪再交叉淡化换上；热切换做不了才退回停下重载（2.1.1-alpha.10） */
        const val TX_LIVE_RELOAD = 25
        /**
         * 变声中录入声纹门限（2.1.1-alpha.6）：从实时链路（与打分同一录音源 / 设备 / 系统 AEC·NS）取本人声音，在 shell 里算嵌入。
         * 请求 op(int)：1 开始（其后 secs(int)）、0 取消、2 查询。应答：magic(int=[VP_ENROLL_MAGIC]) state(int) sec(int) levelDb(float) err(int) detail(String)；
         * state=3（完成）时再跟 consistency(float) windows(int) n(int) n×float。state：0 空闲 1 录音中 2 计算中 3 完成 4 失败；
         * err：0 无 1 未在变声 2 声纹模型未送达 3 有效语音太少 4 计算出错 5 已取消
         */
        const val TX_VP_ENROLL = 26
        const val VP_ENROLL_MAGIC = 0x56504531
        /**
         * 传音（2.1.1-alpha.11）：op(int) 1 = 接收模式开关 on(int)（在 TX_LIVE_START 之前设，影响预缓冲与积压裁剪）；
         * 2 = 发送取点开关 on(int)（把写进注入轨道前的 tapBuf 复制进 linkRing）；3 = 拉取取点 PCM max(int) → byte[]（48k 单声道 s16le）。
         * 应答先写 [LINK_MAGIC]，App 据此判断 shell 是否支持（旧服务不认识这个事务码，应答为空）。
         */
        const val TX_LINK = 27
        const val LINK_MAGIC = 0x4C4E4B31
        /** 实时变声写注入轨道单次最多等这么久（alpha.4 小米实测正常阻塞写 p50≈45ms、偶发 ≤ 340ms） */
        const val WRITE_WAIT_MS = 400L
        /** 已判定卡住后每次写只等这么久，主循环继续按麦克风节拍跑 */
        const val STALL_WAIT_MS = 5L
        /** TX_LIVE_START 的 source 高位：强制兼容录音 */
        const val REC_COMPAT_FLAG = 0x10000
        const val TX_DESTROY = 16777115

        /** AudioAttributes.USAGE_VIRTUAL_SOURCE（隐藏常量） */
        const val USAGE_VIRTUAL_SOURCE = 15
        const val SR = 48000
        const val FRAME_BYTES = 4 // 立体声 16bit
        /** 耳返目标排队（ms）：盖住注入写出 20~40 ms 的抖动，又不明显加耳返延迟（2.1.1-alpha.7） */
        const val MON_TARGET_MS = 40
        /** AEC 参考的提前量（ms）：按时间戳取「录音时刻 + 这么多」的出声位置，让参考略早于回声（AEC3 只能消晚于参考的回声），吸收时间戳/录音时刻的小误差 */
        const val AEC_REF_LEAD_MS = 20
        /** 每次写 10ms */
        const val CHUNK_BYTES = SR / 100 * FRAME_BYTES
        /** 目标开始录音后，先垫一小段静音再放（QQ 开头常会丢一点） */
        const val LEAD_SILENCE_MS = 150
        /** 进入通话模式后等目标应用开麦的秒数，超过才撤掉注入重新接管（alpha.3 为 2 s；alpha.9 曾改 5/10 s，alpha.10 改回 2 s） */
        const val CALL_WAIT_S = 2
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
                    // 系统权限模式服务在 App 进程里：只停注入，不能退出进程
                    if (inApp) { disarm("destroy"); reply?.writeNoException(); return true }
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
                TX_LINK -> {
                    reply?.writeNoException()
                    reply?.writeInt(LINK_MAGIC)
                    when (data.readInt()) {
                        1 -> { linkRx = data.readInt() == 1 }
                        2 -> { val on = data.readInt() == 1; linkRing.clear(); if (on != linkTap) slog('I', "传音：发送取点 ${if (on) "开" else "关"}"); linkTap = on }
                        // 2.1.1-alpha.13：传音接收时按链路自适应改预缓冲（20–400 ms，只在传音接收模式生效，立刻作用于下一帧）
                        4 -> { val ms = data.readInt().coerceIn(20, 400); if (linkRx && ms != livePrimeMs) { slog('I', "传音：接收预缓冲 ${livePrimeMs}ms → ${ms}ms（随抖动 / 传输方式自适应）"); livePrimeMs = ms }; reply?.writeInt(1) }
                        // 2.1.1-alpha.13：发送取点来源：0 = 注入前输出（变声后的声音），1 = 真麦（传音接收注入时用，避免把收到的声音发回去）
                        5 -> { val m = data.readInt() == 1; if (m != linkTapMic) { linkRing.clear(); slog('I', "传音：发送取点来源 → ${if (m) "真麦（接收注入中，防回环）" else "注入前输出"}") }; linkTapMic = m; reply?.writeInt(1) }
                        3 -> {
                            val max = data.readInt().coerceIn(0, SR)
                            val tmp = ShortArray(minOf(max, linkRing.size()))
                            val k = linkRing.read(tmp, 0, tmp.size)
                            val bytes = ByteArray(k * 2)
                            for (i in 0 until k) { val v = tmp[i].toInt(); bytes[2 * i] = v.toByte(); bytes[2 * i + 1] = (v shr 8).toByte() }
                            reply?.writeByteArray(bytes)
                        }
                    }
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
                    val primeReq = if (data.dataAvail() >= 4) data.readInt().coerceIn(20, 1500) else 100
                    // 传音接收：音源是远端音频，不沿用 AI 引擎定档的预缓冲（tierPrimeMs 可能是上一次 AI 变声留下的 330 ms+）
                    livePrimeMs = if (linkRx) primeReq else maxOf(primeReq, tierPrimeMs)
                    val rvcHere = data.dataAvail() >= 4 && data.readInt() == 1
                    liveRvcHere = rvcHere
                    if (data.dataAvail() >= 16) readTune(data)
                    micRing.clear(); outRing.clear()
                    // 系统权限模式：真麦录音也是 App uid，目标=自身（本机试听）会把注入声录回来，换成不存在的占位 uid
                    if (inApp && uids.contains(Process.myUid())) {
                        for (i in uids.indices) if (uids[i] == Process.myUid()) uids[i] = Process.LAST_APPLICATION_UID
                        slog('I', "系统权限模式：目标是 MicMask 自身（本机试听），改用占位 uid ${Process.LAST_APPLICATION_UID}，避免录回注入声")
                    }
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
                TX_VP_ENROLL -> {
                    val op = data.readInt()
                    when (op) {
                        1 -> vpEnrollStart(if (data.dataAvail() >= 4) data.readInt() else com.taocrypt.micmask.Voiceprint.ENROLL_S)
                        0 -> vpEnrollCancel()
                    }
                    reply?.writeNoException()
                    reply?.writeInt(VP_ENROLL_MAGIC)
                    val st = vpEnState
                    reply?.writeInt(st); reply?.writeInt(vpEnPos / SR); reply?.writeFloat(vpEnLevel); reply?.writeInt(vpEnErr); reply?.writeString(vpEnDetail)
                    if (st == 3) {
                        val e = vpEnEmb ?: FloatArray(0)
                        reply?.writeFloat(vpEnCons); reply?.writeInt(vpEnWin); reply?.writeInt(e.size); e.forEach { reply?.writeFloat(it) }
                    }
                    return true
                }
                TX_LIVE_RELOAD -> {
                    val ok = running && liveExternal && engine != null
                    slog('I', "变声中换音色：${if (ok) "热切换（旧音色继续出声，新合成器就绪后交叉淡化换上）" else "未在 AI 变声，忽略"}")
                    if (ok) hotSwapEngine()
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

        armGen++; cycling = false
        buildAndRegister(uids, c, audioManager)?.let { return it }

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

    /** 建 AudioPolicy + mix 并注册、建注入轨道；成功返回 null。arm 和「通话开始后重新接管」共用 */
    private fun buildAndRegister(uids: IntArray, c: Context, audioManager: AudioManager): String? {
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

    /** 前台应用是变声目标时返回包名，否则 null。手动目标：uid 在目标列表；自动识别：第三方应用（非 MicMask、非系统界面/权限弹窗/桌面） */
    private fun fgTarget(): String? = runCatching {
        val pkg = fgPkg().takeIf { it != "?" && it.isNotBlank() } ?: return null
        val uid = runCatching { shellCtx?.packageManager?.getApplicationInfo(pkg, 0)?.uid }.getOrNull() ?: return null
        if (uid < 10000 || uid == callerUid || uid == Process.myUid() || uid == ShellAudioRecord.REC_UID || pkg == ctx?.packageName) return null
        if (!scopeAll) return if (uid in targetUids) pkg else null
        val p = pkg.lowercase()
        if (p.contains("systemui") || p.contains("permissioncontroller") || p.contains("packageinstaller") || p.contains("launcher")) null else pkg
    }.getOrNull()

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

    /**
     * 后台诊断（每 10 s 一次）：cgroup、大核当前频率/上限、温度、App 是否在电池白名单、前台应用。读不到的项写 ?。
     * 2.1.1-alpha.6：shell 读不到 /sys/class/thermal（alpha.4 全是 ?），温度依次试：thermal zone → 系统温度服务（binder，
     * IThermalService.getCurrentTemperatures）→ /sys/class/power_supply/battery/temp → dumpsys thermalservice（最多 60 s 一次，结果缓存）。
     * 白名单状态 60 s 查一次。都是 binder / 小文件读取，dumpsys 只在前三种都不行时才用
     */
    private fun bgDiag(): String {
        fun rd(path: String) = runCatching { java.io.File(path).readText().trim() }.getOrNull()
        val cg = rd("/proc/self/cgroup")?.lines()?.filter { it.isNotBlank() }?.joinToString(";") { it.substringAfter(':') } ?: "?"
        val big = com.taocrypt.micmask.CpuPin.bigCores()
        val cores = (if (big.isEmpty()) (0 until Runtime.getRuntime().availableProcessors()).toList() else big.sorted())
        val freq = cores.joinToString(" ") { c ->
            val cur = rd("/sys/devices/system/cpu/cpu$c/cpufreq/scaling_cur_freq")?.toLongOrNull()?.div(1000)
            val mx = rd("/sys/devices/system/cpu/cpu$c/cpufreq/scaling_max_freq")?.toLongOrNull()?.div(1000)
            "cpu$c=${cur ?: "?"}/${mx ?: "?"}"
        }
        return "cgroup=$cg | 频率MHz(当前/上限) $freq | 温度 ${diagTemp()} | 温控状态 ${thermalStatus()} | App电池白名单 ${appWhitelisted()} | 前台 ${fgPkg()}"
    }

    private val diagBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    /** 后台诊断放到单独线程跑（上一次没跑完就跳过），不占用实时读麦线程；行尾带上本次诊断耗时 */
    private fun diagAsync() {
        if (!diagBusy.compareAndSet(false, true)) return
        Thread({
            try { val t0 = System.nanoTime(); val d = bgDiag(); slog('I', "后台诊断：$d | 诊断耗时 ${(System.nanoTime() - t0) / 1_000_000}ms") }
            catch (_: Throwable) {} finally { diagBusy.set(false) }
        }, "micmask-diag").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    private var tempSrcBad = 0          // 位：1 zone 2 hal 4 battery
    private var zoneMiss = 0
    private var dumpTempT = 0L
    private var dumpTempV = "?"

    private fun diagTemp(): String {
        fun rd(path: String) = runCatching { java.io.File(path).readText().trim() }.getOrNull()
        fun c(v: Float) = if (v > 1000f) v / 1000f else v
        if (tempSrcBad and 1 == 0) {
            val z = zoneTemp()
            if (z != null) return "%.1f°C(%s)".format(z.first, z.second)
            // 有 zone 但全无效时下一次再试（可能只是暂时无效），连续 3 次都没有才放弃 zone
            if (++zoneMiss >= 3) tempSrcBad = tempSrcBad or 1
        }
        if (tempSrcBad and 2 == 0) {
            val h = halTemps()
            if (h != null) return h
            tempSrcBad = tempSrcBad or 2
        }
        if (tempSrcBad and 4 == 0) {
            val b = rd("/sys/class/power_supply/battery/temp")?.toFloatOrNull()
            if (b != null && b / 10f in -20f..80f) return "电池 %.1f°C(battery)".format(b / 10f)
            tempSrcBad = tempSrcBad or 4
        }
        val now = System.nanoTime()
        if (dumpTempT == 0L || now - dumpTempT > 60_000_000_000L) {
            dumpTempT = now
            dumpTempV = runCatching {
                val p = ProcessBuilder("dumpsys", "thermalservice").redirectErrorStream(true).start()
                val lines = p.inputStream.bufferedReader().useLines { it.take(200).toList() }
                p.waitFor()
                val re = Regex("mValue=([0-9.]+), mType=(\\d+), mName=([^,]+)")
                val ts = lines.dropWhile { !it.contains("Current temperatures from HAL") }.drop(1).takeWhile { it.contains("Temperature{") }
                    .mapNotNull { re.find(it)?.destructured?.let { (v, t, n) -> Triple(v.toFloat(), t.toInt(), n.trim()) } }
                fmtTemps(ts) ?: "?"
            }.getOrDefault("?") + "(dumpsys)"
        }
        return dumpTempV
    }

    /** 类型：0 CPU 1 GPU 2 电池 3 皮肤（android.os.Temperature） */
    private fun fmtTemps(ts: List<Triple<Float, Int, String>>): String? {
        if (ts.isEmpty()) return null
        val ok = ts.filter { tempValid(it.third, it.first) }
        fun mx(type: Int) = ok.filter { it.second == type }.maxByOrNull { it.first }
        val parts = listOfNotNull(mx(0)?.let { "CPU %.1f(%s)".format(it.first, it.third) }, mx(3)?.let { "皮肤 %.1f(%s)".format(it.first, it.third) }, mx(2)?.let { "电池 %.1f(%s)".format(it.first, it.third) })
        return if (parts.isEmpty()) ok.maxByOrNull { it.first }?.let { "%.1f°C(%s)".format(it.first, it.third) } else parts.joinToString(" ") + "°C"
    }

    private var thermalSvc: Any? = null
    /** 系统温度服务（隐藏 API IThermalService，shell 有 DEVICE_POWER）；不可用返回 null */
    private fun halTemps(): String? = runCatching {
        val svc = thermalSvc ?: run {
            val b = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "thermalservice") as android.os.IBinder
            Class.forName("android.os.IThermalService\$Stub").getMethod("asInterface", android.os.IBinder::class.java).invoke(null, b)
        }.also { thermalSvc = it }
        @Suppress("UNCHECKED_CAST")
        val arr = svc!!.javaClass.getMethod("getCurrentTemperatures").invoke(svc) as Array<Any>
        val tc = Class.forName("android.os.Temperature")
        val ts = arr.map { t -> Triple((tc.getMethod("getValue").invoke(t) as Float), (tc.getMethod("getType").invoke(t) as Int), (tc.getMethod("getName").invoke(t) as String)) }
        fmtTemps(ts)
    }.getOrNull()

    private fun thermalStatus(): String = runCatching {
        if (Build.VERSION.SDK_INT < 29) return@runCatching "?"
        val pm = shellCtx?.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return@runCatching "?"
        val st = pm.currentThermalStatus
        val hr = if (Build.VERSION.SDK_INT >= 30) runCatching { pm.getThermalHeadroom(10) }.getOrDefault(Float.NaN) else Float.NaN
        "$st" + if (hr.isNaN()) "" else " 余量%.2f".format(hr)
    }.getOrDefault("?")

    private var wlT = 0L
    private var wlV = "?"
    /** App（不是 shell 进程）是否在电池优化白名单；60 s 查一次 */
    private fun appWhitelisted(): String {
        val now = System.nanoTime()
        if (wlT != 0L && now - wlT < 60_000_000_000L) return wlV
        wlT = now
        wlV = runCatching {
            val pkg = ctx?.packageName ?: return@runCatching "?"
            val pm = (shellCtx ?: ctx)?.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return@runCatching "?"
            if (pm.isIgnoringBatteryOptimizations(pkg)) "是" else "否"
        }.getOrDefault("?")
        return wlV
    }

    /** 前台应用包名：ActivityTaskManager 隐藏接口（API 31+ getFocusedRootTaskInfo / 更老 getFocusedStackInfo）→ 退回前台进程列表 */
    private fun fgPkg(): String {
        runCatching {
            val atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
            val info = runCatching { atm.javaClass.getMethod("getFocusedRootTaskInfo").invoke(atm) }.getOrNull()
                ?: runCatching { atm.javaClass.getMethod("getFocusedStackInfo").invoke(atm) }.getOrNull()
            val cn = info?.let { i -> runCatching { i.javaClass.getField("topActivity").get(i) }.getOrNull() } as? android.content.ComponentName
            if (cn != null) return cn.packageName
        }
        return runCatching {
            val am2 = (shellCtx ?: ctx)?.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return@runCatching "?"
            am2.runningAppProcesses?.firstOrNull { it.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND && it.pid != Process.myPid() }
                ?.let { it.pkgList?.firstOrNull() ?: it.processName } ?: "?"
        }.getOrDefault("?")
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

    /** shell 进程：/data/local/tmp/micmask-models；系统权限模式（App 进程）写不了那里，改用 App 的 no_backup 目录 */
    private val MODEL_DIR: String get() =
        if (inApp && ctx != null) java.io.File(ctx.noBackupFilesDir, "injector-models").absolutePath else "/data/local/tmp/micmask-models"
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
    @Volatile private var tuneDnIn = false
    @Volatile private var tuneDnOut = false
    /** 输入麦克风 0=自动 1=机身麦 2=耳机麦（旧版 App 不传 → 自动）；开始变声时生效 */
    @Volatile private var tuneMic = 0
    /** 声纹门限：开关、阈值、录入的声纹嵌入（旧版 App 不传 → 关）；即时生效 */
    @Volatile private var tuneVpOn = false
    @Volatile private var tuneVpThr = com.taocrypt.micmask.SpeakerGate.DEFAULT_THR
    @Volatile private var tuneVpEmb: FloatArray? = null
    /** 软件回声消除（WebRTC AEC3）0=关 1=开（旧版 App 不传 → 关）；接在声纹门限字段之后，变声中即时生效 */
    @Volatile private var tuneAec = 0
    /** 声纹聚焦（Alkove）0=关 1=开（旧版 App 不传 → 关）；接在 aecMode 之后，变声中即时生效。与声纹门限互斥：聚焦开时门限不生效 */
    @Volatile private var tuneFocus = 0
    /** 通话时自动重新接管（cyclePolicy）开关，默认开（旧版 App 不传 → 开） */
    @Volatile private var tuneCallRecycle = true
    /** 声纹聚焦：shell 侧处理器（模型在 MODEL_DIR，声纹持久化到 MODEL_DIR/alkove/voiceprint.json），跨引擎重建保留 */
    private val focus by lazy { com.taocrypt.micmask.AlkoveFocus(java.io.File(MODEL_DIR)) }
    /** 当前录音指定的输入设备 id（-1 = 没指定） */
    @Volatile private var curMicId = -1
    /** 按「输入麦克风」设置选设备：1=机身麦 2=耳机麦（没插就退回机身麦）0=自动，有 USB/有线耳机麦优先 */
    private fun pickMic(warn: Boolean): android.media.AudioDeviceInfo? {
        val mics = am?.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS) ?: return null
        val head = mics.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET || it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET }
        val builtin = mics.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC }
        if (warn && tuneMic == 2 && head == null) slog('W', "输入麦克风选了耳机麦，但没找到 USB/有线耳机麦 → 用机身麦")
        return when (tuneMic) { 1 -> builtin ?: head; else -> head ?: builtin }
    }
    /** 读高级参数并套到现有引擎；返回是否需要重载 */
    private fun readTune(d: android.os.Parcel): Boolean {
        tuneTier = d.readInt(); tuneGate = d.readFloat(); tuneFeat = d.readInt(); tuneSyn = d.readInt()
        if (d.dataAvail() >= 4) tunePin = d.readInt() != 0
        if (d.dataAvail() >= 4) tuneIdx = d.readFloat()
        if (d.dataAvail() >= 4) tuneVoc = d.readInt()
        if (d.dataAvail() >= 4) tuneRms = d.readFloat()
        if (d.dataAvail() >= 4) tuneFormant = d.readFloat()
        if (d.dataAvail() >= 4) tunePl = d.readInt() != 0
        if (d.dataAvail() >= 4) tuneDnIn = d.readInt() != 0
        if (d.dataAvail() >= 4) tuneDnOut = d.readInt() != 0
        if (d.dataAvail() >= 4) tuneMic = d.readInt().coerceIn(0, 2)
        if (d.dataAvail() >= 12) {
            tuneVpOn = d.readInt() != 0; tuneVpThr = d.readFloat()
            val n = d.readInt()
            tuneVpEmb = if (n in 1..1024 && d.dataAvail() >= n * 4) FloatArray(n) { d.readFloat() } else null
            // 软件回声消除：追加在声纹门限之后（只有声纹段完整读完才往下读，避免错位）
            if ((n == 0 || tuneVpEmb != null) && d.dataAvail() >= 4) {
                val a = d.readInt().coerceIn(0, 1)
                if (a != tuneAec) slog('I', "软件回声消除（AEC3）→ ${if (a != 0) "开" else "关"}")
                tuneAec = a
                // 声纹聚焦（Alkove）：追加在 aecMode 之后
                if (d.dataAvail() >= 4) {
                    tuneFocus = d.readInt().coerceIn(0, 1)
                    val json = if (d.dataAvail() >= 4) d.readString() else null
                    focus.configure(tuneFocus != 0, json, updatePrint = true)
                    slog('I', "声纹聚焦参数：${if (tuneFocus != 0) "开" else "关"} 声纹=${if (json.isNullOrEmpty()) "未注册" else "${json.length} 字节"} 模型=${
                        if (com.taocrypt.micmask.AlkoveStore.FILES.all { java.io.File(MODEL_DIR, it).isFile }) "已送达" else "未送达"}" +
                        if (tuneFocus != 0 && tuneVpOn) "（与声纹门限互斥：门限本次不生效）" else "")
                    // 通话时自动重新接管：追加在声纹聚焦之后（旧版 App 不传 → 开）
                    if (d.dataAvail() >= 4) {
                        val cr = d.readInt() != 0
                        if (cr != tuneCallRecycle) slog('I', "通话接管 → ${if (cr) "开" else "关"}")
                        tuneCallRecycle = cr
                    }
                }
            }
            slog('I', "声纹门限参数：${if (tuneVpOn) "开" else "关"} 放行阈值=$tuneVpThr 拦截阈值=${"%.2f".format(com.taocrypt.micmask.VpDecision.thresholds(tuneVpThr).second)} 声纹=${tuneVpEmb?.size ?: 0} 维 模型=${if (models.containsKey(com.taocrypt.micmask.SpeakerEmbedder.MODEL)) "已送达" else "未送达"}")
        }
        engine?.voiceprint(tuneVpOn && tuneFocus == 0, tuneVpThr, tuneVpEmb)
        val plChanged = engine?.let { it.phaseLock != tunePl } ?: false
        engine?.let { it.rmsFollow = tuneRms; it.formant = tuneFormant; it.phaseLock = tunePl; it.denoiseIn = tuneDnIn; it.denoiseOut = tuneDnOut }
        slog('I', "音色参数：响度跟随=$tuneRms 共振峰=$tuneFormant 相位锁定=$tunePl 输入降噪=$tuneDnIn 输出降噪=$tuneDnOut")
        engine?.pinBig = tunePin; engine?.indexRate = tuneIdx
        slog('I', "高级参数：档位=${listOf("自动", "标准", "省电", "高级0.25s").getOrElse(tuneTier) { "?" }} 静音门限=${tuneGate}dB 线程=$tuneFeat/$tuneSyn 绑大核=$tunePin 检索=$tuneIdx 声码器=${listOf("自动", "ORT", "NEON fp16", "NPU").getOrElse(tuneVoc) { "?" }}")
        val vocChanged = engine?.setVocoder(tuneVoc) ?: false
        return (engine?.tune(tuneTier, tuneGate, tuneFeat, tuneSyn) ?: false) || vocChanged || plChanged
    }

    @Synchronized private fun ensureEngine(): com.taocrypt.micmask.LiveRvc {
        if (!inApp) com.taocrypt.micmask.L.sink = { lv, m -> slog(lv, m) }   // App 进程里 L 本来就写 App 日志，不接管
        return engine ?: com.taocrypt.micmask.LiveRvc { n -> models[n]?.takeIf { it.exists() }?.also { synchronized(loadedNames) { loadedNames += n } } }.also {
            it.pickCache = java.io.File(MODEL_DIR, "pick.txt")
            it.tune(tuneTier, tuneGate, tuneFeat, tuneSyn)
            it.pinBig = tunePin; it.indexRate = tuneIdx; it.setVocoder(tuneVoc)
            it.rmsFollow = tuneRms; it.formant = tuneFormant; it.phaseLock = tunePl; it.denoiseIn = tuneDnIn; it.denoiseOut = tuneDnOut
            it.voiceprint(tuneVpOn && tuneFocus == 0, tuneVpThr, tuneVpEmb)
            it.focus = focus
            it.hintCtx = { shellCtx }
            it.qnnDirProvider = { if (inApp) com.taocrypt.micmask.QnnPack.shellDir(ctx?.packageName, MODEL_DIR) else com.taocrypt.micmask.QnnPack.shellDir(ctx?.packageName) }
            it.onConfig = { blockMs -> tierPrimeMs = blockMs + 80; livePrimeMs = tierPrimeMs; slog('I', "预缓冲跟随档位 → ${livePrimeMs}ms") }
            engine = it
        }
    }

    private fun startEngine() {
        if (!inApp) com.taocrypt.micmask.L.sink = { lv, m -> slog(lv, m) }   // App 进程里 L 本来就写 App 日志，不接管
        engine?.stop()
        val my = ++startSeq
        Thread({
            // 串行：上一次启动（比如还在加载模型时就又切了音色）做完再开始，避免两个线程同时 load 同一个引擎
            synchronized(engineLock) {
                if (my != startSeq) return@synchronized
                coldStart(my)
            }
        }, "engine-start").start()
    }

    /**
     * 变声中换音色（2.1.1-alpha.10）：不停推理，旧音色一直出声到新合成器就绪（见 LiveRvc.hotSwap），输出缓冲不被读空。
     * 引擎没在跑、维度不兼容等热切换做不了时，退回以前的「停下 → 重载 → 重启」。
     */
    private fun hotSwapEngine() {
        if (!inApp) com.taocrypt.micmask.L.sink = { lv, m -> slog(lv, m) }
        val my = ++startSeq
        Thread({
            synchronized(engineLock) {
                if (my != startSeq) return@synchronized
                val e = engine
                if (!running) return@synchronized
                val why = if (e == null || !e.isRunning || engineState != "运行") "推理没在运行"
                    else try {
                        runCatching { android.os.Process.setThreadPriority(-16) }
                        e.hotSwap({ msg -> engineState = msg },
                            onLive = { engineState = "运行"; engGen++; slog('I', "热切换音色：新音色已出声（引擎代数 $engGen）") },
                            stale = { my != startSeq || !running })
                    } catch (t: Throwable) { slog('E', "热切换音色异常", t); describe(t) }
                if (why == null) { synthDirty = false; return@synchronized }
                if (why.isEmpty()) { if (running && e?.isRunning == true) engineState = "运行"; return@synchronized }   // 被新的切换取代：旧音色还在跑
                if (my != startSeq || !running) return@synchronized
                slog('W', "热切换音色不可用（$why），改为停下重载：这段输出用静音平滑填充")
                engine?.stop()
                coldStart(my)
            }
        }, "engine-start").start()
    }

    /** 冷启动推理（在 engineLock 里调）：加载模型 → 开始；换音色时先丢掉旧合成器 */
    private fun coldStart(my: Int) {
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
            if (!running) { engineState = ""; return }
            if (my != startSeq) { slog('I', "推理启动被新的切换取代"); return }
            micRing.clear()
            e.start()
            engineState = "运行"
            engGen++
            slog('I', "shell 推理已启动（进程内，不经 App）")
        } catch (t: Throwable) {
            if (my != startSeq) { slog('W', "旧的推理启动失败（已被新的切换取代）：${describe(t)}"); return }
            engineState = "失败：" + describe(t)
            slog('E', "shell 推理启动失败", t)
        }
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
    /** 传音接收模式：outRing 里是远端音频（App 经 TX_LIVE_PUSH 逐包推入），积压裁剪改用更紧的阈值做漂移校正 */
    @Volatile private var linkRx = false
    /** 传音发送取点：tapBuf（目标 App 实际听到的声音）复制一份给 App 拉走发出去；最多攒 0.5 s，App 被冻结时丢最旧的 */
    @Volatile private var linkTap = false
    @Volatile private var linkTapMic = false
    private val linkRing = ShortRing(SR / 2)
    /** 本次 TX_LIVE_START 是否在 shell 里跑 RVC（传音接收为 false）；引擎对象会跨会话保留，不能用 engine != null 判断 */
    @Volatile private var liveRvcHere = false
    /**
     * 2.1.1-alpha.14：传音「仅接收」——外部模式 + 接收注入，且没有「发送取点 = 真麦」（同一会话没有在向对端发本机麦）。
     * 这时 shell 不开本机录音：shell(uid 2000) 一录音就参与安卓并发录音仲裁，系统会把目标应用静音
     * （ph-000008 实测 vivo 录音机 com.android.bbksoundrecorder src=1 静音=true，对方收不到声音），注入循环改由单调时钟驱动。
     * 双向（接收注入 + 发送取点=真麦）仍要开本机录音，仲裁照旧，保留无障碍兜底。
     */
    private fun linkRxOnly(): Boolean = liveExternal && linkRx && !liveRvcHere && !(linkTap && linkTapMic)
    /** 目标开麦（其他应用录音数 0 → ≥1）的时刻，看门狗线程置、liveLoop 取走处理；0 = 无（2.1.1：按住说话开头的旧积压裁剪） */
    private val tgtOpenNs = java.util.concurrent.atomic.AtomicLong(0L)
    /** 外部（AI）输出里最近一次出现人声的时刻；峰值约 -30 dBFS 以上算有声 */
    @Volatile private var lastVoicedNs = 0L
    private val VOICED_PEAK = 1000
    @Volatile private var underruns = 0L
    @Volatile private var liveLevelDb = -120f
    @Volatile private var liveFrames = 0L

    // ------------------------------------------------------------ 变声中录入声纹门限（2.1.1-alpha.6）
    /** 0 空闲 1 录音中 2 计算中 3 完成 4 失败（见 TX_VP_ENROLL） */
    @Volatile private var vpEnState = 0
    @Volatile private var vpEnErr = 0
    @Volatile private var vpEnDetail = ""
    @Volatile private var vpEnBuf: FloatArray? = null
    @Volatile private var vpEnPos = 0
    @Volatile private var vpEnLevel = -120f
    @Volatile private var vpEnEmb: FloatArray? = null
    @Volatile private var vpEnCons = 0f
    @Volatile private var vpEnWin = 0
    private var vpEnE2 = 0.0; private var vpEnN = 0

    private fun vpEnrollFail(err: Int, detail: String) {
        vpEnBuf = null; vpEnErr = err; vpEnDetail = detail; vpEnState = 4
        slog('W', "声纹录入（变声中）失败：err=$err $detail")
    }

    private fun vpEnrollStart(secs: Int) {
        if (vpEnState == 1 || vpEnState == 2) return
        vpEnEmb = null; vpEnErr = 0; vpEnDetail = ""; vpEnLevel = -120f; vpEnE2 = 0.0; vpEnN = 0
        if (!running || phase != "LIVE") { vpEnrollFail(1, ""); return }
        if (models[com.taocrypt.micmask.SpeakerEmbedder.MODEL]?.isFile != true) { vpEnrollFail(2, ""); return }
        val n = SR * secs.coerceIn(5, 20)
        vpEnPos = 0; vpEnBuf = FloatArray(n); vpEnState = 1
        slog('I', "声纹录入（变声中）：开始，从实时链路取 ${n / SR} 秒（录音源=$liveSource 路径=$recPath 系统AEC=${aec?.enabled} 系统NS=${ns?.enabled} 软件AEC=${tuneAec != 0}）")
    }

    private fun vpEnrollCancel() {
        if (vpEnState == 1 || vpEnState == 2) { vpEnBuf = null; vpEnErr = 5; vpEnDetail = ""; vpEnState = 4; slog('I', "声纹录入（变声中）：已取消") }
    }

    /** 录音线程：把送进推理的那路声音（AEC 后、聚焦 / 降噪 / 门限之前，与打分同源）收进录入缓冲 */
    private fun vpEnrollFeed(x: ShortArray, n: Int) {
        val b = vpEnBuf ?: return
        if (vpEnState != 1) return
        val p = vpEnPos
        val k = minOf(n, b.size - p)
        for (i in 0 until k) { val v = x[i] / 32768f; b[p + i] = v; vpEnE2 += v.toDouble() * v }
        vpEnN += k
        vpEnPos = p + k
        if (vpEnN >= SR / 5) { vpEnLevel = (10 * Math.log10(vpEnE2 / vpEnN + 1e-12)).toFloat(); vpEnE2 = 0.0; vpEnN = 0 }
        if (vpEnPos >= b.size) {
            vpEnState = 2
            Thread({ vpEnrollCompute(b) }, "vp-enroll").apply { isDaemon = true; start() }
        }
    }

    private fun vpEnrollCompute(x: FloatArray) {
        try {
            val f = models[com.taocrypt.micmask.SpeakerEmbedder.MODEL]
            if (f == null || !f.isFile) { vpEnrollFail(2, ""); return }
            val t0 = System.nanoTime()
            val embs = com.taocrypt.micmask.SpeakerEmbedder(f, 1).use { e ->
                com.taocrypt.micmask.SpeakerGate.enrollEmbeddings(e, x, com.taocrypt.micmask.Voiceprint.ENROLL_FLOOR_DB)
            }
            if (vpEnState != 2) return   // 计算期间被取消
            slog('I', "声纹录入（变声中）：有效窗 ${embs.size} 个，用时 ${(System.nanoTime() - t0) / 1_000_000}ms")
            if (embs.size < com.taocrypt.micmask.Voiceprint.MIN_WINDOWS) { vpEnrollFail(3, "${embs.size}"); return }
            val (ref, cons) = com.taocrypt.micmask.SpeakerGate.meanRef(embs)
            vpEnEmb = ref; vpEnCons = cons; vpEnWin = embs.size; vpEnBuf = null; vpEnState = 3
            slog('I', "声纹录入（变声中）：完成 自洽度 ${"%.2f".format(cons)}，等 App 取回保存")
        } catch (t: Throwable) {
            slog('E', "声纹录入（变声中）计算出错", t)
            vpEnrollFail(4, describe(t))
        }
    }

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
            // 2.1.1-alpha.7：容量 60 → 100 ms（只是上限，实际排队由 MonQueue 稳在约 40 ms），给写端抖动留余量，减少丢写
            runCatching { newMediaTrack(100).also { it.play(); monTrack = it } }
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

    // ------------------------------------------------------------ 软件回声消除（AEC3，一期：参考 = 本进程的耳返 / 录制预览）
    /**
     * 2.1.1-alpha.7：耳返写给 monTrack 的全部样本（含预填静音）按写入顺序记下，AEC 取参考时按 monTrack 的播放头
     * 取「刚播出去的 10 ms」，不再是「刚写进去的」（liveLoop 线程写、读）
     */
    private val aecAlign = com.taocrypt.micmask.PlayoutAligner(SR * 2)
    /** 耳返排队稳定器（启动/欠载后预填静音、长期积压过多时少写一点）；liveLoop 线程用 */
    private val monQ = com.taocrypt.micmask.MonQueue(SR, MON_TARGET_MS)
    /** tap 最近一次写的 monTrack（换了就重置对齐和排队） */
    private var monSeen: AudioTrack? = null
    private var monUnderLast = -1
    private val monZeros = ShortArray(SR / 10)
    /** AEC 兜底半双工压低；liveLoop 线程用 */
    private val aecDuck = com.taocrypt.micmask.AecDuck()

    /**
     * 兜底是否适用：外放（耳返 AudioTrack 实际路由到扬声器/听筒；取不到路由时看有没有耳机），
     * 或者在放录制预览且没戴耳机。[headset] = 当前是否接着耳机类输出
     */
    private fun aecOnSpeaker(headset: Boolean): Boolean {
        val mt = monTrack
        if (mt != null) {
            val d = if (Build.VERSION.SDK_INT >= 24) runCatching { mt.routedDevice }.getOrNull() else null
            if (d != null) return d.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                d.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE || d.type == 24 /* TYPE_BUILTIN_SPEAKER_SAFE（API 30） */
            return !headset
        }
        return previewPlaying && !headset
    }

    /** 耳返 AudioTrack 已播出的帧数（播放头，按无符号 32 位）；取不到返回 -1 */
    private fun monHead(t: AudioTrack): Long = runCatching { t.playbackHeadPosition.toLong() and 0xffffffffL }.getOrDefault(-1L)
    /** 录制预览回放写出的样本（preview 线程写，liveLoop 读） */
    private val aecPrevRef = ShortRing(SR)
    @Volatile private var previewPlaying = false
    /** 只在 liveLoop 线程里用 */
    private var softAec: com.taocrypt.micmask.SoftAec? = null
    /** 耳返/录制预览只取人声（音效混入之前），音效只进麦克风；要本机听音效用悬浮窗长按外放 */
    private fun voiceTap(buf: FloatArray, n: Int) {
        for (i in 0 until n) monBuf[i] = (buf[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        tap(monBuf, n)
    }

    /** 输出一行 AEC 统计（AEC3 指标 + 参考链路计数），并清零计数；liveLoop 线程调用 */
    private fun aecReport(tag: String) {
        val r = softAec?.report() ?: return
        val mt = monTrack
        val ur = if (mt != null && Build.VERSION.SDK_INT >= 24) runCatching { mt.underrunCount }.getOrDefault(-1) else -1
        val dUr = if (ur >= 0 && aecMonUnderBase >= 0) ur - aecMonUnderBase else -1
        if (ur >= 0) aecMonUnderBase = ur
        fun ms(x: Long) = x * 1000 / SR
        val q = monQ
        val qs = if (q.qN > 0) "${ms(q.qSum / q.qN)}/${ms(q.qMin)}/${ms(q.qMax)}" else "?"
        val alignQ = if (aecAlignN > 0) "${ms(aecAlignSum / aecAlignN)}ms（${ms(aecAlignMin)}~${ms(aecAlignMax)}）" else "?"
        val d = aecDuck
        val duck = when {
            !aecDuckAllowed -> "不适用（${if (monTrack == null && !previewPlaying) "无外放" else "耳机"}）"
            d.engaged -> "已触发"
            else -> "未触发"
        } + (if (d.engages > 0) " 本段触发 ${d.engages} 次" else "") +
            (if (d.engaged || d.duckFrames > 0 || d.nearCount > 0) " 压低 ${d.duckFrames * 10}ms 压低量 平均 -${if (d.duckFrames > 0) "%.1f".format(d.duckDbSum / d.duckFrames) else "0"} dB 最深 -${"%.0f".format(d.maxDuck)} dB（上限 ${"%.0f".format(d.maxDuckDb)}）" +
                " 近端判定 ${d.nearCount} 次 未压 ${d.spareFrames * 10}ms 回声延迟≈${d.lag * 10}ms 相关 ${"%.2f".format(d.corr)} 耦合 ${if (d.couplingDb.isNaN()) "?" else "%.0f".format(d.couplingDb)}dB" else "")
        // alpha.7 之后：出声位置来源、getTimestamp 出声延迟（写入 → 喇叭出声）及比 playbackHeadPosition 多出的 HAL/DSP 延迟
        val srcS = when {
            aecTsFrames > 0 && aecHeadFrames == 0 -> "时间戳"
            aecTsFrames > 0 -> "时间戳（${aecHeadFrames * 10}ms 退回播放头）"
            aecHeadFrames > 0 -> "播放头（时间戳不可用）"
            else -> if (aecHeadOk) "?" else "无（退回按写入）"
        }
        val tsLat = if (aecTsLatN > 0) "${ms(aecTsLatSum / aecTsLatN)}/${ms(aecTsLatMin)}/${ms(aecTsLatMax)}ms（比播放头多 ${ms(aecTsDiffSum / aecTsLatN)}ms）" else "?"
        val capS = when {
            aecCapTsFrames > 0 && aecCapEstFrames == 0 -> "录音时间戳"
            aecCapTsFrames > 0 -> "录音时间戳（${aecCapEstFrames * 10}ms 估计）"
            else -> "估计"
        }
        slog('I', "AEC${if (tag.isEmpty()) "" else "（$tag）"}：$r | 参考对齐：出声位置=$srcS getTimestamp出声延迟 平均/最小/最大 $tsLat 录音时刻=$capS 提前 ${AEC_REF_LEAD_MS}ms " +
            "排队补偿 $alignQ 微调 ${aecAlign.nudges} 次 重同步 ${aecAlign.resyncs} 次 补静音 ${ms(aecAlign.zeroed)}ms " +
            "未写补零 ${ms(aecAlign.missing)}ms | 耳返：排队 平均/最小/最大 $qs ms（目标 $MON_TARGET_MS）预填 ${q.prefills} 次 ${ms(q.prefillSamples)}ms " +
            "削减 ${ms(q.trimmedSamples)}ms 欠载 ${if (dUr >= 0) "$dUr" else "?"} 次 丢写 $aecMonPartial 次 ${ms(aecMonDrop)}ms | 兜底：$duck | 输出积压 ${ms(outRing.size().toLong())}ms")
        aecMonDrop = 0L; aecMonPartial = 0
        aecAlign.clearCounters(); q.clearStats(); d.clearStats()
        aecAlignSum = 0L; aecAlignN = 0; aecAlignMin = Long.MAX_VALUE; aecAlignMax = 0L
        aecTsFrames = 0; aecHeadFrames = 0; aecCapTsFrames = 0; aecCapEstFrames = 0
        aecTsLatSum = 0L; aecTsLatN = 0; aecTsLatMin = Long.MAX_VALUE; aecTsLatMax = 0L; aecTsDiffSum = 0L
    }

    // AEC 参考链路统计（liveLoop 线程读写；随 AEC 统计行输出后清零）
    /** 耳返非阻塞写没写进去、被丢掉的样本数（这些样本没外放，不进参考） */
    private var aecMonDrop = 0L
    private var aecMonPartial = 0
    /** 参考对齐：每帧「排队补偿」（参考起点落后写入末尾多少样本）的累计 / 最小 / 最大 */
    private var aecAlignSum = 0L
    private var aecAlignN = 0
    private var aecAlignMin = Long.MAX_VALUE
    private var aecAlignMax = 0L
    /** 最近一帧能否取到耳返播放头 */
    private var aecHeadOk = true
    /** 出声位置：用时间戳 / 退回播放头 的帧数；录音时刻：用 AudioRecord 时间戳 / 估计 的帧数 */
    private var aecTsFrames = 0
    private var aecHeadFrames = 0
    private var aecCapTsFrames = 0
    private var aecCapEstFrames = 0
    /** getTimestamp 出声延迟（已写入 − 此刻出声位置，样本）累计/最小/最大，及「播放头 − 时间戳出声位置」累计 */
    private var aecTsLatSum = 0L
    private var aecTsLatN = 0
    private var aecTsLatMin = Long.MAX_VALUE
    private var aecTsLatMax = 0L
    private var aecTsDiffSum = 0L
    /** 耳返 AudioTrack 最近一次 getTimestamp（framePosition / nanoTime，-1 = 没有）；liveLoop 线程读写，换 monTrack 时清 */
    private val monTs = android.media.AudioTimestamp()
    private var monTsFrame = -1L
    private var monTsNs = 0L
    private var monTsPoll = 0
    /** 兜底是否适用（AEC 开 + 外放）：liveLoop 每秒评估一次 */
    private var aecDuckAllowed = false
    /** 耳返 AudioTrack 的欠载计数（系统累计值，统计时取差） */
    private var aecMonUnderBase = -1

    /** 每 10 ms 一帧变声结果都过一遍这里：耳返 + 录制预览 */
    private fun tap(mono: ShortArray, n: Int) {
        monTrack?.let { t ->
            if (t !== monSeen) {
                monSeen = t; aecAlign.reset(); monQ.reset(); monTsFrame = -1L; monTsPoll = 0
                monUnderLast = if (Build.VERSION.SDK_INT >= 24) runCatching { t.underrunCount }.getOrDefault(-1) else -1
            }
            // 2.1.1-alpha.7：耳返排队稳定——第一次写先垫 40 ms 静音，欠载后补回 40 ms；整秒都多出 25 ms 以上就少写几毫秒拉回来。
            // 以前排队常在 0~10 ms 徘徊，写端（注入写出单次 20~40 ms、偶尔读麦 80 ms）一抖就欠载，alpha.6 真机每 3 s 欠载 20~53 次
            val head = monHead(t)
            val queued = if (head >= 0) (aecAlign.written - head).coerceAtLeast(0L) else -1L
            val ur = if (Build.VERSION.SDK_INT >= 24) runCatching { t.underrunCount }.getOrDefault(-1) else -1
            val underran = ur >= 0 && monUnderLast >= 0 && ur > monUnderLast
            if (ur >= 0) monUnderLast = ur
            monQ.plan(queued, underran, n)
            var pre = monQ.prefill
            while (pre > 0) {
                val zw = t.write(monZeros, 0, minOf(pre, monZeros.size), AudioTrack.WRITE_NON_BLOCKING)
                if (zw <= 0) break
                aecAlign.writeZeros(zw); pre -= zw
            }
            val off = monQ.skip.coerceIn(0, n)
            val w = t.write(mono, off, n - off, AudioTrack.WRITE_NON_BLOCKING)
            if (w < 0) setMonitor(false)
            else {
                // 2.1.1-alpha.6：软件 AEC 的参考只记实际写进耳返的样本（缓冲满时没写进去的不会外放，以前照记 n 个，参考与真实外放逐渐错位）
                if (w < n - off) { aecMonDrop += (n - off - w).toLong(); aecMonPartial++ }
                // 不论 AEC 开没开都记（记的是这个 AudioTrack 的帧号，排队和对齐都靠它）
                if (w > 0) aecAlign.write(mono, off, w)
            }
        }
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
                aecPrevRef.clear(); previewPlaying = true
                var o = 0
                while (o < pcm.size) {
                    val w = t.write(pcm, o, minOf(4800, pcm.size - o)); if (w <= 0) break
                    if (tuneAec != 0) aecPrevRef.write(pcm, o, w)   // 软件 AEC 的参考：预览回放（同在 shell 进程）
                    o += w
                }
                Thread.sleep(300)
                runCatching { t.stop() }; t.release()
            } catch (t: Throwable) { slog('E', "预览回放失败", t) }
            finally { previewPlaying = false; aecPrevRef.clear() }
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
            resetLiveWrite()
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
                    slog('I', "输入麦克风选择=${listOf("自动", "机身麦", "耳机麦").getOrElse(tuneMic) { "?" }}")
                    val want = pickMic(true)
                    curMicId = want?.id ?: -1
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
            /** 开本机录音（开局或传音从仅接收变成双向时）：含开局的无障碍预登记；失败返回 false（lastError 已写） */
            fun startMic(): Boolean {
                if (preOk && !preOn && !preRevoked) {
                    if (echoRisk()) { preRevoked = true; slog('I', "外放且正在通话，保留录音源 $liveSource（回声消除），被静音时再改无障碍录音") }
                    else preEnable("开始即用无障碍录音（非 vivo，${if (headsetOut()) "戴耳机" else "未在通话"}）")
                }
                val r = openRec() ?: return false
                rec = r; curRec = r
                return true
            }
            /** 关本机录音（传音从双向变回仅接收）：撤无障碍登记、录音源还原、释放录音与系统 AEC/NS */
            fun stopMic(why: String) {
                curRec = null
                a11yThread?.interrupt(); a11yThread = null
                if (a11yOn) a11yBoost(false)
                if (a11yPrevSrc >= 0) liveSource = a11yPrevSrc
                a11yPrevSrc = -1; a11yFailed = false; a11yChkSec = -1; a11yIdle = 0; tgtSilSec = 0
                preOn = false; preRevoked = false; riskSec = 0; safeSec = 0
                runCatching { rec?.stop() }; runCatching { rec?.release() }; rec = null
                runCatching { aec?.release() }; runCatching { ns?.release() }; aec = null; ns = null
                micHint = ""
                slog('I', "传音：$why，关闭本机麦（避免系统静音目标应用）")
            }
            micHint = ""; recRebuilds = 0
            // 传音仅接收：不开本机麦，注入循环改由单调时钟按 10 ms 一帧驱动
            var noMicSince = 0L
            var biWarned = false; var biSilSec = 0
            if (linkRxOnly()) {
                slog('I', "传音：仅接收，不开本机麦（避免系统静音目标应用），注入按单调时钟 10 ms/帧驱动")
            } else {
                if (liveExternal && linkRx) slog('W', "传音：双向（接收注入 + 发送本机麦），需开本机麦；系统可能因并发录音仲裁静音目标应用，保留无障碍兜底")
                if (!startMic()) return
            }
            // 看门狗：0.6 s 读不到麦克风数据（流失效、切路由、音频服务重启）就停掉当前录音，让主循环重建
            tgtOpenNs.set(0L)
            watchdog = Thread({
                var micChk = 0
                var tgtPrev = -1
                while (running) {
                    runCatching { Thread.sleep(200) }
                    // 目标开麦检测（只 AI 外部模式用）：放在看门狗线程（每 200 ms 一次），不在读麦/写出的主循环里做 binder 调用。
                    // 第一次只记基线：开始变声时目标已在录不算开麦
                    if (liveExternal) {
                        val rc = recCount()
                        if (tgtPrev == 0 && rc > 0) tgtOpenNs.set(System.nanoTime())
                        tgtPrev = rc
                    } else tgtPrev = -1
                    val r = curRec ?: continue
                    // 中途插拔耳机：每 1 s 按「输入麦克风」设置重选一次，变了就改录音的首选设备（不重建录音）
                    if (++micChk >= 5) {
                        micChk = 0
                        runCatching {
                            val want = pickMic(false)
                            val id = want?.id ?: -1
                            if (id != curMicId && want != null) {
                                curMicId = id
                                slog('I', "输入设备变化 → ${want.type}/${want.productName} ${r.setPreferredDevice(want)}")
                            }
                        }
                    }
                    val nowW = System.nanoTime()
                    // 2.1.1-alpha.6：读不到 ≠ 写不出。注入写出（或重建注入轨道）正卡着时，主循环根本没去读麦克风，
                    // 这时停录音没用（alpha.4 进游戏后 writeMax=38897ms，看门狗误报 64 次）：只记一次，不停/重建录音
                    val wb = outBusySinceNs
                    if (wb != 0L && nowW - wb > 500_000_000L) {
                        if (!wdWriteLogged) {
                            wdWriteLogged = true
                            slog('W', "看门狗：注入写出卡住 ${(nowW - wb) / 1_000_000}ms（不是麦克风问题，不停/重建录音）${audioSnapshot()}")
                        }
                        lastReadNs = nowW
                    } else if (nowW - lastReadNs > 600_000_000L) {
                        wdWriteLogged = false
                        micHint = "没有收到麦克风数据，正在重建录音"
                        slog('W', "0.6 s 没有麦克风数据，停止当前录音以重建")
                        lastReadNs = System.nanoTime()
                        runCatching { r.stop() }
                    } else if (wb == 0L) wdWriteLogged = false
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
            var diagSec = 0
            var callSec = 0; var callCycled = false; var callOffSec = 0; var lastMode = -99
            // 0 DEFAULT、1 MIC、5 CAMCORDER、6 VOICE_RECOGNITION、9 UNPROCESSED、10 VOICE_PERFORMANCE
            val silOrder = intArrayOf(1, 6, 5, 9, 10, 0); var silTry = 0; val silTried = LinkedHashSet<Int>(); var silGood = true
            fun silWho(): String = runCatching {
                am?.activeRecordingConfigurations?.filter { it.clientAudioSessionId != (rec?.audioSessionId ?: -1) }
                    ?.joinToString(" ") { "src=${it.clientAudioSource}" + (runCatching { " uid=" + it.javaClass.getMethod("getClientUid").invoke(it) }.getOrDefault("")) }
            }.getOrNull().orEmpty().ifEmpty { "未知应用" }
            val shifter = PitchShifter(SR)
            val hop = SR / 100            // 10 ms
            val inBuf = ShortArray(hop)
            // 软件 AEC：处理结果进 aecBuf；检测类逻辑（全零/数字静音/被静音/电平）继续用原始 inBuf
            val aecBuf = ShortArray(hop); val aecRef = ShortArray(hop); val aecTmp = ShortArray(hop)
            var aecWhy: String? = "未开"; var aecStatT = System.nanoTime(); var aecRunT = 0L
            aecMonDrop = 0L; aecMonPartial = 0; aecMonUnderBase = -1
            aecAlign.clearCounters(); monQ.clearStats(); aecDuck.reset(); aecDuck.clearStats()
            aecAlignSum = 0L; aecAlignN = 0; aecAlignMin = Long.MAX_VALUE; aecAlignMax = 0L
            aecTsFrames = 0; aecHeadFrames = 0; aecCapTsFrames = 0; aecCapEstFrames = 0
            aecTsLatSum = 0L; aecTsLatN = 0; aecTsLatMin = Long.MAX_VALUE; aecTsLatMax = 0L; aecTsDiffSum = 0L
            var duckChkT = 0L
            // 录音时刻（AEC 参考对齐用）：AudioRecord.getTimestamp 优先，取不到用 CaptureClock 按读取时刻估
            val capClock = com.taocrypt.micmask.CaptureClock()
            val recTs = android.media.AudioTimestamp()
            var recTsOwner: android.media.AudioRecord? = null
            var recRead = 0L; var recTsFrame = -1L; var recTsNs = 0L; var recTsPoll = 0
            val outBytes = ByteArray(hop * FRAME_BYTES)
            val rnd = java.util.Random()
            val ext = ShortArray(hop)
            // 去爆音（2.1.1-alpha.10）：断流时从最后一个样本 5 ms 线性收到 0（以前是硬切成 0，咔一声）；重新起播的第一个 10 ms 余弦淡入
            val declickN = SR / 200
            val rampIn = FloatArray(hop) { i -> (0.5 - 0.5 * Math.cos(Math.PI * (i + 0.5) / hop)).toFloat() }
            var lastExt = 0f; var fadeInNext = false
            // 目标开麦裁掉旧积压后，下一帧开头 5 ms 从上一样本平滑接过去（裁剪处前后两段不连续）
            var joinNext = false
            val tapBuf = ShortArray(hop)
            var statT = System.nanoTime(); var rdMax = 0L; var wrMax = 0L; var obMin = Int.MAX_VALUE; var obMax = 0
            var lastUnder = 0L; var lastRec = -1
            // 输出积压裁剪（alpha.9）：当前 1 s 窗口内 / 上一个完整窗口的输出缓冲最小值（-1 = 还没有完整窗口）
            var trimWinMin = Int.MAX_VALUE; var trimLastMin = -1; var trimWinT = System.nanoTime()
            var primed = false
            // 外部模式先攒够再开始放：App 每 300 ms 推一整块，缓冲必须 > 一块，否则每块之间都会断
            var prime = SR * livePrimeMs / 1000
            slog('I', "LIVE 外部模式=${liveExternal} 预缓冲=${livePrimeMs}ms")
            /** 单调时钟节拍：下一帧（10 ms）应处理的时刻；0 = 未起拍 */
            var clkNext = 0L
            val hopNs = 10_000_000L
            while (running) {
                // ---- 传音：按「仅接收 / 双向」开关本机麦（发送取点 = 真麦 在 TX_LIVE_START 之后才由 App 打开，所以要在循环里跟）
                if (liveExternal && linkRx) {
                    val rxOnly = linkRxOnly()
                    if (rxOnly && rec != null) {
                        // 关麦防抖 300 ms：App 停发送时先关取点再改取点来源，中间状态不来回开关录音
                        val nowS = System.nanoTime()
                        if (noMicSince == 0L) noMicSince = nowS
                        else if (nowS - noMicSince >= 300_000_000L) { stopMic("仅接收（发送取点已关）"); noMicSince = 0L; clkNext = 0L; biWarned = false; biSilSec = 0 }
                    } else noMicSince = 0L
                    if (!rxOnly && rec == null) {
                        slog('W', "传音：双向（接收注入 + 发送取点=真麦），开本机麦；系统可能因并发录音仲裁静音目标应用，保留无障碍兜底（开 MicMask 无障碍可改善）")
                        if (!startMic()) return
                    }
                }
                var got = 0
                val tr0 = System.nanoTime()
                var zeroReads = 0
                if (rec == null) {
                    // 不开本机麦：没有麦克风读数当节拍，用单调时钟按 10 ms 一帧推进；落后超过 100 ms（被系统冻结 / 写出卡住）就重新起拍，不一口气补
                    java.util.Arrays.fill(inBuf, 0.toShort())
                    val nowC = System.nanoTime()
                    if (clkNext == 0L || nowC - clkNext > 100_000_000L) clkNext = nowC
                    else if (clkNext > nowC) java.util.concurrent.locks.LockSupport.parkNanos(clkNext - nowC)
                    clkNext += hopNs
                    got = hop
                    lastReadNs = System.nanoTime()
                } else clkNext = 0L
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
                // 录音时刻：这一帧（结尾）的录音时刻；换了录音对象就从头计
                val nowRd = System.nanoTime()
                if (rec !== recTsOwner) { recTsOwner = rec; recRead = 0L; recTsFrame = -1L; recTsPoll = 0; capClock.reset() }
                recRead += hop
                val capEst = capClock.frame(nowRd)
                if (tuneAec != 0 && Build.VERSION.SDK_INT >= 24 && recTsPoll-- <= 0) {
                    recTsPoll = 4
                    val rr = rec
                    if (rr != null && runCatching { rr.getTimestamp(recTs, android.media.AudioTimestamp.TIMEBASE_MONOTONIC) }.getOrDefault(-1) == android.media.AudioRecord.SUCCESS) {
                        recTsFrame = recTs.framePosition; recTsNs = recTs.nanoTime
                    }
                }
                val capTs = if (recTsFrame >= 0) recTsNs + (recRead - recTsFrame) * 1_000_000_000L / SR else -1L
                // 合理性：已读到的帧不可能晚于现在，也不该早于现在 0.5 s 以上（丢帧/时间戳失效时退回估计）
                val capOk = capTs > 0 && capTs <= nowRd + 2_000_000L && nowRd - capTs < 500_000_000L
                val capNs = if (capOk) capTs else capEst
                // 原始麦克风不会给出连续的纯数字 0：连续 3 s 全零 = 被系统静音或被别的录音占用。
                // 降噪模式（VOICE_COMMUNICATION）不说话时降噪本身就会输出全 0，不能据此提示（alpha101 真机误报）
                var allZero = rec != null && liveSource != android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION
                if (allZero) for (i in 0 until hop) if (inBuf[i].toInt() != 0) { allZero = false; break }
                if (allZero) { if (++zeroFrames == 300) { micHint = "采集到的是纯静音（麦克风可能被系统静音或被占用）"; slog('W', micHint) } }
                else { if (zeroFrames >= 300 || micHint.startsWith("没有收到")) micHint = ""; zeroFrames = 0 }
                if (rec == null) { stuckT = System.nanoTime(); stuckSec = 0 }
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
                    // 诊断：每 10 s 记一次推理进程的 cgroup、大核当前频率、最高温度（只记录，不改行为）
                    // alpha.9：放到单独线程。TB323FU 上一次诊断（逐个读上百个 thermal_zone 等）要 80~120 ms，
                    // 在本线程里做会让读麦停顿，随后补读把输出缓冲一下子吃掉 ~100 ms（22:11:43 断流#1 就是诊断 + 裁积压叠在一起）
                    if (++diagSec >= 10) { diagSec = 0; diagAsync() }
                    // 2.1.1-alpha.3：进入通话模式后仍看不到别的应用录音 → 撤掉注入再重新接管（每次通话一次）
                    // QQ 外放去电：响铃期间就进通话模式，接通时模式会短暂离开再进入，接通后 2–3 s 才开麦（TB323FU 实测）。离开通话模式只把计时清零，
                    // 连续离开 ≥ 3 s 才算这通电话结束（以前一离开就重置「本通已做过」，接通时模式闪一下就又撤一次）
                    val mode = runCatching { am?.mode ?: -9 }.getOrDefault(-9)
                    val inCall = mode == android.media.AudioManager.MODE_IN_COMMUNICATION || mode == android.media.AudioManager.MODE_IN_CALL
                    if (mode != lastMode) {
                        if (lastMode != -99) slog('I', "音频模式 $lastMode → $mode（${if (inCall) "通话中" else "非通话"}）${recDetail()}")
                        lastMode = mode
                    }
                    if (!inCall) { callSec = 0; if (++callOffSec >= 3) callCycled = false }
                    else {
                        callOffSec = 0
                        if (!callCycled) {
                            callSec++
                            if (appRecording()) { callCycled = true; if (callSec > 1) slog('I', "进入通话模式 ${callSec} s 后目标应用已开麦，不用重新接管") }
                            else {
                                // alpha.10：等待改回 2 s（alpha.9 的 5/10 s 会让 vivo 先开变声再打微信的无声变长：微信要等我们撤了才开麦）。
                                // TB323FU 的问题在于接通时模式闪一下又撤一次，已由「每通只撤一次 + 连续离开 ≥ 3 s 才算结束」解决，与等待长短无关
                                val need = CALL_WAIT_S
                                if (callSec >= need) {
                                    callCycled = true
                                    val why = "进入通话模式 $callSec s 仍没看到目标应用录音（等待上限 ${need} s，${if (!scopeAll) "手动目标" else "自动识别"}）"
                                    if (tuneCallRecycle) cyclePolicy(why)
                                    else slog('W', "$why ${recDetail()}，已关闭「通话接管」，不撤注入")
                                }
                            }
                        }
                    }
                    // 双向时目标应用仍被静音：提示一次（安卓并发录音仲裁，无障碍兜底未必能解除）
                    if (rec != null && liveExternal && linkRx) {
                        if (targetSilenced()) {
                            if (++biSilSec >= 5 && !biWarned) {
                                biWarned = true
                                slog('W', "传音：双向中目标应用仍被系统静音 ${biSilSec}s${recDetail()}（安卓并发录音仲裁；无障碍兜底=${if (a11yOn) "已登记" else if (a11yFailed) "无效" else "未生效"}）。对方若听不到，可停掉本机→对端发送改为仅接收，或开启 MicMask 无障碍")
                            }
                        } else biSilSec = 0
                    }
                    // 仅接收（没开本机麦）：下面的无障碍兜底 / 被静音重建都与本机录音有关，跳过
                    if (rec != null) {
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
                }
                rdMax = maxOf(rdMax, System.nanoTime() - tr0)
                // ---- 软件回声消除（AEC3）：在 micRing / 降噪 / PitchShifter / RVC 之前
                var mic = inBuf
                if (tuneAec != 0) {
                    val why = when {
                        rec == null -> "传音仅接收，未开本机麦"
                        liveSource == android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "录音源 7 已有系统回声消除"
                        softAec?.failed != null -> "出错：${softAec?.failed}"
                        monTrack == null && !previewPlaying -> "无参考信号（耳返未开、无预览回放）"
                        else -> null
                    }
                    if (why == null) {
                        val sa = softAec ?: com.taocrypt.micmask.SoftAec { lv, m -> slog(lv, m) }.also { softAec = it }
                        // 参考与麦克风一对一：每读一帧麦克风（hop=480）取一帧参考。
                        // 2.1.1-alpha.7：耳返参考按 monTrack 播放头取「刚播出去的 10 ms」（PlayoutAligner），AEC3 只需估硬件/HAL 延迟
                        val mt = monTrack
                        if (mt != null && mt === monSeen) {
                            val hd = monHead(mt)
                            aecHeadOk = hd >= 0
                            // alpha.7 之后：出声位置优先用 AudioTrack.getTimestamp()（含 HAL/DSP 出声延迟），外推到这帧麦克风的录音时刻 + 提前量；
                            // 取不到再退回 playbackHeadPosition（只到混音器，AEC3 要自己估 200 ms 级的 DSP 延迟，且一跳 20~40 ms）
                            if (monTsPoll-- <= 0) {
                                monTsPoll = 4
                                if (runCatching { mt.getTimestamp(monTs) }.getOrDefault(false)) { monTsFrame = monTs.framePosition; monTsNs = monTs.nanoTime }
                            }
                            if (capOk) aecCapTsFrames++ else aecCapEstFrames++
                            val nowTs = System.nanoTime()
                            val wr = aecAlign.written
                            val tsNow = com.taocrypt.micmask.TrackClock.framesAt(monTsFrame, monTsNs, nowTs, SR, wr)
                            val tsAt = com.taocrypt.micmask.TrackClock.framesAt(monTsFrame, monTsNs, capNs + AEC_REF_LEAD_MS * 1_000_000L, SR, wr)
                            if (tsNow >= 0) {
                                val lat = wr - tsNow
                                aecTsLatSum += lat; aecTsLatN++; aecTsLatMin = minOf(aecTsLatMin, lat); aecTsLatMax = maxOf(aecTsLatMax, lat)
                                if (hd >= 0) aecTsDiffSum += hd - tsNow
                            }
                            if (tsAt >= 0) { aecTsFrames++; aecAlign.read(aecRef, hop, tsAt, smooth = true) }
                            else { aecHeadFrames++; aecAlign.read(aecRef, hop, hd) }
                            val lq = aecAlign.lastQueue
                            aecAlignSum += lq; aecAlignN++; aecAlignMin = minOf(aecAlignMin, lq); aecAlignMax = maxOf(aecAlignMax, lq)
                        } else java.util.Arrays.fill(aecRef, 0.toShort())
                        if (previewPlaying || aecPrevRef.size() > 0) {
                            val k2 = aecPrevRef.read(aecTmp, 0, hop)
                            for (i in 0 until k2) aecRef[i] = (aecRef[i] + aecTmp[i]).coerceIn(-32768, 32767).toShort()
                        }
                        if (sa.render(aecRef, hop) && sa.process(inBuf, aecBuf)) {
                            mic = aecBuf
                            // ---- 兜底（2.1.1-alpha.7，之后改温和）：AEC 开 + 外放时，ERLE 持续 < 3 dB 且参考有声 → 触发；
                            // 触发后只在回声主导的帧压低最多 8 dB，近端说话（麦克风明显高于预期回声）立即放开并保持 0.4 s
                            val nowD = System.nanoTime()
                            if (nowD - duckChkT >= 1_000_000_000L) { duckChkT = nowD; aecDuckAllowed = aecOnSpeaker(headsetOut()) }
                            val refDb = com.taocrypt.micmask.AecDuck.levelDb(aecRef, hop)
                            val micDb = com.taocrypt.micmask.AecDuck.levelDb(inBuf, hop)
                            val erle = sa.lastErle.let { if (it.isNaN()) 99f else it }
                            val g0 = aecDuck.gainDb
                            if (aecDuck.step(refDb, micDb, erle, aecDuckAllowed)) {
                                slog(if (aecDuck.engaged) 'W' else 'I', if (aecDuck.engaged)
                                    "AEC 兜底：参考有声时 ERLE 持续 < 3 dB（当前 ${"%.1f".format(erle)} dB，参考 ${"%.0f".format(refDb)} dB），之后只在麦克风明显跟着外放走（回声主导）时压低麦克风最多 ${"%.0f".format(aecDuck.maxDuckDb)} dB；你说话时不压"
                                    else "AEC 兜底：解除（${if (!aecDuckAllowed) "已不是外放/已关" else "ERLE 回升到 ${"%.1f".format(erle)} dB"}）")
                            }
                            val g1 = aecDuck.gainDb
                            if (g0 < 0f || g1 < 0f) {
                                // 帧内从上一帧增益线性过渡到这一帧，避免咔哒声
                                val l0 = Math.pow(10.0, g0 / 20.0).toFloat(); val l1 = Math.pow(10.0, g1 / 20.0).toFloat()
                                for (i in 0 until hop) {
                                    val gl = l0 + (l1 - l0) * (i + 1) / hop
                                    aecBuf[i] = (aecBuf[i] * gl).toInt().coerceIn(-32768, 32767).toShort()
                                }
                            }
                        }
                    } else { aecPrevRef.clear(); aecDuck.reset() }
                    val st = why ?: "工作中"
                    if (st != aecWhy) {
                        // 2.1.1-alpha.6：从「工作中」切到旁路时先把这段的统计输出一次（以前不足 10 s 的会话没有任何 ERL/ERLE）
                        if (aecWhy == "工作中") aecReport("旁路前")
                        slog('I', if (why == null) "AEC：开始处理（参考=${if (monTrack != null) "耳返" else ""}${if (previewPlaying) " 预览" else ""}）" else "AEC：旁路（$why）")
                        if (why == null) { aecRunT = System.nanoTime(); aecStatT = aecRunT }
                        aecWhy = st
                    }
                    val nowA = System.nanoTime()
                    // 统计：开始处理后前 30 s 每 3 s 一次，之后每 10 s
                    val every = if (why == null && nowA - aecRunT < 30_000_000_000L) 3_000_000_000L else 10_000_000_000L
                    if (nowA - aecStatT >= every) {
                        aecStatT = nowA
                        if (why == null) aecReport("")
                        else slog('I', "AEC：旁路（$why）")
                    }
                } else if (aecWhy != "未开") {
                    if (aecWhy == "工作中") aecReport("关闭前")
                    softAec?.release(); softAec = null; aecPrevRef.clear(); aecDuck.reset()
                    slog('I', "AEC：已关闭"); aecWhy = "未开"
                }
                if (vpEnState == 1 && rec != null) vpEnrollFeed(mic, hop)
                if (liveExternal) {
                    if (rec != null) micRing.write(mic, 0, hop)
                    var e2 = 0.0
                    for (i in 0 until hop) { val x = inBuf[i] / 32768f; e2 += x * x }
                    liveLevelDb = (10 * Math.log10(e2 / hop + 1e-12)).toFloat()
                    liveFrames++
                    prime = SR * livePrimeMs / 1000   // 引擎定档后会改
                    if (!primed && outRing.size() >= prime) { primed = true; fadeInNext = true }
                    var k = if (primed) outRing.read(ext, 0, hop) else 0
                    val ob = outRing.size(); obMin = minOf(obMin, ob); obMax = maxOf(obMax, ob)
                    if (primed && k < hop) { underruns++; primed = false; slog('W', "断流#$underruns：输出缓冲只剩 ${k * 1000 / SR}ms"); engine?.takeIf { engineState == "运行" }?.noteTrouble("断流") }
                    // 积压超过「预缓冲 + 一块」就丢旧的回到预缓冲：启动时的积压不会一直挂着变成延迟（1.13 实测稳定在 0.7~1 s）
                    // alpha104：只在积压超 1 s 且最近 0.5 s 输出没有人声时才裁，裁掉的是静音而不是半句话
                    // alpha.6：阈值从「预缓冲 + 1 s」降到「预缓冲 + 一块」（至少 250 ms）。OnePlus 0.5 s 档 outBuf 长期 600–1150 ms，一直没到 1580 ms，多挂约半秒
                    // alpha.9：以前一超过「预缓冲 + 一块」就裁到刚好预缓冲。块是整块推进来的，缓冲本来就在「谷底 ~ 谷底 + 一块」之间锯齿，
                    // 刚推完一块时裁到预缓冲，下一块到来前只剩 预缓冲 − 块间隔（330 − 260 ≈ 70 ms），读麦稍一停顿就断流（TB323FU 22:11:43）。
                    // 现改为：只看「谷底」——完整 1 s 窗口里缓冲的最小值仍高于 预缓冲 + 余量，才算持续积压；裁掉的量 = 谷底 − 预缓冲，
                    // 即把谷底拉回预缓冲（平时谷底本来就在预缓冲以下），裁后至少留 预缓冲 + 余量。余量 = max(120 ms, 半块)
                    // 传音接收（远端逐包推入，没有「整块」）：块按 40 ms、余量 30 ms 算，且不要求静音段——用于吸收两台设备的时钟漂移和 Wi-Fi 突发后的积压
                    val blkS = if (linkRx) SR * 40 / 1000 else maxOf(SR / 4, SR * (livePrimeMs - 80).coerceAtLeast(0) / 1000)
                    val trimJit = if (linkRx) SR * 30 / 1000 else maxOf(SR * 120 / 1000, blkS / 2)
                    var obNow = outRing.size()
                    if (primed) trimWinMin = minOf(trimWinMin, obNow) else { trimWinMin = 0; trimLastMin = -1 }
                    if (nowRd - trimWinT >= 1_000_000_000L) { trimWinT = nowRd; trimLastMin = trimWinMin; trimWinMin = Int.MAX_VALUE }
                    val valley = if (trimLastMin < 0) -1 else minOf(trimLastMin, trimWinMin)
                    // 目标开麦（按住说话 / 接通）：这一刻 outRing 里全是按下之前的旧音频（缓冲里的音频比现在晚 端到端 + 缓冲 ≈ 0.6 s 以上），
                    // 一次性裁掉多出来的部分，让按下后的第一句早点进对方录音。下限与静音段裁剪完全相同：把近 1 s 谷底拉回预缓冲，且至少留 预缓冲 + 余量，
                    // 不按人声与否、不要求谷底超过余量；谷底没高于预缓冲就不裁。检测晚于 0.5 s（看门狗被卡）或还没有完整 1 s 统计就不裁，避免裁到按下后的声音或在不明状态下裁
                    var joinArm = false
                    val openEv = tgtOpenNs.getAndSet(0L)
                    if (openEv != 0L) {
                        val late = (nowRd - openEv) / 1_000_000
                        val keep = if (valley < 0) obNow else maxOf(prime + trimJit, obNow - (valley - prime))
                        val why = when {
                            !primed -> "未在出声（预缓冲中），不裁"
                            late > 500 -> "检测晚了 ${late}ms，不裁（避免裁到按下后的声音）"
                            valley < 0 -> "还没有完整 1 s 的缓冲统计，不裁"
                            keep >= obNow -> "近 1 s 谷底 ${valley * 1000 / SR}ms 未高于预缓冲 ${livePrimeMs}ms，已在下限"
                            else -> null
                        }
                        if (why == null) {
                            outRing.trimTo(keep); joinArm = true
                            slog('I', "目标开麦：裁掉旧积压 ${(obNow - keep) * 1000 / SR}ms，剩 ${keep * 1000 / SR}ms（近 1 s 谷底 ${valley * 1000 / SR}ms，预缓冲 ${livePrimeMs}ms + 余量 ${trimJit * 1000 / SR}ms）")
                            trimLastMin = -1; trimWinMin = Int.MAX_VALUE; trimWinT = nowRd
                            obNow = keep
                        } else slog('I', "目标开麦：裁掉旧积压 0ms，剩 ${obNow * 1000 / SR}ms（$why）")
                    }
                    if (!joinArm && obNow > prime + blkS && valley > prime + trimJit && (linkRx || System.nanoTime() - lastVoicedNs > 500_000_000L)) {
                        val keep = maxOf(prime + trimJit, obNow - (valley - prime))
                        if (keep < obNow) {
                            outRing.trimTo(keep)
                            if (linkRx) joinArm = true   // 有声段也会裁：下一帧开头 5 ms 平滑接上
                            slog('I', (if (linkRx) "传音：接收缓冲偏高（漂移/突发）" else "输出积压过多（静音段）") + "：近 1 s 谷底 ${valley * 1000 / SR}ms > 预缓冲 ${livePrimeMs}ms + 余量 ${trimJit * 1000 / SR}ms，" +
                                "丢掉 ${(obNow - keep) * 1000 / SR}ms，剩 ${keep * 1000 / SR}ms")
                            trimLastMin = -1; trimWinMin = Int.MAX_VALUE; trimWinT = nowRd
                        }
                    }
                    var pk = 0
                    for (i in 0 until hop) { fxBuf[i] = if (i < k) ext[i] / 32768f else 0f; if (i < k) pk = maxOf(pk, kotlin.math.abs(ext[i].toInt())) }
                    if (fadeInNext && k > 0) { for (i in 0 until k) fxBuf[i] *= rampIn[i]; fadeInNext = false }
                    if (joinNext && k > 0) {
                        val n = minOf(k, declickN)
                        for (j in 0 until n) { val a = (j + 1f) / (n + 1); fxBuf[j] = lastExt * (1f - a) + fxBuf[j] * a }
                        joinNext = false
                    }
                    if (joinArm) joinNext = true
                    if (k < hop) {
                        val a = if (k > 0) fxBuf[k - 1] else lastExt
                        if (a != 0f) { val n = minOf(hop - k, declickN); for (j in 0 until n) fxBuf[k + j] = a * (n - 1 - j) / n }
                    }
                    lastExt = fxBuf[hop - 1]
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
                    if (linkTap) linkRing.write(if (linkTapMic) mic else tapBuf, 0, hop)
                    val tw0 = System.nanoTime()
                    if (!writeOutLive(outBytes)) break
                    wrMax = maxOf(wrMax, System.nanoTime() - tw0)
                    val now = System.nanoTime()
                    if (now - statT >= 1_000_000_000L) {
                        val rc = recCount()
                        slog('M', "live/s mic=${"%.0f".format(liveLevelDb)}dB readMax=${rdMax / 1_000_000}ms writeMax=${wrMax / 1_000_000}ms " +
                            "outBuf=${obMin * 1000 / SR}~${obMax * 1000 / SR}ms 断流+${underruns - lastUnder} 积压mic=${micRing.size() * 1000 / SR}ms 目标在录=$rc" + (if (rec == null) " 本机麦=关（仅接收）" else ""))
                        if (rc != lastRec) { slog('I', "目标录音数 $lastRec → $rc ${recDetail()}"); lastRec = rc }
                        statT = now; rdMax = 0; wrMax = 0; obMin = Int.MAX_VALUE; obMax = 0; lastUnder = underruns
                    }
                    continue
                }
                var e = 0.0
                val ratio = Math.pow(2.0, liveSemis / 12.0).toFloat()
                for (i in 0 until hop) {
                    val x0 = inBuf[i] / 32768f
                    e += x0 * x0   // 电平（检测用）仍取原始麦克风
                    val x = mic[i] / 32768f
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
                if (linkTap) linkRing.write(tapBuf, 0, hop)
                liveLevelDb = (10 * Math.log10(e / hop + 1e-12)).toFloat()
                liveFrames++
                if (!writeOutLive(outBytes)) break
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
            if (softAec?.ready == true) runCatching { aecReport("停止时") }
            runCatching { softAec?.release() }; softAec = null; aecPrevRef.clear(); aecDuck.reset()
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

    /** 通话开始后重新接管中（注入策略暂时撤掉），这期间写注入轨道直接丢弃 */
    @Volatile private var cycling = false
    @Volatile private var armGen = 0

    /**
     * 2.1.1-alpha.3：先开变声再打微信语音，vivo V2415A 上微信的通话录音（源 7）根本不出现、对面无声；
     * 通话中再开变声则 1 s 内被接管（实测）。所以进入通话模式 2 s 还看不到别的应用录音时：撤掉策略 → 等对方开麦（≤3 s）→ 重新注册。
     * 每次通话只做一次；正常机型目标录音会照常出现，不会触发
     */
    private fun cyclePolicy(why: String? = null) {
        if (cycling) return
        cycling = true
        val gen = armGen
        Thread({
            try {
                slog('W', (why ?: "进入通话模式仍没看到目标应用录音") + " ${recDetail()}，撤掉注入让对方先开麦，再重新接管")
                runCatching { track?.stop() }; runCatching { track?.release() }
                track = null
                unregister()
                val t0 = System.nanoTime()
                while (running && !appRecording() && System.nanoTime() - t0 < 3_000_000_000L) Thread.sleep(100)
                val seen = appRecording()
                Thread.sleep(200)
                if (!running || gen != armGen) return@Thread
                val c = shellCtx; val m = am
                // alpha.9：注册失败不再立刻结束变声，间隔 0.3 s 重试 2 次（撤掉期间注入的声音是丢掉的，越快接回越好）
                var err: String? = null
                for (i in 0 until 3) {
                    if (!running || gen != armGen) return@Thread
                    err = if (c == null || m == null) "没有 Context" else buildAndRegister(targetUids, c, m)
                    if (err == null) break
                    slog('W', "重新接管第 ${i + 1} 次失败：$err")
                    unregister()
                    Thread.sleep(300)
                }
                if (err != null) { slog('E', "重新接管失败：$err"); lastError = err; running = false }
                else slog('I', "已重新接管（撤掉 ${(System.nanoTime() - t0) / 1_000_000}ms，${if (seen) "对方已开麦" else "仍没看到对方录音"}）${recDetail()}")
            } catch (t: Throwable) { slog('E', "重新接管出错", t) } finally { if (gen == armGen) cycling = false }
        }, "micmask-recycle").apply { isDaemon = true; start() }
    }

    /** 写注入轨道；写失败先重建一次再写，仍失败返回 false（结束变声） */
    private fun writeOut(b: ByteArray): Boolean {
        if (cycling) return true
        var t = track ?: return cycling
        var w = t.write(b, 0, b.size)
        if (w < 0 && cycling) return true
        if (w < 0) {
            slog('W', "AudioTrack.write=$w，尝试重建注入轨道")
            if (!rebuildTrack()) { lastError = "AudioTrack.write=$w"; return false }
            t = track ?: return false
            w = t.write(b, 0, b.size)
            if (w < 0) { lastError = "AudioTrack.write=$w"; return false }
        }
        return true
    }

    // ---- 实时变声的注入写出（2.1.1-alpha.6）：不阻塞、带超时，卡住就重建注入轨道
    /** 非 0 = 正在写注入轨道 / 重建注入轨道的起始时刻（看门狗据此区分「写不出」与「读不到」） */
    @Volatile private var outBusySinceNs = 0L
    @Volatile private var wdWriteLogged = false
    /** 给 App 状态行的提示（空 = 无）：注入通道卡住时非空 */
    @Volatile private var outHint = ""
    /** 本次变声是否用非阻塞写；系统不支持时退回阻塞写（旧行为） */
    @Volatile private var nbWrite = true
    private var stallT0 = 0L
    private var stallDropBytes = 0L
    private var stallActT = 0L
    private var stallActs = 0
    private var stallSnapLogged = false

    /** 每次开始实时变声时调 */
    private fun resetLiveWrite() {
        nbWrite = true; stallT0 = 0L; stallDropBytes = 0L; stallActT = 0L; stallActs = 0; stallSnapLogged = false
        outBusySinceNs = 0L; wdWriteLogged = false; outHint = ""
    }

    /**
     * 写注入轨道（实时变声用）：WRITE_NON_BLOCKING，写不完就短暂轮询，单次最多等 [WRITE_WAIT_MS]；
     * 仍写不完 → 丢掉这 10 ms 剩下的部分，进入「卡住」状态（之后每次只等 [STALL_WAIT_MS]，主循环照常按麦克风节拍跑）；任何一次写完整即退出卡住状态。
     * 卡住超过 1 s → 记录音频模式 / 当前播放配置 / 前台应用，重建注入轨道；重建后 3 s 仍卡住 → 重新注册注入策略（cyclePolicy）；
     * 之后每 5 s 再重建一次。写出恢复时记一行。返回 false = 结束变声（与旧 writeOut 一致：写错误且重建失败）。
     * 非阻塞写不被支持（ERROR_BAD_VALUE / 不支持的模式）时本次变声退回阻塞写。
     */
    private fun writeOutLive(b: ByteArray): Boolean {
        if (cycling) return true
        if (!nbWrite) {
            outBusySinceNs = System.nanoTime()
            try { return writeOut(b) } finally { outBusySinceNs = 0L }
        }
        var t = track ?: return cycling
        val t0 = System.nanoTime()
        val stalled = stallT0 != 0L
        val waitNs = (if (stalled) STALL_WAIT_MS else WRITE_WAIT_MS) * 1_000_000L
        var off = 0
        outBusySinceNs = t0
        try {
            while (off < b.size) {
                val w = try { t.write(b, off, b.size - off, AudioTrack.WRITE_NON_BLOCKING) } catch (e: IllegalArgumentException) { AudioTrack.ERROR_BAD_VALUE }
                if (w < 0) {
                    if (cycling) return true
                    if (w == AudioTrack.ERROR_BAD_VALUE && off == 0) {
                        nbWrite = false
                        slog('W', "注入轨道不支持非阻塞写（$w），本次变声退回阻塞写")
                        return writeOut(b)
                    }
                    slog('W', "AudioTrack.write=$w，尝试重建注入轨道")
                    if (!rebuildTrack()) { lastError = "AudioTrack.write=$w"; return false }
                    t = track ?: return false
                    continue
                }
                off += w
                if (off >= b.size) break
                if (System.nanoTime() - t0 >= waitNs) break
                java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L)
            }
        } finally { outBusySinceNs = 0L }
        val now = System.nanoTime()
        if (off >= b.size) {
            if (stallT0 != 0L) {
                slog('I', "注入写出恢复：卡了 ${(now - stallT0) / 1_000_000}ms，丢弃 ${stallDropBytes / FRAME_BYTES * 1000 / SR}ms 音频，处理 $stallActs 次")
                stallT0 = 0L; stallDropBytes = 0L; stallActs = 0; stallActT = 0L; stallSnapLogged = false; outHint = ""
            }
            return true
        }
        // 没写完：丢掉剩下的
        if (stallT0 == 0L) stallT0 = t0
        stallDropBytes += (b.size - off).toLong()
        val dur = now - stallT0
        if (dur >= 1_000_000_000L) {
            if (!stallSnapLogged) {
                stallSnapLogged = true
                slog('W', "注入写出卡住 ${dur / 1_000_000}ms（注入轨道不再取声音，不是麦克风问题）：${audioSnapshot()}")
            }
            val gap = if (stallActs == 0) 0L else if (stallActs == 1) 3_000_000_000L else 5_000_000_000L
            if (now - stallActT >= gap) {
                stallActT = now; stallActs++
                outHint = "注入通道不再取声音（可能被游戏或系统接管），正在重建"
                if (stallActs == 2) {
                    slog('W', "注入写出卡住 ${dur / 1_000_000}ms，重建注入轨道后仍卡 → 重新注册注入策略")
                    cyclePolicy("注入写出卡住，撤掉注入后重新接管")
                } else {
                    slog('W', "注入写出卡住 ${dur / 1_000_000}ms → 重建注入轨道（第 $stallActs 次）")
                    outBusySinceNs = now
                    val ok = try { rebuildTrack() } finally { outBusySinceNs = 0L }
                    if (!ok) { slog('W', "注入轨道重建失败 → 重新注册注入策略"); cyclePolicy("注入轨道重建失败，撤掉注入后重新接管") }
                }
            }
        }
        return true
    }

    /** 卡住时的现场：音频模式、当前播放配置（usage/uid/状态）、录音会话、前台应用 */
    private fun audioSnapshot(): String = runCatching {
        val m = am ?: return@runCatching ""
        val mode = runCatching { m.mode }.getOrDefault(-99)
        val pc = android.media.AudioPlaybackConfiguration::class.java
        fun g(o: Any, n: String) = runCatching { pc.getMethod(n).invoke(o) }.getOrNull()
        val plays = runCatching {
            m.activePlaybackConfigurations.take(12).joinToString(prefix = "[", postfix = "]") { c ->
                val a = c.audioAttributes
                "uid=${g(c, "getClientUid")} usage=${a.usage} content=${a.contentType} 状态=${g(c, "getPlayerState")} 类型=${g(c, "getPlayerType")}"
            }
        }.getOrDefault("?")
        " mode=$mode 播放=$plays 录音=${recDetail()} 前台=${fgPkg()} 注入方式=${trackWay ?: "?"}"
    }.getOrDefault("")

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
    /** 悬浮窗用的 CPU 温度：只认有效的 CPU/SoC 传感器（见 [zoneTemp]），没有返回 -1 */
    private fun cpuTemp(): Float = zoneTemp(cpuOnly = true)?.first ?: -1f

    // ---- 温度有效性（2.1.1-alpha.9）：TB323FU 的 thermal_zone 里有 cpu-hw-trip-0=105（触发阈值）、pmih010x-ibat-lvl1=146（电流档位），
    // 以前取所有 zone 最大值，就在 105/146 之间跳。现排除阈值/档位类节点，只认 −20~120 °C，且长期一动不动的高值当常量排除
    private val tempBadName = Regex("trip|lvl|limit|crit|ibat|vbat|ocp|bcl|thresh|shutdown|cdev", RegexOption.IGNORE_CASE)
    private val tempCpuName = Regex("cpu|soc|tsens|apc|cluster|gold|silver|prime|mtktscpu|aoss", RegexOption.IGNORE_CASE)
    private fun tempValid(name: String, c: Float): Boolean = c >= -20f && c <= 120f && c != 0f && !tempBadName.containsMatchIn(name)
    /** zone 路径 → (上次读数, 连续不变次数)；≥ 90 °C 且连续 6 次（约 1 分钟）完全不变 → 视为常量 */
    private val zoneSame = HashMap<String, Pair<Float, Int>>()
    /** 读 thermal_zone：CPU/SoC 类优先取最大，没有再取其他有效节点最大（cpuOnly 时不退）；返回 (°C, 来源名)，没有有效值返回 null */
    private fun zoneTemp(cpuOnly: Boolean = false): Pair<Float, String>? {
        fun rd(path: String) = runCatching { java.io.File(path).readText().trim() }.getOrNull()
        var cpu: Pair<Float, String>? = null; var other: Pair<Float, String>? = null
        java.io.File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }.orEmpty().forEach { z ->
            val raw = rd("${z.path}/temp")?.toFloatOrNull() ?: return@forEach
            val c = if (raw > 1000f || raw < -1000f) raw / 1000f else raw
            val name = rd("${z.path}/type") ?: z.name
            if (!tempValid(name, c)) return@forEach
            if (!cpuOnly) synchronized(zoneSame) {
                val prev = zoneSame[z.path]
                val n = if (prev != null && prev.first == c) prev.second + 1 else 0
                zoneSame[z.path] = c to n
                if (c >= 90f && n >= 6) return@forEach
            }
            if (tempCpuName.containsMatchIn(name)) { if (cpu == null || c > cpu!!.first) cpu = c to name }
            else if (other == null || c > other!!.first) other = c to name
        }
        return cpu ?: if (cpuOnly) null else other
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
            "note=${outHint.ifEmpty { if (tuneFocus != 0 && liveExternal) focus.note else "" }}",
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
