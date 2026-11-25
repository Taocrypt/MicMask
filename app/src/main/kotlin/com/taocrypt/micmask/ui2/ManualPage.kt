package com.taocrypt.micmask.ui2

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taocrypt.micmask.BuildConfig
import com.taocrypt.micmask.L
import com.taocrypt.micmask.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/**
 * 设置 → 其他 → 使用教程。
 * 内容来源优先级：filesDir 缓存（上次联网拿到的，且不早于本版本内置）→ assets/manual.md（构建时由 docs/manual.md 复制，见 app/build.gradle.kts copyManual）。
 * 打开页面时后台拉 GitHub main 分支最新手册（直连 → 镜像，复用 Updater），成功就替换显示并写缓存。
 */
object ManualStore {
    const val URL = "https://raw.githubusercontent.com/Taocrypt/MicMask/main/docs/manual.md"
    private fun cache(ctx: Context) = File(ctx.filesDir, "manual.md")
    private fun cacheVer(ctx: Context) = File(ctx.filesDir, "manual.ver")

    /** 本地可立即显示的版本：fromCache=true 表示来自上次联网缓存 */
    fun local(ctx: Context): Pair<String, Boolean> {
        val c = cache(ctx)
        val ver = runCatching { cacheVer(ctx).readText().trim().toInt() }.getOrDefault(0)
        if (c.isFile && ver >= BuildConfig.VERSION_CODE) runCatching { c.readText() }.getOrNull()?.takeIf { valid(it) }?.let { return it to true }
        val bundled = runCatching { ctx.assets.open("manual.md").bufferedReader().use { it.readText() } }.getOrDefault("")
        return bundled to false
    }

    /** 粗校验：是 Markdown 手册而不是镜像返回的错误页 */
    fun valid(t: String) = t.length > 500 && t.trimStart().startsWith("#") && !t.contains("<html", ignoreCase = true)

    suspend fun fetch(ctx: Context): String? {
        val t = com.taocrypt.micmask.update.Updater.fetchGhText(ctx, URL) { valid(it) } ?: return null
        withContext(Dispatchers.IO) {
            runCatching { cache(ctx).writeText(t); cacheVer(ctx).writeText(BuildConfig.VERSION_CODE.toString()) }
                .onFailure { L.w("使用教程：写缓存失败 ${it.message}") }
        }
        return t
    }
}

@Composable
fun ManualPage() {
    val ctx = LocalContext.current
    val first = remember { ManualStore.local(ctx) }
    var text by remember { mutableStateOf(first.first) }
    // 0 正在获取 1 已更新到最新 2 获取失败（显示本地）
    var net by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        val t = runCatching { ManualStore.fetch(ctx.applicationContext) }.getOrNull()
        if (t != null) { if (t != text) text = t; net = 1; L.i("使用教程：已获取最新版（${t.length} 字）") }
        else { net = 2; L.i("使用教程：获取最新版失败，显示${if (first.second) "缓存" else "内置"}版本") }
    }
    val blocks = remember(text) { Md.parse(text) }
    val anchors = remember(blocks) {
        val m = HashMap<String, Int>()
        blocks.forEachIndexed { i, b -> if (b is Md.Block.Heading) m.putIfAbsent(b.anchor, i + 1) }
        m
    }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val onLink: (String) -> Unit = { url ->
        if (url.startsWith("#")) {
            val key = java.net.URLDecoder.decode(url.removePrefix("#"), "UTF-8").lowercase()
            anchors[key]?.let { scope.launch { list.animateScrollToItem(it) } }
        } else openUrl(ctx, url)
    }
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), state = list, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp)) {
        item(key = "status") {
            Text(when (net) {
                0 -> tr("正在获取最新版…")
                1 -> tr("已是最新版")
                else -> if (first.second) tr("网络不通，显示上次获取的版本") else tr("网络不通，显示内置版本")
            }, fontSize = 12.sp, color = summary, modifier = Modifier.padding(bottom = 8.dp))
        }
        if (blocks.isEmpty()) item(key = "empty") { Text(tr("教程内容为空"), color = summary) }
        itemsIndexed(blocks) { _, b -> MdBlock(b, onLink, 0) }
    }
}

// ------------------------------------------------------------------ 渲染

