package com.joy4fire.workbuddy2api.ui

import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner

/**
 * Miuix 弹层的 dispatcher 提供 + 系统返回桥接（删掉本文件会让所有对话框崩溃）。
 *
 * 【为什么需要「提供」】
 * 崩溃栈：No NavigationEventDispatcher was provided via LocalNavigationEventDispatcherOwner
 *   at NavigationEventHandlerKt.NavigationBackHandler
 *   at DialogContentLayoutKt.DialogContentLayout
 * 根因：LocalNavigationEventDispatcherOwner 的默认值从「宿主 View 树」按
 * R.id.view_tree_navigation_event_dispatcher_owner 查找。Compose 的 Dialog 是独立 window，
 * 那棵树不继承 Activity 的内容视图 —— 查到 null 就抛异常。
 *
 * 【为什么需要「桥接」（本次修复的核心）】
 * 上一版只提供了一个空的 NavigationEventDispatcher()，注释还声称「不影响页面级返回」。
 * 但 NavigationEventDispatcher 只是一个**分发器**：它需要 register 一个
 * NavigationEventInput 作为事件源，外部调 input.dispatchOnBackCompleted() 才会向
 * 已注册的 handler 分发。空 dispatcher 没有 input，Miuix 弹窗注册的 NavigationBackHandler
 * 就永远不会被触发 —— 实测表现就是「弹窗开着时返回手势毫无反应，只能点空白区或按钮」。
 *
 * 所以这里补上事件源：注册一个 [BackInput]，并用 Activity 的 OnBackPressedDispatcher
 * 驱动它。仅在弹窗真的注册了「启用的返回处理器」时才让这个回调生效
 * （由 onHasEnabledHandlersChanged 同步），避免平时无谓地吞掉返回事件。
 */
@Composable
fun ProvideNavigationEventDispatcher(content: @Composable () -> Unit) {
    val owner = remember { RootDispatcherOwner() }
    val backOwner = LocalOnBackPressedDispatcherOwner.current

    // dispatcher 持有输入注册表，必须在组合销毁时释放，否则 Activity 重建会累积泄漏。
    DisposableEffect(owner) {
        onDispose { owner.dispose() }
    }

    DisposableEffect(owner, backOwner) {
        val callback = backOwner?.let { BackBridge(owner) }
        callback?.let { backOwner.onBackPressedDispatcher.addCallback(it) }
        // 由 dispatcher 反向通知「当前是否有启用的 handler」，据此开关桥接回调：
        // 无弹窗时保持 disabled，返回事件照旧交给 AppShell 的 BackHandler 处理页面导航。
        owner.onEnabledChanged = { enabled -> callback?.isEnabled = enabled }
        onDispose {
            owner.onEnabledChanged = null
            callback?.remove()
        }
    }

    CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
        content()
    }
}

/**
 * 最小 Owner 实现：暴露一个自建的根 [NavigationEventDispatcher]。
 *
 * 单独抽类而非匿名对象：需要在 DisposableEffect 中持有引用以便 dispose，
 * 且避免每次重组都新建（那会让弹层的返回注册不断迁移到新 dispatcher 上）。
 */
private class RootDispatcherOwner : NavigationEventDispatcherOwner {
    private val dispatcher = NavigationEventDispatcher()
    val input = BackInput()
    var onEnabledChanged: ((Boolean) -> Unit)? = null

    init {
        // 必须先 addInput 才能收到 dispatch —— 这正是上一版缺失的一步。
        dispatcher.addInput(input)
        input.enabledObserver = { onEnabledChanged?.invoke(it) }
    }

    override val navigationEventDispatcher: NavigationEventDispatcher = dispatcher

    fun dispose() = dispatcher.dispose()
}

/** 事件源：把「用户按了返回」翻译成 dispatcher 能分发的 back-completed 事件。 */
private class BackInput : NavigationEventInput() {
    var enabledObserver: ((Boolean) -> Unit)? = null

    override fun onHasEnabledHandlersChanged(hasEnabledHandlers: Boolean) {
        enabledObserver?.invoke(hasEnabledHandlers)
    }

    fun pressBack() {
        // 先报 started 再报 completed：handler 依赖完整的状态机转换，
        // 只发 completed 在部分实现里会被判为非法转换而丢弃。
        dispatchOnBackStarted(NavigationEvent())
        dispatchOnBackCompleted()
    }
}

/** 系统返回 → 转发给 Miuix 的 dispatcher。 */
private class BackBridge(private val owner: RootDispatcherOwner) : OnBackPressedCallback(false) {
    override fun handleOnBackPressed() {
        android.util.Log.i("WBACK", "bridge-callback fired")
        owner.input.pressBack()
    }
}
