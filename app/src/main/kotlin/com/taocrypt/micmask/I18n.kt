package com.taocrypt.micmask

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * 运行时界面翻译。源码里的中文字面量就是 key（插值写成 {0}{1}…），
 * 英文时从 assets/i18n/en.json 查表，查不到回退中文。
 *
 * - 语言设置：prefs "ui" 的 int "lang"：0 跟随系统（系统语言不是中文时用英文）、1 简体中文、2 English
 * - 状态放在 Compose mutableState 里：Compose 里调用 [tr] 的地方切换语言后会自动重组
 * - shell 进程（注入服务）不调用 [init]，始终是中文；日志不翻译
 */
object I18n {
    const val PREFS = "ui"
    const val KEY = "lang"

    /** 0 跟随系统 1 简体中文 2 English */
    var lang by mutableIntStateOf(0); private set
    /** 当前是否英文（已结合系统语言算好） */
    var english by mutableStateOf(false); private set

    @Volatile private var en: Map<String, String> = emptyMap()
    @Volatile private var loaded = false

    fun init(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        if (!loaded) synchronized(this) {
            if (!loaded) {
                en = runCatching {
                    val o = org.json.JSONObject(app.assets.open("i18n/en.json").bufferedReader().use { it.readText() })
                    val m = HashMap<String, String>(o.length() * 2)
                    o.keys().forEach { k -> m[k] = o.optString(k) }
                    m
                }.getOrElse { L.w("加载英文翻译失败：${it.message}"); emptyMap() }
                loaded = true
            }
        }
        apply(app.getSharedPreferences(PREFS, 0).getInt(KEY, 0))
    }

    /** 切换语言并保存 */
    fun set(ctx: Context, v: Int) {
        ctx.applicationContext.getSharedPreferences(PREFS, 0).edit().putInt(KEY, v).apply()
        apply(v)
    }

    private fun apply(v: Int) {
        lang = v
        english = when (v) {
            1 -> false
            2 -> true
            else -> Locale.getDefault().language != "zh"
        }
    }

    fun tr(zh: String, vararg a: Any?): String {
        var s = if (english) en[zh]?.takeIf { it.isNotEmpty() } ?: zh else zh
        if (a.isNotEmpty()) for (i in a.indices) s = s.replace("{$i}", a[i].toString())
        return s
    }
}

/** 界面文字：中文模板 → 当前语言。占位符 {0}{1}… 依次替换为参数。 */
fun tr(zh: String, vararg a: Any?): String = I18n.tr(zh, *a)
