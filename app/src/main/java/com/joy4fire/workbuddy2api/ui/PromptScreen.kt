package com.joy4fire.workbuddy2api.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.joy4fire.workbuddy2api.GrowthFacade
import com.joy4fire.workbuddy2api.PromptInjection
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 提示词注入设置页 —— 对应 1.2.7 的「提示词」配置块（prompt_mode / prompt_text /
 * sanitize_fingerprints / use_degraded_prompt）。
 *
 * 关键交互说明：
 *   - 模式用 TabRow 二选一（custom / passthrough），不做成 Switch —— 因为
 *     「原样透传」与「接管 system」是互斥的两种语义，而不是一个开关；
 *   - 降级开关只读展示 + 一键恢复：1.2.7 的降级是【自动触发】的（内容被拦时），
 *     让用户能手动关掉它（说明误伤已排除），但不鼓励平时打开。
 */
@Composable
fun PromptScreen(context: Context) {
    val state = rememberTextFieldState()
    var modeIndex by remember { mutableStateOf(0) }
    var sanitize by remember { mutableStateOf(true) }
    var degraded by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf("") }

    // 进入页面时载入现有配置
    LaunchedEffect(Unit) {
        val settings = GrowthFacade.promptSettings(context)
        modeIndex = if (settings.optString("prompt_mode") == "passthrough") 1 else 0
        sanitize = settings.optString("sanitize_fingerprints", "1") !in setOf("0", "false")
        degraded = settings.optBoolean("use_degraded_prompt", false)
        state.edit { replace(0, state.text.length, settings.optString("prompt_text")) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = PcTokens.SpaceM, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(PcTokens.SpaceS)
    ) {
        item {
            PcCard(title = "模式") {
                TabRow(
                    tabs = listOf("接管 system", "原样透传"),
                    selectedTabIndex = modeIndex,
                    onTabSelected = { modeIndex = it }
                )
                Spacer(Modifier.height(PcTokens.SpaceS))
                Text(
                    if (modeIndex == 0) {
                        "会把客户端传来的 system / developer 消息全部替换为下方的提示词，" +
                            "再插到 messages 首位。适用于希望统一上游人设、避免暴露第三方客户端身份的场景。"
                    } else {
                        "完全不改动 messages，客户端发什么就传什么。" +
                            "此时指纹清洗仍然生效（两者是独立开关）。"
                    },
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }

        item {
            PcCard(title = "提示词内容") {
                TextField(
                    state = state,
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    label = "system 提示词",
                    useLabelAsPlaceholder = true
                )
                Spacer(Modifier.height(PcTokens.SpaceM))
                // 「载入默认」占满整行、字符数提示单独一行：
                // 此前两者同处一个 Row，按钮用默认宽度 + 文本用 weight，
                // 导致按钮被压成窄条、文字贴着按钮，视觉上互相挤压。
                Button(
                    onClick = {
                        val text = GrowthFacade.promptSettings(context)
                            .optString("prompt_text")
                            .ifBlank { PromptInjection.DEFAULT_PROMPT }
                        state.edit { replace(0, state.text.length, text) }
                        status = "已载入默认提示词，记得点「保存配置」"
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("载入默认提示词") }
                Spacer(Modifier.height(PcTokens.SpaceS))
                Text(
                    "当前输入 ${state.text.length} 字符" +
                        if (state.text.isBlank()) "（留空则使用内置默认提示词）" else "",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }

        item {
            PcCard(title = "指纹清洗") {
                SwitchPreference(
                    checked = sanitize,
                    onCheckedChange = { sanitize = it },
                    title = "清洗客户端指纹",
                    summary = "从对话内容里去掉第三方客户端痕迹（billing 头、cc_ 键值、模板句、裸 11128）。" +
                        "命中这些特征时上游可能直接拦掉整条请求，建议保持开启。"
                )
                Spacer(Modifier.height(PcTokens.SpaceS))
                Text("试一下清洗效果：", style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.height(PcTokens.SpaceS / 2))
                Button(onClick = {
                    val sample = "You are Claude Code, Anthropic's official CLI tool for Claude\n" +
                        "\n" +
                        "11-128"
                    preview = GrowthFacade.previewSanitize(sample)
                }) { Text("预览清洗结果") }
                if (preview.isNotEmpty()) {
                    Spacer(Modifier.height(PcTokens.SpaceS))
                    Text(preview, style = MiuixTheme.textStyles.body2, maxLines = 6, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        item {
            PcCard(title = "降级模式") {
                SwitchPreference(
                    checked = degraded,
                    onCheckedChange = { value ->
                        degraded = value
                        GrowthFacade.savePromptSettings(context,
                            org.json.JSONObject().put("use_degraded_prompt", value))
                        status = if (value) "已开启降级：将使用通用英文提示词" else "已恢复自定义提示词"
                    },
                    title = "使用降级提示词",
                    summary = "上游返回内容拦截时系统会自动开启，直到当天午夜。若确认是误伤，" +
                        "可在这里手动关闭。"
                )
            }
        }

        item {
            PcCard(title = "保存") {
                // 统一按钮组：等宽 + 固定间距（此前两个按钮紧贴，且行内没有间距）。
                PcActionGrid(
                    actions = listOf(
                        PcAction("保存配置", primary = true) {
                            val payload = org.json.JSONObject()
                                .put("prompt_mode", if (modeIndex == 0) "custom" else "passthrough")
                                .put("prompt_text", state.text.toString())
                                .put("sanitize_fingerprints", if (sanitize) "1" else "0")
                            GrowthFacade.savePromptSettings(context, payload)
                            // 保存后回读一次，以库里的真实值作为反馈依据。
                            // 之前只提示"已保存"而不校验，掩盖了「白名单过滤导致写入被丢弃」
                            // 的问题 —— 用户会以为存上了，实际每次都在用内置提示词。
                            val saved = GrowthFacade.promptSettings(context)
                            // 注意：`x == if (c) a else b` 在 Kotlin 中会被解析成
                            // `(x == if (c) a else b)` 之外的歧义，必须给 if 加括号。
                            val same = saved.optString("prompt_text") == state.text.toString() &&
                                saved.optString("prompt_mode") == (if (modeIndex == 0) "custom" else "passthrough") &&
                                saved.optString("sanitize_fingerprints") == (if (sanitize) "1" else "0")
                            status = if (same) "已保存并生效（${state.text.length} 字符）"
                            else "保存后校验不一致，请重试"
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
                    Spacer(Modifier.height(PcTokens.SpaceM))
                    Text(
                        status,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        }

        item { SmallTitle(text = "生效范围") }
        item {
            PcCard {
                Text(
                    "上述配置对本机网关的所有协议（OpenAI / Anthropic / Responses）统一生效，" +
                        "在请求转发到上游之前处理一次。修改后无需重启服务，下一条请求即生效。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
    }
}
