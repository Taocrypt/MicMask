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
 * 性能（CPU/温度/内存/进程/断流）· 三模式切换 · 当前模式预设 · 启动/停止 · 耳返；单击悬浮球展开/收起，双击打开 App，长按关闭悬浮窗。
 * 操作经 MainViewModel.current（App 进程在就能用；Activity 被回收时提示打开 App）。
 */
class LiveFloatService : Service() {
    companion object {
        private const val CH = "micmask_live_float"; private const val NID = 0xA772
        @Volatile var running = false; private set
        fun toggle(ctx: Context, on: Boolean) {
            val i = Intent(ctx, LiveFloatService::class.java).setAction(if (on) "show" else "hide")
            runCatching { ctx.startService(i) }.onFailure { L.w("LiveFloatService：${it.message}") }
        }
        private val MODES get() = listOf("AI", tr("普通"), tr("声卡"))
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
    /** 普通模式（无 Shizuku）录播按钮：按住录音，松手转换并外放，上滑取消 */
    private lateinit var recBtn: TextView
    private lateinit var playBtn: TextView
    private lateinit var recRow: LinearLayout
    private lateinit var sfxRow: LinearLayout
    private var lastSfxKey = ""
    private var lastPresetKey = ""
    private var lastStatT = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        I18n.init(applicationContext)
        if (intent?.action == "hide") { Prefs.main(applicationContext).edit().putBoolean("lf_on", false).apply(); remove(); stopSelf(); return START_NOT_STICKY }
        // 不单独发通知：悬浮窗可见时进程本身算前台可感知；变声时由 LiveService 的常驻通知提供「开启悬浮窗」
        runCatching { getSystemService(NotificationManager::class.java).deleteNotificationChannel(CH) }
        if (root == null) {
            if (!FloatingPlayService.canDraw(this)) { stopSelf(); return START_NOT_STICKY }
            add()
        }
        running = true
        LiveService.refresh(applicationContext)
        return START_STICKY
    }

    override fun onDestroy() { Prefs.main(applicationContext).unregisterOnSharedPreferenceChangeListener(prefL); remove(); running = false; super.onDestroy() }

