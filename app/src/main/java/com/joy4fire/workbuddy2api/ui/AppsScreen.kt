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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.joy4fire.workbuddy2api.AccountRegion
import com.joy4fire.workbuddy2api.ApiHostService
import com.joy4fire.workbuddy2api.FormatKit
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 应用页 —— 对应原界面 appsPage() + showAppEditor() + showAppKey()。
 *
 * 原实现用 AlertDialog 装一整套表单（名称/备注/Key 模式/调用版本）。
 * Compose 下改成 WindowDialog 内嵌表单，字段与校验规则完全一致：
 *   名称必填；自定义 Key 需 16–256 位且不含空格。
 */
@Composable
fun AppsScreen(context: Context, app: AppState) {
    var editing by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var creating by remember { mutableStateOf(false) }
    var showKeyDialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingDelete by remember { mutableStateOf<Pair<Long, String>?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = PcTokens.SpaceM, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(PcTokens.CardGap)
    ) {
        item {
            PcCard(title = "API Key 控制台") {
                Text(
                    "每个 Key 都可独立命名、设置备注并固定调用国内版或国际版账号；" +
                        "编辑版本后，新请求会立即按新版本路由。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(PcTokens.SpaceM))
                Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) { Text("＋ 创建新 Key") }
            }
        }

        item { SmallTitle(text = "接入配置") }
        item {
            PcCard {
                PcInfoRow("Base URL", "http://127.0.0.1:${ApiHostService.PORT}/v1")
                PcInfoRow("认证请求头", "Authorization: Bearer <你的 Key>")
                PcInfoRow("兼容请求头", "X-Api-Key: <你的 Key>")
                Spacer(Modifier.height(PcTokens.SpaceM))
                Row(horizontalArrangement = Arrangement.spacedBy(PcTokens.SpaceS)) {
                    TextButton(
                        text = "复制地址",
                        onClick = {
                            copyToClipboard(context, "http://127.0.0.1:${ApiHostService.PORT}/v1")
                            app.showMessage("已复制 Base URL")
                        },
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        text = "复制认证格式",
                        onClick = {
                            copyToClipboard(context, "Authorization: Bearer <你的 Key>")
                            app.showMessage("已复制认证格式")
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (app.apps.length() == 0) {
            item {
                PcCard(title = "还没有 API Key") {
                    Text(
                        "创建后可选择自动生成安全 Key，或使用你已有的自定义 Key。",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        } else {
            item { SmallTitle(text = "应用凭据 · ${app.apps.length()} 个") }
            items(app.apps.length()) { index ->
                val item = app.apps.optJSONObject(index) ?: return@items
                val id = item.optLong("id")
                val enabled = item.optBoolean("enabled")
                val name = item.optString("name")
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = PcTokens.PageGutter),
                    insideMargin = PaddingValues(horizontal = PcTokens.SpaceL, vertical = PcTokens.SpaceM)
                ) {
                    Column {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(name, style = MiuixTheme.textStyles.main, modifier = Modifier.weight(1f))
                            Text(
                                if (enabled) "已启用" else "已停用",
                                style = MiuixTheme.textStyles.body2,
                                color = if (enabled) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                        if (item.optString("note").isNotBlank()) {
                            Spacer(Modifier.height(PcTokens.SpaceS / 2))
                            Text(item.optString("note"), style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        Spacer(Modifier.height(PcTokens.SpaceS / 2))
                        Text(
                            "${item.optString("region_label", "国内版")} · " +
                                "${item.optLong("requests")} 次请求 · ${FormatKit.tokens(item.optLong("tokens"))} tokens",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                        Spacer(Modifier.height(PcTokens.SpaceS / 2))
                        Text(
                            item.optString("key_prefix", "••••••••"),
                            style = MiuixTheme.textStyles.body2,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                        Spacer(Modifier.height(PcTokens.SpaceM))
                        // 统一走 PcActionGrid：四个按钮分两行、等宽、行间有固定间距。
                        // 此前是两排 Row 紧贴（中间没有 Spacer），视觉上上下黏在一起。
                        PcActionGrid(
                            actions = listOf(
                                PcAction("查看 Key") {
                                    val key = app.appKey(id)
                                    if (key.isNullOrBlank()) app.showMessage("无法读取 API Key，可通过编辑替换")
                                    else showKeyDialog = name to key
                                },
                                PcAction("编辑") { editing = item },
                                PcAction(if (enabled) "停用" else "启用") { app.toggleApp(id, name, !enabled) },
                                PcAction("删除") { pendingDelete = id to name }
                            )
                        )
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        AppEditorDialog(
            existing = editing,
            onDismiss = { creating = false; editing = null },
            onSaved = { saved, isEdit ->
                creating = false
                editing = null
                val key = saved.optString("key")
                if (!isEdit || key.isNotBlank()) {
                    showKeyDialog = "${saved.optString("name")} · ${saved.optString("region_label")}" to key
                } else app.showMessage("更改已保存")
            },
            app = app
        )
    }

    showKeyDialog?.let { (label, key) ->
        PcDetailDialog(
            show = true,
            title = "$label · API Key",
            body = "$key\n\n请仅提供给受信任的客户端。可随时回到此页查看或替换。",
            copyLabel = "复制 Key",
            onCopy = {
                copyToClipboard(context, key)
                app.showMessage("已复制 API Key")
            },
            onDismiss = { showKeyDialog = null }
        )
    }

    PcConfirmDialog(
        show = pendingDelete != null,
        title = "删除 ${pendingDelete?.second}？",
        message = "API Key 将立即失效，历史调用记录会保留。",
        confirmText = "删除",
        onConfirm = {
            pendingDelete?.let { app.deleteApp(it.first, it.second) }
            pendingDelete = null
        },
        onDismiss = { pendingDelete = null }
    )
}

/**
 * API Key 编辑器 —— 对应原界面的 showAppEditor。
 *
 * 三个要点与原实现保持一致：
 *   1) 编辑时默认「保留当前 Key」，只有显式选择替换才会让旧 Key 失效
 *      （否则用户改个备注就意外作废了正在用的 Key）；
 *   2) 自定义 Key 校验 16–256 位、不含空格；
 *   3) 版本选择用 TabRow 而不是 RadioGroup —— 只有两个选项，Tab 更省纵向空间。
 */
@Composable
private fun AppEditorDialog(
    existing: org.json.JSONObject?,
    onDismiss: () -> Unit,
    onSaved: (org.json.JSONObject, Boolean) -> Unit,
    app: AppState
) {
    val isEdit = existing != null
    var name by remember { mutableStateOf(existing?.optString("name").orEmpty()) }
    var note by remember { mutableStateOf(existing?.optString("note").orEmpty()) }
    var regionIndex by remember {
        mutableStateOf(
            if (existing != null && AccountRegion.from(existing.optString("region")) == AccountRegion.INTERNATIONAL) 1 else 0
        )
    }
    var keyMode by remember { mutableStateOf(0) } // 0 = 保留/自动生成，1 = 自定义
    var customKey by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    top.yukonga.miuix.kmp.window.WindowDialog(
        show = true,
        title = if (isEdit) "编辑 API Key" else "创建 API Key",
        onDismissRequest = onDismiss,
        content = {
            Column(Modifier.fillMaxWidth()) {
                TextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = "应用名称",
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(PcTokens.SpaceS))
                TextField(
                    value = note,
                    onValueChange = { note = it },
                    label = "备注（可选）",
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(PcTokens.SpaceM))
                Text("API Key", style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                TabRow(
                    tabs = listOf(
                        if (isEdit) "保留当前 Key" else "自动生成",
                        if (isEdit) "替换为自定义" else "使用自定义"
                    ),
                    selectedTabIndex = keyMode,
                    onTabSelected = { keyMode = it }
                )
                if (keyMode == 1) {
                    Spacer(Modifier.height(PcTokens.SpaceS))
                    TextField(
                        value = customKey,
                        onValueChange = { customKey = it; error = null },
                        label = "16–256 位，不含空格",
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(PcTokens.SpaceM))
                Text("调用版本", style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                TabRow(
                    tabs = listOf("国内版", "国际版"),
                    selectedTabIndex = regionIndex,
                    onTabSelected = { regionIndex = it }
                )
                Spacer(Modifier.height(PcTokens.SpaceS / 2))
                Text(
                    if (regionIndex == 0) "请求只会路由到国内版账号" else "请求只会路由到国际版账号",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )

                if (error != null) {
                    Spacer(Modifier.height(PcTokens.SpaceS))
                    Text(error.orEmpty(), style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.error)
                }

                Spacer(Modifier.height(PcTokens.SpaceM))
                Row(horizontalArrangement = Arrangement.spacedBy(PcTokens.SpaceS)) {
                    TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                    Button(
                        onClick = {
                            val appName = name.trim()
                            if (appName.isBlank()) { error = "请输入应用名称"; return@Button }
                            val replacement = customKey.takeIf { keyMode == 1 }
                            if (keyMode == 1 && replacement.orEmpty().length !in 16..256) {
                                error = "自定义 Key 需要 16–256 位字符"; return@Button
                            }
                            val region = if (regionIndex == 0) AccountRegion.DOMESTIC else AccountRegion.INTERNATIONAL
                            val result = runCatching {
                                if (isEdit) app.updateApp(existing!!.optLong("id"), appName, note.trim(), region, replacement)
                                else app.createApp(appName, note.trim(), region, replacement)
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
