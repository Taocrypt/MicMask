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
        const val TX_DESTROY = 16777115

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
        val ident = Binder.clearCallingIdentity()
        try { return transactInner(code, data, reply, flags) } finally { Binder.restoreCallingIdentity(ident) }
    }

    private fun transactInner(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        try {
            when (code) {
                TX_DESTROY -> {
                    disarm("destroy")
                    System.exit(0)
                }
                TX_PING -> {
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
                    liveSource = data.readInt()
                    liveExternal = data.dataAvail() >= 4 && data.readInt() == 1
                    livePrimeMs = maxOf(if (data.dataAvail() >= 4) data.readInt().coerceIn(20, 1500) else 100, tierPrimeMs)
                    val rvcHere = data.dataAvail() >= 4 && data.readInt() == 1
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
                    models.keys.filter { it.contains("_stream") }.forEach { models.remove(it) }
                    repeat(n) {
                        val name = data.readString() ?: ""
                        val pfd = data.readFileDescriptor()
                        if (pfd != null && name.isNotEmpty()) {
                            // shell 读不了 App 私有目录（/proc/self/fd 重开也被 SELinux 拒），拷到 shell 自己的目录
                            pfd.use { p ->
                                val dst = java.io.File(MODEL_DIR, name)
                                val size = p.statSize
                                if (dst.length() != size) {
                                    java.io.File(MODEL_DIR).mkdirs()
                                    val t0 = System.currentTimeMillis()
                                    val tmpF = java.io.File(MODEL_DIR, "$name.part")
                                    FileInputStream(p.fileDescriptor).use { i -> java.io.FileOutputStream(tmpF).use { o -> i.copyTo(o, 1 shl 20) } }
                                    if (!tmpF.renameTo(dst)) throw java.io.IOException("rename $name")
                                    slog('I', "拷贝模型 $name ${size shr 20}MB 用时 ${System.currentTimeMillis() - t0}ms")
                                    if (name in loadedNames) engine?.let { e -> engine = null; Thread { e.release() }.start() }
                                }
                                models[name] = dst
                            }
                            got += "$name(${pfd.statSize shr 20}MB)"
                        }
                    }
                    slog('I', "收到模型 ${got.joinToString()}")
                    reply?.writeNoException()
                    reply?.writeString(null)
                    return true
                }
                TX_LIVE_BENCH -> {
                    val r = runCatching {
                        val e = ensureEngine()
                        e.load({ msg -> engineState = msg }, force = true)
                        engineState = ""
                        e.benchSummary
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
                    // sec>0：开始录（最长 sec 秒，满了自动回放）；0：停止并回放已录部分；-1：取消
                    val sec = data.readInt()
                    var play: ShortArray? = null
                    synchronized(capLock) {
                        when {
                            sec > 0 -> { capBuf = ShortArray(SR * sec.coerceAtMost(30)); capPos = 0 }
                            sec == 0 -> { val cb = capBuf; if (cb != null && capPos > SR / 5) play = cb.copyOf(capPos); capBuf = null }
                            else -> capBuf = null
                        }
                    }
                    slog('I', "录制预览 cmd=$sec")
                    play?.let { pcm -> Thread { playPreview(pcm) }.start() }
                    reply?.writeNoException()
                    return true
                }
                TX_LIVE_FX -> {
                    val n = data.readInt().coerceIn(0, 64)
                    val a = FloatArray(n) { data.readFloat() }
                    fx.set(a); fxLast = a
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
        if (uids.isEmpty() || uids.any { it < 10000 }) return "目标 uid 无效：${uids.joinToString()}"
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
            for (u in uids) ruleB.getMethod("addMixRule", I, Any::class.java).invoke(rb, ruleUid, Integer.valueOf(u))
            val rule = ruleB.getMethod("build").invoke(rb)

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

            val t = polCls.getMethod("createAudioTrackSource", mixCls).invoke(pol, mix) as AudioTrack?
            if (t == null || t.state != AudioTrack.STATE_INITIALIZED) {
                unregister()
                return "注入通道没有初始化（state=${t?.state}）"
            }
            t.play()
            track = t
        } catch (t: Throwable) {
            unregister()
            lastError = describe(t)
            slog('E', "arm failed", t)
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
    private val loadedNames = HashSet<String>()
    private val models = java.util.concurrent.ConcurrentHashMap<String, java.io.File>()
    @Volatile private var engine: com.taocrypt.micmask.LiveRvc? = null
    @Volatile private var engineState = ""

    @Synchronized private fun ensureEngine(): com.taocrypt.micmask.LiveRvc {
        com.taocrypt.micmask.L.sink = { lv, m -> slog(lv, m) }
        return engine ?: com.taocrypt.micmask.LiveRvc { n -> models[n]?.takeIf { it.exists() }?.also { synchronized(loadedNames) { loadedNames += n } } }.also {
            it.pickCache = java.io.File(MODEL_DIR, "pick.txt")
            it.onConfig = { blockMs -> tierPrimeMs = blockMs + 80; livePrimeMs = tierPrimeMs; slog('I', "预缓冲跟随档位 → ${livePrimeMs}ms") }
            engine = it
        }
    }

    private fun startEngine() {
        com.taocrypt.micmask.L.sink = { lv, m -> slog(lv, m) }
        engine?.stop()
        Thread({
            try {
                runCatching { android.os.Process.setThreadPriority(-16) }
                val e = ensureEngine().also { engine = it }
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
                if (!running) { engineState = ""; return@Thread }
                micRing.clear()
                e.start()
                engineState = "运行"
                slog('I', "shell 推理已启动（进程内，不经 App）")
            } catch (t: Throwable) {
                engineState = "失败：" + describe(t)
                slog('E', "shell 推理启动失败", t)
            }
        }, "engine-start").start()
    }

    // ------------------------------------------------------------ 实时变声

    @Volatile private var liveSemis = 0f
    @Volatile private var liveSource = 1 // MediaRecorder.AudioSource.MIC
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

    /** 每 10 ms 一帧变声结果都过一遍这里：耳返 + 录制预览 */
    private fun tap(mono: ShortArray, n: Int) {
        monTrack?.let { t -> if (t.write(mono, 0, n, AudioTrack.WRITE_NON_BLOCKING) < 0) setMonitor(false) }
        var done: ShortArray? = null
        synchronized(capLock) {
            val cb = capBuf ?: return@synchronized
            val k = minOf(n, cb.size - capPos)
            System.arraycopy(mono, 0, cb, capPos, k); capPos += k
            if (capPos >= cb.size) { done = cb; capBuf = null }
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
        try {
            val minBuf = android.media.AudioRecord.getMinBufferSize(SR,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val fmt = AudioFormat.Builder().setSampleRate(SR)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
            val b = android.media.AudioRecord.Builder()
                .setAudioSource(liveSource)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(maxOf(minBuf, SR / 50 * 2 * 2))
            if (Build.VERSION.SDK_INT >= 31 && c != null) runCatching { b.setContext(c) }
            @Suppress("MissingPermission")
            rec = runCatching { b.build() }.getOrNull()?.takeIf { it.state == android.media.AudioRecord.STATE_INITIALIZED }
            if (rec == null && liveSource != android.media.MediaRecorder.AudioSource.MIC) {
                slog('W', "录音源 $liveSource 初始化失败，退回 MIC")
                liveSource = android.media.MediaRecorder.AudioSource.MIC
                b.setAudioSource(liveSource)
                @Suppress("MissingPermission")
                rec = runCatching { b.build() }.getOrNull()
            }
            if (rec == null || rec.state != android.media.AudioRecord.STATE_INITIALIZED) {
                lastError = "shell 录音初始化失败（ROM 不给 shell 录音权限？）"
                return
            }
            // 回声消除 / 降噪：扬声器外放的声音会被真麦录进去，用系统（多为高通硬件）AEC 去掉
            val sid = rec.audioSessionId
            aec = if (android.media.audiofx.AcousticEchoCanceler.isAvailable())
                runCatching { android.media.audiofx.AcousticEchoCanceler.create(sid)?.apply { enabled = true } }.getOrNull() else null
            ns = if (android.media.audiofx.NoiseSuppressor.isAvailable())
                runCatching { android.media.audiofx.NoiseSuppressor.create(sid)?.apply { enabled = true } }.getOrNull() else null
            slog('I', "LIVE 回声消除 可用=${android.media.audiofx.AcousticEchoCanceler.isAvailable()} 已开=${aec?.enabled} 降噪 已开=${ns?.enabled}")
            rec.startRecording()
            slog('I', "LIVE 开始：src=$liveSource semis=$liveSemis dev=${runCatching { rec.routedDevice?.productName }.getOrNull()}")
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
                while (got < hop && running) {
                    val r = rec.read(inBuf, got, hop - got)
                    if (r < 0) { lastError = "AudioRecord.read=$r"; return }
                    got += r
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
                    if (outRing.size() > prime + SR / 2) { outRing.trimTo(prime); slog('I', "输出积压过多，丢到预缓冲 ${livePrimeMs}ms") }
                    var pk = 0
                    for (i in 0 until hop) { fxBuf[i] = if (i < k) ext[i] / 32768f else 0f; if (i < k) pk = maxOf(pk, kotlin.math.abs(ext[i].toInt())) }
                    if (pk > VOICED_PEAK) lastVoicedNs = System.nanoTime()
                    fx.process(fxBuf, hop); mixSfx(fxBuf, hop)
                    for (i in 0 until hop) {
                        var v = (fxBuf[i] * 32767f).toInt().coerceIn(-32768, 32767)
                        if (v == 0) v = rnd.nextInt(5) - 2
                        tapBuf[i] = v.toShort()
                        val lo = (v and 0xff).toByte(); val hi = ((v shr 8) and 0xff).toByte()
                        val o = i * FRAME_BYTES
                        outBytes[o] = lo; outBytes[o + 1] = hi; outBytes[o + 2] = lo; outBytes[o + 3] = hi
                    }
                    tap(tapBuf, hop)
                    val t = track ?: break
                    val tw0 = System.nanoTime()
                    val w = t.write(outBytes, 0, outBytes.size)
                    wrMax = maxOf(wrMax, System.nanoTime() - tw0)
                    if (w < 0) { lastError = "AudioTrack.write=$w"; break }
                    val now = System.nanoTime()
                    if (now - statT >= 1_000_000_000L) {
                        val rc = recCount()
                        slog('M', "live/s mic=${"%.0f".format(liveLevelDb)}dB readMax=${rdMax / 1_000_000}ms writeMax=${wrMax / 1_000_000}ms " +
                            "outBuf=${obMin * 1000 / SR}~${obMax * 1000 / SR}ms 断流+${underruns - lastUnder} 积压mic=${micRing.size() * 1000 / SR}ms 目标在录=$rc")
                        if (rc != lastRec) { slog('I', "目标录音数 $lastRec → $rc"); lastRec = rc }
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
                fx.process(fxBuf, hop); mixSfx(fxBuf, hop)
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
                tap(tapBuf, hop)
                liveLevelDb = (10 * Math.log10(e / hop + 1e-12)).toFloat()
                liveFrames++
                val t = track ?: break
                val w = t.write(outBytes, 0, outBytes.size)
                if (w < 0) { lastError = "AudioTrack.write=$w"; break }
            }
        } catch (t: Throwable) {
            lastError = describe(t)
            slog('E', "liveLoop", t)
        } finally {
            runCatching { rec?.stop() }
            runCatching { aec?.release() }; runCatching { ns?.release() }; aec = null; ns = null
            runCatching { rec?.release() }
            if (running) disarm("live-end")
        }
    }

    @Synchronized
    private fun disarm(why: String) {
        if (!running && policy == null) return
        running = false
        setMonitor(false)
        synchronized(capLock) { capBuf = null }
        sfx = null
        slog('I', "DISARM ($why)")
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

    private fun unregister() {
        val p = policy ?: return
        policy = null
        runCatching {
            val polCls = Class.forName("android.media.audiopolicy.AudioPolicy")
            AudioManager::class.java.getMethod("unregisterAudioPolicy", polCls).invoke(am, p)
        }.onFailure { slog('W', "unregisterAudioPolicy failed", it) }
    }

    /** 目标 uid 现在有没有在录音（shell 进程能看到 clientUid） */
    private fun recCount(): Int = runCatching { am?.activeRecordingConfigurations?.size ?: 0 }.getOrDefault(0)

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
            "uid=${Process.myUid()}",
            "perm=${if (hasRoutingPermission()) 1 else 0}",
            "target=${targetUids.joinToString(",")}",
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
