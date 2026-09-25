package com.joy4fire.workbuddy2api.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * Miuix 主题入口 —— 全 App 唯一的主题挂载点。
 *
 * 为什么用「静态配色」而不是 ThemeController + Monet 动态取色：
 * 本 App 是本地网关工具，界面只在用户主动打开时看几眼，不需要跟随壁纸变色；
 * 而 Monet 需要 Android 12+ 且会引入额外的资源解析路径（沙箱实测在低版本
 * 回落时偶发首帧透明）。静态配色行为完全确定，且浅深两套已覆盖需求。
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MiuixTheme(colors = colors, content = content)
}
