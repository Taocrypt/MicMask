# MicMask 电脑版 release 的 ProGuard 规则（叠加在 Compose 插件自带的 default-compose-desktop-rules.pro 之上：
# 那份已保留 kotlin.**、org.jetbrains.skia/skiko.**、coroutines、kotlinx.serialization 的常用规则）。
# 原则：只摇树（删没用到的类/方法），不混淆；有反射、JNI、ServiceLoader、Java 序列化的库整包保留。
# 改依赖后务必跑 MicMask.exe --smoke（CI 每次都在装好的程序上跑）。

-dontobfuscate
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable,Exceptions,RuntimeVisible*Annotations*,AnnotationDefault,MethodParameters

# 入口与本项目代码：体积很小，全保留（协议、自测、BuildInfo 常量、Settings 等）
-keep class com.taocrypt.micmask.** { *; }

# Opus（Concentus）：纯 Java 数值代码，整包保留，避免优化改变数值行为
-keep class org.concentus.** { *; }
-dontwarn org.concentus.**

# JmDNS：内部用 ServiceLoader / 委托工厂 / 反射创建实现类，整包保留；slf4j 无实现时走 NOP
-keep class javax.jmdns.** { *; }
-dontwarn javax.jmdns.**
-keep class org.slf4j.** { *; }
-dontwarn org.slf4j.**

# org.json：JSONObject 包装对象时用反射读 getter
-keep class org.json.** { *; }
-dontwarn org.json.**

# miuix：只摇树；它的主题/取色依赖（materialkolor）有 Kotlin object 与默认参数桥接，保留类名和成员签名更稳
-keep,allowshrinking class top.yukonga.miuix.kmp.** { *; }
-dontwarn top.yukonga.miuix.kmp.**
-keep,allowshrinking class com.materialkolor.** { *; }
-dontwarn com.materialkolor.**

# Compose / AndroidX（桌面版）：靠 ServiceLoader / 反射找的平台实现
-keep class androidx.compose.ui.platform.** implements java.util.ServiceLoader$Provider { *; }
-keepnames class androidx.compose.** { *; }
-dontwarn androidx.compose.**
-dontwarn androidx.**
# Swing 主线程调度器（Dispatchers.Main 走 ServiceLoader 找 MainDispatcherFactory）
-keep class kotlinx.coroutines.swing.** { *; }
-keep class * implements kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keep class * implements kotlinx.coroutines.CoroutineExceptionHandler { *; }
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# kotlinx.serialization / datetime（经 lifecycle / savedstate 间接带入；本项目不直接用）
-dontwarn kotlinx.serialization.**
-dontwarn kotlinx.datetime.**

# 所有 ServiceLoader 实现类（META-INF/services 里列的）
-keep class * implements javax.sound.sampled.spi.** { *; }

# Kotlin 枚举 values()/valueOf()（Compose 与 miuix 有按名取枚举）
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# JNI：Skiko 已在默认规则里整包保留；其它带 native 方法的类保持方法名
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

# 可选依赖引用的类（运行时不会走到）
-dontwarn org.jetbrains.annotations.**
-dontwarn javax.annotation.**
-dontwarn edu.umd.cs.findbugs.annotations.**
-dontwarn org.graalvm.**
-dontwarn com.oracle.svm.**
-dontnote **

# JNA：反射 + native 回调，整包保留；我们的接口（NativeAudio.Lib）由 JNA 动态代理实现，方法名必须保留
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-dontwarn com.sun.jna.**

# QR Code generator（Nayuki）：纯 Java，摇树即可
-dontwarn io.nayuki.qrcodegen.**
