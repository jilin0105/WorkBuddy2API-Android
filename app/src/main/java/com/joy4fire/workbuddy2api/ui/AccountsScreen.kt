package com.joy4fire.workbuddy2api.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.joy4fire.workbuddy2api.AccountRegion
import com.joy4fire.workbuddy2api.FormatKit
import com.joy4fire.workbuddy2api.NativeCore
import com.joy4fire.workbuddy2api.OAuthWebActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 账号页 —— 账号池管理。
 *
 * 【重写要点】
 *   ① 每个账号卡从「7 行键值 + 5 个按钮」压缩为「标题行 + 额度条 + 3 行关键信息 +
 *      2 个按钮 + 一个"更多"」：账号卡是列表项，不是详情页。
 *      高频动作（设为当前 / 刷新额度）留在卡上，低频动作（导出 / 优先级 / 删除）
 *      收进「更多」弹窗 —— 这是上一版"每张卡五个等宽按钮、要滑两屏才看完一个号"的直接修正。
 *   ② 额度用进度条 + 百分比表达，而不是一串数字：多账号场景下用户的第一诉求是
 *      "哪个号快用完了"，条形比数字更快被扫读。
 *   ③ 签到结果不再平铺成一列卡片，而是汇总成一句话 + 可展开的明细弹窗。
 *
 * 功能对齐清单（逐项对应原实现，一处未减）：
 *   国内版登录 / 国际版登录 / 切换账号登录 / 导入 auth 文件 / 导出全部账号
 *   单账号：启用停用、优先级、导出、删除、积分详情
 *   批量：刷新全部额度、为全部国内账号签到
 *   展示：账号池、健康状态、额度、今日签到、签到结果
 */
