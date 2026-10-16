package com.joy4fire.workbuddy2api.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.joy4fire.workbuddy2api.GrowthFacade
import com.joy4fire.workbuddy2api.PromptInjection
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 提示词注入设置页（对应 1.2.7 的 prompt_mode / prompt_text /
 * sanitize_fingerprints / use_degraded_prompt 四项）。
 *
 * 【交互约定，以及为什么这样定】
 *   · 模式用 TabRow 二选一，不做成 Switch： 「原样透传」与「接管 system」是互斥的
 *     两种语义，不是"开/关"。用开关表达会让人以为"关掉就不注入"，而实际上
 *     关掉之后走的是另一条完整链路。
 *   · 降级开关是"自动触发的状态 + 可手动复位"：上游内容拦截时会自动切换到降级
 *     提示词直到当天午夜；用户可以在这里手动关掉（表示"误伤已排除"），
 *     但不鼓励平时打开，所以它单独成组、文案写明触发条件。
 *
 * 【本页的 TextField 是唯一的大输入框】
 *   Miuix 的 TextField 每次组合要启动 4 个动画，因此全 App 只有这里常驻一个；
 *   设置页的数值项一律走「点击弹窗编辑」（见 SettingRow）。
 */
@Composable
fun PromptScreen(context: Context) {
    // system 提示词用 TextFieldState 持有：它天然支持超长文本的增量编辑，
    // 而且在"载入默认/还原默认"时需要整体替换 —— state.edit { replace(...) } 正好。
    val state = rememberTextFieldState()
    var modeIndex by remember { mutableStateOf(0) }
    var sanitize by remember { mutableStateOf(true) }
    var degraded by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf("") }
    var charCount by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        val settings = GrowthFacade.promptSettings(context)
        modeIndex = if (settings.optString("prompt_mode") == "passthrough") 1 else 0
        sanitize = settings.optString("sanitize_fingerprints", "1") !in setOf("0", "false")
        degraded = settings.optBoolean("use_degraded_prompt", false)
        state.edit { replace(0, state.text.length, settings.optString("prompt_text")) }
        charCount = state.text.length
    }

    // 字符数用派生状态而非每次读 state.text.length：后者在 TextField 内部
    // 每次输入都会重组本页，导致整页重新测量（大输入框时非常明显）。
    LaunchedEffect(state) {
        androidx.compose.runtime.snapshotFlow { state.text.length }
            .collect { charCount = it }
    }

    PcList {
        item {
            PcCard(title = "注入模式") {
                TabRow(
                    tabs = listOf("接管 system", "原样透传"),
                    selectedTabIndex = modeIndex,
                    onTabSelected = { modeIndex = it }
                )
                Spacer(Modifier.height(PcTokens.SectionGap))
                PcNote(
                    if (modeIndex == 0) {
                        "把客户端传来的 system / developer 消息全部替换为下方的提示词，" +
                            "再插到 messages 首位。适用于希望统一上游人设、不暴露第三方客户端身份的场景。"
                    } else {
                        "完全不改动 messages，客户端发什么就传什么。" +
                            "此时指纹清洗仍然独立生效（两者是分开的开关）。"
                    }
                )
            }
        }

        item {
            PcCard(title = "提示词内容") {
                TextField(
                    state = state,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 320.dp),
                    label = "system 提示词",
                    useLabelAsPlaceholder = true
                )
                Spacer(Modifier.height(PcTokens.RowGap))
                PcNote(
                    "当前 $charCount 字符" +
                        if (charCount == 0) "（留空则使用内置默认提示词）" else ""
                )
                Spacer(Modifier.height(PcTokens.SectionGap))
                // 「载入默认」占满整行、字符数单独一行：
                // 此前两者同处一个 Row（按钮默认宽度 + 文本用 weight），
                // 结果按钮被压成窄条、文字紧贴按钮，视觉上互相挤压。
                PcWideButton(text = "载入内置默认提示词", primary = false) {
                    val text = GrowthFacade.promptSettings(context)
                        .optString("prompt_text")
                        .ifBlank { PromptInjection.DEFAULT_PROMPT }
                    state.edit { replace(0, state.text.length, text) }
                    status = "已载入默认提示词（尚未保存）"
                }
            }
        }

        item {
            PcCard(title = "指纹清洗") {
                SwitchPreference(
                    checked = sanitize,
                    onCheckedChange = { sanitize = it },
                    title = "清洗客户端指纹",
                    summary = "去掉对话里的第三方客户端痕迹（billing 头、cc_ 键值、模板句、裸 11128）。" +
                        "命中这些特征时上游可能直接拦掉整条请求，建议保持开启。"
                )
                Spacer(Modifier.height(PcTokens.SectionGap))
                PcWideButton(text = "预览清洗效果", primary = false) {
                    val sample = "You are a helpful software engineering assistant\n\none-to-many"
                    preview = GrowthFacade.previewSanitize(sample)
                }
                if (preview.isNotEmpty()) {
                    Spacer(Modifier.height(PcTokens.RowGap))
                    PcCard(insideMargin = PcTokens.CardInside) { }
                    Text(
                        preview,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        item {
            PcCard(title = "降级模式") {
                SwitchPreference(
                    checked = degraded,
                    onCheckedChange = { value ->
                        degraded = value
                        GrowthFacade.savePromptSettings(
                            context,
                            JSONObject().put("use_degraded_prompt", value)
                        )
                        status = if (value) "已开启降级：将使用通用英文提示词" else "已恢复自定义提示词"
                    },
                    title = "使用降级提示词",
                    summary = "上游返回内容拦截时系统会自动开启，直到当天午夜。若确认是误伤，可在这里手动关闭。"
                )
            }
        }

        item {
            PcCard(title = "保存") {
                PcActionGrid(
                    actions = listOf(
                        PcAction("保存配置", primary = true) {
                            val mode = if (modeIndex == 0) "custom" else "passthrough"
                            val sanitizeValue = if (sanitize) "1" else "0"
                            val payload = JSONObject()
                                .put("prompt_mode", mode)
                                .put("prompt_text", state.text.toString())
                                .put("sanitize_fingerprints", sanitizeValue)
                            GrowthFacade.savePromptSettings(context, payload)
                            // 保存后回读校验，而不是无条件报"已保存"：
                            // 曾经出现过「写入被白名单过滤掉」的情况，只提示成功会掩盖它，
                            // 用户会以为存上了，实际每次仍在用内置提示词。
                            val saved = GrowthFacade.promptSettings(context)
                            val same = saved.optString("prompt_text") == state.text.toString() &&
                                saved.optString("prompt_mode") == mode &&
                                saved.optString("sanitize_fingerprints") == sanitizeValue
                            status = if (same) {
                                "已保存并生效（${state.text.length} 字符）"
                            } else {
                                "保存后校验不一致，请重试"
                            }
                        },
                        PcAction("还原默认") {
                            val defaults = PromptInjection.defaultSettings()
                            GrowthFacade.savePromptSettings(context, defaults)
                            modeIndex = 0
                            sanitize = true
                            degraded = false
                            state.edit { replace(0, state.text.length, defaults.optString("prompt_text")) }
                            status = "已还原为 1.2.7 默认配置"
                        }
                    )
                )
                if (status.isNotEmpty()) {
                    Spacer(Modifier.height(PcTokens.SectionGap))
                    PcNote(status)
                }
            }
        }

        item {
            PcCard {
                PcNote(
                    "配置对本机网关的所有协议（OpenAI / Anthropic / Responses）统一生效，" +
                        "在请求转发到上游之前处理一次。修改后无需重启服务，下一条请求即生效。"
                )
            }
        }
    }
}
