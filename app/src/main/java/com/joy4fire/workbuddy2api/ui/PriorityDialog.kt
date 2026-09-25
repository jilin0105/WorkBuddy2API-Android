package com.joy4fire.workbuddy2api.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 优先级设置对话框 —— 对应原界面的 showPriorityDialog。
 *
 * 为什么保留「步进 + 滑块 + 快捷档位」三条调节路径：
 * 优先级影响的是多账号之间的选号概率，用户常常需要 +1/−1 反复微调，
 * 只给滑块很难精确定位到某个整数；只给步进在大跨度调整时又太慢。
 *
 * 上限取值规则与原实现一致：以 max(默认上限, 当前值) 为上限，
 * 保证历史遗留的大值一定能往下调（否则用户会被"卡"在高位上无法降低）。
 */
@Composable
fun PcPriorityDialogContent(
    value: Int,
    onValueChange: (Int) -> Unit,
    accountLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val ceiling = 20
    val presets = listOf(0, 1, 2, 5, 10).filter { it <= ceiling }

    WindowDialog(
        show = true,
        title = "设置优先级",
        summary = accountLabel,
        onDismissRequest = onDismiss,
        content = {
            Column(Modifier.fillMaxWidth()) {
                // 数值面板：口径与后果写在一处，避免用户只看到数字不知道含义。
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "选号权重 ×${value + 1}",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Spacer(Modifier.height(PcTokens.SpaceS))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            text = "−",
                            onClick = { if (value > 0) onValueChange(value - 1) },
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "$value",
                            style = MiuixTheme.textStyles.title1,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            text = "+",
                            onClick = { if (value < ceiling) onValueChange(value + 1) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(Modifier.height(PcTokens.SpaceM))
                Slider(
                    value = value.toFloat(),
                    onValueChange = { onValueChange(it.toInt().coerceIn(0, ceiling)) },
                    valueRange = 0f..ceiling.toFloat(),
                    steps = ceiling - 1,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("0 · 不加权", style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Text("上限 $ceiling", style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }

                Spacer(Modifier.height(PcTokens.SpaceS))
                Row(horizontalArrangement = Arrangement.spacedBy(PcTokens.SpaceS)) {
                    Text("快捷", style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    presets.forEach { preset ->
                        TextButton(
                            text = if (preset == value) "● $preset" else "$preset",
                            onClick = { onValueChange(preset) }
                        )
                    }
                }

                Spacer(Modifier.height(PcTokens.SpaceS))
                Text(
                    "只影响多账号之间的选号概率：账号额度、成功率与闲置时间仍照常参与加权。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )

                Spacer(Modifier.height(PcTokens.SpaceM))
                Row(horizontalArrangement = Arrangement.spacedBy(PcTokens.SpaceS)) {
                    TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                    Button(onClick = onConfirm, modifier = Modifier.weight(1f)) { Text("保存") }
                }
            }
        }
    )
}
