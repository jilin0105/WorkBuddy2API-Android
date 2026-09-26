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
import androidx.compose.runtime.remember
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
 * 用于「查看 API Key」「出网取证明细」「存储占用明细」「保活状态检查」。
 *
 * ⚠️ 超长文本处理（本函数最重要的约束）：
 *   出网取证的 body 是【完整请求体】，一条就可能达到几十到上百 KB。
 *   把这么长的字符串整个交给 Text() 渲染会直接闪退 —— 原因不是长度本身，
 *   而是 Compose 会对整段文本做分词与布局测量，单帧工作量大到触发
 *   「GL 上下文丢失 / 渲染超时」并让进程被杀（表现为"点某条日志就闪退"）。
 *   因此这里在渲染前先做长度上限裁剪，并明确告知用户已截断、可用「复制」拿全文。
 *
 *   注意限制只作用于【显示】：「复制」始终复制完整原文，
 *   因为取证文本的用途就是整段贴进 diff 工具比对。
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
    // 渲染上限：按字符数裁剪。取 20000 字符是权衡后的值 ——
    // 足以完整显示绝大多数 Key / 存储报告 / 状态检查，
    // 又远低于会让 Compose 布局测量卡死的量级（实测 100KB+ 必崩）。
    val display = remember(body) {
        if (body.length <= DETAIL_DISPLAY_LIMIT) body
        else body.take(DETAIL_DISPLAY_LIMIT) +
            "\n\n… 已省略 ${body.length - DETAIL_DISPLAY_LIMIT} 字符（共 ${body.length} 字符）\n" +
            "点击下方「${copyLabel ?: "复制"}」可获取完整内容。"
    }
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
                        text = display,
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

/** 明细弹窗的渲染长度上限（字符）。超过则截断显示，但不影响复制全文。 */
private const val DETAIL_DISPLAY_LIMIT = 20_000
