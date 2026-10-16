package com.joy4fire.workbuddy2api.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.joy4fire.workbuddy2api.AccountRegion
import com.joy4fire.workbuddy2api.ApiHostService
import com.joy4fire.workbuddy2api.FormatKit
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 应用页 —— API Key 控制台。
 *
 * 【重写要点】
 *   上一版每张 Key 卡上摆了四个按钮（查看/编辑/停用/删除），并且把
 *   「Base URL / 认证头 / 兼容头」三个键值行常驻在页面顶部 —— 结果：
 *     · 用户第一次接入时要的信息（怎么填 Base URL）被淹没在 Key 列表里；
 *     · 每张卡的四个按钮里，「删除」和「停用」挨着，误触代价高。
 *
 *   现在拆成三块：
 *     ① 接入参数卡：把 Base URL / 认证头 / 兼容头 做成"一键复制"的三行，
 *        这才是用户真正要抄走的东西，值得占据首屏；
 *     ② Key 列表：一行一个 Key（名称 + 状态 + 前缀 + 用量），点整行看详情；
 *     ③ 详情弹窗：查看完整 Key（含复制）、编辑、启停、删除都在这里，
 *        删除单独一行并用错误色。
 *
 * 校验规则与原实现一致：名称必填；自定义 Key 需 16–256 位且不含空格。
 */
