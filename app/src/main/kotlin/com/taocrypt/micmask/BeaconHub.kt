package com.taocrypt.micmask

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.taocrypt.beacon.Beacon
import com.taocrypt.beacon.BeaconConfig
import com.taocrypt.beacon.Notice
import com.taocrypt.beacon.Slot

/**
 * Beacon 后台（自建：公告 / 自营推荐位 / 匿名统计 / 反馈）在 App 里的薄封装。
 *
 * - 只在 App 主进程初始化（[MicMaskApp]）；Shizuku shell 进程（InjectorService 等）不走 Application，也不会调用这里。
 * - BuildConfig 里的 BEACON_* 为空（源码、本地构建、fork）时 SDK 关闭：[enabled]=false，所有入口隐藏、调用空操作。
 * - 统计只在首启同意弹窗里勾选「同意匿名统计」（默认勾选）并同意，或在 关于 → 其他 打开后才上报。
 */
object BeaconHub {
    /** 同意弹窗（免责声明 + 信息收集说明）版本；声明有实质改动时 +1，所有人重新确认 */
    const val CONSENT_VERSION = 2  // 2：alpha94 加基础统计（始终开启）、匿名统计默认勾选
    private const val K_CONSENT = "consent_v"
    private const val K_SLOTS = "show_slots"

    @Volatile private var inited = false
    val enabled: Boolean get() = inited && Beacon.isEnabled

    /** Compose 可观察：统计开关、推荐位开关、配置 */
    @set:JvmName("setAnalyticsState") var analytics by mutableStateOf(false); private set
    /** 推荐位总开关已移除（alpha94），恒为 true；保留字段供现有判断使用 */
    val showSlots = true
    var config by mutableStateOf<BeaconConfig?>(null); private set

    /** 本次启动待弹的 dialog 公告（首次拉到配置时快照一次） */
    val dialogQueue = mutableStateListOf<Notice>()
    /** 本次启动的横幅公告（关闭即移除） */
    val banners = mutableStateListOf<Notice>()
    private var queued = false
    /** 推荐位：每个位置每次启动挑一次，避免重组时随机换来换去 */
    private val picked = HashMap<String, Slot?>()
    /** 多条位置（repo_list）：每次启动排一次序，避免重组时顺序变 */
    private val pickedMulti = HashMap<String, List<Slot>>()

    fun init(app: Application) {
        if (inited) return
        val o = Beacon.Options(
            baseUrl = BuildConfig.BEACON_URL.trimEnd('/'),
            appId = BuildConfig.BEACON_APP_ID,
            clientKey = BuildConfig.BEACON_KEY,
            versionCode = BuildConfig.VERSION_CODE.toLong(),
            channel = "github",
            track = if (Regex("alpha|beta", RegexOption.IGNORE_CASE).containsMatchIn(BuildConfig.VERSION_NAME)) "beta" else "stable",
            debug = BuildConfig.DEBUG,
        )
        Beacon.init(app, o)
        appCtx = app.applicationContext
        clearLegacyDismiss(appCtx!!)
        loadBoundSponsor(appCtx!!)
        inited = true
        analytics = Beacon.isAnalyticsEnabled()
        // 基础统计只在同意过当前版本说明后开启；旧版同意（v1）要重新弹窗确认
        Beacon.setEssentialEnabled(enabled && !consentNeeded(app))
        if (!Beacon.isEnabled) return
        config = Beacon.config
        installCrashHook()
        fetch()
    }

    fun fetch() {
        if (!enabled) return
        Beacon.fetchConfig { c ->
            config = c
            // 配置刷新后重挑
            synchronized(picked) { picked.clear() }
            synchronized(pickedMulti) { pickedMulti.clear() }
            if (!queued && c != null) {
                queued = true
                // 阻断公告不进队列（由 [blockingNotice] 随配置实时判断）；强制阅读（ack）不论 style 都按弹窗处理并排在最前
                val p = Beacon.pendingNotices().filter { it.force != "block" }
                dialogQueue.addAll(p.filter { it.style == "dialog" || it.force == "ack" }
                    .sortedWith(compareBy<Notice> { if (it.force == "ack") 0 else 1 }.thenByDescending { it.priority }))
                banners.addAll(p.filter { it.style == "banner" && it.force != "ack" }.sortedByDescending { it.priority })
            }
        }
    }

    // ------------------------------------------------ 同意

    fun consentNeeded(ctx: Context) = Prefs.main(ctx).getInt(K_CONSENT, 0) < CONSENT_VERSION