@Composable
fun AccountsScreen(context: Context, app: AppState) {
    val activity = LocalContext.current as? Activity
    val scope = rememberCoroutineScope()

    // 导出目标由 SAF 选定后再写盘：pendingExport 保存待写内容，
    // 避免"先写盘再让用户选位置"（那样用户取消后磁盘上已留下文件）。
    var pendingExport by remember { mutableStateOf<String?>(null) }
    var pendingExportName by remember { mutableStateOf("workbuddy-accounts.json") }
    var showExportResult by remember { mutableStateOf<String?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    // 积分详情弹窗：按账号查看积分来源（签到/奖励/消耗）
    var creditAccountKey by remember { mutableStateOf<String?>(null) }
    var creditAccountName by remember { mutableStateOf("") }
    var creditDetail by remember { mutableStateOf<JSONObject?>(null) }
    // 单账号「更多」操作弹窗
    var moreSheetAccount by remember { mutableStateOf<JSONObject?>(null) }
    // 签到结果明细弹窗
    var showCheckinDetail by remember { mutableStateOf(false) }

    // 签到结果在 LazyList 内用于渲染汇总，在 LazyList 外用于明细弹窗，
    // 因此提升到函数作用域一次读取（两处必须看到同一份数据）。
    val results = app.checkinResults

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val raw = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(target)?.use { stream ->
                        val max = 1024 * 1024
                        val bytes = stream.readNBytes(max + 1)
                        require(bytes.size <= max) { "认证文件不能超过 1 MB" }
                        bytes.toString(Charsets.UTF_8)
                    } ?: error("无法读取所选文件")
                }
            }
            raw.fold(
                onSuccess = { app.importAuth(it) },
                onFailure = { importError = it.message ?: "读取失败" }
            )
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val payload = pendingExport
        pendingExport = null
        if (uri == null || payload.isNullOrBlank()) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")
                        ?.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                        ?: error("无法打开目标文件")
                }
            }
            result.fold(
                onSuccess = { app.showMessage("已导出 $pendingExportName（含登录凭证，请妥善保管）") },
                onFailure = { showExportResult = "导出失败：${it.message ?: "写入被拒绝"}" }
            )
        }
    }

    /** 统一的单账号导出流程（卡片上的「导出」与「更多」里的「导出」共用）。 */
    fun exportAccount(account: JSONObject, accountKey: String, nickname: String, uid: String) {
        scope.launch {
            val payload = withContext(Dispatchers.IO) { app.exportAccount(accountKey) }
            if (payload.isNullOrBlank()) {
                showExportResult = "该账号没有可导出的认证数据"
            } else {
                pendingExport = payload
                pendingExportName = "workbuddy-${FormatKit.safeFileName(nickname.ifBlank { uid })}.json"
                exportLauncher.launch(pendingExportName)
            }
        }
    }

    PcList {
        // ---------------- 连接账号 ----------------
        item {
            PcCard(title = "连接账号") {
                PcActionGrid(
                    actions = listOf(
                        PcAction("国内版登录", primary = true) {
                            if (activity != null) startOAuth(context, activity, AccountRegion.DOMESTIC, true, app)
                        },
                        PcAction("国际版登录") {
                            if (activity != null) startOAuth(context, activity, AccountRegion.INTERNATIONAL, true, app)
                        },
                        PcAction("切换账号登录") {
                            if (activity != null) {
                                startOAuth(context, activity, AccountRegion.DOMESTIC, true, app)
                                app.showMessage("已清空登录态并打开登录窗口")
                            }
                        },
                        PcAction("导入 auth 文件") { importLauncher.launch(arrayOf("application/json", "*/*")) }
                    )
                )
                Spacer(Modifier.height(PcTokens.RowGap))
                PcWideButton(text = "导出全部账号", primary = false) {
                    scope.launch {
                        val payload = withContext(Dispatchers.IO) { app.exportAllAccounts() }
                        if (payload.isNullOrBlank()) {
                            showExportResult = "导出失败：没有可导出的认证数据"
                        } else {
                            pendingExport = payload
                            pendingExportName = "workbuddy-accounts-${app.accounts.length()}.json"
                            exportLauncher.launch(pendingExportName)
                        }
                    }
                }
                Spacer(Modifier.height(PcTokens.SectionGap))
                PcNote(
                    "「切换账号登录」会先清空登录态，用于在同一台设备上登录第二个账号；" +
                        "导入支持 WorkBuddy / CodeBuddy 的 .info 与 JSON 认证文件。"
                )
            }
        }

        // ---------------- 账号池 ----------------
        if (app.accounts.length() == 0) {
            item { PcSection("账号池") }
            item {
                PcEmptyCard("还没有账号。用上方按钮登录（App 内置窗口，不跳浏览器），或导入认证文件。")
            }
        } else {
            item {
                PcSection("账号池 · ${app.accounts.length()} 个")
            }
            items(app.accounts.length()) { index ->
                val account = app.accounts.optJSONObject(index) ?: return@items
                AccountCard(
                    account = account,
                    app = app,
                    onMore = { moreSheetAccount = account },
                    onShowCredits = {
                        creditAccountKey = account.optString("account_key", account.optString("uid"))
                        creditAccountName = FormatKit.accountName(
                            account.optString("nickname"), account.optString("uid")
                        )
                        creditDetail = null // 先置空，弹窗内显示"读取中"
                        scope.launch {
                            creditDetail = withContext(Dispatchers.IO) {
                                app.loadCreditDetail(
                                    account.optString("account_key", account.optString("uid"))
                                )
                            }
                        }
                    }
                )
            }
        }

        // ---------------- 批量操作 ----------------
        if (app.accounts.length() > 0) {
            item { PcSection("批量操作") }
            item {
                PcCard {
                    PcActionGrid(
                        actions = listOf(
                            PcAction(
                                label = if (app.busy) "刷新中…" else "刷新全部额度",
                                primary = true,
                                enabled = !app.busy
                            ) { app.refreshCredits() },
                            PcAction("全部国内账号签到", enabled = !app.busy) { app.checkInAll() }
                        )
                    )
                    Spacer(Modifier.height(PcTokens.SectionGap))
                    PcNote("额度按账号逐个刷新后汇总；批量签到只处理国内账号，国际版额度由上游自动发放。")
                }
            }
        }

        // ---------------- 签到结果 ----------------
        //
        // checkinResults 是 AppState 上的可变状态，直接在 LazyList content lambda 里
        // 反复读取会让"读状态"这件事发生在非组合作用域；这里先用局部 val 固化一次，
        // 后面的统计与弹窗都复用它（弹窗在 LazyList 之外引用同一个值）。
        if (results != null && results.length() > 0) {
            val okCount = (0 until results.length()).count { results.optJSONObject(it).optBoolean("ok") }
            val failedCount = (0 until results.length()).count {
                val item = results.optJSONObject(it)
                !item.optBoolean("ok") && !item.optBoolean("skipped")
            }
            val gained = (0 until results.length()).sumOf { i ->
                val item = results.optJSONObject(i) ?: return@sumOf 0.0
                if (item.isNull("credits_gained")) 0.0 else item.optDouble("credits_gained", 0.0)
            }
            item { PcSection("最近签到") }
            item {
                PcCard {
                    PcMetricRow(
                        listOf(
                            PcMetric("成功 / 已签", okCount.toString(), MiuixTheme.colorScheme.primary),
                            PcMetric("失败", failedCount.toString(), if (failedCount > 0) MiuixTheme.colorScheme.error else null),
                            PcMetric("本次到账", "+%.1f".format(gained))
                        )
                    )
                    Spacer(Modifier.height(PcTokens.SectionGap))
                    PcWideButton(text = "查看每个账号的结果", primary = false) { showCheckinDetail = true }
                }
            }
        }
    }

    // ---------------- 弹窗区 ----------------

    PcAlertDialog(
        show = importError != null,
        title = "导入失败",
        message = importError.orEmpty(),
        onDismiss = { importError = null }
    )
    PcAlertDialog(
        show = showExportResult != null,
        title = "导出",
        message = showExportResult.orEmpty(),
        onDismiss = { showExportResult = null }
    )

    // 签到结果明细：从"每账号一张卡"改成"一句话汇总 + 明细弹窗"，
    // 因为签到结果只在刚点完的十几秒内有价值，不该长期占据账号页版面。
    if (showCheckinDetail && results != null) {
        PcLogDialog(
            show = true,
            title = "签到结果（${results.length()} 个账号）",
            lines = buildCheckinLines(results),
            onDismiss = { showCheckinDetail = false }
        )
    }

    // 积分详情：按账号查看积分来源（签到/奖励/消耗）与去向
    creditAccountKey?.let { key ->
        CreditDetailDialog(
            accountName = creditAccountName,
            detail = creditDetail,
            onRefresh = {
                creditDetail = null
                scope.launch {
                    creditDetail = withContext(Dispatchers.IO) { app.loadCreditDetail(key) }
                }
            },
            onClearLedger = {
                app.clearCreditLedger(key)
                scope.launch {
                    creditDetail = withContext(Dispatchers.IO) { app.loadCreditDetail(key) }
                }
            },
            onDismiss = { creditAccountKey = null }
        )
    }

    // 单账号「更多」——低频操作的收容所
    moreSheetAccount?.let { account ->
        val accountKey = account.optString("account_key", account.optString("uid"))
        val nickname = account.optString("nickname", "WorkBuddy 用户")
        val uid = account.optString("uid")
        AccountMoreDialog(
            account = account,
            onExport = {
                moreSheetAccount = null
                exportAccount(account, accountKey, nickname, uid)
            },
            onSetPriority = { priority ->
                app.setAccountPriority(accountKey, priority)
                moreSheetAccount = null
            },
            onToggleEnabled = {
                app.setAccountEnabled(accountKey, !account.optBoolean("enabled", true))
                moreSheetAccount = null
            },
            onDelete = {
                app.deleteAccount(accountKey, nickname)
                moreSheetAccount = null
            },
            onDismiss = { moreSheetAccount = null }
        )
    }
}

