package com.joy4fire.workbuddy2api.ui

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.joy4fire.workbuddy2api.ApiHostService
import com.joy4fire.workbuddy2api.FormatKit
import com.joy4fire.workbuddy2api.KeepAliveGuide
import com.joy4fire.workbuddy2api.NativeCore
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 用量页 —— 对应原界面 usagePage()。
 *
 * 保留原页面的四个分组：总量指标、调用分布（协议/模型/应用）、额度、最近 24 小时趋势。
 * 「调用分布」是原代码注释里特别说明过「早已算出但一直没呈现」的部分，此处补齐。
 */
@Composable
fun UsageScreen(context: Context, app: AppState) {
    val summary = app.usageSummary
    // 进入用量页自动拉取（与记录页同一份数据，去重窗口共享，避免重复查询）。
    LaunchedEffect(Unit) { app.loadUsageIfStale() }
    // 趋势数据在 LazyColumn 之外算好：LazyColumn 的 content lambda 是 @Composable 但不允许
    // 在其中声明局部 val（会被当作 item 之外的语句），且 remember 放在 item 之间会破坏作用域。
    val nonEmpty = remember(app.usageSeries, app.revision) {
        (0 until app.usageSeries.length()).mapNotNull { app.usageSeries.optJSONObject(it) }
            .filter { it.optLong("count") > 0 }
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = PcTokens.SpaceM, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(PcTokens.CardGap)
    ) {
        item {
            PcCard(title = "总量") {
                PcInfoRow("今日请求", (summary?.optLong("today_requests") ?: 0).toString())
                PcInfoRow("今日 Tokens", FormatKit.tokens(summary?.optLong("today_tokens") ?: 0))
                PcInfoRow("总请求", (summary?.optLong("total_requests") ?: 0).toString())
                PcInfoRow("总 Tokens", FormatKit.tokens(summary?.optLong("total_tokens") ?: 0))
                Spacer(Modifier.height(PcTokens.SpaceS))
                Button(onClick = { app.loadUsage() }, modifier = Modifier.fillMaxWidth()) { Text("刷新统计") }
            }
        }

        item { SmallTitle(text = "调用分布") }
        item {
            BreakdownCard(summary?.optJSONArray("by_protocol"), "protocol",
                "成功与失败的网关调用都会计入协议分布。")
        }
        item {
            BreakdownCard(summary?.optJSONArray("by_model"), "model",
                "上游未返回模型名时归入「未指定」。")
        }
        item {
            BreakdownCard(summary?.optJSONArray("by_app"), "app",
                "按应用 Key 归因；Key 删除后历史记录仍保留归因。")
        }

        item { SmallTitle(text = "额度") }
        item {
            val credits = app.credits
            PcCard {
                if (credits == null || credits.optInt("known") == 0) {
                    Text("额度数据未加载，点击下方按钮刷新。", style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Spacer(Modifier.height(PcTokens.SpaceS))
                    Button(onClick = { app.refreshCredits() }, enabled = !app.busy,
                        modifier = Modifier.fillMaxWidth()) { Text("刷新全部账号额度") }
                } else {
                    Text(
                        "全部剩余 ${FormatKit.credits(credits.optDouble("remain"))} 积分",
                        style = MiuixTheme.textStyles.title3,
                        color = MiuixTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(PcTokens.SpaceS / 2))
                    Text(
                        "总额度 ${FormatKit.credits(credits.optDouble("total"))} 积分 · " +
                            "已汇总 ${credits.optInt("known")}/${credits.optInt("accounts")} 个账号",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    val total = credits.optDouble("total")
                    val ratio = if (total > 0) (credits.optDouble("remain") / total).toFloat().coerceIn(0f, 1f) else 0f
                    Spacer(Modifier.height(PcTokens.SpaceS))
                    LinearProgressIndicator(progress = ratio, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        item { SmallTitle(text = "最近 24 小时") }
        if (nonEmpty.isEmpty()) {
            item {
                PcCard {
                    Text("暂无调用趋势，成功或失败的调用会按小时聚合。",
                        style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        } else {
            nonEmpty.takeLast(12).forEach { point ->
                item {
                    PcCard {
                        PcInfoRow(
                            point.optString("bucket"),
                            "${point.optLong("count")} 次 · ${FormatKit.tokens(point.optLong("tokens"))} tokens"
                        )
                    }
                }
            }
        }
    }
}

/** 分组统计卡（协议 / 模型 / 应用三处共用）。积分仅在真的 > 0 时展示，避免挂一排 0。 */
@Composable
private fun BreakdownCard(rows: JSONArray?, key: String, emptyHint: String) {
    PcCard {
        // 用 if/else 而不是提前 return：PcCard 的 content 是 ColumnScope lambda，
        // 在其中 return@PcCard 会让编译器认为后续 @Composable 调用可能不在组合上下文里。
        if (rows == null || rows.length() == 0) {
            Text("暂无统计数据：$emptyHint", style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        for (i in 0 until (rows?.length() ?: 0)) {
            val item = rows!!.optJSONObject(i) ?: continue
            // grouped() 的键就是传入的 alias（protocol / model / app）。
            val name = item.optString(key).ifBlank { "（未指定）" }
            val credits = item.optDouble("credits", 0.0)
            val detail = buildString {
                append("${item.optLong("count")} 次 · ${FormatKit.tokens(item.optLong("tokens"))} tokens")
                // 积分始终显示（含 0），否则用户无法判断"这一项到底有没有扣分"。
                append(" · ").append("%.2f".format(credits)).append(" 积分")
            }
            PcInfoRow(name, detail)
        }
    }
}

// ================================================================ 出网取证

/**
 * 出网取证页 —— 对应原界面 showRequestInspector() / showInspectorDetail()。
 *
 * 用途：与官方 CLI 逐字节比对「实际发给上游的 headers/body」。
 * 因此明细必须等宽字体、且提供「复制全部」——导出文本本来就是要贴到别处 diff 的。
 */
@Composable
fun InspectorScreen(context: Context, app: AppState) {
    var showAll by remember { mutableStateOf(false) }
    var detailIndex by remember { mutableStateOf<Int?>(null) }
    val entries = remember(app.revision) { app.inspectorEntries() }
    val enabled = app.inspectorEnabled()

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = PcTokens.SpaceM, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(PcTokens.CardGap)
    ) {
        item {
            PcCard(title = "什么是出网取证？") {
                Text(
                    "记录「实际发给上游的最后一份 headers/body」，用于与官方 CLI 的抓包逐字节比对，" +
                        "确认反代在身份上是否与官方一致。\n\n" +
                        "记录的是发送前一刻的数据，中间不再有任何改写；导出文本保持原始紧凑 JSON 格式，" +
                        "可直接贴进 diff 工具。\n\n" +
                        "敏感头（Authorization / X-Api-Key / Cookie）会保形脱敏——保留头名、前后缀与总长度，" +
                        "但隐藏凭据本体，因此不影响「这个头有没有发、格式对不对」的判断。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }

        item {
            PcCard(title = "开关") {
                SwitchPreference(
                    title = "记录出网请求",
                    summary = if (enabled) "已开启 · 当前 ${entries.length()} 条（仅保留最近 20 条）"
                    else "已关闭 · 开启后会留存请求内容（含对话正文），用完请清空",
                    checked = enabled,
                    onCheckedChange = { app.setInspectorEnabled(it) }
                )
                Spacer(Modifier.height(PcTokens.SpaceS))
                Row(horizontalArrangement = Arrangement.spacedBy(PcTokens.SpaceS)) {
                    TextButton(
                        text = "查看并复制",
                        onClick = { showAll = true },
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        text = "清空记录",
                        onClick = { app.clearInspector() },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (entries.length() > 0) {
            item { SmallTitle(text = "已留存 ${entries.length()} 条") }
            items(entries.length()) { index ->
                val entry = entries.optJSONObject(index) ?: return@items
                val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date(entry.optLong("ts")))
                val headerCount = entry.optJSONObject("headers")?.length() ?: 0
                ArrowPreference(
                    title = stamp,
                    summary = "$headerCount 个头 · 账号 ${entry.optString("account").take(6)}",
                    onClick = { detailIndex = index }
                )
            }
        }
    }

    PcDetailDialog(
        show = showAll,
        title = "出网取证（${entries.length()} 条）",
        body = remember(app.revision, showAll) { if (showAll) app.inspectorExport() else "" },
        copyLabel = "复制全部",
        onCopy = {
            copyToClipboard(context, app.inspectorExport())
            app.showMessage("已复制全部取证记录")
        },
        onDismiss = { showAll = false }
    )

    detailIndex?.let { index ->
        val entry = entries.optJSONObject(index)
        if (entry != null) {
            val text = buildString {
                append(entry.optString("method")).append(' ').append(entry.optString("url")).append("\n\n")
                entry.optJSONObject("headers")?.let { hs ->
                    hs.keys().forEach { k -> append(k).append(": ").append(hs.optString(k)).append('\n') }
                }
                append('\n').append(entry.optString("body"))
            }
            PcDetailDialog(
                show = true,
                title = "第 ${index + 1} 条",
                body = text,
                copyLabel = "复制此条",
                onCopy = {
                    copyToClipboard(context, text)
                    app.showMessage("已复制该条取证记录")
                },
                onDismiss = { detailIndex = null }
            )
        }
    }
}

// ================================================================ 存储占用

/** 存储占用页 —— 对应原界面 showStorageInfo()。 */
@Composable
fun StorageScreen(context: Context, app: AppState) {
    var detail by remember { mutableStateOf<String?>(null) }
    val info = remember(app.revision) { app.storageInfo() }
    val breakdown = remember(app.revision) { app.storageBreakdown() }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = PcTokens.SpaceM, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(PcTokens.CardGap)
    ) {
        item {
            PcCard(title = "数据库") {
                PcInfoRow("文件大小", FormatKit.bytes(info.optLong("db_bytes")))
                PcInfoRow("记录条数", info.optLong("rows").toString())
                PcInfoRow("可回收空闲", FormatKit.bytes(info.optLong("free_bytes")))
                val content = info.optLong("input_bytes") + info.optLong("output_bytes") + info.optLong("reasoning_bytes")
                PcInfoRow("内容合计", FormatKit.bytes(content))
                PcInfoRow("· 输入", FormatKit.bytes(info.optLong("input_bytes")))
                PcInfoRow("· 输出", FormatKit.bytes(info.optLong("output_bytes")))
                PcInfoRow("· 思考链", FormatKit.bytes(info.optLong("reasoning_bytes")))
            }
        }

        item {
            PcCard(title = "目录占用（实测）") {
                PcInfoRow("数据目录合计", FormatKit.bytes(breakdown.optLong("data_total")))
                PcInfoRow("· 数据库", FormatKit.bytes(breakdown.optLong("databases")))
                PcInfoRow("· WebView 缓存", FormatKit.bytes(breakdown.optLong("webview")))
                PcInfoRow("· cache", FormatKit.bytes(breakdown.optLong("cache")))
                PcInfoRow("· files", FormatKit.bytes(breakdown.optLong("files")))
                PcInfoRow("· code_cache", FormatKit.bytes(breakdown.optLong("code_cache")))
                PcInfoRow("· 外部缓存", FormatKit.bytes(breakdown.optLong("external_cache")))
            }
        }

        item {
            PcCard(title = "清理") {
                Text(
                    "使用记录会保存每次请求的输入/输出/思考链，是数据体积的主要来源；" +
                        "WebView 缓存没有容量上限，登录页的静态资源会长期堆积。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(PcTokens.SpaceS))
                PcActionGrid(
                    actions = listOf(
                        PcAction("清理 WebView 缓存") { app.clearWebViewCache() },
                        PcAction("截断超长记录内容") { app.trimUsage() },
                        PcAction("清理过期记录") {
                            val days = app.settings.optString("usage_retention_days", "30").toIntOrNull()?.coerceIn(1, 3650) ?: 30
                            app.cleanupUsage(days)
                        }
                    )
                )
                Spacer(Modifier.height(PcTokens.SpaceS))
                Button(onClick = { app.loadSettings(); detail = storageReport(app) },
                    modifier = Modifier.fillMaxWidth()) { Text("生成完整报告") }
            }
        }
    }

    detail?.let { text ->
        PcDetailDialog(
            show = true,
            title = "存储占用明细",
            body = text,
            onDismiss = { detail = null }
        )
    }
}

private fun storageReport(app: AppState): String = buildString {
    val info = app.storageInfo()
    append("【数据库】\n")
    append("文件大小：${FormatKit.bytes(info.optLong("db_bytes"))}（${info.optLong("rows")} 条记录）\n")
    append("可回收空闲：${FormatKit.bytes(info.optLong("free_bytes"))}\n")
    val content = info.optLong("input_bytes") + info.optLong("output_bytes") + info.optLong("reasoning_bytes")
    append("内容合计：${FormatKit.bytes(content)}\n")
    append("　输入 ${FormatKit.bytes(info.optLong("input_bytes"))} / ")
    append("输出 ${FormatKit.bytes(info.optLong("output_bytes"))} / ")
    append("思考 ${FormatKit.bytes(info.optLong("reasoning_bytes"))}\n")
    val b = app.storageBreakdown()
    append("\n【目录占用（实测）】\n")
    append("数据目录合计：${FormatKit.bytes(b.optLong("data_total"))}\n")
    append("· 数据库目录：${FormatKit.bytes(b.optLong("databases"))}\n")
    append("· WebView 缓存：${FormatKit.bytes(b.optLong("webview"))}\n")
    append("· cache 目录：${FormatKit.bytes(b.optLong("cache"))}\n")
    append("· files 目录：${FormatKit.bytes(b.optLong("files"))}\n")
    append("· 代码缓存：${FormatKit.bytes(b.optLong("code_cache"))}\n")
    append("· 外部缓存：${FormatKit.bytes(b.optLong("external_cache"))}\n")
}

// ================================================================ 后台保活

/** 保活引导页 —— 对应原界面 settingsPage() 里的保活步骤与状态检查。 */
@Composable
fun KeepAliveScreen(context: Context, app: AppState) {
    var report by remember { mutableStateOf<String?>(null) }
    val steps = remember { KeepAliveGuide.steps() }
    val notificationsGranted = remember(app.revision) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
    }
    val channelDisabled = remember(app.revision) { ApiHostService.isChannelDisabled(context) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = PcTokens.SpaceM, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(PcTokens.CardGap)
    ) {
        item {
            PcCard(title = "为什么还会被杀？") {
                Text(
                    "${KeepAliveGuide.vendorLabel()} 对后台应用有独立于「电池优化白名单」的一套管控，" +
                        "必须逐项手动放开。上面的白名单只解决其中一项，其余开关若不打开，" +
                        "本地 API 在锁屏或长时间待机后仍会被系统回收。\n\n" +
                        "另外要说明：被回收本身很难完全避免，真正影响体验的是「回收后能否自动恢复」。" +
                        "本版已加入开机自启、划掉任务后自动重启、15 分钟看门狗兜底，即使中途被杀也会自动拉回。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }

        item { SmallTitle(text = "请依次完成以下设置") }
        item {
            PcCard {
                steps.forEachIndexed { index, step ->
                    ArrowPreference(
                        title = "${index + 1}. ${step.title}",
                        summary = step.desc,
                        onClick = {
                            KeepAliveGuide.open(context, step.key)
                            if (step.key == "lock") app.showMessage("本项无法自动跳转：请打开最近任务，下拉本应用卡片后点锁图标")
                        }
                    )
                }
            }
        }

        item {
            PcCard(title = "状态检查") {
                Button(
                    onClick = {
                        val (lines, manual) = KeepAliveGuide.statusCheck(context, notificationsGranted, channelDisabled)
                        report = buildString {
                            lines.forEach { append(it).append('\n') }
                            append("\n系统未开放查询接口，请自行确认：\n")
                            manual.forEach { append("· ${it.title}\n") }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("一键检查当前状态") }
                Spacer(Modifier.height(PcTokens.SpaceS))
                TextButton(
                    text = "开启精确闹钟（看门狗命门）",
                    onClick = { KeepAliveGuide.requestExactAlarm(context) },
                    modifier = Modifier.fillMaxWidth()
                )
                if (channelDisabled) {
                    TextButton(
                        text = "重开前台服务通知渠道",
                        onClick = { KeepAliveGuide.openChannelSettings(context) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    report?.let { text ->
        PcDetailDialog(
            show = true,
            title = "保活状态检查",
            body = text,
            mono = false,
            onDismiss = { report = null }
        )
    }
}
