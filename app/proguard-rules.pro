# ============================================================================
# R8 混淆/压缩保留规则
#
# 本项目没有使用 Class.forName / getMethod 这类真反射（已 grep 全源码确认，
# 仅有 javaClass.simpleName 用于日志，混淆后只影响日志可读性，不影响功能）。
# 因此规则以「保框架入口 + 保序列化契约」为主，不做大面积 keep。
# ============================================================================

# ---------------------------------------------------------------------------
# 1. Android 四大组件（Activity / Service / BroadcastReceiver）
#
# 这些类由系统通过 manifest 里的 android:name 反射实例化，
# 系统不认混淆后的名字，必须保类名与构造器。
# 虽然 AGP 会自动读取 manifest 生成部分规则，但显式写出更可靠，
# 尤其是在开启 isShrinkResources 之后。
# ---------------------------------------------------------------------------
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.Application

# 组件的构造器与生命周期入口方法不能被改名，否则系统回调找不到。
-keepclassmembers class * extends android.app.Activity {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
}
-keepclassmembers class * extends android.app.Service {
    public <init>();
    public void onStartCommand(android.content.Intent, int, int);
    public android.os.IBinder onBind(android.content.Intent);
}
-keepclassmembers class * extends android.content.BroadcastReceiver {
    public <init>();
    public void onReceive(android.content.Context, android.content.Intent);
}

# ---------------------------------------------------------------------------
# 2. 自定义 View 的 XML 构造器
#
# 布局文件里若以全限定类名引用自定义 View，R8 通过资源链接通常能自动保留；
# 但显式保留带 AttributeSet 的构造器可以避免「XML 里能用、混淆后崩」这类偶发问题。
# ---------------------------------------------------------------------------
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# ---------------------------------------------------------------------------
# 3. View 的 onClick 属性反射
#
# 若布局里写了 android:onClick="xxx"，系统会用反射找该方法，
# 方法名被混淆就会 NoSuchMethodException。保留该命名约定。
# ---------------------------------------------------------------------------
-keepclassmembers class * extends android.view.View {
    public void onClick(android.view.View);
}

# ---------------------------------------------------------------------------
# 4. org.json（JSONObject / JSONArray）
#
# 请求体与上游响应全靠它解析，键名是字符串字面量、不走反射，
# 因此不需要保护字段名；这里只保类本身，
# 规避 R8 对其做过度内联后可能出现的边界行为差异。
# ---------------------------------------------------------------------------
-keep class org.json.** { *; }
-dontwarn org.json.**

# ---------------------------------------------------------------------------
# 5. OkHttp / Okio
#
# OkHttp 内部大量使用反射与平台探测（如检测 ConscryptProvider、
# 判断运行平台特性），R8 若删掉「看起来没人调用」的平台类，
# 会在特定 Android 版本上抛异常。官方推荐规则即为此。
# ---------------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keep class okhttp3.internal.platform.** { *; }
-keep class okhttp3.internal.publicsuffix.** { *; }

# OkHttp 的 HTTP/2 ALPN 探测需要通过反射访问系统 SSL 类，必须保留。
-keepnames class okhttp3.internal.platform.ConscryptPlatform
-keepnames class okhttp3.internal.platform.BouncyCastlePlatform

# ---------------------------------------------------------------------------
# 6. Kotlin 元数据
#
# 保留 Kotlin 的 @Metadata 注解，避免 R8 因元数据不一致
# 在处理协程挂起函数、对象单例（object）时做出错误假设。
# ---------------------------------------------------------------------------
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings {
    <fields>;
}
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# Kotlin 协程与反射依赖的 intrinsic 类
-dontwarn kotlin.**
-dontwarn kotlinx.**

# ---------------------------------------------------------------------------
# 7. 保留行号（可选）
#
# 保留行号信息，便于线上崩溃时定位；release 下 sourcefile 一般保留原文件名。
# 若追求最小体积，可注释掉下面两行。
# ---------------------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------
# 8. 泛型与注解签名
#
# 部分库依赖 Signature 属性做泛型推断，缺失后会在运行时报 ClassCastException。
# ---------------------------------------------------------------------------
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod
