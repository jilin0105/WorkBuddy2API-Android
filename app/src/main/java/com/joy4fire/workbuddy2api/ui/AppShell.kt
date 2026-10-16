package com.joy4fire.workbuddy2api.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Snackbar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.ContactsCircle
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tasks
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 应用外壳 —— 全 App 唯一的页面容器与导航。
 *
 * 页面结构：6 个一级 Tab + 7 个二级页
 *   一级：概览 · 账号 · 模型 · 应用 · 设置 · 成长
 *   二级：用量统计 / 出网取证 / 存储占用 / 后台保活 / 提示词注入 / 调用记录 / 定时参数
 *
 * 【为什么不引 Navigation 组件】
 *   只有两级、二级页之间不做跳转，Navigation 的收益（返回栈同步、深链）在这里用不上，
 *   代价却是多一个依赖 + 一处状态源不一致的风险。用两个枚举表达层级，
 *   状态量少到可以完整推理。
 *
 * 【两处关键修正（相对上一版）】
 *   ① 二级页返回：上一版只在顶栏放了一个「返回」文字按钮，且固定回所属 Tab；
 *      但同一个二级页可以从不同 Tab 进入（「用量统计」从概览和设置都能进），
 *      于是会出现"返回回到了莫名其妙的地方"。现在用 [subOrigin] 记住来源，
 *      返回键与顶栏返回都回到**来的那一页**。返回图标也改用 IconButton
 *      （文字按钮会把标题挤偏，且图标按钮才符合"返回"的视觉约定）。
 *   ② 瞬时提示：从「写进页面里的日志卡片」改到 Scaffold 级 Snackbar。
 *      日志类内容（最近活动）保留为弹窗，不再占据页面版面。
 */
enum class AppTab(val label: String) {
    OVERVIEW("概览"),
    ACCOUNTS("账号"),
    MODELS("模型"),
    APPS("应用"),
    SETTINGS("设置"),
    GROWTH("成长")
}

/** 二级页面：覆盖在 Tab 之上，带返回键。 */
enum class SubPage(val title: String) {
    NONE(""),
    USAGE("用量统计"),
    INSPECTOR("出网取证"),
    STORAGE("存储占用"),
    KEEPALIVE("后台保活"),
    PROMPT("提示词注入"),
    RECORDS("调用记录"),
    SCHEDULE("定时参数")
}

