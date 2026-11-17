package com.joy4fire.workbuddy2api.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * 弹窗返回栈 —— 让「系统返回 / 全面屏手势」能关闭 Miuix 弹窗。
 *
 * 【为什么需要它】
 * 在每个弹窗内部写 BackHandler(onDismiss) 实测无效：Miuix 的 WindowDialog 把内容放进
 * 独立 Dialog 窗口，那个子组合拿不到宿主的 LocalOnBackPressedDispatcherOwner，
 * BackHandler 静默失效（不报错）。
 *
 * 更根本的原因在 NavCompat.kt：那里为修「弹窗崩溃」自建了一个根 NavigationEventDispatcher，
 * 但它没有接 Activity 的 OnBackPressedDispatcher —— 没有任何人把系统返回事件喂给它，
 * 所以 Miuix 弹窗内部的 NavigationBackHandler 永远不会被触发。
 *
 * 【做法】
 * 弹窗打开时把「关闭自己的动作」压进这个栈，关闭时弹出；
 * AppShell 只保留唯一一个 BackHandler，按「弹窗 → 二级页 → 一级页 → 退出」分发。
 * 这样返回事件永远有且只有一个处理者，不会互相吞。
 *
 * 用栈而不是单个值：确认框叠在弹窗上时，返回应先关最上层那个。
 */
object DialogBackQueue {
    private val stack = ArrayDeque<() -> Unit>()

    fun push(dismiss: () -> Unit) {
        stack.addLast(dismiss)
    }

    fun pop() {
        stack.removeLastOrNull()
    }

    /** 当前最上层的关闭动作；没有弹窗时为 null。 */
    fun top(): (() -> Unit)? = stack.lastOrNull()

    fun clear() = stack.clear()
}

/**
 * 在弹窗内调用：把自己注册到返回栈。
 *
 * 用 DisposableEffect 而不是 LaunchedEffect：弹窗从组合树移除时（点空白、点按钮、
 * 状态置空）必须同步出栈，否则残留的 dismiss 会让返回键去关一个已经不存在的窗。
 *
 * rememberUpdatedState 保证 dismiss 动作更新后栈里拿到的仍是最新闭包。
 */
@Composable
fun RegisterDialogBack(onBack: () -> Unit) {
    val current by rememberUpdatedState(onBack)
    DisposableEffect(Unit) {
        DialogBackQueue.push { current() }
        onDispose { DialogBackQueue.pop() }
    }
}

/**
 * 从 Context 往上找 Activity。
 *
 * Compose 的 LocalContext 常常是 ContextThemeWrapper 而不是 Activity 本身，
 * 直接 `as? Activity` 会拿到 null，导致返回时无法 finish()。
 */
fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
