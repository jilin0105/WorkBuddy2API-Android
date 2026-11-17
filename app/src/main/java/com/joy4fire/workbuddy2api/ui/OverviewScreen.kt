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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.joy4fire.workbuddy2api.ApiHostService
import com.joy4fire.workbuddy2api.FormatKit
import com.joy4fire.workbuddy2api.ServerPort
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Surface
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
    // 端口可在设置页改，运行时可变：这里随重组重读，保证改完后概览页显示的地址立即跟上。
    val currentPort = ServerPort.current(context)
    var showEvents by remember { mutableStateOf(false) }
    var showAccountDetail by remember { mutableStateOf(false) }
    // 网关端口在概览页直接可改：这里是编辑弹窗的开关。
    var showPortEditor by remember { mutableStateOf(false) }

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
                // 点击整行即可改端口：网关地址里的端口是用户最常想改的东西，
                // 把它藏在设置页会让"想换端口"多跳两层。
                Surface(
                    onClick = { showPortEditor = true },
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            ServerPort.baseUrl(currentPort),
                            style = MiuixTheme.textStyles.body2,
                            fontFamily = FontFamily.Monospace,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "修改",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.primary
                        )
                    }
                }

                // 端口改完不会立即生效，这里如实暴露"保存值 ≠ 监听值"的中间态，
                // 否则用户改完看到地址变了，却连不上，会以为功能坏了。
                val listeningPort = ApiHostService.listeningPort
                if (!running) {
                    Spacer(Modifier.height(PcTokens.RowGap))
                    PcNote("端口 $currentPort 已保存，启动服务后生效。")
                } else if (listeningPort > 0 && listeningPort != currentPort) {
                    Spacer(Modifier.height(PcTokens.RowGap))
                    PcNote("端口已改为 $currentPort，重启服务后生效（当前仍监听 $listeningPort）。")
                }

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
                            copyToClipboard(context, ServerPort.baseUrl(currentPort))
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
            // 注意：这里刻意不再提供「立即启动服务」待办。
            // 顶部服务卡的主按钮在 stopped 状态下就是「启动服务」，且始终可见；
            // 若这里再放一个同 action 的按钮，未启动 + 有账号时两者会同时出现，
            // 表现为「启动功能有两个」。同一个动作只保留一个入口。
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

    if (showPortEditor) {
        // 概览页直接改端口：用户在这里看到网关地址，想改端口自然也在同一处动手，
        // 不需要先跳到设置页再找一遍。校验与「改完要重启」的提示复用 SettingsScreen 里
        // PortSettingRow 的同一套 ServerPort 规则，避免两个入口两套判断。
        val saved = remember(app.revision) { ServerPort.current(context) }
        val live = if (app.serviceRunning) ApiHostService.listeningPort else 0
        // 输入值只在弹窗打开期间有效：打开时以"当前保存值"为初值，
        // 但之后与 app.revision 脱钩，否则后台刷新会把用户正在敲的数字冲掉。
        var input by remember(showPortEditor) { mutableStateOf(saved.toString()) }
        val parsed = ServerPort.normalize(input)
        val invalid = parsed == null
        val unchanged = parsed == saved
        PcInputDialog(
            show = true,
            title = "本地端口",
            label = "端口（1024 - 65535）",
            value = input,
            onValueChange = { input = it },
            confirmText = "保存",
            errorText = when {
                invalid -> "请输入 1024 - 65535 之间的数字（1024 以下为系统端口，普通应用无法监听）"
                unchanged -> "与当前保存值相同，无需修改"
                else -> null
            },
            hint = when {
                live != 0 && parsed != null && parsed != live ->
                    "保存后地址显示为 $parsed，需重启服务才会在新端口监听（当前仍监听 $live）。"
                else -> "保存后需重启服务才会生效。"
            },
            onConfirm = {
                // 校验在 onConfirm 里再做一次：输入框是自由文本，
                // 只要 PcInputDialog 不给 disabled 能力，就必须在提交口兜住非法值。
                if (invalid) {
                    app.showMessage("端口需为 1024 - 65535 之间的数字")
                } else if (ServerPort.set(context, input)) {
                    app.showMessage("端口已保存为 ${ServerPort.current(context)}，重启服务后生效")
                    showPortEditor = false
                } else {
                    app.showMessage("保存失败：端口不在 1024 - 65535 范围内")
                }
            },
            onDismiss = { showPortEditor = false }
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
    // 返回手势/返回键关窗：Miuix 的 WindowDialog 不注册返回处理，
    // AppShell 的 BackHandler 会先消费返回事件，TalkBack 下又点不到空白区。
    RegisterDialogBack { onDismiss() }
    PcDialog(
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
