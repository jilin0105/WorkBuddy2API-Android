package com.joy4fire.workbuddy2api.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 弹窗容器 —— 替代 Miuix 的 WindowDialog，唯一区别是**支持系统返回**。
 *
 * 【为什么必须替换】
 * 实测（2026-11-17 虚拟屏注入返回键 + logcat）：
 *   · Miuix 的 WindowDialog 跑在独立 Dialog 窗口里，返回键被它吞掉；
 *   · Miuix 内部注册的 NavigationBackHandler 挂在 NavCompat 自建的根 dispatcher 上，
 *     而那个 dispatcher 没有事件源（不接 Activity 的 OnBackPressedDispatcher），
 *     所以 handler 永远不会被触发；
 *   · Activity 侧收不到事件 → AppShell 的 BackHandler、DialogBackQueue 全部打空，
 *     logcat 里连一条日志都没有。
 * 结论：只要还用 Miuix 的 WindowDialog，返回就无解（它的 WindowDialog 参数表里
 * 也没有 dismissOnBackPress / enableBackPress 这类开关）。
 *
 * 【做法】
 * 改用 Compose 官方的 Dialog + DialogProperties(dismissOnBackPress = true)，
 * 这是框架自己保证能响应返回的路径。外观上尽量贴近 Miuix：
 * 圆角卡片、主题背景色、标题居中、内容区可滚动、最大高度受限。
 *
 * 参数与原先调用点用到的保持一致（show/title/summary/onDismissRequest/
 * insideMargin/cornerRadius/modifier/content），减少调用方改动。
 */
@Composable
fun PcDialog(
    show: Boolean,
    onDismissRequest: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    title: String? = null,
    summary: String? = null,
    insideMargin: DpSize = DpSize(20.dp, 20.dp),
    cornerRadius: Dp = 24.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    if (!show) return

    Dialog(
        onDismissRequest = { onDismissRequest?.invoke() },
        properties = DialogProperties(
            // 这三项是本次修复的核心：
            //   dismissOnBackPress → 返回键/返回手势触发 onDismissRequest
            //   dismissOnClickOutside → 保持原有的「点空白关闭」
            //   usePlatformDefaultWidth=false → 允许自定义宽度，避免被系统压窄
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp)
                    .heightIn(max = 620.dp)
                    .background(
                        color = MiuixTheme.colorScheme.surface,
                        shape = RoundedCornerShape(cornerRadius)
                    )
                    .padding(horizontal = insideMargin.width, vertical = insideMargin.height)
                    .verticalScroll(rememberScrollState())
            ) {
                if (title != null) {
                    Text(
                        text = title,
                        style = MiuixTheme.textStyles.title2,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                }
                if (summary != null) {
                    Text(
                        text = summary,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
                content()
            }
        }
    }
}
