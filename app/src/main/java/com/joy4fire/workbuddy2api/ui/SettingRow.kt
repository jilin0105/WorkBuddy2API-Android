package com.joy4fire.workbuddy2api.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 设置项：一行只读文本 + 点击弹窗编辑。
 *
 * 【为什么不把 TextField 直接放在页面上】
 *   Miuix 的 TextField 每个实例内部都会跑 4 个动画
 *   （边框宽度 animateDpAsState、边框颜色 animateColorAsState、标签位移、标签字号各一个）。
 *   设置页曾一度首屏放 5 个 TextField，等于首次组合时同时创建并启动 20 个 Animatable
 *   与对应协程 —— 这是"点设置要等 3 秒"的直接原因（其它 Tab 没有输入框，所以不卡）。
 *
 *   现在首屏只渲染一行只读文本，TextField 仅在用户点击后于弹窗内创建：
 *   代价从"每次进页面 × 5"变成"每次编辑一项 × 1"。
 */
@Composable
fun SettingRow(
    label: String,
    value: String,
    hint: String? = null,
    onCommit: (String) -> Unit
) {
    var editing by remember { mutableStateOf(false) }

    PcRow(
        title = label,
        summary = hint,
        value = value.ifBlank { "未设置" },
        onClick = { editing = true }
    )

    if (editing) {
        SettingEditDialog(
            label = label,
            initial = value,
            hint = hint,
            onDismiss = { editing = false },
            onConfirm = {
                onCommit(it)
                editing = false
            }
        )
    }
}

/** 单项设置编辑弹窗（页面上唯一的那个 TextField 在这里按需创建）。 */
@Composable
private fun SettingEditDialog(
    label: String,
    initial: String,
    hint: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    // 用 key = label 重建输入状态：同一位置先后打开不同设置项时，
    // 若沿用 remember {} 会把上一项的输入带过来（串值）。
    var text by remember(label) { mutableStateOf(initial) }

    WindowDialog(
        show = true,
        title = label,
        onDismissRequest = onDismiss,
        content = {
            Column(Modifier.fillMaxWidth()) {
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    label = label,
                    modifier = Modifier.fillMaxWidth()
                )
                if (!hint.isNullOrBlank()) {
                    Spacer(Modifier.height(PcTokens.RowGap))
                    PcNote(hint)
                }
                Spacer(Modifier.height(PcTokens.SectionGap))
                Row(horizontalArrangement = Arrangement.spacedBy(PcTokens.ButtonGap)) {
                    TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                    top.yukonga.miuix.kmp.basic.Button(
                        onClick = { onConfirm(text) },
                        modifier = Modifier.weight(1f)
                    ) { Text("保存") }
                }
            }
        }
    )
}

/**
 * 优先级设置弹窗 —— 从账号卡「更多」进入。
 *
 * 保留三条调节路径（步进 / 滑块 / 快捷档位），因为优先级影响的是多账号之间的
 * 选号概率，用户常常需要 +1/−1 反复微调：只给滑块很难精确定位到某个整数，
 * 只给步进在大跨度调整时又太慢。
 */
@Composable
fun PriorityDialog(
    initial: Int,
    accountLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var value by remember(accountLabel) { mutableStateOf(initial.coerceIn(0, PRIORITY_CEILING)) }

    WindowDialog(
        show = true,
        title = "设置优先级",
        summary = accountLabel,
        onDismissRequest = onDismiss,
        content = {
            Column(Modifier.fillMaxWidth()) {
                Text("选号权重 ×${value + 1}", style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.height(PcTokens.RowGap))
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TextButton(
                        text = "−",
                        onClick = { if (value > 0) value -= 1 },
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        value.toString(),
                        style = MiuixTheme.textStyles.title2,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Clip
                    )
                    TextButton(
                        text = "+",
                        onClick = { if (value < PRIORITY_CEILING) value += 1 },
                        modifier = Modifier.weight(1f)
                    )
                }
                top.yukonga.miuix.kmp.basic.Slider(
                    value = value.toFloat(),
                    onValueChange = { value = it.toInt().coerceIn(0, PRIORITY_CEILING) },
                    valueRange = 0f..PRIORITY_CEILING.toFloat(),
                    steps = PRIORITY_CEILING - 1,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PcTokens.ButtonGap)) {
                    listOf(0, 1, 2, 5, 10).forEach { preset ->
                        TextButton(
                            text = if (preset == value) "● $preset" else preset.toString(),
                            onClick = { value = preset },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Spacer(Modifier.height(PcTokens.RowGap))
                PcNote("只影响多账号之间的选号概率：账号额度、成功率与闲置时长仍照常参与加权。")
                Spacer(Modifier.height(PcTokens.SectionGap))
                Row(horizontalArrangement = Arrangement.spacedBy(PcTokens.ButtonGap)) {
                    TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                    top.yukonga.miuix.kmp.basic.Button(
                        onClick = { onConfirm(value) },
                        modifier = Modifier.weight(1f)
                    ) { Text("保存") }
                }
            }
        }
    )
}

/** 优先级上限。与原实现一致，不做成可配置项（20 档已远超实际需要）。 */
private const val PRIORITY_CEILING = 20