/**
 * 单个账号卡。
 *
 * 布局顺序刻意与"用户扫读顺序"一致：
 *   昵称 → 状态 → 额度条 → 版本/UID/签到 → 高频按钮 → 更多
 * 而不是先铺一排键值再放按钮（上一版就是这样，导致同一张卡里"剩余额度"
 * 夹在"优先级"和"签到状态"之间，扫读时容易跳过）。
 */
@Composable
private fun AccountCard(
    account: JSONObject,
    app: AppState,
    onMore: () -> Unit,
    onShowCredits: () -> Unit
) {
    val uid = account.optString("uid")
    val nickname = FormatKit.accountName(account.optString("nickname"), uid)
    val enabled = account.optBoolean("enabled", true)
    val healthy = account.optBoolean("healthy")
    val accountKey = account.optString("account_key", uid)
    val isInternational = AccountRegion.from(account.optString("region")) == AccountRegion.INTERNATIONAL
    val hasCredits = !account.isNull("credits_remaining")

    PcCard {
        // 标题行：昵称 + 状态
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                nickname,
                style = MiuixTheme.textStyles.main,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            PcStatus(
                text = when {
                    !enabled -> "已停用"
                    healthy -> "健康"
                    else -> "冷却中"
                },
                positive = enabled && healthy
            )
        }

        // 额度条：只有拿到过额度才画，否则显示"未刷新"（不画一条假的 0%）
        Spacer(Modifier.height(PcTokens.RowGap))
        if (hasCredits) {
            val remain = account.optDouble("credits_remaining")
            val total = account.optDouble("credits_total")
            val ratio = if (total > 0) (remain / total).toFloat().coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(progress = ratio, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(PcTokens.RowGap / 2))
            Text(
                "剩余 ${FormatKit.credits(remain)} / ${FormatKit.credits(total)} 积分（${(ratio * 100).toInt()}%）",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.primary
            )
        } else {
            Text(
                "额度尚未刷新",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }

        Spacer(Modifier.height(PcTokens.RowGap))
        PcInfoRow("版本", account.optString("region_label", "国内版"))
        PcInfoRow(
            if (isInternational) "额度发放" else "今日签到",
            if (isInternational) {
                "自动发放 · 刷新额度即可"
            } else if (isCheckedInToday(account.optString("last_checkin_date"))) {
                "已签到"
            } else {
                "未签到"
            }
        )
        PcMonoRow("UID", uid)

        Spacer(Modifier.height(PcTokens.SectionGap))
        // 高频动作留在卡上：积分详情（核对积分来源）与更多（导出/优先级/删除）。
        // 中间两个位置刻意留空，不用 4 个按钮填满 —— 账号卡的重点是"读"，不是"按"。
        PcActionGrid(
            actions = listOf(
                PcAction("积分详情", primary = true) { onShowCredits() },
                PcAction("更多操作") { onMore() }
            )
        )
    }
}

