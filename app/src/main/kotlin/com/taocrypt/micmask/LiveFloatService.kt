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
import android.os.SystemClock
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
        private const val RING_OFF = 0x66FFFFFF
        val LOOK_KEYS = setOf("lf_ball_dp", "lf_ball_alpha", "lf_panel_scale", "lf_panel_alpha", "lf_autohide", "lf_panel_w", "lf_panel_h")
        private const val RING_BUSY = 0xFFFF9F0A.toInt()
    }

    private val h = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var root: LinearLayout? = null
    private lateinit var lp: WindowManager.LayoutParams
    private var expanded = false
    private lateinit var ball: android.widget.FrameLayout
    private lateinit var ring: GradientDrawable
    private var dockRight = false
    private var homeY = 0
    /** 外观（设置页可调）：悬浮球直径 dp、悬浮球透明度、控制窗缩放、控制窗透明度 */
    private var ballDp = 45; private var ballAlpha = 0.9f; private var ps = 0.85f; private var panelAlpha = 1f
    private fun sdp(v: Int) = (v * ps * resources.displayMetrics.density).toInt()
    private val prefL = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
        if (k != null && k in LOOK_KEYS) h.post { if (root != null) { val ex = expanded; remove(); add(); running = true; if (ex) setExpanded(true) } }
    }
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

    override fun onDestroy() { Prefs.main(applicationContext).unregisterOnSharedPreferenceChangeListener(prefL); remove(); running = false; super.onDestroy() }

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

        dockRight = p.getBoolean("lf_right", false); homeY = lp.y
        ballDp = p.getInt("lf_ball_dp", 45).coerceIn(32, 80); ballAlpha = p.getFloat("lf_ball_alpha", 0.9f).coerceIn(0.2f, 1f)
        ps = p.getFloat("lf_panel_scale", 0.85f).coerceIn(0.7f, 1.5f); panelAlpha = p.getFloat("lf_panel_alpha", 1f).coerceIn(0.3f, 1f)
        lastSfxKey = ""; lastPresetKey = ""; lastStopOn = null
        p.unregisterOnSharedPreferenceChangeListener(prefL); p.registerOnSharedPreferenceChangeListener(prefL)
        // 悬浮球 = App 图标 + 状态色环（灰=未开启，蓝=变声中，橙=AI 还在输出）
        ring = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x00000000); setStroke(dp(3), RING_OFF) }
        ball = android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(ballDp), dp(ballDp)); alpha = ballAlpha
            background = ring; setPadding(dp(3), dp(3), dp(3), dp(3)); elevation = dp(6).toFloat()
            addView(android.widget.ImageView(this@LiveFloatService).apply {
                setImageResource(R.drawable.float_logo); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            }, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        stat = TextView(this).apply { setTextColor(0xFFDDDDDD.toInt()); textSize = 11f * ps; typeface = Typeface.MONOSPACE; text = "读取中…" }
        modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sfxRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bottom = LinearLayout(this).apply {
            // 不按文字基线对齐（否则右侧工具按钮会被基线拉低），整行垂直居中
            orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL
            stopChip = chip("停止", 0xFFE5484D.toInt()) {
                val v = vm(); val on = (v?.state?.value?.liveOn == true) || MicInjector.liveOn
                if (on) v?.stopAll() ?: run { MicInjector.stopLive(); LiveService.stop(this@LiveFloatService) }
                else if (v == null) openApp() else v.startMode(v.state.value.liveMode)
            }
            addView(stopChip)
            addView(chip("打开 App", 0x33FFFFFF) { openApp() })
            // 关闭：直接关掉悬浮窗（同设置里关开关；App 不在时直接停服务）
            addView(chip("关闭", 0x33FFFFFF) { h.post { vm()?.setLiveFloat(false) ?: toggle(applicationContext, false) } })
        }
        // 右下角工具：内容缩放 A− / A+，窗口大小 ⤡（宽窗时与按钮同一行，窄窗自动换到下一行右对齐）
        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(scaleBtn("A−", -0.1f)); addView(scaleBtn("A+", 0.1f)); addView(resizeHandle())
        }
        val bottomWrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(bottom) }
        fun layBottom(w: Int) {
            // 拖动缩放中不挪工具条：移走正在接收触摸的「⤡」会打断手势（表现为一卡一卡）
            if (resizing) return
            (tools.parent as? ViewGroup)?.removeView(tools)
            bottom.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED); tools.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            if (bottom.measuredWidth + tools.measuredWidth <= w) bottom.addView(tools, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            else bottomWrap.addView(tools, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
        relayBottom = { bottomWrap.post { layBottom(bottomWrap.width.takeIf { it > 0 } ?: (panelW() - sdp(24))) } }
        bottomWrap.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or_, _ -> if (r - l != or_ - ol && r > l) bottomWrap.post { layBottom(r - l) } }
        layBottom(panelW() - sdp(24))
        val pw = panelW(); val ph = panelH()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(stat)
            addView(space(8)); addView(modeRow)
            addView(space(8)); addView(HorizontalScrollView(this@LiveFloatService).apply { isHorizontalScrollBarEnabled = false; addView(presetRow) })
            addView(space(8)); addView(TextView(this@LiveFloatService).apply { text = "语音包"; setTextColor(0xFF999999.toInt()); textSize = 11f * ps })
            addView(space(2)); addView(sfxRow)
        }
        bodyScroll = android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false; isFillViewport = false; addView(body) }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            background = round(0xE61C1C1E.toInt(), 18); setPadding(sdp(12), sdp(10), sdp(12), sdp(10)); alpha = panelAlpha
            layoutParams = LinearLayout.LayoutParams(pw, ph).apply { topMargin = dp(6) }
            addView(bodyScroll, scrollLp(ph))
            addView(space(8)); addView(bottomWrap)
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = if (dockRight) Gravity.END else Gravity.START; addView(ball); addView(panel)
        }
        attachDrag(ball)
        wm.addView(root, lp)
        peeked = false
        root?.post { place(false) }
        tick(); scheduleHide()
    }

    private fun space(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, sdp(h)) }

    /** 正在拖「⤡」缩放：期间不重排底栏；松手后 relayBottom 重排一次 */
    private var resizing = false
    private var relayBottom: (() -> Unit)? = null
    /** 右下角缩放柄：长按后拖动缩放控制窗，松手保存 */
    private var bodyScroll: android.widget.ScrollView? = null
    /** 控制窗宽高（px）：宽默认 268dp，高 0 = 随内容 */
    private fun panelW() = Prefs.main(applicationContext).getInt("lf_panel_w", 268).let { minOf(dp(it), screen().first - dp(8)) }
    private fun panelH() = Prefs.main(applicationContext).getInt("lf_panel_h", 0).let { if (it <= 0) ViewGroup.LayoutParams.WRAP_CONTENT else minOf(dp(it), screen().second - dp(80)) }
    private fun scrollLp(ph: Int) = if (ph == ViewGroup.LayoutParams.WRAP_CONTENT) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(-1, 0, 1f)

    /** 内容缩放：文字/按钮整体变大变小（0.7–1.5），写配置后自动重建并保持展开 */
    private fun scaleBtn(t: String, step: Float) = TextView(this).apply {
        text = t; setTextColor(Color.WHITE); textSize = 13f * ps; gravity = Gravity.CENTER; includeFontPadding = false
        background = round(0x33FFFFFF, 14); minWidth = sdp(34); minHeight = 0; minimumHeight = 0
        setPadding(sdp(8), sdp(6), sdp(8), sdp(6))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) }
        setOnClickListener {
            val n = (Math.round((ps + step) * 10) / 10f).coerceIn(0.7f, 1.5f)
            if (n != ps) Prefs.main(applicationContext).edit().putFloat("lf_panel_scale", n).apply()
        }
    }

    /** 「⤡」长按后拖动：横向改宽、纵向改高，宽高比例自由；双击恢复默认 */
    private fun resizeHandle() = TextView(this).apply {
        text = "⤡"; setTextColor(0xDDFFFFFF.toInt()); textSize = 15f * ps; gravity = Gravity.CENTER; includeFontPadding = false
        background = round(0x33FFFFFF, 14); minWidth = sdp(34); minHeight = 0; minimumHeight = 0
        setPadding(sdp(8), sdp(5), sdp(8), sdp(5))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) }
        var armed = false; var sx = 0f; var sy = 0f; var w0 = 0; var h0 = 0; var lastTap = 0L
        setOnClickListener {
            val now = SystemClock.uptimeMillis()
            if (now - lastTap < 350) Prefs.main(applicationContext).edit().remove("lf_panel_w").remove("lf_panel_h").apply()
            lastTap = now
        }
        setOnLongClickListener { armed = true; resizing = true; w0 = panel.width; h0 = panel.height
            parent?.requestDisallowInterceptTouchEvent(true)
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); true }
        setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; false }
                MotionEvent.ACTION_MOVE -> if (armed) {
                    val (sw, sh) = screen()
                    val dx = (e.rawX - sx).toInt() * (if (dockRight) -1 else 1)
                    val nw = (w0 + dx).coerceIn(dp(180), sw - dp(8))
                    val nh = (h0 + (e.rawY - sy).toInt()).coerceIn(dp(120), sh - dp(80))
                    val l = panel.layoutParams as LinearLayout.LayoutParams
                    if (l.width != nw || l.height != nh) {
                        l.width = nw; l.height = nh; panel.layoutParams = l
                        bodyScroll?.layoutParams = scrollLp(nh)
                    }
                    true } else false
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (armed) {
                    armed = false; resizing = false; relayBottom?.invoke()
                    val d = resources.displayMetrics.density
                    Prefs.main(applicationContext).edit().putInt("lf_panel_w", (panel.layoutParams.width / d).toInt())
                        .putInt("lf_panel_h", (panel.layoutParams.height / d).toInt()).apply()
                    root?.post { place(true) }
                    true } else false
                else -> false
            }
        }
    }

    private fun chip(text: String, bg: Int, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; setTextColor(Color.WHITE); textSize = 13f * ps; gravity = Gravity.CENTER
        background = round(bg, 14); setPadding(sdp(12), sdp(6), sdp(12), sdp(6))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = sdp(6) }
        setOnClickListener { onClick(); h.postDelayed({ refresh(true) }, 150) }
    }

    private fun attachDrag(v: View) {
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { cancelHide(); if (peeked) unpeek(); dockAnim?.cancel(); sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx; val dy = e.rawY - sy
                    if (!moved && abs(dx) + abs(dy) > dp(6)) moved = true
                    if (moved) {
                        // 展开时整个窗口一起移动，不收起；按整个窗口尺寸夹在屏内
                        val (w, hh) = screen(); val r = root
                        val rw = r?.width?.takeIf { it > 0 } ?: ball.width; val rh = r?.height?.takeIf { it > 0 } ?: ball.height
                        // 只要求悬浮球留在屏内，面板可暂时伸出屏幕；松手贴边后再整体收回屏内
                        val bw = ball.width
                        val minX = if (dockRight) -(rw - bw) else 0; val maxX = if (dockRight) w - rw else w - bw
                        val nx = (ox + dx.toInt()).coerceIn(minX, maxOf(minX, maxX)); val ny = (oy + dy.toInt()).coerceIn(0, maxOf(0, hh - rh))
                        if (nx != lp.x || ny != lp.y) { lp.x = nx; lp.y = ny; r?.let { runCatching { wm.updateViewLayout(it, lp) } } }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!moved) { if (e.action == MotionEvent.ACTION_UP) setExpanded(!expanded) }
                    else {
                        // 贴边：按悬浮球中心吸到最近的左/右边
                        val (w, _) = screen()
                        val rw = root?.width?.takeIf { it > 0 } ?: ball.width
                        val cx = lp.x + if (dockRight) rw - ball.width / 2 else ball.width / 2
                        dockRight = cx > w / 2
                        homeY = lp.y
                        root?.gravity = if (dockRight) Gravity.END else Gravity.START
                        Prefs.main(applicationContext).edit().putBoolean("lf_right", dockRight).putInt("lf_y", homeY).apply()
                        root?.post { place(true) }
                    }
                    scheduleHide()
                    true
                }
                else -> false
            }
        }
    }

    // 收起几秒后半隐藏到屏幕边缘，碰一下恢复
    private var peeked = false
    private val hideRun = Runnable { peek() }
    private fun autoHide() = Prefs.main(applicationContext).getBoolean("lf_autohide", true)
    private fun cancelHide() = h.removeCallbacks(hideRun)
    private fun scheduleHide() { cancelHide(); if (!expanded && autoHide()) h.postDelayed(hideRun, 3000) }
    private fun peek() {
        val r = root ?: return
        if (expanded || peeked) return
        peeked = true
        val (w, _) = screen()
        val bw = ball.width
        val tx = if (dockRight) w - bw / 2 else -bw / 2
        dockAnim?.cancel()
        val x0 = lp.x
        dockAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240; interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { a -> lp.x = (x0 + (tx - x0) * (a.animatedValue as Float)).toInt(); if (root != null) runCatching { wm.updateViewLayout(r, lp) } }
            start()
        }
        ball.animate().alpha(ballAlpha * 0.55f).setDuration(240).start()
    }
    private fun unpeek() {
        peeked = false
        ball.animate().alpha(ballAlpha).setDuration(150).start()
        place(true)
    }

    private fun setExpanded(on: Boolean) {
        expanded = on
        cancelHide(); if (peeked) { peeked = false; ball.alpha = ballAlpha }
        panel.visibility = if (on) View.VISIBLE else View.GONE
        panel.alpha = 0f; if (on) panel.animate().alpha(1f).setDuration(160).start()
        refresh(true)
        root?.post { place(false) }
        scheduleHide()
    }

    /** 屏幕可用尺寸（像素） */
    private fun screen(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.let { it.width() to it.height() }
        else resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    private var dockAnim: android.animation.ValueAnimator? = null
    /** 贴边 + 夹在屏幕内：靠右时整个窗口右对齐（面板往左展开），展开放不下就往上挪，收起回原位 */
    private fun place(animate: Boolean) {
        val r = root ?: return
        val (w, hh) = screen()
        val rw = if (r.width > 0) r.width else ball.width
        val rh = if (r.height > 0) r.height else ball.height
        val tx = if (dockRight) maxOf(0, w - rw) else 0
        val ty = (if (expanded) homeY else homeY).coerceIn(0, maxOf(0, hh - rh))
        dockAnim?.cancel()
        if (!animate) { lp.x = tx; lp.y = ty; runCatching { wm.updateViewLayout(r, lp) }; return }
        val x0 = lp.x; val y0 = lp.y
        dockAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220; interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { a -> val f = a.animatedValue as Float
                lp.x = (x0 + (tx - x0) * f).toInt(); lp.y = (y0 + (ty - y0) * f).toInt()
                if (root != null) runCatching { wm.updateViewLayout(r, lp) } }
            start()
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig); root?.post { place(false) }
    }

    // AI 变声时快速轮询输出状态，给色环上色
    private var outVoiced = false
    private val fast = object : Runnable { override fun run() {
        val st = vm()?.state?.value
        val ai = (st?.liveOn == true) && st.liveMode == 0
        outVoiced = ai && (MicInjector.outState()?.let { it.first in 0..250 } ?: false)
        paintRing(st?.liveOn == true)
        h.postDelayed(this, if (ai) 120 else 600)
    } }
    private fun paintRing(on: Boolean) = ring.setStroke(dp(3), when { outVoiced -> RING_BUSY; on -> ACCENT; else -> RING_OFF })

    private fun vm(): MainViewModel? = MainViewModel.current

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    private val loop = object : Runnable { override fun run() { refresh(false); h.postDelayed(this, 1000) } }
    private fun tick() { h.post(loop); h.post(fast) }

    private var stopChip: TextView? = null
    private var lastStopOn: Boolean? = null
    private fun refresh(force: Boolean) {
        if (root == null) return
        val vm = vm()
        val st = vm?.state?.value
        val on = st?.liveOn == true
        val mode = st?.liveMode ?: 0
        paintRing(on)
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
                if (st.sfxPlaying != null) sfxRow.addView(LinearLayout(this).apply { setPadding(0, sdp(2), 0, sdp(2)); addView(chip("■ 停止音效", 0xFFE5484D.toInt()) { vm.stopSound() }) })
                // 按分组展示：每组一个小标题 + 一行横向滚动
                st.sounds.groupBy { it.pack }.forEach { (pack, list) ->
                    val title = when (pack) { SoundBoard.PACK_FX -> "常用音效"; SoundBoard.PACK_LOCAL -> "本地音频"; else -> pack }
                    sfxRow.addView(TextView(this).apply { text = "▍$title"; setTextColor(0xFFBBBBBB.toInt()); textSize = 10.5f * ps; setPadding(0, sdp(4), 0, sdp(3)) })
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    list.forEach { so -> row.addView(chip((if (so.id == st.sfxPlaying) "▶ " else "") + so.name,
                        if (so.id == st.sfxPlaying) ACCENT else 0x33FFFFFF) { vm.playSound(so.id) }) }
                    sfxRow.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
                }
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
        // 一行显示：段内不断行（不换行空格），宽度不够时才在段与段之间换行
        val segs = mutableListOf("CPU $cpu", temp, "内存 %.1f/%.1fG".format(avail / 1024f, total / 1024f) + if (mi.lowMemory) " ⚠" else "")
        if (ss != null) { segs += "进程 $rss"; segs += "断流 ${ss.underruns}" } else segs += "未开始变声"
        return segs.joinToString("  ") { it.replace(' ', '\u00A0') }
    }

    private fun remove() {
        h.removeCallbacksAndMessages(null)
        root?.let { runCatching { wm.removeViewImmediate(it) } }
        root = null; running = false
    }
}
