package com.joy4fire.workbuddy2api.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.joy4fire.workbuddy2api.FormatKit
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 积分详情弹窗 —— 回答"我的积分去哪了"。
 *
 * 【数据结构（来自 NativeCore.creditDetail，逐字段对齐，不臆造）】
 *   nickname / uid / region_label
 *   remaining / total / expire_at / packages[]        ← 上游实时快照（失败时为本地缓存）
 *   refresh_error                                     ← 上游刷新失败时的错误文案
 *   ledger_summary { today_gain, today_loss, total_gain, total_loss,
 *                    kinds { <kind>: { today, total, today_gain, today_loss,
 *                                     total_gain, total_loss } } }
 *   ledger[] { id, ts, kind, amount, note }            ← 逐笔明细，倒序
 *   last_checkin_date
 *
 * 【为什么必须把"上游快照"和"本地账本"分开呈现】
 *   上游【没有】积分流水接口（1.2.7 的全部 billing/meter 端点只有一个当前快照），
 *   所以"每日签到得了多少、奖励得了多少、调用消耗了多少"是本机在每次动作发生时
 *   自己记下来的。两者的口径必然存在差异（例如在 Web 端领的积分本机看不到），
 *   如果不说明，用户会以为账本漏记。
 *
 * 因此本弹窗做了三件事：
 *   ① 顶部给上游快照（剩余/总量/到期/资源包拆分）—— 这是权威值；
 *   ② 中部给本地账本汇总（今日/累计 的 获得/消耗）—— 这是来源拆解；
 *   ③ 底部逐笔明细 + 一句口径说明（差异会明确写出来，而不是掩饰）。
 */
@Composable
fun CreditDetailDialog(
    accountName: String,
    detail: JSONObject?,
    onRefresh: () -> Unit,
    onClearLedger: () -> Unit,
    onDismiss: () -> Unit
) {
    var confirmClear by remember { mutableStateOf(false) }

    top.yukonga.miuix.kmp.window.WindowDialog(
        show = true,
        title = "积分详情",
        summary = accountName,
        onDismissRequest = onDismiss,
        content = {
            Column(Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = PcTokens.DetailBodyMax)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (detail == null) {
                        // 上游刷新可能要几秒，先给出明确的"读取中"，而不是空白弹窗
                        Text("正在读取积分与账本…", style = MiuixTheme.textStyles.main)
                    } else {
                        CreditSnapshotSection(detail)
                        Spacer(Modifier.height(PcTokens.SectionGap))
                        PcDivider()
                        Spacer(Modifier.height(PcTokens.SectionGap))
                        CreditLedgerSummarySection(detail)
                        Spacer(Modifier.height(PcTokens.SectionGap))
                        PcDivider()
                        Spacer(Modifier.height(PcTokens.SectionGap))
                        CreditLedgerListSection(detail)
                    }
                }

                Spacer(Modifier.height(PcTokens.SectionGap))
                PcActionGrid(
                    actions = listOf(
                        PcAction("刷新", primary = true, enabled = detail != null) { onRefresh() },
                        PcAction("关闭") { onDismiss() }
                    )
                )
                Spacer(Modifier.height(PcTokens.ButtonGap))
                TextButton(
                    text = "清空本地账本",
                    onClick = { confirmClear = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColors(
                        color = MiuixTheme.colorScheme.errorContainer,
                        textColor = MiuixTheme.colorScheme.error
                    )
                )
                Spacer(Modifier.height(PcTokens.RowGap / 2))
                PcNote("清空只影响本机的来源拆解记录，不影响上游真实积分。")
            }
        }
    )

    PcConfirmDialog(
        show = confirmClear,
        title = "清空本地账本？",
        message = "将删除该账号在本机记录的积分流水（签到/奖励/消耗的逐笔明细）。\n\n" +
            "上游的真实积分不受影响，但清空后无法再区分积分来源。",
        confirmText = "清空",
        danger = true,
        onConfirm = {
            confirmClear = false
            onClearLedger()
        },
        onDismiss = { confirmClear = false }
    )
}

