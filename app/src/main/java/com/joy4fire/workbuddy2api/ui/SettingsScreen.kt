package com.joy4fire.workbuddy2api.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.joy4fire.workbuddy2api.ApiHostService
import com.joy4fire.workbuddy2api.FormatKit
import com.joy4fire.workbuddy2api.KeepAliveGuide
import com.joy4fire.workbuddy2api.NativeCore
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置页 —— 对应原界面 settingsPage()。
 *
 * 原页面是一个超长页面，包含：自动化任务配置、数据管理、出网取证、系统权限、保活引导。
 * 这里把四个重项（出网取证 / 存储占用 / 后台保活 / 提示词）提升为二级页面入口，
 * 主干只留开关与高频项 —— 因为原页面长度已超过 3 屏，用户很难找到想要的那一项。
 */
@Composable
fun SettingsScreen(context: Context, app: AppState, onOpenSub: (SubPage) -> Unit) {
    // 临时性能埋点：定位"点击设置要 3 秒才响应"的真实耗时点。
    // 用 remember 包住，只在进入页面时打一次，不干扰每帧。
    val perfTag = remember { System.currentTimeMillis() }

    val settings = app.settings
    val activity = LocalContext.current as? Activity

    val t1 = remember { System.currentTimeMillis() }
    val versionText = remember(context) { installedVersion(context) }
    val t2 = remember { System.currentTimeMillis() }
    val vendorText = remember { KeepAliveGuide.vendorLabel() }
    val t3 = remember { System.currentTimeMillis() }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        android.util.Log.i("WbPerf",
            "Settings 分段耗时：进入块=${t1 - perfTag}ms，" +
                "installedVersion=${t2 - t1}ms，vendorLabel=${t3 - t2}ms，" +
                "组合完成=${System.currentTimeMillis() - t3}ms；" +
                "settings键数=${settings.length()} accounts=${app.accounts.length()}")
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = PcTokens.SpaceM, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(PcTokens.CardGap)
    ) {
        item {
            PcCard(title = "自动化任务") {
                SwitchPreference(
                    title = "启用账号保活",
                    summary = "定时刷新令牌与额度，保持账号可用",
                    checked = settings.optString("keepalive_enabled", "1") in setOf("1", "true"),
                    onCheckedChange = { app.saveSetting("keepalive_enabled", if (it) "1" else "0") }
                )
                // 以下四项合并为一行入口，首屏不再铺开四行：
                // 它们都是低频修改项（签到时段/刷新间隔/缓存时长/保留天数），
                // 放在二级页后，设置页首屏的组件数从 7 项降到 3 项，
                // 页面组合成本显著下降 —— 对高温降频的设备尤其重要。
                ArrowPreference(
                    title = "定时参数",
                    summary = "签到时段 ${settings.optString("checkin_hours", "9,21")} · " +
                        "额度刷新 ${settings.optString("credit_refresh_min", "30")} 分钟 · " +
                        "模型缓存 ${settings.optString("model_ttl_min", "60")} 分钟 · " +
                        "记录保留 ${settings.optString("usage_retention_days", "30")} 天",
                    onClick = { onOpenSub(SubPage.SCHEDULE) }
                )
                Spacer(Modifier.height(PcTokens.SpaceS))
                Row(horizontalArrangement = Arrangement.spacedBy(PcTokens.SpaceS)) {
                    TextButton(
                        text = "立即刷新模型",
                        onClick = { app.refreshModels() },
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        text = "立即刷新额度",
                        onClick = { app.refreshCredits() },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        item {
            PcCard(title = "提示词") {
                ArrowPreference(
                    title = "提示词注入",
                    summary = "接管 system / 指纹清洗 / 降级开关",
                    onClick = { onOpenSub(SubPage.PROMPT) }
                )
            }
        }

        item {
            PcCard(title = "数据管理") {
                // 「调用记录」从底部 Tab 迁到这里：它是低频查阅项，
                // 放在设置里能让底部导航从 7 个精简到 6 个。
                ArrowPreference(
                    title = "调用记录",
                    summary = "查看每次请求的模型、扣费账号、Tokens 与积分消耗",
                    onClick = { onOpenSub(SubPage.RECORDS) }
                )
                ArrowPreference(
                    title = "存储占用",
                    summary = "数据库体积、目录占用与清理",
                    onClick = { onOpenSub(SubPage.STORAGE) }
                )
                ArrowPreference(
                    title = "用量统计",
                    summary = "请求、Tokens 与额度概览",
                    onClick = { onOpenSub(SubPage.USAGE) }
                )
                ArrowPreference(
                    title = "出网取证",
                    summary = if (app.inspectorEnabled()) "已开启 · 当前 ${app.inspectorCount()} 条" else "已关闭 · 用于与官方 CLI 比对",
                    onClick = { onOpenSub(SubPage.INSPECTOR) }
                )
                // 三个清理动作统一走 PcActionGrid：等宽、有间距、自动折行。
                // 此前是三个满宽按钮垂直相邻，会紧贴成一整块（"功能键融一块"）。
                PcActionGrid(
                    actions = listOf(
                        PcAction("清理 WebView 缓存") { app.clearWebViewCache() },
                        PcAction("截断超长记录内容") { app.trimUsage() },
                        PcAction("清理过期记录") {
                            val days = settings.optString("usage_retention_days", "30").toIntOrNull()?.coerceIn(1, 3650) ?: 30
                            app.cleanupUsage(days)
                        }
                    )
                )
            }
        }

        item {
            PcCard(title = "后台保活 · $vendorText") {
                SwitchPreference(
                    title = "悬浮球保活（可选）",
                    summary = "屏幕上显示服务状态球。可见窗口能提升进程优先级，但挡不住「一键清理」。",
                    checked = app.floatingEnabled(),
                    onCheckedChange = { app.setFloating(it, activity) }
                )
                Spacer(Modifier.height(PcTokens.SpaceS / 2))
                Text(
                    app.floatingStateText(),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(PcTokens.SpaceM))
                ArrowPreference(
                    title = "保活引导与状态检查",
                    summary = "按厂商逐步放开后台限制，并检查是否还有漏项",
                    onClick = { onOpenSub(SubPage.KEEPALIVE) }
                )
            }
        }

        item { SmallTitle(text = "系统") }
        item {
            PcCard {
                ArrowPreference(
                    title = "电池优化白名单",
                    summary = "防后台冻结，其他软件随时可连",
                    onClick = { KeepAliveGuide.requestIgnoreBattery(context) }
                )
                ArrowPreference(
                    title = "通知权限",
                    summary = "前台服务运行状态",
                    onClick = { KeepAliveGuide.openNotificationSettings(context) }
                )
                ArrowPreference(
                    title = "应用详情",
                    summary = "电池、存储和权限",
                    onClick = { KeepAliveGuide.openAppDetails(context) }
                )
            }
        }

        item { SmallTitle(text = "关于") }
        item {
            PcCard {
                PcInfoRow("本地端口", ApiHostService.PORT.toString())
                PcInfoRow("国内后端", NativeCore.BACKEND)
                PcInfoRow("国际后端", NativeCore.INTERNATIONAL_BACKEND)
                PcInfoRow("版本", versionText)
            }
        }
    }
}

/** 悬浮球状态说明（把「开关」与「权限」分开说，否则用户会困惑于"开了却没效果"）。 */
private fun AppState.floatingStateText(): String {    val enabled = floatingEnabled()
    val permitted = floatingPermitted()
    return when {
        !enabled -> "未启用。启用后需授予「显示在其他应用上层」权限。"
        !permitted -> "已开启但缺少悬浮窗权限，请重新开关一次以授权。"
        !ApiHostService.running -> "已开启且权限就绪；服务启动后会显示悬浮球。"
        else -> "运行中：悬浮球已显示，进程处于可见状态。"
    }
}

/**
 * 读取本机已安装的版本名。
 *
 * 为什么从 PackageManager 读而不是用 BuildConfig：
 *   本项目未开启 buildConfig 生成，且这样读到的就是【设备上实际运行的版本】——
 *   排查"是否装上了新版"时，这比读编译期常量更可信。
 */
private fun installedVersion(context: Context): String = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    // longVersionCode 需要 API 28+，minSdk 为 24，故低版本回退到 versionCode。
    val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    "${info.versionName} ($code)"
}.getOrDefault("未知")
