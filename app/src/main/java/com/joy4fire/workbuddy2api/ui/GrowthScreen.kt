package com.joy4fire.workbuddy2api.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.joy4fire.workbuddy2api.GrowthFacade
import com.joy4fire.workbuddy2api.FormatKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 成长中心页（移植自 WorkBuddy 1.2.7 的成长任务体系）。
 *
 * 【本次重写的三处修正】
 *   ① 原来的任务卡是「标题 + code + 描述 + 进度 + 奖励 + 按钮」六段竖排，
 *      每个任务占掉近半屏；现在压成「标题 + 一行元信息 + 一个动作按钮」，
 *      奖励与进度并到同一行（都用小字），一屏能看 5~6 个任务。
 *   ② 「一键做完所有任务」原来是四个等宽按钮平铺，其中「一键做完」与
 *      「领每日福利」「猫猫」「开学季」权重完全不同，混在一起会让用户误以为
 *      它们是并列的四件事。现在主按钮独占一行，子任务收进「更多操作」。
 *   ③ 执行日志从"卡内十几行灰字"改为执行完成后弹窗展示，并把积分汇总单独高亮 ——
 *      用户最关心的是"到底加了多少分"，不该埋在日志里。
 *
 * 关于列表 key：最初用 `key = { it.code }` 触发过
 *   IllegalArgumentException: Key "" was already used
 * 因为上游可能返回重复或空 task_code。改用「索引 + code」组合，保证唯一且稳定。
 */
