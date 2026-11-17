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
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 端口设置行。
 *
 * 【为什么不复用 [SettingRow]】
 *   SettingRow 是"自由文本 → 立刻提交"的通用编辑行，对端口来说不够，原因有三：
 *
 *   ① 端口必须校验。SettingRow 的 onCommit 拿到什么就存什么，
 *      用户填 "abc" 或 99999 会一路写进设置表，直到下次启动服务 bind 失败才暴露。
 *      这里在提交前用 ServerPort.normalize 挡住，并把错误就地显示在弹窗里。
 *
 *   ② 改端口要求重启服务才生效。SettingRow 的语义是"立刻生效"，
 *      端口不是 —— 必须在弹窗里就把这件事讲清楚，否则用户改完发现地址没变，
 *      会以为没保存成功。这里在保存成功后通过 onSaved 回调把"需重启"的提示交回去。
 *
 *   ③ 端口还要展示"当前实际在监听哪个"。设置表里的值和真正 bind 成功的端口
 *      在"改了但还没重启"这段时间里是不一致的，界面必须能表达这个中间态。
 *
 * @param saved  设置表里保存的端口（用户意图）
 * @param active 当前服务实际监听的端口；服务未运行或无意义时传 null
 * @param running 服务是否在跑，用于决定提示口径
 * @param onSave 校验通过后写入；返回 false 表示写入失败
 */
@Composable
fun PortSettingRow(
    saved: Int,
    active: Int?,
    running: Boolean,
    onSave: (Int) -> Unit
) {
    var editing by remember { mutableStateOf(false) }

    // 待重启：saved 与 active 不一致，且服务正在跑 —— 此时用户改了端口但还没重启。
    val pendingRestart = running && active != null && active != saved

    val hint = when {
        pendingRestart -> "已改为 $saved，重启服务后生效（当前仍监听 $active）"
        running -> "当前监听 $saved，局域网内其他设备可用本机 IP 访问"
        else -> "服务未运行；修改后启动即使用新端口"
    }

    // 同 SettingRow：PcRow 的 onClick 参数从未被真正挂上（函数体里没有任何
    // clickable 修饰），用它做入口等于点不动。换 PcClickRow（内部是 Card(onClick)）。
    PcClickRow(
        title = "本地端口",
        summary = hint,
        value = saved.toString(),
        onClick = { editing = true }
    )

    if (editing) {
        PortEditDialog(
            initial = saved,
            running = running,
            active = active,
            onDismiss = { editing = false },
            onConfirm = { port ->
                onSave(port)
                editing = false
            }
        )
    }
}

/**
 * 端口编辑弹窗。
 *
 * 校验策略：允许输入任意字符（否则会用"输入到一半就报错"打断用户），
 * 但保存按钮在非法时禁用，并把原因写在输入框下方 ——
 * 这比"点了保存才弹错误"少一次挫败。
 */
@Composable
private fun PortEditDialog(
    initial: Int,
    running: Boolean,
    active: Int?,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var text by remember { mutableStateOf(initial.toString()) }
    val parsed = com.joy4fire.workbuddy2api.ServerPort.normalize(text)
    val valid = parsed != null
    val changed = parsed != initial

    // 返回手势/返回键关窗：Miuix 的 WindowDialog 不注册返回处理，
    // AppShell 的 BackHandler 会先消费返回事件，TalkBack 下又点不到空白区。
    RegisterDialogBack { onDismiss() }
    PcDialog(
        show = true,
        title = "本地端口",
        onDismissRequest = onDismiss,
        content = {
            Column(Modifier.fillMaxWidth()) {
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    label = "端口",
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(PcTokens.RowGap))

                // 错误优先：非法输入时只说错在哪，不再叠加别的说明，避免信息过载。
                if (!valid) {
                    PcErrorText(com.joy4fire.workbuddy2api.ServerPort.invalidHint())
                } else {
                    Column {
                        PcNote(
                            "可用范围 ${com.joy4fire.workbuddy2api.ServerPort.MIN}–" +
                                "${com.joy4fire.workbuddy2api.ServerPort.MAX}。" +
                                "1024 以下是系统保留端口，普通应用无法监听。"
                        )
                        if (running && changed) {
                            Spacer(Modifier.height(PcTokens.RowGap))
                            PcNote(
                                "保存后需要重启服务才会切到 $parsed" +
                                    (active?.let { "（当前仍监听 $it）" } ?: "") + "。"
                            )
                        }
                    }
                }

                Spacer(Modifier.height(PcTokens.SectionGap))
                Row(horizontalArrangement = Arrangement.spacedBy(PcTokens.ButtonGap)) {
                    TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                    Button(
                        onClick = { parsed?.let(onConfirm) },
                        modifier = Modifier.weight(1f),
                        // 非法输入时禁用，而不是让用户点了再看到报错。
                        enabled = valid
                    ) { Text("保存") }
                }
            }
        }
    )
}
