package com.taocrypt.micmask.desktop

import io.nayuki.qrcodegen.QrCode
import java.net.URLDecoder

/**
 * 配对链接 / 二维码（与手机一致，格式由手机端定义）：
 *   micmask://link?h=<地址1,地址2,…>&p=<端口>&c=<6 位配对码>&id=<设备 ID 32 位十六进制>&n=<名称>
 * 值做 URL 编码（UTF-8，空格编成 %20；解析时 + 也当空格）。未知参数忽略（向前兼容）。
 * 电脑「接收」显示二维码给手机扫；电脑「发送」可以粘贴手机分享的链接导入。
 * 生成和（带配对码的）解析直接用共享源码里的 MmPairUri（与手机同一实现）；粘贴不带配对码的链接时用这里的宽松解析，连接时再要配对码。
 */
object LinkUri {
    data class Link(val hosts: List<String>, val port: Int, val code: String, val id: String, val name: String)

    private fun dec(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    fun build(l: Link): String = com.taocrypt.micmask.link.MmPairUri(l.hosts, l.port, l.code, l.id, l.name).encode()

    /** 从任意文本里找出 micmask://link?… 并解析；没有或缺地址返回 null */
    fun parse(text: String): Link? {
        val m = Regex("micmask://link\\?[^\\s\"'<>]+", RegexOption.IGNORE_CASE).find(text) ?: return null
        com.taocrypt.micmask.link.MmPairUri.parse(m.value)?.let { return Link(it.hosts, it.port, it.code, it.id, it.name) }
        val q = m.value.substringAfter('?')
        val kv = q.split('&').mapNotNull { p -> val i = p.indexOf('='); if (i <= 0) null else p.substring(0, i).lowercase() to dec(p.substring(i + 1).replace("+", "%20")) }.toMap()
        // h 里的逗号可能被编码成 %2C：先解码再按逗号切
        val hosts = (kv["h"] ?: "").split(',').map { it.trim().removePrefix("[").removeSuffix("]") }.filter { it.isNotEmpty() && it.length <= 64 }
        if (hosts.isEmpty()) return null
        val port = kv["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: com.taocrypt.micmask.link.MmLink.DEFAULT_PORT
        val code = (kv["c"] ?: "").filter(Char::isDigit).take(6)
        val id = (kv["id"] ?: "").lowercase().filter { it in '0'..'9' || it in 'a'..'f' }.take(32)
        return Link(hosts.take(8), port, code, id, (kv["n"] ?: "").take(40))
    }

    /** 本机接收的链接：所有可用 IPv4 地址（局域网在前、组网虚拟网卡在后） */
    fun forReceiver(): Link {
        val addrs = Discovery.addrs().filter { it.ip is java.net.Inet4Address }.sortedBy { if (it.vpn) 1 else 0 }.map { it.ip.hostAddress }.distinct()
        return Link(addrs, com.taocrypt.micmask.link.MmLink.DEFAULT_PORT, DeskLink.code, DeskLink.deviceIdHex, DeskLink.deviceName)
    }

    /** 二维码模块矩阵（true = 黑）；纠错 M 级 */
    fun qr(text: String): Array<BooleanArray> {
        val q = QrCode.encodeText(text, QrCode.Ecc.MEDIUM)
        return Array(q.size) { y -> BooleanArray(q.size) { x -> q.getModule(x, y) } }
    }
}
