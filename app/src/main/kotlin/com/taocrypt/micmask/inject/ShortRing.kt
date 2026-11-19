package com.taocrypt.micmask.inject

/** 简单的线程安全 16bit 环形缓冲（满了丢最旧的）。 */
class ShortRing(cap: Int) {
    private val buf = ShortArray(cap)
    private var r = 0
    private var n = 0

    @Synchronized fun size() = n
    @Synchronized fun clear() { r = 0; n = 0 }

    @Synchronized fun write(src: ShortArray, off: Int, len: Int) {
        for (i in 0 until len) {
            val w = (r + n) % buf.size
            buf[w] = src[off + i]
            if (n < buf.size) n++ else r = (r + 1) % buf.size
        }
    }

    /** 读最多 len 个，返回实际个数 */
    @Synchronized fun read(dst: ShortArray, off: Int, len: Int): Int {
        val k = minOf(len, n)
        for (i in 0 until k) { dst[off + i] = buf[r]; r = (r + 1) % buf.size }
        n -= k
        return k
    }

    /** 丢掉最旧的，只留最新 keep 个 */
    @Synchronized fun trimTo(keep: Int) {
        if (n > keep) { r = (r + (n - keep)) % buf.size; n = keep }
    }
}
