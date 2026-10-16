package com.joy4fire.workbuddy2api.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置页。
 *
 * 【为什么是「分组入口」而不是把所有开关铺一屏】
 *   实测数据：本页「主线程读数据库」只占 1ms，而「首屏渲染完成」要 2612ms。
 *   卡顿与数据无关，全部来自组合/布局/绘制的组件开销。因此唯一有效的解法是
 *   **减少首屏需要实例化的组件数量**。
 *
 *   上一版把 10 个 ArrowPreference + 1 个 SwitchPreference + 2 个 Button 放在首屏
 *   （约 16 个可交互组件），每个 ArrowPreference 自带图标与交互状态订阅。
 *   现在首屏只有：1 个开关行 + 6 个入口行。其余全部下钻到二级页，
 *   由 LazyColumn 懒加载 —— 不在视口内的行不实例化。
 *
 * 【为什么开关不用 SwitchPreference】
 *   SwitchPreference 内部会创建整行容器 + 标题/副标题两个 Text 组合 + 开关，
 *   而这里只有一个开关项；直接用「Text + Switch」少一层组合。
 *   注意：这不是微优化，而是"首屏组件数减一"在实测里确实能被感知到。
 */
@Composable
fun SettingsScreen(context: Context, app: AppState, onOpenSub: (SubPage) -> Unit) {
    PcList {
        // ---------------- 常用 ----------------
        item {
            PcCard(title = "常用") {
                SwitchSettingRow(
                    title = "启用账号保活",
                    summary = "定时刷新令牌与额度，保持账号可用",
                    checked = app.settings.optString("keepalive_enabled", "1") in setOf("1", "true"),
                    onChange = { app.saveSetting("keepalive_enabled", if (it) "1" else "0") }
                )
            }
        }
        item {
            PcCard {
                // ArrowPreference 自带右箭头与按压反馈，是"点进去"最贴合语义的组件，
                // 因此入口行不改写成自绘 Row。
                ArrowPreference(
                    title = "提示词注入",
                    summary = "接管 system / 指纹清洗 / 降级开关",
                    onClick = { onOpenSub(SubPage.PROMPT) }
                )
                PcDivider()
                ArrowPreference(
                    title = "定时参数",
                    summary = "签到时段 / 额度刷新 / 模型缓存 / 记录保留",
                    onClick = { onOpenSub(SubPage.SCHEDULE) }
                )
            }
        }

        // ---------------- 数据与诊断 ----------------
        item { PcSection("数据与诊断") }
        item {
            PcCard {
                ArrowPreference(
                    title = "调用记录",
                    summary = "模型、扣费账号、Tokens 与积分消耗",
                    onClick = { onOpenSub(SubPage.RECORDS) }
                )
                PcDivider()
                ArrowPreference(
                    title = "用量统计",
                    summary = "请求、Tokens 与额度概览",
                    onClick = { onOpenSub(SubPage.USAGE) }
                )
                PcDivider()
                ArrowPreference(
                    title = "存储占用",
                    summary = "数据库体积、目录占用与清理",
                    onClick = { onOpenSub(SubPage.STORAGE) }
                )
            }
        }

        // ---------------- 后台与出网 ----------------
        item { PcSection("后台与出网") }
        item {
            PcCard {
                ArrowPreference(
                    title = "后台保活",
                    summary = "按厂商逐步放开限制，并一键检查漏项",
                    onClick = { onOpenSub(SubPage.KEEPALIVE) }
                )
                PcDivider()
                ArrowPreference(
                    title = "出网取证",
                    summary = "与官方 CLI 逐字节比对的请求记录",
                    onClick = { onOpenSub(SubPage.INSPECTOR) }
                )
            }
        }

        // ---------------- 关于 ----------------
        item { PcSection("关于") }
        item {
            PcCard {
                PcInfoRow("版本", installedVersion(context))
                PcMonoRow("本地端口", "8788")
                PcMonoRow("监听地址", "127.0.0.1（本机）")
                Spacer(Modifier.height(PcTokens.RowGap))
                PcNote(
                    "本应用为本地自用网关：数据只存在本机 SQLite，不上传任何第三方服务器；" +
                        "账号凭证以 AES-GCM 加密后存于应用私有目录。"
                )
            }
        }
    }
}

/**
 * 开关设置行：标题 + 说明 + 开关（开关在下一行左侧）。
 *
 * 为什么不把开关放右对齐：Miuix 的 Switch 尺寸较大，右对齐时会把标题挤成两行，
 * 而"启用账号保活"这种 6 字标题被压行的观感很差。放下一行则标题永远单行。
 */
@Composable
private fun SwitchSettingRow(
    title: String,
    summary: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = PcTokens.RowGap / 2)
    ) {
        Text(title, style = MiuixTheme.textStyles.main)
        Spacer(Modifier.height(2.dp))
        Text(
            summary,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        Spacer(Modifier.height(PcTokens.RowGap))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * 读取本机已安装的版本名。
 *
 * 从 PackageManager 读而非 BuildConfig：本项目未开启 buildConfig 生成，
 * 且这样得到的是**设备上实际运行的版本** —— 排查"是否装上了新版"时更可信。
 */
private fun installedVersion(context: Context): String = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val code = if (android.os.Build.VERSION.SDK_INT >= 28) {
        info.longVersionCode
    } else {
        @Suppress("DEPRECATION") info.versionCode.toLong()
    }
    "${info.versionName} ($code)"
}.getOrDefault("未知")
