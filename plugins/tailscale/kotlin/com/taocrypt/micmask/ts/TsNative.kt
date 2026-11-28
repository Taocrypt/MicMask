// Part of MicMask (GPL-3.0, see LICENSE at the repository root). Links tailscale.com (BSD-3-Clause).
package com.taocrypt.micmask.ts

/**
 * JNI bindings exported by the downloaded plugin library
 * (libmicmask_tailscale.so / micmask_tailscale.dll). The symbol names are fixed:
 * Java_com_taocrypt_micmask_ts_TsNative_<name>, so this class must keep its
 * package and name, and R8/ProGuard must keep it (see README).
 *
 * Strings cross as UTF-8 byte arrays. Return codes: 0 = ok, -1 = error
 * (see [lastError]). Do not call before [TsPlugin.load] succeeded.
 */
internal object TsNative {
    @JvmStatic external fun abiVersion(): Int
    @JvmStatic external fun version(): ByteArray
    @JvmStatic external fun setLogFile(path: ByteArray?): Int
    @JvmStatic external fun start(
        stateDir: ByteArray, hostname: ByteArray?, authKey: ByteArray?, controlUrl: ByteArray?,
        ephemeral: Boolean, uploadLogs: Boolean,
    ): Int
    @JvmStatic external fun waitJson(timeoutMs: Int): ByteArray
    @JvmStatic external fun statusJson(withPeers: Boolean): ByteArray
    @JvmStatic external fun expose(port: Int, localPort: Int, udp: Boolean): Int
    @JvmStatic external fun unexpose(port: Int, udp: Boolean): Int
    @JvmStatic external fun dial(host: ByteArray, port: Int, udp: Boolean): Int
    @JvmStatic external fun undial(localPort: Int, udp: Boolean): Int
    @JvmStatic external fun stop(): Int
    @JvmStatic external fun logout(wipe: Boolean): Int
    @JvmStatic external fun setInterfaces(json: ByteArray): Int
    @JvmStatic external fun lastError(): ByteArray
}