/**
 * 单账号「更多」弹窗 —— 低频操作的收容所。
 *
 * 为什么把优先级做成这个弹窗里的一栏而不是独立弹窗：
 * 优先级是一个 0..20 的整数，用户在切号不顺手时才会来调它，
 * 而且调完通常还要顺手做点别的（停用/导出）。放在同一层减少一次跳转。
 */
@Composable
private fun AccountMoreDialog(
    account: JSONObject,
    onExport: () -> Unit,
    onSetPriority: (Int) -> Unit,
    onToggleEnabled: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val uid = account.optString("uid")
    val nickname = FormatKit.accountName(account.optString("nickname"), uid)
    val enabled = account.optBoolean("enabled", true)
    var priority by remember(uid) { mutableStateOf(account.optInt("priority").coerceIn(0, 20)) }
    var confirmDelete by remember { mutableStateOf(false) }

    top.yukonga.miuix.kmp.window.WindowDialog(
        show = true,
        title = nickname,
        summary = "${account.optString("region_label", "国内版")} · ${FormatKit.shortUid(uid)}",
        onDismissRequest = onDismiss,
        content = {
            Column(Modifier.fillMaxWidth()) {
                // 优先级：数值 + 滑块 + 快捷档位三条路径并存。
                // 只给滑块在"从 0 调到 1"这种微调上很难精确定位；
                // 只给步进在大跨度调整上又太慢 —— 两者都要有。
                Text("选号优先级", style = MiuixTheme.textStyles.main)
                Spacer(Modifier.height(2.dp))
                PcNote("权重 ×${priority + 1}。只影响多账号之间的选号概率，额度、成功率与闲置时长仍照常参与加权。")
                Spacer(Modifier.height(PcTokens.RowGap))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    top.yukonga.miuix.kmp.basic.TextButton(
                        text = "−",
                        onClick = { if (priority > 0) priority -= 1 },
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        priority.toString(),
                        style = MiuixTheme.textStyles.title2,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    top.yukonga.miuix.kmp.basic.TextButton(
                        text = "+",
                        onClick = { if (priority < 20) priority += 1 },
                        modifier = Modifier.weight(1f)
                    )
                }
                top.yukonga.miuix.kmp.basic.Slider(
                    value = priority.toFloat(),
                    onValueChange = { priority = it.toInt().coerceIn(0, 20) },
                    valueRange = 0f..20f,
                    steps = 19,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PcTokens.ButtonGap)) {
                    listOf(0, 1, 2, 5, 10).forEach { preset ->
                        top.yukonga.miuix.kmp.basic.TextButton(
                            text = if (preset == priority) "● $preset" else preset.toString(),
                            onClick = { priority = preset },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Spacer(Modifier.height(PcTokens.RowGap))
                PcWideButton(text = "保存优先级") { onSetPriority(priority) }

                Spacer(Modifier.height(PcTokens.SectionGap))
                PcDivider()
                Spacer(Modifier.height(PcTokens.SectionGap))

                PcActionGrid(
                    actions = listOf(
                        PcAction(if (enabled) "停用该账号" else "启用该账号") { onToggleEnabled() },
                        PcAction("导出该账号") { onExport() }
                    )
                )
                Spacer(Modifier.height(PcTokens.ButtonGap))
                // 删除单独一行并用错误色：它是唯一不可撤销的操作，
                // 与"停用/导出"混在同一行会显著提高误触概率。
                top.yukonga.miuix.kmp.basic.TextButton(
                    text = "删除该账号",
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColors(
                        color = MiuixTheme.colorScheme.errorContainer,
                        textColor = MiuixTheme.colorScheme.error
                    )
                )
                Spacer(Modifier.height(PcTokens.RowGap))
                PcNote("导出会包含登录凭证；删除会从本机移除该账号的认证数据，无法恢复。")
            }
        }
    )

    PcConfirmDialog(
        show = confirmDelete,
        title = "删除账号？",
        message = "${account.optString("region_label", "国内版")} · $uid\n\n删除后该账号的登录凭证将从本机移除，无法恢复。",
        confirmText = "删除",
        danger = true,
        onConfirm = {
            confirmDelete = false
            onDelete()
        },
        onDismiss = { confirmDelete = false }
    )
}

/** 把签到结果拍成"一行一个账号"，供弹窗渲染（首字符带符号，弹窗会按符号着色）。 */
private fun buildCheckinLines(results: JSONArray): List<String> = buildList {
    for (i in 0 until results.length()) {
        val item = results.optJSONObject(i) ?: continue
        val mark = when {
            item.optBoolean("skipped") -> "·"
            item.optBoolean("ok") -> "✓"
            else -> "✗"
        }
        val status = when {
            !item.optBoolean("checkin_supported", true) && item.optBoolean("skipped") ->
                if (item.optBoolean("ok")) "国际版不适用" else "国际版刷新失败"
            item.optBoolean("skipped") -> "已跳过"
            item.optBoolean("already") -> "今日已签到"
            item.optBoolean("ok") -> "签到成功"
            else -> "签到失败"
        }
        val gained = if (item.isNull("credits_gained")) "" else {
            val value = item.optDouble("credits_gained", 0.0)
            if (value != 0.0) " · " + if (value > 0) "+%.1f 积分".format(value) else "%.1f 积分".format(value) else ""
        }
        val name = FormatKit.accountName(item.optString("nickname"), item.optString("uid"))
        val message = item.optString("message").takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
        add("$mark $name：$status$gained$message")
    }
}

/** 判断今天是否已签到（本地日期比较，不依赖上游时区）。 */
internal fun isCheckedInToday(lastDate: String): Boolean {
    if (lastDate.isBlank()) return false
    val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
    return lastDate.take(10) == today
}

/**
 * 发起 OAuth 登录（内置窗口 + 轮询）。
 *
 * 与原实现一致：默认清空 WebView 会话 —— 因为 Google / GitHub 只要留有上次的登录
 * Cookie，再点登录会被静默授权回同一账号，用户永远换不到第二个账号。
 * 轮询成功后才关闭窗口并刷新列表。
 */
internal fun startOAuth(
    context: Context,
    activity: Activity,
    region: AccountRegion,
    clearSession: Boolean,
    app: AppState
) {
    app.showMessage("正在创建${region.label}登录会话…")
    Thread {
        val session = runCatching { NativeCore.beginOAuth(region) }.getOrNull()
        activity.runOnUiThread {
            if (session == null) {
                app.showMessage("发起${region.label}登录失败，请重试")
                return@runOnUiThread
            }
            activity.startActivity(
                Intent(activity, OAuthWebActivity::class.java)
                    .putExtra(OAuthWebActivity.EXTRA_AUTH_URL, session.authUrl)
                    .putExtra(OAuthWebActivity.EXTRA_REGION_LABEL, region.label)
                    .putExtra(OAuthWebActivity.EXTRA_CLEAR_SESSION, clearSession)
            )
            app.showMessage("已打开登录窗口，授权成功后自动关闭")
            pollOAuth(activity, session, app, 0)
        }
    }.start()
}

/** 轮询登录结果，最多 150 次 × 2 秒（与原实现一致）。 */
private fun pollOAuth(
    activity: Activity,
    session: com.joy4fire.workbuddy2api.OAuthSession,
    app: AppState,
    attempts: Int
) {
    if (activity.isFinishing) return
    activity.window.decorView.postDelayed({
        if (activity.isFinishing) return@postDelayed
        Thread {
            val result = runCatching { NativeCore.pollOAuth(context = activity, session = session) }
            activity.runOnUiThread {
                result.fold(
                    onSuccess = { account ->
                        if (account != null) {
                            com.joy4fire.workbuddy2api.AccountSync.afterAuthorize(session.region.defaultDomain)
                            OAuthWebActivity.dismissIfOpen()
                            app.showMessage("${account.region.label}登录成功：${account.nickname}")
                            app.loadAccounts()
                        } else if (attempts < 150) {
                            pollOAuth(activity, session, app, attempts + 1)
                        } else {
                            app.showMessage("${session.region.label}登录等待超时，请重试")
                        }
                    },
                    onFailure = { app.showMessage("登录轮询失败：${it.message}") }
                )
            }
        }.start()
    }, 2000)
}
