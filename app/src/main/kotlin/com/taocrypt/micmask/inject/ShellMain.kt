package com.taocrypt.micmask.inject

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper

/**
 * 不经 Shizuku UserService，自己用 app_process 以 shell 身份起注入服务进程。
 * 1.15~1.18 日志：vivo 上 App 重启后 Shizuku 建了服务记录却不拉起进程，只能重启 Shizuku。
 * 这里由 Shizuku.newProcess 跑：
 *   app_process -Djava.class.path=<apk> /system/bin --nice-name=<pkg>:injector com.taocrypt.micmask.inject.ShellMain <pkg>
 * 起来后把 binder 通过 App 的 ShellBinderProvider 交回去；App 进程死了这边也退出。
 */
object ShellMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val pkg = args.getOrNull(0) ?: "com.taocrypt.micmask"
        println("ShellMain start uid=${android.os.Process.myUid()} pid=${android.os.Process.myPid()} pkg=$pkg")
        Thread.setDefaultUncaughtExceptionHandler { _, e -> println("ShellMain 崩溃: $e"); e.printStackTrace(); System.out.flush(); System.exit(3) }
        Runtime.getRuntime().addShutdownHook(Thread { println("ShellMain 退出"); System.out.flush() })
        Looper.prepareMainLooper()
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("systemMain").invoke(null)
        val sys = at.getMethod("getSystemContext").invoke(thread) as Context
        println("ShellMain systemMain ok")
        val ctx = sys.createPackageContext(pkg, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
        println("ShellMain ctx ok")
        // 用包上下文的 ClassLoader 加载服务：它带 App 的原生库路径（ORT 的 .so），系统类路径没有
        val svc = ctx.classLoader.loadClass("com.taocrypt.micmask.inject.InjectorService")
            .getConstructor(Context::class.java).newInstance(ctx) as IBinder
        println("ShellMain loader=${ctx.classLoader}")
        println("ShellMain service ok，交 binder")
        val reply = sendBinder("$pkg.shellbinder", svc)
        val token = reply?.getBinder("token")
        if (token == null) { println("ShellMain: App 没收下 binder，退出"); System.exit(2) }
        token!!.linkToDeath({ println("ShellMain: App 进程没了，退出"); System.exit(0) }, 0)
        println("ShellMain ready")
        Looper.loop()
    }

    /** 以 shell 身份直接调 App 的 ContentProvider.call（IActivityManager.getContentProviderExternal） */
    private fun sendBinder(authority: String, b: IBinder): Bundle? {
        val sm = Class.forName("android.os.ServiceManager")
        val amBinder = sm.getMethod("getService", String::class.java).invoke(null, "activity") as IBinder
        val am = Class.forName("android.app.IActivityManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, amBinder)!!
        val token = Binder()
        val get = am.javaClass.methods.first { it.name == "getContentProviderExternal" }
        val holder = when (get.parameterTypes.size) {
            4 -> get.invoke(am, authority, 0, token, "micmask")
            else -> get.invoke(am, authority, 0, token)
        } ?: throw IllegalStateException("拿不到 provider $authority")
        val provider = holder.javaClass.getField("provider").get(holder)!!
        val extras = Bundle().apply { putBinder("binder", b) }
        try {
            // 只用带 authority 的那个重载（参数最多的）；4 参数的默认方法会把 authority 填成 "unknown"
            val call = provider.javaClass.methods.filter { it.name == "call" && it.parameterTypes.lastOrNull() == Bundle::class.java }
                .maxByOrNull { it.parameterTypes.size }!!
            println("ShellMain 调用 " + call.parameterTypes.joinToString { it.simpleName })
            val pt = call.parameterTypes
            val a = arrayOfNulls<Any>(pt.size)
            // 末尾固定为 (…, method, arg, extras)；前面按类型填 AttributionSource / 包名 / authority
            a[pt.size - 1] = extras; a[pt.size - 2] = null; a[pt.size - 3] = "binder"
            var strings = 0
            for (i in 0 until pt.size - 3) {
                val t = pt[i]
                a[i] = when {
                    t.name == "android.content.AttributionSource" ->
                        android.content.AttributionSource.Builder(android.os.Process.myUid()).setPackageName("com.android.shell").build()
                    t == String::class.java -> (if (strings++ == 0 && pt.size - 3 >= 2 && pt[0] == String::class.java && pt[1] == String::class.java) "com.android.shell" else authority)
                    else -> null
                }
            }
            return call.invoke(provider, *a) as Bundle?
        } finally {
            runCatching { am.javaClass.methods.first { it.name == "removeContentProviderExternalAsUser" }.invoke(am, authority, token, 0) }
                .recoverCatching { am.javaClass.methods.first { it.name == "removeContentProviderExternal" }.invoke(am, authority, token) }
        }
    }
}