/** ① 上游快照：剩余 / 总量 / 进度 / 到期 / 资源包拆分。 */
@Composable
private fun CreditSnapshotSection(detail: JSONObject) {
    val remaining = detail.optDouble("remaining", 0.0)
    val total = detail.optDouble("total", 0.0)
    val ratio = if (total > 0) (remaining / total).toFloat().coerceIn(0f, 1f) else 0f
    val refreshError = detail.optString("refresh_error")

    PcSection("上游余额")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${FormatKit.credits(remaining)} / ${FormatKit.credits(total)}",
            style = MiuixTheme.textStyles.title3,
            color = MiuixTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "${(ratio * 100).toInt()}%",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
    }
    Spacer(Modifier.height(PcTokens.RowGap))
    LinearProgressIndicator(progress = ratio, modifier = Modifier.fillMaxWidth())

    if (refreshError.isNotBlank()) {
        // 刷新失败时数值来自本地缓存，必须把这件事说出来，
        // 否则用户会把过期数值当当前余额（这是会造成误判的信息）
        Spacer(Modifier.height(PcTokens.RowGap))
        PcErrorText("上游刷新失败：$refreshError（以下为上次成功的本地缓存值）")
    }

    Spacer(Modifier.height(PcTokens.RowGap))
    PcInfoRow("剩余积分", FormatKit.credits(remaining))
    PcInfoRow("积分总量", FormatKit.credits(total))
    PcInfoRow("到期时间", formatExpire(detail.opt("expire_at")))
    PcInfoRow("签到状态", if (detail.optString("last_checkin_date").isNotBlank()) {
        if (isCheckedInToday(detail.optString("last_checkin_date"))) "今日已签到"
        else "上次签到 ${detail.optString("last_checkin_date").take(10)}"
    } else "无签到记录")

    val packages = detail.optJSONArray("packages")
    if (packages != null && packages.length() > 0) {
        Spacer(Modifier.height(PcTokens.RowGap))
        PcNote("额度由 ${packages.length()} 笔发放构成：")
        for (i in 0 until packages.length()) {
            val pkg = packages.optJSONObject(i) ?: continue
            // 资源包字段名在不同版本间有差异，这里按已确认的键名读取，
            // 缺失的（如名称/到期）不显示而不是显示 "null"。
            val label = pkg.optString("name").ifBlank { "资源包 ${i + 1}" }
            val amount = when {
                pkg.has("amount") -> pkg.optDouble("amount")
                pkg.has("remain") -> pkg.optDouble("remain")
                else -> pkg.optDouble("total", 0.0)
            }
            val expire = formatExpire(pkg.opt("expire_at"), short = true)
            PcInfoRow(label, "${FormatKit.credits(amount)} 积分" + if (expire.isNotBlank()) " · $expire" else "")
        }
    }
}

/** ② 本地账本汇总：今日/累计 的 获得/消耗，并按来源分类。 */
@Composable
private fun CreditLedgerSummarySection(detail: JSONObject) {
    val summary = detail.optJSONObject("ledger_summary")
    PcSection("来源拆解（本机记录）")
    if (summary == null) {
        PcNote("暂无账本数据。")
        return
    }

    val todayGain = summary.optDouble("today_gain")
    val todayLoss = summary.optDouble("today_loss")
    val totalGain = summary.optDouble("total_gain")
    val totalLoss = summary.optDouble("total_loss")

    PcMetricRow(
        listOf(
            PcMetric("今日获得", "+%.1f".format(todayGain), MiuixTheme.colorScheme.primary),
            PcMetric("今日消耗", "-%.1f".format(todayLoss),
                if (todayLoss > 0) MiuixTheme.colorScheme.error else null),
            PcMetric("今日净额", "%+.1f".format(todayGain - todayLoss))
        )
    )
    Spacer(Modifier.height(PcTokens.RowGap))
    PcInfoRow("累计获得", "+%.1f 积分".format(totalGain))
    PcInfoRow("累计消耗", "-%.1f 积分".format(totalLoss))
    PcInfoRow("累计净额", "%+.1f 积分".format(totalGain - totalLoss))

    val kinds = summary.optJSONObject("kinds")
    if (kinds != null && kinds.length() > 0) {
        Spacer(Modifier.height(PcTokens.SectionGap))
        PcNote("按来源分类（今日 / 累计）：")
        kinds.keys().forEach { rawKind ->
            val item = kinds.optJSONObject(rawKind) ?: return@forEach
            val today = item.optDouble("today")
            val totalNet = item.optDouble("total")
            PcInfoRow(
                kindLabel(rawKind),
                "%+.1f / %+.1f 积分".format(today, totalNet)
            )
        }
    }

    if (!hasLedgerData(summary)) {
        Spacer(Modifier.height(PcTokens.RowGap))
        PcNote("本机账本还没有记录。开始使用（签到、调用）后这里会逐笔累积。")
    }
}

