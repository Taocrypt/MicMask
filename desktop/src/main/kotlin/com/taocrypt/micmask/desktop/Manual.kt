package com.taocrypt.micmask.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/*
 * 「使用说明」：本机渲染随安装包附带的 docs/desktop-manual.md（构建时复制到资源 manual/desktop-manual.md，与仓库同步，离线可用）。
 * 不引入第三方 Markdown 库：解析器与渲染沿用手机端 ui2/ManualPage.kt 的轻量实现（只覆盖手册用到的子集：
 * 标题、段落、列表、代码块、引用、表格、分隔线、行内粗斜体 / 代码 / 链接）。
 * 懒加载：只有打开这一页时才读资源、解析、组合；离开页面后文本、解析结果和组合全部随之释放（不放全局缓存）。
 * 链接：#锚点在页内跳转；http(s) 用系统浏览器；仓库相对路径（如 desktop-signing.md）打开 GitHub 上对应文件。
 */
object Manual {
    const val RES = "manual/desktop-manual.md"
    /** 官网手册页（网页版入口；本机版加载失败时的回退）。官网上线前先指向 GitHub 上的文件 */
    const val WEB_URL = "https://github.com/Taocrypt/MicMask/blob/feat/desktop-windows/docs/desktop-manual.md"
    private const val REPO_DOCS = "https://github.com/Taocrypt/MicMask/blob/feat/desktop-windows/docs/"

    fun load(): String? = runCatching {
        Thread.currentThread().contextClassLoader.getResourceAsStream(RES)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
    }.getOrNull()

    fun resolve(url: String): String = when {
        url.startsWith("http://") || url.startsWith("https://") || url.startsWith("mailto:") -> url
        else -> REPO_DOCS + url.removePrefix("./")
    }
}

@Composable
fun ManualPage(onBack: () -> Unit) {
    var text by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val t = withContext(Dispatchers.IO) { Manual.load() }
        if (t == null) { failed = true; DeskLog.w("系统", "使用说明资源缺失：${Manual.RES}") } else text = t
    }
    val blocks = remember(text) { text?.let { Md.parse(it) } ?: emptyList() }
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
        } else openUrl(Manual.resolve(url))
    }
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("‹ 返回"), fontSize = 14.sp, color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onBack).padding(horizontal = 6.dp, vertical = 4.dp))
            Text(tr("使用说明"), fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp).weight(1f))
            Text(tr("在浏览器打开"), fontSize = 13.sp, color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { openUrl(Manual.WEB_URL) }.padding(horizontal = 6.dp, vertical = 4.dp))
        }
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 4.dp, bottom = 32.dp)) {
                when {
                    failed -> item(key = "fail") { Text(tr("本机手册加载失败，可点右上角在浏览器打开"), color = summary) }
                    text == null -> item(key = "loading") { Text(tr("加载中…"), color = summary) }
                }
                itemsIndexed(blocks) { _, b -> Box(Modifier.widthIn(max = 860.dp)) { MdBlock(b, onLink, 0) } }
            }
        }
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
