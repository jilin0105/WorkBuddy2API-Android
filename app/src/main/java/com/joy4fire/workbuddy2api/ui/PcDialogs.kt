package com.joy4fire.workbuddy2api.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * Miuix 对话框封装 —— 替代原界面里十几个 `AlertDialog.Builder` 调用。
 *
 * 为什么用 WindowDialog 而不是 OverlayDialog：
 * OverlayDialog 走 MiuixPopupHost → DialogContentLayout → NavigationBackHandler，
 * 在纯 Compose 且宿主未提供 NavigationEventDispatcherOwner 时会抛
 * "No NavigationEventDispatcher was provided"。
 * WindowDialog 直接基于 Compose 的 window Dialog（它的源码里 0 处 BackHandler），
 * 完全绕开这条依赖链，是零崩溃风险的选项。返回键关闭由系统 Dialog 自身承担。
 *
 * 另：OverlayDialog / WindowDialog 都【没有】buttons 参数，按钮要写进 content 里，
 * 这是 Miuix 的设计（content 即整个对话框体）。下方各封装已按此约定处理。
 */
@Composable
fun PcConfirmDialog(
    show: Boolean,
    title: String,
    message: String,
    confirmText: String = "确定",
    dismissText: String = "取消",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    WindowDialog(
        show = show,
        title = title,
        onDismissRequest = onDismiss,
        content = {
            Column(Modifier.fillMaxWidth()) {
                // 正文可能很长（保活检查报告等），限高 + 滚动，避免撑出屏幕导致按钮点不到。
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())
                ) {
                    Text(message, style = MiuixTheme.textStyles.main)
                }
                Spacer(Modifier.height(PcTokens.SpaceM))
                Row(horizontalArrangement = Arrangement.spacedBy(PcTokens.SpaceS)) {
                    TextButton(text = dismissText, onClick = onDismiss, modifier = Modifier.weight(1f))
                    Button(onClick = onConfirm, modifier = Modifier.weight(1f)) { Text(confirmText) }
                }
            }
        }
    )
}

/** 只有「知道了」的提示框。 */
@Composable
fun PcAlertDialog(
    show: Boolean,
    title: String,
    message: String,
    buttonText: String = "知道了",
    onDismiss: () -> Unit
) {
    WindowDialog(
        show = show,
        title = title,
        onDismissRequest = onDismiss,
        content = {
            Column(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())
                ) {
                    Text(message, style = MiuixTheme.textStyles.main)
                }
                Spacer(Modifier.height(PcTokens.SpaceM))
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(buttonText) }
            }
        }
    )
}

/**
 * 单行输入对话框（自定义 API Key、设置项数值等）。
 *
 * @param value 当前值（外部 state 持有，便于校验后回填错误）
 * @param errorText 非空时在输入框下方红字提示
 */
@Composable
fun PcInputDialog(
    show: Boolean,
    title: String,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    confirmText: String = "保存",
    errorText: String? = null,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    WindowDialog(
        show = show,
        title = title,
        onDismissRequest = onDismiss,
        content = {
            Column(Modifier.fillMaxWidth()) {
                TextField(
                    value = value,
                    onValueChange = onValueChange,
                    label = label,
                    modifier = Modifier.fillMaxWidth()
                )
                if (errorText != null) {
                    Spacer(Modifier.height(PcTokens.SpaceS))
                    Text(errorText, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.error)
                }
                Spacer(Modifier.height(PcTokens.SpaceM))
                Row(horizontalArrangement = Arrangement.spacedBy(PcTokens.SpaceS)) {
                    TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                    Button(onClick = onConfirm, modifier = Modifier.weight(1f)) { Text(confirmText) }
                }
            }
        }
    )
}

/**
 * 明细查看对话框：等宽文本 + 一键复制。
 * 用于「查看 API Key」「出网取证明细」「存储占用明细」。
 *
 * 用等宽字的理由：这些内容都是要逐字节比对/肉眼扫字段顺序的（headers、JSON、Key），
 * 比例字体下很容易看错行。
 */
@Composable
fun PcDetailDialog(
    show: Boolean,
    title: String,
    body: String,
    mono: Boolean = true,
    copyLabel: String? = null,
    onCopy: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    WindowDialog(
        show = show,
        title = title,
        onDismissRequest = onDismiss,
        content = {
            Column(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = body,
                        style = MiuixTheme.textStyles.body2,
                        fontFamily = if (mono) FontFamily.Monospace else null
                    )
                }
                Spacer(Modifier.height(PcTokens.SpaceM))
                Row(horizontalArrangement = Arrangement.spacedBy(PcTokens.SpaceS)) {
                    if (onCopy != null) {
                        TextButton(
                            text = copyLabel ?: "复制",
                            onClick = onCopy,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("关闭") }
                }
            }
        }
    )
}
