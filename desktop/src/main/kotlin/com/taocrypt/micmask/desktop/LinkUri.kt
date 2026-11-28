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

    /**
     * 二维码用的精简链接（格式不变，只省掉可选字段，手机端现有解析 MmPairUri.parse 直接认）：
     *   micmask://link?h=<地址1,地址2,…>&c=<配对码>[&p=<端口>]
     *  - 不带 id / n：设备 ID 和名称在握手（MmHello）里本来就会交换，连上后照样记住设备；
     *  - 端口是默认 47310 时省略 p（解析默认就是 47310）；
     *  - 地址之间的逗号不再编码成 %2C（查询串里逗号合法，解析先按 & 切再解码，结果相同）；最多 6 个地址（局域网在前）。
     * 例：micmask://link?h=192.168.31.253,10.126.126.1&c=123456（48 字符，纠错 H 级 + 中间 logo 时为 41×41 模块）
     * 「复制链接」仍用 [build] 的完整链接（带 id / 名称，电脑间粘贴导入时用来认已保存设备）。
     */
    fun buildQr(l: Link): String {
        val hosts = l.hosts.take(6).joinToString(",") { java.net.URLEncoder.encode(it, "UTF-8").replace("%3A", ":") }
        return "micmask://link?h=$hosts&c=${l.code}" + (if (l.port != com.taocrypt.micmask.link.MmLink.DEFAULT_PORT) "&p=${l.port}" else "")
    }

    /** 二维码模块矩阵（true = 深色）。默认纠错 H 级（可恢复约 30%），中间盖 logo 也能扫 */
    fun qr(text: String, ecc: QrCode.Ecc = QrCode.Ecc.HIGH): Array<BooleanArray> {
        val q = QrCode.encodeText(text, ecc)
        return Array(q.size) { y -> BooleanArray(q.size) { x -> q.getModule(x, y) } }
    }

    /** 中间 logo 占的模块边长（含白色衬底）：约 22% 边长，取与二维码同奇偶以便居中；面积约 5%，远低于 H 级 30% 的纠错能力 */
    fun logoModules(size: Int): Int { var k = (size * 0.22f).toInt(); if ((size - k) % 2 != 0) k++; return k.coerceAtLeast(5) }
}