/** ③ 逐笔明细。 */
@Composable
private fun CreditLedgerListSection(detail: JSONObject) {
    val ledger = detail.optJSONArray("ledger")
    PcSection("逐笔明细")
    if (ledger == null || ledger.length() == 0) {
        PcNote("暂无逐笔记录。")
        return
    }
    val fmt = remember { java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US) }
    for (i in 0 until ledger.length()) {
        val entry = ledger.optJSONObject(i) ?: continue
        val amount = entry.optDouble("amount")
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = PcTokens.RowGap / 2),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    formatTs(entry.optDouble("ts"), fmt) + " · " + kindLabel(entry.optString("kind")),
                    style = MiuixTheme.textStyles.main,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val note = entry.optString("note")
                if (note.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        note,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.height(0.dp))
            Text(
                "%+.2f".format(amount),
                style = MiuixTheme.textStyles.main,
                color = if (amount > 0) MiuixTheme.colorScheme.primary
                else if (amount < 0) MiuixTheme.colorScheme.error
                else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1
            )
        }
        if (i < ledger.length() - 1) PcDivider()
    }
    Spacer(Modifier.height(PcTokens.RowGap))
    PcNote(
        "口径说明：上游只提供当前余额快照、没有流水接口，因此来源拆解与逐笔明细由本机记账，" +
            "在网页端或其它设备上的积分变动不会出现在这里 —— 两者的差额属于正常现象。"
    )
}

/** kind 英文标识 → 中文标签。 */
private fun kindLabel(kind: String): String = when (kind) {
    "checkin" -> "每日签到"
    "reward" -> "任务奖励"
    "gift" -> "每日福利"
    "school" -> "开学季"
    "lottery" -> "抽奖"
    "buddy" -> "猫猫"
    "consume" -> "调用消耗"
    "refund" -> "返还"
    "adjust" -> "人工调整"
    else -> if (kind.isBlank()) "其它" else kind
}

/** 账本是否为空（今日与累计四项全为 0 视为空）。 */
private fun hasLedgerData(summary: JSONObject): Boolean =
    summary.optDouble("today_gain") != 0.0 || summary.optDouble("today_loss") != 0.0 ||
        summary.optDouble("total_gain") != 0.0 || summary.optDouble("total_loss") != 0.0

/**
 * 到期时间格式化。
 *
 * 上游三种形态都出现过：秒级时间戳、ISO 字符串、JSONObject.NULL。
 * 统一收敛成"本地日期"或"—"，绝不把 null 直接渲染出来。
 */
private fun formatExpire(raw: Any?, short: Boolean = false): String {
    if (raw == null || raw == JSONObject.NULL) return if (short) "" else "—"
    val seconds = when (raw) {
        is Number -> raw.toDouble()
        is String -> raw.toDoubleOrNull() ?: return if (short) "" else raw.take(19)
        else -> return if (short) "" else "—"
    }
    if (seconds <= 0) return if (short) "" else "—"
    // 上游存在按毫秒返回的版本，用 1e12 作为分界自动纠正
    val millis = if (seconds > 1e12) seconds.toLong() else (seconds * 1000).toLong()
    val pattern = if (short) "yyyy-MM-dd" else "yyyy-MM-dd HH:mm"
    return runCatching {
        java.text.SimpleDateFormat(pattern, java.util.Locale.US).format(java.util.Date(millis))
    }.getOrDefault(if (short) "" else "—")
}

/** 账本时间戳（秒）→ 本地时间。 */
private fun formatTs(seconds: Double, fmt: java.text.SimpleDateFormat): String =
    runCatching { fmt.format(java.util.Date((seconds * 1000).toLong())) }.getOrDefault("")
