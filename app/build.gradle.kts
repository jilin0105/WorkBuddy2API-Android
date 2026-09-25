plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.joy4fire.workbuddy2api"

    /**
     * compileSdk 可配置化（逃生口）。
     *
     * 默认值 "android-37.0" 与 CI 保持一致，正常情况下无需覆盖。
     *
     * 背景（三点都验证过，改这里前请先读）：
     *   1) 必须用字符串版 compileSdkVersion 而不是 `compileSdk = 37` ——
     *      后者会去找名为 `android-37` 的目录并报 platform not found；
     *   2) "android-37.0" 位于 Google 的预览（preview）channel，不在稳定 channel，
     *      因此 CI 需用 `sdkmanager --channel=3 "platforms;android-37.0"` 安装；
     *   3) 不能降到 android-36：Miuix 0.9.1 的 AAR 元数据硬性要求 compileSdk >= 37，
     *      降版会被 checkDebugAarMetadata 直接拒绝。
     *
     * 覆盖方式（仅当需要临时换平台时）：
     *   ./gradlew -PcompileSdk=android-36 :app:assembleDebug
     */
    val compileSdkName: String = (project.findProperty("compileSdk") as String?) ?: "android-37.0"
    compileSdkVersion(compileSdkName)

    defaultConfig {
        applicationId = "com.joy4fire.workbuddy2api"
        minSdk = 24
        targetSdk = 36
        versionCode = 5
        versionName = "1.2.0-native-miuix"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 复用 debug 签名，让 assembleRelease 直接产出可安装的 APK。
            // 本项目是本地自用网关，不发布到任何商店，用 debug key 签 release 变体
            // 只是为了「R8 压缩后的产物能装到手机上实测」这一件事；
            // 若将来要正式发布，这里必须换成独立 release keystore。
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    configurations.all {
        // Compose 版本强制锁 1.11.2。
        //
        // 为什么必须锁：Miuix 0.9.4 传递依赖 compose 1.12.0，而 1.12.0 的 AAR 元数据
        // 声明 minAndroidGradlePluginVersion = 9.1.0。沙箱内可用的 AGP 只能到 8.13.2
        // （build-tools 36+ 只有 x86_64 版，AGP 9 所需的 aapt2 无法在 ARM64 上执行），
        // 因此必须把 compose 压回 1.11.2 —— 它只要求 AGP 8.6.0。
        resolutionStrategy {
            force("androidx.compose.ui:ui:1.11.2")
            force("androidx.compose.foundation:foundation:1.11.2")
            force("androidx.compose.animation:animation:1.11.2")
            force("androidx.compose.runtime:runtime:1.11.2")
            force("androidx.compose.ui:ui-graphics:1.11.2")
            force("androidx.compose.ui:ui-unit:1.11.2")
            force("androidx.compose.ui:ui-text:1.11.2")
            force("androidx.compose.ui:ui-util:1.11.2")
            force("androidx.compose.foundation:foundation-layout:1.11.2")
            force("androidx.compose.runtime:runtime-saveable:1.11.2")
        }
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ---- Compose 基建（版本与上方 force 一致，显式声明以免靠传递依赖碰运气）----
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui:1.11.2")
    implementation("androidx.compose.foundation:foundation:1.11.2")
    implementation("androidx.compose.material3:material3")
    // Miuix 弹层（对话框 / 下拉菜单）内部注册 NavigationBackHandler，要求 Composition 中存在
    // LocalNavigationEventDispatcherOwner；而 Compose Dialog 是独立 window、View 树取不到宿主的
    // owner（默认值按 R.id.view_tree_navigation_event_dispatcher_owner 从 View 树查找），
    // 不提供就会崩：No NavigationEventDispatcher was provided via LocalNavigationEventDispatcherOwner。
    // 修复实现在 ui/NavCompat.kt。
    //
    // 为什么必须显式声明这两个坐标：
    //   Miuix 0.9.1 的 pom 声明的是 org.jetbrains.androidx.navigationevent:navigationevent-compose:1.1.0，
    //   但该 group 下【只有 compose 模块、没有 core 模块】（Maven Central 上 core 为 404），
    //   属上游声明残缺；因此 NavigationEventDispatcher 等核心类拿不到，编译不过。
    //   实际运行期打进 APK 的类包名就是 androidx.navigationevent.*（已核对 1.1.x 字节码），
    //   故改用 androidx 官方坐标：与既有类同源，不会产生重复类或运行时冲突。
    // 两个坐标都必须显式声明：
    //   core 提供 NavigationEventDispatcher / Owner 接口；compose 提供 LocalNavigationEventDispatcherOwner
    //   与 rememberNavigationEventDispatcherOwner。
    // 只加 core 会把 compose 部分挤出编译类路径（core 不含 compose 组件），反之则缺核心类。
    implementation("androidx.navigationevent:navigationevent:1.1.2")
    implementation("androidx.navigationevent:navigationevent-compose:1.1.2")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // ---- Miuix（小米 HyperOS 风格 UI）----
    // 版本必须锁 0.9.1：它的 kotlin-stdlib 依赖 2.3.21，与本地 Kotlin 编译器一致；
    // 0.9.2/0.9.3 要求 Kotlin 2.4.0，0.9.4 的传递依赖会把 compose 拉到 1.12.0（见上）。
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.1")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.1")
    implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.1")
}
