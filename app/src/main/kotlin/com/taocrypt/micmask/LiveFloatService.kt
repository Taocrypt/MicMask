package com.taocrypt.micmask

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.taocrypt.micmask.inject.MicInjector
import com.taocrypt.micmask.ui2.cardPresets
import com.taocrypt.micmask.ui2.pitchPresets
import kotlin.math.abs

/**
 * 实时变声悬浮窗：收起是小球（颜色=运行状态，字=当前模式），展开是面板：
 * 性能（CPU/温度/内存/进程/断流）· 三模式切换 · 当前模式预设 · 停止 / 打开 App。
 * 操作经 MainViewModel.current（App 进程在就能用；Activity 被回收时提示打开 App）。
 */
class LiveFloatService : Service() {
    companion object {
        private const val CH = "micmask_live_float"; private const val NID = 0xA772
        @Volatile var running = false; private set
        fun toggle(ctx: Context, on: Boolean) {
            val i = Intent(ctx, LiveFloatService::class.java).setAction(if (on) "show" else "hide")
            runCatching { if (on) ctx.startForegroundService(i) else ctx.startService(i) }.onFailure { L.w("LiveFloatService：${it.message}") }
        }
        private val MODES = listOf("AI", "普通", "声卡")
        private const val ACCENT = 0xFF3482FF.toInt()
    }

    private val h = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var root: LinearLayout? = null
    private lateinit var lp: WindowManager.LayoutParams
    private var expanded = false
    private lateinit var ball: TextView
    private lateinit var panel: LinearLayout
    private lateinit var stat: TextView
    private lateinit var modeRow: LinearLayout
    private lateinit var presetRow: LinearLayout
    private lateinit var sfxRow: LinearLayout
    private var lastSfxKey = ""
    private var lastPresetKey = ""
    private var lastStatT = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "hide") { remove(); stopSelf(); return START_NOT_STICKY }
        foreground()
        if (root == null) {
            if (!FloatingPlayService.canDraw(this)) { stopSelf(); return START_NOT_STICKY }
            add()
        }
        running = true
        return START_STICKY
    }

    override fun onDestroy() { remove(); running = false; super.onDestroy() }

    private fun foreground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "变声悬浮窗", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val hide = PendingIntent.getService(this, 2, Intent(this, LiveFloatService::class.java).setAction("hide"), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CH).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("变声悬浮窗").setContentText("点小球展开：切换模式、预设、看性能")
            .addAction(Notification.Action.Builder(null, "关闭悬浮窗", hide).build()).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(NID, n)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun round(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    private fun add() {
        wm = getSystemService(WindowManager::class.java)
        val p = Prefs.main(applicationContext)
        lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = p.getInt("lf_x", dp(8)); y = p.getInt("lf_y", dp(200)) }

        ball = TextView(this).apply {
            gravity = Gravity.CENTER; setTextColor(Color.WHITE); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(dp(50), dp(50))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xCC333333.toInt()) }
            elevation = dp(6).toFloat()
        }
        stat = TextView(this).apply { setTextColor(0xFFDDDDDD.toInt()); textSize = 11f; typeface = Typeface.MONOSPACE; text = "读取中…" }
        modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sfxRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            stopChip = chip("停止", 0xFFE5484D.toInt()) {
                val v = vm(); val on = (v?.state?.value?.liveOn == true) || MicInjector.liveOn
                if (on) v?.stopAll() ?: run { MicInjector.stopLive(); LiveService.stop(this@LiveFloatService) }
                else if (v == null) openApp() else v.startMode(v.state.value.liveMode)
            }
            addView(stopChip)
            addView(chip("打开 App", 0x33FFFFFF) { openApp() })
            addView(chip("收起", 0x33FFFFFF) { setExpanded(false) })
        }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            background = round(0xE61C1C1E.toInt(), 18); setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(268), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
            addView(stat)
            addView(space(8)); addView(modeRow)
            addView(space(8)); addView(HorizontalScrollView(this@LiveFloatService).apply { isHorizontalScrollBarEnabled = false; addView(presetRow) })
            addView(space(8)); addView(TextView(this@LiveFloatService).apply { text = "语音包"; setTextColor(0xFF999999.toInt()); textSize = 11f })
            addView(space(4)); addView(HorizontalScrollView(this@LiveFloatService).apply { isHorizontalScrollBarEnabled = false; addView(sfxRow) })
            addView(space(8)); addView(bottom)
        }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(ball); addView(panel) }
        attachDrag(ball)
        wm.addView(root, lp)
        tick()
    }

    private fun space(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    private fun chip(text: String, bg: Int, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER
        background = round(bg, 14); setPadding(dp(12), dp(6), dp(12), dp(6))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
        setOnClickListener { onClick(); h.postDelayed({ refresh(true) }, 150) }
    }

    private fun attachDrag(v: View) {
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx; val dy = e.rawY - sy
                    if (abs(dx) + abs(dy) > dp(6)) moved = true
                    if (moved) { lp.x = ox + dx.toInt(); lp.y = oy + dy.toInt(); root?.let { wm.updateViewLayout(it, lp) } }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) setExpanded(!expanded)
                    else Prefs.main(applicationContext).edit().putInt("lf_x", lp.x).putInt("lf_y", lp.y).apply()
                    true
                }
                else -> false
            }
        }
    }

    private fun setExpanded(on: Boolean) {
        expanded = on
        panel.visibility = if (on) View.VISIBLE else View.GONE
        panel.alpha = 0f; if (on) panel.animate().alpha(1f).setDuration(160).start()
        refresh(true)
    }

    private fun vm(): MainViewModel? = MainViewModel.current

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    private val loop = object : Runnable { override fun run() { refresh(false); h.postDelayed(this, 1000) } }
    private fun tick() { h.post(loop) }

    private var stopChip: TextView? = null
    private var lastStopOn: Boolean? = null
    private fun refresh(force: Boolean) {
        if (root == null) return
        val vm = vm()
        val st = vm?.state?.value
        val on = st?.liveOn == true
        val mode = st?.liveMode ?: 0
        ball.text = if (on) MODES[mode] else "变声"
        (ball.background as GradientDrawable).setColor(if (on) ACCENT else 0xCC333333.toInt())
        if (!expanded) return
        // 停止 ↔ 启动
        if (force || lastStopOn != on) {
            lastStopOn = on
            stopChip?.text = if (on) "停止" else "启动"
            stopChip?.background = round(if (on) 0xFFE5484D.toInt() else 0xFF2EA043.toInt(), 14)
        }
        // 性能：每秒一次
        val now = System.currentTimeMillis()
        if (force || now - lastStatT >= 900) { lastStatT = now; stat.text = statText() }
        // 模式按钮
        modeRow.removeAllViews()
        MODES.forEachIndexed { i, n ->
            val sel = st != null && mode == i
            modeRow.addView(chip(if (on && sel) "■ $n" else n, if (on && sel) ACCENT else if (sel) 0x553482FF else 0x33FFFFFF) {
                if (vm == null) openApp() else vm.startMode(i)
            })
        }
        // 语音包
        val sk = "${st?.sfxPlaying}|${st?.sounds?.size}|${st?.sfxLoop}"
        if (force || sk != lastSfxKey) {
            lastSfxKey = sk
            sfxRow.removeAllViews()
            if (vm != null && st != null) {
                if (st.sounds.isEmpty()) sfxRow.addView(chip("去 App 导入素材", 0x33FFFFFF) { openApp() })
                if (st.sfxPlaying != null) sfxRow.addView(chip("■ 停止音效", 0xFFE5484D.toInt()) { vm.stopSound() })
                st.sounds.forEach { so -> sfxRow.addView(chip((if (so.id == st.sfxPlaying) "▶ " else "") + so.name,
                    if (so.id == st.sfxPlaying) ACCENT else 0x33FFFFFF) { vm.playSound(so.id) }) }
            }
        }
        // 预设：仅在模式/选中变化时重建
        val sel = when (mode) { 2 -> st?.fxCardPreset; 1 -> st?.fxPreset; else -> st?.liveVoice }.orEmpty()
        val key = "$mode|$sel|${st?.userPresets?.get(mode)?.size}|${st?.liveVoices?.size}"
        if (force || key != lastPresetKey) {
            lastPresetKey = key
            presetRow.removeAllViews()
            if (vm == null || st == null) { presetRow.addView(chip("先打开 App", 0x33FFFFFF) { openApp() }); return }
            when (mode) {
                0 -> st.liveVoices.forEach { v -> presetRow.addView(chip(vm.voiceLabel(v), if (v == sel) ACCENT else 0x33FFFFFF) { vm.setLiveVoice(v) }) }
                1 -> {
                    pitchPresets.forEach { p -> presetRow.addView(chip(p.name, if (p.name == sel) ACCENT else 0x33FFFFFF) { vm.setPitchSemis(p.semis); vm.applyFxPreset(1, p.name, p.fx) }) }
                    st.userPresets[1].orEmpty().forEach { p -> presetRow.addView(chip("★" + p.name, if (p.name == sel) ACCENT else 0x33FFFFFF) { vm.applyUserPreset(p) }) }
                }
                else -> {
                    cardPresets.forEach { (n, fx) -> presetRow.addView(chip(n, if (n == sel) ACCENT else 0x33FFFFFF) { vm.applyFxPreset(2, n, fx) }) }
                    st.userPresets[2].orEmpty().forEach { p -> presetRow.addView(chip("★" + p.name, if (p.name == sel) ACCENT else 0x33FFFFFF) { vm.applyUserPreset(p) }) }
                }
            }
        }
    }

    private fun statText(): String {
        val am = getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val avail = mi.availMem / (1 shl 20); val total = mi.totalMem / (1 shl 20)
        val bat = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val ss = if (MicInjector.liveOn) MicInjector.sysStat() else null
        val cpu = ss?.cpu?.takeIf { it >= 0 }?.let { "%.0f%%".format(it) } ?: "—"
        val temp = ss?.temp?.takeIf { it > 0 }?.let { "%.0f℃".format(it) } ?: if (bat > 0) "电池%.0f℃".format(bat / 10f) else "—"
        val rss = ss?.rssMb?.takeIf { it > 0 }?.let { "${it}M" } ?: "—"
        val lowMem = if (mi.lowMemory) " ⚠低内存" else ""
        return "CPU $cpu  温度 $temp\n内存 ${avail}/${total}M 可用$lowMem\n变声进程 $rss  断流 ${ss?.underruns ?: 0}" +
            if (ss == null) "\n（开始变声后显示 CPU 与进程数据）" else ""
    }

    private fun remove() {
        h.removeCallbacksAndMessages(null)
        root?.let { runCatching { wm.removeViewImmediate(it) } }
        root = null; running = false
    }
}
