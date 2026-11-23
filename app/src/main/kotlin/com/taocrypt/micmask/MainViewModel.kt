package com.taocrypt.micmask

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import micmask.core.RvcConfig
import micmask.core.Wav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

data class UiState(
    val modelDir: String = "",
    val modelItems: List<ModelStore.Item> = emptyList(),
    val modelsReady: Boolean = false,

    // ---- 首次启动从 APK 内解包模型（全量包才有事做） ----
    val importing: Boolean = false,
    val importDone: Int = 0,
    val importTotal: Int = 0,
    val importName: String = "",
    val engineLoaded: Boolean = false,

    val loadingEngine: Boolean = false,
    val engineLog: String = "",

    val srcName: String? = null,
    val srcInfo: String? = null,

    val busy: Boolean = false,
    val stage: String = "",
    val progress: Float = 0f,

    val resultFile: File? = null,
    val resultDisplay: String? = null,
    val resultSeconds: Float = 0f,
    val elapsedMs: Long = 0L,

    val error: String? = null,
    val toast: String? = null,

    // ---------------------------------------------------------------- 录音
    val recording: Boolean = false,
    val recElapsedMs: Long = 0L,
    /** 0..1 实时电平 */
    val recLevel: Float = 0f,
    /** 0..1 本次录音峰值电平，贴 1 说明可能削波 */
    val recPeakLevel: Float = 0f,
    val recSampleRate: Int = 0,
    /** 最近一次录音落盘的 wav（原始采样率） */
    val recFile: File? = null,
    val recSeconds: Float = 0f,
    val recCount: Int = 0,

    // ---------------------------------------------------------------- 合成（GPT-SoVITS）
    val ttsDir: String = "",
    val ttsModelItems: List<TtsAssetStore.Item> = emptyList(),
    val ttsReady: Boolean = false,
    val ttsMissing: List<String> = emptyList(),
    /** 已就绪的情绪参考特征（joy/sad/angry/calm 的子集） */
    val ttsRefsReady: List<String> = emptyList(),
    /** 当前选中的情绪 */
    val ttsEmotion: String = "calm",

    val ttsText: String = "",
    val ttsRefText: String = "",
    val ttsLang: String = "zh",
    val ttsSpeed: Float = 1.0f,
    val ttsTemperature: Float = 1.0f,
    val ttsTopK: Int = 15,
    val ttsTopP: Float = 1.0f,
    val ttsRepetition: Float = 1.35f,
    /** 选用的参考音频（未指定时用录音目录里最新的一条） */
    val ttsRefFile: File? = null,
    val ttsRefName: String? = null,

    val ttsBusy: Boolean = false,
    val ttsStage: String = "",
    val ttsProgress: Float = 0f,
    val ttsResultFile: File? = null,
    val ttsResultSeconds: Float = 0f,
    val ttsElapsedMs: Long = 0L,
    val ttsLog: String = "",

    /** 合成指标（对齐 PC 管线，便于 A/B） */
    val ttsArSteps: Int = 0,
    val ttsMsPerStep: Double = 0.0,
    val ttsRms: Float = 0f,

    /** 常用语音收藏（最多 10 条，LFU 淘汰）。合成耗时长，留一份便于复用。 */
    val library: List<TtsLibrary.Entry> = emptyList(),

    // 参数
    val pitchShift: Int = 0,
    val indexRate: Float = 0.3f,
    val protect: Float = 0.33f,
    val sid: Int = 0,
    val overlap: Int = 64,
    val useIndex: Boolean = true,
    /** 0 = 跟随 ORT 默认线程数（与 PC 基准一致；显式设值会改变 INT8 图的数值，见 VcEngine.load 注释） */
    val threads: Int = 0,

    // ---------------- QQ 语音代发 ----------------
    /** 总开关：允许在 QQ 录音时自动外放语音 */
    val qqAutoSend: Boolean = false,
    /** 无障碍服务是否已在系统设置里开启（开启只能在设置里手动做） */
    val qqWatchEnabled: Boolean = false,
    /** 当前待发送的语音文件名（null = 还没有） */
    val qqPendingName: String? = null,
    /** 是否正在外放 */
    val qqPlaying: Boolean = false,
    /** 音频路由：耳机（已证伪）还是手机外放（默认，唯一可行） */
    val qqHeadsetMode: Boolean = false,
    /** 悬浮小窗是否已开启（服务在跑） */
    val qqFloating: Boolean = false,
    /** 当前是否插着/连着耳机 */
    val qqHeadsetOn: Boolean = false,
    /** 当前耳机名 */
    val qqHeadsetName: String = "",
    /** 实际输出设备名（排查用） */
    val qqLastOutput: String = "",
    /** 上次播放是否因为连着耳机而没真正外放（QQ 会录成静音） */
    val qqBlockedByHeadset: Boolean = false,
    /** 测试版：Shizuku 注入模式（不外放，直接接管目标 App 麦克风） */
    val qqInjectMode: Boolean = false,
    val qqSelfTesting: Boolean = false,
    val liveOn: Boolean = false,
    val liveSemis: Float = 0f,
    val liveAi: Boolean = true,
    /** true=通话模式（系统回声消除+降噪，外放不串音）；false=原始麦克风（音质优先，建议戴耳机） */
    val liveAec: Boolean = true,
    /** 本机性能测试结果（空=还没测） */
    val liveBench: String = "",
    val liveVoices: List<String> = emptyList(),
    /** 置顶的本地音色 */
    val voicePins: Set<String> = emptySet(),
    /** 关闭的音效包（不在悬浮窗显示） */
    val packsOff: Set<String> = emptySet(),
    val liveVoice: String = "",
    val liveBenching: Boolean = false,
    val logMsg: String = "",
    val liveMsg: String = "",
    val qqSelfTest: String = "",
    /** 注入目标包名 */
    val qqInjectTarget: String = com.taocrypt.micmask.inject.MicInjector.DEFAULT_TARGET,
    /** Shizuku 状态文字 */
    val qqShizuku: String = "",
    /** 注入最近状态 */
    val qqInjectMsg: String = "",

    // ---------------- v2：在线模型仓库 ----------------
    val repoUrl: String = "",
    /** 多个模型仓库（按顺序合并，同名音色/文件以靠前的仓库为准），含备注名与开关 */
    val repos: List<RepoEntry> = emptyList(),
    val repoModels: List<com.taocrypt.micmask.repo.ModelRepo.RModel> = emptyList(),
    val repoLoading: Boolean = false,
    val repoMsg: String = "",
    /** AI 页模型仓库分类 tab：female / male / other */
    val repoTab: String = "female",
    /** AI 页模型仓库：搜索词、只看某个仓库（地址，空 = 全部） */
    val repoQuery: String = "",
    val repoFilter: String = "",
    /** 正在下载的音色 id（null=空闲） */
    val dlId: String? = null,
    val dlProgress: Float = 0f,
    val dlName: String = "",
    /** 外放模式：没有 Shizuku 授权时，变声与语音包改为扬声器外放，声卡禁用 */
    val speakerMode: Boolean = false,
    /** 用户选了普通模式（不走 Shizuku） */
    val forceNormal: Boolean = false,
    /** 录播（外放模式）：转换中 / 进度 / 结果 */
    val clipBusy: Boolean = false,
    val clipStage: String = "",
    val clipProgress: Float = 0f,
    val clipFile: File? = null,
    val clipSeconds: Float = 0f,
    /** 仓库里 TTS 文件总大小（0=仓库没有 TTS） */
    val repoTtsSize: Long = 0L,
    /** 合成参数页面的高级参数展开 */
    val ttsAdvanced: Boolean = false,
    /** 耳返 / 实时试听 */
    val liveMonitor: Boolean = false,
    /** 效果链参数（FxChain.P_*）与当前预设名 */
    val fx: List<Float> = com.taocrypt.micmask.inject.FxChain.neutral().toList(),
    val fxPreset: String = "原声",
    /** 0=AI 1=音调 2=声卡：三种模式各自独立（音调、效果参数互不影响） */
    val liveMode: Int = 0,
    val pitchSemis: Float = 0f,
    val aiSemis: Float = 0f,
    /** 输出音量增益 dB（实时变声），AI / 普通各一份 */
    val aiGain: Float = 0f,
    val pitchGain: Float = 0f,
    /** AI 实时高级：档位 0 自动/1 标准/2 省电、静音门限 dB、特征/合成线程 */
    val aiTier: Int = 0,
    val aiGate: Float = -50f,
    val aiFeatTh: Int = 3,
    val aiSynTh: Int = 4,
    val liveFloat: Boolean = false,
    val fxCard: List<Float> = com.taocrypt.micmask.inject.FxChain.neutral().toList(),
    val fxCardPreset: String = "原声",
    /** 用户自存预设：mode(1/2) → 列表 */
    val userPresets: Map<Int, List<UserPreset>> = emptyMap(),
    /** 录制预览倒计时（秒，0=空闲） */
    val previewLeft: Int = 0,
    /** 变声页当前显示 0=AI 1=音调 */
    val voiceTab: Int = 0,
    // ---------------- 语音包 ----------------
    val sounds: List<SoundBoard.Sound> = emptyList(),
    val repoSounds: List<com.taocrypt.micmask.repo.ModelRepo.RSound> = emptyList(),
    /** 正在播放的素材 id */
    val sfxPlaying: String? = null,
    val sfxGain: Float = 0.8f,
    val sfxLoop: Boolean = false,
    /** 导入/下载中提示（空=空闲） */
    val sfxBusy: String = "",
    /** 语音页分类 0=效果包 1=本地音频 */
    val sfxTab: Int = 0,
    /** 音效多选：null = 未在多选 */
    val sfxSel: Set<String>? = null,
    /** 多选条点「重命名」→ 对应分组的卡片弹编辑框 */
    val sfxRename: String? = null,
    /** 页面内试听中的素材 id（走扬声器） */
    val sfxPreview: String? = null,
    /** 预设/效果包 卡片网格（true）或列表 */
    val gridLayout: Boolean = true,
)

private typealias FxChain = com.taocrypt.micmask.inject.FxChain
const val GAIN_MIN = -12f
const val GAIN_MAX = 18f