@Composable
fun AppShell(context: Context, app: AppState) {
    var tab by rememberSaveable { mutableStateOf(AppTab.OVERVIEW) }
    var sub by rememberSaveable { mutableStateOf(SubPage.NONE) }
    // 二级页的来源 Tab：返回时优先回它，而不是固定回某个"所属"Tab。
    var subOrigin by rememberSaveable { mutableStateOf(AppTab.OVERVIEW) }

    val openSub: (SubPage) -> Unit = { target ->
        subOrigin = tab
        sub = target
    }
    val goBack: () -> Unit = {
        sub = SubPage.NONE
        tab = subOrigin
    }

    // 系统返回键（含全面屏手势）分级处理。
    //
    // 为什么必须显式处理：Compose 页面本身不消费返回事件，不注册就会直接传到
    // Activity 的默认实现（finish），表现为「在任何页面随手势返回都退出应用」。
    //
    // 分级：① 有二级页 → 关二级页；② 非概览 Tab → 回概览；③ 概览 → 交给系统退出。
    // 用 enabled 开关而不是单 handler 内分支：未启用的 handler 不参与返回栈，
    // 系统才能正确判定"无处可退"并执行退出。
    BackHandler(enabled = sub != SubPage.NONE) { goBack() }
    BackHandler(enabled = sub == SubPage.NONE && tab != AppTab.OVERVIEW) { tab = AppTab.OVERVIEW }

    // 提供 NavigationEventDispatcherOwner：Miuix 的弹层/下拉/抽屉内部会注册
    // NavigationBackHandler，而 Compose Dialog 是独立 window、View 树取不到宿主的
    // owner，不提供就会崩（详见 NavCompat.kt）。包住整个 Scaffold 覆盖所有页面子树。
    ProvideNavigationEventDispatcher {
        AppTheme {
            val snackbarHostState = androidx.compose.runtime.remember { SnackbarHostState() }
            val appContext = LocalContext.current

            // AppState.message 是"一次性提示"：显示一次后立刻清空，
            // 否则每次重组都会重新弹一遍。
            val message = app.message
            LaunchedEffect(message) {
                if (!message.isNullOrBlank()) {
                    snackbarHostState.showSnackbar(message)
                    app.clearMessage()
                }
            }

            Scaffold(
                topBar = {
                    val showBack = sub != SubPage.NONE
                    SmallTopAppBar(
                        title = if (showBack) sub.title else tab.label,
                        navigationIcon = {
                            if (showBack) {
                                IconButton(onClick = goBack) {
                                    // 用扩展图标集里语义正确的 Back，而不是拿 ArrowRight
                                    // 旋转 180° 充数：miuix-icons 已在依赖里（本项目需要
                                    // 它做底部导航的图标），所以没有理由再退而求其次。
                                    Icon(
                                        imageVector = MiuixIcons.Back,
                                        contentDescription = "返回",
                                        tint = MiuixTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    )
                },
                bottomBar = {
                    // 二级页隐藏底部导航：二级页是"深入"进来的，保留 Tab 会让用户
                    // 以为还能横向切页；隐藏后焦点自然落在"看完返回"这一条路径上。
                    if (sub == SubPage.NONE) {
                        NavigationBar {
                            NavigationBarItem(
                                selected = tab == AppTab.OVERVIEW,
                                onClick = { tab = AppTab.OVERVIEW },
                                icon = MiuixIcons.Tasks,
                                label = AppTab.OVERVIEW.label
                            )
                            NavigationBarItem(
                                selected = tab == AppTab.ACCOUNTS,
                                onClick = { tab = AppTab.ACCOUNTS },
                                icon = MiuixIcons.ContactsCircle,
                                label = AppTab.ACCOUNTS.label
                            )
                            NavigationBarItem(
                                selected = tab == AppTab.MODELS,
                                onClick = { tab = AppTab.MODELS },
                                icon = MiuixIcons.Layers,
                                label = AppTab.MODELS.label
                            )
                            NavigationBarItem(
                                selected = tab == AppTab.APPS,
                                onClick = { tab = AppTab.APPS },
                                icon = MiuixIcons.GridView,
                                label = AppTab.APPS.label
                            )
                            NavigationBarItem(
                                selected = tab == AppTab.SETTINGS,
                                onClick = { tab = AppTab.SETTINGS },
                                icon = MiuixIcons.Settings,
                                label = AppTab.SETTINGS.label
                            )
                            NavigationBarItem(
                                selected = tab == AppTab.GROWTH,
                                onClick = { tab = AppTab.GROWTH },
                                icon = MiuixIcons.Favorites,
                                label = AppTab.GROWTH.label
                            )
                        }
                    }
                },
                snackbarHost = { SnackbarHost(state = snackbarHostState) { data -> Snackbar(data) } }
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    if (sub != SubPage.NONE) {
                        when (sub) {
                            SubPage.USAGE -> UsageScreen(appContext, app)
                            SubPage.INSPECTOR -> InspectorScreen(appContext, app)
                            SubPage.STORAGE -> StorageScreen(appContext, app)
                            SubPage.KEEPALIVE -> KeepAliveScreen(appContext, app)
                            SubPage.PROMPT -> PromptScreen(appContext)
                            SubPage.RECORDS -> RecordsScreen(appContext, app)
                            SubPage.SCHEDULE -> ScheduleScreen(appContext, app)
                            SubPage.NONE -> Unit
                        }
                    } else {
                        when (tab) {
                            AppTab.OVERVIEW -> OverviewScreen(appContext, app, openSub)
                            AppTab.ACCOUNTS -> AccountsScreen(appContext, app)
                            AppTab.MODELS -> ModelsScreen(appContext, app)
                            AppTab.APPS -> AppsScreen(appContext, app)
                            AppTab.SETTINGS -> SettingsScreen(appContext, app, openSub)
                            AppTab.GROWTH -> GrowthScreen(appContext, app)
                        }
                    }
                }
            }
        }
    }
}
