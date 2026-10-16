package com.joy4fire.workbuddy2api.ui

import android.content.Context
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import com.joy4fire.workbuddy2api.FormatKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 调用记录页。
 *
 * 【重写要点】
 *   上一版每条记录一张卡、卡内 7 行键值（扣费账号/应用/协议/Tokens/积分/耗时/错误）
 *   + 2 个按钮。100 条记录 = 100 张卡 + 700 行文本，等于把整张数据库表铺在屏幕上，
 *   滚动与内存都很吃力，而且用户真正要看的只有三列：**时间 / 模型 / 谁扣了多少分**。
 *
 *   现在改成一张卡内的紧凑列表，每行三行文本：
 *     ① 状态 + 时间 + 模型名
 *     ② 扣费账号 · 应用 · 协议
 *     ③ Tokens / 积分 / 耗时
 *   点整行打开详情弹窗（其中才有"查看完整内容"与"删除"）。
 *
 * 字段契约（与 NativeStore.usageRecent 的 light 列一致）：
 *   id / ts / model / protocol / account_uid / input_tokens / output_tokens /
 *   total_tokens / latency_ms / status / error / credits / app_name
 */
@Composable
fun RecordsScreen(context: Context, app: AppState) {
    val records = app.usageRecords
    val scope = rememberCoroutineScope()
    var statusFilter by remember { mutableStateOf(0) } // 0=全部 1=成功 2=失败
    var detailId by remember { mutableStateOf<Long?>(null) }
    var detailMeta by remember { mutableStateOf("") }
    var detailBody by remember { mutableStateOf("读取中…") }
    var pendingDelete by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    // 进入页面自动拉最新记录：此前只在 refreshAll 与删除后加载，
    // 新产生的记录必须手动点一次刷新才显示。配合 loadUsageIfStale 的短窗口去重，
    // 既保证"刚调用完就能看到"，又避免来回切 Tab 时重复查询。
    LaunchedEffect(Unit) { app.loadUsageIfStale() }

    // uid → 「昵称 · 短UID」映射。多账号下这是判断"谁在扣分"的唯一可读线索。
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
        (0 until records.length()).mapNotNull { records.optJSONObject(it) }
            .filter {
                when (statusFilter) {
                    1 -> it.optString("status", "ok") == "ok"
                    2 -> it.optString("status", "ok") != "ok"
                    else -> true
                }
            }
    }

    val totalCredits = filtered.sumOf { it.optDouble("credits", 0.0) }
    val totalTokens = filtered.sumOf { it.optLong("total_tokens") }

    PcList {
        // ---------------- 汇总 + 筛选 ----------------
        item {
            PcCard(title = "汇总（当前筛选）") {
                PcMetricRow(
                    listOf(
                        PcMetric("记录条数", "${filtered.size} / ${app.usageTotal}"),
                        PcMetric("消耗积分", "%.2f".format(totalCredits)),
                        PcMetric("消耗 Tokens", FormatKit.tokens(totalTokens))
                    )
                )
                Spacer(Modifier.height(PcTokens.SectionGap))
                TabRow(
                    tabs = listOf("全部", "成功", "失败"),
                    selectedTabIndex = statusFilter,
                    onTabSelected = { statusFilter = it }
                )
                Spacer(Modifier.height(PcTokens.SectionGap))
                PcActionGrid(
                    actions = listOf(
                        PcAction("刷新", primary = true, enabled = !app.busy) { app.loadUsage() },
                        PcAction("清空全部", enabled = records.length() > 0) { showClearConfirm = true }
                    )
                )
                Spacer(Modifier.height(PcTokens.RowGap))
                PcNote("列表只加载元信息（不含对话正文）；点开某条时才按需读取完整输入与输出。")
            }
        }

        // ---------------- 记录列表 ----------------
        if (filtered.isEmpty()) {
            item {
                PcEmptyCard(
                    if (records.length() == 0) "暂无调用记录。用任一 API Key 调用本地网关后，记录会出现在这里。"
                    else "当前筛选条件下没有记录。"
                )
            }
        } else {
            item { PcSection("最近 ${filtered.size} 条") }
            item {
                // 一张卡承载全部记录行：100 条记录从"100 张卡片"变成"1 张卡片内 100 行"，
                // 布局层级少一个数量级，滚动不再抖。
                PcCard {
                    filtered.forEachIndexed { index, item ->
                        if (index > 0) {
                            Spacer(Modifier.height(PcTokens.RowGap))
                            PcDivider()
                            Spacer(Modifier.height(PcTokens.RowGap))
                        }
                        UsageRecordRow(
                            item = item,
                            accountLabel = nameByUid[item.optString("account_uid")],
                            onClick = {
                                detailId = item.optLong("id")
                                detailMeta = "#${item.optLong("id")} · ${item.optString("model", "auto")}"
                                detailBody = "读取中…"
                                scope.launch {
                                    val id = item.optLong("id")
                                    detailBody = withContext(Dispatchers.IO) { app.loadUsageDetail(id) }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // ---------------- 单条详情 ----------------
    detailId?.let {
        RecordDetailDialog(
            title = detailMeta,
            body = detailBody,
            onCopy = {
                copyToClipboard(context, detailBody)
                app.showMessage("已复制该条内容")
            },
            onDelete = {
                // detailId 是 Long?（弹窗随时可能被关掉），而 pendingDelete 只在这条记录
                // 确实还在时设置；用局部非空 id，避免把可空性漏进待删状态里。
                detailId?.let { pendingDelete = it to detailMeta }
            },
            onDismiss = { detailId = null }
        )
    }

    PcConfirmDialog(
        show = pendingDelete != null,
        title = "删除这条记录？",
        message = "${pendingDelete?.second}\n\n该记录包含完整请求与响应原文，删除后无法恢复。",
        confirmText = "删除",
        danger = true,
        onConfirm = {
            pendingDelete?.let { app.deleteUsage(it.first) }
            pendingDelete = null
            detailId = null
        },
        onDismiss = { pendingDelete = null }
    )

    PcConfirmDialog(
        show = showClearConfirm,
        title = "清空全部记录？",
        message = "将永久删除全部 ${app.usageTotal} 条记录（含输入/输出原文），无法恢复。",
        confirmText = "清空",
        danger = true,
        onConfirm = {
            app.clearUsage(app.usageTotal)
            showClearConfirm = false
        },
        onDismiss = { showClearConfirm = false }
    )
}

/**
 * 一条记录的三行紧凑表示。
 *
 * 为什么把"错误信息"放在第三行而不是单独一行红色文字：
 * 失败的记录本来就少，把错误塞进第三行可以让成功记录的行高保持一致，
 * 列表看起来均匀、可扫读；而失败行因为行尾带红色错误摘要，依然一眼可辨。
 */
@Composable
private fun UsageRecordRow(
    item: org.json.JSONObject,
    accountLabel: String?,
    onClick: () -> Unit
) {
    val id = item.optLong("id")
    val ok = item.optString("status", "ok") == "ok"
    val uid = item.optString("account_uid")
    val credits = item.optDouble("credits", 0.0)
    val error = item.optString("error")
    val time = remember(id) {
        runCatching {
            java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date((item.optDouble("ts", 0.0) * 1000).toLong()))
        }.getOrDefault("")
    }

    top.yukonga.miuix.kmp.basic.Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        insideMargin = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 0.dp,
            vertical = PcTokens.RowGap / 2
        )
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (ok) "✓" else "✗",
                    style = MiuixTheme.textStyles.body2,
                    color = if (ok) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.error
                )
                Spacer(Modifier.height(0.dp))
                Text(
                    "  $time",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(0.dp))
                Text(
                    "  ${item.optString("model", "auto")}",
                    style = MiuixTheme.textStyles.main,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text("#$id", style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    append("扣费账号 ")
                    append(accountLabel ?: if (uid.isBlank()) "未记录" else FormatKit.shortUid(uid))
                    append(" · 应用 ")
                    append(item.optString("app_name").ifBlank { "未命名" })
                    append(" · ")
                    append(item.optString("protocol"))
                },
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    append("输入 ${FormatKit.tokens(item.optLong("input_tokens"))}")
                    append(" · 输出 ${FormatKit.tokens(item.optLong("output_tokens"))}")
                    append(" · 合计 ${FormatKit.tokens(item.optLong("total_tokens"))}")
                    append(" · %.2f 积分".format(credits))
                    append(" · ${item.optLong("latency_ms")} ms")
                } + if (error.isNotBlank()) " · $error" else "",
                style = MiuixTheme.textStyles.body2,
                color = if (error.isNotBlank()) MiuixTheme.colorScheme.error
                else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 单条记录详情弹窗：正文（完整输入/输出/思考链）+ 复制 + 删除。
 *
 * 正文在这里是「用户主动要看的」，因此限高比普通弹窗高，且不裁剪显示
 * （PcDetailDialog 会裁剪到 12000 字符并说明已裁剪量；单条记录正文上限各 32 万字符，
 * 全量渲染会直接触发渲染超时闪退，这个上限是必需的）。
 */
@Composable
private fun RecordDetailDialog(
    title: String,
    body: String,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    top.yukonga.miuix.kmp.window.WindowDialog(
        show = true,
        title = title,
        onDismissRequest = onDismiss,
        content = {
            Column(Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = PcTokens.DetailBodyMax)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = body,
                        style = MiuixTheme.textStyles.body2,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
                Spacer(Modifier.height(PcTokens.SectionGap))
                PcActionGrid(
                    actions = listOf(
                        PcAction("复制全部", primary = true) { onCopy() },
                        PcAction("关闭") { onDismiss() }
                    )
                )
                Spacer(Modifier.height(PcTokens.ButtonGap))
                top.yukonga.miuix.kmp.basic.TextButton(
                    text = "删除这条记录",
                    onClick = { onDelete() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColors(
                        color = MiuixTheme.colorScheme.errorContainer,
                        textColor = MiuixTheme.colorScheme.error
                    )
                )
            }
        }
    )
}