class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        @Volatile var current: MainViewModel? = null
        /** 不内置任何模型仓库：用户在设置里填自己有权使用的仓库 */
        const val DEFAULT_REPO = ""
        /** 仓库列表：新键 repos（JSON），没有就沿用 alpha41 的 repoUrls 或更早的单个 repoUrl */
        fun loadRepos(ctx: android.content.Context): List<RepoEntry> {
            val sp = Prefs.main(ctx)
            sp.getString("repos", null)?.let { js -> runCatching {
                val a = org.json.JSONArray(js)
                return (0 until a.length()).map { a.getJSONObject(it) }
                    .map { RepoEntry(it.optString("url"), it.optString("name"), it.optBoolean("on", true)) }.filter { it.url.isNotBlank() }
            } }
            val l = sp.getString("repoUrls", null)?.split('\n') ?: listOf(sp.getString("repoUrl", null).orEmpty())
            return l.map { it.trim() }.filter { it.isNotEmpty() }.distinct().map { RepoEntry(it) }
        }
        /**
         * 无障碍**自动**触发时的外放时长（秒）。
         *
         * 手动操作一律「点一次播一次」（[playPendingNow]），不用这个值。
         * 只有无障碍自动触发时才需要它：那时用户可能还没切到 QQ，
         * 得让声音多留一会儿才来得及按住说话。
         */
        const val QQ_LOOP_SEC = 12
    }

    private val ctx: Context get() = getApplication()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var engine: VcEngine? = null
    private var ttsEngine: TtsEngine? = null
    private var pendingSrc: Uri? = null
    private var pending16k: FloatArray? = null

    private val recorder = Recorder(app)

    private val prefs = Prefs.main(ctx)

    init {
        // 开始实时变声时引擎会测速/读缓存，把结果记到当前音色上（自动降档的临时结果不记）
        com.taocrypt.micmask.inject.MicInjector.onBench = { r ->
            val v = _state.value.liveVoice
            if (v.isNotEmpty() && !r.contains("自动")) {
                ctx.getSharedPreferences("live", 0).edit().putString("bench_$v", r).apply()
                _state.value = _state.value.copy(liveBench = r)
            }
        }
        com.taocrypt.micmask.inject.MicInjector.forceNormal = prefs.getBoolean("force_normal", false)
        _state.value = _state.value.copy(forceNormal = com.taocrypt.micmask.inject.MicInjector.forceNormal)
        // Toast 由 ViewModel 直接用系统 Toast 弹出：App 在后台（悬浮窗操作）时也能看到
        viewModelScope.launch {
            _state.collect { st -> st.toast?.let { msg ->
                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                _state.value = _state.value.copy(toast = null)
            } }
        }
        _state.value = _state.value.copy(liveAec = ctx.getSharedPreferences("live", 0).getBoolean("aec", true), liveBench = "",
            repos = loadRepos(ctx), repoUrl = loadRepos(ctx).firstOrNull { it.on }?.url ?: DEFAULT_REPO)
        refreshVoices()
        migrateRoutingOnce()
        runCatching { restoreLiveFloat() }
        // 旧的独立悬浮球（FloatingPlayService）已并入变声悬浮窗：升级后若还开着就关掉
        runCatching { if (FloatingPlayService.running) FloatingPlayService.toggle(ctx, false) }

        val s = _state.value
        _state.value = s.copy(
            pitchShift = prefs.getInt("pitch", 0),
            indexRate = prefs.getFloat("indexRate", 0.3f),
            protect = prefs.getFloat("protect", 0.33f),
            sid = prefs.getInt("sid", 0),
            overlap = prefs.getInt("overlap", 64),
            useIndex = prefs.getBoolean("useIndex", true),
            threads = prefs.getInt("threads", 0),
            ttsLang = prefs.getString("ttsLang", "zh") ?: "zh",
            ttsSpeed = prefs.getFloat("ttsSpeed", 1.0f),
            ttsTemperature = prefs.getFloat("ttsTemperature", 1.0f),
            ttsTopK = prefs.getInt("ttsTopK", 15),
            ttsTopP = prefs.getFloat("ttsTopP", 1.0f),
            ttsRepetition = prefs.getFloat("ttsRepetition", 1.35f),
            qqAutoSend = prefs.getBoolean("qqAutoSend", false),
            // 默认外放：耳机通路已实测证伪（耳机麦收不到喇叭声）
            qqHeadsetMode = prefs.getBoolean("qqHeadsetMode", false),
        )
        // bind() 会先从 qq_pending.txt 恢复「上次待发送的语音」，
        // 再用 prefs 里的开关覆盖 armed（prefs 是开关的唯一真源）。
        VoiceSender.bind(ctx)
        VoiceSender.setArmedState(_state.value.qqAutoSend)
        // bind() 可能从磁盘恢复了上次合成的待发语音 → 同步进 UI，
        // 否则界面会显示「还没有待发送语音」但内部其实有（按钮点了没反应）。
        _state.value = _state.value.copy(qqPendingName = VoiceSender.pendingFile?.name)
        // ⚠️ 必须订阅播放状态：循环播放是 Handler 定时自动停的，
        // 不回调的话 UI 会一直停在「正在外放中」，「开始代发」按钮也就一直点不动。
        VoiceSender.injectTarget = prefs.getString("qqInjectTarget", null)
            ?: com.taocrypt.micmask.inject.MicInjector.DEFAULT_TARGET
        VoiceSender.applyRoute(
            if (prefs.getBoolean("qqInjectMode", false)) VoiceSender.Route.INJECT
            else if (prefs.getBoolean("qqHeadsetMode", false)) VoiceSender.Route.HEADSET
            else VoiceSender.Route.SPEAKER
        )
        _state.value = _state.value.copy(
            qqInjectMode = prefs.getBoolean("qqInjectMode", false),
            qqInjectTarget = VoiceSender.injectTarget,
        )
        refreshInjectState()
        _state.value = _state.value.copy(qqFloating = FloatingPlayService.running)
        refreshHeadsetState()
        VoiceSender.onState = { st ->
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
                _state.value = _state.value.copy(
                    qqPlaying = st == VoiceSender.State.PLAYING,
                    qqPendingName = VoiceSender.pendingFile?.name,
                    // 播放开始/结束时再读一次设备名：getRoutedDevice() 是异步解析的，
                    // 同步读会拿到「未知」。
                    qqLastOutput = VoiceSender.lastOutputName,
                    qqBlockedByHeadset = VoiceSender.lastBlockedByHeadset,
                    qqHeadsetOn = VoiceSender.hasHeadset(ctx),
                    qqHeadsetName = VoiceSender.headsetName(ctx),
                    qqInjectMsg = VoiceSender.injectMessage,
                )
            }
        }
        refreshModels()
        refreshTtsModels()
        refreshLibrary()
        // 全量包：首次启动把 APK 里的模型解包到工作目录，用户装完即用。
        // 轻量包 assets 里没有 models/，这里会立刻返回。
        startAutoImport()
        restoreLastRecording()
        autoRunIfRequested()
        loopTestIfRequested()
        ttsAutoRunIfRequested()
        viewModelScope.launch {
            recorder.state.collect { r ->
                _state.value = _state.value.copy(
                    recording = r.recording,
                    recElapsedMs = r.elapsedMs,
                    recLevel = r.level,
                    recPeakLevel = r.peakLevel,
                    recSampleRate = r.sampleRate,
                )
            }
        }
    }

    /**
     * 一次性路由迁移（版本 2）。
     *
     * 背景：v1 的默认路由是 `qqHeadsetMode = true`（"耳机当虚拟麦"），
     * 但那条路后来被实测**证伪**（耳机麦收不到喇叭声 → QQ 录成静音）。
     * 而 `getBoolean(key, false)` 遇到已存在的 key 会直接读旧值 `true`，
     * 于是**老用户升级后仍然停在已废弃的耳机模式上**，表现为"QQ 里什么声音都没有"。
     *
     * 这里做一次硬迁移：只在首次跑到 v2 时把路由重置为外放。
     * 之后用户手动改回耳机也尊重（但会在 UI 上看到"此模式 QQ 会录成静音"的警告）。
     */
    private fun migrateRoutingOnce() {
        val v = prefs.getInt("routeMigrated", 0)
        if (v >= 2) return
        prefs.edit()
            .putBoolean("qqHeadsetMode", false)
            .putInt("routeMigrated", 2)
            .apply()
        L.i("路由迁移 v1->v2：qqHeadsetMode 强制置为 false（外放），原值=$v")
    }

    // ------------------------------------------------ 首次启动自动导入模型

    /**
     * 把 APK 里打包的模型解包到外部私有目录。
     *
     * 放在后台 IO 线程 —— 910 MB 的拷贝，UI 线程会直接 ANR。
     * 已存在且大小一致的文件会跳过，所以第二次启动是一瞬间的事。
     */
    private fun startAutoImport() {
        if (!ModelStore.hasBundledModels(ctx) && !TtsAssetStore.hasBundledModels(ctx)) return
        if (_state.value.importing) return

        viewModelScope.launch {
            _state.value = _state.value.copy(importing = true, importDone = 0,
                importTotal = 0, importName = tr("正在准备…"))
            val copied = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val n1 = ModelStore.ensureExtractedFromAssets(ctx) { d, t, n ->
                        _state.value = _state.value.copy(
                            importDone = d, importTotal = t, importName = n)
                    }
                    val n2 = TtsAssetStore.ensureExtractedFromAssets(ctx) { d, t, n ->
                        _state.value = _state.value.copy(
                            importDone = d, importTotal = t, importName = n)
                    }
                    n1 + n2
                }.getOrElse { t ->
                    L.w("自动导入模型失败：${t.message}")
                    -1
                }
            }
            L.i("自动导入完成：copied=$copied")
            _state.value = _state.value.copy(importing = false, importName = "")
            refreshModels()
            refreshTtsModels()
            if (copied > 0) {
                _state.value = _state.value.copy(toast = tr("模型已自动导入完成（{0} 个文件）", copied))
            } else if (copied < 0) {
                _state.value = _state.value.copy(error = tr("模型自动导入失败，请检查存储空间后重试"))
            }
        }
    }

    /**
     * 从手机里选模型导入：可以选模型 zip 包，也可以直接多选那几个 .onnx/.bin。
     * 只认 ModelStore 里的四个文件名，写到内部私有目录（先写 .part 再改名）。
     */
    private var compat = 0
    private var compatErr: String? = null
    fun importModelFiles(uris: List<Uri>) {
        if (uris.isEmpty() || _state.value.importing) return
        val known0 = (ModelStore.required + ModelStore.optional).toSet()
        val known = object : AbstractSet<String>() { override val size get() = known0.size; override fun iterator() = known0.iterator(); override fun contains(element: String) = element in known0 || ModelStore.VOICE_RE.matches(element) }
        viewModelScope.launch {
            _state.value = _state.value.copy(importing = true, importDone = 0,
                importTotal = known.size, importName = tr("正在读取…"))
            val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
                runCatching {
                    val dir = ModelStore.internalDir(ctx).also { it.mkdirs() }
                    var n = 0
                    compat = 0; compatErr = null
                    fun save(name: String, input: java.io.InputStream) {
                        _state.value = _state.value.copy(importName = name)
                        val tmp = File(dir, "$name.part")
                        tmp.outputStream().use { input.copyTo(it, 1 shl 20) }
                        val dst = File(dir, name)
                        dst.delete()
                        if (!tmp.renameTo(dst)) throw java.io.IOException(tr("改名失败：{0}", name))
                        ModelStore.externalDir(ctx).let { File(it, name).delete() }
                        n++
                        _state.value = _state.value.copy(importDone = n)
                    }
                    for (u in uris) {
                        val disp = queryName(u) ?: u.lastPathSegment.orEmpty()
                        val base = disp.substringAfterLast('/')
                        if (base.lowercase().endsWith(".zip")) {
                            ctx.contentResolver.openInputStream(u)!!.use { raw ->
                                java.util.zip.ZipInputStream(raw.buffered(1 shl 20)).use { z ->
                                    while (true) {
                                        val e = z.nextEntry ?: break
                                        val en = e.name.substringAfterLast('/')
                                        if (!e.isDirectory && en in known) save(en, z)
                                    }
                                }
                            }
                        } else if (base in known) {
                            ctx.contentResolver.openInputStream(u)!!.use { save(base, it) }
                        } else if (base.lowercase().endsWith(".onnx")) {
                            // 兼容模式：w-okada voice-changer 导出的 RVC ONNX，先落盘检查再改名成 <音色>_stream_compat.onnx
                            val tmp = File(dir, "compat_import.part")
                            _state.value = _state.value.copy(importName = base)
                            ctx.contentResolver.openInputStream(u)!!.use { i -> tmp.outputStream().use { i.copyTo(it, 1 shl 20) } }
                            val why = ModelStore.checkCompatOnnx(tmp)
                            if (why != null) { tmp.delete(); L.w("兼容导入 $base 失败：$why"); compatErr = "$base：$why"; continue }
                            val vid = base.substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N}-]+"), "_").replace("_stream", "").trim('_').ifEmpty { "compat" }
                            val dst = File(dir, "${vid}_stream_compat.onnx")
                            dst.delete()
                            if (!tmp.renameTo(dst)) throw java.io.IOException(tr("改名失败：{0}", dst.name))
                            L.i("兼容模式导入 $base → ${dst.name}")
                            n++; compat++
                            _state.value = _state.value.copy(importDone = n)
                        } else {
                            L.w("跳过不认识的文件：$base")
                        }
                    }
                    n
                }
            }
            _state.value = _state.value.copy(importing = false, importName = "")
            refreshModels()
            _state.value = _state.value.copy(
                toast = result.fold(
                    { when {
                        compat > 0 -> tr("已按兼容模式导入 {0} 个音色：录音变声可用，实时较慢只建议高性能机；用 convert_rvc.py 转成流式版会快很多", compat)
                        it > 0 -> tr("已导入 {0} 个模型文件", it)
                        compatErr != null -> tr("无法导入：{0}", compatErr)
                        else -> tr("没找到模型文件（要选模型 zip 或那几个 .onnx/.bin）")
                    } },
                    { L.e("模型导入失败", it); tr("模型导入失败：{0}", it.message) },
                )
            )
        }
    }

    private fun queryName(u: Uri): String? = runCatching {
        ctx.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    /** 设置页「重新导入模型」入口：清掉半截文件后再跑一次 */
    fun reimportModels() {
        if (_state.value.importing) return
        viewModelScope.launch {
            _state.value = _state.value.copy(importing = true, importDone = 0,
                importTotal = 0, importName = tr("正在准备…"))
            val copied = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    ModelStore.ensureExtractedFromAssets(ctx) { d, t, n ->
                        _state.value = _state.value.copy(
                            importDone = d, importTotal = t, importName = n)
                    } + TtsAssetStore.ensureExtractedFromAssets(ctx) { d, t, n ->
                        _state.value = _state.value.copy(
                            importDone = d, importTotal = t, importName = n)
                    }
                }.getOrElse { -1 }
            }
            _state.value = _state.value.copy(importing = false, importName = "")
            refreshModels()
            refreshTtsModels()
            _state.value = _state.value.copy(
                toast = if (copied > 0) tr("已导入 {0} 个模型文件", copied) else tr("模型已是最新")
            )
        }
    }

    fun refreshModels() {
        val dir = ModelStore.modelDir(ctx)
        runCatching { refreshVoices() }
        val st = ModelStore.status(ctx)
        L.i("models dir=${dir.absolutePath} ready=${st.ready} " +
            st.items.joinToString(", ") { "${it.name}=${if (it.present) "${it.sizeBytes / 1048576}MB" else "缺"}" })
        _state.value = _state.value.copy(
            modelDir = dir.absolutePath,
            modelItems = st.items,
            modelsReady = st.ready,
        )
    }

    fun setPitch(v: Int) { prefs.edit().putInt("pitch", v).apply(); _state.value = _state.value.copy(pitchShift = v) }
    fun setIndexRate(v: Float) { prefs.edit().putFloat("indexRate", v).apply(); _state.value = _state.value.copy(indexRate = v) }
    fun setProtect(v: Float) { prefs.edit().putFloat("protect", v).apply(); _state.value = _state.value.copy(protect = v) }
    fun setSid(v: Int) { prefs.edit().putInt("sid", v).apply(); _state.value = _state.value.copy(sid = v) }
    fun setOverlap(v: Int) { prefs.edit().putInt("overlap", v).apply(); _state.value = _state.value.copy(overlap = v) }
    fun setUseIndex(v: Boolean) { prefs.edit().putBoolean("useIndex", v).apply(); _state.value = _state.value.copy(useIndex = v) }
    /** 文件变声参数恢复默认（变调/检索/保护/说话人/重叠/线程） */
    fun resetFileParams() {
        prefs.edit().remove("pitch").remove("indexRate").remove("protect").remove("sid").remove("overlap").remove("useIndex").remove("threads").apply()
        _state.value = _state.value.copy(pitchShift = 0, indexRate = 0.3f, protect = 0.33f, sid = 0, overlap = 64, useIndex = true, threads = 0)
    }
    fun setThreads(v: Int) { prefs.edit().putInt("threads", v).apply(); _state.value = _state.value.copy(threads = v) }

    // ------------------------------------------------ QQ 语音代发

    /** 开关「QQ 录音时自动外放」。无障碍服务必须另外在系统设置里手动开启。 */
    fun setQqAutoSend(v: Boolean) {
        prefs.edit().putBoolean("qqAutoSend", v).apply()
        VoiceSender.setArmedState(v)
        _state.value = _state.value.copy(qqAutoSend = v)
        L.i("QQ 语音代发 = $v（无障碍服务 running=${QqWatchService.running}）")
    }

    /**
     * 刷新无障碍服务开启状态（从设置页返回时调用）。
     *
     * 用 Settings.Secure 查系统设置，而不是只看 QqWatchService.running：
     * 后者是进程内静态标志，App 进程被系统回收后重新打开时会是 false，
     * 于是界面明明开着无障碍却显示「未开启」。
     */
    fun refreshQqWatchState() {
        val on = isQqWatchEnabled() || QqWatchService.running
        if (on && _state.value.qqAutoSend) VoiceSender.armed = true
        _state.value = _state.value.copy(
            qqWatchEnabled = on,
            qqPendingName = VoiceSender.pendingFile?.name,
            qqPlaying = VoiceSender.isPlaying,
        )
    }

    /** 系统「无障碍」列表里是否启用了本服务的 QQ 监听 */
    private fun isQqWatchEnabled(): Boolean {
        val want = ctx.packageName + "http://x/" + QqWatchService::class.java.name
        val want2 = ctx.packageName + "/" + QqWatchService::class.java.name
        val flat = runCatching {
            android.provider.Settings.Secure.getString(
                ctx.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
        }.getOrNull()
        if (flat.isNullOrBlank()) return false
        val hit = flat.split(":").any { it.equals(want2, ignoreCase = true) || it.equals(want, ignoreCase = true) }
        L.i("无障碍列表：$flat -> hit=$hit")
        return hit
    }

    /** 把刚合成好的音频设为「待发送」 */
    fun armQqPending(file: File) {
        VoiceSender.arm(file)
        _state.value = _state.value.copy(qqPendingName = file.name)
    }

    /** 刷新耳机检测结果（插拔耳机 / 连断蓝牙后调用） */
    fun refreshHeadsetState() {
        _state.value = _state.value.copy(
            qqHeadsetOn = VoiceSender.hasHeadset(ctx),
            qqHeadsetName = VoiceSender.headsetName(ctx),
        )
    }

    // ---------------- 测试版：Shizuku 麦克风注入 ----------------

    fun refreshInjectState() {
        val inj = com.taocrypt.micmask.inject.MicInjector
        val txt = when {
            !inj.shizukuRunning() -> tr("Shizuku 未运行（先装 Shizuku 并用无线调试启动）")
            !inj.shizukuPermitted() -> tr("Shizuku 已运行，未授权本 App")
            else -> tr("Shizuku 已授权") + (if (inj.binder != null) tr("，注入服务：") + inj.ping() else "")
        }
        val uids = _state.value.qqInjectTarget.split(',', ' ', ';').filter { it.isNotBlank() }
            .joinToString { p -> val u = inj.uidOf(ctx, p.trim()); if (u < 0) tr("{0} 未安装", p.trim()) else "uid=$u" }
        _state.value = _state.value.copy(
            speakerMode = !inj.shizukuGranted(),
            qqShizuku = txt + tr("\n目标：{0}", uids) + "\nAndroid ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            qqInjectMsg = VoiceSender.injectMessage,
        )
    }

    fun toast(msg: String) { _state.value = _state.value.copy(toast = msg) }

    /** 无条件停止（悬浮窗/通知用）：不看状态，一律下发 DISARM */
    fun stopAll() {
        stopSoundLocal(); stopPreview()
        com.taocrypt.micmask.inject.MicInjector.stopLive()
        LiveService.stop(ctx)
        SpeakerSfx.stopSfx()
        _state.value = _state.value.copy(liveOn = false, liveMsg = tr("已停止"), sfxPlaying = null)
        L.i("UI 停止（悬浮窗）")
    }

    /** 当前是否走外放模式（无 Shizuku 授权） */
    fun speakerMode(): Boolean = !com.taocrypt.micmask.inject.MicInjector.shizukuGranted().also { g ->
        if (_state.value.speakerMode == g) _state.value = _state.value.copy(speakerMode = !g)
    }

    // ---------------- 录播（外放模式）：按住录一句 → 松手转换 → 设为悬浮球待发送 ----------------
    private var clipEngine: LiveRvc? = null
    private var clipEngineVoice: String? = null

    fun clipStart() {
        if (_state.value.clipBusy || _state.value.recording) return
        startRecording()
    }

    fun clipCancel() = cancelRecording()

    /** mode 0=AI 音色，1=音调（变调 + 音调页效果） */
    /** @param autoPlay 主页松手直接外放；悬浮窗传 false，改为手动点播放 */
    fun clipStop(mode: Int, autoPlay: Boolean = true) {
        if (!_state.value.recording) return
        val r = recorder.stop()
        _state.value = _state.value.copy(recording = false, recElapsedMs = 0L, recLevel = 0f)
        if (r == null) { _state.value = _state.value.copy(toast = tr("太短了，按住多说一会儿")); return }
        clipRaw = r.file.absolutePath; clipSigDone = null
        clipConvert(mode, autoPlay, again = false)
    }

    // 录播原声：保留最近一次录音，切换音色/预设后可直接重新转换，不用重录
    private var clipRaw: String? = null
    private var clipSigDone: String? = null

    /** 当前转换参数的签名：AI=音色+音高+档位，普通=音调+效果 */
    private fun clipSig(mode: Int): String {
        val st = _state.value
        return if (mode == 0) "0|${ModelStore.currentVoice(ctx)}|${prefs.getFloat("ai_semis", 0f).toInt()}|${st.aiTier}|${st.aiGate}|${st.aiFeatTh}|${st.aiSynTh}"
        else "1|${st.pitchSemis}|${st.fx.joinToString(",")}"
    }

    private fun clipStale(mode: Int): Boolean =
        clipRaw?.let { File(it).exists() } == true && clipSig(mode) != clipSigDone

    /** 悬浮窗切换音色/预设后调用：有原声且参数变了就重新转换（不自动外放） */
    fun clipReconvertIfStale(mode: Int) {
        val st = _state.value
        if (st.clipBusy || st.recording || st.qqPlaying || !clipStale(mode)) return
        clipConvert(mode, autoPlay = false, again = true)
    }

    /** 悬浮窗 ▶：参数变了先按当前音色/预设重新转换再播，否则直接播 */
    fun clipPlay(mode: Int) {
        val st = _state.value
        if (st.clipBusy || st.recording) return
        if (clipStale(mode)) clipConvert(mode, autoPlay = true, again = true) else playPendingNow()
    }

    private fun clipConvert(mode: Int, autoPlay: Boolean, again: Boolean) {
        val raw = clipRaw ?: return
        val sig = clipSig(mode)
        val st0 = _state.value
        _state.value = st0.copy(clipBusy = true, clipStage = tr("读取录音"), clipProgress = 0f)
        viewModelScope.launch {
            val res = kotlinx.coroutines.withContext(Dispatchers.Default) {
                runCatching {
                    val x = SoundBoard.decode(null, raw)
                    val y: ShortArray = if (mode == 0) {
                        val voice = ModelStore.currentVoice(ctx) ?: throw IllegalStateException(tr("还没有音色，先在变声页下载"))
                        val tu = _state.value
                        // 档位/线程变了要换新引擎（release 后执行器不可复用）
                        clipEngine?.let { if (clipEngineVoice == voice && it.tune(tu.aiTier, tu.aiGate, tu.aiFeatTh, tu.aiSynTh)) { it.release(); clipEngine = null } }
                        val e = clipEngine?.takeIf { clipEngineVoice == voice } ?: run {
                            clipEngine?.release()
                            LiveRvc { canon -> ModelStore.locate(ctx, if (canon.startsWith(ModelStore.DEFAULT_VOICE + "_stream")) ModelStore.voiceFile(voice, canon) else canon) }
                                .also { it.pickCache = File(ctx.filesDir, "pick_clip_$voice.txt"); it.tune(tu.aiTier, tu.aiGate, tu.aiFeatTh, tu.aiSynTh); clipEngine = it; clipEngineVoice = voice }
                        }
                        e.semis = prefs.getFloat("ai_semis", 0f).toInt()
                        _state.value = _state.value.copy(clipStage = tr("加载模型"))
                        e.load({ m -> _state.value = _state.value.copy(clipStage = m) })
                        _state.value = _state.value.copy(clipStage = tr("AI 变声"))
                        e.offline(x) { p -> _state.value = _state.value.copy(clipProgress = p) }
                    } else {
                        _state.value = _state.value.copy(clipStage = tr("变调"))
                        val sh = com.taocrypt.micmask.inject.PitchShifter(SoundBoard.SR)
                        val ratio = Math.pow(2.0, st0.pitchSemis / 12.0).toFloat()
                        val fx = com.taocrypt.micmask.inject.FxChain(SoundBoard.SR).also { it.set(st0.fx.toFloatArray()) }
                        val out = ShortArray(x.size); val hop = SoundBoard.SR / 100; val buf = FloatArray(hop)
                        var i = 0
                        while (i < x.size) {
                            val n = minOf(hop, x.size - i)
                            for (k in 0 until n) { val v = x[i + k] / 32768f; buf[k] = if (ratio == 1f) v else sh.process(v, ratio) }
                            for (k in n until hop) buf[k] = 0f
                            fx.process(buf, hop)
                            for (k in 0 until n) out[i + k] = (buf[k].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                            i += n; if (i % (hop * 50) == 0) _state.value = _state.value.copy(clipProgress = i.toFloat() / x.size)
                        }
                        out
                    }
                    val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "clips").also { it.mkdirs() }
                    dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(9)?.forEach { it.delete() }
                    val f = File(dir, "clip_${System.currentTimeMillis()}.wav")
                    var pk = 1e-6f; for (v in y) pk = maxOf(pk, kotlin.math.abs(v / 32768f))
                    micmask.core.Wav.write(f, FloatArray(y.size) { y[it] / 32768f }, SoundBoard.SR, peak = minOf(0.95f, pk))
                    f to y.size / SoundBoard.SR.toFloat()
                }
            }
            res.onSuccess { (f, sec) ->
                clipSigDone = sig
                armQqPending(f)
                L.i("录播 ${if (mode == 0) "AI" else "普通"} → ${f.name} %.1fs".format(sec))
                // 松手转换完直接外放（不再需要单独的悬浮球/外放按钮）
                if (autoPlay) runCatching { playPendingNow() }
                _state.value = _state.value.copy(clipBusy = false, clipStage = "", clipFile = f, clipSeconds = sec,
                    toast = when {
                        autoPlay -> tr("转换完成，正在外放")
                        again -> tr("已按当前音色重新转换，点 ▶ 播放")
                        else -> tr("转换完成，点 ▶ 播放")
                    })
            }.onFailure {
                L.e("录播转换失败", it)
                _state.value = _state.value.copy(clipBusy = false, clipStage = "", toast = tr("转换失败：{0}", it.message))
            }
        }
    }

    fun toggleLive() {
        val inj = com.taocrypt.micmask.inject.MicInjector
        if (!inj.liveOn && !_state.value.liveOn && speakerMode()) {
            _state.value = _state.value.copy(toast = if (_state.value.liveMode == 2) tr("外放模式下声卡不可用（需要 Shizuku）") else tr("未授权 Shizuku：当前为录播模式，在变声页按住按钮录一句"))
            return
        }
        if (inj.liveOn || _state.value.liveOn) {
            stopSoundLocal()
            inj.stopLive()
            // 推理在 shell 进程，随 DISARM 停止
            LiveService.stop(ctx)
            _state.value = _state.value.copy(liveOn = false, liveMsg = tr("已停止"))
            return
        }
        val src = if (_state.value.liveAec) android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION else android.media.MediaRecorder.AudioSource.MIC
        if (whitelist().isEmpty()) L.i("UI 白名单为空：只做本机试听（目标=自身）")
        Thread { runCatching { com.taocrypt.micmask.inject.MicInjector.auditPlayAudio() } }.start()
        com.taocrypt.micmask.inject.MicInjector.modeLabel = listOf(tr("AI 变声"), tr("普通变声"), tr("虚拟声卡")).getOrNull(_state.value.liveMode)
        L.i("UI 开始实时变声：模式=${com.taocrypt.micmask.inject.MicInjector.modeLabel} 录音=${if (_state.value.liveAec) "通话(回声消除)" else "原始麦克风"} 半音=${_state.value.liveSemis} 目标=${_state.value.qqInjectTarget}")
        _state.value = _state.value.copy(liveOn = true, liveMsg = tr("启动中…"))
        val cb: (String) -> Unit = { msg ->
            val ended = msg.startsWith("已停止") || msg.contains("失败") || msg.startsWith("缺") || msg.startsWith("没找到") || msg.contains("Shizuku") ||
                msg.startsWith("Stopped") || msg.contains("fail", true) || msg.startsWith("Missing") || msg.startsWith("Not found") || msg.startsWith("Couldn't find")
            _state.value = _state.value.copy(liveOn = inj.liveOn || !ended, liveMsg = msg)
            if (ended && !inj.liveOn && !_state.value.liveAi) LiveService.stop(ctx)
        }
        if (_state.value.liveAi) {
            if (!LiveRvc.modelPresent(ctx)) {
                _state.value = _state.value.copy(liveOn = false, liveMsg = tr("缺流式合成器模型，请先在模型页下载或导入实时模型包"))
                return
            }
            LiveService.onStop = { stopAll() }
            LiveService.start(ctx, liveTarget(), _state.value.liveSemis.toInt(), src, cb)
        } else {
            LiveService.onStop = { stopAll() }
            LiveService.notifyOnly(ctx)
            inj.startLive(ctx, liveTarget(), _state.value.liveSemis,
                src, external = false, status = cb)
        }
        viewModelScope.launch {
            repeat(60) { if (inj.liveOn) { kotlinx.coroutines.delay(600); inj.setFx(activeFx()); return@launch }; kotlinx.coroutines.delay(250) }
        }
        if (_state.value.liveMonitor) viewModelScope.launch {
            repeat(60) { if (inj.liveOn) { kotlinx.coroutines.delay(800); inj.setMonitor(true); return@launch }; kotlinx.coroutines.delay(250) }
        }
    }

    /** 白名单为空时用自身包名占位，变声只用于本机试听/耳返 */
    private fun liveTarget(): String = _state.value.qqInjectTarget.ifBlank { ctx.packageName }


    // ---------------- 自定义预设：存 prefs JSON；分享为文本分享码 ----------------
    private fun loadUserPresets(mode: Int): List<UserPreset> = runCatching {
        val a = org.json.JSONArray(prefs.getString("user_presets_$mode", "[]"))
        (0 until a.length()).map { UserPreset.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())
    private fun saveUserPresets(mode: Int, l: List<UserPreset>) {
        prefs.edit().putString("user_presets_$mode", org.json.JSONArray(l.map { it.toJson() }).toString()).apply()
        _state.value = _state.value.copy(userPresets = _state.value.userPresets + (mode to l))
    }
    fun saveCurrentPreset(mode: Int, name: String) {
        val st = _state.value
        val p = UserPreset(name.trim().ifEmpty { tr("我的预设") }, mode, if (mode == 1) st.pitchSemis else 0f, if (mode == 2) st.fxCard else st.fx)
        saveUserPresets(mode, (st.userPresets[mode].orEmpty().filter { it.name != p.name }) + p)
        if (mode == 2) _state.value = _state.value.copy(fxCardPreset = p.name) else _state.value = _state.value.copy(fxPreset = p.name)
        _state.value = _state.value.copy(toast = tr("已保存「{0}」", p.name))
    }
    fun deleteUserPreset(mode: Int, name: String) = saveUserPresets(mode, _state.value.userPresets[mode].orEmpty().filter { it.name != name })
    fun renameUserPreset(mode: Int, old: String, new: String) {
        if (new.isBlank()) return
        saveUserPresets(mode, _state.value.userPresets[mode].orEmpty().map { if (it.name == old) it.copy(name = new.trim()) else it })
    }
    fun applyUserPreset(p: UserPreset) {
        if (p.mode == 1) setPitchSemis(p.semis)
        applyFxPreset(p.mode, p.name, p.fx.toFloatArray())
    }
    fun sharePreset(p: UserPreset) {
        val code = p.toShareCode()
        val i = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_TEXT, tr("【{0}预设·{1}】复制整段后在 App 里点「导入」\n{2}", if (p.mode == 2) tr("声卡场景") else tr("声线"), p.name, code))
        ctx.startActivity(android.content.Intent.createChooser(i, tr("分享预设")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    /** 从剪贴板导入；返回导入到的模式（失败 -1） */
    fun importPresetFromClipboard(): Int {
        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
        val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
        val p = UserPreset.fromShareCode(text)
        if (p == null) { _state.value = _state.value.copy(toast = tr("剪贴板里没有预设分享码")); return -1 }
        saveUserPresets(p.mode, _state.value.userPresets[p.mode].orEmpty().filter { it.name != p.name } + p)
        applyUserPreset(p)
        _state.value = _state.value.copy(toast = tr("已导入「{0}」到{1}", p.name, if (p.mode == 2) tr("声卡") else tr("普通变声")))
        return p.mode
    }


    private fun loadFx(key: String): List<Float> {
        val raw = prefs.getString(key, null) ?: return com.taocrypt.micmask.inject.FxChain.neutral().toList()
        val a = raw.split(',').mapNotNull { it.toFloatOrNull() }
        return if (a.size == com.taocrypt.micmask.inject.FxChain.N) a else com.taocrypt.micmask.inject.FxChain.neutral().toList()
    }
    /** 当前模式该用的效果参数：AI 不加效果，音调用音色参数，声卡用机架参数 */
    fun activeFx(st: UiState = _state.value): FloatArray = when (st.liveMode) {
        1 -> st.fx.toFloatArray().also { it[FxChain.P_OUT] += st.pitchGain }
        2 -> st.fxCard.toFloatArray()
        else -> FxChain.neutral().also { it[FxChain.P_OUT] = st.aiGain }
    }
    /** mode 0=AI 1=普通；dB */
    fun setGain(mode: Int, db: Float) {
        val v = db.coerceIn(GAIN_MIN, GAIN_MAX)
        _state.value = if (mode == 0) _state.value.copy(aiGain = v) else _state.value.copy(pitchGain = v)
        prefs.edit().putFloat(if (mode == 0) "ai_gain" else "pitch_gain", v).apply()
        pushIfActive(mode)
    }
    private fun pushIfActive(mode: Int) {
        val st = _state.value
        if (st.liveOn && st.liveMode == mode) com.taocrypt.micmask.inject.MicInjector.setFx(activeFx(st))
    }
    /** mode: 1=音调 2=声卡 */
    fun setFx(mode: Int, i: Int, v: Float) {
        if (mode == 2) { val l = _state.value.fxCard.toMutableList(); l[i] = v
            _state.value = _state.value.copy(fxCard = l, fxCardPreset = "自定义"); prefs.edit().putString("fx_card", l.joinToString(",")).putString("fx_card_preset", "自定义").apply()
        } else { val l = _state.value.fx.toMutableList(); l[i] = v
            _state.value = _state.value.copy(fx = l, fxPreset = "自定义"); prefs.edit().putString("fx", l.joinToString(",")).putString("fx_preset", "自定义").apply() }
        pushIfActive(mode)
    }
    fun applyFxPreset(mode: Int, name: String, values: FloatArray) {
        val l = values.toList()
        if (mode == 2) { _state.value = _state.value.copy(fxCard = l, fxCardPreset = name); prefs.edit().putString("fx_card", l.joinToString(",")).putString("fx_card_preset", name).apply() }
        else { _state.value = _state.value.copy(fx = l, fxPreset = name); prefs.edit().putString("fx", l.joinToString(",")).putString("fx_preset", name).apply() }
        L.i("UI 效果预设[$mode] → $name"); pushIfActive(mode)
    }
    fun setPitchSemis(v: Float) {
        _state.value = _state.value.copy(pitchSemis = v); prefs.edit().putFloat("pitch_semis", v).apply()
        if (_state.value.liveOn && _state.value.liveMode == 1) setLiveSemis(v)
    }
    fun initFx() {
        current = this
        _state.value = _state.value.copy(voiceTab = prefs.getInt("voice_tab", 0), sounds = SoundBoard.list(ctx), sfxTab = prefs.getInt("sfx_tab", 0), gridLayout = prefs.getBoolean("grid_layout", true),
            sfxGain = prefs.getFloat("sfx_gain", 0.8f), sfxLoop = prefs.getBoolean("sfx_loop", false), fx = loadFx("fx"), fxPreset = prefs.getString("fx_preset", "原声") ?: "原声",
            fxCard = loadFx("fx_card"), fxCardPreset = prefs.getString("fx_card_preset", "原声") ?: "原声",
            userPresets = mapOf(1 to loadUserPresets(1), 2 to loadUserPresets(2)),
            pitchSemis = prefs.getFloat("pitch_semis", 0f), aiSemis = prefs.getFloat("ai_semis", 0f),
            aiGain = prefs.getFloat("ai_gain", 0f), pitchGain = prefs.getFloat("pitch_gain", 0f), liveMonitor = prefs.getBoolean("monitor", false), liveMode = prefs.getInt("live_mode", 0),
            aiTier = prefs.getInt("ai_tier", 0), aiGate = prefs.getFloat("ai_gate", -50f),
            aiFeatTh = prefs.getInt("ai_feat_th", 3), aiSynTh = prefs.getInt("ai_syn_th", 4))
        syncTune()
    }
    private fun syncTune() {
        val st = _state.value; val inj = com.taocrypt.micmask.inject.MicInjector
        inj.tuneTier = st.aiTier; inj.tuneGate = st.aiGate; inj.tuneFeat = st.aiFeatTh; inj.tuneSyn = st.aiSynTh
    }
    /** AI 实时高级参数；运行中会推给注入进程（档位/线程变了会重载模型，约停顿 1~3 秒） */
    fun setAiTune(tier: Int = _state.value.aiTier, gate: Float = _state.value.aiGate, feat: Int = _state.value.aiFeatTh, syn: Int = _state.value.aiSynTh) {
        val st = _state.value
        if (tier == st.aiTier && gate == st.aiGate && feat == st.aiFeatTh && syn == st.aiSynTh) return
        _state.value = st.copy(aiTier = tier, aiGate = gate, aiFeatTh = feat, aiSynTh = syn)
        prefs.edit().putInt("ai_tier", tier).putFloat("ai_gate", gate).putInt("ai_feat_th", feat).putInt("ai_syn_th", syn).apply()
        syncTune()
        L.i("AI 高级参数：档位=$tier 门限=${gate}dB 线程=$feat/$syn")
    }
    /** 滑杆松手 / 选项点下后推给运行中的引擎 */
    fun commitAiTune() {
        if (_state.value.liveOn && _state.value.liveMode == 0) Thread { com.taocrypt.micmask.inject.MicInjector.pushTune() }.start()
    }
    fun resetAiTune() { setAiTune(0, -50f, 3, 4); commitAiTune() }
    /** 按模式开始/停止；运行中切到别的模式会先停再按新模式重启 */
    fun startMode(mode: Int) {
        if (mode == 2 && !com.taocrypt.micmask.inject.MicInjector.liveOn && speakerMode()) { _state.value = _state.value.copy(toast = tr("外放模式下声卡不可用（需要 Shizuku）")); return }
        val st = _state.value
        if (st.liveOn) {
            if (st.liveMode == mode) { toggleLive(); return }
            val needRestart = (mode == 0) != (st.liveMode == 0)
            selectMode(mode)
            if (needRestart) { toggleLive(); viewModelScope.launch { kotlinx.coroutines.delay(900); toggleLive() } }
            else { com.taocrypt.micmask.inject.MicInjector.setLiveSemis(_state.value.liveSemis); com.taocrypt.micmask.inject.MicInjector.setFx(activeFx())
                com.taocrypt.micmask.inject.MicInjector.modeLabel = listOf(tr("AI 变声"), tr("普通变声"), tr("虚拟声卡")).getOrNull(_state.value.liveMode); LiveService.refresh(ctx) }
            return
        }
        selectMode(mode); toggleLive()
    }
    /** 悬浮窗（普通模式）只切换录播用的模式，不启动 */
    fun pickMode(mode: Int) { if (!_state.value.liveOn) selectMode(mode.coerceIn(0, 1)) }
    private fun selectMode(mode: Int) {
        val semis = when (mode) { 0 -> prefs.getFloat("ai_semis", 0f); 1 -> _state.value.pitchSemis; else -> 0f }
        _state.value = _state.value.copy(liveMode = mode, liveAi = mode == 0, liveSemis = semis)
        prefs.edit().putInt("live_mode", mode).apply()
        com.taocrypt.micmask.inject.MicInjector.modeLabel = listOf(tr("AI 变声"), tr("普通变声"), tr("虚拟声卡")).getOrNull(mode)
        L.i("UI 模式 → ${listOf("AI", "普通", "声卡")[mode]} 半音=$semis")
    }

    fun setVoiceTab(t: Int) { _state.value = _state.value.copy(voiceTab = t); prefs.edit().putInt("voice_tab", t).apply() }

    // ------------------------------------------------ 语音包
    private var sfxJob: kotlinx.coroutines.Job? = null
    fun refreshSounds() {
        viewModelScope.launch {
            val l = kotlinx.coroutines.withContext(Dispatchers.IO) { SoundBoard.installBuiltins(ctx); SoundBoard.list(ctx) }
            _state.value = _state.value.copy(sounds = l)
        }
    }
    fun setSfxTab(t: Int) { _state.value = _state.value.copy(sfxTab = t, sfxSel = null); prefs.edit().putInt("sfx_tab", t).apply() }
    fun setGridLayout(v: Boolean) { _state.value = _state.value.copy(gridLayout = v); prefs.edit().putBoolean("grid_layout", v).apply() }
    /** fx=true 导入效果包（只认 .mim3）；false 导入本地音频 */
    fun importSounds(uris: List<Uri>, fx: Boolean = false) {
        if (uris.isEmpty() || _state.value.sfxBusy.isNotEmpty()) return
        val ok0 = if (fx) uris.filter { SoundBoard.isMim3(ctx, it) } else uris
        val skipped = uris.size - ok0.size
        if (ok0.isEmpty()) { _state.value = _state.value.copy(toast = tr("音效包只支持 .mim3 文件")); return }
        viewModelScope.launch {
            var ok = 0; var fail = ""
            ok0.forEachIndexed { i, u ->
                _state.value = _state.value.copy(sfxBusy = tr("正在解码 {0}/{1}…", i + 1, ok0.size))
                kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { if (fx) SoundBoard.importMim3Uri(ctx, u).size else { SoundBoard.importUri(ctx, u, SoundBoard.PACK_LOCAL); 1 } } }
                    .onSuccess { ok += it }.onFailure { L.e("素材导入失败", it); fail = it.message ?: tr("未知错误") }
            }
            _state.value = _state.value.copy(sfxBusy = "", sounds = SoundBoard.list(ctx),
                toast = tr("已导入 {0} 个", ok) + (if (skipped > 0) tr("，跳过 {0} 个非 .mim3", skipped) else "") + if (fail.isNotEmpty()) tr("，失败：{0}", fail) else "")
        }
    }
    /** 页面内试听：走扬声器，不进麦克风；再点同一个停止 */
    fun previewSound(id: String) {
        if (_state.value.sfxPreview == id) { stopPreview(); return }
        val s = _state.value.sounds.firstOrNull { it.id == id } ?: return
        _state.value = _state.value.copy(sfxPreview = id)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { SoundPreview.play(s.file, _state.value.sfxGain) { if (_state.value.sfxPreview == id) _state.value = _state.value.copy(sfxPreview = null) } }
                .onFailure { L.e("试听失败", it); _state.value = _state.value.copy(sfxPreview = null, toast = tr("试听失败")) }
        }
    }
    fun stopPreview() { SoundPreview.stop(); if (_state.value.sfxPreview != null) _state.value = _state.value.copy(sfxPreview = null) }
    fun downloadRepoSound(r: com.taocrypt.micmask.repo.ModelRepo.RSound) {
        if (_state.value.sfxBusy.isNotEmpty()) return
        _state.value = _state.value.copy(sfxBusy = tr("正在下载 {0}…", r.name))
        viewModelScope.launch {
            val res = kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { com.taocrypt.micmask.repo.ModelRepo.downloadSound(ctx, r) } }
            _state.value = _state.value.copy(sfxBusy = "", sounds = SoundBoard.list(ctx),
                toast = res.fold({ tr("已加入音效包：{0}", r.name) }, { L.e("素材下载失败", it); tr("下载失败：{0}", it.message) }))
        }
    }
    /** 自定义分组：多个音频打包成 .mim3（原样不转码），可选同时导入本机 */
    fun buildMim3(group: String, author: String, desc: String, items: List<SoundBoard.PackItem>, alsoImport: Boolean) {
        if (_state.value.sfxBusy.isNotEmpty()) return
        if (group.isBlank()) { _state.value = _state.value.copy(toast = tr("先填分组名")); return }
        if (items.isEmpty()) { _state.value = _state.value.copy(toast = tr("先选音频")); return }
        _state.value = _state.value.copy(sfxBusy = tr("正在打包…"))
        viewModelScope.launch {
            val r = kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching {
                val path = SoundBoard.buildMim3(ctx, group, author, desc, items)
                var n = 0
                if (alsoImport) {
                    val tmp = java.io.File(ctx.cacheDir, "self.mim3")
                    // 直接再读一遍生成的文件导入，保证和别人拿到的一致
                    val uri = ctx.contentResolver.query(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(android.provider.MediaStore.Downloads._ID),
                        android.provider.MediaStore.Downloads.DISPLAY_NAME + "=?", arrayOf(path.substringAfterLast('/')), android.provider.MediaStore.Downloads.DATE_ADDED + " DESC")
                        ?.use { c -> if (c.moveToFirst()) android.content.ContentUris.withAppendedId(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0)) else null }
                    if (uri != null) { ctx.contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                        n = SoundBoard.importMim3(ctx, tmp, group).size; tmp.delete() }
                }
                path to n
            } }
            _state.value = _state.value.copy(sfxBusy = "", sounds = SoundBoard.list(ctx), toast = r.fold(
                { (p, n) -> tr("已打包到 {0}", p) + if (n > 0) tr("，并导入 {0} 个", n) else "" }, { L.e("mim3 打包失败", it); tr("打包失败：{0}", it.message) }))
        }
    }
    fun packInfo(group: String) = SoundBoard.packInfo(ctx, group)
    fun fxGroups() = SoundBoard.fxGroups(ctx)
    /** 文件变声结果加进音效包分组 */
    fun addFileToGroup(f: File, group: String, name: String) {
        val g = SoundBoard.safe(group.ifBlank { tr("我的音效") })
        if (g == SoundBoard.PACK_LOCAL) { _state.value = _state.value.copy(toast = tr("分组名不能叫「本地」")); return }
        viewModelScope.launch {
            val r = kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { SoundBoard.importFile(ctx, f, g, SoundBoard.safe(name.ifBlank { f.nameWithoutExtension }.take(40))) } }
            _state.value = _state.value.copy(sounds = SoundBoard.list(ctx),
                toast = r.fold({ tr("已加入「{0}」", if (g == SoundBoard.PACK_FX) tr("预设音效") else g) }, { L.e("加入音效包失败", it); tr("加入失败：{0}", it.message) }))
        }
    }
    /** 文件变声结果：有 Shizuku 送麦，没有就设为待发送（交给变声页的悬浮球外放） */
    fun sendOrArm(f: File) {
        if (speakerMode()) { armQqPending(f); runCatching { playPendingNow() }; _state.value = _state.value.copy(toast = tr("正在外放")) }
        else sendFileToMic(f, f.nameWithoutExtension)
    }

    /** 把单独的 mp3 等音频直接加进音效包的某个分组（新分组会自动创建） */
    fun importToGroup(uris: List<Uri>, group: String) {
        val g = SoundBoard.safe(group.ifBlank { tr("我的音效") })
        if (uris.isEmpty() || _state.value.sfxBusy.isNotEmpty()) return
        if (g == SoundBoard.PACK_LOCAL) { _state.value = _state.value.copy(toast = tr("分组名不能叫「本地」")); return }
        viewModelScope.launch {
            var ok = 0; var fail = ""
            uris.forEachIndexed { i, u ->
                _state.value = _state.value.copy(sfxBusy = tr("正在添加 {0}/{1}…", i + 1, uris.size))
                kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { SoundBoard.importUri(ctx, u, g) } }
                    .onSuccess { ok++ }.onFailure { fail = it.message ?: tr("解码失败"); L.e("添加音效失败", it) }
            }
            _state.value = _state.value.copy(sfxBusy = "", sounds = SoundBoard.list(ctx),
                toast = tr("已添加 {0} 个到「{1}」", ok, if (g == SoundBoard.PACK_FX) tr("预设音效") else g) + if (fail.isNotEmpty()) tr("，{0} 个失败：{1}", uris.size - ok, fail) else "")
        }
    }
    /** 把音效包里一个分组导出成 .mim3 */
    fun exportGroup(group: String) {
        if (_state.value.sfxBusy.isNotEmpty()) return
        _state.value = _state.value.copy(sfxBusy = tr("正在导出…"))
        viewModelScope.launch {
            val r = kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { SoundBoard.exportGroup(ctx, group) } }
            _state.value = _state.value.copy(sfxBusy = "", toast = r.fold({ tr("已导出到 {0}", it) }, { L.e("mim3 导出失败", it); tr("导出失败：{0}", it.message) }))
        }
    }
    /** 一键还原：把被设成 ignore/deny 的 PLAY_AUDIO 改回 allow，并清掉残留注入进程 */
    fun repairAudio() {
        if (_state.value.sfxBusy.isNotEmpty()) return
        stopAll()
        _state.value = _state.value.copy(toast = tr("正在检查…"))
        viewModelScope.launch {
            val r = kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { com.taocrypt.micmask.inject.MicInjector.repairAudio(ctx) } }
            _state.value = _state.value.copy(toast = r.getOrElse { L.e("音频还原失败", it); tr("还原失败：{0}", it.message) })
        }
    }
    fun soundInstalled(r: com.taocrypt.micmask.repo.ModelRepo.RSound) = _state.value.sounds.any { it.id == r.id }
    private fun presetId(id: String) = _state.value.sounds.firstOrNull { it.id == id }?.let { SoundBoard.isPreset(it) } == true
    fun sfxSelStart(id: String) {
        if (presetId(id)) { _state.value = _state.value.copy(toast = tr("预设音效不能删除或改名")); return }
        _state.value = _state.value.copy(sfxSel = (_state.value.sfxSel ?: emptySet()) + id)
    }
    fun sfxSelToggle(id: String) {
        if (presetId(id)) { _state.value = _state.value.copy(toast = tr("预设音效不能删除或改名")); return }
        val c = _state.value.sfxSel ?: emptySet(); _state.value = _state.value.copy(sfxSel = if (id in c) c - id else c + id)
    }
    fun sfxSelSet(ids: Set<String>) { _state.value = _state.value.copy(sfxSel = ids.filterNot { presetId(it) }.toSet()) }
    fun sfxRename(id: String) { _state.value = _state.value.copy(sfxRename = id, sfxSel = null) }
    fun sfxRenameDone() { _state.value = _state.value.copy(sfxRename = null) }
    fun sfxSelEnd() { _state.value = _state.value.copy(sfxSel = null) }
    /** 删除多选的素材 */
    fun deleteSelectedSounds() {
        val ids = _state.value.sfxSel ?: return
        val del = _state.value.sounds.filter { it.id in ids && !SoundBoard.isPreset(it) }
        if (del.any { it.id == _state.value.sfxPlaying }) stopSound()
        if (del.any { it.id == _state.value.sfxPreview }) stopPreview()
        del.forEach { SoundBoard.delete(it) }
        _state.value = _state.value.copy(sfxSel = null, toast = tr("已删除 {0} 个", del.size))
        L.i("多选删除素材 ${del.size} 个")
        refreshSounds()
    }
    /** 整组删除 */
    fun deleteGroup(pack: String) {
        val del = _state.value.sounds.filter { it.pack == pack && !SoundBoard.isPreset(it) }
        if (del.any { it.id == _state.value.sfxPlaying }) stopSound()
        if (del.any { it.id == _state.value.sfxPreview }) stopPreview()
        del.forEach { SoundBoard.delete(it) }
        if (pack != SoundBoard.PACK_FX) File(SoundBoard.dir(ctx), pack).takeIf { it.isDirectory }?.deleteRecursively()
        _state.value = _state.value.copy(sfxSel = null, toast = tr("已删除分组（{0} 个）", del.size))
        L.i("删除分组 $pack ${del.size} 个")
        refreshSounds()
    }
    fun deleteSound(s: SoundBoard.Sound) { if (_state.value.sfxPlaying == s.id) stopSound(); SoundBoard.delete(s); refreshSounds() }
    fun renameSound(s: SoundBoard.Sound, n: String) { if (!SoundBoard.rename(s, n)) _state.value = _state.value.copy(toast = tr("重名或无法改名")); refreshSounds() }
    fun setSfxGain(v: Float) { _state.value = _state.value.copy(sfxGain = v); prefs.edit().putFloat("sfx_gain", v).apply() }
    fun setSfxLoop(v: Boolean) { _state.value = _state.value.copy(sfxLoop = v); prefs.edit().putBoolean("sfx_loop", v).apply() }
    private fun stopSoundLocal() { sfxJob?.cancel(); sfxJob = null; if (_state.value.sfxPlaying != null) _state.value = _state.value.copy(sfxPlaying = null) }
    fun stopSound() { stopSoundLocal(); com.taocrypt.micmask.inject.MicInjector.stopSfx(); SpeakerSfx.stopSfx() }
    /** 点一下播放（再点同一个停止）。没在变声时自动以声卡模式打开麦克风通道。 */
    /** 悬浮窗长按音效：只从扬声器外放（不进麦克风）；再长按同一个停止 */
    fun playSoundSpeaker(id: String) {
        if (_state.value.sfxPlaying == id) { stopSound(); return }
        val s = _state.value.sounds.firstOrNull { it.id == id } ?: SoundBoard.find(ctx, id) ?: return
        sfxJob?.cancel()
        _state.value = _state.value.copy(sfxPlaying = id, toast = tr("外放：{0}", s.name))
        sfxJob = viewModelScope.launch {
            val n = kotlinx.coroutines.withContext(Dispatchers.IO) { SpeakerSfx.playSfx(s.file, _state.value.sfxGain, _state.value.sfxLoop) }
            if (n <= 0) { _state.value = _state.value.copy(sfxPlaying = null, toast = tr("播放失败")); return@launch }
            if (!_state.value.sfxLoop) { kotlinx.coroutines.delay(n * 1000L / SoundBoard.SR + 100); if (_state.value.sfxPlaying == id) _state.value = _state.value.copy(sfxPlaying = null) }
        }
    }

    fun playSound(id: String) {
        if (_state.value.sfxPlaying == id) { stopSound(); return }
        val s = _state.value.sounds.firstOrNull { it.id == id } ?: SoundBoard.find(ctx, id) ?: return
        sfxJob?.cancel()
        _state.value = _state.value.copy(sfxPlaying = id)
        sfxJob = viewModelScope.launch {
            val inj = com.taocrypt.micmask.inject.MicInjector
            if (!inj.liveOn && speakerMode()) {
                // 外放模式：直接从扬声器放
                val n = kotlinx.coroutines.withContext(Dispatchers.IO) { SpeakerSfx.playSfx(s.file, _state.value.sfxGain, _state.value.sfxLoop) }
                if (n <= 0) { _state.value = _state.value.copy(sfxPlaying = null, toast = tr("播放失败")); return@launch }
                if (!_state.value.sfxLoop) { kotlinx.coroutines.delay(n * 1000L / SoundBoard.SR + 100); if (_state.value.sfxPlaying == id) _state.value = _state.value.copy(sfxPlaying = null) }
                return@launch
            }
            if (!inj.liveOn) {
                if (!_state.value.liveOn) { _state.value = _state.value.copy(toast = tr("未在变声，已自动用声卡模式打开麦克风通道")); selectMode(2); toggleLive() }
                var w = 0
                while (!inj.liveOn && w < 150) { kotlinx.coroutines.delay(100); w++ }
                if (!inj.liveOn) { _state.value = _state.value.copy(sfxPlaying = null, toast = tr("麦克风通道没打开，播放失败")); return@launch }
                kotlinx.coroutines.delay(300)
            }
            val n = kotlinx.coroutines.withContext(Dispatchers.IO) { inj.playSfx(s.file, _state.value.sfxGain, _state.value.sfxLoop) }
            if (n <= 0) { _state.value = _state.value.copy(sfxPlaying = null, toast = tr("播放失败")); return@launch }
            if (!_state.value.sfxLoop) {
                kotlinx.coroutines.delay(n * 1000L / SoundBoard.SR + 100)
                if (_state.value.sfxPlaying == id) _state.value = _state.value.copy(sfxPlaying = null)
            }
        }
    }

    fun setLiveFloat(on: Boolean) {
        if (on && !canFloat()) { _state.value = _state.value.copy(toast = tr("请先授予「显示在其他应用上层」权限")); requestFloatPermission(); return }
        prefs.edit().putBoolean("lf_on", on).apply()
        LiveFloatService.toggle(ctx, on)
        _state.value = _state.value.copy(liveFloat = on)
    }
    /** 启动时恢复上次的悬浮窗开关 */
    private fun restoreLiveFloat() {
        if (prefs.getBoolean("lf_on", false) && !LiveFloatService.running && canFloat()) {
            LiveFloatService.toggle(ctx, true); _state.value = _state.value.copy(liveFloat = true)
            L.i("UI 恢复悬浮窗")
        }
    }
    fun refreshLiveFloat() { _state.value = _state.value.copy(liveFloat = LiveFloatService.running) }

    fun setLiveMonitor(on: Boolean) {
        _state.value = _state.value.copy(liveMonitor = on)
        prefs.edit().putBoolean("monitor", on).apply()
        com.taocrypt.micmask.inject.MicInjector.setMonitor(on)
        L.i("UI 耳返 → $on")
    }

    /** 录制预览：录 [sec] 秒变声结果，录完自动在本机回放（需实时变声在运行） */
    private var recJob: kotlinx.coroutines.Job? = null
    /** 按住录制：down=true 开始；松手 down=false 回放；cancel=true 丢弃 */
    fun holdPreview(down: Boolean, cancel: Boolean = false) {
        val inj = com.taocrypt.micmask.inject.MicInjector
        if (down) {
            if (!inj.liveOn) { _state.value = _state.value.copy(toast = tr("先开始变声，再按住录制预览")); return }
            inj.capturePreview(30)
            recJob?.cancel()
            recJob = viewModelScope.launch {
                var t = 0
                while (t <= 30) { _state.value = _state.value.copy(previewLeft = t.coerceAtLeast(1)); kotlinx.coroutines.delay(1000); t++ }
                _state.value = _state.value.copy(previewLeft = 0)
            }
        } else {
            if (_state.value.previewLeft == 0) return
            recJob?.cancel(); _state.value = _state.value.copy(previewLeft = 0)
            inj.capturePreview(if (cancel) -1 else 0)
            L.i("UI 预览 ${if (cancel) "取消" else "松手回放"}")
        }
    }

    fun exportLogs() {
        _state.value = _state.value.copy(logMsg = tr("正在导出…"))
        Thread {
            val m = runCatching { tr("已导出到 ") + L.exportToDownloads(ctx) }.getOrElse { tr("导出失败：{0}", it.message) }
            _state.value = _state.value.copy(logMsg = m)
        }.start()
    }

    /** 性能测试：在本机实测各档合成器，选能稳定跑的最低延迟档，结果记住 */
    fun runLiveBench() {
        if (com.taocrypt.micmask.inject.MicInjector.liveOn || _state.value.liveBenching) return
        if (!LiveRvc.modelPresent(ctx)) { _state.value = _state.value.copy(liveBench = tr("缺实时变声模型，先在模型页导入")); return }
        _state.value = _state.value.copy(liveBenching = true, liveMsg = tr("性能测试中，约 20 秒…"))
        Thread {
            val r = com.taocrypt.micmask.inject.MicInjector.liveBench(ctx, ModelStore.liveNames(ctx))
            L.i("UI 性能测试结果：$r")
            if (!r.startsWith("失败")) ctx.getSharedPreferences("live", 0).edit().putString("bench_" + _state.value.liveVoice, r).apply()
            _state.value = _state.value.copy(liveBenching = false, liveBench = r, liveMsg = if (r.startsWith("失败")) r else tr("性能测试完成"))
        }.start()
    }

    // ---------------------------------------------------------------- v2 模型仓库 / 音色管理
    private var repoManifest: com.taocrypt.micmask.repo.ModelRepo.Manifest? = null

    // ---- 本地音色排序：置顶的在前（按置顶先后），其余按拖动后的顺序，新下载的排最后 ----
    private fun packsOff(): Set<String> = prefs.getString("packsOff", "")!!.split('\n').filter { it.isNotEmpty() }.toSet()
    fun togglePack(pack: String, on: Boolean) {
        val s = _state.value.packsOff.toMutableSet(); if (on) s.remove(pack) else s.add(pack)
        prefs.edit().putString("packsOff", s.joinToString("\n")).apply()
        _state.value = _state.value.copy(packsOff = s)
        L.i("UI 音效包 $pack ${if (on) "开启" else "关闭"}")
    }
    private fun voicePins(): Set<String> = prefs.getString("voicePins", "")!!.split('\n').filter { it.isNotEmpty() }.toCollection(LinkedHashSet())
    private fun orderVoices(all: List<String>): List<String> {
        val ord = prefs.getString("voiceOrder", "")!!.split('\n').filter { it.isNotEmpty() }
        val pins = voicePins()
        val idx = { v: String -> ord.indexOf(v).let { if (it < 0) Int.MAX_VALUE else it } }
        return all.sortedWith(compareBy<String>({ if (it in pins) 0 else 1 }, { if (it in pins) pins.indexOf(it) else idx(it) }))
    }
    private fun saveVoiceOrder(l: List<String>, pins: Set<String>) {
        prefs.edit().putString("voiceOrder", l.joinToString("\n")).putString("voicePins", pins.joinToString("\n")).apply()
        _state.value = _state.value.copy(liveVoices = orderVoices(l), voicePins = pins)
    }
    fun togglePinVoice(v: String) {
        val p = LinkedHashSet(_state.value.voicePins); if (!p.remove(v)) p.add(v)
        saveVoiceOrder(_state.value.liveVoices, p)
    }
    /** 拖动排序：只在同一组内移动（置顶组 / 普通组），跨组以置顶状态为准 */
    fun moveVoice(from: Int, to: Int) {
        val l = _state.value.liveVoices.toMutableList()
        if (from !in l.indices || to !in l.indices || from == to) return
        val v = l.removeAt(from); l.add(to, v)
        val pins = _state.value.voicePins
        // 置顶组的顺序 = 拖完后它们在列表里的顺序
        saveVoiceOrder(l, l.filter { it in pins }.toCollection(LinkedHashSet()))
    }

    fun setRepoTab(t: String) { _state.value = _state.value.copy(repoTab = t) }
    fun setRepoQuery(q: String) { _state.value = _state.value.copy(repoQuery = q) }
    fun setRepoFilter(u: String) { _state.value = _state.value.copy(repoFilter = u) }
    fun setRepoUrl(v: String) = setRepos(listOf(RepoEntry(v.trim())))
    /** 保存仓库列表（去空、按地址去重）；旧键 repoUrl 同步为第一个启用的，降级到旧版本也能用 */
    fun setRepos(list: List<RepoEntry>, refresh: Boolean = false) {
        val l = list.map { it.copy(url = it.url.trim(), name = it.name.trim()) }.filter { it.url.isNotEmpty() }.distinctBy { it.url }
        val arr = org.json.JSONArray(); l.forEach { arr.put(org.json.JSONObject().put("url", it.url).put("name", it.name).put("on", it.on)) }
        val first = l.firstOrNull { it.on }?.url.orEmpty()
        prefs.edit().putString("repos", arr.toString()).putString("repoUrl", first).apply()
        _state.value = _state.value.copy(repos = l, repoUrl = first)
        if (refresh) refreshRepo()
    }
    /** 新增或修改（old 为原地址，null 表示新增） */
    fun saveRepo(old: String?, url: String, name: String) {
        val e = RepoEntry(url.trim(), name.trim(), true); val l = _state.value.repos
        setRepos(if (old == null) l + e else l.map { if (it.url == old) e.copy(on = it.on) else it }, refresh = true)
    }
    fun removeRepo(url: String) = setRepos(_state.value.repos.filter { it.url != url }, refresh = true)
    fun toggleRepo(url: String, on: Boolean) = setRepos(_state.value.repos.map { if (it.url == url) it.copy(on = on) else it }, refresh = true)
    /** 拖动排序结束时调用 */
    fun moveRepo(from: Int, to: Int) {
        val l = _state.value.repos.toMutableList()
        if (from !in l.indices || to !in l.indices || from == to) return
        l.add(to, l.removeAt(from)); setRepos(l, refresh = true)
    }

    fun refreshRepo() {
        if (_state.value.repoLoading) return
        val urls = _state.value.repos.filter { it.on }.map { it.url }
        if (urls.isEmpty()) {
            repoManifest = null
            _state.value = _state.value.copy(repoModels = emptyList(), repoSounds = emptyList(), repoTtsSize = 0L, repoMsg = if (_state.value.repos.isEmpty()) tr("还没填模型仓库地址（设置页）") else tr("模型仓库都已关闭（设置页）")); return
        }
        _state.value = _state.value.copy(repoLoading = true, repoMsg = tr("正在获取模型列表…"))
        viewModelScope.launch {
            val r = kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { com.taocrypt.micmask.repo.ModelRepo.fetchAll(urls) } }
            r.onSuccess { repoManifest = it; _state.value = _state.value.copy(repoSounds = it.sounds, repoTtsSize = it.ttsSize) }
            _state.value = _state.value.copy(repoLoading = false,
                repoModels = r.getOrNull()?.models ?: _state.value.repoModels,
                // 多仓库时部分失败也照常显示能拿到的
                repoMsg = r.fold({ m -> when { m.errors.isNotEmpty() && m.models.isEmpty() -> tr("获取失败：") + m.errors.joinToString("；")
                    m.errors.isNotEmpty() -> tr("部分仓库获取失败：") + m.errors.joinToString("；"); m.models.isEmpty() -> tr("仓库里还没有模型"); else -> "" } },
                    { L.e("模型仓库获取失败", it); tr("获取失败：{0}", it.message) }))
        }
    }

    fun downloadRepoModel(m: com.taocrypt.micmask.repo.ModelRepo.RModel) {
        val man = repoManifest ?: return
        if (_state.value.dlId != null || com.taocrypt.micmask.inject.MicInjector.liveOn) return
        _state.value = _state.value.copy(dlId = m.id, dlProgress = 0f, dlName = "")
        viewModelScope.launch {
            val r = kotlinx.coroutines.withContext(Dispatchers.IO) {
                runCatching { com.taocrypt.micmask.repo.ModelRepo.download(ctx, man, m) { p, n -> _state.value = _state.value.copy(dlProgress = p, dlName = n) } }
            }
            _state.value = _state.value.copy(dlId = null, dlName = "",
                toast = r.fold({ tr("已下载：{0}", m.name) }, { L.e("模型下载失败", it); tr("下载失败：{0}", it.message) }))
            if (r.isSuccess && m.name.isNotBlank()) ModelStore.voices(ctx).firstOrNull { it == m.id }?.let { ModelStore.setVoiceAlias(ctx, it, m.name) }
            refreshModels()
        }
    }

    /** 从模型仓库下载文件变声（句级）模型，只下缺的 */
    fun downloadFileModel() {
        if (_state.value.dlId != null) return
        val man = repoManifest
        if (man == null) { refreshRepo(); _state.value = _state.value.copy(toast = tr("正在获取仓库清单，稍后再点一次")); return }
        _state.value = _state.value.copy(dlId = "file", dlProgress = 0f, dlName = "")
        viewModelScope.launch {
            val r = kotlinx.coroutines.withContext(Dispatchers.IO) {
                runCatching { com.taocrypt.micmask.repo.ModelRepo.downloadFileModel(ctx, man) { p, n -> _state.value = _state.value.copy(dlProgress = p, dlName = n) } }
            }
            _state.value = _state.value.copy(dlId = null, dlName = "",
                toast = r.fold({ tr("句级模型已就绪") }, { L.e("句级模型下载失败", it); tr("下载失败：{0}", it.message) }))
            refreshModels()
        }
    }

    /** 从模型仓库下载 TTS 全套（模型 + 参考特征），只下缺的 */
    fun downloadTts() {
        if (_state.value.dlId != null) return
        val man = repoManifest
        if (man == null) { refreshRepo(); _state.value = _state.value.copy(toast = tr("正在获取仓库清单，稍后再点一次")); return }
        _state.value = _state.value.copy(dlId = "tts", dlProgress = 0f, dlName = "")
        viewModelScope.launch {
            val r = kotlinx.coroutines.withContext(Dispatchers.IO) {
                runCatching { com.taocrypt.micmask.repo.ModelRepo.downloadTts(man, TtsAssetStore.dir(ctx)) { p, n -> _state.value = _state.value.copy(dlProgress = p, dlName = n) } }
            }
            _state.value = _state.value.copy(dlId = null, dlName = "",
                toast = r.fold({ tr("TTS 模型已就绪") }, { L.e("TTS 下载失败", it); tr("TTS 下载失败：{0}", it.message) }))
            refreshTtsModels()
        }
    }

    /** 从手机导入 TTS 文件：zip（内含 tts/ 或平铺）或直接多选文件；refbin 放进 ref/ */
    fun importTtsFiles(uris: List<Uri>) {
        if (uris.isEmpty() || _state.value.importing) return
        val names = (TtsAssetStore.MODELS.keys + TtsAssetStore.SHARED_DATA + TtsAssetStore.ASSETS).toSet()
        fun target(n: String): String? = when {
            n in names -> n
            Regex("^ref_[\\w]+\\.(refbin|txt)$").matches(n) -> "ref/$n"
            else -> null
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(importing = true, importDone = 0, importTotal = names.size + 5, importName = tr("正在读取…"))
            val res = kotlinx.coroutines.withContext(Dispatchers.IO) {
                runCatching {
                    val dir = TtsAssetStore.dir(ctx)
                    var n = 0
                    fun save(rel: String, input: java.io.InputStream) {
                        _state.value = _state.value.copy(importName = rel)
                        val dst = File(dir, rel).also { it.parentFile?.mkdirs() }
                        val tmp = File(dst.path + ".part")
                        tmp.outputStream().use { input.copyTo(it, 1 shl 20) }
                        dst.delete(); if (!tmp.renameTo(dst)) throw java.io.IOException(tr("改名失败：{0}", rel))
                        n++; _state.value = _state.value.copy(importDone = n)
                    }
                    for (u in uris) {
                        val base = (queryName(u) ?: u.lastPathSegment.orEmpty()).substringAfterLast('/')
                        if (base.lowercase().endsWith(".zip")) {
                            ctx.contentResolver.openInputStream(u)!!.use { raw ->
                                java.util.zip.ZipInputStream(raw.buffered(1 shl 20)).use { z ->
                                    while (true) { val e = z.nextEntry ?: break
                                        if (!e.isDirectory) target(e.name.substringAfterLast('/'))?.let { save(it, z) } }
                                }
                            }
                        } else target(base)?.let { rel -> ctx.contentResolver.openInputStream(u)!!.use { save(rel, it) } }
                            ?: L.w("TTS 导入跳过：$base")
                    }
                    n
                }
            }
            _state.value = _state.value.copy(importing = false, importName = "",
                toast = res.fold({ if (it > 0) tr("已导入 {0} 个 TTS 文件", it) else tr("没找到 TTS 文件") }, { L.e("TTS 导入失败", it); tr("TTS 导入失败：{0}", it.message) }))
            refreshTtsModels()
        }
    }

    fun setTtsAdvanced(v: Boolean) { _state.value = _state.value.copy(ttsAdvanced = v) }

    /** TTS 结果直接送麦：解码进本地语音包「合成」分组再播放（走实时通道） */
    fun sendFileToMic(f: File, name: String) {
        viewModelScope.launch {
            val r = kotlinx.coroutines.withContext(Dispatchers.IO) {
                runCatching {
                    val n = SoundBoard.safe("合成·" + name.take(16))
                    SoundBoard.find(ctx, SoundBoard.PACK_LOCAL + "/" + n)?.takeIf { it.file.lastModified() >= f.lastModified() }
                        ?: SoundBoard.find(ctx, SoundBoard.PACK_LOCAL + "/" + n)?.also { SoundBoard.delete(it) }.let { SoundBoard.importFile(ctx, f, SoundBoard.PACK_LOCAL, n) }
                }
            }
            r.onSuccess { s -> _state.value = _state.value.copy(sounds = SoundBoard.list(ctx)); playSound(s.id) }
                .onFailure { L.e("送麦失败", it); _state.value = _state.value.copy(toast = tr("送麦失败：{0}", it.message)) }
        }
    }

    fun repoInstalled(m: com.taocrypt.micmask.repo.ModelRepo.RModel): Boolean = com.taocrypt.micmask.repo.ModelRepo.installed(ctx, m)

    fun voiceLabel(v: String): String = ModelStore.voiceLabel(ctx, v)
    fun voiceSizeMb(v: String): Float = ModelStore.voiceSize(ctx, v) / 1048576f

    fun renameVoice(v: String, alias: String) {
        ModelStore.setVoiceAlias(ctx, v, alias)
        refreshVoices()
    }

    fun deleteVoice(v: String) {
        if (com.taocrypt.micmask.inject.MicInjector.liveOn) { _state.value = _state.value.copy(toast = tr("先停止实时变声再删除")); return }
        val n = ModelStore.deleteVoice(ctx, v)
        L.i("UI 删除音色 $v（$n 个文件）")
        refreshModels()
        _state.value = _state.value.copy(toast = tr("已删除 {0}", ModelStore.voiceLabel(v)))
    }

    /** 白名单：已选包名集合（存成逗号分隔，沿用注入目标字段） */
    fun whitelist(): Set<String> = _state.value.qqInjectTarget.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    fun setWhitelist(pkgs: Set<String>) = setQqInjectTarget(pkgs.sorted().joinToString(","))

    fun refreshVoices() {
        val v = ModelStore.currentVoice(ctx) ?: ""
        _state.value = _state.value.copy(liveVoices = orderVoices(ModelStore.voices(ctx)), voicePins = voicePins(), packsOff = packsOff(), liveVoice = v,
            liveBench = ctx.getSharedPreferences("live", 0).getString("bench_$v", "") ?: "")
    }

    fun setLiveVoice(v: String) {
        if (com.taocrypt.micmask.inject.MicInjector.liveOn || _state.value.liveBenching) return
        ctx.getSharedPreferences("live", 0).edit().putString("voice", v).apply()
        L.i("UI 切换音色 → $v")
        refreshVoices()
    }

    fun setLiveAec(on: Boolean) {
        if (com.taocrypt.micmask.inject.MicInjector.liveOn) return
        ctx.getSharedPreferences("live", 0).edit().putBoolean("aec", on).apply()
        _state.value = _state.value.copy(liveAec = on)
    }

    /** 悬浮窗「降噪」：未变声直接切；变声中切换录音源后重启一次（录音源只能在启动时设定） */
    fun toggleLiveAecAnywhere() {
        val on = !_state.value.liveAec
        ctx.getSharedPreferences("live", 0).edit().putBoolean("aec", on).apply()
        _state.value = _state.value.copy(liveAec = on)
        L.i("UI 降噪 → $on")
        if (com.taocrypt.micmask.inject.MicInjector.liveOn || _state.value.liveOn) {
            toggleLive()
            viewModelScope.launch { kotlinx.coroutines.delay(900); if (!_state.value.liveOn) toggleLive() }
        }
    }

    fun setLiveAi(on: Boolean) {
        if (com.taocrypt.micmask.inject.MicInjector.liveOn) return
        selectMode(if (on) 0 else 1)
    }

    /** AI 模式的音高偏移（男声转女声音色一般 +10~+12） */
    fun setAiSemis(v: Float) {
        prefs.edit().putFloat("ai_semis", v).apply()
        if (_state.value.liveMode == 0) setLiveSemis(v) else _state.value = _state.value.copy()
        _state.value = _state.value.copy(aiSemis = v)
    }

    fun setLiveSemis(v: Float) {
        _state.value = _state.value.copy(liveSemis = v)
        // semis 经 TX_LIVE_SET 同步到 shell 引擎
        com.taocrypt.micmask.inject.MicInjector.setLiveSemis(v)
    }

    fun injectSelfTest() {
        if (_state.value.qqSelfTesting) return
        _state.value = _state.value.copy(qqSelfTesting = true, qqSelfTest = tr("自检中，约 10 秒，别动手机…"))
        com.taocrypt.micmask.inject.MicInjector.selfTest(ctx) { r ->
            _state.value = _state.value.copy(qqSelfTesting = false, qqSelfTest = r)
            refreshInjectState()
        }
    }

    /** 设置页权限模式：true=普通模式（扬声器外放/录播），false=Shizuku 实时注入 */
    fun setNormalMode(normal: Boolean) {
        if (com.taocrypt.micmask.inject.MicInjector.liveOn || _state.value.liveOn) { toast(tr("请先关闭变声，再切换模式")); return }
        prefs.edit().putBoolean("force_normal", normal).apply()
        com.taocrypt.micmask.inject.MicInjector.forceNormal = normal
        _state.value = _state.value.copy(forceNormal = normal)
        L.i("UI 权限模式 → ${if (normal) "普通" else "Shizuku"}")
        if (!normal && !com.taocrypt.micmask.inject.MicInjector.shizukuPermitted()) requestShizuku() else refreshInjectState()
    }

    fun requestShizuku() {
        if (!com.taocrypt.micmask.inject.MicInjector.shizukuRunning()) { toast(tr("Shizuku 没有运行，请先打开 Shizuku 并启动服务")); refreshInjectState(); return }
        com.taocrypt.micmask.inject.MicInjector.requestShizuku { ok ->
            L.i("Shizuku 授权结果：$ok")
            if (!ok) toast(tr("Shizuku 授权被拒绝"))
            android.os.Handler(android.os.Looper.getMainLooper()).post { refreshInjectState() }
        }
        refreshInjectState()
    }

    fun setQqInjectMode(v: Boolean) {
        prefs.edit().putBoolean("qqInjectMode", v).apply()
        VoiceSender.applyRoute(
            if (v) VoiceSender.Route.INJECT
            else if (_state.value.qqHeadsetMode) VoiceSender.Route.HEADSET
            else VoiceSender.Route.SPEAKER
        )
        _state.value = _state.value.copy(qqInjectMode = v)
        if (v && !com.taocrypt.micmask.inject.MicInjector.shizukuGranted()) requestShizuku()
        refreshInjectState()
    }

    fun setQqInjectTarget(pkg: String) {
        val p = pkg.trim()
        prefs.edit().putString("qqInjectTarget", p).apply()
        VoiceSender.injectTarget = p
        _state.value = _state.value.copy(qqInjectTarget = p)
        refreshInjectState()
    }

    /** 切换「耳机当虚拟麦」/「手机外放」 */
    fun setQqHeadsetMode(v: Boolean) {
        if (!_state.value.qqInjectMode)
            VoiceSender.applyRoute(if (v) VoiceSender.Route.HEADSET else VoiceSender.Route.SPEAKER)
        prefs.edit().putBoolean("qqHeadsetMode", v).apply()
        _state.value = _state.value.copy(qqHeadsetMode = v)
        refreshHeadsetState()
        L.i("QQ 代发路由 = ${if (v) "耳机" else "外放"}，耳机=${VoiceSender.hasHeadset(ctx)}")
    }

    /**
     * **点一次播一次**（主入口，也是悬浮球的行为）。
     *
     * 不依赖无障碍服务。用户在 QQ 前台按住说话前点一下即可。
     */
    fun playPendingNow() {
        VoiceSender.playNow(ctx)
        _state.value = _state.value.copy(
            qqPlaying = VoiceSender.isPlaying,
            qqHeadsetOn = VoiceSender.hasHeadset(ctx),
            qqBlockedByHeadset = VoiceSender.lastBlockedByHeadset,
        )
        // 输出设备名由 VoiceSender 异步解析，800ms 后（通常已解析完）再同步一次
        getApplication<Application>().let { app ->
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                _state.value = _state.value.copy(qqLastOutput = VoiceSender.lastOutputName)
            }, 800)
        }
    }

    /**
     * 无障碍**自动**触发时用的循环外放（`QQ_LOOP_SEC` 秒后自动停）。
     *
     * ⚠️ 手动操作不要走这里，用 [playPendingNow]（点一次播一次）。
     * 保留它是因为自动触发时用户还没切到 QQ，需要多响一会儿。
     */
    fun startQqLoop() {
        VoiceSender.playLoop(ctx, QQ_LOOP_SEC)
        _state.value = _state.value.copy(
            qqPlaying = VoiceSender.isPlaying,
            qqLastOutput = VoiceSender.lastOutputName,
            qqHeadsetOn = VoiceSender.hasHeadset(ctx),
            qqHeadsetName = VoiceSender.headsetName(ctx),
        )
    }

    fun stopQqLoop() {
        VoiceSender.stop()
        _state.value = _state.value.copy(qqPlaying = false)
    }

    /** 悬浮窗权限是否已授予 */
    fun canFloat(): Boolean = FloatingPlayService.canDraw(ctx)

    /** 打开系统「显示在其他应用上层」授权页 */
    fun requestFloatPermission() {
        runCatching {
            ctx.startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + ctx.packageName),
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { L.w("打开悬浮窗授权页失败：${it.message}") }
    }

    /**
     * 开关悬浮小窗。
     *
     * 没权限时不硬启动（系统会静默拒绝，用户看不到任何反馈），
     * 而是直接把授权页推出来。
     */
    fun setFloating(on: Boolean) {
        if (on && !canFloat()) {
            _state.value = _state.value.copy(toast = tr("请先授予「显示在其他应用上层」权限"))
            requestFloatPermission()
            return
        }
        FloatingPlayService.toggle(ctx, on)
        _state.value = _state.value.copy(qqFloating = on)
        L.i("悬浮小窗 = $on")
    }

    /** 从授权页返回时刷新（onResume 调用） */
    fun refreshFloatingState() {
        _state.value = _state.value.copy(
            qqFloating = FloatingPlayService.running,
            qqPendingName = VoiceSender.pendingFile?.name,
        )
    }
    // ------------------------------------------------ 常用语音（LFU 收藏）

    /** 重新读一次收藏列表（最多 10 条，按用得多的排前面） */
    fun refreshLibrary() {
        _state.value = _state.value.copy(library = TtsLibrary.list(ctx))
    }

    /**
     * 标记「用过一次」——试听、设为待发送时调用。
     *
     * 这是 LFU 的计数来源：淘汰时计数最小的先走，所以常用的不会被清掉。
     */
    fun touchLibrary(e: TtsLibrary.Entry) {
        TtsLibrary.touch(ctx, e)
        refreshLibrary()
    }

    /** 把收藏里的一条设为「待发送」，悬浮球/设置页播的就是它 */
    fun useLibraryAsPending(e: TtsLibrary.Entry) {
        touchLibrary(e)
        armQqPending(e.file)
        _state.value = _state.value.copy(toast = tr("已设为待发送：{0}", e.preview))
    }

    fun deleteLibrary(e: TtsLibrary.Entry) {
        TtsLibrary.remove(ctx, e)
        // 删掉的正好是待发送那条时，一起解绑，免得播一个已经不存在的文件
        if (VoiceSender.pendingFile?.absolutePath == e.file.absolutePath) {
            VoiceSender.clear()
            _state.value = _state.value.copy(qqPendingName = null)
        }
        refreshLibrary()
    }

    fun clearToast() { _state.value = _state.value.copy(toast = null) }
    fun clearError() { _state.value = _state.value.copy(error = null) }

    // ---------------------------------------------------------------- 录音

    /** RECORD_AUDIO 是否已授权（UI 层据此决定是先要权限还是直接开录） */
    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun startRecording() {
        val s = _state.value
        if (s.recording) return
        if (!hasMicPermission()) {
            _state.value = s.copy(error = tr("需要麦克风权限才能录音"))
            return
        }
        val err = recorder.start()
        if (err != null) _state.value = _state.value.copy(error = err)
    }

    /**
     * 停止录音。录到的内容会**同时**成为变声输入和合成参考音频的默认值 ——
     * 用户录一遍自己的声音，两个页都能直接用上。
     */
    fun stopRecording() {
        val s = _state.value
        if (!s.recording) return
        val r = recorder.stop()
        if (r == null) {
            _state.value = s.copy(toast = tr("录音太短（不足 0.3 秒），已丢弃"))
            return
        }
        pending16k = r.samples16k
        pendingSrc = null
        val desc = buildString {
            append(tr("录音 %.1f 秒 · %d Hz · 已重采样到 16 kHz").format(r.seconds, r.originRate))
            if (r.clipped) append(tr(" · 峰值偏大，可能削波"))
            if (r.tooQuiet) append(tr(" · 几乎没有声音"))
        }
        L.i("录音 → 变声输入：${r.file.absolutePath}")
        _state.value = s.copy(
            recording = false,
            recFile = r.file,
            recSeconds = r.seconds,
            recCount = s.recCount + 1,
            srcName = r.file.name,
            srcInfo = desc,
            resultFile = null,
            error = null,
            toast = when {
                r.tooQuiet -> tr("录到的几乎都是底噪，请检查麦克风后重录")
                r.clipped -> tr("录音完成，但音量偏大，建议离麦克风远一点")
                else -> tr("录音完成，已作为变声输入")
            },
            ttsRefFile = s.ttsRefFile ?: r.file,
            ttsRefName = s.ttsRefName ?: r.file.name,
        )
    }

    fun cancelRecording() {
        recorder.cancel()
        _state.value = _state.value.copy(recording = false, recElapsedMs = 0L, recLevel = 0f)
    }

    /** 把之前录的某一条回放素材设为变声输入（不进录音器，直接读 wav） */
    fun useRecordingAsInput(f: File) {
        _state.value = _state.value.copy(busy = true, stage = tr("读取录音"), progress = 0.05f, error = null)
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val raw = Wav.read(f)
                val mono16k = if (raw.sampleRate == AudioIo.TARGET_SR) raw.samples
                else micmask.core.Resampler.convert(raw.samples, raw.sampleRate, AudioIo.TARGET_SR)
                AudioIo.preprocess16k(mono16k)
                pending16k = mono16k
                pendingSrc = null
                _state.value = _state.value.copy(
                    busy = false,
                    srcName = f.name,
                    srcInfo = tr("录音文件 · %.1f 秒 · %d Hz").format(
                        raw.samples.size.toFloat() / raw.sampleRate, raw.sampleRate
                    ),
                    resultFile = null,
                )
            } catch (t: Throwable) {
                L.e("读取录音失败", t)
                _state.value = _state.value.copy(busy = false, error = tr("读取录音失败：{0}", t.message))
            }
        }
    }

    fun listRecordings(): List<File> = AudioIo.listWavs(ctx, "recordings")

    /** 通过文件选择器把音频导入历史录音（解码成 16k wav 存到 recordings/） */
    fun importRecordings(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _state.value = _state.value.copy(busy = true, stage = tr("导入音频"), progress = 0.02f, error = null)
        viewModelScope.launch(Dispatchers.Default) {
            var ok = 0; val fail = mutableListOf<String>()
            uris.forEachIndexed { i, u ->
                runCatching {
                    val d = AudioIo.decodeTo16k(ctx, u)
                    val base = d.sourceName.substringBeforeLast('.').replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(40).ifBlank { "audio" }
                    var name = "imp_$base"; var k = 1
                    while (File(AudioIo.recordingsDir(ctx), "$name.wav").exists()) name = "imp_${base}_${++k}"
                    AudioIo.saveLocalWav(ctx, d.samples, 16000, "recordings", name, normalize = false)
                    ok++
                }.onFailure { L.e("导入录音失败 $u", it); fail += AudioIo.displayName(ctx, u) }
                _state.value = _state.value.copy(progress = (i + 1f) / uris.size)
            }
            L.i("导入历史录音 $ok/${uris.size}")
            _state.value = _state.value.copy(busy = false, recCount = listRecordings().size,
                toast = tr("已导入 {0} 条到历史录音", ok) + if (fail.isNotEmpty()) tr("，{0} 条失败：{1}", fail.size, fail.joinToString("、")) else "")
        }
    }

    /** 删除历史录音（多选）；删到当前录音时回落到最新一条 */
    fun deleteRecordings(files: Collection<File>): Int {
        val n = files.count { it.delete() }
        val all = AudioIo.listWavs(ctx, "recordings")
        val s = _state.value
        _state.value = s.copy(recCount = all.size,
            recFile = s.recFile?.takeIf { it.exists() },
            ttsRefFile = s.ttsRefFile?.takeIf { it.exists() },
            ttsRefName = if (s.ttsRefFile?.exists() == false) null else s.ttsRefName,
            toast = tr("已删除 {0} 条录音", n))
        if (_state.value.recFile == null) restoreLastRecording()
        L.i("删除录音 $n 条，剩 ${all.size}")
        return n
    }

    private fun restoreLastRecording() {
        val all = AudioIo.listWavs(ctx, "recordings")
        val latest = all.firstOrNull() ?: return
        _state.value = _state.value.copy(
            recFile = latest,
            recCount = all.size,
            recSeconds = try {
                val d = Wav.read(latest)
                d.samples.size.toFloat() / d.sampleRate
            } catch (_: Throwable) {
                0f
            },
            ttsRefFile = _state.value.ttsRefFile ?: latest,
            ttsRefName = _state.value.ttsRefName ?: latest.name,
        )
    }

    // ---------------------------------------------------------------- 合成（GPT-SoVITS）

    fun refreshTtsModels() {
        // 首次调用时把打包进 APK 的文本前端（symbols/syl/charmap/poly/phrases/p2s/
        // bert_vocab）与 4 个参考特征 .refbin 解包到工作目录。缺表 → G2P 全 UNK；
        // 缺 refbin → 情绪列表为空、合成不可用。
        val unpacked = TtsAssetStore.unpackTextAssets(ctx)
        if (unpacked > 0) L.i("首次解包 TTS 文本资源 $unpacked 个文件")

        val st = TtsAssetStore.status(ctx)
        val refs = TtsAssetStore.refsReady(ctx)
        _state.value = _state.value.copy(
            ttsDir = TtsAssetStore.dir(ctx).absolutePath,
            ttsModelItems = st.items,
            ttsReady = st.ready && refs.isNotEmpty(),
            ttsMissing = st.missing,
            ttsRefsReady = refs,
            ttsEmotion = _state.value.ttsEmotion.let { e ->
                if (e in refs) e else (refs.firstOrNull() ?: "calm")
            },
        )
        val assetsOk = TtsAssetStore.assetsReady(ctx)
        L.i("refreshTtsModels ready=${st.ready} assets=$assetsOk refs=$refs " +
            "missing=${st.missing} 总模型 ${"%.1f".format(st.totalMb)} MB")
    }

    fun setTtsEmotion(emo: String) {
        if (emo !in RefFeatureStore.EMOTIONS) return
        L.i("TTS 情绪切换 -> $emo (${RefFeatureStore.LABELS[emo]})")
        _state.value = _state.value.copy(ttsEmotion = emo)
    }

    fun setTtsText(v: String) { _state.value = _state.value.copy(ttsText = v) }
    fun setTtsRefText(v: String) { _state.value = _state.value.copy(ttsRefText = v) }

    fun setTtsLang(v: String) {
        prefs.edit().putString("ttsLang", v).apply()
        _state.value = _state.value.copy(ttsLang = v)
    }

    fun setTtsSpeed(v: Float) {
        prefs.edit().putFloat("ttsSpeed", v).apply()
        _state.value = _state.value.copy(ttsSpeed = v)
    }

    fun setTtsTemperature(v: Float) {
        prefs.edit().putFloat("ttsTemperature", v).apply()
        _state.value = _state.value.copy(ttsTemperature = v)
    }

    fun setTtsTopK(v: Int) {
        prefs.edit().putInt("ttsTopK", v).apply()
        _state.value = _state.value.copy(ttsTopK = v)
    }

    fun setTtsTopP(v: Float) {
        prefs.edit().putFloat("ttsTopP", v).apply()
        _state.value = _state.value.copy(ttsTopP = v)
    }

    fun setTtsRepetition(v: Float) {
        prefs.edit().putFloat("ttsRepetition", v).apply()
        _state.value = _state.value.copy(ttsRepetition = v)
    }

    /** 文字转语音：语速和高级采样参数恢复默认（语气不动） */
    fun resetTtsParams() {
        prefs.edit().remove("ttsSpeed").remove("ttsTemperature").remove("ttsTopK").remove("ttsTopP").remove("ttsRepetition").apply()
        _state.value = _state.value.copy(ttsSpeed = 1.0f, ttsTemperature = 1.0f, ttsTopK = 15, ttsTopP = 1.0f, ttsRepetition = 1.35f)
    }

    /** 用录音目录里最新的一条当参考音频 */
    fun useLatestRecordingAsRef() {
        val f = listRecordings().firstOrNull()
        if (f == null) {
            _state.value = _state.value.copy(error = tr("还没有录过音，先去变声页录一段"))
            return
        }
        _state.value = _state.value.copy(ttsRefFile = f, ttsRefName = f.name, toast = tr("参考音频已设为 {0}", f.name))
    }

    /** 选一个文件当参考音频（拷进 tts_ref 目录，采样率统一到 16k） */
    fun importTtsRef(uri: Uri) {
        _state.value = _state.value.copy(ttsBusy = true, ttsStage = tr("准备参考音频"), ttsProgress = 0.1f, error = null)
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val d = AudioIo.decodeTo16k(ctx, uri)
                val name = "ref_" + SimpleDateFormat("MMdd_HHmmss", Locale.US).format(Date())
                val f = AudioIo.saveLocalWav(ctx, d.samples, AudioIo.TARGET_SR, "tts_ref", name, normalize = false)
                _state.value = _state.value.copy(
                    ttsBusy = false, ttsStage = "", ttsProgress = 0f,
                    ttsRefFile = f, ttsRefName = f.name,
                    toast = tr("参考音频已导入（%.1f 秒）").format(d.samples.size / 16000f),
                )
            } catch (t: Throwable) {
                L.e("导入参考音频失败", t)
                _state.value = _state.value.copy(ttsBusy = false, ttsStage = "", error = tr("导入参考音频失败：{0}", t.message))
            }
        }
    }

    /**
     * 合成语音：前置校验 → 后台跑 TtsEngine → 结果落盘 → 装载为「待发送」。
     *
     * 校验顺序刻意排在起协程之前，因为这三类是用户最容易撞上的：
     * ① 没输文本 ② 模型没就位 ③ 参考特征没放。
     * 直接弹提示比起协程后再抛异常好排查得多。
     *
     * ⚠️ AR 循环在真机上要跑几十秒到两分钟，**必须有进度回调**（见 runSynthesis 的
     * onStep）。早期版本没有回调，进度条一直卡在 30%，用户报的就是「卡死/闪退」。
     */
    fun synthesizeTts() {
        val s = _state.value
        if (s.ttsBusy) return

        if (s.ttsText.isBlank()) {
            _state.value = s.copy(error = tr("请先输入要合成的文本"))
            return
        }
        if (s.ttsMissing.isNotEmpty()) {
            _state.value = s.copy(
                error = tr("合成模型未就位，缺少：") + s.ttsMissing.joinToString("、") +
                    tr("\n放到 {0}", s.ttsDir)
            )
            return
        }
        if (s.ttsRefsReady.isEmpty()) {
            _state.value = s.copy(
                error = tr("还没有参考特征。把 ref_*.refbin 放到 {0}/ref/ 后重试", s.ttsDir)
            )
            return
        }

        val emo = s.ttsEmotion
        val text = s.ttsText.trim()
        _state.value = s.copy(
            ttsBusy = true, ttsLog = "", error = null, ttsResultFile = null,
            ttsStage = tr("加载引擎…"), ttsProgress = 0f,
            ttsArSteps = 0, ttsMsPerStep = 0.0, ttsRms = 0f,
        )

        viewModelScope.launch {
            try {
                val out = withContext(Dispatchers.Default) { runSynthesis(text, emo) }
                val (file, info) = out
                // 合成成功即装载为「待发送」，QQ 录音时会自动外放它
                armQqPending(file)
                // 同时收进「常用语音」收藏：合成一次要几十秒，不留下就得重跑
                TtsLibrary.add(ctx, file, text, emo)
                refreshLibrary()
                _state.value = _state.value.copy(
                    ttsBusy = false, ttsResultFile = file, ttsResultSeconds = info.seconds,
                    ttsElapsedMs = info.totalMs, ttsStage = tr("完成"),
                    ttsProgress = 1f, ttsLog = info.log,
                    ttsArSteps = info.steps, ttsMsPerStep = info.msPerStep,
                    ttsRms = info.rms, toast = tr("合成完成（%s）").format(
                        RefFeatureStore.LABELS[emo]?.let { tr(it) } ?: emo),
                )
            } catch (t: Throwable) {
                L.e("合成失败", t)
                // 带上异常类名：G2P 空、ONNX 形状不匹配、OOM 从 message 上很难区分
                val kind = t.javaClass.simpleName
                _state.value = _state.value.copy(
                    ttsBusy = false, ttsStage = tr("失败"),
                    error = tr("合成失败（{0}）：{1}", kind, t.message ?: tr("无详细信息")),
                )
            }
        }
    }

    private class SynthInfo(
        val seconds: Float, val totalMs: Long, val steps: Int,
        val msPerStep: Double, val rms: Float, val log: String,
    )

    /** 真正的合成流程（后台线程）：G2P -> BERT -> encoder -> AR -> vits */
    private fun runSynthesis(text: String, emo: String): Pair<File, SynthInfo> {
        val t0 = System.currentTimeMillis()
        val sb = StringBuilder()

        // 引擎按需加载（615 MB 模型，不想拖慢启动）
        var e = ttsEngine
        if (e == null || !e.loaded) {
            runCatching { e?.close() }
            val ne = TtsEngine(TtsAssetStore.dir(ctx), TtsAssetStore.dir(ctx))
            val info = ne.load(threads = 4)
            sb.append(tr("引擎加载：")).append(info).append('\n')
            L.i("TtsEngine $info")
            if (!ne.loaded) {
                ne.close()
                error(tr("TTS 引擎加载失败：{0}", info))
            }
            ttsEngine = ne
            e = ne
        }
        _state.value = _state.value.copy(ttsStage = tr("读取参考特征…"), ttsProgress = 0.1f)

        val ref = RefFeatureStore.load(ctx, emo)
            ?: error(tr("读取参考特征失败：ref_{0}.refbin", emo))
        val refText = RefFeatureStore.REF_TEXTS[emo] ?: ""

        _state.value = _state.value.copy(
            ttsStage = tr("推理中（AR 循环）…"), ttsProgress = 0.3f)

        // ⚠️ onStep 必须传：AR 循环在真机上要跑几十秒到两分钟，
        // 不回调的话进度条会一直停在一个值上，用户看到的就是「点下去没反应」，
        // 很容易被当成「卡死 / 合成失败 / 闪退」。
        val tAr = System.currentTimeMillis()
        val (audio, tm) = e!!.synthesize(
            text = text,
            refText = refText,
            ref = ref.feat,
            temperature = _state.value.ttsTemperature,
            topK = _state.value.ttsTopK,
            topP = _state.value.ttsTopP,
            repetition = _state.value.ttsRepetition,
            minSteps = 45,
            onStep = { step ->
                // AR 长度未知，用「已跑步数」做渐进式进度（每步最多推进到 90%）
                val p = (0.3f + 0.6f * (1f - kotlin.math.exp(-step / 60f))).coerceAtMost(0.9f)
                // 每 10 步打一条日志：UI 看不到进度条时，靠 logcat 也能确认「活着在跑」
                if (step % 10 == 0) {
                    L.i("tts> AR 第 %d 步，已用 %.1f 秒".format(step, (System.currentTimeMillis() - tAr) / 1000f))
                }
                _state.value = _state.value.copy(
                    ttsStage = tr("推理中（AR 第 %d 步，%.0f 秒）…").format(
                        step, (System.currentTimeMillis() - tAr) / 1000f),
                    ttsProgress = p,
                    ttsArSteps = step,
                )
            },
        )

        sb.append("G2P %d ms\n".format(tm.g2pMs))
        sb.append("BERT %d ms\n".format(tm.bertMs))
        sb.append("encoder %d ms\n".format(tm.encoderMs))
        sb.append("prefill %d ms\n".format(tm.prefillMs))
        sb.append(tr("AR %d 步 %.1f ms/帧 共 %d ms\n").format(
            tm.arSteps, tm.msPerStep(), tm.arMs))
        sb.append("vits %d ms\n".format(tm.vitsMs))
        sb.append(tr("合计 %d ms\n").format(tm.totalMs))

        // 归一化防削波，再落盘
        var peak = 0f
        for (v in audio) if (abs(v) > peak) peak = abs(v)
        val gain = if (peak > 0.999f) 0.999f / peak else 1f
        var sq = 0.0
        for (i in audio.indices) {
            audio[i] *= gain
            sq += audio[i].toDouble() * audio[i]
        }
        val rms = Math.sqrt(sq / audio.size).toFloat()
        sb.append(tr("峰值 %.4f 增益 %.3f rms %.5f\n").format(peak, gain, rms))

        if (peak > 0.999f) {
            L.w("合成削波（峰值 %.4f），已按 %.3f 归一化".format(peak, gain))
        }

        val stamp = SimpleDateFormat("MMdd_HHmmss", Locale.US).format(Date())
        // saveLocalWav 内部还会再做一次归一化（preprocess16k），
        // 这里传 normalize=false 自己控制增益，否则二次缩放会掩盖真实 rms。
        val f = AudioIo.saveLocalWav(
            ctx, audio, TtsEngine.SAMPLE_RATE, "tts", "tts_${emo}_$stamp",
            normalize = false,
        )
        sb.append(tr("输出 ")).append(f.absolutePath).append('\n')

        val totalMs = System.currentTimeMillis() - t0
        sb.append(tr("墙钟 %d ms").format(totalMs))

        return f to SynthInfo(
            seconds = audio.size.toFloat() / TtsEngine.SAMPLE_RATE,
            totalMs = totalMs, steps = tm.arSteps, msPerStep = tm.msPerStep(),
            rms = rms, log = sb.toString(),
        )
    }

    /** 取消加载：关闭文件变声引擎释放内存（转换中不允许） */
    fun unloadEngine() {
        if (_state.value.busy || _state.value.loadingEngine) { _state.value = _state.value.copy(toast = tr("正在转换，完成后再释放")); return }
        val e = engine ?: run { _state.value = _state.value.copy(engineLoaded = false); return }
        engine = null
        _state.value = _state.value.copy(engineLoaded = false, engineLog = "", toast = tr("已释放模型内存"))
        viewModelScope.launch(Dispatchers.Default) { runCatching { e.close() }.onFailure { L.e("释放模型失败", it) }; System.gc() }
        L.i("文件变声模型已卸载")
    }

    fun loadEngine() {
        if (_state.value.loadingEngine) return
        _state.value = _state.value.copy(loadingEngine = true, engineLog = "", error = null)
        viewModelScope.launch(Dispatchers.Default) {
            try {
                ModelStore.ensureExtractedFromAssets(ctx)
                val e = engine ?: VcEngine(ModelStore.candidateDirs(ctx)).also { engine = it }
                e.load(
                    withIndex = _state.value.useIndex,
                    threads = _state.value.threads,
                    onLog = { line ->
                        _state.value = _state.value.copy(engineLog = _state.value.engineLog + line + "\n")
                    },
                )
                _state.value = _state.value.copy(
                    loadingEngine = false, engineLoaded = true,
                    engineLog = _state.value.engineLog + tr("模型加载完成\n"),
                )
            } catch (t: Throwable) {
                L.e("loadEngine 失败", t)
                _state.value = _state.value.copy(
                    loadingEngine = false,
                    error = tr("模型加载失败：{0}", t.message),
                )
            }
        }
    }

    // ---------------------------------------------------------------- 输入通路

    private val audioExts = setOf("wav", "mp3", "m4a", "aac", "flac", "ogg")

    /** 扫描 App 私有目录（外部优先）里的音频文件，按名字排序 */
    fun scanImportableAudio(): List<File> {
        val roots = listOfNotNull(ctx.getExternalFilesDir(null), ctx.filesDir)
        return roots.asSequence()
            .flatMap { d -> d.listFiles()?.asSequence() ?: emptySequence() }
            .filter { it.isFile && it.extension.lowercase() in audioExts }
            .sortedBy { it.name }
            .toList()
    }

    /**
     * 免文件选择器的导入：把 wav/mp3 丢进 App 私有目录，点一下就能载入。
     * 模拟器 / 真机自动化都用这条路。
     */
    fun importFromDir() {
        val found = scanImportableAudio()
        L.i("importFromDir 候选 ${found.size} 个：" + found.joinToString(", ") { it.name })
        val f = found.firstOrNull()
        if (f == null) {
            val hint = ctx.getExternalFilesDir(null)?.absolutePath ?: ctx.filesDir.absolutePath
            _state.value = _state.value.copy(error = tr("App 目录里没找到音频；把 wav/mp3 放到 {0}", hint))
            return
        }
        pickAudio(Uri.fromFile(f))
    }

    fun pickAudio(uri: Uri) {
        _state.value = _state.value.copy(busy = true, stage = tr("解码音频"), progress = 0.02f, error = null, resultFile = null)
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val d = AudioIo.decodeTo16k(ctx, uri)
                pendingSrc = uri
                pending16k = d.samples
                val sec = d.samples.size / 16000f
                L.i("解码完成 ${d.sourceName} 原始 ${d.originRate}Hz -> 16k ${d.samples.size} 点 (%.2f 秒)".format(sec))
                _state.value = _state.value.copy(
                    busy = false,
                    srcName = d.sourceName,
                    srcInfo = tr("原始 {0} Hz · %.1f 秒 · 已重采样到 16 kHz", d.originRate).format(sec),
                )
            } catch (t: Throwable) {
                L.e("音频解码失败", t)
                _state.value = _state.value.copy(busy = false, error = tr("音频解码失败：{0}", t.message))
            }
        }
    }

    /** 用一段已经解好的 16k 音频（模拟器/测试注入用） */
    fun setPreparedAudio(name: String, samples16k: FloatArray) {
        pending16k = samples16k
        _state.value = _state.value.copy(
            srcName = name,
            srcInfo = tr("已注入 16 kHz 音频 · %.1f 秒").format(samples16k.size / 16000f),
            resultFile = null, error = null,
        )
    }

    fun convert() {
        val audio = pending16k
        if (audio == null) {
            _state.value = _state.value.copy(error = tr("先选一段音频"))
            return
        }
        if (!_state.value.engineLoaded) {
            _state.value = _state.value.copy(error = tr("模型还没加载，先到「模型」页加载"))
            return
        }
        val s = _state.value
        if (s.busy) return
        _state.value = s.copy(busy = true, stage = tr("准备"), progress = 0f, error = null, resultFile = null)
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val cfg = RvcConfig(
                    outSampleRate = 40000,
                    pitchShift = s.pitchShift,
                    indexRate = if (s.useIndex) s.indexRate else 0f,
                    protect = s.protect,
                    sid = s.sid,
                    overlapFrames = s.overlap,
                    topK = 8,
                    seed = System.nanoTime(),
                )
                val t0 = System.currentTimeMillis()
                val out = engine!!.convert(audio, cfg) { stage, pct ->
                    _state.value = _state.value.copy(stage = stage, progress = pct)
                }
                val ms = System.currentTimeMillis() - t0
                val stamp = SimpleDateFormat("MMdd_HHmmss", Locale.US).format(Date())
                val base = (s.srcName ?: "audio").substringBeforeLast('.') + "_micmask_$stamp"
                val saved = AudioIo.saveWav(ctx, out, 40000, base)
                val srcSec = audio.size / 16000f
                L.i("完成：输出 ${out.size} 点 @40k，文件 ${saved.file.absolutePath}，耗时 ${ms}ms，RTF=${"%.3f".format(ms / 1000.0 / srcSec)}")
                _state.value = _state.value.copy(
                    busy = false, stage = "", progress = 1f,
                    resultFile = saved.file,
                    resultDisplay = saved.displayPath,
                    resultSeconds = out.size / 40000f,
                    elapsedMs = ms,
                    toast = tr("转换完成（%.2f 秒音频用了 %.1f 秒，RTF=%.2f）").format(
                        srcSec, ms / 1000.0, ms / 1000.0 / srcSec
                    ),
                )
            } catch (t: Throwable) {
                L.e("转换失败", t)
                _state.value = _state.value.copy(
                    busy = false, stage = "",
                    error = tr("转换失败：{0}", t.message),
                )
            }
        }
    }

    // ---------------------------------------------------------------- 调试通路

    /**
     * DEBUG 专用：耳机回环自测。
     *
     * 外部私有目录放 `LOOPTEST` 文件即触发；待播放音频取 `qq_pending.txt` 第 2 行。
     * 结果写 `looptest_result.txt`（中文 log 在 PowerShell 里会乱码，所以落盘）。
     *
     * 目的：验证「耳机喇叭放的声音能不能被耳机麦收回去」——
     * 这是蓝牙耳机当虚拟麦克风方案的唯一成立条件。
     */
    private fun loopTestIfRequested() {
        if (!BuildConfig.DEBUG) return
        val ext = ctx.getExternalFilesDir(null) ?: return
        if (!File(ext, "LOOPTEST").exists()) return
        L.i("=== LOOPTEST 触发 ===")
        viewModelScope.launch(Dispatchers.Default) {
            val out = File(ext, "looptest_result.txt")
            try {
                // 找待播放音频：qq_pending.txt 第 2 行，或 output 目录里最新的 wav
                val wav = runCatching {
                    val pf = File(ext, "qq_pending.txt")
                    val lines = if (pf.exists()) pf.readText().split("\n") else emptyList()
                    val p = lines.getOrNull(1)?.trim().orEmpty()
                    if (p.isNotEmpty() && File(p).exists()) File(p) else null
                }.getOrNull() ?: File(ext, "output").listFiles()
                    ?.filter { it.extension == "wav" }
                    ?.maxByOrNull { it.lastModified() }

                if (wav == null) {
                    out.writeText("LOOPTEST 失败：找不到可播放的 wav（qq_pending.txt 第 2 行 / output 目录）")
                    L.w("=== LOOPTEST 无音频可播 ===")
                    return@launch
                }
                L.i("LOOPTEST 播放源 = ${wav.absolutePath}")

                val r = HeadsetLoopTest.run(ctx, wav)
                val txt = buildString {
                    appendLine("=== 耳机回环自测结果 ===")
                    appendLine("播放文件     : ${wav.name}")
                    appendLine()
                    appendLine("【第 1 组：A2DP 默认通路】")
                    appendLine("  静音基线   : ${"%.1f".format(r.silentDb)} dBFS")
                    appendLine("  回环时能量 : ${"%.1f".format(r.loopDb)} dBFS")
                    appendLine("  回环增益   : ${"%.1f".format(r.gainDb)} dB")
                    appendLine()
                    appendLine("【第 2 组：主动建立 SCO】")
                    appendLine("  startBluetoothSco 成功 : ${r.scoStarted}")
                    appendLine("  SCO 组增益             : ${r.scoGainDb?.let { "%.1f".format(it) } ?: "未测" } dB")
                    appendLine("  SCO 组输出设备         : ${r.scoOutputDevice.ifEmpty { "未测" }}")
                    appendLine()
                    appendLine("【第 3 组：外放 → 手机自带麦】（耳机路线证伪后的主力路线）")
                    appendLine("  静音基线   : ${"%.1f".format(r.speakerSilentDb)} dBFS")
                    appendLine("  播放时能量 : ${"%.1f".format(r.speakerLoopDb)} dBFS")
                    appendLine("  外放增益   : ${"%.1f".format(r.speakerGainDb)} dB")
                    appendLine("  外放输出设备: ${r.speakerOutputDevice.ifEmpty { "未测" }}")
                    appendLine("  外放结论   : ${r.speakerVerdict}")
                    appendLine()
                    appendLine("实际输出设备 : ${r.outputDevice}")
                    appendLine("实际输入设备 : ${r.inputDevice}")
                    appendLine("录音source   : ${r.audioSource}（3=VOICE_COMMUNICATION）")
                    appendLine("录音采样率   : ${r.sampleRate}")
                    appendLine()
                    appendLine("结论         : ${r.verdict}")
                    appendLine("可用         : ${if (r.ok) "是" else "否"}")
                    appendLine("（第 1/2 组是「蓝牙耳机回环」，已证伪，保留作对照）")
                }
                out.writeText(txt)
                L.i("=== LOOPTEST DONE ===")
                L.i("LOOPTEST 结论：${r.verdict}")
                L.i("LOOPTEST 输出=${r.outputDevice} 输入=${r.inputDevice} gain=${"%.1f".format(r.gainDb)}dB")
            } catch (t: Throwable) {
                out.writeText("LOOPTEST 异常：${t.message}\n${t.stackTraceToString()}")
                L.e("=== LOOPTEST FAILED ===", t)
            }
        }
    }

    /**
     * DEBUG 专用：App 外部私有目录里若存在 `AUTORUN` 文件，则跳过所有 UI 交互，
     * 直接「加载模型 → 导入第一段音频 → 转换 → 存盘」。
     *
     * 模拟器/真机无人值守验证用；release 包里这个文件不存在，自然不触发。
     */
    private fun autoRunIfRequested() {
        if (!BuildConfig.DEBUG) return
        val ext = ctx.getExternalFilesDir(null) ?: return
        if (!File(ext, "AUTORUN").exists()) return
        L.i("=== AUTORUN 触发 ===")
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val e = engine ?: VcEngine(ModelStore.candidateDirs(ctx)).also { engine = it }
                // 线程数可被 <外部目录>/threads.txt 覆盖（调试用）。
                // 背景：ORT 的 INT8 量化 MatMul 结果随 intra_op 线程数【分档变化】
                //   （实测桌面：4/8 -> 一档，12 -> 又一档，>=16 与默认 -> 与基准逐位相同）。
                // PC 基准是 32 线程跑出来的，所以只有 >=16 线程才能逐位复现；
                // 默认 threads=0 表示跟随 ORT 默认（手机上即核心数），数值上等价于「本机档位」。
                val thrFile = File(ext, "threads.txt")
                val threads = if (thrFile.exists()) {
                    thrFile.readText().trim().toIntOrNull()?.also { L.i("autorun> 线程数被 threads.txt 覆盖为 $it") } ?: 0
                } else 0
                // 执行提供器：<外部目录>/ep.txt 写 cpu | xnnpack | nnapi（默认 cpu）
                val epFile = File(ext, "ep.txt")
                val ep = if (epFile.exists()) epFile.readText().trim().lowercase() else "cpu"
                // 算子级 profiling：<外部目录>/profile.txt 存在即开启，结果写入 ort_profile/
                val profDir = if (File(ext, "profile.txt").exists()) File(ext, "ort_profile") else null
                e.load(
                    withIndex = _state.value.useIndex,
                    threads = threads,
                    ep = ep,
                    profileDir = profDir,
                ) { L.i("autorun> $it") }
                // net_g 分块并行度：<外部目录>/parallel.txt 写 1..8（默认 4）
                val parFile = File(ext, "parallel.txt")
                if (parFile.exists()) {
                    val n = parFile.readText().trim().toIntOrNull() ?: 4
                    e.setBlockParallelism(n)
                    L.i("autorun> net_g 块并行度被 parallel.txt 覆盖为 $n")
                }
                _state.value = _state.value.copy(engineLoaded = true, engineLog = "模型加载完成（autorun）\n")

                // 输入二选一：
                //  A) input16k.f32 存在 → 直接用（裸 f32 @16k），用于隔离「前端重采样」这一段，
                //     与 PC 基准 audio16k.f32 完全同源；
                //  B) 否则走正常前端：读 44.1k wav → 重采样 16k → 去直流 → 峰值归一化。
                val inF32 = File(ext, "input16k.f32")
                val samples: FloatArray
                val srcLabel: String
                if (inF32.exists() && inF32.length() > 4096) {
                    val n = (inF32.length() / 4).toInt()
                    samples = Wav.readF32(inF32, n)
                    srcLabel = "input16k.f32(裸,${n}点)"
                    L.i("autorun> 使用裸 16k 输入 ${n} 点")
                } else {
                    val f = scanImportableAudio().firstOrNull()
                        ?: throw IllegalStateException("App 目录里没有音频文件")
                    val d = AudioIo.decodeTo16k(ctx, Uri.fromFile(f))
                    samples = d.samples
                    srcLabel = "${f.name}(原始${d.originRate}Hz)"
                    L.i("autorun> 音频 ${f.name} 原始 ${d.originRate}Hz -> ${d.samples.size} 点")
                }

                // 把真正喂进管线的 16k 波形落盘，便于 PC 侧用同一输入复算（隔离前端差异）
                Wav.writeF32(File(ext, "audio16k_dump.f32"), samples)
                var pk = 1e-9f
                var msum = 0.0
                for (v in samples) { if (abs(v) > pk) pk = abs(v); msum += v }
                L.i("autorun> 管线输入 %d 点 peak=%.5f mean=%.6f".format(samples.size, pk, msum / samples.size))

                // 若提供了基准噪声块，则复用（用于「同输入+同噪声」的严格比对）
                val rndRef = File(ext, "blocks_rnd.f32")
                if (rndRef.exists() && rndRef.length() > 4096) {
                    try {
                        e.installRndFromReference(rndRef)
                    } catch (t: Throwable) {
                        L.e("装载基准噪声失败，改用自生成噪声", t)
                    }
                }

                val cfg = RvcConfig(
                    outSampleRate = 40000,
                    pitchShift = _state.value.pitchShift,
                    indexRate = if (_state.value.useIndex) _state.value.indexRate else 0f,
                    protect = _state.value.protect,
                    sid = _state.value.sid,
                    overlapFrames = _state.value.overlap,
                    topK = 8,
                    seed = 12345L,
                )
                val t0 = System.currentTimeMillis()
                val out = e.convertWithDebugDump(samples, cfg, File(ext, "dbg"))
                val ms = System.currentTimeMillis() - t0
                L.i("分阶段耗时(ms): " + e.timings().entries.joinToString(", ") { "${it.key}=${it.value}" })
                // 原始 f32 输出（未经 16bit 量化），与 out_40000.f32 直接比对
                Wav.writeF32(File(ext, "out_40000_dump.f32"), out)
                val saved = AudioIo.saveWav(ctx, out, 40000, "autorun_40000")
                val srcSec = samples.size / 16000f
                L.i("=== AUTORUN DONE === [$srcLabel] 输出 ${out.size} 点 @40k -> ${saved.file.absolutePath} 用时 ${ms}ms RTF=${"%.3f".format(ms / 1000.0 / srcSec)}")
                _state.value = _state.value.copy(
                    engineLoaded = true, busy = false,
                    srcName = srcLabel,
                    resultFile = saved.file,
                    resultDisplay = saved.displayPath,
                    resultSeconds = out.size / 40000f,
                    elapsedMs = ms,
                )
                if (profDir != null) {
                    // 释放 net_g 会话，强制 ONNX Runtime 把算子级 profile 落盘
                    e.flushProfileAndUnload()
                    L.i("profile flushed -> ${profDir.absolutePath}")
                }
            } catch (t: Throwable) {
                L.e("=== AUTORUN FAILED ===", t)
                _state.value = _state.value.copy(error = "autorun 失败：${t.message}")
            }
        }
    }

    /**
     * TTS 无人值守通路（调试用）。
     *
     * 真机上 `adb shell input text` 无法输入中文，所以合成验证走文件驱动：
     * ```
     * <外部目录>/TTSAUTORUN      存在即触发（内容忽略）
     * <外部目录>/tts_text.txt    要合成的文本（UTF-8）
     * <外部目录>/tts_emo.txt     可选，joy|sad|angry|calm
     * <外部目录>/tts_seed.txt    可选，采样种子（默认 11）
     * ```
     * 结果 wav 落在 `<外部目录>/output/`。
     */
    /**
     * TTS 无人值守通路（仅 debug 包）。
     *
     * 真机上 `adb shell input text` 无法输入中文，所以合成验证走文件驱动：
     * ```
     * <外部目录>/TTSAUTORUN      存在即触发（内容忽略）
     * <外部目录>/tts_text.txt    要合成的文本（UTF-8）
     * <外部目录>/tts_emo.txt     可选，joy|sad|angry|calm（默认 calm）
     * <外部目录>/tts_seed.txt    可选，采样种子（默认 11）
     * <外部目录>/tts_threads.txt 可选，ORT 线程数（默认 4）
     * ```
     * 结果 wav 落在 `<外部目录>/output/tts/`。
     */
    private fun ttsAutoRunIfRequested() {
        if (!BuildConfig.DEBUG) return
        val ext = ctx.getExternalFilesDir(null) ?: return
        if (!File(ext, "TTSAUTORUN").exists()) return
        val textFile = File(ext, "tts_text.txt")
        if (!textFile.exists()) { L.w("TTSAUTORUN 存在但缺 tts_text.txt"); return }
        L.i("=== TTSAUTORUN 触发 ===")
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val text = textFile.readText(Charsets.UTF_8).trim()
                val emo = File(ext, "tts_emo.txt").let {
                    if (it.exists()) it.readText().trim().lowercase() else "calm"
                }
                val seed = File(ext, "tts_seed.txt").let {
                    if (it.exists()) it.readText().trim().toLongOrNull() ?: 11L else 11L
                }
                val thr = File(ext, "tts_threads.txt").let {
                    if (it.exists()) it.readText().trim().toIntOrNull() ?: 4 else 4
                }
                L.i("tts-autorun> text=\"$text\" emo=$emo seed=$seed threads=$thr")

                // 诊断开关：写 tts_uipath.txt=1 就走**完整 UI 路径**（synthesizeTts），
                // 用来区分「引擎本身的问题」和「UI 编排层的问题」。
                // 用户报「合成失败/闪退」但 AUTORUN 直接调引擎是好的 —— 差异必在编排层。
                if (File(ext, "tts_uipath.txt").let { it.exists() && it.readText().trim() == "1" }) {
                    L.i("tts-autorun> 走 UI 路径（synthesizeTts）")
                    _state.value = _state.value.copy(
                        ttsText = text,
                        ttsEmotion = if (emo in RefFeatureStore.EMOTIONS) emo else "calm",
                    )
                    withContext(Dispatchers.Main) { synthesizeTts() }
                    return@launch
                }

                // 1) 加载引擎（assetsDir == modelDir：文本表与模型同目录）
                val dir = TtsAssetStore.dir(ctx)
                val e = TtsEngine(dir, dir)
                L.i("tts-autorun> " + e.load(thr))

                // 2) 参考特征
                val loaded = RefFeatureStore.load(ctx, emo)
                    ?: throw IllegalStateException("缺少 ref_$emo.refbin")
                val ref = loaded.feat
                L.i("tts-autorun> ref $emo sslLen=${ref.sslLen} prompt=${ref.prompt.size} refer=${ref.refer.size}")

                // 3) 合成
                val refText = RefFeatureStore.REF_TEXTS[emo] ?: ""
                val t0 = System.currentTimeMillis()
                val (audio, tm) = e.synthesize(
                    text = text,
                    refText = refText,
                    ref = ref,
                    temperature = _state.value.ttsTemperature,
                    topK = _state.value.ttsTopK,
                    topP = _state.value.ttsTopP,
                    repetition = _state.value.ttsRepetition,
                    minSteps = 45,
                    seed = seed,
                    noiseSeed = seed,
                )
                val ms = System.currentTimeMillis() - t0

                var peak = 0f
                var sum = 0.0
                for (v in audio) {
                    val a = kotlin.math.abs(v)
                    if (a > peak) peak = a
                    sum += v.toDouble() * v
                }
                val rms = kotlin.math.sqrt(sum / audio.size.coerceAtLeast(1)).toFloat()

                val savedFile = AudioIo.saveLocalWav(
                    ctx, audio, TtsEngine.SAMPLE_RATE, "tts", "tts_${emo}_aut$seed", normalize = false
                )
                L.i("tts-autorun> 耗时 g2p=${tm.g2pMs} bert=${tm.bertMs} encoder=${tm.encoderMs} " +
                    "prefill=${tm.prefillMs} ar=${tm.arMs}(${tm.arSteps} 步, ${"%.2f".format(tm.msPerStep())}ms/步) " +
                    "vits=${tm.vitsMs} 合计=${ms}ms")
                L.i("tts-autorun> 音频 ${audio.size} 点 @32k = ${"%.2f".format(audio.size / 32000f)}s " +
                    "peak=${"%.4f".format(peak)} rms=${"%.5f".format(rms)}")
                L.i("=== TTSAUTORUN DONE === ${savedFile.absolutePath}")

                // ttsEmotion 必须同步，否则结果卡会显示上一次（或默认）的情绪名，
                // 出现「文件是 sad 却显示平淡」这种对不上的情况。
                _state.value = _state.value.copy(
                    ttsBusy = false,
                    ttsEmotion = if (emo in RefFeatureStore.EMOTIONS) emo else "calm",
                    ttsArSteps = tm.arSteps,
                    ttsMsPerStep = tm.msPerStep(),
                    ttsRms = rms,
                    ttsResultFile = savedFile,
                    ttsResultSeconds = audio.size / 32000f,
                    ttsElapsedMs = ms,
                )
            } catch (t: Throwable) {
                L.e("=== TTSAUTORUN FAILED ===", t)
                _state.value = _state.value.copy(error = "tts autorun 失败：${t.message}")
            }
        }
    }

    override fun onCleared() {
        if (current === this) current = null
        super.onCleared()
        recorder.release()
        engine?.close()
        engine = null
    }

    @Suppress("unused")
    private suspend fun unusedWithContext() = withContext(Dispatchers.Default) { Unit }
}

/** 模型仓库：地址、备注名（空则显示地址）、是否启用 */
data class RepoEntry(val url: String, val name: String = "", val on: Boolean = true) {
    val label: String get() = name.ifBlank { url.substringAfter("://").removeSuffix("/") }
}
