package com.taocrypt.micmask.link

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * MicMask Link v1 的密码学部分（纯 Kotlin + JDK/Android 自带的 javax.crypto，不依赖 Android，可用 kotlinc 单独测试；
 * 手机端与电脑端共用同一份源码，电脑端在构建时从 app/ 复制，见 desktop/build.gradle.kts 的 syncLinkShared）。
 *
 *  - 配对：SPAKE2（RFC 9382，套件 P256-SHA256-HKDF-HMAC，M/N 取 RFC 9382 §6 的 P-256 点，h = 1）。
 *    口令 = 接收端显示的 6 位配对码；被动监听拿不到密钥，主动攻击每次连接只能猜一个码（10⁻⁶）。
 *  - 会话密钥：HKDF-SHA256(Ke, salt = 双方 HELLO 随机数, info = "MicMask Link v1 keys") → 4 把 AES-128 密钥（控制/音频 × 两个方向）。
 *  - 加密：AES-128-GCM，nonce = 4 字节 0 ‖ 8 字节包计数（每把密钥各自递增，绝不复用），tag 16 字节，明文头作 AAD。
 * 注意：这里的椭圆曲线运算用 BigInteger 实现，不是常数时间；配对码只在握手时用一次、口令熵本身只有 20 bit，
 * 侧信道不是主要威胁（推断）。详见 docs/link-protocol.md。
 */
object P256 {
    val p = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
    val a: BigInteger = p.subtract(BigInteger.valueOf(3))
    val b = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)
    val n = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)
    val G = Pt(BigInteger("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296", 16),
        BigInteger("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5", 16))
    /** RFC 9382 §6 */
    val M = decode(hex("02886e2f97ace46e55ba9dd7242579f2993b64e16ef3dcab95afd497333d8fa12f"))
    val N = decode(hex("03d8bbd6c639c62937b04d997f38c3770719c629d7014d49a24b4f98baa1292b49"))

    /** 仿射坐标点；INF = 无穷远点 */
    class Pt(val x: BigInteger?, val y: BigInteger?) {
        val inf get() = x == null
        override fun equals(other: Any?) = other is Pt && other.x == x && other.y == y
        override fun hashCode() = (x?.hashCode() ?: 0) * 31 + (y?.hashCode() ?: 0)
    }
    val INF = Pt(null, null)

    fun onCurve(q: Pt): Boolean {
        if (q.inf) return false
        val x = q.x!!; val y = q.y!!
        if (x.signum() < 0 || x >= p || y.signum() < 0 || y >= p) return false
        return y.multiply(y).subtract(x.pow(3).add(a.multiply(x)).add(b)).mod(p).signum() == 0
    }

    fun neg(q: Pt): Pt = if (q.inf) q else Pt(q.x, p.subtract(q.y!!).mod(p))

    fun add(u: Pt, v: Pt): Pt {
        if (u.inf) return v
        if (v.inf) return u
        val x1 = u.x!!; val y1 = u.y!!; val x2 = v.x!!; val y2 = v.y!!
        val l: BigInteger = if (x1 == x2) {
            if (y1.add(y2).mod(p).signum() == 0) return INF
            x1.multiply(x1).multiply(BigInteger.valueOf(3)).add(a).multiply(y1.shiftLeft(1).modInverse(p)).mod(p)
        } else y2.subtract(y1).multiply(x2.subtract(x1).modInverse(p)).mod(p)
        val x3 = l.multiply(l).subtract(x1).subtract(x2).mod(p)
        val y3 = l.multiply(x1.subtract(x3)).subtract(y1).mod(p)
        return Pt(x3, y3)
    }

    fun mul(k0: BigInteger, q: Pt): Pt {
        val k = k0.mod(n)
        var r = INF; var d = q
        for (i in 0 until k.bitLength()) { if (k.testBit(i)) r = add(r, d); d = add(d, d) }
        return r
    }

    fun encode(q: Pt): ByteArray {
        require(!q.inf) { "无穷远点不能编码" }
        return byteArrayOf(4) + fixed(q.x!!, 32) + fixed(q.y!!, 32)
    }

    /** SEC1 压缩（02/03）或非压缩（04）点；不在曲线上抛异常 */
    fun decode(b: ByteArray): Pt {
        val q = when {
            b.size == 65 && b[0].toInt() == 4 -> Pt(BigInteger(1, b.copyOfRange(1, 33)), BigInteger(1, b.copyOfRange(33, 65)))
            b.size == 33 && (b[0].toInt() == 2 || b[0].toInt() == 3) -> {
                val x = BigInteger(1, b.copyOfRange(1, 33))
                val rhs = x.pow(3).add(a.multiply(x)).add(this.b).mod(p)
                var y = rhs.modPow(p.add(BigInteger.ONE).shiftRight(2), p)
                if (y.testBit(0) != (b[0].toInt() == 3)) y = p.subtract(y)
                Pt(x, y)
            }
            else -> throw IllegalArgumentException("点编码长度/前缀不对")
        }
        require(onCurve(q)) { "点不在 P-256 上" }
        return q
    }

    fun fixed(v: BigInteger, len: Int): ByteArray {
        val raw = v.toByteArray()
        val s = if (raw.size > len) raw.copyOfRange(raw.size - len, raw.size) else raw
        return ByteArray(len - s.size) + s
    }

    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { ((Character.digit(s[2 * it], 16) shl 4) or Character.digit(s[2 * it + 1], 16)).toByte() }
}

