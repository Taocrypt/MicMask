package com.taocrypt.beacon

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Html
import android.text.method.LinkMovementMethod
import android.widget.TextView

/** 系统原生弹窗；需要自定义样式时直接用 Beacon.pendingNotices() / Beacon.update() 自己画 */
internal object Ui {
    fun openUrl(ctx: Context, url: String) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun showNotices(activity: Activity, list: List<Notice>) {
        if (list.isEmpty() || activity.isFinishing) return
        val n = list.first()
        val b = AlertDialog.Builder(activity).setTitle(n.title).setView(body(activity, n.body))
        val btns = n.buttons.take(2)
        if (btns.isEmpty()) b.setPositiveButton(android.R.string.ok, null)
        btns.getOrNull(0)?.let { x -> b.setPositiveButton(x.text) { _, _ -> Beacon.reportNoticeClick(n, x.text); x.url?.let { openUrl(activity, it) } } }
        btns.getOrNull(1)?.let { x -> b.setNegativeButton(x.text) { _, _ -> Beacon.reportNoticeClick(n, x.text); x.url?.let { openUrl(activity, it) } } }
        b.setOnDismissListener { showNotices(activity, list.drop(1)) }
        b.show()
        Beacon.markNoticeShown(n)
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
