package com.taocrypt.beacon

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** 系统原生弹窗；需要自定义样式时直接用 Beacon.pendingNotices() / Beacon.update() 自己画 */
internal object Ui {
    fun openUrl(ctx: Context, url: String) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun showNotices(activity: Activity, list: List<Notice>) {
        if (list.isEmpty() || activity.isFinishing) return
        val n = list.first()
        if (n.force == "block") return showBlock(activity, n)
        if (n.force == "ack") return showAck(activity, n, list.drop(1))
        val b = AlertDialog.Builder(activity).setTitle(title(n)).setView(content(activity, n.body, n.image))
        val btns = n.buttons.take(2)
        if (btns.isEmpty()) b.setPositiveButton(android.R.string.ok, null)
        btns.getOrNull(0)?.let { x -> b.setPositiveButton(x.text) { _, _ -> Beacon.reportNoticeClick(n, x.text); x.url?.let { openUrl(activity, it) } } }
        btns.getOrNull(1)?.let { x -> b.setNegativeButton(x.text) { _, _ -> Beacon.reportNoticeClick(n, x.text); x.url?.let { openUrl(activity, it) } } }
        b.setOnDismissListener { showNotices(activity, list.drop(1)) }
        b.show()
        Beacon.markNoticeShown(n)
    }

    /** 阻断公告：不能关闭；按钮链接可打开，之后弹窗重新出现；「退出」结束 App */
    private fun showBlock(activity: Activity, n: Notice) {
        val b = AlertDialog.Builder(activity).setTitle(title(n)).setView(content(activity, n.body, n.image)).setCancelable(false)
        n.buttons.firstOrNull { it.url != null }?.let { x -> b.setNegativeButton(x.text) { _, _ -> Beacon.reportNoticeClick(n, x.text); openUrl(activity, x.url!!) } }
        b.setPositiveButton("退出") { _, _ -> activity.finishAffinity() }
        b.setOnDismissListener { if (!activity.isFinishing) activity.window.decorView.post { showBlock(activity, n) } }
        b.show().setCanceledOnTouchOutside(false)
        Beacon.markNoticeShown(n)
    }

    /** 强制阅读：不能关闭，倒计时结束后点「我已阅读」才消失 */
    private fun showAck(activity: Activity, n: Notice, rest: List<Notice>) {
        val ok = n.buttons.firstOrNull()?.text ?: "我已阅读"
        val b = AlertDialog.Builder(activity).setTitle(title(n)).setView(content(activity, n.body, n.image)).setCancelable(false)
            .setPositiveButton(ok) { _, _ ->
                Beacon.markNoticeAcked(n)
                n.buttons.firstOrNull()?.let { x -> Beacon.reportNoticeClick(n, x.text); x.url?.let { openUrl(activity, it) } }
            }
        b.setOnDismissListener { showNotices(activity, rest) }
        val d = b.show()
        d.setCanceledOnTouchOutside(false)
        Beacon.markNoticeShown(n)
        if (n.ackSeconds > 0) {
            val btn = d.getButton(AlertDialog.BUTTON_POSITIVE)
            btn.isEnabled = false
            var left = n.ackSeconds
            val tick = object : Runnable {
                override fun run() {
                    if (!d.isShowing) return
                    if (left <= 0) { btn.text = ok; btn.isEnabled = true; return }
                    btn.text = "$ok（$left）"; left--; btn.postDelayed(this, 1000)
                }
            }
            tick.run()
        }
    }

    /** 轮播图弹窗：显示弹窗内容，按钮（按钮文案，默认「前往」）跳转链接 */
    fun showBannerPopup(activity: Activity, b: Banner) {
        if (activity.isFinishing) return
        val d = AlertDialog.Builder(activity).setTitle(b.title.ifBlank { null }).setView(content(activity, b.popup, null))
        if (b.url != null) {
            d.setPositiveButton(b.button.ifBlank { "前往" }) { _, _ -> openUrl(activity, b.url) }
            d.setNegativeButton("关闭", null)
        } else d.setPositiveButton(android.R.string.ok, null)
        d.show()
    }

    private val KIND = mapOf("important" to ("重要公告" to 0xFF2F6FEB.toInt()), "risk" to ("风险提醒" to 0xFFD1242F.toInt()), "event" to ("活动通知" to 0xFFBF8700.toInt()))

    /** 非普通公告在标题前加彩色类型标签 */
    private fun title(n: Notice): CharSequence {
        val k = KIND[n.kind] ?: return n.title
        return SpannableStringBuilder().apply {
            append("【${k.first}】", ForegroundColorSpan(k.second), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(n.title)
        }
    }

    /** 头图（可空，异步加载，失败则隐藏）+ 正文 */
    private fun content(ctx: Context, md: String, image: String?): View {
        val text = body(ctx, md)
        if (image.isNullOrBlank()) return text
        val pad = (20 * ctx.resources.displayMetrics.density).toInt()
        val iv = ImageView(ctx).apply {
            adjustViewBounds = true; scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE
            setPadding(pad, pad / 2, pad, 0); setBackgroundColor(Color.TRANSPARENT)
        }
        Images.load(image, ctx.resources.displayMetrics.widthPixels) { bmp -> if (bmp != null) { iv.setImageBitmap(bmp); iv.visibility = View.VISIBLE } }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(iv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(text)
        }
    }

    fun showUpdate(activity: Activity, u: Update, onSkip: () -> Unit) {
        if (activity.isFinishing) return
        val title = "发现新版本 ${u.versionName}" + (u.size?.let { "（%.1f MB）".format(it / 1048576.0) } ?: "")
        val b = AlertDialog.Builder(activity).setTitle(title).setView(body(activity, u.changelog.ifBlank { " " })).setCancelable(!u.force)
        val mirrors = u.mirrors
        val go = { m: Mirror -> Beacon.track("\$update_click", mapOf("to" to u.versionCode, "mirror" to m.name)); openUrl(activity, m.url) }
        if (mirrors.size <= 1) {
            mirrors.firstOrNull()?.let { m -> b.setPositiveButton("下载") { _, _ -> go(m) } }
        } else {
            b.setPositiveButton("下载") { _, _ ->
                AlertDialog.Builder(activity).setTitle("选择下载地址")
                    .setItems(mirrors.map { it.name }.toTypedArray()) { _, i -> go(mirrors[i]) }
                    .setCancelable(!u.force).show()
            }
        }
        if (!u.force) {
            b.setNegativeButton("稍后", null)
            b.setNeutralButton("跳过此版本") { _, _ -> onSkip() }
        }
        val d = b.show()
        if (u.force) d.setCanceledOnTouchOutside(false)
    }

    /** 极简 Markdown：段落、换行、- 列表、**粗体**、[文字](链接) */
    private fun body(ctx: Context, md: String): TextView {
        val esc = md.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val html = esc.lines().joinToString("<br>") { line ->
            val l = if (line.trimStart().startsWith("- ")) "• " + line.trimStart().removePrefix("- ") else line
            l.replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
                .replace(Regex("\\[(.+?)]\\((https?://[^)\\s]+)\\)"), "<a href=\"$2\">$1</a>")
        }
        val pad = (20 * ctx.resources.displayMetrics.density).toInt()
        return TextView(ctx).apply {
            text = if (android.os.Build.VERSION.SDK_INT >= 24) Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY) else @Suppress("DEPRECATION") Html.fromHtml(html)
            movementMethod = LinkMovementMethod.getInstance()
            setPadding(pad, pad / 2, pad, 0)
            textSize = 15f
        }
    }
}
