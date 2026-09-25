package com.joy4fire.workbuddy2api.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner

/**
 * Miuix 弹层依赖修复（删掉这个文件会让所有对话框/下拉菜单崩溃）。
 *
 * 崩溃栈：
 *   java.lang.IllegalStateException: No NavigationEventDispatcher was provided
 *     via LocalNavigationEventDispatcherOwner
 *       at androidx.navigationevent.compose.NavigationEventHandlerKt.NavigationBackHandler
 *       at top.yukonga.miuix.kmp.layout.DialogContentLayoutKt.DialogContentLayout
 *       at top.yukonga.miuix.kmp.window.WindowDialogKt.WindowDialog
 *
 * 根因：LocalNavigationEventDispatcherOwner 的默认值由 HostDefaultKey 从「宿主 View 树」
 * 按 R.id.view_tree_navigation_event_dispatcher_owner 查找。Activity 会把它挂到自己的
 * 内容视图上，但 Compose 的 Dialog 是独立 window（AndroidDialog 在自己 window 里建
 * ComposeView），其 View 树不继承 Activity 那棵 —— 查到 null 就抛异常。
 * WindowDialog 与 OverlayDialog 都会命中这条路径，因此不能靠换组件绕开，必须显式提供。
 *
 * 为什么不用官方的 rememberNavigationEventDispatcherOwner()：
 *   它会去 LocalNavigationEventDispatcherOwner 找 parent，找不到就抛
 *   "If you intended to create a root dispatcher, explicitly pass null as the parent"。
 *   而本项目用的 activity 版本里 ComponentActivity 尚未实现该 Owner 接口，
 *   于是「找不到 parent → 抛异常」形成死循环。因此这里直接自建「根 dispatcher」
 *   （NavigationEventDispatcher() 有公开无参构造，本身即为根，无需 parent）。
 *
 * 自建根 dispatcher 的代价与取舍：
 *   返回键的关闭行为只作用于 Miuix 弹层内部（这正是我们需要的），
 *   不影响 AppShell 里用 BackHandler 实现的页面级返回——两者互不干扰。
 */
@Composable
fun ProvideNavigationEventDispatcher(content: @Composable () -> Unit) {
    val owner = remember { RootDispatcherOwner() }
    // dispatcher 持有输入注册表，必须在组合销毁时释放，否则 Activity 重建会累积泄漏。
    DisposableEffect(owner) {
        onDispose { owner.dispose() }
    }
    CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
        content()
    }
}

/**
 * 最小 Owner 实现：暴露一个自建的根 [NavigationEventDispatcher]。
 *
 * 单独抽类而非匿名对象：需要在 DisposableEffect 中持有引用以便 dispose，
 * 且避免每次重组都新建（那会让弹层的返回键注册不断迁移到新 dispatcher 上）。
 */
private class RootDispatcherOwner : NavigationEventDispatcherOwner {
    private val dispatcher = NavigationEventDispatcher()

    // Kotlin 侧接口属性为 val navigationEventDispatcher，JVM 签名即 getNavigationEventDispatcher()
    override val navigationEventDispatcher: NavigationEventDispatcher = dispatcher

    fun dispose() = dispatcher.dispose()
}
