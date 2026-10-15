package com.joy4fire.workbuddy2api.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 定时参数二级页 —— 承接原先铺在设置页首屏的四项数值配置。
 *
 * 为什么拆出来：
 *   设置页首屏组件数越少，进入时越不容易被卡（设备高温降频时尤其明显）。
 *   这四项都是低频修改项，放进二级页后首屏只需一个入口行。
 *
 * 每项仍是「点击弹窗编辑」——不为了一行配置再引入 TextField 的常驻开销。
 */
@Composable
fun ScheduleScreen(context: Context, app: AppState) {
    val settings = app.settings
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = PcTokens.SpaceM, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(PcTokens.CardGap)
    ) {
        item {
            PcCard(title = "定时参数") {
                SettingRow(
                    label = "每日签到时段",
                    value = settings.optString("checkin_hours", "9,21"),
                    hint = "24 小时制，逗号分隔，例如 9,21",
                    onCommit = { app.saveSetting("checkin_hours", it) }
                )
                SettingRow(
                    label = "额度刷新间隔（分钟）",
                    value = settings.optString("credit_refresh_min", "30"),
                    hint = "间隔越短越及时，也越耗电与流量",
                    onCommit = { app.saveSetting("credit_refresh_min", it) }
                )
                SettingRow(
                    label = "模型缓存（分钟）",
                    value = settings.optString("model_ttl_min", "60"),
                    hint = "缓存过期后会自动重新拉取模型目录",
                    onCommit = { app.saveSetting("model_ttl_min", it) }
                )
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
                Text(
                    "参数会立即生效，无需重启服务。签到时段用于账号保活任务，" +
                        "到点后会自动为国内版账号签到。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
    }
}