    /** 同意弹窗「同意并继续」：记录版本；统计开关按勾选框 */
    fun acceptConsent(ctx: Context, stats: Boolean) {
        Prefs.main(ctx).edit().putInt(K_CONSENT, CONSENT_VERSION).apply()
        Beacon.setEssentialEnabled(enabled)
        setAnalytics(stats)
        L.i("同意弹窗 v$CONSENT_VERSION：已同意，匿名统计=${stats && enabled}")
    }

    fun setAnalytics(on: Boolean) {
        val v = on && enabled
        Beacon.setAnalyticsEnabled(v)
        analytics = v
    }


    // ------------------------------------------------ 统计

    /** 自定义事件（未同意 / SDK 关闭时空操作）。只传类别与数值，不传用户内容 */
    fun event(name: String, props: Map<String, Any?> = emptyMap()) {
        if (!enabled || !analytics) return
        runCatching { Beacon.track(name, props) }
    }

    /** $crash：异常类名 + 前 3 个本包栈帧；同步写进本地队列后再交回原 handler（链式） */
    private fun installCrashHook() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, ex ->
            runCatching {
                if (Beacon.isAnalyticsEnabled()) {
                    val frames = ArrayList<String>()
                    var t: Throwable? = ex
                    var depth = 0
                    while (t != null && frames.size < 3 && depth < 5) {
                        for (f in t.stackTrace) {
                            if (f.className.startsWith("com.taocrypt.micmask")) {
                                frames += "${f.className.removePrefix("com.taocrypt.micmask.")}.${f.methodName}:${f.lineNumber}".take(100)
                                if (frames.size >= 3) break
                            }
                        }
                        t = t.cause; depth++
                    }
                    val p = linkedMapOf<String, Any?>("cls" to ex.javaClass.name.take(100), "thread" to (if (th.name == "main") "main" else "bg"))
                    frames.forEachIndexed { i, s -> p["f${i + 1}"] = s }
                    Beacon.trackSync("\$crash", p)
                }
            }
            prev?.uncaughtException(th, ex)
        }
    }

    // ------------------------------------------------ 推荐位

    // 频控/关闭规则（alpha90，alpha91 调整）：
    // - 每个位置每次启动挑一次（[picked]），本次启动内一直显示同一条，滑动/重组不换。
    // - 曝光：同一条（slot id）同一位置每个本地日期只上报一次 $slot_view，记在 prefs [K_SEEN]（"yyyyMMdd|id|pos"，跨日自动清理）；
    //   alpha91 起 slot_seen 只用于上报去重，不再影响显示：当天已曝光的条目之后的启动照常挑选/显示（长期显示，不再"每天只显示一次"）。
    // - 关闭（alpha96 起所有位置统一）：按条目记 [K_DISMISS_IDS] "yyyyMMdd|id|pos"，只隐藏那一条到当天本地 24:00；
    //   同位置还有别的条目时当场换下一条。旧版按位置写的 slot_dismiss_<pos>（含 home_bottom 7 天）启动时清掉、不再生效。
    // 多条位置（alpha91，目前只有 repo_list，见 [MULTI_POSITIONS] / [slots]）：
    // - 该位置所有条目都展示：priority 降序、同优先级按 weight 加权随机排序，每次启动排一次；
    // - 曝光上报同单条位置：按 "日期|id|位置" 当天只报一次 $slot_view；
    // - 关闭按条目：prefs [K_DISMISS_IDS] 记 "yyyyMMdd|id|pos"，只隐藏那一条到当天结束，其他条目不受影响；
    //   旧的位置级 slot_dismiss_repo_list（alpha90 写的，到当天 24:00）仍会整位置隐藏到期为止，新版不再写它。
    private const val K_SEEN = "slot_seen"
    private const val K_DISMISS = "slot_dismiss_"
    private const val K_DISMISS_IDS = "slot_dismiss_ids"
    val MULTI_POSITIONS = setOf("repo_list")
    private var appCtx: Context? = null
    /** 本次启动已曝光的 "id|pos"：保持显示，不因当天已曝光而消失 */
    private val sessionSeen = HashSet<String>()
    /** 关闭后让 Compose 重组 */
    private var slotRev by mutableStateOf(0)

    private fun today(): String = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())

    /** alpha96：清掉旧版按位置的关闭记录（home_bottom 7 天等），改为按条目当天隐藏 */
    private fun clearLegacyDismiss(ctx: Context) {
        val sp = Prefs.main(ctx)
        val keys = sp.all.keys.filter { it.startsWith(K_DISMISS) && it != K_DISMISS_IDS }
        if (keys.isNotEmpty()) sp.edit().apply { keys.forEach { remove(it) } }.apply()
    }

    /** 同 SDK pickSlot（最高优先级组内按权重随机）。alpha91 起当天已曝光的条目照常参与挑选（slot_seen 只用于上报去重） */
    private fun pickFresh(ctx: Context, c: BeaconConfig, position: String): Slot? {
        val gone = dismissedIdsToday(ctx)
        val list = c.slots.filter { it.position == position && "${it.id}|$position" !in gone }
        if (list.isEmpty()) return null
        val top = list.maxOf { it.priority }
        val cands = list.filter { it.priority == top }
        var r = (Math.random() * cands.sumOf { maxOf(1, it.weight) }).toInt()
        for (s in cands) { r -= maxOf(1, s.weight); if (r < 0) return s }
        return cands.last()
    }

    // ------------------------------------------------------------------ 赞助者（Beacon 赞助后端）

    /**
     * 赞助状态验签公钥（ECDSA P-256，SPKI DER base64，Beacon 后台公布）。
     * 只认签名有效、app/iid 与本机一致的状态（app/iid 在待签内容里，不一致则验签失败）。留空 = 接受未签名（仅调试）。
     */
    private const val SPONSOR_PUBKEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEo9nVkReXAI+NQT0vG91+i9yLrk7Qt3ZGrPeB8hlyZgoxOQFsAUX5idzJg9NgehsK6ozxmXXLUtHgWogLXvdmSg=="
    private const val K_SPONSOR = "sponsor_state"
    /** bind 成功后本地保存的签名状态（重启后在配置刷新前也能生效） */
    var boundSponsor by mutableStateOf<org.json.JSONObject?>(null); private set

    private val beaconApp get() = BuildConfig.BEACON_APP_ID.ifBlank { "micmask" }
    private fun iid() = Beacon.installId()
    /** 本设备 ID（赞赏弹窗里显示、可复制，找作者处理赞助时用）；Beacon 关闭时为 null */
    val deviceId: String? get() = if (enabled) runCatching { iid() }.getOrNull() else null

    /** 验证 {until, iat, sig}；返回有效到期时间（毫秒），无效返回 0 */
    private fun verifiedUntil(o: org.json.JSONObject?): Long {
        o ?: return 0L
        val until = o.optLong("until", 0L)
        if (until <= 0) return 0L
        if (SPONSOR_PUBKEY.isBlank()) return until
        val sig = o.optString("sig").takeIf { it.isNotBlank() } ?: return 0L
        val iat = o.optLong("iat", 0L)
        return runCatching {
            val key = java.security.KeyFactory.getInstance("EC").generatePublic(
                java.security.spec.X509EncodedKeySpec(android.util.Base64.decode(SPONSOR_PUBKEY, android.util.Base64.DEFAULT)))
            val msg = "beacon-sponsor-v1\n$beaconApp\n${iid()}\n$until\n$iat".toByteArray(Charsets.UTF_8)
            val raw = android.util.Base64.decode(sig, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
            val v = java.security.Signature.getInstance("SHA256withECDSA")
            v.initVerify(key); v.update(msg)
            if (raw.size == 64 && v.verify(p1363ToDer(raw))) until else 0L
        }.getOrDefault(0L)
    }

    /** WebCrypto 的 r||s（各 32 字节）→ Java 要的 DER SEQUENCE{INTEGER r, INTEGER s} */
    private fun p1363ToDer(rs: ByteArray): ByteArray {
        fun int(b: ByteArray): ByteArray {
            var i = 0; while (i < b.size - 1 && b[i] == 0.toByte()) i++
            var v = b.copyOfRange(i, b.size)
            if (v[0].toInt() and 0x80 != 0) v = byteArrayOf(0) + v
            return byteArrayOf(0x02, v.size.toByte()) + v
        }
        val body = int(rs.copyOfRange(0, 32)) + int(rs.copyOfRange(32, 64))
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    /** 赞助者到期时间（毫秒；配置下发或订单号绑定得到的签名状态，取较晚者；0=不是赞助者） */
    val sponsorUntil: Long get() = maxOf(verifiedUntil(config?.raw?.optJSONObject("sponsor")), verifiedUntil(boundSponsor))
    /** 赞助者：有效期内隐藏所有推荐位 */
    val isSponsor: Boolean get() = enabled && sponsorUntil > System.currentTimeMillis()

    /** 赞赏墙名单接口（Beacon 关闭时为 null，走 GitHub sponsors.json 兜底） */
    val sponsorsUrl: String? get() = if (enabled) "${BuildConfig.BEACON_URL.trimEnd('/')}/v1/$beaconApp/sponsors" else null

    /** 爱发电下单链接：带 custom_order_id=<app>:<设备 ID>，付款后后台自动把本设备记为赞助者。planId 为空=自选金额 */
    fun donateUrl(planId: String?): String {
        val sb = StringBuilder("https://afdian.com/order/create?product_type=0")
        if (!planId.isNullOrBlank()) sb.append("&plan_id=").append(planId)
        if (enabled) sb.append("&custom_order_id=").append(java.net.URLEncoder.encode("$beaconApp:${iid()}", "UTF-8"))
        return sb.toString()
    }

    /** 订单号绑定结果：ok / not_found / limit / expired / rate_limited / bad_request / not_configured / network */
    data class BindResult(val code: String, val until: Long = 0, val maxDevices: Int = 0, val expire: Long = 0, val evicted: Int = 0, val nextAt: Long = 0)

    /** 用爱发电订单号把本设备绑定为赞助者（网页付款、换机、重装后用）。在 IO 线程调用。 */
    fun bindSponsor(orderNo: String): BindResult {
        if (!enabled) return BindResult("not_configured")
        return runCatching {
            val c = (java.net.URL("${BuildConfig.BEACON_URL.trimEnd('/')}/v1/$beaconApp/sponsors/bind").openConnection() as java.net.HttpURLConnection)
            try {
                val body = org.json.JSONObject().put("iid", iid()).put("order_no", orderNo.trim()).toString().toByteArray()
                c.requestMethod = "POST"; c.connectTimeout = 8000; c.readTimeout = 10000; c.doOutput = true
                c.setRequestProperty("X-Client-Key", BuildConfig.BEACON_KEY)
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body) }
                val code = c.responseCode
                val text = (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                val j = runCatching { org.json.JSONObject(text) }.getOrDefault(org.json.JSONObject())
                when (code) {
                    200 -> {
                        val until = verifiedUntil(j)
                        if (until > 0) {
                            appCtx?.let { Prefs.main(it).edit().putString(K_SPONSOR, j.toString()).apply() }
                            boundSponsor = j
                            BindResult("ok", until, maxDevices = j.optInt("max_devices", 3), evicted = j.optInt("evicted", 0))
                        } else BindResult("bad_sig")
                    }
                    404 -> BindResult("not_found")
                    409 -> BindResult("limit", nextAt = j.optLong("next_at", 0))
                    410 -> BindResult("expired", expire = j.optLong("expire", 0))
                    429 -> BindResult("rate_limited")
                    503 -> BindResult("not_configured")
                    else -> BindResult(j.optString("error").ifBlank { "bad_request" })
                }
            } finally { c.disconnect() }
        }.getOrElse { BindResult("network") }
    }

    private fun loadBoundSponsor(ctx: Context) {
        boundSponsor = Prefs.main(ctx).getString(K_SPONSOR, null)?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
    }

    fun slot(position: String): Slot? {
        @Suppress("UNUSED_VARIABLE") val rev = slotRev
        if (!enabled || !showSlots) return null
        if (isSponsor) return null
        val c = config ?: return null
        val ctx = appCtx ?: return null
        return synchronized(picked) {
            val cur = picked[position]
            if (cur != null && "${cur.id}|$position" !in dismissedIdsToday(ctx)) cur
            else pickFresh(ctx, c, position).also { if (it != null) picked[position] = it else picked.remove(position) }
        }
    }

    /** 真正显示时调用；同一条同一位置当天只上报一次（修 LazyColumn 滑回重复上报） */
    fun slotViewed(s: Slot, position: String = s.position) {
        val ctx = appCtx ?: return
        val k = "${s.id}|$position"
        synchronized(sessionSeen) {
            if (k in sessionSeen) return
            sessionSeen += k
        }
        val d = today()
        val sp = Prefs.main(ctx)
        val cur = sp.getStringSet(K_SEEN, emptySet())!!
        if ("$d|$k" in cur) return
        sp.edit().putStringSet(K_SEEN, cur.filter { it.startsWith("$d|") }.toSet() + "$d|$k").apply()
        Beacon.reportSlotView(s)
    }

    private fun dismissedIdsToday(ctx: Context): Set<String> {
        val d = today() + "|"
        return Prefs.main(ctx).getStringSet(K_DISMISS_IDS, emptySet())!!.filter { it.startsWith(d) }.map { it.removePrefix(d) }.toSet()
    }

    /** 加权随机排序（不放回）：同优先级里 weight 大的更可能排前面 */
    private fun weightedShuffle(list: List<Slot>): List<Slot> {
        val rest = list.toMutableList()
        val out = ArrayList<Slot>(list.size)
        while (rest.isNotEmpty()) {
            var r = Math.random() * rest.sumOf { maxOf(1, it.weight) }
            var idx = rest.lastIndex
            for ((i, s) in rest.withIndex()) { r -= maxOf(1, s.weight); if (r < 0) { idx = i; break } }
            out += rest.removeAt(idx)
        }
        return out
    }

    /**
     * 多条位置：返回该位置所有可展示条目（priority 降序，同优先级按 weight 加权随机），每次启动排一次序。
     * 当天已曝光的不排除；按条目关闭的（当天）过滤掉；单条位置请用 [slot]。
     */
    fun slots(position: String): List<Slot> {
        @Suppress("UNUSED_VARIABLE") val rev = slotRev
        if (!enabled || !showSlots) return emptyList()
        if (isSponsor) return emptyList()
        val c = config ?: return emptyList()
        val ctx = appCtx ?: return emptyList()
        val all = synchronized(pickedMulti) {
            pickedMulti.getOrPut(position) {
                c.slots.filter { it.position == position }.groupBy { it.priority }
                    .toSortedMap(compareByDescending { it }).values.flatMap { weightedShuffle(it) }
            }
        }
        val gone = dismissedIdsToday(ctx)
        return all.filter { "${it.id}|$position" !in gone }
    }

    /** 关闭某一条推荐：只隐藏这一条到当天本地 24:00（所有位置通用） */
    fun dismissSlotItem(s: Slot, position: String = s.position) {
        val ctx = appCtx ?: return
        val d = today()
        val sp = Prefs.main(ctx)
        val cur = sp.getStringSet(K_DISMISS_IDS, emptySet())!!
        sp.edit().putStringSet(K_DISMISS_IDS, cur.filter { it.startsWith("$d|") }.toSet() + "$d|${s.id}|$position").apply()
        slotRev++
    }

    fun openSlot(ctx: Context, s: Slot) = Beacon.openSlot(ctx, s)

    // ------------------------------------------------ 公告

    fun noticeShown(n: Notice) = Beacon.markNoticeShown(n)
    /** 「不再提示」：同一版本不再弹；只对 daily / every_launch 的普通公告提供 */
    fun noticeMuted(n: Notice) = Beacon.markNoticeMuted(n)
    /** 强制阅读公告点了确认：同一版本（rev）不再弹 */
    fun noticeAcked(n: Notice) = Beacon.markNoticeAcked(n)
    /**
     * 当前生效的阻断公告（force=block，停服维护等）。读 [config]（Compose 状态），配置刷新后公告下线/过期即自动解除。
     * SDK 关闭时恒为 null。
     */
    fun blockingNotice(): Notice? {
        if (!enabled) return null
        val c = config ?: return null
        val now = System.currentTimeMillis()
        return c.notices.firstOrNull { it.force == "block" && endOf(c, it.id).let { e -> e == null || e > now } }
    }

    /** 公告结束时间（下发 JSON 的 end，模型里没有）：离线用缓存配置时，已过期的阻断公告不再拦人 */
    private fun endOf(c: BeaconConfig, id: Long): Long? {
        val arr = c.raw.optJSONArray("notices") ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optLong("id") == id) return if (o.isNull("end") || !o.has("end")) null else o.optLong("end")
        }
        return null
    }
    fun noticeClick(n: Notice, button: String) = Beacon.reportNoticeClick(n, button)

    // ------------------------------------------------ 反馈

    /** 反馈（2–2000 字，meta ≤8 项，附件 File/Uri）；回调在主线程，err 形如 "file_too_large (413)"、"429"、"network" */
    fun sendFeedback(content: String, contact: String?, meta: Map<String, Any?>, files: List<Any>, cb: (Boolean, String?) -> Unit) {
        if (!enabled) { cb(false, "disabled"); return }
        Beacon.sendFeedback(content, contact, meta.entries.take(8).associate { it.key to it.value }, files, cb)
    }

    // ------------------------------------------------ 杂项

    /** 主进程判断：Shizuku shell / 其他进程里不初始化 */
    fun isMainProcess(app: Application): Boolean {
        val name = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName()
        else runCatching { java.io.File("/proc/self/cmdline").readText().trim('\u0000', ' ', '\n') }.getOrNull()
        return name == null || name == app.packageName
    }
}

/** 应用入口：只在主进程初始化 Beacon（Shizuku shell 进程用 app_process 启动，不会创建 Application） */
class MicMaskApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!BeaconHub.isMainProcess(this)) return
        runCatching { BeaconHub.init(this) }.onFailure { android.util.Log.w("MicMask", "Beacon init", it) }
    }
}