    private fun foreground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, tr("变声悬浮窗"), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val hide = PendingIntent.getService(this, 2, Intent(this, LiveFloatService::class.java).setAction("hide"), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CH).setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(tr("变声悬浮窗")).setContentText(tr("点小球展开：切换模式、预设、看性能"))
            .addAction(Notification.Action.Builder(null, tr("关闭悬浮窗"), hide).build()).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(NID, n)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val R1 = 6
    private val R2 = 16
    private fun round(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    private fun add() {
        wm = getSystemService(WindowManager::class.java)
        val p = Prefs.main(applicationContext)
        lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // SPLIT_TOUCH：别的 App 里正按住按钮（如长按微信录音）时，第二根手指也能点到悬浮窗
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = p.getInt("lf_x", dp(8)); y = p.getInt("lf_y", dp(200)) }

        dockRight = p.getBoolean("lf_right", false); homeY = lp.y
        ballDp = p.getInt("lf_ball_dp", 45).coerceIn(32, 80); ballAlpha = p.getFloat("lf_ball_alpha", 0.9f).coerceIn(0.2f, 1f)
        ps = p.getFloat("lf_panel_scale", 0.85f).coerceIn(0.7f, 1.5f); panelAlpha = p.getFloat("lf_panel_alpha", 1f).coerceIn(0.3f, 1f)
        lastSfxKey = ""; lastPresetKey = ""; 
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
        stat = TextView(this).apply { setTextColor(0xFFAAAAAA.toInt()); textSize = 10f * ps; typeface = Typeface.MONOSPACE; text = tr("读取中…") }
        modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        recBtn = recButton()
        playBtn = playButton()
        recRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; visibility = View.GONE
            addView(recBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { topMargin = sdp(8) })
            addView(playBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = sdp(8); marginStart = sdp(6) })
        }
        sfxRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // 底栏：左下性能信息（可换行，占满剩余宽度），右下缩放工具 A− / A+ / ⤡
        // 启动/停止按钮已去掉：点正在运行的模式按钮即停止；「打开 App」「关闭」改为双击 / 长按悬浮球
        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.END or Gravity.BOTTOM
            addView(scaleBtn("A−", -0.05f)); addView(scaleBtn("A+", 0.05f)); addView(resizeHandle())
        }
        val bottomWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL
            addView(stat, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(tools, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        relayBottom = null
        val pw = panelW(); val ph = panelH()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(modeRow)
            addView(recRow)
            addView(space(8)); addView(HorizontalScrollView(this@LiveFloatService).apply { isHorizontalScrollBarEnabled = false; addView(presetRow) })
            addView(space(8)); addView(TextView(this@LiveFloatService).apply { text = tr("音效包"); setTextColor(0xFF999999.toInt()); textSize = 11f * ps })
            addView(space(2)); addView(sfxRow)
        }
        bodyScroll = android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false; isFillViewport = false; addView(body) }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            background = round(0xE61C1C1E.toInt(), 10); setPadding(sdp(12), sdp(10), sdp(12), sdp(10)); alpha = panelAlpha
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

    private var scaleToast: android.widget.Toast? = null
    /** 内容缩放：文字/按钮整体变大变小（0.7–1.5），写配置后自动重建并保持展开 */
    private fun scaleBtn(t: String, step: Float) = TextView(this).apply {
        // 纯文字图标，无按钮底：省位置，和底栏「启动/停止」同一行贴右下角
        text = t; setTextColor(0xCCFFFFFF.toInt()); textSize = 13f * ps; gravity = Gravity.CENTER; includeFontPadding = false
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding(sdp(5), sdp(6), sdp(5), sdp(6))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener {
            // 每次 5%，点击时短暂提示当前比例
            val n = (Math.round((ps + step) * 20) / 20f).coerceIn(0.7f, 1.5f)
            if (n != ps) Prefs.main(applicationContext).edit().putFloat("lf_panel_scale", n).apply()
            scaleToast?.cancel()
            scaleToast = android.widget.Toast.makeText(applicationContext,
                tr("内容缩放 {0}%", Math.round(n * 100)) + (if (n == ps) (if (step > 0) tr("（已最大）") else tr("（已最小）")) else ""), android.widget.Toast.LENGTH_SHORT).also { it.show() }
        }
    }

    /** 「⤡」长按后拖动：横向改宽、纵向改高，宽高比例自由；双击恢复默认 */
    private fun resizeHandle() = object : View(this) {
        // 自绘双向斜箭头：字体里的「⤡」基线/大小和 A−/A+ 不一致，永远对不齐；这里按 A 的字高居中画
        val tp = android.graphics.Paint().apply { textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 13f * ps, resources.displayMetrics) }
        val fm = tp.fontMetrics
        val capH = android.graphics.Rect().also { tp.getTextBounds("A", 0, 1, it) }.height().toFloat()
        val padV = sdp(6); val padH = sdp(5)
        val pen = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCCFFFFFF.toInt(); style = android.graphics.Paint.Style.STROKE
            strokeWidth = capH * 0.13f; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
        }
        override fun onMeasure(wms: Int, hms: Int) {
            setMeasuredDimension((capH + padH * 2).toInt(), (fm.descent - fm.ascent + padV * 2).toInt())
        }
        override fun onDraw(c: android.graphics.Canvas) {
            val cy = padV - fm.ascent - capH / 2; val cx = width / 2f; val r = capH / 2
            val a = r * 0.55f
            c.drawLine(cx - r, cy - r, cx + r, cy + r, pen)
            c.drawLine(cx - r, cy - r, cx - r + a, cy - r, pen); c.drawLine(cx - r, cy - r, cx - r, cy - r + a, pen)
            c.drawLine(cx + r, cy + r, cx + r - a, cy + r, pen); c.drawLine(cx + r, cy + r, cx + r, cy + r - a, pen)
        }
    }.apply {
        contentDescription = tr("调整窗口大小")
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = sdp(2) }
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

    /** 一级按钮（模式/启动停止/耳返）小圆角 R1，二级按钮（预设/音效等）胶囊 R2 */
    private fun chip(text: String, bg: Int, r: Int = R2, onLong: (() -> Unit)? = null, onClick: () -> Unit) = TextView(this).apply {
        if (onLong != null) setOnLongClickListener { onLong(); h.postDelayed({ refresh(true) }, 150); true }
        this.text = text; setTextColor(Color.WHITE); textSize = 13f * ps; gravity = Gravity.CENTER
        background = round(bg, r); setPadding(sdp(12), sdp(6), sdp(12), sdp(6))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = sdp(6) }
        setOnClickListener { onClick(); h.postDelayed({ refresh(true) }, 150) }
    }

    private fun attachDrag(v: View) {
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        // 单击展开/收起（等双击超时才执行）；双击打开 App；长按关闭悬浮窗
        var longFired = false; var lastUp = 0L
        val dbl = android.view.ViewConfiguration.getDoubleTapTimeout().toLong()
        val singleRun = Runnable { setExpanded(!expanded) }
        val longRun = Runnable {
            longFired = true
            runCatching { v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) }
            h.post { vm()?.setLiveFloat(false) ?: toggle(applicationContext, false) }
        }
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { cancelHide(); if (peeked) unpeek(); dockAnim?.cancel(); sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false
                    longFired = false; h.postDelayed(longRun, android.view.ViewConfiguration.getLongPressTimeout().toLong()); true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx; val dy = e.rawY - sy
                    if (!moved && abs(dx) + abs(dy) > dp(6)) { moved = true; h.removeCallbacks(longRun) }
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
                    h.removeCallbacks(longRun)
                    if (longFired) { /* 长按已关闭悬浮窗 */ }
                    else if (!moved) {
                        if (e.action == MotionEvent.ACTION_UP) {
                            val t = e.eventTime
                            if (t - lastUp <= dbl) { h.removeCallbacks(singleRun); lastUp = 0L; openApp() }
                            else { lastUp = t; h.postDelayed(singleRun, dbl) }
                        }
                    }
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
    private var lastClip = false
    private val fast = object : Runnable { override fun run() {
        val st = vm()?.state?.value
        val ai = (st?.liveOn == true) && st.liveMode == 0
        outVoiced = ai && (MicInjector.outState()?.let { it.first in 0..250 } ?: false)
        paintRing(st?.liveOn == true)
        val clip = expanded && (st?.recording == true || st?.clipBusy == true || st?.qqPlaying == true)
        if (clip || clip != lastClip) paintRec(st)
        lastClip = clip
        h.postDelayed(this, if (ai) 120 else if (clip) 200 else 600)
    } }
    private fun paintRing(on: Boolean) = ring.setStroke(dp(3), when { outVoiced -> RING_BUSY; on -> ACCENT; else -> RING_OFF })

    private fun vm(): MainViewModel? = MainViewModel.current

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    private val loop = object : Runnable { override fun run() { refresh(false); h.postDelayed(this, 1000) } }
    private fun tick() { h.post(loop); h.post(fast) }

    private fun refresh(force: Boolean) {
        if (root == null) return
        val vm = vm()
        val st = vm?.state?.value
        val on = st?.liveOn == true
        // 普通模式（无 Shizuku、未在变声）：悬浮窗换成录播 UI —— 模式只有 AI/普通，按住录音松手转换外放
        val speaker = st?.speakerMode == true && !on
        val mode = (st?.liveMode ?: 0).let { if (speaker) minOf(it, 1) else it }
        paintRing(on)
        if (!expanded) return
        // 性能：每秒一次
        val now = System.currentTimeMillis()
        if (force || now - lastStatT >= 900) { lastStatT = now; stat.text = statText() }
        // 模式按钮
        modeRow.removeAllViews()
        recRow.visibility = if (speaker) View.VISIBLE else View.GONE
        if (speaker) {
            MODES.take(2).forEachIndexed { i, n ->
                modeRow.addView(chip(n, if (mode == i) ACCENT else 0x33FFFFFF, R1) { if (vm == null) openApp() else vm.pickMode(i) })
            }
            paintRec(st)
        } else MODES.forEachIndexed { i, n ->
            // 声卡依赖 Shizuku 注入：未授权（外放模式）时不显示
            if (i == 2 && !runCatching { com.taocrypt.micmask.inject.MicInjector.shizukuGranted() }.getOrDefault(false)) return@forEachIndexed
            val sel = st != null && mode == i
            modeRow.addView(chip(if (on && sel) "■ $n" else n, if (on && sel) ACCENT else if (sel) 0x553482FF else 0x33FFFFFF, R1) {
                if (vm == null) openApp() else vm.startMode(i)
            })
        }
        if (!speaker) {
            // 耳返（放在声卡后面）：只写「耳返」，颜色标识开关；戴耳机用，外放会啸叫
            val mon = st?.liveMonitor == true
            modeRow.addView(chip(tr("耳返"), if (mon) ACCENT else 0x33FFFFFF, R1) { if (vm == null) openApp() else vm.setLiveMonitor(!mon) })
            // 降噪（通话录音源，带回声消除/降噪）：变声中切换会自动重启一次
            val aec = st?.liveAec == true
            modeRow.addView(chip(tr("降噪"), if (aec) ACCENT else 0x33FFFFFF, R1) { if (vm == null) openApp() else vm.toggleLiveAecAnywhere() })
        }
        // 语音包
        val sk = "${st?.sfxPlaying}|${st?.sounds?.size}|${st?.sfxLoop}|${st?.packsOff}"
        if (force || sk != lastSfxKey) {
            lastSfxKey = sk
            sfxRow.removeAllViews()
            if (vm != null && st != null) {
                val shown = st.sounds.filter { it.pack !in st.packsOff }
                if (st.sounds.isNotEmpty() && shown.isEmpty()) sfxRow.addView(chip(tr("音效包都已关闭，去 App 开启"), 0x33FFFFFF) { openApp() })
                if (st.sounds.isEmpty()) sfxRow.addView(chip(tr("去 App 导入素材"), 0x33FFFFFF) { openApp() })
                if (st.sfxPlaying != null) sfxRow.addView(LinearLayout(this).apply { setPadding(0, sdp(2), 0, sdp(2)); addView(chip(tr("■ 停止音效"), 0xFFE5484D.toInt()) { vm.stopSound() }) })
                // 按分组展示：每组一个小标题 + 一行横向滚动
                shown.groupBy { it.pack }.forEach { (pack, list) ->
                    val title = when (pack) { SoundBoard.PACK_FX -> tr("预设音效"); SoundBoard.PACK_LOCAL -> tr("本地音频"); else -> pack }
                    sfxRow.addView(TextView(this).apply { text = "▍$title"; setTextColor(0xFFBBBBBB.toInt()); textSize = 10.5f * ps; setPadding(0, sdp(4), 0, sdp(3)) })
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    list.forEach { so -> row.addView(chip((if (so.id == st.sfxPlaying) "▶ " else "") + so.name,
                        if (so.id == st.sfxPlaying) ACCENT else 0x33FFFFFF, onLong = { vm.playSoundSpeaker(so.id) }) { vm.playSound(so.id) }) }
                    sfxRow.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
                }
            }
        }
        // 预设：仅在模式/选中变化时重建
        val sel = when (mode) { 2 -> st?.fxCardPreset; 1 -> st?.fxPreset; else -> st?.liveVoice }.orEmpty()
        val key = "$mode|$sel|${st?.userPresets?.get(mode)?.size}|${st?.liveVoices?.size}"
        if (force || key != lastPresetKey) {
            // 普通模式：切换音色/预设后，用保留的原声按新参数重新转换，不用重录
            if (lastPresetKey.isNotEmpty() && key != lastPresetKey && st?.speakerMode == true && mode <= 1) vm?.clipReconvertIfStale(mode)
            lastPresetKey = key
            presetRow.removeAllViews()
            if (vm == null || st == null) { presetRow.addView(chip(tr("先打开 App"), 0x33FFFFFF) { openApp() }); return }
            when (mode) {
                0 -> st.liveVoices.forEach { v -> presetRow.addView(chip(vm.voiceLabel(v), if (v == sel) ACCENT else 0x33FFFFFF) { vm.setLiveVoice(v) }) }
                1 -> {
                    pitchPresets.forEach { p -> presetRow.addView(chip(tr(p.name), if (p.name == sel) ACCENT else 0x33FFFFFF) { vm.setPitchSemis(p.semis); vm.applyFxPreset(1, p.name, p.fx) }) }
                    st.userPresets[1].orEmpty().forEach { p -> presetRow.addView(chip("★" + p.name, if (p.name == sel) ACCENT else 0x33FFFFFF) { vm.applyUserPreset(p) }) }
                }
                else -> {
                    cardPresets.forEach { (n, fx) -> presetRow.addView(chip(tr(n), if (n == sel) ACCENT else 0x33FFFFFF) { vm.applyFxPreset(2, n, fx) }) }
                    st.userPresets[2].orEmpty().forEach { p -> presetRow.addView(chip("★" + p.name, if (p.name == sel) ACCENT else 0x33FFFFFF) { vm.applyUserPreset(p) }) }
                }
            }
        }
    }

    private fun paintRec(st: UiState?) {
        if (!::recBtn.isInitialized) return
        val rec = st?.recording == true; val busy = st?.clipBusy == true; val playing = st?.qqPlaying == true
        recBtn.text = when {
            st == null -> tr("先打开 App")
            busy -> "${st.clipStage} ${Math.round(st.clipProgress * 100)}%"
            recCancel -> tr("松开取消")
            rec -> tr("录音中 %d:%02d · 松手转换，上滑取消").format(st.recElapsedMs / 60000, st.recElapsedMs / 1000 % 60)
            else -> tr("按住 录一句")
        }
        recBtn.background = round(when { recCancel -> 0xFFE5484D.toInt(); rec -> ACCENT; busy -> 0x22FFFFFF; else -> 0x33FFFFFF }, R2)
        if (::playBtn.isInitialized) {
            val has = st?.qqPendingName != null && !busy && !rec
            playBtn.text = if (playing) "■" else "▶"
            playBtn.alpha = if (has || playing) 1f else 0.35f
            playBtn.background = round(if (playing) ACCENT else 0x33FFFFFF, R2)
        }
    }

    private var recCancel = false
    /** 普通模式悬浮窗：手动播放/停止最近一次转换结果 */
    private fun playButton() = TextView(this).apply {
        setTextColor(Color.WHITE); textSize = 14f * ps; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
        setPadding(sdp(16), sdp(12), sdp(16), sdp(12)); text = "▶"
        setOnClickListener {
            cancelHide()
            val vm = vm(); val st = vm?.state?.value
            when {
                vm == null || st == null -> openApp()
                st.qqPlaying -> vm.stopQqLoop()
                st.clipBusy || st.recording -> {}
                st.qqPendingName == null -> android.widget.Toast.makeText(applicationContext, tr("还没有录音，先按住录一句"), android.widget.Toast.LENGTH_SHORT).show()
                else -> vm.clipPlay(st.liveMode.coerceAtMost(1))
            }
            h.postDelayed({ paintRec(vm()?.state?.value) }, 80); scheduleHide()
        }
    }
    private fun recButton() = TextView(this).apply {
        setTextColor(Color.WHITE); textSize = 14f * ps; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
        setPadding(sdp(12), sdp(12), sdp(12), sdp(12))
        var y0 = 0f; var active = false
        setOnTouchListener { v, e ->
            val vm = vm(); val st = vm?.state?.value
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelHide(); active = false; recCancel = false
                    when {
                        vm == null || st == null -> openApp()
                        st.clipBusy -> {}
                        !vm.hasMicPermission() -> { android.widget.Toast.makeText(applicationContext, tr("需要麦克风权限才能录音"), android.widget.Toast.LENGTH_SHORT).show(); openApp() }
                        else -> { y0 = e.rawY; active = true; v.parent?.requestDisallowInterceptTouchEvent(true)
                            runCatching { v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY) }; vm.clipStart() }
                    }
                    h.postDelayed({ paintRec(vm()?.state?.value) }, 80)
                }
                MotionEvent.ACTION_MOVE -> if (active) { val c = y0 - e.rawY > dp(72); if (c != recCancel) { recCancel = c; paintRec(st) } }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (active && vm != null) {
                        val cur = st?.liveMode?.coerceAtMost(1) ?: 0
                        if (recCancel || e.actionMasked == MotionEvent.ACTION_CANCEL) vm.clipCancel() else vm.clipStop(cur, autoPlay = false)
                    }
                    active = false; recCancel = false
                    h.postDelayed({ paintRec(vm()?.state?.value) }, 80); scheduleHide()
                }
            }
            true
        }
    }

    private fun statText(): String {
        val am = getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val avail = mi.availMem / (1 shl 20); val total = mi.totalMem / (1 shl 20)
        val bat = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val ss = if (MicInjector.liveOn) MicInjector.sysStat() else null
        val cpu = ss?.cpu?.takeIf { it >= 0 }?.let { "%.0f%%".format(it) } ?: "—"
        val temp = ss?.temp?.takeIf { it > 0 }?.let { "%.0f℃".format(it) } ?: if (bat > 0) tr("电池%.0f℃").format(bat / 10f) else "—"
        val rss = ss?.rssMb?.takeIf { it > 0 }?.let { "${it}M" } ?: "—"
        // 一行显示：段内不断行（不换行空格），宽度不够时才在段与段之间换行
        val segs = mutableListOf("CPU $cpu", temp, tr("内存 %.1f/%.1fG").format(avail / 1024f, total / 1024f) + if (mi.lowMemory) " ⚠" else "")
        if (ss != null) { segs += tr("进程 {0}", rss); segs += tr("断流 {0}", ss.underruns) } else segs += tr("未开始变声")
        // 每段内部用不换行空格 + 词连接符（U+2060）粘住，只在段与段之间换行，避免「进/程624M」这种拆字
        return segs.joinToString("  ") { seg -> seg.replace(' ', '\u00A0').toList().joinToString("\u2060") }
    }

    private fun remove() {
        h.removeCallbacksAndMessages(null)
        root?.let { runCatching { wm.removeViewImmediate(it) } }
        root = null; running = false
        LiveService.refresh(applicationContext)
    }
}
