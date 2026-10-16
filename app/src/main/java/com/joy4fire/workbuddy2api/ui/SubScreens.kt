package com.joy4fire.workbuddy2api.ui

import android.content.Context
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.joy4fire.workbuddy2api.ApiHostService
import com.joy4fire.workbuddy2api.FormatKit
import com.joy4fire.workbuddy2api.KeepAliveGuide
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 二级页集合：用量统计 / 出网取证 / 存储占用 / 后台保活 / 定时参数。
 *
 * 【统一约定】
 *   ① 每个页面都用 [PcList] 起手 —— 内边距与卡片间距由它统一给出，
 *      页面自己不再写 contentPadding（上一版 5 个页面各写一遍，其中 2 个写错）。
 *   ② 说明性文字一律用 [PcNote]，不各自指定颜色字号。
 *   ③ 危险操作的确认框统一 [PcConfirmDialog](danger = true)。
 */

// ================================================================ 用量统计

/**
 * 用量统计页。
 *
 * 保留原页面的四个分组：总量指标、调用分布（协议/模型/应用）、额度、最近 24 小时趋势。
 * 「调用分布」在原始实现里是"数据早就算好了但界面一直没呈现"的部分，这里补齐。
 */
@Composable
fun UsageScreen(context: Context, app: AppState) {
    val summary = app.usageSummary
    // 用量页与记录页共用同一份查询结果，短窗口去重避免来回切页时重复查库。
    LaunchedEffect(Unit) { app.loadUsageIfStale() }

    // 趋势点在 LazyColumn 之外先算好：把 mapNotNull 写在 item 之间会破坏 LazyColumn
    // 的 content lambda 作用域（items 之外不允许出现任意语句）。
    val points = remember(app.usageSeries, app.revision) {
        (0 until app.usageSeries.length()).mapNotNull { app.usageSeries.optJSONObject(it) }
            .filter { it.optLong("count") > 0 }
    }

    PcList {
        item {
            PcCard(title = "总量") {
                PcMetricRow(
                    listOf(
                        PcMetric("今日请求", (summary?.optLong("today_requests") ?: 0).toString()),
                        PcMetric("今日 Tokens", FormatKit.tokens(summary?.optLong("today_tokens") ?: 0)),
                        PcMetric("总请求", (summary?.optLong("total_requests") ?: 0).toString())
                    )
                )
                Spacer(Modifier.height(PcTokens.SectionGap))
                PcInfoRow("总 Tokens", FormatKit.tokens(summary?.optLong("total_tokens") ?: 0))
                Spacer(Modifier.height(PcTokens.RowGap))
                PcWideButton(text = "刷新统计", primary = false) { app.loadUsage() }
            }
        }

        item { PcSection("调用分布") }
        item { BreakdownCard(summary?.optJSONArray("by_protocol"), "protocol", "成功与失败的调用都会计入协议分布。") }
        item { BreakdownCard(summary?.optJSONArray("by_model"), "model", "上游未返回模型名时归入「未指定」。") }
        item { BreakdownCard(summary?.optJSONArray("by_app"), "app", "按应用 Key 归因；Key 删除后历史记录仍保留归因。") }

        item { PcSection("额度") }
        item {
            val credits = app.credits
            PcCard {
                if (credits == null || credits.optInt("known") == 0) {
                    PcNote("额度数据尚未加载。")
                    Spacer(Modifier.height(PcTokens.SectionGap))
                    PcWideButton(text = "刷新全部账号额度", enabled = !app.busy) { app.refreshCredits() }
                } else {
                    val total = credits.optDouble("total")
                    val remain = credits.optDouble("remain")
                    val ratio = if (total > 0) (remain / total).toFloat().coerceIn(0f, 1f) else 0f
                    Text(
                        "全部剩余 ${FormatKit.credits(remain)} 积分",
                        style = MiuixTheme.textStyles.title3,
                        color = MiuixTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(2.dp))
                    PcNote(
                        "总额度 ${FormatKit.credits(total)} 积分 · " +
                            "已汇总 ${credits.optInt("known")}/${credits.optInt("accounts")} 个账号"
                    )
                    Spacer(Modifier.height(PcTokens.RowGap))
                    LinearProgressIndicator(progress = ratio, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        item { PcSection("最近 24 小时") }
        if (points.isEmpty()) {
            item { PcEmptyCard("暂无调用趋势。成功或失败的调用都会按小时聚合。") }
        } else {
            item {
                // 趋势用一张卡内的行列表，而不是每点一张卡：
                // 24 个点铺开就是 24 张卡，纯属浪费纵向空间。
                PcCard {
                    points.takeLast(12).forEachIndexed { index, point ->
                        if (index > 0) {
                            Spacer(Modifier.height(PcTokens.RowGap))
                            PcDivider()
                            Spacer(Modifier.height(PcTokens.RowGap))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                point.optString("bucket"),
                                style = MiuixTheme.textStyles.body2,
                                fontFamily = FontFamily.Monospace,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "${point.optLong("count")} 次 · ${FormatKit.tokens(point.optLong("tokens"))}",
                                style = MiuixTheme.textStyles.main
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 分组统计卡（协议 / 模型 / 应用三处共用）。积分始终显示（含 0），否则无法判断是否扣分。 */
@Composable
private fun BreakdownCard(rows: JSONArray?, key: String, emptyHint: String) {
    PcCard {
        if (rows == null || rows.length() == 0) {
            PcNote("暂无统计数据：$emptyHint")
            return@PcCard
        }
        for (i in 0 until rows.length()) {
            val item = rows.optJSONObject(i) ?: continue
            val name = item.optString(key).ifBlank { "（未指定）" }
            val credits = item.optDouble("credits", 0.0)
            PcInfoRow(
                name,
                "${item.optLong("count")} 次 · ${FormatKit.tokens(item.optLong("tokens"))} tokens · " +
                    "%.2f 积分".format(credits)
            )
        }
    }
}

// ================================================================ 出网取证

/**
 * 出网取证页 —— 与官方 CLI 逐字节比对"实际发给上游的 headers/body"。
 *
 * 因此明细必须等宽字体、且提供「复制全部」——导出文本本来就是要贴到别处 diff 的。
 * 敏感头（Authorization / X-Api-Key / Cookie）由采集侧保形脱敏：保留头名、前后缀与
 * 总长度，隐藏凭据本体，因此不影响"这个头有没有发、格式对不对"的判断。
 */
@Composable
fun InspectorScreen(context: Context, app: AppState) {
    var showAll by remember { mutableStateOf(false) }
    var detailIndex by remember { mutableStateOf<Int?>(null) }
    val entries = remember(app.revision) { app.inspectorEntries() }
    // 与设置页同理：isEnabled 每次重组都读 SharedPreferences，
    // 虽比 canDrawOverlays 轻，但没有理由每帧读一次。
    val enabled = remember(app.revision) { app.inspectorEnabled() }

    PcList {
        item {
            PcCard(title = "取证开关") {
                SwitchPreference(
                    title = "记录出网请求",
                    summary = if (enabled) {
                        "已开启 · 当前 ${entries.length()} 条（仅保留最近 20 条）"
                    } else {
                        "已关闭 · 开启后会留存请求内容（含对话正文），用完请清空"
                    },
                    checked = enabled,
                    onCheckedChange = { app.setInspectorEnabled(it) }
                )
                Spacer(Modifier.height(PcTokens.SectionGap))
                PcActionGrid(
                    actions = listOf(
                        PcAction("查看并复制全部", primary = true) { showAll = true },
                        PcAction("清空记录", enabled = entries.length() > 0) { app.clearInspector() }
                    )
                )
                Spacer(Modifier.height(PcTokens.SectionGap))
                PcNote(
                    "记录的是发送前一刻的数据，中间不再有任何改写；导出保持原始紧凑 JSON，" +
                        "可直接贴进 diff 工具。"
                )
            }
        }

        if (entries.length() > 0) {
            item { PcSection("已留存 ${entries.length()} 条") }
            item {
                PcCard {
                    for (index in 0 until entries.length()) {
                        val entry = entries.optJSONObject(index) ?: continue
                        if (index > 0) {
                            Spacer(Modifier.height(PcTokens.RowGap))
                            PcDivider()
                            Spacer(Modifier.height(PcTokens.RowGap))
                        }
                        val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                            .format(java.util.Date(entry.optLong("ts")))
                        val headerCount = entry.optJSONObject("headers")?.length() ?: 0
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stamp, style = MiuixTheme.textStyles.main)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "${entry.optString("method")} ${entry.optString("url")}",
                                    style = MiuixTheme.textStyles.body2,
                                    fontFamily = FontFamily.Monospace,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(2.dp))
                                PcNote("$headerCount 个头 · 账号 ${entry.optString("account").take(6)}")
                            }
                            top.yukonga.miuix.kmp.basic.TextButton(
                                text = "查看",
                                onClick = { detailIndex = index }
                            )
                        }
                    }
                }
            }
        } else {
            item { PcEmptyCard("暂无取证记录。开启上面的开关并产生一次调用后，记录会出现在这里。") }
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
        if (entry == null) {
            detailIndex = null
        } else {
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

/** 存储占用页。 */
@Composable
fun StorageScreen(context: Context, app: AppState) {
    var detail by remember { mutableStateOf<String?>(null) }
    val info = remember(app.revision) { app.storageInfo() }
    val breakdown = remember(app.revision) { app.storageBreakdown() }
    val contentBytes = info.optLong("input_bytes") + info.optLong("output_bytes") + info.optLong("reasoning_bytes")

    PcList {
        item {
            PcCard(title = "数据库") {
                PcMetricRow(
                    listOf(
                        PcMetric("文件大小", FormatKit.bytes(info.optLong("db_bytes"))),
                        PcMetric("记录条数", info.optLong("rows").toString()),
                        PcMetric("可回收", FormatKit.bytes(info.optLong("free_bytes")))
                    )
                )
                Spacer(Modifier.height(PcTokens.SectionGap))
                PcInfoRow("内容合计", FormatKit.bytes(contentBytes))
                PcInfoRow("· 输入", FormatKit.bytes(info.optLong("input_bytes")))
                PcInfoRow("· 输出", FormatKit.bytes(info.optLong("output_bytes")))
                PcInfoRow("· 思考链", FormatKit.bytes(info.optLong("reasoning_bytes")))
            }
        }

        item { PcSection("目录占用（实测）") }
        item {
            PcCard {
                PcInfoRow("数据目录合计", FormatKit.bytes(breakdown.optLong("data_total")))
                PcInfoRow("· 数据库", FormatKit.bytes(breakdown.optLong("databases")))
                PcInfoRow("· WebView 缓存", FormatKit.bytes(breakdown.optLong("webview")))
                PcInfoRow("· cache", FormatKit.bytes(breakdown.optLong("cache")))
                PcInfoRow("· files", FormatKit.bytes(breakdown.optLong("files")))
                PcInfoRow("· code_cache", FormatKit.bytes(breakdown.optLong("code_cache")))
                PcInfoRow("· 外部缓存", FormatKit.bytes(breakdown.optLong("external_cache")))
            }
        }

        item { PcSection("清理") }
        item {
            PcCard {
                PcNote(
                    "使用记录保存每次请求的输入/输出/思考链，是数据体积的主要来源；" +
                        "WebView 缓存没有容量上限，登录页的静态资源会长期堆积。"
                )
                Spacer(Modifier.height(PcTokens.SectionGap))
                PcActionGrid(
                    actions = listOf(
                        PcAction("清理 WebView 缓存") { app.clearWebViewCache() },
                        PcAction("截断超长记录") { app.trimUsage() },
                        PcAction("清理过期记录") {
                            val days = app.settings.optString("usage_retention_days", "30")
                                .toIntOrNull()?.coerceIn(1, 3650) ?: 30
                            app.cleanupUsage(days)
                        },
                        PcAction("生成完整报告") {
                            app.loadSettings()
                            detail = storageReport(app)
                        }
                    )
                )
                Spacer(Modifier.height(PcTokens.RowGap))
                PcNote("清理动作只删本地数据，不影响上游账号与额度。")
            }
        }
    }

    // 明细弹窗必须挂在 PcList 之外：LazyList content lambda 只接受 item/items 声明，
    // 直接在里面写 @Composable 调用会被当成非法语句（Kotlin 会报 "Expecting ')'"）。
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

/**
 * 保活引导页。
 *
 * 按厂商列出的步骤不是"建议"，而是各自 ROM 独立于 AOSP 电池优化白名单的一套管控：
 * 缺一项锁屏后就会被回收。`KeepAliveGuide` 负责给出"在设置里的哪一层、叫什么名字"，
 * 本页负责把它渲染成可点击的清单 + 一键状态检查。
 */
@Composable
fun KeepAliveScreen(context: Context, app: AppState) {
    var report by remember { mutableStateOf<String?>(null) }
    val steps = remember { KeepAliveGuide.steps() }
    val notificationsGranted = remember(app.revision) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
    val channelDisabled = remember(app.revision) { ApiHostService.isChannelDisabled(context) }
    val exactAlarmDisabled = remember(app.revision) { KeepAliveGuide.exactAlarmDisabled(context) }

    PcList {
        item {
            PcCard(title = "为什么还会被杀") {
                PcNote(
                    "${KeepAliveGuide.vendorLabel()} 对后台应用有独立于「电池优化白名单」的一套管控，" +
                        "必须逐项手动放开。白名单只解决其中一项，其余开关不开，锁屏或长时间待机后仍会被回收。"
                )
                Spacer(Modifier.height(PcTokens.RowGap))
                PcNote(
                    "另外要说清：被回收本身很难完全避免，真正影响体验的是「回收后能否自动恢复」。" +
                        "本版已加入开机自启、划掉任务后自动重启、15 分钟看门狗兜底，即使中途被杀也会自动拉回。"
                )
            }
        }

        item { PcSection("请依次完成以下设置") }
        item {
            PcCard {
                steps.forEachIndexed { index, step ->
                    if (index > 0) PcDivider()
                    top.yukonga.miuix.kmp.preference.ArrowPreference(
                        title = "${index + 1}. ${step.title}",
                        summary = step.desc,
                        onClick = {
                            KeepAliveGuide.open(context, step.key)
                            if (step.key == "lock") {
                                app.showMessage("本项无法自动跳转：请打开最近任务，下拉本应用卡片后点锁图标")
                            }
                        }
                    )
                }
            }
        }

        item { PcSection("状态检查") }
        item {
            PcCard {
                // 能自动判定的项内联成状态行，用户进来就知道哪里缺。
                // 判定不了的（自启动/后台运行等厂商私有开关）在检查报告里如实说明。
                CheckLine("电池优化白名单", remember(app.revision) {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    pm.isIgnoringBatteryOptimizations(context.packageName)
                }, app)
                CheckLine("通知权限", notificationsGranted, app)
                CheckLine("前台服务通知渠道", !channelDisabled, app)
                CheckLine("精确闹钟（看门狗）", !exactAlarmDisabled, app)

                Spacer(Modifier.height(PcTokens.SectionGap))
                PcWideButton(text = "生成完整检查报告") {
                    val (lines, manual) = KeepAliveGuide.statusCheck(context, notificationsGranted, channelDisabled)
                    report = buildString {
                        lines.forEach { append(it).append('\n') }
                        append("\n以下项系统未开放查询接口，请自行确认：\n")
                        manual.forEach { append("· ${it.title}：${it.desc}\n") }
                    }
                }
                Spacer(Modifier.height(PcTokens.ButtonGap))
                PcWideButton(
                    text = if (exactAlarmDisabled) "开启精确闹钟（必需）" else "重新检查精确闹钟",
                    primary = false
                ) {
                    if (exactAlarmDisabled) {
                        KeepAliveGuide.requestExactAlarm(context)
                    } else {
                        app.showMessage("精确闹钟已允许")
                    }
                }
                if (channelDisabled) {
                    Spacer(Modifier.height(PcTokens.ButtonGap))
                    PcWideButton(text = "重开前台服务通知渠道", primary = false) {
                        KeepAliveGuide.openChannelSettings(context)
                    }
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

/** 单条状态行：✓/✗ + 名称 + 可选修复入口。 */
@Composable
private fun CheckLine(label: String, ok: Boolean, app: AppState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = PcTokens.RowGap / 2),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (ok) "✓" else "✗",
            style = MiuixTheme.textStyles.main,
            color = if (ok) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.error
        )
        Spacer(Modifier.height(0.dp))
        Text(
            "  $label",
            style = MiuixTheme.textStyles.main,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            if (ok) "已就绪" else "需处理",
            style = MiuixTheme.textStyles.body2,
            color = if (ok) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.error
        )
    }
}

// ================================================================ 定时参数

/**
 * 定时参数二级页 —— 承接原先铺在设置页首屏的四项数值配置。
 *
 * 为什么拆出来：设置页首屏组件数越少越不容易被卡（设备高温降频时尤其明显），
 * 而这四项都是低频修改项，放进二级页后首屏只需一个入口行。
 *
 * 每项仍是「点击弹窗编辑」—— 不为了一行配置再引入 TextField 的常驻开销：
 * Miuix 的 TextField 每个实例会启动 4 个动画（边框宽度/边框颜色/标签位移/标签字号），
 * 5 个常驻输入框等于 20 个 Animatable 在首次组合时同时创建，那是设置页卡顿的直接原因。
 */
@Composable
fun ScheduleScreen(context: Context, app: AppState) {
    val settings = app.settings
    PcList {
        item {
            PcCard(title = "定时参数") {
                SettingRow(
                    label = "每日签到时段",
                    value = settings.optString("checkin_hours", "9,21"),
                    hint = "24 小时制，逗号分隔，例如 9,21",
                    onCommit = { app.saveSetting("checkin_hours", it) }
                )
                PcDivider()
                SettingRow(
                    label = "额度刷新间隔（分钟）",
                    value = settings.optString("credit_refresh_min", "30"),
                    hint = "间隔越短越及时，也越耗电与流量",
                    onCommit = { app.saveSetting("credit_refresh_min", it) }
                )
                PcDivider()
                SettingRow(
                    label = "模型缓存（分钟）",
                    value = settings.optString("model_ttl_min", "60"),
                    hint = "缓存过期后会自动重新拉取模型目录",
                    onCommit = { app.saveSetting("model_ttl_min", it) }
                )
                PcDivider()
                SettingRow(
                    label = "使用记录保留天数",
                    value = settings.optString("usage_retention_days", "30"),
                    hint = "控制数据体积最有效的一项",
                    onCommit = { app.saveSetting("usage_retention_days", it) }
                )
            }
        }
        item {
            PcCard {
                PcNote(
                    "参数保存后立即生效，无需重启服务。签到时段用于账号保活任务，" +
                        "到点后会自动为国内版账号签到；国际版额度由上游自动发放，不参与签到。"
                )
            }
        }
    }
}
