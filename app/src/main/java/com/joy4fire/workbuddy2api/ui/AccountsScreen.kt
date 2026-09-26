package com.joy4fire.workbuddy2api.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.joy4fire.workbuddy2api.AccountRegion
import com.joy4fire.workbuddy2api.FormatKit
import com.joy4fire.workbuddy2api.NativeCore
import com.joy4fire.workbuddy2api.OAuthWebActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 账号页 —— 对应原界面 accountsPage()。
 *
 * 功能对齐清单（逐项对应原实现，一处未减）：
 *   国内版登录 / 国际版登录 / 切换账号登录 / 导入 auth 文件 / 导出全部账号
 *   单账号：启用停用、优先级、导出、删除
 *   批量：刷新全部额度、为全部国内账号签到
 *   展示：账号池、健康状态、额度、今日签到、签到结果
 */
@Composable
fun AccountsScreen(context: Context, app: AppState) {
    val activity = LocalContext.current as? Activity
    val scope = rememberCoroutineScope()

    // 导出目标由 SAF 选定后再写盘：pendingExport 保存待写内容，避免先写盘再让用户选位置。
    var pendingExport by remember { mutableStateOf<String?>(null) }
    var pendingExportName by remember { mutableStateOf("workbuddy-accounts.json") }
    var showExportResult by remember { mutableStateOf<String?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }

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

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val payload = pendingExport
        pendingExport = null
        if (uri == null || payload.isNullOrBlank()) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                        ?: error("无法打开目标文件")
                }
            }
            result.fold(
                onSuccess = { app.showMessage("已导出 $pendingExportName（含登录凭证，请妥善保管）") },
                onFailure = { showExportResult = "导出失败：${it.message ?: "写入被拒绝"}" }
            )
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = PcTokens.SpaceM, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(PcTokens.CardGap)
    ) {
        // ---------------- 登录与导入导出入 ----------------
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
                        PcAction("导入 auth 文件") { importLauncher.launch(arrayOf("application/json", "*/*")) },
                        PcAction("导出全部账号") {
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
                    )
                )
                Spacer(Modifier.height(PcTokens.SpaceS))
                Text(
                    "「切换账号登录」会先清空登录态，用于在同一设备上登录第二个账号；" +
                        "导入支持 WorkBuddy / CodeBuddy 的 .info 与 JSON 认证文件。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
        // ---------------- 账号池 ----------------
        if (app.accounts.length() == 0) {
            item {
                PcCard(title = "尚未连接账号") {
                    Text(
                        "在 App 内置窗口完成登录（不跳浏览器），或导入 WorkBuddy / CodeBuddy 的 .info、JSON 文件。",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        } else {
            item { SmallTitle(text = "账号池 · ${app.accounts.length()} 个") }
            items(app.accounts.length()) { index ->
                val account = app.accounts.optJSONObject(index) ?: return@items
                val uid = account.optString("uid")
                val accountKey = account.optString("account_key", uid)
                val nickname = account.optString("nickname", "WorkBuddy 用户")
                AccountCard(
                    account = account,
                    accountKey = accountKey,
                    nickname = nickname,
                    app = app,
                    onExport = {
                        scope.launch {
                            val payload = withContext(Dispatchers.IO) { app.exportAccount(accountKey) }
                            if (payload.isNullOrBlank()) showExportResult = "该账号没有可导出的认证数据"
                            else {
                                pendingExport = payload
                                pendingExportName = "workbuddy-${FormatKit.safeFileName(nickname.ifBlank { uid })}.json"
                                exportLauncher.launch(pendingExportName)
                            }
                        }
                    }
                )
            }
        }

        // ---------------- 批量操作 ----------------
        item { SmallTitle(text = "账号操作") }
        item {
            PcCard {
                Button(
                    onClick = { app.refreshCredits() },
                    enabled = !app.busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (app.busy) "刷新中…" else "刷新全部账号额度") }
                Spacer(Modifier.height(PcTokens.SpaceS))
                TextButton(
                    text = "为全部启用的国内账号签到",
                    onClick = { app.checkInAll() },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(PcTokens.SpaceS))
                Text(
                    "额度会按账号逐个刷新并汇总；批量签到只处理国内账号，国际版额度由上游自动发放。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }

        // ---------------- 额度汇总 ----------------
        val summary = app.credits
        if (summary != null && summary.optInt("known") > 0) {
            item { SmallTitle(text = "额度汇总") }
            item {
                PcCard {
                    Text(
                        "${FormatKit.credits(summary.optDouble("remain"))} / ${FormatKit.credits(summary.optDouble("total"))} 积分",
                        style = MiuixTheme.textStyles.title3,
                        color = MiuixTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(PcTokens.SpaceS / 2))
                    Text(
                        "已汇总 ${summary.optInt("known")} / ${summary.optInt("accounts")} 个账号",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    val total = summary.optDouble("total")
                    val ratio = if (total > 0) (summary.optDouble("remain") / total).toFloat().coerceIn(0f, 1f) else 0f
                    Spacer(Modifier.height(PcTokens.SpaceS))
                    LinearProgressIndicator(progress = ratio, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        // ---------------- 签到结果 ----------------
        app.checkinResults?.let { results ->
            if (results.length() > 0) {
                item { SmallTitle(text = "最近签到结果") }
                items(results.length()) { index ->
                    val result = results.optJSONObject(index) ?: return@items
                    val status = when {
                        result.optBoolean("skipped") -> "已跳过"
                        result.optBoolean("already") -> "今日已签到"
                        result.optBoolean("ok") -> "签到成功"
                        else -> "签到失败"
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = PcTokens.PageGutter),
                        insideMargin = PaddingValues(horizontal = PcTokens.SpaceL, vertical = PcTokens.SpaceM)
                    ) {
                        Column {
                            Text(
                                FormatKit.accountName(result.optString("nickname"), result.optString("uid")),
                                style = MiuixTheme.textStyles.main
                            )
                            Spacer(Modifier.height(PcTokens.SpaceS / 2))
                            Text(
                                "$status · UID ${FormatKit.shortUid(result.optString("uid"))}",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                            if (result.optString("message").isNotBlank()) {
                                Text(
                                    result.optString("message"),
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                            // 本次到账的积分单独一行高亮：这是签到最核心的结果，
                            // 混在说明文案里不容易看见。
                            if (!result.isNull("credits_gained")) {
                                val gained = result.optDouble("credits_gained", 0.0)
                                if (gained != 0.0) {
                                    Text(
                                        "本次 " + if (gained > 0) "+%.1f".format(gained) else "%.1f".format(gained) + " 积分",
                                        style = MiuixTheme.textStyles.main,
                                        color = if (gained > 0) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

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
}

/** 单个账号卡片：状态、额度、签到、优先级、启停、导出、删除。 */
@Composable
private fun AccountCard(
    account: org.json.JSONObject,
    accountKey: String,
    nickname: String,
    app: AppState,
    onExport: () -> Unit
) {
    val uid = account.optString("uid")
    val enabled = account.optBoolean("enabled", true)
    val healthy = account.optBoolean("healthy")
    var showPriority by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PcTokens.PageGutter),
        insideMargin = PaddingValues(horizontal = PcTokens.SpaceL, vertical = PcTokens.SpaceM)
    ) {
        Column {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(nickname, style = MiuixTheme.textStyles.main, modifier = Modifier.weight(1f))
                Text(
                    when {
                        healthy -> "● 健康"
                        enabled -> "冷却"
                        else -> "已停用"
                    },
                    style = MiuixTheme.textStyles.body2,
                    color = if (healthy) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
            Spacer(Modifier.height(PcTokens.SpaceS))
            PcInfoRow("版本", account.optString("region_label", "国内版"))
            // UID 用等宽 + 固定标签列宽：此前用 PcInfoRow 的权重布局，短标签 "UID"
            // 被长值挤成逐字竖排（截图可见 U/I/D 三行）。
            PcMonoRow("UID", uid)
            PcInfoRow("优先级", "${account.optInt("priority")}（权重 ×${account.optInt("priority") + 1}）")
            PcInfoRow(
                "剩余 / 总额度",
                if (account.isNull("credits_remaining")) "尚未刷新"
                else "${FormatKit.credits(account.optDouble("credits_remaining"))} / ${FormatKit.credits(account.optDouble("credits_total"))} 积分"
            )
            val isInternational = AccountRegion.from(account.optString("region")) == AccountRegion.INTERNATIONAL
            PcInfoRow(
                if (isInternational) "额度发放" else "今日签到",
                if (isInternational) "自动发放 · 刷新额度即可"
                else if (isCheckedInToday(account.optString("last_checkin_date"))) "已签到" else "未签到"
            )
            Spacer(Modifier.height(PcTokens.SpaceM))
            // 统一走 PcActionGrid：等宽、按钮间有固定间距、奇数个自动折行且不拉伸。
            // 此前四个按钮分两个 Row 手工摆放，导致三个挤成一排、删除单独换行且宽度不一致。
            PcActionGrid(
                actions = listOf(
                    PcAction(if (enabled) "停用" else "启用", primary = true) {
                        app.setAccountEnabled(accountKey, !enabled)
                    },
                    PcAction("优先级") { showPriority = true },
                    PcAction("导出") { onExport() },
                    PcAction("删除账号") { showDelete = true }
                )
            )
        }
    }

    if (showPriority) {
        PriorityDialog(
            initial = account.optInt("priority"),
            accountLabel = "$nickname · ${FormatKit.shortUid(uid)}",
            onDismiss = { showPriority = false },
            onConfirm = { value ->
                app.setAccountPriority(accountKey, value)
                showPriority = false
            }
        )
    }

    PcConfirmDialog(
        show = showDelete,
        title = "删除账号？",
        message = "${account.optString("region_label")} · $uid\n\n删除后该账号的登录凭证将从本机移除，无法恢复。",
        confirmText = "删除",
        onConfirm = { app.deleteAccount(accountKey, nickname); showDelete = false },
        onDismiss = { showDelete = false }
    )
}

/**
 * 优先级设置对话框 —— 对应原界面的 showPriorityDialog。
 *
 * 保留原版的三条调节路径（步进 / 滑块 / 快捷档位），因为优先级是「选号概率」，
 * 用户往往需要反复微调，单一滑块不好精确定位。
 */
@Composable
private fun PriorityDialog(
    initial: Int,
    accountLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var value by remember { mutableStateOf(initial.coerceIn(0, 20)) }
    PcPriorityDialogContent(
        value = value,
        onValueChange = { value = it },
        accountLabel = accountLabel,
        onDismiss = onDismiss,
        onConfirm = { onConfirm(value) }
    )
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
 * 与原实现一致：默认清空 WebView 会话，因为 Google / GitHub 只要留有上次的登录
 * Cookie，再点登录会被静默授权到同一账号，用户永远换不到第二个账号。
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
private fun pollOAuth(activity: Activity, session: com.joy4fire.workbuddy2api.OAuthSession, app: AppState, attempts: Int) {
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
