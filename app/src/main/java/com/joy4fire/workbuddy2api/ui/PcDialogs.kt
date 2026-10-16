package com.joy4fire.workbuddy2api.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 对话框族 —— 全 App 唯一的弹窗入口。
 *
 * 【为什么统一用 WindowDialog 而不是 OverlayDialog】
 *   OverlayDialog 走 MiuixPopupHost → DialogContentLayout → NavigationBackHandler，
 *   而 Compose 的 Dialog 是独立 window，View 树取不到宿主的 NavigationEventDispatcherOwner，
 *   在纯 Compose 宿主下必崩（本项目实测栈见 ui/NavCompat.kt）。
 *   WindowDialog 直接基于平台 Dialog，源码里 0 处 BackHandler，完全绕开这条依赖链。
 *   NavCompat 仍然保留，因为 Miuix 的 ListPopup / BottomSheet 等仍会命中那条路径。
 *
 * 【Miuix 弹窗的一个反直觉点】
 *   WindowDialog / OverlayDialog 都【没有】buttons 参数，按钮必须写进 content 里
 *   （content 就是整个对话框体）。下面的封装都按这个约定处理。
 *
 * 【长文本渲染的上限是硬约束，不是优化】
 *   出网取证的 body 是完整请求体，单条可达几十到上百 KB。把这么长的字符串交给单个
 *   Text() 渲染会直接闪退 —— 不是长度本身，而是 Compose 会对整段文本做分词与布局
 *   测量，单帧工作量过大触发渲染超时，进程被杀（表现为"点某条日志就闪退"）。
 *   因此 [PcDetailDialog] 内部先裁剪到 [DETAIL_DISPLAY_LIMIT] 字符再渲染，
 *   并且把"已裁剪多少"如实写出来。复制走的始终是全文，不受裁剪影响。
 */

/** 明细弹窗的渲染长度上限（字符）。超过则裁剪【显示】，不影响复制全文。 */
private const val DETAIL_DISPLAY_LIMIT = 12_000

/**
 * 确认框（危险操作用）。
 *
 * @param danger true 时确认按钮用错误色 —— 删除类操作的确认按钮必须与普通"确定"
 *               在视觉上可区分，否则用户会习惯性连点。
 */
@Composable
fun PcConfirmDialog(
    show: Boolean,
    title: String,
    message: String,
    confirmText: String = "确定",
    dismissText: String = "取消",
    danger: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    WindowDialog(
        show = show,
        title = title,
        onDismissRequest = onDismiss,
        content = {
            Column(Modifier.fillMaxWidth()) {
                ScrollableBody(message, PcTokens.DialogBodyMax)
                Spacer(Modifier.height(PcTokens.SectionGap))
                Row(horizontalArrangement = Arrangement.spacedBy(PcTokens.ButtonGap)) {
                    TextButton(text = dismissText, onClick = onDismiss, modifier = Modifier.weight(1f))
                    if (danger) {
                        // 危险操作：确认按钮走错误色，颜色来自主题，不硬编码。
                        TextButton(
                            text = confirmText,
                            onClick = onConfirm,
                            modifier = Modifier.weight(1f),
                            colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColors(
                                color = MiuixTheme.colorScheme.errorContainer
                            )
                        )
                    } else {
                        Button(onClick = onConfirm, modifier = Modifier.weight(1f)) { Text(confirmText) }
                    }
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
                ScrollableBody(message, PcTokens.DialogBodyMax)
                Spacer(Modifier.height(PcTokens.SectionGap))
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(buttonText) }
            }
        }
    )
}