object MmCrypto {
    private val rnd = SecureRandom()
    fun random(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }

    fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run { parts.forEach { update(it) }; digest() }
    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); parts.forEach { update(it) }; doFinal() }

    /** RFC 5869 HKDF-SHA256；salt 为 null/空时用 32 字节 0 */
    fun hkdf(ikm: ByteArray, salt: ByteArray?, info: ByteArray, len: Int): ByteArray {
        val prk = hmac(if (salt == null || salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = ByteArray(len); var t = ByteArray(0); var pos = 0; var i = 1
        while (pos < len) { t = hmac(prk, t, info, byteArrayOf(i.toByte())); val k = minOf(t.size, len - pos); System.arraycopy(t, 0, out, pos, k); pos += k; i++ }
        return out
    }

    fun ctEquals(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)

    /** 6 位配对码 → SPAKE2 的 w（RFC 9382 允许自选 MHF；口令只有 20 bit，慢哈希意义不大，这里用 HKDF 并取 64 字节再模 n 以消除偏差） */
    fun pairingW(code: String): BigInteger {
        val norm = code.filter { it.isDigit() }
        val wide = hkdf(norm.toByteArray(Charsets.US_ASCII), "MicMask Link v1 pairing".toByteArray(), "w".toByteArray(), 64)
        return BigInteger(1, wide).mod(P256.n)
    }

    fun newPairingCode(): String = (rnd.nextInt(1_000_000)).toString().padStart(6, '0')
}

/**
 * SPAKE2（RFC 9382）。A = 连接发起方（客户端），B = 监听方（服务端）。
 * 用法：start() 得到本方消息 → 收到对方消息后 finish(peerMsg) → 得到 Ke 与双方确认值。
 */
class Spake2(
    private val isA: Boolean,
    private val idA: ByteArray,
    private val idB: ByteArray,
    private val w: BigInteger,
    /** 测试用：固定随机数（RFC 9382 附录 B 测试向量） */
    fixedScalar: BigInteger? = null,
) {
    private val s: BigInteger = fixedScalar ?: BigInteger(1, MmCrypto.random(40)).mod(P256.n.subtract(BigInteger.ONE)).add(BigInteger.ONE)
    val myMsg: ByteArray = P256.encode(P256.add(P256.mul(s, P256.G), P256.mul(w, if (isA) P256.M else P256.N)))

    class Result(val ke: ByteArray, val myConfirm: ByteArray, val peerConfirm: ByteArray, val tt: ByteArray, val k: ByteArray)

    fun finish(peerMsg: ByteArray, aad: ByteArray = ByteArray(0)): Result {
        val peer = P256.decode(peerMsg)
        // K = h·s·(peer − w·(对方的 M/N))，P-256 的 h = 1
        val k = P256.mul(s, P256.add(peer, P256.neg(P256.mul(w, if (isA) P256.N else P256.M))))
        require(!k.inf) { "SPAKE2：K 为无穷远点" }
        val kb = P256.encode(k)
        val pA = if (isA) myMsg else peerMsg
        val pB = if (isA) peerMsg else myMsg
        val tt = java.io.ByteArrayOutputStream().apply {
            fun put(x: ByteArray) { val l = x.size.toLong(); for (i in 0 until 8) write(((l ushr (8 * i)) and 0xff).toInt()); write(x) }
            put(idA); put(idB); put(pA); put(pB); put(kb); put(P256.fixed(w, 32))
        }.toByteArray()
        val h = MmCrypto.sha256(tt)
        val ke = h.copyOfRange(0, 16); val ka = h.copyOfRange(16, 32)
        val kc = MmCrypto.hkdf(ka, null, "ConfirmationKeys".toByteArray() + aad, 32)
        val cA = MmCrypto.hmac(kc.copyOfRange(0, 16), tt)
        val cB = MmCrypto.hmac(kc.copyOfRange(16, 32), tt)
        return Result(ke, if (isA) cA else cB, if (isA) cB else cA, tt, kb)
    }
}

/** 一个方向、一个通道的 AES-128-GCM 封装。nonce = 0x00000000 ‖ 64 位计数 */
class GcmBox(key: ByteArray) {
    private val k = SecretKeySpec(key, "AES")
    private val enc: Cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private val dec: Cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private fun nonce(ctr: Long) = ByteArray(12).also { for (i in 0 until 8) it[4 + i] = (ctr ushr (8 * (7 - i))).toByte() }

    /** 返回 密文‖tag（长度 = 明文 + 16） */
    @Synchronized fun seal(ctr: Long, aad: ByteArray, aadOff: Int, aadLen: Int, pt: ByteArray, off: Int, len: Int): ByteArray {
        enc.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, nonce(ctr)))
        enc.updateAAD(aad, aadOff, aadLen)
        return enc.doFinal(pt, off, len)
    }

    /** 认证失败返回 null */
    @Synchronized fun open(ctr: Long, aad: ByteArray, aadOff: Int, aadLen: Int, ct: ByteArray, off: Int, len: Int): ByteArray? = try {
        dec.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, nonce(ctr)))
        dec.updateAAD(aad, aadOff, aadLen)
        dec.doFinal(ct, off, len)
    } catch (_: javax.crypto.AEADBadTagException) { null } catch (_: java.security.GeneralSecurityException) { null }
}