@Composable
fun AppsScreen(context: Context, app: AppState) {
    var editing by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var creating by remember { mutableStateOf(false) }
    var detailApp by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var showKey by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingDelete by remember { mutableStateOf<Pair<Long, String>?>(null) }

    val baseUrl = "http://127.0.0.1:${ApiHostService.PORT}/v1"

    PcList {
        // ---------------- 接入参数 ----------------
        item {
            PcCard(title = "接入参数") {
                PcMonoRow("Base URL", baseUrl)
                PcMonoRow("主认证头", "Authorization: Bearer <你的 Key>")
                PcMonoRow("兼容头", "X-Api-Key: <你的 Key>")
                Spacer(Modifier.height(PcTokens.SectionGap))
                PcActionGrid(
                    actions = listOf(
                        PcAction("复制 Base URL", primary = true) {
                            copyToClipboard(context, baseUrl)
                            app.showMessage("已复制 Base URL")
                        },
                        PcAction("复制认证头格式") {
                            copyToClipboard(context, "Authorization: Bearer ")
                            app.showMessage("已复制认证头前缀")
                        }
                    )
                )
                Spacer(Modifier.height(PcTokens.RowGap))
                PcNote("把上面三项填进任意 OpenAI / Anthropic 兼容客户端即可；Key 由下方列表创建。")
            }
        }

        // ---------------- Key 列表 ----------------
        item {
            PcCard(title = "API Key 控制台") {
                Text(
                    "每个 Key 可独立命名、写备注，并固定路由到国内版或国际版账号。" +
                        "改动版本后，新请求立即按新版本路由。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(PcTokens.SectionGap))
                PcWideButton(text = "＋ 创建新 Key") { creating = true }
            }
        }

        if (app.apps.length() == 0) {
            item { PcSection("已有 Key") }
            item {
                PcEmptyCard("还没有 API Key。点上方按钮创建，可选择自动生成或使用你已有的自定义 Key。")
            }
        } else {
            item { PcSection("已有 Key · ${app.apps.length()} 个") }
            item {
                // 整份 Key 列表放进一张卡：Key 通常不超过十几个，
                // 一卡一行比一卡一 Key 少一个数量级的布局层级，滚动更稳。
                PcCard {
                    for (index in 0 until app.apps.length()) {
                        val item = app.apps.optJSONObject(index) ?: continue
                        if (index > 0) {
                            Spacer(Modifier.height(PcTokens.RowGap))
                            PcDivider()
                            Spacer(Modifier.height(PcTokens.RowGap))
                        }
                        AppKeyRow(item = item, onClick = { detailApp = item })
                    }
                }
            }
        }
    }

    // ---------------- 创建 / 编辑 ----------------
    if (creating || editing != null) {
        AppEditorDialog(
            existing = editing,
            onDismiss = {
                creating = false
                editing = null
            },
            onSaved = { saved, isEdit ->
                creating = false
                editing = null
                val key = saved.optString("key")
                // 新建时一定有 Key 可展示；编辑时只有在真的替换了 Key 时才展示，
                // 否则用户会以为"我只是改了个备注，Key 就换了"。
                if (!isEdit || key.isNotBlank()) {
                    showKey = "${saved.optString("name")} · ${saved.optString("region_label")}" to key
                } else {
                    app.showMessage("更改已保存")
                }
            },
            app = app
        )
    }

    // ---------------- Key 详情 ----------------
    detailApp?.let { item ->
        val id = item.optLong("id")
        AppDetailDialog(
            item = item,
            onViewKey = {
                val key = app.appKey(id)
                if (key.isNullOrBlank()) {
                    app.showMessage("无法读取 API Key，可通过「编辑」替换为新 Key")
                } else {
                    showKey = "${item.optString("name")} · ${item.optString("region_label")}" to key
                }
            },
            onEdit = { editing = item },
            onToggle = { app.toggleApp(id, item.optString("name"), !item.optBoolean("enabled")) },
            onDelete = { pendingDelete = id to item.optString("name") },
            onDismiss = { detailApp = null }
        )
    }

    // ---------------- Key 明文展示 ----------------
    showKey?.let { (label, key) ->
        PcDetailDialog(
            show = true,
            title = "$label · API Key",
            body = "$key\n\n请仅提供给受信任的客户端；可随时回到本页查看或替换。",
            copyLabel = "复制 Key",
            onCopy = {
                copyToClipboard(context, key)
                app.showMessage("已复制 API Key")
            },
            onDismiss = { showKey = null }
        )
    }

    PcConfirmDialog(
        show = pendingDelete != null,
        title = "删除 ${pendingDelete?.second}？",
        message = "该 API Key 会立即失效，使用它的客户端将收到鉴权失败；历史调用记录会保留。",
        confirmText = "删除",
        danger = true,
        onConfirm = {
            pendingDelete?.let { app.deleteApp(it.first, it.second) }
            pendingDelete = null
        },
        onDismiss = { pendingDelete = null }
    )
}

/** Key 列表的一行：名称 + 状态 / 前缀 + 用量。点整行进详情。 */
@Composable
private fun AppKeyRow(item: org.json.JSONObject, onClick: () -> Unit) {
    val enabled = item.optBoolean("enabled")
    top.yukonga.miuix.kmp.basic.Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        insideMargin = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 0.dp,
            vertical = PcTokens.RowGap / 2
        )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.optString("name").ifBlank { "未命名 Key" },
                        style = MiuixTheme.textStyles.main,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.height(0.dp))
                    PcStatus(if (enabled) "启用" else "停用", enabled)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    item.optString("key_prefix", "••••••••"),
                    style = MiuixTheme.textStyles.body2,
                    fontFamily = FontFamily.Monospace,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${item.optString("region_label", "国内版")} · " +
                        "${item.optLong("requests")} 次请求 · ${FormatKit.tokens(item.optLong("tokens"))} tokens" +
                        item.optString("note").takeIf { it.isNotBlank() }?.let { " · $it" } ?: "",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(0.dp))
            Text("›", style = MiuixTheme.textStyles.main, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

/**
 * Key 详情弹窗：读取完整 Key、编辑、启停、删除。
 *
 * 删除按钮单独一行 + 错误色：它与"停用"在语义上差别巨大（一个可逆一个不可逆），
 * 放在同一行相邻位置会显著提高误触概率。
 */
@Composable
private fun AppDetailDialog(
    item: org.json.JSONObject,
    onViewKey: () -> Unit,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val enabled = item.optBoolean("enabled")
    top.yukonga.miuix.kmp.window.WindowDialog(
        show = true,
        title = item.optString("name").ifBlank { "API Key" },
        summary = "${item.optString("region_label", "国内版")} · ${if (enabled) "已启用" else "已停用"}",
        onDismissRequest = onDismiss,
        content = {
            Column(Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = PcTokens.DialogBodyMax)
                        .verticalScroll(rememberScrollState())
                ) {
                    PcMonoRow("Key 前缀", item.optString("key_prefix", "••••••••"))
                    PcInfoRow("调用版本", item.optString("region_label", "国内版"))
                    PcInfoRow("累计请求", item.optLong("requests").toString())
                    PcInfoRow("累计 Tokens", FormatKit.tokens(item.optLong("tokens")))
                    PcInfoRow("备注", item.optString("note").ifBlank { "（无）" })
                    Spacer(Modifier.height(PcTokens.RowGap))
                    PcNote("完整 Key 只在创建时可见；若已丢失，用「查看完整 Key」读取本机加密存储，或用「编辑」替换。")
                }
                Spacer(Modifier.height(PcTokens.SectionGap))
                PcWideButton(text = "查看完整 Key", primary = true) { onViewKey() }
                Spacer(Modifier.height(PcTokens.ButtonGap))
                PcActionGrid(
                    actions = listOf(
                        PcAction("编辑") { onEdit() },
                        PcAction(if (enabled) "停用" else "启用") { onToggle() }
                    )
                )
                Spacer(Modifier.height(PcTokens.ButtonGap))
                TextButton(
                    text = "删除这个 Key",
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

/**
 * API Key 编辑器。
 *
 * 三个要点与原实现一致，且都有明确理由：
 *   1) 编辑时默认「保留当前 Key」——只有显式选择替换才会让旧 Key 失效，
 *      否则用户改个备注就意外作废了正在用的 Key；
 *   2) 自定义 Key 校验 16–256 位、不含空格；
 *   3) 版本用 TabRow 而不是下拉——只有两个选项，Tab 省一次点击也省纵向空间。
 */
@Composable
private fun AppEditorDialog(
    existing: org.json.JSONObject?,
    onDismiss: () -> Unit,
    onSaved: (org.json.JSONObject, Boolean) -> Unit,
    app: AppState
) {
    val isEdit = existing != null
    var name by remember(existing) { mutableStateOf(existing?.optString("name").orEmpty()) }
    var note by remember(existing) { mutableStateOf(existing?.optString("note").orEmpty()) }
    var regionIndex by remember(existing) {
        mutableStateOf(
            if (existing != null &&
                AccountRegion.from(existing.optString("region")) == AccountRegion.INTERNATIONAL
            ) 1 else 0
        )
    }
    var keyMode by remember(existing) { mutableStateOf(0) } // 0 = 保留/自动生成，1 = 自定义
    var customKey by remember(existing) { mutableStateOf("") }
    var error by remember(existing) { mutableStateOf<String?>(null) }

    top.yukonga.miuix.kmp.window.WindowDialog(
        show = true,
        title = if (isEdit) "编辑 API Key" else "创建 API Key",
        onDismissRequest = onDismiss,
        content = {
            Column(Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = PcTokens.DetailBodyMax)
                        .verticalScroll(rememberScrollState())
                ) {
                    TextField(
                        value = name,
                        onValueChange = { name = it; error = null },
                        label = "应用名称（必填）",
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(PcTokens.RowGap))
                    TextField(
                        value = note,
                        onValueChange = { note = it },
                        label = "备注（可选）",
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(PcTokens.SectionGap))
                    Text("Key 来源", style = MiuixTheme.textStyles.main)
                    Spacer(Modifier.height(PcTokens.RowGap))
                    TabRow(
                        tabs = listOf(
                            if (isEdit) "保留当前 Key" else "自动生成",
                            if (isEdit) "替换为自定义" else "使用自定义"
                        ),
                        selectedTabIndex = keyMode,
                        onTabSelected = { keyMode = it }
                    )
                    if (keyMode == 1) {
                        Spacer(Modifier.height(PcTokens.RowGap))
                        TextField(
                            value = customKey,
                            onValueChange = { customKey = it; error = null },
                            label = "16–256 位，不含空格",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(Modifier.height(PcTokens.SectionGap))
                    Text("调用版本", style = MiuixTheme.textStyles.main)
                    Spacer(Modifier.height(PcTokens.RowGap))
                    TabRow(
                        tabs = listOf("国内版", "国际版"),
                        selectedTabIndex = regionIndex,
                        onTabSelected = { regionIndex = it }
                    )
                    Spacer(Modifier.height(PcTokens.RowGap))
                    PcNote(
                        if (regionIndex == 0) "使用该 Key 的请求只会路由到国内版账号。"
                        else "使用该 Key 的请求只会路由到国际版账号。"
                    )

                    if (error != null) {
                        Spacer(Modifier.height(PcTokens.RowGap))
                        PcErrorText(error.orEmpty())
                    }
                }

                Spacer(Modifier.height(PcTokens.SectionGap))
                Row(horizontalArrangement = Arrangement.spacedBy(PcTokens.ButtonGap)) {
                    TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                    top.yukonga.miuix.kmp.basic.Button(
                        onClick = {
                            val appName = name.trim()
                            if (appName.isBlank()) {
                                error = "请输入应用名称"
                                return@Button
                            }
                            val replacement = customKey.takeIf { keyMode == 1 }
                            if (keyMode == 1 && replacement.orEmpty().length !in 16..256) {
                                error = "自定义 Key 需要 16–256 位字符"
                                return@Button
                            }
                            if (keyMode == 1 && replacement.orEmpty().any { it.isWhitespace() }) {
                                error = "自定义 Key 不能包含空格"
                                return@Button
                            }
                            val region = if (regionIndex == 0) AccountRegion.DOMESTIC else AccountRegion.INTERNATIONAL
                            val result = runCatching {
                                if (isEdit) {
                                    app.updateApp(existing!!.optLong("id"), appName, note.trim(), region, replacement)
                                } else {
                                    app.createApp(appName, note.trim(), region, replacement)
                                }
                            }
                            result.fold(
                                onSuccess = { saved -> if (saved != null) onSaved(saved, isEdit) else onDismiss() },
                                onFailure = { error = it.message ?: "保存失败" }
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(if (isEdit) "保存更改" else "创建 Key") }
                }
            }
        }
    )
}
