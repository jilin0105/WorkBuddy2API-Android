package com.joy4fire.workbuddy2api.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置页 —— 分组入口式布局（性能优先版）。
 *
 * 【为什么改成分组入口】
 *   实测埋点数据：本页「主线程读数据库」仅 1ms，而「渲染完成」要 2612ms。
 *   卡顿与数据无关，全部来自组合/布局/绘制的组件开销。
 *   因此唯一有效的解法是【减少首屏需要实例化的组件数量】。
 *
 *   原布局首屏要建：3 张卡片 + 1 个 SwitchPreference + 10 个 ArrowPreference
 *   + 2 个 Button，约 16 个可交互组件，且 ArrowPreference 各自带图标与
 *   交互状态订阅。现布局首屏只有 8 行纯文本入口 + 1 个开关，
 *   其余全部下钻到二级页，由 LazyColumn 懒加载 —— 不在视口内的行不实例化。
 */
@Composable
fun SettingsScreen(context: Context, app: AppState, onOpenSub: (SubPage) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = PcTokens.SpaceM, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(PcTokens.CardGap)
    ) {
        item {
            PcCard(title = "常用") {
                SwitchRow(
                    title = "启用账号保活",
                    summary = "定时刷新令牌与额度，保持账号可用",
                    checked = app.settings.optString("keepalive_enabled", "1") in setOf("1", "true"),
                    onChange = { app.saveSetting("keepalive_enabled", if (it) "1" else "0") }
                )
                PcRow(
                    title = "提示词注入",
                    summary = "接管 system / 指纹清洗 / 降级开关",
                    value = "›",
                    onClick = { onOpenSub(SubPage.PROMPT) }
                )
            }
        }

        item {
            PcCard(title = "自动化") {
                PcRow(
                    title = "定时参数",
                    summary = "签到时段 / 额度刷新 / 模型缓存 / 记录保留",
                    value = "›",
                    onClick = { onOpenSub(SubPage.SCHEDULE) }
                )
                PcRow(
                    title = "后台保活",
                    summary = "按厂商逐步放开限制，并检查漏项",
                    value = "›",
                    onClick = { onOpenSub(SubPage.KEEPALIVE) }
                )
            }
        }

        item {
            PcCard(title = "数据与诊断") {
                PcRow(
                    title = "调用记录",
                    summary = "模型、扣费账号、Tokens 与积分消耗",
                    value = "›",
                    onClick = { onOpenSub(SubPage.RECORDS) }
                )
                PcRow(
                    title = "存储占用",
                    summary = "数据库体积、目录占用与清理",
                    value = "›",
                    onClick = { onOpenSub(SubPage.STORAGE) }
                )
                PcRow(
                    title = "用量统计",
                    summary = "请求、Tokens 与额度概览",
                    value = "›",
                    onClick = { onOpenSub(SubPage.USAGE) }
                )
                PcRow(
                    title = "出网取证",
                    summary = "与官方 CLI 逐字节比对的请求记录",
                    value = "›",
                    onClick = { onOpenSub(SubPage.INSPECTOR) }
                )
            }
        }

        item { SmallTitle(text = "关于") }
        item {
            PcCard {
                PcInfoRow("版本", installedVersion(context))
                PcInfoRow("本地端口", "8788")
            }
        }
    }
}

/** 轻量开关行：直接用 Switch，不经过 Preference 包装层。 */
@Composable
private fun SwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = PcTokens.SpaceS / 2)) {
        Text(title, style = MiuixTheme.textStyles.main)
        Text(
            summary,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * 读取本机已安装的版本名。
 *
 * 从 PackageManager 读而非 BuildConfig：本项目未开启 buildConfig 生成，
 * 且这样得到的是【设备上实际运行的版本】—— 排查"是否装上新版"时更可信。
 */
private fun installedVersion(context: Context): String = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode
    else @Suppress("DEPRECATION") info.versionCode.toLong()
    "${info.versionName} ($code)"
}.getOrDefault("未知")