/**
 * 单行输入框。
 *
 * 【实现要点】
 *   Miuix 的 TextField 有【两套 API】：新版收 TextFieldState，旧版收 value/onValueChange。
 *   本对话框需要「外部持有值以便校验后回填错误」，因此必须用旧版重载
 *   （那个 `TextField-z9KcpoQ(String, Function1, ...)` 签名）。
 *   新版 TextFieldState 一旦创建就由内部持有，外部改值要走 state.edit{}，
 *   在弹窗里容易和 remember 的生存期打架。
 *
 * @param errorText 非空时在输入框下方显示红色校验提示
 * @param hint      常驻的灰色说明（不会因为出现错误而消失，两者位置分开）
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
    hint: String? = null,
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
                    Spacer(Modifier.height(PcTokens.RowGap))
                    PcErrorText(errorText)
                }
                if (!hint.isNullOrBlank()) {
                    Spacer(Modifier.height(PcTokens.RowGap))
                    PcNote(hint)
                }
                Spacer(Modifier.height(PcTokens.SectionGap))
                Row(horizontalArrangement = Arrangement.spacedBy(PcTokens.ButtonGap)) {
                    TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                    Button(onClick = onConfirm, modifier = Modifier.weight(1f)) { Text(confirmText) }
                }
            }
        }
    )
}

/**
 * 明细查看框：等宽正文 + 一键复制 + 关闭。
 *
 * @param body     完整原文（内部会裁剪显示，但复制的是原文）
 * @param copyLabel 为空则不显示复制按钮
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
    // 裁剪放在 remember(body) 里：正文动辄上百 KB，每次重组都重算会拖慢弹窗弹出。
    val display = remember(body) {
        if (body.length <= DETAIL_DISPLAY_LIMIT) body
        else body.take(DETAIL_DISPLAY_LIMIT) +
            "\n\n… 已省略 ${body.length - DETAIL_DISPLAY_LIMIT} 字符（全文共 ${body.length} 字符）\n" +
            "点「${copyLabel ?: "复制"}」可获取未裁剪的完整内容。"
    }
    WindowDialog(
        show = show,
        title = title,
        onDismissRequest = onDismiss,
        content = {
            Column(Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = PcTokens.DetailBodyMax)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = display,
                        style = MiuixTheme.textStyles.body2,
                        fontFamily = if (mono) FontFamily.Monospace else null
                    )
                }
                Spacer(Modifier.height(PcTokens.SectionGap))
                Row(horizontalArrangement = Arrangement.spacedBy(PcTokens.ButtonGap)) {
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

/**
 * 执行日志框 —— 任务执行完的逐行结果（✓/★/·/△/✗）。
 *
 * 为什么单独做一个：执行日志是「几十行状态行」，用 [PcDetailDialog] 的等宽正文
 * 会把中文标点排得很开；而用 PcNote 又拿不到滚动。这里按「一行一条」渲染，
 * 主行（带 ✓/✗ 前缀的）用主文本色，辅助行用灰色，滚动区固定高度。
 */
@Composable
fun PcLogDialog(
    show: Boolean,
    title: String,
    lines: List<String>,
    onDismiss: () -> Unit
) {
    WindowDialog(
        show = show,
        title = title,
        onDismissRequest = onDismiss,
        content = {
            Column(Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = PcTokens.DetailBodyMax)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(PcTokens.RowGap / 2)
                ) {
                    if (lines.isEmpty()) {
                        Text("（无日志）", style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    lines.forEach { line ->
                        val marked = line.startsWith("✓") || line.startsWith("★") ||
                            line.startsWith("✗") || line.startsWith("△")
                        Text(
                            text = line,
                            style = MiuixTheme.textStyles.body2,
                            fontFamily = if (marked) null else FontFamily.Monospace,
                            color = when {
                                line.startsWith("✗") -> MiuixTheme.colorScheme.error
                                marked -> MiuixTheme.colorScheme.onSurface
                                else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                            },
                            textAlign = TextAlign.Start
                        )
                    }
                }
                Spacer(Modifier.height(PcTokens.SectionGap))
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("关闭") }
            }
        }
    )
}

/** 弹窗内的可滚动正文。抽出来是为了让「限高 + 滚动」这件事在所有弹窗里写法一致。 */
@Composable
private fun ScrollableBody(text: String, maxHeight: androidx.compose.ui.unit.Dp) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState())
    ) {
        Text(text, style = MiuixTheme.textStyles.main)
    }
}

/**
 * 受控输入状态的辅助函数：弹窗打开时用 initial 重置、关闭时丢弃。
 *
 * 为什么需要：编辑器类弹窗（编辑 API Key、设置项）如果直接用
 * `remember { mutableStateOf(initial) }`，在同一位置复用弹窗时会保留上一次的输入
 * （用户点了 A 项编辑，关掉再点 B 项，看到的还是 A 的值）。
 * 用 key 重置可以彻底避免这一类串值。
 */
@Composable
fun <T> DialogState(key: Any?, initial: T): androidx.compose.runtime.MutableState<T> {
    val state = remember(key) { mutableStateOf(initial) }
    // key 变化时记得重建；Compose 的 remember(key) 已经保证，但显式 LaunchedEffect
    // 可以把「值变了但 state 还是旧的」这种情况也覆盖掉（例如同一 item 被外部刷新）。
    LaunchedEffect(key) { state.value = initial }
    return state
}
