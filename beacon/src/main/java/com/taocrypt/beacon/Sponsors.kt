package com.taocrypt.beacon

import android.util.Base64
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

data class SponsorPlan(val id: String, val title: String, val price: String, val desc: String)

/** 赞赏墙条目。kind = sponsor（赞助者）| contributor（手动添加的贡献者：tier/amount/last/expire 为空） */
data class SponsorEntry(
    val kind: String, val name: String, val avatar: String, val qq: String?, val remark: String?,
    val tier: String?, val amount: Double?, val since: Long, val last: Long?, val expire: Long?,
) { val isContributor get() = kind == "contributor" }

/** GitHub 仓库贡献者（后台关联仓库，每 6 小时同步，已排除 bot） */
data class Contributor(val login: String, val name: String, val avatarUrl: String, val htmlUrl: String, val contributions: Int, val remark: String?)

/** GET /v1/{app}/sponsors。时间为 Unix 秒；count/total 只算赞助者 */
data class SponsorWall(val updated: Long, val count: Int, val total: Double, val plans: List<SponsorPlan>, val sponsors: List<SponsorEntry>, val contributors: List<Contributor>) {
    companion object {
        internal fun parse(j: JSONObject) = SponsorWall(
            j.optLong("updated"), j.optInt("count"), j.optDouble("total", 0.0),
            j.optJSONArray("plans").objs().map { SponsorPlan(it.optString("id"), it.optString("title"), it.optString("price"), it.optString("desc")) },
            j.optJSONArray("sponsors").objs().map {
                val c = it.optString("kind", "sponsor") == "contributor"
                SponsorEntry(
                    if (c) "contributor" else "sponsor", it.optString("name"), it.optString("avatar"), it.optStringOrNull("qq"), it.optStringOrNull("remark"),
                    if (c) null else it.optString("tier"), if (c) null else it.optDouble("amount", 0.0), it.optLong("since"),
                    if (c) null else it.optLong("last"), if (c) null else it.optLong("expire"),
                )
            },
            j.optJSONArray("contributors").objs().map {
                Contributor(it.optString("login"), it.optString("name").ifEmpty { it.optString("login") }, it.optString("avatar_url"), it.optString("html_url"), it.optInt("contributions"), it.optStringOrNull("remark"))
            },
        )
    }
}

/** 绑定结果：ok=true 时 until 为到期（毫秒）；失败时 error 为 bad_request/not_found/limit/expired/rate_limited/not_configured/network */
data class SponsorBindResult(val ok: Boolean, val until: Long?, val evicted: Int, val error: String?, val nextAt: Long?)

internal object SponsorSig {
    /** 服务端赞助签名公钥（SPKI DER base64，所有 App 共用） */
    const val DEFAULT_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEo9nVkReXAI+NQT0vG91+i9yLrk7Qt3ZGrPeB8hlyZgoxOQFsAUX5idzJg9NgehsK6ozxmXXLUtHgWogLXvdmSg=="

    fun verify(publicKey: String, app: String, iid: String, until: Long, iat: Long, sig: String): Boolean = runCatching {
        val raw = Base64.decode(sig, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        if (raw.size != 64) return false
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(publicKey, Base64.DEFAULT)))
        val v = Signature.getInstance("SHA256withECDSA")
        v.initVerify(key)
        v.update("beacon-sponsor-v1\n$app\n$iid\n$until\n$iat".toByteArray(Charsets.UTF_8))
        v.verify(der(raw))
    }.getOrDefault(false)

    /** P1363 r‖s → DER */
    private fun der(raw: ByteArray): ByteArray {
        fun int(b: ByteArray) = BigInteger(1, b).toByteArray().let { byteArrayOf(0x02, it.size.toByte()) + it }
        val body = int(raw.copyOfRange(0, 32)) + int(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, body.size.toByte()) + body
    }
}
