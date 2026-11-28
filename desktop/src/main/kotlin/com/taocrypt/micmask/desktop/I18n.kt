package com.taocrypt.micmask.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * 界面翻译，与手机版 I18n 同一套规则：源码里的中文字面量就是 key（插值写成 {0}{1}…），英文时查表，查不到回退中文；日志不翻译。
 * 英文表：resources/i18n/en-desktop.json（电脑版用到的全部文字；与手机版 app/src/main/assets/i18n/en.json 重叠的句子取同一译文）。
 * 语言设置：Settings "lang"：0 跟随系统（系统语言不是中文时用英文）、1 简体中文、2 English。
 */
object I18n {
    var lang by mutableIntStateOf(Settings.getInt("lang", 0)); private set
    var english by mutableStateOf(calc(Settings.getInt("lang", 0))); private set

    val en: Map<String, String> by lazy {
        runCatching {
            val txt = Thread.currentThread().contextClassLoader.getResourceAsStream("i18n/en-desktop.json")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val o = org.json.JSONObject(txt)
            HashMap<String, String>(o.length() * 2).also { m -> o.keys().forEach { k -> m[k] = o.optString(k) } }
        }.getOrElse { emptyMap() }
    }

    private fun calc(v: Int) = when (v) { 1 -> false; 2 -> true; else -> Locale.getDefault().language != "zh" }

    fun set(v: Int) { Settings.setInt("lang", v); lang = v; english = calc(v) }

    fun tr(zh: String, vararg a: Any?): String {
        var s = if (english) en[zh]?.takeIf { it.isNotEmpty() } ?: zh else zh
        if (a.isNotEmpty()) for (i in a.indices) s = s.replace("{$i}", a[i].toString())
        return s
    }
}

/** 界面文字：中文模板 → 当前语言。占位符 {0}{1}… 依次替换为参数。 */
fun tr(zh: String, vararg a: Any?): String = I18n.tr(zh, *a)