/** 握手完成后的会话密钥 */
class MmKeys(val txCtrl: GcmBox, val rxCtrl: GcmBox, val txAudio: GcmBox, val rxAudio: GcmBox) {
    companion object {
        fun derive(ke: ByteArray, nonceC: ByteArray, nonceS: ByteArray, isClient: Boolean): MmKeys {
            val okm = MmCrypto.hkdf(ke, nonceC + nonceS, "MicMask Link v1 keys".toByteArray(), 64)
            val c2sCtrl = okm.copyOfRange(0, 16); val s2cCtrl = okm.copyOfRange(16, 32)
            val c2sAud = okm.copyOfRange(32, 48); val s2cAud = okm.copyOfRange(48, 64)
            return if (isClient) MmKeys(GcmBox(c2sCtrl), GcmBox(s2cCtrl), GcmBox(c2sAud), GcmBox(s2cAud))
            else MmKeys(GcmBox(s2cCtrl), GcmBox(c2sCtrl), GcmBox(s2cAud), GcmBox(c2sAud))
        }
    }
}

/** 64 包滑动窗口防重放（RFC 4303 风格） */
class ReplayWindow {
    private var top = -1L
    private var bits = 0L
    @Synchronized fun accept(n: Long): Boolean {
        if (n < 0) return false
        if (n > top) { val sh = n - top; bits = if (sh >= 64) 1L else (bits shl sh.toInt()) or 1L; top = n; return true }
        val d = top - n
        if (d >= 64) return false
        val m = 1L shl d.toInt()
        if (bits and m != 0L) return false
        bits = bits or m; return true
    }
}
