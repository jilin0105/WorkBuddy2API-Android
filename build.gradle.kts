plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    // Compose 编译器插件必须与 Kotlin 版本严格一致，否则编译期直接报错。
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}