@Composable
fun GrowthScreen(context: Context, app: AppState) {
    val scope = rememberCoroutineScope()
    val accountKey = app.selectedAccount
    var summary by remember { mutableStateOf<JSONObject?>(null) }
    var tasks by remember { mutableStateOf<List<GrowthTaskRow>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }
    var showLogs by remember { mutableStateOf(false) }
    var showActions by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        val key = accountKey ?: return
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val s = GrowthFacade.summary(context, key)
                    val list = GrowthFacade.list(context, key).mapIndexed { index, item ->
                        GrowthTaskRow(
                            id = "$index:${item.code}",
                            code = item.code,
                            title = item.title,
                            description = item.description,
                            progress = item.progressText,
                            rewardText = item.rewardText,
                            claimable = item.claimable,
                            claimed = item.claimed,
                            credit = item.credit,
                            energy = item.energy,
                            ratio = if (item.target > 0) {
                                (item.current.toFloat() / item.target).coerceIn(0f, 1f)
                            } else if (item.claimed) 1f else 0f,
                            hasProgress = item.target > 0
                        )
                    }
                    s to list
                }
            }
            result.fold(
                onSuccess = { (s, list) ->
                    summary = s
                    tasks = list
                    error = null
                },
                onFailure = { error = it.message ?: "加载失败" }
            )
        }
    }

    LaunchedEffect(accountKey) {
        logs = emptyList()
        statusText = ""
        refresh()
    }

    /** 统一的任务执行入口：避免三处重复写协程与错误处理。 */
    fun run(action: String, block: suspend () -> String) {
        if (running) return
        running = true
        statusText = action
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { block() } }
            result.fold(
                onSuccess = { statusText = it },
                onFailure = { statusText = "失败：${it.message ?: it.javaClass.simpleName}" }
            )
            running = false
            refresh()
            // 执行结束自动弹日志：用户点了按钮就该立刻看到发生了什么，
            // 不必再去翻页面（上一版日志在卡内，长任务跑完还要往上翻才能看）。
            if (logs.isNotEmpty()) showLogs = true
        }
    }

    if (accountKey == null) {
        PcList {
            item { PcEmptyCard("请先在「账号」页登录一个账号。成长任务与每日福利都按账号结算。") }
        }
        return
    }

    PcList {
        // ---------------- 今日可领 ----------------
        item {
            PcCard(title = "今日可领") {
                val s = summary
                when {
                    s == null && error == null -> Text("加载中…", style = MiuixTheme.textStyles.main)
                    s == null -> PcErrorText("加载失败：$error")
                    else -> {
                        val arr = s.optJSONArray("claimable")
                        if (arr == null || arr.length() == 0) {
                            Text("当前没有可领项，下方列表可逐个执行任务。",
                                style = MiuixTheme.textStyles.main)
                        } else {
                            // JSONArray 不是 Kotlin 集合，没有 forEachIndexed；
                            // 用下标循环（Compose 允许在 @Composable 里写普通 for，
                            // 因为每次重组都会重新执行这段代码）。
                            for (i in 0 until arr.length()) {
                                Text("· ${arr.optString(i)}", style = MiuixTheme.textStyles.main)
                            }
                        }
                        val errs = s.optJSONArray("errors")
                        if (errs != null && errs.length() > 0) {
                            Spacer(Modifier.height(PcTokens.RowGap))
                            for (i in 0 until errs.length()) {
                                PcNote("⚠ ${errs.optString(i)}")
                            }
                        }
                    }
                }
            }
        }

        // ---------------- 一键操作 ----------------
        item {
            PcCard(title = "一键操作") {
                PcWideButton(
                    text = if (running) "执行中…" else "一键做完所有任务",
                    enabled = !running
                ) {
                    run("准备中…") {
                        val results = GrowthFacade.runAll(context, accountKey, reporter = { msg: String ->
                            statusText = msg
                        })
                        logs = flattenResults(results)
                        "全部执行完成"
                    }
                }
                Spacer(Modifier.height(PcTokens.ButtonGap))
                PcWideButton(text = "更多操作（福利 / 猫猫 / 开学季）", primary = false) { showActions = true }
                if (statusText.isNotEmpty()) {
                    Spacer(Modifier.height(PcTokens.SectionGap))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PcNote(statusText)
                    }
                    if (logs.isNotEmpty()) {
                        Spacer(Modifier.height(PcTokens.RowGap))
                        PcWideButton(text = "查看执行日志", primary = false) { showLogs = true }
                    }
                }
                Spacer(Modifier.height(PcTokens.SectionGap))
                PcNote("执行链会发多次上游请求，单个任务通常 5~25 秒；期间请保持应用在前台。")
            }
        }

        // ---------------- 成长任务 ----------------
        if (tasks.isNotEmpty()) {
            item { PcSection("成长任务（${tasks.size}）") }
            item {
                // 一张卡承载全部任务行：任务通常十几个，一卡一行比一卡一任务少一个
                // 数量级的布局层级，滚动更稳，也更像"清单"而不是"卡片墙"。
                PcCard {
                    tasks.forEachIndexed { index, task ->
                        if (index > 0) {
                            Spacer(Modifier.height(PcTokens.RowGap))
                            PcDivider()
                            Spacer(Modifier.height(PcTokens.RowGap))
                        }
                        GrowthTaskRowView(
                            task = task,
                            enabled = !running,
                            onClaim = {
                                run("领取 ${task.code}…") {
                                    GrowthFacade.claimDetailed(context, accountKey, task.code).describe()
                                }
                            },
                            onRun = {
                                run("${task.code}…") {
                                    GrowthFacade.runOne(context, accountKey, task.code) { statusText = it }
                                        .optString("message", "完成")
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // ---------------- 更多操作 ----------------
    if (showActions) {
        GrowthActionsDialog(
            onRunDaily = {
                showActions = false
                run("领取每日福利…") {
                    val r = GrowthFacade.claimDaily(context, accountKey) { statusText = it }
                    "礼包 ${r.optLong("gift")} / 补偿 ${r.optLong("compensation")} / 抽奖 ${r.optInt("draws")} 次"
                }
            },
            onRunBuddy = {
                showActions = false
                run("猫猫…") {
                    GrowthFacade.runBuddy(context, accountKey) { statusText = it }
                    "猫猫完成"
                }
            },
            onRunSchool = {
                showActions = false
                run("开学季…") {
                    val r = GrowthFacade.runSchool(context, accountKey) { statusText = it }
                    if (r.optBoolean("skipped")) r.optString("message")
                    else "开学季完成（抽奖 ${r.optInt("draws")} 次）"
                }
            },
            onLottery = {
                showActions = false
                run("抽奖…") {
                    val r = GrowthFacade.lottery(context, accountKey) { statusText = it }
                    "抽奖完成：本次抽了 ${r.optInt("draws")} 次"
                }
            },
            onRefresh = {
                showActions = false
                refresh()
            },
            onDismiss = { showActions = false }
        )
    }

    // ---------------- 执行日志 ----------------
    if (showLogs) {
        PcLogDialog(
            show = true,
            title = "执行日志",
            lines = logs,
            onDismiss = { showLogs = false }
        )
    }
}

/**
 * 单个任务行：标题 + 元信息一行 + 动作。
 *
 * 元信息把「进度」和「奖励」合并到一行，用 " · " 分隔：
 * 这两项都是短字符串，各占一行会让每个任务多出 30dp 高度，而信息量没变。
 */
@Composable
private fun GrowthTaskRowView(
    task: GrowthTaskRow,
    enabled: Boolean,
    onClaim: () -> Unit,
    onRun: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                task.title.ifBlank { task.code },
                style = MiuixTheme.textStyles.main,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (task.claimed) {
                PcStatus("已领取", true)
            } else if (task.claimable) {
                PcStatus("可领取", true)
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            task.code,
            style = MiuixTheme.textStyles.body2,
            fontFamily = FontFamily.Monospace,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (task.description.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                task.description,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(PcTokens.RowGap / 2))
        Text(
            "进度 ${task.progress} · 奖励 ${task.rewardText}",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        // 只有上游确实给了进度指标（target>0）才画进度条；
        // 这些任务大多没有可查询进度，恒显示 0% 反而误导。
        if (task.hasProgress && !task.claimed) {
            Spacer(Modifier.height(PcTokens.RowGap / 2))
            LinearProgressIndicator(progress = task.ratio, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(PcTokens.RowGap))
        when {
            task.claimed -> Unit // 已领取：不给按钮，避免用户反复点
            task.claimable -> PcWideButton(text = "领取奖励", enabled = enabled) { onClaim() }
            else -> PcWideButton(text = "执行任务", primary = false, enabled = enabled) { onRun() }
        }
    }
}

/**
 * 「更多操作」弹窗 —— 把权重不对等的四件事从主界面挪走。
 *
 * 顺序有讲究：福利类（会真的发积分）在前，娱乐类（猫猫/抽奖）在后，
 * 刷新放最后 —— 用户打开这个弹窗的动机通常是"想拿分"，不是"想刷新"。
 */
@Composable
private fun GrowthActionsDialog(
    onRunDaily: () -> Unit,
    onRunBuddy: () -> Unit,
    onRunSchool: () -> Unit,
    onLottery: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    top.yukonga.miuix.kmp.window.WindowDialog(
        show = true,
        title = "更多操作",
        onDismissRequest = onDismiss,
        content = {
            Column(Modifier.fillMaxWidth()) {
                PcWideButton(text = "领每日福利", primary = true) { onRunDaily() }
                Spacer(Modifier.height(PcTokens.ButtonGap))
                PcActionGrid(
                    actions = listOf(
                        PcAction("开学季") { onRunSchool() },
                        PcAction("猫猫") { onRunBuddy() },
                        PcAction("抽奖") { onLottery() },
                        PcAction("刷新任务列表") { onRefresh() }
                    )
                )
                Spacer(Modifier.height(PcTokens.SectionGap))
                PcNote("这些入口原本与「一键做完」并列，导致主次不分；现在收在这里，一键做完仍是默认路径。")
            }
        }
    )
}

/** 任务行的 UI 模型。 */
private data class GrowthTaskRow(
    val id: String,
    val code: String,
    val title: String,
    val description: String,
    val progress: String,
    val rewardText: String,
    val claimable: Boolean,
    val claimed: Boolean,
    val credit: Long,
    val energy: Long,
    val ratio: Float,
    val hasProgress: Boolean
)

/** 把 runAll 的结果拍成日志行（首字符是符号，日志弹窗会按符号着色）。 */
private fun flattenResults(results: JSONArray): List<String> = buildList {
    for (i in 0 until results.length()) {
        val item = results.optJSONObject(i) ?: continue
        val mark = when (item.optString("status")) {
            "done" -> "✓"
            "claimed" -> "★"
            "skipped" -> "·"
            "unmapped" -> "△"
            "error" -> "✗"
            else -> "?"
        }
        add("$mark ${item.optString("task_code")}：${item.optString("message")}")
    }
}
