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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 设置项：点击弹窗编辑。
 *
 * 【为什么不再把 TextField 直接放在设置页里】
 *   Miuix 的 TextField 内部每个实例都会跑 4 个动画
 *   （边框宽度 animateDpAsState、边框颜色 animateColorAsState、
 *   标签位移、标签字号各一个 animateDpAsState）。
 *   设置页原先首屏放了 5 个 TextField，等于首次组合时要同时创建并启动
 *   20 个 Animatable 与对应协程 —— 这是"点设置要等 3 秒"的直接原因
 *   （其它 Tab 没有文本输入框，所以不卡，与内存无关）。
 *
 *   现在首屏只渲染一行只读文本 + 一个箭头，TextField 仅在用户点击后
 *   于弹窗内创建，代价从"每次进页面"变成"每次编辑一项"，且只建 1 个。
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

/** 单项设置编辑弹窗（只在这里创建唯一的那个 TextField）。 */
@Composable
private fun SettingEditDialog(
    label: String,
    initial: String,
    hint: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    // 用状态持有输入值：弹窗关闭即随组合销毁，不需要额外的清理。
    var text by remember { mutableStateOf(initial) }

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
                if (hint != null) {
                    Spacer(Modifier.height(PcTokens.SpaceS))
                    Text(
                        hint,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
                Spacer(Modifier.height(PcTokens.SpaceM))
                Row(horizontalArrangement = Arrangement.spacedBy(PcTokens.SpaceS)) {
                    TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                    Button(onClick = { onConfirm(text) }, modifier = Modifier.weight(1f)) { Text("保存") }
                }
            }
        }
    )
}