@Composable
private fun mdStyle(size: Int = 15, weight: FontWeight = FontWeight.Normal, color: Color = MiuixTheme.colorScheme.onSurface) =
    TextStyle(fontSize = size.sp, lineHeight = (size * 1.6f).sp, fontWeight = weight, color = color)

@Composable
private fun MdText(s: String, onLink: (String) -> Unit, style: TextStyle, modifier: Modifier = Modifier) {
    val primary = MiuixTheme.colorScheme.primary
    val codeBg = MiuixTheme.colorScheme.surfaceContainerHigh
    val cur by rememberUpdatedState(onLink)   // 换内容后锚点表会变，链接回调总用最新的
    val a = remember(s, primary, codeBg) { Md.inline(s, primary, codeBg) { cur(it) } }
    BasicText(a, modifier, style = style)
}

@Composable
private fun MdBlock(b: Md.Block, onLink: (String) -> Unit, depth: Int) {
    val cs = MiuixTheme.colorScheme
    when (b) {
        is Md.Block.Heading -> {
            val size = when (b.level) { 1 -> 24; 2 -> 20; 3 -> 17; else -> 15 }
            Column(Modifier.padding(top = if (b.level <= 2) 20.dp else 14.dp, bottom = 6.dp)) {
                MdText(b.text, onLink, mdStyle(size, FontWeight.Bold))
                if (b.level <= 2) Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(1.dp).background(cs.dividerLine))
            }
        }
        is Md.Block.Para -> MdText(b.text, onLink, mdStyle(), Modifier.padding(vertical = 4.dp))
        is Md.Block.Hr -> Box(Modifier.padding(vertical = 14.dp).fillMaxWidth().height(1.dp).background(cs.dividerLine))
        is Md.Block.Code -> Box(Modifier.padding(vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(cs.surfaceContainerHigh)
            .horizontalScroll(rememberScrollState()).padding(12.dp)) {
            BasicText(b.text, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp, color = cs.onSurface))
        }
        is Md.Block.Quote -> Row(Modifier.padding(vertical = 6.dp).fillMaxWidth().height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(6.dp)).background(cs.surfaceContainer)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(cs.primary.copy(alpha = 0.6f)))
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                b.blocks.forEach { MdBlock(it, onLink, depth) }
            }
        }
        is Md.Block.ListBlock -> Column(Modifier.padding(vertical = 4.dp)) {
            b.items.forEach { it -> MdListItem(it, onLink, 0) }
        }
        is Md.Block.Table -> MdTable(b, onLink)
    }
}

