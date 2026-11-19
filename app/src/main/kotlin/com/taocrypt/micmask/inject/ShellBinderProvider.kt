package com.taocrypt.micmask.inject

import android.content.ContentProvider
import android.content.ContentValues
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/** 接收 ShellMain 交回来的注入服务 binder（只认 shell/root 调用） */
class ShellBinderProvider : ContentProvider() {
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val uid = Binder.getCallingUid()
        if (uid != 2000 && uid != 0) return null
        if (method != "binder") return null
        val b = extras?.getBinder("binder") ?: return null
        MicInjector.onShellBinder(b)
        return Bundle().apply { putBinder("token", MicInjector.appToken) }
    }
    override fun onCreate() = true
    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?) = null
    override fun getType(u: Uri): String? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
