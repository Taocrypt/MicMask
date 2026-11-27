package com.taocrypt.micmask.desktop

import java.io.File
import java.util.Properties

/** 电脑版设置：%APPDATA%\MicMask\desktop.properties（非 Windows 开发机用 ~/.micmask/） */
object Settings {
    val dir: File = (System.getenv("APPDATA")?.let { File(it, "MicMask") } ?: File(System.getProperty("user.home"), ".micmask")).apply { mkdirs() }
    private val file = File(dir, "desktop.properties")
    private val p = Properties().apply { runCatching { file.inputStream().use { load(it) } } }

    @Synchronized fun get(k: String, def: String = ""): String = p.getProperty(k, def)
    @Synchronized fun set(k: String, v: String) { p.setProperty(k, v); save() }
    fun getBool(k: String, def: Boolean) = get(k, def.toString()).toBoolean()
    fun setBool(k: String, v: Boolean) = set(k, v.toString())
    fun getInt(k: String, def: Int) = get(k, def.toString()).toIntOrNull() ?: def
    fun setInt(k: String, v: Int) = set(k, v.toString())

    @Synchronized private fun save() { runCatching { file.outputStream().use { p.store(it, "MicMask desktop") } } }
}
