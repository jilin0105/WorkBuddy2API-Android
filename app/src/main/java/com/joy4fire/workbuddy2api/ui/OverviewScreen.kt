package com.joy4fire.workbuddy2api.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.joy4fire.workbuddy2api.ApiHostService
import com.joy4fire.workbuddy2api.FormatKit
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 概览页 —— 对应原界面 overviewPage()。
 *
 * 保留原页面的信息层次（服务状态 → 快速概览 → 账号额度 → 开始使用 → 最近活动），
 * 但合并了原「更多」页里的用量入口，让状态与统计在一屏内可见。
 */
@Composable
fun OverviewScreen(context: Context, app: AppState, onOpenSub: (SubPage) -> Unit) {
    val activity = LocalContext.current as? android.app.Activity
    // 必须从 AppState 读（它是 mutableStateOf，广播写入后会触发重组）。
    // 直接读 ApiHostService.running 不会建立订阅，按钮状态不会更新。
    val running = app.serviceRunning
    val starting = app.startingUp

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = PcTokens.SpaceM, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(PcTokens.CardGap)
    ) {
        // ---------------- 服务状态 ----------------
        item {
            PcCard(title = "本地网关") {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        when {
                            running -> "●  运行中"
                            starting -> "◐  启动中…"
                            else -> "○  已停止"
                        },
                        style = MiuixTheme.textStyles.main,
                        color = when {
                            running -> MiuixTheme.colorScheme.primary
                            starting -> MiuixTheme.colorScheme.primary
                            else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                        },
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        when {
                            running -> "健康"
                            starting -> "预热中"
                            else -> "离线"
                        },
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
                Spacer(Modifier.height(PcTokens.SpaceS))
                Text(
                    when {
                        running -> "OpenAI 兼容地址  http://127.0.0.1:${ApiHostService.PORT}/v1"
                        starting -> "正在校验账号并预热模型，通常数秒内完成；若长时间无变化，请检查通知权限。"
                        else -> "启动后仅在本机提供 OpenAI 兼容 API。"
                    },
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(PcTokens.SpaceM))
                PcActionGrid(
                    actions = listOf(
                        // 启动中仍可点「停止」：若陷入启动异常（如上游卡住），
                        // 用户必须有办法中止，否则只能杀进程 —— 此前 disabled = !starting
                        // 会把按钮锁死，是"反复启停后再也停不掉"的观感来源之一。
                        PcAction(
                            when {
                                running -> "停止服务"
                                starting -> "取消启动"
                                else -> "启动服务"
                            },
                            primary = true
                        ) { app.toggleServer(activity) },
                        PcAction("复制地址") {
                            copyToClipboard(context, "http://127.0.0.1:${ApiHostService.PORT}/v1")
                            app.showMessage("已复制 Base URL")
                        }
                    )
                )
            }
        }

        // ---------------- 快速概览 ----------------
        item {
            PcCard(title = "快速概览") {
                val creditKnown = app.credits?.optInt("known") ?: 0
                PcInfoRow("账号", app.accounts.length().let { if (it == 0) "0（待配置）" else "$it 个" })
                PcInfoRow("模型", app.models?.length()?.toString() ?: "未刷新")
                PcInfoRow(
                    "全部剩余额度",
                    if (creditKnown > 0) "${FormatKit.credits(app.credits?.optDouble("remain") ?: 0.0)} 积分"
                    else "未刷新"
                )
                PcInfoRow("运行记录", "${app.events.size} 条")
                PcInfoRow("今日请求", FormatKit.tokens(app.usageSummary?.optLong("today_requests") ?: 0))
                PcInfoRow("今日 Tokens", FormatKit.tokens(app.usageSummary?.optLong("today_tokens") ?: 0))
            }
        }

        // ---------------- 账号额度 ----------------
        if (app.accounts.length() > 0) {
            item { top.yukonga.miuix.kmp.basic.SmallTitle(text = "账号额度") }
            items(app.accounts.length()) { index ->
                val item = app.accounts.optJSONObject(index) ?: return@items
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = PcTokens.PageGutter),
                    insideMargin = PaddingValues(horizontal = PcTokens.SpaceL, vertical = PcTokens.SpaceM)
                ) {
                    Column {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(
                                FormatKit.accountName(item.optString("nickname"), item.optString("uid")),
                                style = MiuixTheme.textStyles.main,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                if (item.optBoolean("enabled", true)) "已启用" else "已停用",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                        Spacer(Modifier.height(PcTokens.SpaceS / 2))
                        Text(
                            "UID ${FormatKit.shortUid(item.optString("uid"))}",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                        Spacer(Modifier.height(PcTokens.SpaceS / 2))
                        Text(
                            if (item.isNull("credits_remaining")) "额度尚未刷新"
                            else "剩余 ${FormatKit.credits(item.optDouble("credits_remaining"))} / " +
                                "总计 ${FormatKit.credits(item.optDouble("credits_total"))} 积分",
                            style = MiuixTheme.textStyles.main,
                            color = if (item.isNull("credits_remaining")) MiuixTheme.colorScheme.onSurfaceVariantSummary
                            else MiuixTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // ---------------- 开始使用 ----------------
        item { top.yukonga.miuix.kmp.basic.SmallTitle(text = "开始使用") }
        item {
            PcCard {
                val first = app.accounts.optJSONObject(0)
                Text(
                    if (first == null) "连接 WorkBuddy 账号" else "${first.optString("nickname", "账号")} 已就绪",
                    style = MiuixTheme.textStyles.main
                )
                Spacer(Modifier.height(PcTokens.SpaceS / 2))
                Text(
                    if (first == null) "先登录或导入 auth 文件，再启动本地服务。"
                    else "可直接启动服务，或刷新额度和模型目录。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(PcTokens.SpaceM))
                PcActionGrid(
                    actions = listOf(
                        PcAction(if (app.busy) "刷新中…" else "刷新全部", primary = true, enabled = !app.busy) {
                            app.refreshAll()
                        },
                        PcAction("用量统计") { onOpenSub(SubPage.USAGE) }
                    )
                )
            }
        }

        // ---------------- 最近活动 ----------------
        item { top.yukonga.miuix.kmp.basic.SmallTitle(text = "最近活动") }
        if (app.events.isEmpty()) {
            item {
                PcCard {
                    Text(
                        "暂无活动。启动服务、刷新模型或管理账号后，操作结果会显示在这里。",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        } else {
            items(minOf(5, app.events.size)) { index ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = PcTokens.PageGutter),
                    insideMargin = PaddingValues(horizontal = PcTokens.SpaceL, vertical = PcTokens.SpaceS)
                ) {
                    Text(
                        app.events.getOrNull(index).orEmpty(),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        }
    }
}

/** 复制到剪贴板（Compose 没有内置 API，走系统服务）。 */
internal fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return
    manager.setPrimaryClip(android.content.ClipData.newPlainText("WorkBuddy2API", text))
}
