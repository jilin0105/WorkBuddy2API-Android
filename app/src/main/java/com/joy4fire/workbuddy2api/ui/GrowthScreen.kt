package com.joy4fire.workbuddy2api.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.joy4fire.workbuddy2api.GrowthFacade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 成长中心页 —— 对应 1.2.7 的「成长任务」界面。
 *
 * 关于列表 key：最初用 `key = { it.code }` 导致
 *   IllegalArgumentException: Key "" was already used
 * 因为上游可能返回重复 task_code 或空 code。改成「索引 + code」组合，
 * 保证唯一且稳定（索引在刷新前后对同一位置的项保持不变，不会引起无谓重排）。
 */
@Composable
fun GrowthScreen(context: Context, accountKey: String?) {
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf<JSONObject?>(null) }
    var tasks by remember { mutableStateOf<List<GrowthTaskRow>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        val key = accountKey ?: return
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val s = GrowthFacade.summary(context, key)
                    val list = GrowthFacade.list(context, key).mapIndexed { index, item ->
                        GrowthTaskRow(
                            // 组合 key：索引保证唯一，code 保证可读性
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
                onSuccess = { (s, list) -> summary = s; tasks = list; error = null },
                onFailure = { error = it.message ?: "加载失败" }
            )
        }
    }

    LaunchedEffect(accountKey) {
        logs = emptyList()
        statusText = ""
        refresh()
    }

    if (accountKey == null) {
        PcCard(title = "成长中心") {
            Text(
                "请先在「账号」页登录一个账号。成长任务与每日福利都按账号结算。",
                style = MiuixTheme.textStyles.main
            )
        }
        return
    }

    /** 统一的任务执行入口，避免三处重复写协程与错误处理。 */
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
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = PcTokens.SpaceM, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(PcTokens.CardGap)
    ) {
        // ---------------- 今日可领 ----------------
        item {
            PcCard(title = "今日可领") {
                val s = summary
                when {
                    s == null && error == null -> Text("加载中…", style = MiuixTheme.textStyles.main)
                    s == null -> Text("加载失败：$error", style = MiuixTheme.textStyles.main,
                        color = MiuixTheme.colorScheme.error)
                    s.optBoolean("international") -> Text(
                        "国际版无成长体系（只有每日额度与试用加油包）。",
                        style = MiuixTheme.textStyles.main
                    )
                    else -> {
                        val arr = s.optJSONArray("claimable")
                        if (arr == null || arr.length() == 0) {
                            Text("当前没有可领项，下方列表可执行任务。",
                                style = MiuixTheme.textStyles.main)
                        } else {
                            for (i in 0 until arr.length()) {
                                Text("· ${arr.optString(i)}", style = MiuixTheme.textStyles.main)
                            }
                        }
                        s.optJSONArray("errors")?.takeIf { it.length() > 0 }?.let { errs ->
                            Spacer(Modifier.height(PcTokens.SpaceS))
                            for (i in 0 until errs.length()) {
                                Text("⚠ ${errs.optString(i)}", style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                        }
                    }
                }
            }
        }

        // ---------------- 操作 ----------------
        item {
            PcCard(title = "一键操作") {
                Button(
                    onClick = {
                        run("准备中…") {
                            val results = GrowthFacade.runAll(context, accountKey, reporter = { msg: String ->
                                statusText = msg
                            })
                            logs = flattenResults(results)
                            "全部执行完成"
                        }
                    },
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (running) "执行中…" else "一键做完所有任务") }
                Spacer(Modifier.height(PcTokens.SpaceM))
                // 统一按钮组：等宽、有固定间距、自动折行（此前四个按钮挤成两排且贴在一起）。
                PcActionGrid(
                    actions = listOf(
                        PcAction("刷新", enabled = !running) { refresh() },
                        PcAction("领每日福利", enabled = !running) {
                            run("领取每日福利…") {
                                val r = GrowthFacade.claimDaily(context, accountKey) { statusText = it }
                                "礼包 ${r.optLong("gift")} / 补偿 ${r.optLong("compensation")} / 抽奖 ${r.optInt("draws")} 次"
                            }
                        },
                        PcAction("猫猫", enabled = !running) {
                            run("猫猫…") { GrowthFacade.runBuddy(context, accountKey) { statusText = it }; "猫猫完成" }
                        },
                        PcAction("开学季", enabled = !running) {
                            run("开学季…") {
                                val r = GrowthFacade.runSchool(context, accountKey) { statusText = it }
                                if (r.optBoolean("skipped")) r.optString("message")
                                else "开学季完成（抽奖 ${r.optInt("draws")} 次）"
                            }
                        }
                    )
                )
                if (statusText.isNotEmpty()) {
                    Spacer(Modifier.height(PcTokens.SpaceM))
                    Text(statusText, style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                if (logs.isNotEmpty()) {
                    Spacer(Modifier.height(PcTokens.SpaceS))
                    logs.takeLast(12).forEach { line ->
                        Text(line, style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        // ---------------- 任务清单 ----------------
        if (tasks.isNotEmpty()) {
            item { SmallTitle(text = "成长任务（${tasks.size}）") }
            items(tasks.size, key = { tasks[it].id }) { index ->
                GrowthTaskCard(
                    task = tasks[index],
                    onClaim = {
                        run("领取 ${tasks[index].code}…") {
                            val (credit, _) = GrowthFacade.claim(context, accountKey, tasks[index].code)
                            if (credit == 0L) "该奖励已领取过" else "已领 $credit 分"
                        }
                    },
                    onRun = {
                        run("${tasks[index].code}…") {
                            GrowthFacade.runOne(context, accountKey, tasks[index].code) { statusText = it }
                                .optString("message", "完成")
                        }
                    }
                )
            }
        }
    }
}

/** 单个任务卡：标题、进度、奖励、动作按钮。 */
@Composable
private fun GrowthTaskCard(task: GrowthTaskRow, onClaim: () -> Unit, onRun: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PcTokens.PageGutter),
        insideMargin = PaddingValues(horizontal = PcTokens.SpaceL, vertical = PcTokens.SpaceM)
    ) {
        Column {
            Text(task.title.ifBlank { task.code }, style = MiuixTheme.textStyles.main)
            Spacer(Modifier.height(2.dp))
            Text(
                task.code,
                style = MiuixTheme.textStyles.body2,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            if (task.description.isNotBlank()) {
                Spacer(Modifier.height(PcTokens.SpaceS / 2))
                Text(
                    task.description,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(PcTokens.SpaceS))
            PcInfoRow("进度", task.progress)
            PcInfoRow("奖励", task.rewardText)
            // 只有上游确实给了进度指标（target>0）才画进度条；
            // 这些任务大多没有可查询进度，恒显示 0% 反而误导。
            if (task.hasProgress && !task.claimed) {
                Spacer(Modifier.height(PcTokens.SpaceS))
                LinearProgressIndicator(progress = task.ratio, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(PcTokens.SpaceS))
            when {
                task.claimed -> Text(
                    "已领取",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                task.claimable -> Button(onClick = onClaim, modifier = Modifier.fillMaxWidth()) { Text("领取奖励") }
                else -> TextButton(text = "执行任务", onClick = onRun, modifier = Modifier.fillMaxWidth())
            }
        }
    }
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

/** 把 runAll 的结果拍成日志行。 */
private fun flattenResults(results: JSONArray): List<String> = buildList {
    for (i in 0 until results.length()) {
        val item = results.optJSONObject(i) ?: continue
        val mark = when (item.optString("status")) {
            "done" -> "✓"
            "skipped" -> "·"
            "error" -> "✗"
            else -> "?"
        }
        add("$mark ${item.optString("task_code")}：${item.optString("message")}")
    }
}
