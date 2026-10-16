package com.joy4fire.workbuddy2api.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.joy4fire.workbuddy2api.ApiHostService
import com.joy4fire.workbuddy2api.FormatKit
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 概览页 —— 全 App 的落地页。
 *
 * 【重写思路：按"用户此刻要做什么"排布，而不是按数据表排布】
 *   上一版把服务状态、快速概览、账号额度、开始使用、最近活动五段全部用
 *   同一种 Key-Value 行平铺，一屏 20+ 行等权重文字，导致两个最关键的信息
 *   ——「服务有没有在跑」「还剩多少额度」——沉在列表里，用户要逐行找。
 *
 *   现在按重要性从高到低分层：
 *     ① 服务卡    状态 + 地址 + 启停（唯一带主按钮的卡）
 *     ② 指标卡    三个大号数字（额度 / 账号 / 今日请求），细节退到副行
 *     ③ 额度池    进度条 + 逐账号明细（弹窗）
 *     ④ 待办      只在真的有事可做时出现
 *     ⑤ 更多      用量统计 / 活动日志入口
 *
 * 【为什么"最近活动"降级为弹窗】
 *   活动日志是排查用的，不是日常要看的；常驻概览页会让页面永远很长。
 */
@Composable
fun OverviewScreen(context: Context, app: AppState, onOpenSub: (SubPage) -> Unit) {
    val activity = LocalContext.current as? Activity
    val running = app.serviceRunning
    val starting = app.startingUp
    var showEvents by remember { mutableStateOf(false) }
    var showAccountDetail by remember { mutableStateOf(false) }

    // 概览只需要「今日请求 / 今日 Tokens」两个摘要字段，因此这里按需拉取，
    // 而不是把全表聚合的 loadUsage 塞进启动路径（那会让首屏变慢）。
    LaunchedEffect(Unit) { app.loadUsageIfStale() }

    PcList {
        // ---------------- ① 服务状态 ----------------
        item {
            PcCard(title = "本地网关") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        PcStatus(
                            text = when {
                                running -> "运行中"
                                starting -> "启动中…"
                                else -> "已停止"
                            },
                            positive = running || starting
                        )
                        Spacer(Modifier.height(2.dp))
                        PcNote(
                            when {
                                running -> "服务已就绪，局域网内的客户端可直接调用。"
                                starting -> "正在校验账号并预热模型，通常数秒内完成。"
                                else -> "启动后在本机与局域网提供 OpenAI / Anthropic 兼容 API。"
                            }
                        )
                    }
                }

                Spacer(Modifier.height(PcTokens.SectionGap))

                // 地址是"要抄到别的设备上去"的东西，因此独占一行、等宽显示。
                Text(
                    "http://127.0.0.1:${ApiHostService.PORT}/v1",
                    style = MiuixTheme.textStyles.body2,
                    fontFamily = FontFamily.Monospace,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(PcTokens.SectionGap))

                PcActionGrid(
                    actions = listOf(
                        // 启动中仍可点「取消启动」：启动流程若卡住（上游超时等），
                        // 用户必须能中止，否则只能杀进程 —— 上一版 button 的
                        // enabled 会被 starting 锁死，是"反复启停后再也停不掉"的一部分原因。
                        PcAction(
                            label = when {
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

        // ---------------- ② 关键指标 ----------------
        item {
            PcCard(title = "关键指标") {
                val creditKnown = app.credits?.optInt("known") ?: 0
                PcMetricRow(
                    listOf(
                        PcMetric(
                            label = if (creditKnown > 0) "可用额度（积分）" else "可用额度",
                            value = if (creditKnown > 0) FormatKit.credits(app.credits?.optDouble("remain") ?: 0.0) else "—",
                            color = if (creditKnown > 0) MiuixTheme.colorScheme.primary else null
                        ),
                        PcMetric(label = "已连接账号", value = app.accounts.length().toString()),
                        PcMetric(
                            label = "今日请求",
                            value = FormatKit.tokens(app.usageSummary?.optLong("today_requests") ?: 0)
                        )
                    )
                )
                Spacer(Modifier.height(PcTokens.SectionGap))
                PcInfoRow("可用模型数", app.models?.length()?.toString() ?: "未刷新")
                PcInfoRow("今日 Tokens", FormatKit.tokens(app.usageSummary?.optLong("today_tokens") ?: 0))
                PcInfoRow("API Key", "${app.apps.length()} 个")
                PcInfoRow("调用记录", "${app.usageTotal} 条")
            }
        }

        // ---------------- ③ 额度池 ----------------
        val summary = app.credits
        if (summary != null && summary.optInt("known") > 0) {
            item {
                PcCard(title = "额度池") {
                    val total = summary.optDouble("total")
                    val remain = summary.optDouble("remain")
                    val ratio = if (total > 0) (remain / total).toFloat().coerceIn(0f, 1f) else 0f
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${FormatKit.credits(remain)} / ${FormatKit.credits(total)}",
                            style = MiuixTheme.textStyles.title3,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${(ratio * 100).toInt()}%",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                    Spacer(Modifier.height(PcTokens.RowGap))
                    LinearProgressIndicator(progress = ratio, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(PcTokens.RowGap))
                    PcNote("已汇总 ${summary.optInt("known")} / ${summary.optInt("accounts")} 个账号的剩余额度。")
                    Spacer(Modifier.height(PcTokens.SectionGap))
                    PcActionGrid(
                        actions = listOf(
                            PcAction("刷新全部额度", primary = true, enabled = !app.busy) { app.refreshCredits() },
                            PcAction("额度明细") { showAccountDetail = true }
                        )
                    )
                }
            }
        }

        // ---------------- ④ 待办（只在有事可做时出现） ----------------
        //
        // 三项都是「用户此刻能动手解决的事」，因此每项都配一个直接执行的按钮，
        // 而不是只写一句提示让用户自己去找入口。
        //
        // 这里用显式的 TodoItem 数据类而不是 Triple：Triple 的 second 是嵌套 Pair
        // （文案 + lambda），解构出来的 action.second() 语义不清，也容易在后续
        // 增加字段时被误改。
        val todos = buildList {
            if (app.accounts.length() == 0) {
                add(
                    TodoItem(
                        title = "还没有连接账号",
                        hint = "登录或导入 auth 文件后才能启动本地服务。",
                        actionLabel = "去「账号」页处理",
                        onAction = { app.showMessage("请切换到「账号」页完成登录或导入") }
                    )
                )
            }
            if (app.accounts.length() > 0 && (summary?.optInt("known") ?: 0) == 0) {
                add(
                    TodoItem(
                        title = "额度尚未刷新",
                        hint = "刷新后才能看到各账号的剩余额度与汇总。",
                        actionLabel = "立即刷新额度",
                        onAction = { app.refreshCredits() }
                    )
                )
            }
            if (!running && !starting && app.accounts.length() > 0) {
                add(
                    TodoItem(
                        title = "服务未启动",
                        hint = "启动后局域网内的客户端才能调用这个 API。",
                        actionLabel = "立即启动服务",
                        onAction = { app.toggleServer(activity) }
                    )
                )
            }
        }
        if (todos.isNotEmpty()) {
            item {
                PcCard(title = "待办") {
                    todos.forEachIndexed { index, todo ->
                        if (index > 0) {
                            Spacer(Modifier.height(PcTokens.SectionGap))
                            PcDivider()
                            Spacer(Modifier.height(PcTokens.SectionGap))
                        }
                        Text(todo.title, style = MiuixTheme.textStyles.main)
                        Spacer(Modifier.height(2.dp))
                        PcNote(todo.hint)
                        Spacer(Modifier.height(PcTokens.RowGap))
                        PcWideButton(text = todo.actionLabel, primary = false) { todo.onAction() }
                    }
                }
            }
        }

        // ---------------- ⑤ 更多入口 ----------------
        item { PcSection("更多") }
        item {
            PcCard {
                PcActionGrid(
                    actions = listOf(
                        PcAction("用量统计") { onOpenSub(SubPage.USAGE) },
                        PcAction("最近活动") { showEvents = true }
                    )
                )
                Spacer(Modifier.height(PcTokens.RowGap))
                PcNote("用量统计按协议 / 模型 / 应用拆分请求与 Token；最近活动记录你刚才的操作结果。")
            }
        }
    }

    if (showEvents) {
        // 活动日志是排查用的：用弹窗承载，不占据首屏高度。
        PcLogDialog(
            show = true,
            title = "最近活动（最多 50 条）",
            lines = app.events.toList(),
            onDismiss = { showEvents = false }
        )
    }

    if (showAccountDetail) {
        val accounts = remember(app.accounts, app.revision) {
            (0 until app.accounts.length()).mapNotNull { app.accounts.optJSONObject(it) }
        }
        AccountCreditsDialog(
            accounts = accounts,
            onDismiss = { showAccountDetail = false }
        )
    }
}

/**
 * 待办项。
 *
 * 用显式数据类而不是 Triple(String, String, Pair<String, () -> Unit>)：
 * 后者解构出来是 action.second()，语义不清，后续加字段时极容易被改错。
 */
private data class TodoItem(
    val title: String,
    val hint: String,
    val actionLabel: String,
    val onAction: () -> Unit
)

/**
 * 账号额度明细弹窗。
 *
 * 为什么在原地弹窗而不是跳「账号」页：用户看完进度条的下一个问题是
 * 「是哪个号剩得多」，弹窗能就地回答，不必中断当前任务流切 Tab。
 */
@Composable
private fun AccountCreditsDialog(
    accounts: List<org.json.JSONObject>,
    onDismiss: () -> Unit
) {
    top.yukonga.miuix.kmp.window.WindowDialog(
        show = true,
        title = "账号额度明细",
        onDismissRequest = onDismiss,
        content = {
            Column(Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = PcTokens.DetailBodyMax)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (accounts.isEmpty()) {
                        PcNote("暂无账号。先在「账号」页登录或导入。")
                    }
                    accounts.forEachIndexed { index, account ->
                        if (index > 0) {
                            Spacer(Modifier.height(PcTokens.RowGap))
                            PcDivider()
                            Spacer(Modifier.height(PcTokens.RowGap))
                        }
                        val nickname = FormatKit.accountName(
                            account.optString("nickname"), account.optString("uid")
                        )
                        val enabled = account.optBoolean("enabled", true)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                nickname,
                                style = MiuixTheme.textStyles.main,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            PcStatus(if (enabled) "启用" else "停用", enabled)
                        }
                        Spacer(Modifier.height(PcTokens.RowGap / 2))
                        val hasCredits = !account.isNull("credits_remaining")
                        Text(
                            if (hasCredits) {
                                "剩余 ${FormatKit.credits(account.optDouble("credits_remaining"))} / " +
                                    "总计 ${FormatKit.credits(account.optDouble("credits_total"))} 积分"
                            } else "额度尚未刷新",
                            style = MiuixTheme.textStyles.body2,
                            color = if (hasCredits) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                        if (hasCredits) {
                            val remain = account.optDouble("credits_remaining")
                            val total = account.optDouble("credits_total")
                            val ratio = if (total > 0) (remain / total).toFloat().coerceIn(0f, 1f) else 0f
                            Spacer(Modifier.height(PcTokens.RowGap / 2))
                            LinearProgressIndicator(progress = ratio, modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(PcTokens.RowGap / 2))
                        PcInfoRow("版本", account.optString("region_label", "国内版"))
                        PcMonoRow("UID", account.optString("uid"))
                    }
                }
                Spacer(Modifier.height(PcTokens.SectionGap))
                PcWideButton(text = "关闭") { onDismiss() }
            }
        }
    )
}

/** 复制到剪贴板（Compose 没有内置 API，走系统服务）。 */
internal fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("WorkBuddy2API", text))
}
