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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.joy4fire.workbuddy2api.AccountRegion
import com.joy4fire.workbuddy2api.FormatKit
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 记录页 —— 对应原界面 recordsPage()。
 *
 * 本次补齐的关键信息（原纯 View 版与初版 Compose 版都没显示）：
 *   · 扣费账号：数据库早就存了 account_uid，但界面从未展示，导致多账号下
 *     无法判断"这次请求是哪个号在扣积分"。这里把 uid 映射成「昵称（短UID）」，
 *     并按版本标注，一眼可辨。
 *   · 本次消耗积分 credits：同表已存，此前也没显示。
 *
 * 字段契约（与 NativeStore.usageRecent 的 light 列一致）：
 *   id / ts / model / protocol / account_uid / input_tokens / output_tokens /
 *   total_tokens / latency_ms / status / error / credits / app_name
 */
@Composable
fun RecordsScreen(context: Context, app: AppState) {
    val records = app.usageRecords
    var pendingDelete by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var statusFilter by remember { mutableStateOf(0) } // 0=全部 1=成功 2=失败

    // 进入记录页自动拉取最新记录。
    // 此前只在 refreshAll() 与删除操作后加载，导致新产生的调用记录
    // 必须手动点一次刷新才显示；这里改成每次进入页面（含从其它 Tab 切回）都检查一次，
    // 配合 loadUsageIfStale 的短窗口去重，兼顾实时性与无谓查询。
    LaunchedEffect(Unit) { app.loadUsageIfStale() }

    // uid → 「昵称 · 短UID」映射。多账号下这是"谁在扣分"的唯一可读线索。
    val nameByUid = remember(app.accounts, app.revision) {
        buildMap {
            for (i in 0 until app.accounts.length()) {
                val item = app.accounts.optJSONObject(i) ?: continue
                val uid = item.optString("uid")
                if (uid.isNotBlank()) {
                    put(uid, FormatKit.accountName(item.optString("nickname"), uid))
                }
            }
        }
    }

    val filtered = remember(records, statusFilter, app.revision) {
        (0 until records.length()).mapNotNull { records.optJSONObject(it) }.filter {
            when (statusFilter) {
                1 -> it.optString("status", "ok") == "ok"
                2 -> it.optString("status", "ok") != "ok"
                else -> true
            }
        }
    }

    // 汇总当前筛选结果的消耗，便于核对账户扣分是否对得上
    val totalCredits = filtered.sumOf { it.optDouble("credits", 0.0) }
    val totalTokens = filtered.sumOf { it.optLong("total_tokens") }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = PcTokens.SpaceM, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(PcTokens.CardGap)
    ) {
        item {
            PcCard(title = "API 调用记录") {
                PcInfoRow("总条数", app.usageTotal.toString())
                PcInfoRow("当前筛选", "${filtered.size} 条")
                PcInfoRow("消耗积分", "%.2f".format(totalCredits))
                PcInfoRow("消耗 Tokens", FormatKit.tokens(totalTokens))
                Spacer(Modifier.height(PcTokens.SpaceM))
                TabRow(
                    tabs = listOf("全部", "成功", "失败"),
                    selectedTabIndex = statusFilter,
                    onTabSelected = { statusFilter = it }
                )
                Spacer(Modifier.height(PcTokens.SpaceS))
                Text(
                    "逐条记录模型、应用、扣费账号、Tokens 与积分消耗。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                // 刷新是记录页最常用的动作（数据随时间增长），与清空并列成组。
                Spacer(Modifier.height(PcTokens.SpaceM))
                PcActionGrid(
                    actions = listOf(
                        PcAction("刷新", primary = true, enabled = !app.busy) { app.loadUsage() },
                        PcAction("清空全部记录", enabled = records.length() > 0) { showClearConfirm = true }
                    )
                )
            }
        }

        if (filtered.isEmpty()) {
            item {
                PcCard {
                    Text(
                        if (records.length() == 0) "暂无调用记录。使用任一应用 Key 调用本地网关后，记录会显示在这里。"
                        else "当前筛选条件下没有记录。",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        } else {
            item { SmallTitle(text = "最近 ${filtered.size} 条") }
            items(filtered.size) { index ->
                val item = filtered[index]
                UsageRecordCard(
                    item = item,
                    accountLabel = nameByUid[item.optString("account_uid")],
                    onDelete = {
                        pendingDelete = item.optLong("id") to item.optString("model", "auto")
                    }
                )
            }
        }
    }

    PcConfirmDialog(
        show = pendingDelete != null,
        title = "删除这条记录？",
        message = "#${pendingDelete?.first} · ${pendingDelete?.second}\n\n记录包含完整请求与响应原文，删除后无法恢复。",
        confirmText = "删除",
        onConfirm = {
            pendingDelete?.let { app.deleteUsage(it.first) }
            pendingDelete = null
        },
        onDismiss = { pendingDelete = null }
    )

    PcConfirmDialog(
        show = showClearConfirm,
        title = "清空全部记录？",
        message = "将永久删除全部 ${app.usageTotal} 条记录（含输入/输出原文），无法恢复。",
        confirmText = "清空",
        onConfirm = { app.clearUsage(app.usageTotal); showClearConfirm = false },
        onDismiss = { showClearConfirm = false }
    )
}

/** 单条记录卡：状态、模型、扣费账号、应用、Tokens、积分、延迟、错误。 */
@Composable
private fun UsageRecordCard(
    item: org.json.JSONObject,
    accountLabel: String?,
    onDelete: () -> Unit
) {
    val id = item.optLong("id")
    val ok = item.optString("status", "ok") == "ok"
    val uid = item.optString("account_uid")
    val credits = item.optDouble("credits", 0.0)
    val time = remember(id) {
        runCatching {
            java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date((item.optDouble("ts", 0.0) * 1000).toLong()))
        }.getOrDefault("")
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PcTokens.PageGutter),
        insideMargin = PaddingValues(horizontal = PcTokens.SpaceL, vertical = PcTokens.SpaceM)
    ) {
        Column {
            // 首行：状态 + 时间 + 序号
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    item.optString("status", "ok"),
                    style = MiuixTheme.textStyles.body2,
                    color = if (ok) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "$time · #$id",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
            Spacer(Modifier.height(PcTokens.SpaceS))
            Text(item.optString("model", "auto"), style = MiuixTheme.textStyles.main)
            Spacer(Modifier.height(PcTokens.SpaceS))

            // 核心信息：谁在扣分（账号）
            PcInfoRow("扣费账号", accountLabel ?: if (uid.isBlank()) "未记录" else FormatKit.shortUid(uid))
            PcInfoRow("应用", item.optString("app_name").ifBlank { "未命名" })
            PcInfoRow("协议", item.optString("protocol"))
            PcInfoRow(
                "Tokens",
                "输入 ${FormatKit.tokens(item.optLong("input_tokens"))} · " +
                    "输出 ${FormatKit.tokens(item.optLong("output_tokens"))} · " +
                    "合计 ${FormatKit.tokens(item.optLong("total_tokens"))}"
            )
            PcInfoRow("积分消耗", "%.2f".format(credits))
            PcInfoRow("耗时", "${item.optLong("latency_ms")} ms")

            if (item.optString("error").isNotBlank()) {
                Spacer(Modifier.height(PcTokens.SpaceS))
                Text(item.optString("error"), style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.error)
            }
            Spacer(Modifier.height(PcTokens.SpaceS))
            TextButton(text = "删除这条", onClick = onDelete, modifier = Modifier.fillMaxWidth())
        }
    }
}
