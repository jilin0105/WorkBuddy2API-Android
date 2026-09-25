package com.joy4fire.workbuddy2api.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ContactsCircle
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tasks

/**
 * 应用外壳 —— 全 App 唯一的页面容器与导航。
 *
 * 页面结构（原「经典界面」的 8 个页面合并为 7 个一级 Tab）：
 *   概览 · 账号 · 模型 · 记录 · 应用 · 设置 · 成长
 *
 * 「成长」为本次新增（移植自 WorkBuddy 1.2.7），其余六页承载原界面全部功能。
 *
 * 二级页面（用量 / 出网取证 / 存储占用 / 后台保活 / 提示词注入）不入 Tab，
 * 由所在页面内以 [SubPage] 状态切换，返回按钮在顶栏 —— 层级只有两级，
 * 引 Navigation 组件的成本（返回栈同步、依赖体积）大于收益。
 */
enum class AppTab(val label: String) {
    OVERVIEW("概览"),
    ACCOUNTS("账号"),
    MODELS("模型"),
    RECORDS("记录"),
    APPS("应用"),
    SETTINGS("设置"),
    GROWTH("成长")
}

/** 二级页面（覆盖在 Tab 之上，带返回按钮）。 */
enum class SubPage { NONE, USAGE, INSPECTOR, STORAGE, KEEPALIVE, PROMPT }

@Composable
fun AppShell(context: Context, app: AppState) {
    var tab by rememberSaveable { mutableStateOf(AppTab.OVERVIEW) }
    var sub by rememberSaveable { mutableStateOf(SubPage.NONE) }

    // 系统返回键（含全面屏手势）分级处理。
    //
    // 为什么必须显式处理：Compose 页面本身不消费返回事件，不注册就会直接传到
    // Activity 的默认实现（finish），表现为「在任何页面随手势返回都退出应用」。
    // 这与 Android 用户的普遍预期不符，也让二级页几乎无法用单手返回。
    //
    // 分级策略：
    //   ① 二级页（用量/取证/存储/保活/提示词）→ 回所属一级 Tab
    //   ② 非首页的一级 Tab → 回首页（概览）
    //   ③ 首页且无二级页 → 不拦截，交给系统退出（此时退出才符合预期）
    // 用 enabled 开关而不是在一个 handler 里分支：未启用的 handler 不参与返回栈，
    // 系统能正确判定「无处可退」并执行退出。
    BackHandler(enabled = sub != SubPage.NONE) { sub = SubPage.NONE }
    BackHandler(enabled = sub == SubPage.NONE && tab != AppTab.OVERVIEW) { tab = AppTab.OVERVIEW }

    // 提供 NavigationEventDispatcherOwner：Miuix 的对话框/弹层内部注册 NavigationBackHandler，
    // 而 Compose Dialog 是独立 window、取不到宿主的 owner，不提供会直接崩（详见 NavCompat.kt）。
    // 包裹整个 Scaffold：各页面的对话框都渲染在其子树内，一层即可覆盖全部弹层。
    ProvideNavigationEventDispatcher {
        AppTheme {
        Scaffold(
            topBar = {
                val title = if (sub == SubPage.NONE) tab.label else when (sub) {
                    SubPage.USAGE -> "用量"
                    SubPage.INSPECTOR -> "出网取证"
                    SubPage.STORAGE -> "存储占用"
                    SubPage.KEEPALIVE -> "后台保活"
                    SubPage.PROMPT -> "提示词注入"
                    SubPage.NONE -> tab.label
                }
                SmallTopAppBar(
                    title = title,
                    navigationIcon = {
                        if (sub != SubPage.NONE) {
                            TextButton(
                                text = "返回",
                                onClick = { sub = SubPage.NONE },
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                    // 不设全局「刷新」按钮：各页面自身已有更贴合语境的刷新入口
                    // （概览的「刷新全部」、模型页的「刷新模型目录」、记录页的「刷新统计」、
                    // 账号页的「刷新全部账号额度」）。顶栏再挂一个全局刷新，
                    // 既与页面内按钮职责重叠，又会在每页重复出现，属冗余。
                )
            },
            bottomBar = {
                if (sub == SubPage.NONE) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = tab == AppTab.OVERVIEW,
                            onClick = { tab = AppTab.OVERVIEW },
                            icon = MiuixIcons.Tasks,
                            label = "概览"
                        )
                        NavigationBarItem(
                            selected = tab == AppTab.ACCOUNTS,
                            onClick = { tab = AppTab.ACCOUNTS },
                            icon = MiuixIcons.ContactsCircle,
                            label = "账号"
                        )
                        NavigationBarItem(
                            selected = tab == AppTab.MODELS,
                            onClick = { tab = AppTab.MODELS },
                            icon = MiuixIcons.Layers,
                            label = "模型"
                        )
                        NavigationBarItem(
                            selected = tab == AppTab.RECORDS,
                            onClick = { tab = AppTab.RECORDS },
                            icon = MiuixIcons.ListView,
                            label = "记录"
                        )
                        NavigationBarItem(
                            selected = tab == AppTab.APPS,
                            onClick = { tab = AppTab.APPS },
                            icon = MiuixIcons.GridView,
                            label = "应用"
                        )
                        NavigationBarItem(
                            selected = tab == AppTab.SETTINGS,
                            onClick = { tab = AppTab.SETTINGS },
                            icon = MiuixIcons.Settings,
                            label = "设置"
                        )
                        NavigationBarItem(
                            selected = tab == AppTab.GROWTH,
                            onClick = { tab = AppTab.GROWTH },
                            icon = MiuixIcons.Favorites,
                            label = "成长"
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (sub != SubPage.NONE) {
                    when (sub) {
                        SubPage.USAGE -> UsageScreen(context, app)
                        SubPage.INSPECTOR -> InspectorScreen(context, app)
                        SubPage.STORAGE -> StorageScreen(context, app)
                        SubPage.KEEPALIVE -> KeepAliveScreen(context, app)
                        SubPage.PROMPT -> PromptScreen(context)
                        SubPage.NONE -> Unit
                    }
                } else {
                    when (tab) {
                        AppTab.OVERVIEW -> OverviewScreen(context, app) { sub = it }
                        AppTab.ACCOUNTS -> AccountsScreen(context, app)
                        AppTab.MODELS -> ModelsScreen(context, app)
                        AppTab.RECORDS -> RecordsScreen(context, app)
                        AppTab.APPS -> AppsScreen(context, app)
                        AppTab.SETTINGS -> SettingsScreen(context, app) { sub = it }
                        AppTab.GROWTH -> GrowthScreen(context, app.selectedAccount)
                    }
                }
            }
        }
        }
    }
}
