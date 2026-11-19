package com.taocrypt.micmask

import android.content.Context
import android.content.SharedPreferences

/** App 主配置。旧版存在 "atri_vc"，首次访问时整体迁到 "micmask" 并删除旧文件。 */
object Prefs {
    private const val NAME = "micmask"
    private const val OLD = "atri_vc"
    @Volatile private var done = false

    fun main(ctx: Context): SharedPreferences {
        val app = ctx.applicationContext ?: ctx
        val sp = app.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        if (!done) synchronized(this) {
            if (!done) {
                if (!sp.getBoolean("_migrated", false)) {
                    val old = app.getSharedPreferences(OLD, Context.MODE_PRIVATE)
                    val e = sp.edit()
                    for ((k, v) in old.all) when (v) {
                        is String -> e.putString(k, v); is Int -> e.putInt(k, v); is Long -> e.putLong(k, v)
                        is Float -> e.putFloat(k, v); is Boolean -> e.putBoolean(k, v)
                        is Set<*> -> e.putStringSet(k, v.filterIsInstance<String>().toSet())
                    }
                    e.putBoolean("_migrated", true).commit()
                    runCatching { old.edit().clear().commit(); app.deleteSharedPreferences(OLD) }
                }
                done = true
            }
        }
        return sp
    }
}