@Composable
private fun MdListItem(it: Md.Item, onLink: (String) -> Unit, level: Int) {
    Row(Modifier.padding(start = (level * 18).dp, top = 2.dp, bottom = 2.dp)) {
        val bullet = if (it.ordered) "${it.number}." else if (level == 0) "•" else "◦"
        BasicText(bullet, Modifier.width(if (it.ordered) 26.dp else 18.dp), style = mdStyle(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
        MdText(it.text, onLink, mdStyle(), Modifier.weight(1f))
    }
    it.children.forEach { c -> MdListItem(c, onLink, level + 1) }
}

@Composable
private fun MdTable(t: Md.Block.Table, onLink: (String) -> Unit) {
    val cs = MiuixTheme.colorScheme
    val cols = t.header.size
    // 列宽按最长单元格估算（中文算 2），可横向滚动
    val widths = (0 until cols).map { c ->
        val w = (listOf(t.header) + t.rows).maxOf { r -> Md.visualLen(r.getOrElse(c) { "" }) }
        (w * 7.5f + 24f).coerceIn(64f, 260f).dp
    }
    Box(Modifier.padding(vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(cs.surfaceContainer)
        .horizontalScroll(rememberScrollState())) {
        Column {
            (listOf(t.header) + t.rows).forEachIndexed { ri, r ->
                Row(Modifier.height(IntrinsicSize.Min).background(if (ri == 0) cs.surfaceContainerHigh else Color.Transparent)) {
                    for (c in 0 until cols) {
                        MdText(r.getOrElse(c) { "" }, onLink, mdStyle(14, if (ri == 0) FontWeight.Bold else FontWeight.Normal),
                            Modifier.width(widths[c]).padding(horizontal = 10.dp, vertical = 8.dp))
                    }
                }
                if (ri < t.rows.size) Box(Modifier.width(widths.fold(0.dp) { a, w -> a + w }).height(1.dp).background(cs.dividerLine))
            }
        }
    }
}

// ------------------------------------------------------------------ 轻量 Markdown 解析

/** 只覆盖手册用到的 GitHub Markdown 子集：标题、段落、列表（一级嵌套）、代码块、引用、表格、分隔线、行内粗斜体/代码/链接。 */
internal object Md {
    sealed class Block {
        class Heading(val level: Int, val text: String, val anchor: String) : Block()
        class Para(val text: String) : Block()
        class Code(val text: String) : Block()
        class Quote(val blocks: List<Block>) : Block()
        class ListBlock(val items: List<Item>) : Block()
        class Table(val header: List<String>, val rows: List<List<String>>) : Block()
        object Hr : Block()
    }
    class Item(val ordered: Boolean, val number: Int, val text: String, val children: MutableList<Item> = mutableListOf())

    private val heading = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val ul = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val ol = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
    private val hr = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val tableSep = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    /** GitHub 标题锚点：小写，字母数字（含中文）、-、_ 保留，空格变 -，其余去掉 */
    fun slug(s: String): String {
        val plain = s.replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1").replace("`", "").replace("*", "")
        val sb = StringBuilder()
        for (ch in plain.trim().lowercase()) when {
            ch.isLetterOrDigit() || ch == '-' || ch == '_' -> sb.append(ch)
            ch == ' ' -> sb.append('-')
        }
        return sb.toString()
    }

    fun visualLen(s: String) = s.replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1").replace("**", "").replace("`", "")
        .fold(0) { n, ch -> n + if (ch.code > 0x2E80) 2 else 1 }

    private fun cells(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|") && !s.endsWith("\\|")) s = s.dropLast(1)
        val out = ArrayList<String>(); val cur = StringBuilder(); var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length && s[i + 1] == '|') { cur.append('|'); i += 2; continue }
            if (c == '|') { out += cur.toString().trim(); cur.clear() } else cur.append(c)
            i++
        }
        out += cur.toString().trim()
        return out
    }

    fun parse(src: String): List<Block> = parseLines(src.replace("\r\n", "\n").split("\n"))

    private fun parseLines(lines: List<String>): List<Block> {
        val out = ArrayList<Block>()
        var i = 0
        val para = StringBuilder()
        fun flush() { if (para.isNotBlank()) out += Block.Para(para.toString().trim()); para.clear() }
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            when {
                t.isEmpty() -> { flush(); i++ }
                t.startsWith("```") || t.startsWith("~~~") -> {
                    flush()
                    val fence = t.take(3); val code = ArrayList<String>(); i++
                    while (i < lines.size && !lines[i].trim().startsWith(fence)) { code += lines[i]; i++ }
                    i++
                    out += Block.Code(code.joinToString("\n"))
                }
                heading.matches(t) -> {
                    flush()
                    val m = heading.find(t)!!
                    out += Block.Heading(m.groupValues[1].length, m.groupValues[2], slug(m.groupValues[2])); i++
                }
                hr.matches(line) -> { flush(); out += Block.Hr; i++ }
                t.startsWith(">") -> {
                    flush()
                    val q = ArrayList<String>()
                    while (i < lines.size && lines[i].trim().startsWith(">")) { q += lines[i].trim().removePrefix(">").removePrefix(" "); i++ }
                    out += Block.Quote(parseLines(q))
                }
                t.startsWith("|") && i + 1 < lines.size && tableSep.matches(lines[i + 1]) -> {
                    flush()
                    val header = cells(t); i += 2
                    val rows = ArrayList<List<String>>()
                    while (i < lines.size && lines[i].trim().startsWith("|")) { rows += cells(lines[i]); i++ }
                    out += Block.Table(header, rows)
                }
                ul.matches(line) || ol.matches(line) -> {
                    flush()
                    val items = ArrayList<Item>()
                    var baseIndent = -1
                    while (i < lines.size) {
                        val l = lines[i]
                        val mu = ul.find(l); val mo = ol.find(l)
                        if (mu == null && mo == null) {
                            // 列表项的续行（缩进的普通文字）并入上一项
                            if (l.isNotBlank() && l.startsWith("  ") && items.isNotEmpty()) {
                                val last = items.last().children.lastOrNull() ?: items.last()
                                val merged = Item(last.ordered, last.number, last.text + " " + l.trim(), last.children)
                                if (items.last().children.isNotEmpty()) items.last().children[items.last().children.size - 1] = merged
                                else items[items.size - 1] = merged
                                i++; continue
                            }
                            break
                        }
                        val indent = (mu ?: mo)!!.groupValues[1].replace("\t", "    ").length
                        if (baseIndent < 0) baseIndent = indent
                        val item = if (mo != null) Item(true, mo.groupValues[2].toIntOrNull() ?: 1, mo.groupValues[3]) else Item(false, 0, mu!!.groupValues[2])
                        if (indent >= baseIndent + 2 && items.isNotEmpty()) items.last().children += item else items += item
                        i++
                    }
                    out += Block.ListBlock(items)
                }
                else -> { if (para.isNotEmpty()) para.append(' '); para.append(t); i++ }
            }
        }
        flush()
        return out
    }

    // 行内：粗体（两个星号）、斜体（单星号或下划线）、行内代码、[文字](链接)、尖括号自动链接
    fun inline(s: String, link: Color, codeBg: Color, onLink: (String) -> Unit): AnnotatedString = buildAnnotatedString {
        appendInline(s, link, codeBg, onLink)
    }

    private fun AnnotatedString.Builder.appendInline(s: String, link: Color, codeBg: Color, onLink: (String) -> Unit) {
        var i = 0
        val plain = StringBuilder()
        fun flushPlain() { if (plain.isNotEmpty()) { append(plain.toString()); plain.clear() } }
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length && s[i + 1] in "\\`*_[]()#+-.!|<>" -> { plain.append(s[i + 1]); i += 2 }
                c == '`' -> {
                    val end = s.indexOf('`', i + 1)
                    if (end < 0) { plain.append(c); i++ } else {
                        flushPlain()
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, fontSize = 13.sp)) { append(" ${s.substring(i + 1, end)} ") }
                        i = end + 1
                    }
                }
                s.startsWith("**", i) || s.startsWith("__", i) -> {
                    val mark = s.substring(i, i + 2)
                    val end = s.indexOf(mark, i + 2)
                    if (end < 0) { plain.append(mark); i += 2 } else {
                        flushPlain()
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(s.substring(i + 2, end), link, codeBg, onLink) }
                        i = end + 2
                    }
                }
                (c == '*' || c == '_') && i + 1 < s.length && s[i + 1] != ' ' && (c == '*' || i == 0 || !s[i - 1].isLetterOrDigit()) -> {
                    val end = s.indexOf(c, i + 1)
                    if (end < 0 || end == i + 1) { plain.append(c); i++ } else {
                        flushPlain()
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(s.substring(i + 1, end), link, codeBg, onLink) }
                        i = end + 1
                    }
                }
                c == '[' -> {
                    val close = findClose(s, i)
                    if (close > 0 && close + 1 < s.length && s[close + 1] == '(') {
                        val pEnd = s.indexOf(')', close + 2)
                        if (pEnd > 0) {
                            flushPlain()
                            val url = s.substring(close + 2, pEnd).trim().substringBefore(' ')
                            linkSpan(url, link, onLink) { appendInline(s.substring(i + 1, close), link, codeBg, onLink) }
                            i = pEnd + 1
                        } else { plain.append(c); i++ }
                    } else { plain.append(c); i++ }
                }
                c == '<' && (s.startsWith("<http://", i) || s.startsWith("<https://", i)) && s.indexOf('>', i) > 0 -> {
                    val end = s.indexOf('>', i)
                    flushPlain()
                    val url = s.substring(i + 1, end)
                    linkSpan(url, link, onLink) { append(url) }
                    i = end + 1
                }
                else -> { plain.append(c); i++ }
            }
        }
        flushPlain()
    }

    private fun findClose(s: String, open: Int): Int {
        var depth = 0
        for (j in open until s.length) {
            if (s[j] == '[') depth++
            else if (s[j] == ']') { depth--; if (depth == 0) return j }
        }
        return -1
    }

    private inline fun AnnotatedString.Builder.linkSpan(url: String, color: Color, noinline onLink: (String) -> Unit, body: AnnotatedString.Builder.() -> Unit) {
        val ann = LinkAnnotation.Clickable(url, TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline))) { onLink(url) }
        withLink(ann) { body() }
    }
}
