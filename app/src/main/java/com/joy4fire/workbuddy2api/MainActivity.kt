package com.joy4fire.workbuddy2api

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.window.OnBackInvokedDispatcher
import kotlin.math.max
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/** Single-activity native console. Network/storage calls stay centralized here so a future NativeStore can replace prefs. */
class MainActivity : Activity() {
    private enum class Page(val title: String, val subtitle: String) {
        OVERVIEW("概览", "本地模型网关状态"),
        ACCOUNTS("账号", "登录、签到与额度"),
        MODELS("模型", "上游模型目录与能力"),
        RECORDS("记录", "本次运行的操作日志"),
        MORE("更多", "用量、应用与设置"),
        USAGE("用量", "调用统计与额度概览"),
        APPS("应用", "API Key 与接入信息"),
        SETTINGS("设置", "网关偏好与系统权限")
    }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var contentHost: FrameLayout
    private lateinit var pageTitle: TextView
    private lateinit var pageSubtitle: TextView
    private lateinit var headerStatus: TextView
    private lateinit var navItems: Map<Page, TextView>
    private var currentPage = Page.OVERVIEW
    private var oauthSession: OAuthSession? = null
    private var oauthAttempts = 0
    private var modelCache: JSONArray? = null
    private var creditCache: JSONObject? = null
    private var checkinResults: JSONArray? = null
    private val events = ArrayDeque<String>()
    /** 待写入目标文件的导出内容（用户选完保存位置后由 onActivityResult 写盘）。 */
    private var pendingExport: String? = null
    private var exportFileName: String = "workbuddy-accounts.json"

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateServerHeader(intent?.getBooleanExtra("running", false) == true)
            if (currentPage == Page.OVERVIEW) render(Page.OVERVIEW)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        oauthHostRef = WeakReference(this)
        configureEdgeToEdge()
        setContentView(R.layout.activity_main)
        applySystemBarIconAppearance()
        contentHost = findViewById(R.id.contentHost)
        installWindowInsets()
        pageTitle = findViewById(R.id.pageTitle)
        pageSubtitle = findViewById(R.id.pageSubtitle)
        headerStatus = findViewById(R.id.headerStatus)
        navItems = mapOf(
            Page.OVERVIEW to findViewById(R.id.navOverview),
            Page.ACCOUNTS to findViewById(R.id.navAccounts),
            Page.MODELS to findViewById(R.id.navModels),
            Page.RECORDS to findViewById(R.id.navRecords),
            Page.MORE to findViewById(R.id.navMore)
        )
        navItems.forEach { (page, view) -> view.setOnClickListener { render(page) } }
        updateServerHeader(ApiHostService.running)
        render(Page.OVERVIEW)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBackNavigation() }
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
    }

    @Suppress("DEPRECATION")
    private fun configureEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
    }

    @Suppress("DEPRECATION")
    private fun applySystemBarIconAppearance() {
        val darkIcons = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
        if (Build.VERSION.SDK_INT >= 30) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.decorView.windowInsetsController?.setSystemBarsAppearance(if (darkIcons) mask else 0, mask)
        } else {
            var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            if (darkIcons) {
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                if (Build.VERSION.SDK_INT >= 26) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
            window.decorView.systemUiVisibility = flags
        }
    }

    @Suppress("DEPRECATION")
    private fun installWindowInsets() {
        val root = findViewById<View>(R.id.root)
        val top = findViewById<View>(R.id.topBar)
        val bottom = findViewById<View>(R.id.bottomNavigation)
        val topPads = intArrayOf(top.paddingLeft, top.paddingTop, top.paddingRight, top.paddingBottom)
        val contentPads = intArrayOf(contentHost.paddingLeft, contentHost.paddingTop, contentHost.paddingRight, contentHost.paddingBottom)
        val bottomPads = intArrayOf(bottom.paddingLeft, bottom.paddingTop, bottom.paddingRight, bottom.paddingBottom)
        val baseBottomHeight = dp(80)
        root.setOnApplyWindowInsetsListener { _, insets ->
            var left: Int; var topInset: Int; var right: Int; var navBottom: Int; var imeBottom = 0; var imeVisible = false
            if (Build.VERSION.SDK_INT >= 30) {
                val status = insets.getInsets(WindowInsets.Type.statusBars())
                val nav = insets.getInsets(WindowInsets.Type.navigationBars())
                val cutout = insets.getInsets(WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                left = max(max(status.left, nav.left), cutout.left); right = max(max(status.right, nav.right), cutout.right)
                topInset = max(status.top, cutout.top); navBottom = max(nav.bottom, cutout.bottom)
                imeVisible = insets.isVisible(WindowInsets.Type.ime()); if (imeVisible) imeBottom = ime.bottom
            } else {
                val cutout = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout else null
                left = max(insets.systemWindowInsetLeft, cutout?.safeInsetLeft ?: 0)
                right = max(insets.systemWindowInsetRight, cutout?.safeInsetRight ?: 0)
                topInset = max(insets.systemWindowInsetTop, cutout?.safeInsetTop ?: 0)
                imeVisible = insets.systemWindowInsetBottom > insets.stableInsetBottom + dp(48)
                imeBottom = if (imeVisible) insets.systemWindowInsetBottom else 0
                navBottom = max(if (imeVisible) insets.stableInsetBottom else insets.systemWindowInsetBottom, cutout?.safeInsetBottom ?: 0)
            }
            top.setPadding(topPads[0] + left, topPads[1] + topInset, topPads[2] + right, topPads[3])
            contentHost.setPadding(contentPads[0] + left, contentPads[1], contentPads[2] + right,
                contentPads[3] + if (imeVisible) max(imeBottom, navBottom) else 0)
            bottom.visibility = if (imeVisible) View.GONE else View.VISIBLE
            bottom.setPadding(bottomPads[0] + left, bottomPads[1], bottomPads[2] + right, bottomPads[3] + navBottom)
            bottom.layoutParams = bottom.layoutParams.apply { height = baseBottomHeight + navBottom }
            insets
        }
        root.requestApplyInsets()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ApiHostService.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(stateReceiver, filter)
    }

    override fun onStop() {
        runCatching { unregisterReceiver(stateReceiver) }
        super.onStop()
    }

    override fun onDestroy() {
        oauthSession = null
        io.shutdownNow()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        handleBackNavigation()
    }

    @Suppress("DEPRECATION")
    private fun handleBackNavigation() {
        if (currentPage in listOf(Page.USAGE, Page.APPS, Page.SETTINGS)) {
            render(Page.MORE)
        } else {
            finishAfterTransition()
        }
    }

    private fun render(page: Page) {
        currentPage = page
        pageTitle.text = page.title
        pageSubtitle.text = page.subtitle
        contentHost.removeAllViews()
        contentHost.addView(
            when (page) {
                Page.OVERVIEW -> overviewPage()
                Page.ACCOUNTS -> accountsPage()
                Page.MODELS -> modelsPage()
                Page.RECORDS -> recordsPage()
                Page.MORE -> morePage()
                Page.USAGE -> usagePage()
                Page.APPS -> appsPage()
                Page.SETTINGS -> settingsPage()
            }, match()
        )
        val selected = if (page in listOf(Page.USAGE, Page.APPS, Page.SETTINGS)) Page.MORE else page
        navItems.forEach { (key, item) ->
            val active = key == selected
            item.setTextColor(color(if (active) R.color.wb_on_primary_container else R.color.wb_on_surface_variant))
            item.setTypeface(null, Typeface.NORMAL)
            item.background = if (active) shape(R.color.wb_primary_container, 16f) else null
            item.compoundDrawables.forEach { drawable ->
                drawable?.setTint(color(if (active) R.color.wb_primary else R.color.wb_on_surface_variant))
            }
        }
    }

    private fun overviewPage(): View = page {
        val accounts = NativeCore.listAccounts(this@MainActivity)
        val account = NativeCore.loadAccount(this@MainActivity)
        val credits = NativeCore.creditsSummary(this@MainActivity).also { creditCache = it }
        addView(card {
            addView(row {
                addView(text(if (ApiHostService.running) "●  网关运行中" else "○  网关已停止", 19, true,
                    if (ApiHostService.running) R.color.wb_success else R.color.wb_on_surface), weighted())
                addView(chip(if (ApiHostService.running) "健康" else "离线",
                    if (ApiHostService.running) R.color.wb_success_container else R.color.wb_surface_variant,
                    if (ApiHostService.running) R.color.wb_success else R.color.wb_on_surface_variant))
            })
            addView(caption(if (ApiHostService.running)
                "OpenAI 兼容地址  http://127.0.0.1:${ApiHostService.PORT}/v1"
            else "启动后仅在本机提供 OpenAI 兼容 API。"), top(8))
            addView(row(top = 14) {
                addView(action(if (ApiHostService.running) "停止服务" else "启动服务") { toggleServer() }, weighted())
                addView(action("复制地址", outlined = true) { copyText("http://127.0.0.1:${ApiHostService.PORT}/v1", "Base URL") }, weighted(start = 8))
            })
        })
        addView(section("快速概览"))
        addView(row {
            addView(metric("账号", accounts.length().toString(), if (accounts.length() == 0) "待配置" else "已连接"), weighted())
            addView(metric("模型", modelCache?.length()?.toString() ?: "—", if (modelCache == null) "待刷新" else "可用"), weighted(start = 10))
        })
        addView(row(top = 10) {
            val known = credits.optInt("known")
            addView(metric("全部剩余额度", if (known > 0) "%.1f".format(credits.optDouble("remain")) else "—",
                if (known == accounts.length() && known > 0) "$known 个账号合计" else "$known/${accounts.length()} 个账号已刷新"), weighted())
            addView(metric("运行记录", events.size.toString(), "本次会话"), weighted(start = 10))
        })
        if (accounts.length() > 0) {
            addView(section("账号额度"))
            for (i in 0 until accounts.length()) {
                val item = accounts.getJSONObject(i)
                addView(card(topMargin = if (i == 0) 0 else 8) {
                    addView(row {
                        addView(text(accountDisplayName(item), 16, true), weighted())
                        addView(chip(if (item.optBoolean("enabled", true)) "已启用" else "已停用",
                            if (item.optBoolean("enabled", true)) R.color.wb_success_container else R.color.wb_surface_variant,
                            if (item.optBoolean("enabled", true)) R.color.wb_success else R.color.wb_on_surface_variant))
                    })
                    addView(caption("UID ${shortUid(item.optString("uid"))}"), top(4))
                    addView(text(if (item.isNull("credits_remaining")) "额度尚未刷新"
                        else "剩余 ${"%.1f".format(item.optDouble("credits_remaining"))} / 总计 ${"%.1f".format(item.optDouble("credits_total"))} 积分",
                        15, true, if (item.isNull("credits_remaining")) R.color.wb_on_surface_variant else R.color.wb_primary), top(7))
                })
            }
        }
        addView(section("开始使用"))
        addView(card {
            addView(text(if (account == null) "连接 WorkBuddy 账号" else "${account.nickname} 已就绪", 17, true))
            addView(caption(if (account == null) "先登录或导入 auth 文件，再启动本地服务。" else "可直接启动服务，或刷新额度和模型目录。"), top(6))
            addView(action(if (account == null) "前往账号" else "查看账号", full = true) { render(Page.ACCOUNTS) }, top(12))
        })
        addView(section("最近活动"))
        if (events.isEmpty()) addView(stateCard("暂无活动", "启动服务、刷新模型或管理账号后，操作结果会显示在这里。", "空"))
        else events.take(3).forEach { addView(eventCard(it)) }
    }

    private fun accountsPage(): View = page {
        val accounts = NativeCore.listAccounts(this@MainActivity)
        addView(row {
            addView(action("国内版登录") { startOAuth(AccountRegion.DOMESTIC) }, weighted())
            addView(action("国际版登录", outlined = true) { startOAuth(AccountRegion.INTERNATIONAL) }, weighted(start = 8))
        })
        // 切换账号：登录窗口默认复用已有会话（日常续期更省事），点这里强制清空会话，
        // 让 Google / GitHub 等 IdP 重新弹出账号选择器，从而登录到第二个账号。
        addView(action("切换账号登录", outlined = true, full = true) {
            startOAuthSwitch()
        }, top(8))
        addView(action("导入 auth 文件", outlined = true, full = true) { chooseAuth() }, top(8))
        addView(action("导出全部账号", outlined = true, full = true) { exportAllAccounts() }, top(8))
        if (accounts.length() == 0) {
            addView(stateCard("尚未连接账号", "在 App 内置窗口完成登录（不跳浏览器），或导入 WorkBuddy / CodeBuddy 的 .info、JSON 文件。已登录的账号可用上方「导出全部账号」备份，换机时再导入即可。", "空"), top(14))
        } else {
            addView(section("账号池 · ${accounts.length()} 个"))
            for (i in 0 until accounts.length()) {
                val account = accounts.getJSONObject(i)
                val uid = account.optString("uid")
                val accountKey = account.optString("account_key", uid)
                addView(card(topMargin = if (i == 0) 0 else 10) {
                    addView(row {
                        addView(text(account.optString("nickname", "WorkBuddy 用户"), 18, true), weighted())
                        addView(chip(if (account.optBoolean("healthy")) "● 健康" else if (account.optBoolean("enabled", true)) "冷却" else "已停用",
                            if (account.optBoolean("healthy")) R.color.wb_success_container else R.color.wb_surface_variant,
                            if (account.optBoolean("healthy")) R.color.wb_success else R.color.wb_on_surface_variant))
                    })
                    addView(labelValue("版本", account.optString("region_label", "国内版")), top(10))
                    addView(labelValue("UID", uid), top(6))
                    addView(priorityRow(account.optInt("priority")) {
                        showPriorityDialog(accountKey, account.optString("nickname", "WorkBuddy 用户"), uid, account.optInt("priority"))
                    }, top(6))
                    if (!account.isNull("credits_remaining")) {
                        addView(labelValue("剩余 / 总额度", "%.1f / %.1f 积分".format(account.optDouble("credits_remaining"), account.optDouble("credits_total"))), top(6))
                    } else addView(labelValue("额度", "尚未刷新"), top(6))
                    if (AccountRegion.from(account.optString("region")) == AccountRegion.INTERNATIONAL) {
                        addView(labelValue("额度发放", "自动发放 · 刷新额度即可"), top(6))
                    } else {
                        addView(labelValue("今日签到", if (isCheckedInToday(account.optString("last_checkin_date"))) "已签到" else "未签到"), top(6))
                    }
                    addView(row(top = 10) {
                        addView(action(if (account.optBoolean("enabled", true)) "停用" else "启用", outlined = true) {
                            NativeCore.setAccountEnabled(this@MainActivity, accountKey, !account.optBoolean("enabled", true)); render(Page.ACCOUNTS)
                        }, weighted())
                        addView(action("优先级", outlined = true) {
                            showPriorityDialog(accountKey, account.optString("nickname", "WorkBuddy 用户"), uid, account.optInt("priority"))
                        }, weighted(start = 8))
                        addView(action("导出", outlined = true) {
                            exportAccount(accountKey, account.optString("nickname"), uid)
                        }, weighted(start = 8))
                    })
                    addView(row(top = 8) {
                        addView(action("删除", outlined = true, full = true) {
                            AlertDialog.Builder(this@MainActivity).setTitle("删除账号？").setMessage("${account.optString("region_label")} · $uid")
                                .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ -> NativeCore.deleteAccount(this@MainActivity, accountKey); render(Page.ACCOUNTS) }.show()
                        }, weighted())
                    })
                })
            }
        }
        addView(section("账号操作"))
        addView(card {
            addView(action("刷新全部账号额度", full = true) { refreshCredits() })
            addView(action("为全部启用的国内账号签到", outlined = true, full = true) { checkIn() }, top(8))
            addView(caption("额度会按账号逐个刷新并汇总；批量签到只处理国内账号，国际版额度由上游自动发放。"), top(10))
        })
        val summary = NativeCore.creditsSummary(this@MainActivity).also { creditCache = it }
        if (summary.optInt("known") > 0) {
            addView(card(topMargin = 12) {
                addView(text("全部账号额度", 16, true))
                addView(text("${"%.1f".format(summary.optDouble("remain"))} / ${"%.1f".format(summary.optDouble("total"))} 积分", 24, true, R.color.wb_primary), top(8))
                addView(caption("已汇总 ${summary.optInt("known")} / ${summary.optInt("accounts")} 个账号"), top(4))
                val percent = if (summary.optDouble("total") > 0) ((summary.optDouble("remain") / summary.optDouble("total")) * 100).toInt().coerceIn(0, 100) else 0
                addView(ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply { progress = percent; max = 100 }, lp(-1, dp(12), top = 10))
            })
        }
        checkinResults?.let { results ->
            addView(section("最近签到结果"))
            for (i in 0 until results.length()) {
                val result = results.getJSONObject(i)
                val isInternational = AccountRegion.from(result.optString("region")) == AccountRegion.INTERNATIONAL
                val status = when { result.optBoolean("skipped") -> "已跳过"; result.optBoolean("already") -> "今日已签到"; result.optBoolean("ok") -> "签到成功"; else -> "签到失败" }
                addView(card(topMargin = if (i == 0) 0 else 8) {
                    addView(row {
                        addView(text(accountDisplayName(result), 16, true), weighted())
                        if (!isInternational) {
                            addView(chip(status,
                                if (result.optBoolean("ok")) R.color.wb_success_container else R.color.wb_surface_variant,
                                if (result.optBoolean("ok")) R.color.wb_success else R.color.wb_on_surface_variant))
                        }
                    })
                    addView(caption("UID ${shortUid(result.optString("uid"))}"), top(5))
                    addView(caption(result.optString("message", status)), top(4))
                })
            }
        }
    }

    private fun modelsPage(): View = page {
        val search = EditText(this@MainActivity).apply {
            hint = "搜索模型 ID 或名称"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setTextColor(color(R.color.wb_on_surface))
            setHintTextColor(color(R.color.wb_outline))
            backgroundTintList = android.content.res.ColorStateList.valueOf(color(R.color.wb_primary))
            minHeight = dp(48)
        }
        addView(search)
        addView(action("刷新模型目录", full = true) { loadModels() }, top(10))
        val list = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        addView(list, top(8))
        fun populate(query: String = "") {
            list.removeAllViews()
            val data = modelCache
            if (data == null) {
                list.addView(stateCard("模型目录尚未加载", "连接账号后点击“刷新模型目录”，将从上游加载真实可用模型。", "空"))
                return
            }
            val matches = (0 until data.length()).mapNotNull { data.optJSONObject(it) }.filter {
                query.isBlank() || it.optString("id").contains(query, true) || it.optString("name").contains(query, true)
            }
            list.addView(caption("动态目录 · ${matches.size} 个结果"), top(8))
            if (matches.isEmpty()) list.addView(stateCard("没有匹配模型", "换个关键词试试。", "空"), top(8))
            matches.forEach { model -> list.addView(modelCard(model), top(8)) }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = populate(s?.toString().orEmpty())
            override fun afterTextChanged(s: Editable?) = Unit
        })
        populate()
    }

    private fun recordsPage(): View = page {
        val result = NativeCore.usageRecent(this@MainActivity, pageSize = 100)
        val records = result.optJSONArray("records") ?: JSONArray()
        addView(row {
            addView(text("API 调用记录", 18, true), weighted())
            addView(chip("${result.optInt("total")} 条", R.color.wb_primary_container, R.color.wb_primary))
        })
        addView(caption("持久化保存请求状态、协议、模型、应用、Tokens 与延迟。点右下「删除」或长按卡片删除单条。"), top(6))
        if (records.length() == 0) {
            addView(stateCard("暂无调用记录", "使用任一应用 Key 调用本地网关后，记录会显示在这里。", "空"), top(14))
        } else {
            addView(row(top = 10) {
                addView(action("清空全部记录", outlined = true, full = true) { confirmClearUsage(result.optInt("total")) }, weighted())
            })
            if (result.optInt("total") > records.length()) addView(caption("仅显示最近 ${records.length()} 条（共 ${result.optInt("total")} 条）；清空会删除全部记录。"), top(8))
        }
        for (i in 0 until records.length()) {
            val item = records.getJSONObject(i)
            val id = item.optLong("id")
            val model = item.optString("model", "auto")
            addView(card(topMargin = 8) {
                addView(row {
                    addView(chip(item.optString("status", "ok"), if (item.optString("status") == "ok") R.color.wb_success_container else R.color.wb_error_container,
                        if (item.optString("status") == "ok") R.color.wb_success else R.color.wb_error))
                    addView(text(model, 15, true), weighted(start = 10))
                    addView(caption("#$id"))
                })
                addView(row(top = 8) {
                    addView(caption("${item.optString("protocol")} · ${item.optString("app_name", "未命名")} · ${item.optLong("total_tokens")} tokens · ${item.optLong("latency_ms")} ms"), weighted())
                    addView(linkAction("删除", R.color.wb_error) { confirmDeleteUsage(id, model) })
                })
                if (item.optString("error").isNotBlank()) addView(text(item.optString("error"), 13, false, R.color.wb_error), top(6))
            }.apply {
                isLongClickable = true
                setOnLongClickListener { confirmDeleteUsage(id, model); true }
            })
        }
    }

    /** 记录里存的是完整对话原文，删除不可逆：统一二次确认，并在提示里给出可核对的条目标识。 */
    private fun confirmDeleteUsage(id: Long, model: String) {
        AlertDialog.Builder(this)
            .setTitle("删除这条记录？")
            .setMessage("#$id · $model\n记录包含完整请求与响应原文，删除后无法恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                runTask("删除记录 #$id", Page.RECORDS) { if (NativeCore.deleteUsage(this, id)) "已删除记录 #$id" else "这条记录已不存在" }
            }.show()
    }

    private fun confirmClearUsage(total: Int) {
        AlertDialog.Builder(this)
            .setTitle("清空全部记录？")
            .setMessage("将永久删除全部 $total 条记录（含输入/输出原文），无法恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                runTask("清空记录", Page.RECORDS) {
                    val removed = NativeCore.clearUsage(this)
                    if (removed > 0) "已清空 $removed 条记录" else "没有可清空的记录"
                }
            }.show()
    }

    private fun morePage(): View = page {
        addView(card {
            addView(menuRow("▥", "用量", "请求、Tokens 与额度概览") { render(Page.USAGE) })
            addView(divider())
            addView(menuRow("⌁", "应用", "API Key 与接入配置") { render(Page.APPS) })
            addView(divider())
            addView(menuRow("⚙", "设置", "启动偏好与系统权限") { render(Page.SETTINGS) })
        })
        addView(section("关于"))
        addView(card {
            addView(text("WorkBuddy2API", 17, true))
            addView(caption("Android 原生版 · 内置登录窗口 · 无系统浏览器跳转"), top(4))
            addView(labelValue("本地端口", ApiHostService.PORT.toString()), top(12))
            addView(labelValue("国内后端", NativeCore.BACKEND), top(8))
            addView(labelValue("国际后端", NativeCore.INTERNATIONAL_BACKEND), top(8))
        })
    }

    private fun usagePage(): View = page(withBack = true) {
        val summary = NativeCore.usageSummary(this@MainActivity)
        addView(row {
            addView(metric("今日请求", summary.optLong("today_requests").toString(), "持久记录"), weighted())
            addView(metric("今日 Tokens", formatTokens(summary.optLong("today_tokens")), "输入 + 输出"), weighted(start = 10))
        })
        addView(row(top = 10) {
            addView(metric("总请求", summary.optLong("total_requests").toString(), "全部时间"), weighted())
            addView(metric("总 Tokens", formatTokens(summary.optLong("total_tokens")), "全部时间"), weighted(start = 10))
        })
        addView(section("调用分布"))
        addView(breakdownCard(summary.optJSONArray("by_protocol"), "protocol", "成功与失败的网关调用都会计入协议分布。"))
        addView(breakdownCard(summary.optJSONArray("by_model"), "model", "上游未返回模型名时归入「未指定」。"), top(10))
        addView(breakdownCard(summary.optJSONArray("by_app"), "app", "按应用 Key 归因；Key 删除后历史记录仍保留归因。"), top(10))
        addView(section("额度"))
        val credits = NativeCore.creditsSummary(this@MainActivity).also { creditCache = it }
        if (credits.optInt("known") == 0) {
            addView(stateCard("额度数据未加载", "点击下方按钮刷新全部账号额度。", "空"))
            addView(action("刷新全部账号额度", full = true) { refreshCredits(Page.USAGE) }, top(10))
        } else {
            addView(card {
                addView(text("全部剩余 ${"%.1f".format(credits.optDouble("remain"))} 积分", 22, true, R.color.wb_primary))
                addView(caption("全部总额度 ${"%.1f".format(credits.optDouble("total"))} 积分 · 已汇总 ${credits.optInt("known")}/${credits.optInt("accounts")} 个账号"), top(4))
                val percent = if (credits.optDouble("total") > 0) ((credits.optDouble("remain") / credits.optDouble("total")) * 100).toInt().coerceIn(0, 100) else 0
                addView(ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply { progress = percent; max = 100 }, lp(-1, dp(12), top = 12))
            })
        }
        addView(section("最近 24 小时"))
        val series = NativeCore.usageTimeseries(this@MainActivity)
        val nonEmpty = (0 until series.length()).map { series.getJSONObject(it) }.filter { it.optLong("count") > 0 }
        if (nonEmpty.isEmpty()) addView(stateCard("暂无调用趋势", "成功或失败的网关调用会按小时聚合。", "空"))
        nonEmpty.takeLast(12).forEach { point -> addView(labelValue(point.optString("bucket"), "${point.optLong("count")} 次 · ${formatTokens(point.optLong("tokens"))} tokens"), top(6)) }
    }

    /**
     * 渲染 usageSummary 的分组维度（按协议 / 按模型 / 按应用）。
     *
     * 这三个维度 NativeStore 早已算出，但页面一直没呈现，导致"用量页看不到统计"。
     * 名称 +「次数 · tokens」沿用记录页的紧凑写法；后端按调用次数降序返回，原样展示即 Top N。
     * 空维度给说明卡而不是静默消失，避免被误读为数据丢失。
     */
    private fun breakdownCard(rows: JSONArray?, key: String, emptyHint: String): View {
        if (rows == null || rows.length() == 0) return stateCard("暂无统计数据", emptyHint, "空")
        return card {
            for (i in 0 until rows.length()) {
                val item = rows.optJSONObject(i) ?: continue
                val name = item.optString(key).ifBlank { "（未指定）" }
                // 积分只在真正解析出非 0 消耗时才展示，避免给"上游未返回积分"的记录挂一排 0。
                val credits = item.optDouble("credits", 0.0).takeIf { it.isFinite() && it > 0.0 }
                val detail = buildString {
                    append("${item.optLong("count")} 次 · ${formatTokens(item.optLong("tokens"))} tokens")
                    if (credits != null) append(" · ${"%.2f".format(credits)} 积分")
                }
                addView(labelValue(name, detail), top(if (i == 0) 0 else 10))
            }
        }
    }

    private fun appsPage(): View = page {
        val apps = NativeCore.listApps(this@MainActivity)
        addView(tonalCard {
            addView(text("API Key 控制台", 21, true, R.color.wb_primary))
            addView(caption("每个 Key 都可独立命名、设置备注并固定调用国内版或国际版账号；编辑版本后，新请求会立即按新版本路由。"), top(6))
            addView(row(top = 16) {
                addView(chip("${apps.length()} 个凭据", R.color.wb_primary_container, R.color.wb_primary))
                addView(action("＋ 创建新 Key") { showAppEditor() }, weighted(start = 12))
            })
        })
        addView(section("接入配置"))
        addView(card {
            addView(labelValue("Base URL", "http://127.0.0.1:${ApiHostService.PORT}/v1"))
            addView(labelValue("认证请求头", "Authorization=[REDACTED] <API_KEY>"), top(12))
            addView(labelValue("兼容请求头", "X-Api-Key: <API_KEY>"), top(12))
            addView(row(top = 14) {
                addView(action("复制地址", outlined = true) { copyText("http://127.0.0.1:${ApiHostService.PORT}/v1", "Base URL") }, weighted())
                addView(action("复制认证格式", outlined = true) { copyText("Authorization=[REDACTED] <API_KEY>", "认证格式") }, weighted(start = 8))
            })
        })
        addView(section("应用凭据"))
        if (apps.length() == 0) {
            addView(stateCard("还没有 API Key", "创建后可选择自动生成安全 Key，或使用你已有的自定义 Key。", "待创建"))
        }
        for (i in 0 until apps.length()) {
            val app = apps.getJSONObject(i)
            val id = app.optLong("id")
            val enabled = app.optBoolean("enabled")
            addView(card(topMargin = if (i == 0) 0 else 10) {
                addView(row {
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(text(app.optString("name"), 18, true))
                        app.optString("note").takeIf { it.isNotBlank() }?.let { addView(caption(it), top(4)) }
                    }, weighted())
                    addView(chip(app.optString("region_label", "国内版"), R.color.wb_primary_container, R.color.wb_primary))
                    addView(chip(if (enabled) "已启用" else "已停用",
                        if (enabled) R.color.wb_success_container else R.color.wb_surface_variant,
                        if (enabled) R.color.wb_success else R.color.wb_on_surface_variant))
                })
                addView(TextView(this@MainActivity).apply {
                    text = app.optString("key_prefix", "••••••••")
                    textSize = 14f
                    typeface = Typeface.MONOSPACE
                    setTextColor(color(R.color.wb_on_surface_variant))
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = shape(R.color.wb_surface_container, 10f)
                }, top(14))
                addView(caption("${app.optLong("requests")} 次请求 · ${formatTokens(app.optLong("tokens"))} tokens"), top(8))
                addView(row(top = 12) {
                    addView(action("查看 Key", outlined = true) {
                        val key = NativeCore.getAppKey(this@MainActivity, id)
                        if (key.isNullOrBlank()) toast("无法读取 API Key，可通过编辑替换") else showAppKey(app.optString("name"), key)
                    }, weighted())
                    addView(action("编辑") { showAppEditor(app) }, weighted(start = 8))
                })
                addView(row(top = 8) {
                    addView(action(if (enabled) "停用" else "启用", outlined = true) {
                        val changed = NativeCore.setAppEnabled(this@MainActivity, id, !enabled)
                        if (changed) {
                            toast(if (enabled) "已停用 ${app.optString("name")}" else "已启用 ${app.optString("name")}")
                            render(Page.APPS)
                        } else toast("状态更新失败，请重试")
                    }, weighted())
                    addView(action("删除", outlined = true) { confirmDeleteApp(id, app.optString("name")) }, weighted(start = 8))
                })
            })
        }
    }

    private fun confirmDeleteApp(id: Long, name: String) {
        AlertDialog.Builder(this)
            .setTitle("删除 $name？")
            .setMessage("API Key 将立即失效，历史调用记录会保留。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                if (NativeCore.deleteApp(this, id)) {
                    toast("已删除 $name")
                    render(Page.APPS)
                } else toast("删除失败，请重试")
            }.show()
    }

    private fun showAppEditor(existing: JSONObject? = null) {
        val editing = existing != null
        val regions = arrayOf(AccountRegion.DOMESTIC, AccountRegion.INTERNATIONAL)
        var selectedRegion = if (editing) AccountRegion.from(existing?.optString("region")) else AccountRegion.DOMESTIC
        var customKeyEnabled = false
        val name = EditText(this).apply {
            hint = "例如：Cherry Studio"
            setText(existing?.optString("name").orEmpty())
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val note = EditText(this).apply {
            hint = "例如：仅用于本地开发（可选）"
            setText(existing?.optString("note").orEmpty())
            maxLines = 2
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val customKey = EditText(this).apply {
            hint = "输入 16–256 位自定义 Key"
            isSingleLine = true
            visibility = View.GONE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val keyMode = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val keepOrGenerate = RadioButton(this).apply {
            id = View.generateViewId()
            text = if (editing) "保留当前 Key（推荐）" else "自动生成安全 Key（推荐）"
            minHeight = dp(48)
            isChecked = true
        }
        val custom = RadioButton(this).apply {
            id = View.generateViewId()
            text = if (editing) "替换为自定义 Key" else "使用自定义 Key"
            minHeight = dp(48)
        }
        keyMode.addView(keepOrGenerate, RadioGroup.LayoutParams(-1, -2))
        keyMode.addView(custom, RadioGroup.LayoutParams(-1, -2))
        keyMode.setOnCheckedChangeListener { _, checkedId ->
            customKeyEnabled = checkedId == custom.id
            customKey.visibility = if (customKeyEnabled) View.VISIBLE else View.GONE
            if (customKeyEnabled) customKey.requestFocus()
        }
        val regionGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        regions.forEach { region ->
            regionGroup.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = "${region.label}  ·  ${region.defaultDomain}\n请求只会路由到${region.label}账号"
                minHeight = dp(58)
                isChecked = region == selectedRegion
                setOnCheckedChangeListener { _, checked -> if (checked) selectedRegion = region }
            }, RadioGroup.LayoutParams(-1, -2))
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
            addView(tonalCard {
                addView(text(if (editing) "编辑 API Key" else "创建 API Key", 20, true, R.color.wb_primary))
                addView(caption(if (editing) "名称、备注和调用版本可直接修改；只有选择替换时才会让旧 Key 失效。" else "为客户端创建独立凭据，并指定固定使用的 WorkBuddy 版本。"), top(5))
            })
            addView(text("名称", 14, true), top(16))
            addView(name, top(4))
            addView(text("备注", 14, true), top(12))
            addView(note, top(4))
            addView(text("API Key", 14, true), top(16))
            addView(keyMode, top(4))
            addView(customKey, top(4))
            addView(caption(if (editing) "替换后旧 Key 会立即失效。自定义 Key 不可包含空格。" else "自定义 Key 不可包含空格；建议优先使用自动生成。"), top(4))
            addView(text("调用版本", 14, true), top(16))
            addView(regionGroup, top(4))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(form) }
        val dialog = AlertDialog.Builder(this)
            .setView(scroll)
            .setNegativeButton("取消", null)
            .setPositiveButton(if (editing) "保存更改" else "创建 Key", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val appName = name.text.toString().trim()
                val appNote = note.text.toString().trim()
                val replacement = customKey.text.toString().takeIf { customKeyEnabled }
                if (appName.isBlank()) {
                    name.error = "请输入应用名称"
                    name.requestFocus()
                    return@setOnClickListener
                }
                if (customKeyEnabled && replacement.orEmpty().length !in 16..256) {
                    customKey.error = "自定义 Key 需要 16–256 位字符"
                    customKey.requestFocus()
                    return@setOnClickListener
                }
                val result = runCatching {
                    if (editing) NativeCore.updateApp(this, existing!!.optLong("id"), appName, appNote, selectedRegion, replacement)
                    else NativeCore.createApp(this, appName, appNote, selectedRegion, replacement)
                }
                result.onSuccess { saved ->
                    dialog.dismiss()
                    render(Page.APPS)
                    val returnedKey = saved.optString("key")
                    if (!editing || returnedKey.isNotBlank()) showAppKey("${saved.optString("name")} · ${saved.optString("region_label")}", returnedKey)
                    else toast("更改已保存")
                }.onFailure { error ->
                    val message = error.message ?: if (editing) "保存失败" else "创建失败"
                    if (message.contains("名称")) name.error = message
                    else if (message.contains("Key")) customKey.error = message
                    else toast(message)
                }
            }
        }
        dialog.show()
    }

    private fun showCreateAppDialog() = showAppEditor()

    /**
     * 优先级设置弹窗（Material 3 风格）。
     *
     * 视觉：surface 容器 28dp 圆角 + surfaceContainer 数值面板 16dp 圆角，内边距走 4dp 网格。
     * 交互：−/+ 步进、滑块、快捷档位三条路径共用同一个 sync()，把值调小时即时反馈——
     *      旧版只有一个写死的「优先级 +1」按钮，这正是"优先级不能减小"的根因。
     */
    private fun showPriorityDialog(accountKey: String, accountName: String, uid: String, current: Int) {
        val dialog = Dialog(this)
        // 历史遗留的大值原样作为上限保留，保证一定能往下调；新值限制在 0..20
        val ceiling = max(PRIORITY_MAX, current.coerceAtLeast(PRIORITY_MIN))
        var value = current.coerceIn(PRIORITY_MIN, ceiling)
        val presets = listOf(0, 1, 2, 5, 10).filter { it <= ceiling }

        val number = text("$value", 42, true, R.color.wb_primary).apply { gravity = Gravity.CENTER }
        val weightChip = chip("", R.color.wb_primary_container, R.color.wb_primary)
        val presetViews = mutableListOf<Pair<Int, TextView>>()

        val slider = SeekBar(this).apply {
            max = ceiling
            progress = value
            progressTintList = ColorStateList.valueOf(color(R.color.wb_primary))
            thumbTintList = ColorStateList.valueOf(color(R.color.wb_primary))
            progressBackgroundTintList = ColorStateList.valueOf(color(R.color.wb_surface_variant))
        }
        val minus = stepButton("−")
        val plus = stepButton("+")

        fun sync(next: Int, fromSlider: Boolean = false) {
            value = next.coerceIn(PRIORITY_MIN, ceiling)
            number.text = value.toString()
            weightChip.text = "选号权重 ×${value + 1}"
            if (!fromSlider) slider.progress = value
            presetViews.forEach { (preset, view) ->
                val selected = preset == value
                view.background = shape(if (selected) R.color.wb_primary_container else R.color.wb_surface_container, 16f)
                view.setTextColor(color(if (selected) R.color.wb_primary else R.color.wb_on_surface_variant))
            }
            minus.isEnabled = value > PRIORITY_MIN
            plus.isEnabled = value < ceiling
            minus.alpha = if (value > PRIORITY_MIN) 1f else 0.35f
            plus.alpha = if (value < ceiling) 1f else 0.35f
        }

        minus.setOnClickListener { sync(value - 1) }
        plus.setOnClickListener { sync(value + 1) }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) sync(progress, fromSlider = true)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(16))
            background = shape(R.color.wb_surface, 28f)
            addView(row {
                addView(text("设置优先级", 20, true), weighted())
                addView(text("✕", 18, false, R.color.wb_on_surface_variant).apply {
                    gravity = Gravity.CENTER
                    background = shape(R.color.wb_surface_container, 20f)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { dialog.dismiss() }
                }, lp(dp(40), dp(40)))
            })
            addView(caption("$accountName · ${shortUid(uid)}"), top(2))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(16), dp(16), dp(14))
                background = shape(R.color.wb_surface_container, 16f)
                addView(row { gravity = Gravity.CENTER; addView(weightChip) })
                addView(row(top = 6) {
                    gravity = Gravity.CENTER
                    addView(minus, lp(dp(48), dp(48)))
                    addView(number, weighted())
                    addView(plus, lp(dp(48), dp(48)))
                })
                addView(caption("数值越大，被选中调用 API 的概率越高").apply { gravity = Gravity.CENTER }, top(2))
            }, top(14))
            addView(slider, top(12))
            addView(row {
                addView(caption("0 · 不加权"), weighted())
                addView(caption("上限 $ceiling"))
            }, top(2))
            addView(row(top = 10) {
                addView(caption("快捷"), lp(-2, -2))
                presets.forEach { preset ->
                    val item = chip("$preset", R.color.wb_surface_container, R.color.wb_on_surface_variant).apply {
                        isClickable = true
                        isFocusable = true
                        minimumWidth = dp(44)
                        setOnClickListener { sync(preset) }
                    }
                    presetViews += preset to item
                    addView(item, lp(-2, -2, start = 8))
                }
            })
            addView(caption("只影响多账号之间的选号概率：账号额度、成功率与闲置时间仍照常参与加权。"), top(12))
            addView(row(top = 14) {
                addView(action("取消", outlined = true) { dialog.dismiss() }, weighted())
                addView(action("保存") {
                    val saved = NativeCore.setAccountPriority(this@MainActivity, accountKey, value)
                    dialog.dismiss()
                    toast(if (saved) "优先级已设为 $value · 选号权重 ×${value + 1}" else "设置失败：账号已不存在")
                    render(Page.ACCOUNTS)
                }, weighted(start = 10))
            })
        }

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setContentView(body)
        sync(value)
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun showAppKey(name: String, key: String) {
        AlertDialog.Builder(this).setTitle("$name · API Key")
            .setMessage("$key\n\n请仅提供给受信任的客户端。可随时回到此页查看或替换。")
            .setNeutralButton("复制 Key") { _, _ -> copyText(key, "$name API Key") }
            .setPositiveButton("完成", null).show()
    }

    @Suppress("DEPRECATION")
    private fun settingsPage(): View = page(withBack = true) {
        val settings = NativeCore.settings(this@MainActivity)
        addView(card {
            addView(text("自动化任务", 18, true))
            val keepalive = Switch(this@MainActivity).apply {
                text = "启用账号保活"
                textSize = 15f
                setTextColor(color(R.color.wb_on_surface))
                minHeight = dp(48)
                isChecked = settings.optString("keepalive_enabled", "1") in setOf("1", "true")
                setOnCheckedChangeListener { _, checked -> NativeCore.saveSettings(this@MainActivity, JSONObject().put("keepalive_enabled", if (checked) "1" else "0")) }
            }
            addView(keepalive)
            addView(settingField("每日签到时段", settings.optString("checkin_hours", "9,21"), "checkin_hours"))
            addView(settingField("额度刷新间隔（分钟）", settings.optString("credit_refresh_min", "30"), "credit_refresh_min"))
            addView(settingField("模型缓存（分钟）", settings.optString("model_ttl_min", "60"), "model_ttl_min"))
            // 记录保留期：这是控制数据体积最有效的一个开关，放在设置页让用户可自主调整。
            addView(settingField("使用记录保留天数", settings.optString("usage_retention_days", "30"), "usage_retention_days"))
            addView(action("立即刷新模型", outlined = true, full = true) { loadModels() }, top(10))
            addView(action("立即刷新额度", outlined = true, full = true) { refreshCredits(Page.SETTINGS) }, top(8))
        })
        addView(section("提示"))
        addView(tonalCard {
            addView(text("国际版没有可用的手动签到活动", 15, true))
            addView(caption("国际版官网称其为“每日活跃奖励”：实际使用 CodeBuddy 国际版产品后由上游自动发放，应用不会对国际账号定时调用国内签到接口。"), top(6))
        })
        addView(section("数据管理"))
        addView(card {
            addView(caption("使用记录会保存每次请求的输入/输出/思考链，是应用数据体积的主要来源。"))
            addView(storageRow(), top(10))
            addView(action("查看存储占用", outlined = true, full = true) { showStorageInfo() }, top(10))
            addView(action("清理 WebView 缓存", outlined = true, full = true) {
                // 登录窗口使用的 WebView 会把登录页的 JS/图片/字体写进磁盘，且自身不限容量；
                // 这是"记录不多但数据很大"时最可能的占用来源，单独给一个清理入口。
                NativeCore.clearWebViewCache(this@MainActivity)
                toast("已清理 WebView 缓存")
                storageUsageText?.text = storageSummary()
            }, top(8))
            addView(action("截断超长记录内容", outlined = true, full = true) {
                val count = NativeCore.trimUsage(this@MainActivity)
                // 裁剪只是把字段改短，文件不会自动变小，必须 VACUUM 才能把空间还给系统。
                NativeCore.vacuum(this@MainActivity)
                toast("已处理 $count 条记录并回收空间")
                storageUsageText?.text = storageSummary()
            }, top(8))
            addView(action("清理过期记录", outlined = true, full = true) {
                val days = storageRetentionDays()
                val count = NativeCore.cleanupUsage(this@MainActivity, days)
                NativeCore.vacuum(this@MainActivity)
                toast("已清理 $count 条 $days 天前的记录并回收空间")
                storageUsageText?.text = storageSummary()
            }, top(8))
        })
        addView(section("系统"))
        addView(card {
            addView(menuRow("↗", "电池优化白名单", "防后台冻结，其他软件随时可连") {
                // 引导用户把 APP 加入忽略电池优化列表：MIUI 等激进省电策略会冻结后台进程，
                // 导致本地反代端口仍在监听但请求全部挂起（其他软件拉模型超时/失败的根因）。
                val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } else toast("已在电池优化白名单中")
            })
            addView(divider())
            addView(menuRow("↗", "通知权限", "前台服务运行状态") {
                if (Build.VERSION.SDK_INT >= 26) startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            })
            addView(divider())
            addView(menuRow("↗", "应用详情", "电池、存储和权限") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            })
        })
        addView(section("后台保活 · ${keepAliveVendorLabel()}"))
        addView(tonalCard {
            addView(text("为什么还会被杀？", 15, true))
            addView(caption("${keepAliveVendorLabel()} 对后台应用有独立于「电池优化白名单」的一套管控，" +
                "必须逐项手动放开。上面的白名单只解决其中一项，其余开关若不打开，" +
                "本地 API 在锁屏或长时间待机后仍会被系统回收。"), top(6))
            addView(caption("另外要说明：被系统回收本身很难完全避免，真正影响体验的是「回收后能否自动恢复」。" +
                "本版已加入开机自启、划掉任务后自动重启、15 分钟看门狗兜底，" +
                "即使中途被杀也会自动拉回；配合下面的设置可显著降低被杀的频率。"), top(6))
        })
        addView(card(topMargin = 12) {
            addView(text("请依次完成以下设置", 16, true))
            addView(caption("点击每一项可直接跳到对应设置页；跳转失败会打开应用详情页，请按提示文字手动查找。"), top(6))
            keepAliveSteps().forEachIndexed { index, step ->
                addView(menuRow("${index + 1}", step.title, step.desc) { openKeepAliveSetting(step.key) }, top(if (index == 0) 12 else 6))
            }
            addView(action("一键检查当前状态", outlined = true, full = true) { checkKeepAliveStatus() }, top(12))
        })
        addView(section("状态"))
        addView(stateCard("设置已持久化", "界面支持系统日/夜主题；触控目标不小于 48dp。", "就绪"))
    }

    private fun settingField(label: String, value: String, key: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(caption(label))
        addView(EditText(this@MainActivity).apply {
            setText(value); minHeight = dp(48); setSingleLine(true)
            setTextColor(color(R.color.wb_on_surface)); backgroundTintList = android.content.res.ColorStateList.valueOf(color(R.color.wb_primary))
            setOnFocusChangeListener { _, focused -> if (!focused) NativeCore.saveSettings(this@MainActivity, JSONObject().put(key, text.toString())) }
        }, matchWidth())
    }

    private fun toggleServer() {
        if (ApiHostService.running) {
            startService(Intent(this, ApiHostService::class.java).setAction(ApiHostService.ACTION_STOP))
            record("已请求停止本地 API")
        } else {
            if (NativeCore.loadAccount(this) == null) {
                toast("请先导入或登录 WorkBuddy 账号")
                render(Page.ACCOUNTS)
                return
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(Intent(this, ApiHostService::class.java))
            else startService(Intent(this, ApiHostService::class.java))
            record("正在启动本地 API")
            updateServerHeader(true)
        }
        render(Page.OVERVIEW)
    }

    private fun updateServerHeader(running: Boolean) {
        if (!::headerStatus.isInitialized) return
        headerStatus.text = if (running) "● 运行中" else "○ 已停止"
        headerStatus.setTextColor(color(if (running) R.color.wb_success else R.color.wb_on_surface_variant))
        headerStatus.background = shape(if (running) R.color.wb_success_container else R.color.wb_surface_variant, 16f)
    }

    // ==================== 后台保活引导 ====================
    //
    // 各厂商对后台应用有各自独立于 AOSP「电池优化白名单」的管控层，
    // 且入口藏在系统设置深处（一加/OPPO 的 ColorOS 尤为激进：自启动、后台运行、
    // 耗电管理三处都要放开，缺一项锁屏后就会被回收）。这里按厂商生成对应的
    // 步骤清单并提供跳转，尽量减少用户"找不到开关"的挫败感。

    /** 保活引导步骤：key 用于选择跳转目标，title/desc 是展示文案。 */
    private data class KeepAliveStep(val key: String, val title: String, val desc: String)

    /** 识别当前 ROM 厂商，用于选择引导文案与设置页跳转。 */
    private fun keepAliveVendor(): String {
        val brand = (Build.BRAND + " " + Build.MANUFACTURER).lowercase(Locale.US)
        return when {
            brand.contains("oneplus") || brand.contains("oppo") || brand.contains("realme") -> "coloros"
            brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") -> "miui"
            brand.contains("huawei") || brand.contains("honor") -> "harmony"
            brand.contains("vivo") || brand.contains("iqoo") -> "funtouch"
            brand.contains("samsung") -> "oneui"
            else -> "generic"
        }
    }

    private fun keepAliveVendorLabel(): String = when (keepAliveVendor()) {
        "coloros" -> "ColorOS / 一加"
        "miui" -> "MIUI / 红米"
        "harmony" -> "HarmonyOS / 华为"
        "funtouch" -> "OriginOS / vivo"
        "oneui" -> "One UI / 三星"
        else -> "当前设备"
    }

    /** 各厂商的保活步骤清单。desc 尽量写清"在设置里的哪一层"，降低查找成本。 */
    private fun keepAliveSteps(): List<KeepAliveStep> = when (keepAliveVendor()) {
        "coloros" -> listOf(
            KeepAliveStep("autostart", "允许自启动", "设置 → 应用 → 自启动管理（或 手机管家 → 权限隐私 → 自启动）"),
            KeepAliveStep("background", "允许后台运行", "设置 → 应用 → 应用管理 → 本应用 → 耗电管理 → 允许后台运行"),
            KeepAliveStep("battery", "耗电管理设为「不限制」", "设置 → 应用 → 本应用 → 耗电管理 → 允许完全后台行为"),
            KeepAliveStep("lock", "在最近任务中加锁", "打开最近任务 → 下拉本应用卡片 → 点锁图标固定，避免一键清理时被清掉"),
            KeepAliveStep("battery_optimization", "忽略电池优化", "设置 → 电池 → 更多设置 → 电池优化 → 本应用 → 不优化"),
        )
        "miui" -> listOf(
            KeepAliveStep("autostart", "开启自启动", "设置 → 应用设置 → 应用管理 → 本应用 → 自启动"),
            KeepAliveStep("battery", "省电策略设为「无限制」", "设置 → 应用设置 → 本应用 → 省电策略"),
            KeepAliveStep("lock", "在最近任务中加锁", "最近任务 → 长按本应用卡片 → 点锁图标"),
            KeepAliveStep("battery_optimization", "忽略电池优化", "设置 → 应用设置 → 特殊权限 → 电池优化 → 本应用"),
        )
        "harmony" -> listOf(
            KeepAliveStep("autostart", "允许自启动", "设置 → 应用 → 应用启动管理 → 本应用 → 手动管理（三项全开）"),
            KeepAliveStep("battery", "允许后台活动", "设置 → 电池 → 更多电池设置 → 应用启动管理"),
            KeepAliveStep("lock", "锁定后台", "最近任务 → 下拉本应用卡片 → 点锁图标"),
            KeepAliveStep("battery_optimization", "忽略电池优化", "设置 → 应用 → 特殊访问权限 → 电池优化"),
        )
        else -> listOf(
            KeepAliveStep("battery_optimization", "忽略电池优化", "设置 → 电池 → 电池优化 → 本应用 → 不优化"),
            KeepAliveStep("background", "允许后台活动", "设置 → 应用 → 本应用 → 电池 → 允许后台活动"),
            KeepAliveStep("lock", "在最近任务中加锁", "最近任务 → 本应用卡片 → 点锁图标固定"),
        )
    }

    /**
     * 跳到对应的设置页。
     * 厂商设置页的 component 名并不稳定（随 ROM 版本变化），因此用「候选列表 + 逐个尝试」，
     * 全部失败则回落到本应用详情页，保证任何设备上点下去都有反馈，不会静默无响应。
     */
    private fun openKeepAliveSetting(key: String) {
        val candidates = when (key) {
            "autostart" -> when (keepAliveVendor()) {
                "coloros" -> listOf(
                    "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity",
                    "com.oplus.safecenter/com.oplus.safecenter.permission.startup.StartupAppListActivity",
                    "com.coloros.safecenter/com.coloros.safecenter.startupapp.StartupAppListActivity",
                )
                "miui" -> listOf("com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity")
                "harmony" -> listOf(
                    "com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity",
                    "com.huawei.systemmanager/.appcontrol.activity.StartupAppControlActivity",
                )
                "funtouch" -> listOf("com.vivo.permissionmanager/.activity.BgStartUpManagerActivity")
                else -> emptyList()
            }
            "background" -> when (keepAliveVendor()) {
                "coloros" -> listOf(
                    "com.oplus.battery/com.oplus.powermanager.fuelgaue.PowerUsageModelActivity",
                    "com.coloros.oppoguardelf/com.coloros.powermanager.fuelgaue.PowerUsageModelActivity",
                )
                else -> listOf("android.settings.APPLICATION_DETAILS_SETTINGS")
            }
            "battery" -> when (keepAliveVendor()) {
                "coloros" -> listOf(
                    "com.oplus.battery/com.oplus.powermanager.fuelgaue.PowerUsageModelActivity",
                    "com.coloros.oppoguardelf/com.coloros.powermanager.fuelgaue.PowerUsageModelActivity",
                )
                "miui" -> listOf("com.miui.powerkeeper/com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                else -> emptyList()
            }
            else -> emptyList()
        }
        // 先试厂商页面，再试系统级页面，最后一定回落到应用详情页。
        val intents = candidates.map { component ->
            Intent().setComponent(android.content.ComponentName.unflattenFromString(component))
        } + Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")
        )
        // "忽略电池优化" 用标准 API 最稳，单独处理。
        if (key == "battery_optimization") {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) { toast("已在电池优化白名单中，无需设置"); return }
            runCatching {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }.onFailure {
                runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    .onFailure { openAppDetails() }
            }
            return
        }
        if (key == "lock") { toast("本项无法自动跳转：请打开最近任务，下拉本应用卡片后点锁图标"); return }
        intents.forEach { intent ->
            if (runCatching { startActivity(intent) }.isSuccess) return
        }
        openAppDetails()
    }

    private fun openAppDetails() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }.onFailure { toast("无法打开系统设置，请手动在设置中查找") }
    }

    /** 检查当前能自动判定的保活项，给出还差什么的明确结论。 */
    /** 存储占用摘要文本：界面顶部常驻显示，让用户随时知道数据长到多大了。 */
    private var storageUsageText: android.widget.TextView? = null

    private fun storageRetentionDays(): Int =
        NativeCore.settings(this).optString("usage_retention_days", "30").toIntOrNull()?.coerceIn(1, 3650) ?: 30

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024)
        bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun storageSummary(): String = runCatching {
        val info = NativeCore.storageInfo(this)
        val rows = info.optLong("rows")
        val db = info.optLong("db_bytes")
        val free = info.optLong("free_bytes")
        buildString {
            append("当前占用 ${formatBytes(db)} · $rows 条记录")
            if (free > 1024 * 512) append("\n其中可回收空闲空间 ${formatBytes(free)}（点下方清理即可释放）")
            append("\n自动保留 ${storageRetentionDays()} 天，服务运行时会每日自动清理")
        }
    }.getOrDefault("存储信息读取失败")

    private fun storageRow(): android.view.View {
        val text = caption(storageSummary())
        storageUsageText = text
        return text
    }

    /** 存储占用详情：把体积拆开，才能看出到底是输入、输出还是思考链在占空间。 */
    private fun showStorageInfo() {
        val sb = StringBuilder()
        runCatching {
            val info = NativeCore.storageInfo(this)
            val rows = info.optLong("rows")
            val input = info.optLong("input_bytes")
            val output = info.optLong("output_bytes")
            val reasoning = info.optLong("reasoning_bytes")
            val content = input + output + reasoning
            sb.append("【数据库】\n")
            sb.append("文件大小：${formatBytes(info.optLong("db_bytes"))}（$rows 条记录）\n")
            sb.append("可回收空闲：${formatBytes(info.optLong("free_bytes"))}\n")
            sb.append("内容合计：${formatBytes(content)}\n")
            sb.append("　输入 ${formatBytes(input)} / 输出 ${formatBytes(output)} / 思考 ${formatBytes(reasoning)}\n")
        }.onFailure { sb.append("数据库信息读取失败：${it.message}\n") }
        runCatching {
            // 实测各目录体积：系统设置只给一个总数，这里直接量出来，避免靠猜。
            val b = NativeCore.storageBreakdown(this)
            sb.append("\n【目录占用（实测）】\n")
            sb.append("数据目录合计：${formatBytes(b.optLong("data_total"))}\n")
            sb.append("· 数据库目录：${formatBytes(b.optLong("databases"))}\n")
            sb.append("· WebView 缓存：${formatBytes(b.optLong("webview"))}\n")
            sb.append("· cache 目录：${formatBytes(b.optLong("cache"))}\n")
            sb.append("· files 目录：${formatBytes(b.optLong("files"))}\n")
            sb.append("· 代码缓存：${formatBytes(b.optLong("code_cache"))}\n")
            sb.append("· 外部缓存：${formatBytes(b.optLong("external_cache"))}\n")
        }.onFailure { sb.append("\n目录统计失败：${it.message}\n") }
        AlertDialog.Builder(this).setTitle("存储占用明细").setMessage(sb.toString().trim())
            .setPositiveButton("知道了", null)
            .setNeutralButton("清理 WebView 缓存") { _, _ ->
                // WebView 的磁盘缓存没有容量上限，登录页加载的 JS/图片/字体会长期堆积，
                // 是"数据体积远超记录本身"的主因之一。这里给一个一键清理入口。
                NativeCore.clearWebViewCache(this)
                toast("已清理 WebView 缓存")
                storageUsageText?.text = storageSummary()
            }.show()
    }

    private fun checkKeepAliveStatus() {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val ignoringBattery = pm.isIgnoringBatteryOptimizations(packageName)
        val notifications = if (Build.VERSION.SDK_INT >= 33) {
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
        val channelOff = ApiHostService.isChannelDisabled(this)
        // 精确闹钟是看门狗的命门：被关掉后只能降级为非精确闹钟，Doze 下可能延迟数小时，
        // 表现为"服务被杀后久久不恢复"。这是用户很难自行联想到的一项，必须显式提示。
        val exactAlarm = if (Build.VERSION.SDK_INT >= 31) {
            runCatching { (getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager).canScheduleExactAlarms() }.getOrDefault(true)
        } else true
        val network = try {
            val nm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            nm.activeNetwork != null
        } catch (e: Exception) { true }
        val sb = StringBuilder()
        sb.append(if (ignoringBattery) "✓ 已忽略电池优化" else "✗ 未加入电池优化白名单（必须开启）").append('\n')
        sb.append(if (notifications) "✓ 通知权限已授予" else "✗ 通知权限未授予，前台服务会被系统隐藏（必须开启）").append('\n')
        sb.append(if (!channelOff) "✓ 前台服务通知渠道正常" else "✗ 前台服务通知渠道被关闭，服务会被削弱（点下方「重开通知」）").append('\n')
        sb.append(if (exactAlarm) "✓ 精确闹钟已允许（看门狗可准时唤醒）" else "✗ 精确闹钟被禁用，被杀后可能很久才恢复（必须开启）").append('\n')
        sb.append(if (network) "✓ 网络可用" else "✗ 当前无网络").append('\n')
        sb.append('\n')
        sb.append("以下项目系统未开放查询接口，请自行确认：\n")
        keepAliveSteps().filter { it.key == "autostart" || it.key == "background" || it.key == "battery" || it.key == "lock" }
            .forEach { sb.append("· ${it.title}\n") }
        val builder = AlertDialog.Builder(this).setTitle("保活状态检查").setMessage(sb.toString().trim())
        if (!exactAlarm) {
            builder.setNeutralButton("开启精确闹钟") { _, _ ->
                runCatching {
                    startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:$packageName")))
                }.onFailure { toast("请到 设置 → 应用 → 特殊权限 中允许「闹钟和提醒」") }
            }
        } else if (channelOff) {
            builder.setNeutralButton("重开通知") { _, _ -> openChannelSettings() }
        }
        builder.setPositiveButton("知道了", null).show()
    }

    /** 跳转到本应用的前台服务通知渠道设置，供用户把被关闭的渠道重新打开。 */
    private fun openChannelSettings() {
        runCatching {
            startActivity(Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, "workbuddy_native_api")
            })
        }.onFailure {
            runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                })
            }.onFailure { toast("请到 设置 → 通知 中重新开启本应用的通知") }
        }
    }

    private fun chooseAuth() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }, REQ_AUTH)
    }

    /**
     * 导出单个账号的 auth 文件。
     * 走系统 SAF 让用户自选保存位置（下载目录/网盘/文件管理器都行），文件名带昵称与 UID 便于区分。
     */
    private fun exportAccount(accountKey: String, nickname: String, uid: String) {
        pendingExport = runCatching { NativeCore.exportAccount(this, accountKey) }.getOrNull()
        if (pendingExport.isNullOrBlank()) { toast("该账号没有可导出的认证数据"); return }
        exportFileName = "workbuddy-${sanitizeFileName(nickname.ifBlank { uid })}.json"
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, exportFileName)
            },
            REQ_EXPORT
        )
    }

    /** 导出全部账号为单个 JSON（一个账号时导出对象，多个时导出数组，均可直接导回）。 */
    private fun exportAllAccounts() {
        val accounts = NativeCore.listAccounts(this)
        if (accounts.length() == 0) { toast("当前没有可导出的账号"); return }
        pendingExport = runCatching { NativeCore.exportAllAccounts(this) }.getOrNull()
        if (pendingExport.isNullOrBlank()) { toast("导出失败：没有可导出的认证数据"); return }
        exportFileName = "workbuddy-accounts-${accounts.length()}.json"
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, exportFileName)
            },
            REQ_EXPORT
        )
    }

    /** 文件名里去掉路径分隔符等非法字符，避免 SAF 拒绝或生成奇怪的名字。 */
    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_').take(40).ifBlank { "account" }

    /** 把待导出的 JSON 写入用户选定的目标（SAF 返回的 Uri）。 */
    private fun writeExport(uri: Uri) {
        val payload = pendingExport
        pendingExport = null
        if (payload.isNullOrBlank()) { toast("导出内容已失效，请重试"); return }
        runCatching {
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                ?: error("无法打开目标文件")
        }.fold(
            onSuccess = { toast("已导出 ${exportFileName}（含登录凭证，请妥善保管）") },
            onFailure = { toast("导出失败：${it.message ?: "写入被拒绝"}") }
        )
    }

    @Deprecated("Legacy callback retained for minSdk compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_AUTH && resultCode == RESULT_OK) data?.data?.let { importAuth(it) }
        if (requestCode == REQ_EXPORT && resultCode == RESULT_OK) data?.data?.let { writeExport(it) }
    }

    private fun importAuth(uri: Uri) = runTask("导入账号", Page.ACCOUNTS) {
        val raw = contentResolver.openInputStream(uri)?.use { stream ->
            val max = 1024 * 1024
            val bytes = stream.readNBytes(max + 1)
            require(bytes.size <= max) { "认证文件不能超过 1 MB" }
            bytes.toString(Charsets.UTF_8)
        } ?: error("无法读取所选文件")
        runCatching { JSONObject(raw) }.fold(
            onSuccess = { parsed ->
                val account = NativeCore.saveAccount(this, parsed)
                "账号 ${account.nickname} (${account.uid}) 已导入"
            },
            onFailure = {
                val result = NativeCore.importAccounts(this, JSONArray(raw))
                val imported = result.getJSONArray("imported")
                val rejected = result.getJSONArray("rejected")
                require(imported.length() > 0) { rejected.optJSONObject(0)?.optString("error", "认证文件为空") ?: "认证文件为空" }
                "已导入 ${imported.length()} 个账号${if (rejected.length() > 0) "，${rejected.length()} 个失败" else ""}"
            }
        )
    }

    private fun refreshCredits(returnPage: Page = Page.ACCOUNTS) = runTask("刷新全部额度", returnPage) {
        val response = NativeCore.refreshAllCredits(this)
        val summary = response.getJSONObject("summary")
        creditCache = summary
        val results = response.getJSONArray("results")
        val failed = (0 until results.length()).count { !results.getJSONObject(it).optBoolean("ok") }
        "已刷新 ${results.length() - failed}/${results.length()} 个账号，合计剩余 ${"%.1f".format(summary.optDouble("remain"))} / ${"%.1f".format(summary.optDouble("total"))} 积分${if (failed > 0) "，$failed 个失败" else ""}"
    }

    private fun checkIn() = runTask("全部账号签到", Page.ACCOUNTS) {
        val response = NativeCore.checkInAll(this)
        val results = response.getJSONArray("results")
        checkinResults = results
        val success = (0 until results.length()).count { results.getJSONObject(it).optBoolean("ok") }
        val failed = (0 until results.length()).count {
            val item = results.getJSONObject(it); !item.optBoolean("ok") && !item.optBoolean("skipped")
        }
        val skipped = results.length() - success - failed
        "签到完成：成功/已签 $success，失败 $failed${if (skipped > 0) "，跳过 $skipped" else ""}；详情见账号页"
    }

    private fun loadModels() = runTask("刷新模型", Page.MODELS) {
        val response = NativeCore.refreshAllModels(this)
        val data = response.getJSONArray("models")
        modelCache = data
        val failures = response.getJSONArray("failures")
        if (failures.length() == 0) {
            "已加载 ${data.length()} 个模型（国内版 / 国际版均刷新成功）"
        } else {
            val detail = (0 until failures.length()).joinToString("；") {
                val item = failures.getJSONObject(it)
                "${item.optString("region_label")}：${item.optString("message")}"
            }
            "已加载 ${data.length()} 个模型；$detail"
        }
    }

    /**
     * 打开内置登录窗口。
     * [clearSession] 默认 true：**每次都从干净会话开始**。
     * 这是必须的——Google / GitHub 这类 IdP 只要在 WebView 里留有上次的登录 Cookie，
     * 再点「Google 登录」就会被静默授权到上次那个账号，用户永远换不到第二个账号；
     * 而且上游页面里的「退出」只清 codebuddy.ai 自己的会话，清不掉 accounts.google.com 的票据，
     * 所以必须由打开窗口这一侧负责清空。传 false 仅用于确实需要复用会话的续期场景。
     */
    private fun startOAuth(region: AccountRegion, clearSession: Boolean = true) {
        showLoading("正在创建${region.label}登录会话…")
        io.execute {
            val result = runCatching { NativeCore.beginOAuth(region) }
            main.post {
                result.fold({ session ->
                    oauthSession = session
                    oauthAttempts = 0
                    record("已在内置窗口打开${region.label}登录，等待授权")
                    render(Page.ACCOUNTS)
                    startActivity(
                        Intent(this@MainActivity, OAuthWebActivity::class.java)
                            .putExtra(OAuthWebActivity.EXTRA_AUTH_URL, session.authUrl)
                            .putExtra(OAuthWebActivity.EXTRA_REGION_LABEL, region.label)
                            // true = 清空内嵌窗口的登录态，强制 Google/GitHub 等 IdP 重新选账号
                            .putExtra(OAuthWebActivity.EXTRA_CLEAR_SESSION, clearSession)
                    )
                    toast(if (clearSession) "已打开登录窗口（可切换其它账号）" else "已打开内置登录窗口，授权成功后自动关闭")
                    pollOAuth()
                }, { showError("发起${region.label}登录失败", it) { startOAuth(region, clearSession) } })
            }
        }
    }

    /** 「切换账号登录」：让用户先选版本，再以全新会话打开登录窗口。 */
    private fun startOAuthSwitch() {
        AlertDialog.Builder(this)
            .setTitle("切换账号登录")
            .setMessage("将清空内置登录窗口的会话状态，让 Google / GitHub 等重新弹出账号选择器。")
            .setPositiveButton("国际版") { _, _ -> startOAuth(AccountRegion.INTERNATIONAL, clearSession = true) }
            .setNegativeButton("国内版") { _, _ -> startOAuth(AccountRegion.DOMESTIC, clearSession = true) }
            .setNeutralButton("取消", null)
            .show()
    }

    private fun pollOAuth() {
        val session = oauthSession ?: return
        main.postDelayed({
            if (isFinishing || oauthSession == null) return@postDelayed
            io.execute {
                val result = runCatching { NativeCore.pollOAuth(this, session) }
                main.post {
                    result.onFailure { showError("${session.region.label}登录轮询失败", it) { startOAuth(session.region) } }
                    val account = result.getOrNull()
                    when {
                        account != null -> {
                            oauthSession = null
                            // 先在网页会话还在时取走收尾所需的会话信息，再关窗；网页侧的收尾动作全程后台静默
                            AccountSync.afterAuthorize(session.region.defaultDomain)
                            OAuthWebActivity.dismissIfOpen()
                            record("${account.region.label}网页登录成功：${account.nickname}")
                            toast("${account.region.label}登录成功")
                            render(Page.ACCOUNTS)
                        }
                        ++oauthAttempts < 150 -> pollOAuth()
                        else -> {
                            oauthSession = null
                            showError("${session.region.label}登录等待超时", IllegalStateException("请重新发起登录")) { startOAuth(session.region) }
                        }
                    }
                }
            }
        }, 2000)
    }

    /** 内置登录窗口被用户关掉：结束本轮等待。授权成功自动关窗时 oauthSession 已清空，这里不会误取消。 */
    private fun onOAuthWindowClosed() {
        if (oauthSession == null) return
        oauthSession = null
        toast("已关闭内置登录窗口")
    }

    private fun runTask(name: String, returnPage: Page, block: () -> String) {
        showLoading("$name…")
        io.execute {
            val result = runCatching(block)
            main.post {
                result.fold({ message ->
                    record("$name：$message")
                    toast(message)
                    render(returnPage)
                }, { error -> showError("${name}失败", error) { runTask(name, returnPage, block) } })
            }
        }
    }

    private fun showLoading(message: String) {
        contentHost.removeAllViews()
        contentHost.addView(page {
            addView(card {
                addView(ProgressBar(this@MainActivity).apply { isIndeterminate = true }, lp(-1, dp(48)))
                addView(text(message, 16, true).apply { gravity = Gravity.CENTER }, top(12))
                addView(caption("请稍候，不要关闭应用。" ).apply { gravity = Gravity.CENTER }, top(4))
            })
        }, match())
    }

    private fun showError(title: String, error: Throwable, retry: () -> Unit) {
        contentHost.removeAllViews()
        contentHost.addView(page {
            addView(stateCard(title, error.message ?: error.javaClass.simpleName, "错误", error = true))
            addView(action("重试", full = true, click = retry), top(12))
            addView(action("返回", outlined = true, full = true) { render(currentPage) }, top(8))
        }, match())
    }

    private fun copyKey() {
        val key = NativeCore.apiKey(this)
        copyText(key, "API Key")
        AlertDialog.Builder(this)
            .setTitle("本地 API Key")
            .setMessage("$key\n\n已复制。可放入 Authorization=[REDACTED] 请求头或 X-Api-Key。")
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun maskedKey(): String {
        val key = NativeCore.apiKey(this)
        return if (key.length > 12) key.take(7) + "••••••••" + key.takeLast(4) else "••••••••"
    }

    private fun copyText(value: String, label: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, value))
        toast("已复制 $label")
    }

    private fun record(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(Date())
        events.addFirst("$timestamp  $message")
        while (events.size > 30) events.removeLast()
    }

    // ---- Native view factory -------------------------------------------------

    private fun page(withBack: Boolean = false, build: LinearLayout.() -> Unit): ScrollView = ScrollView(this).apply {
        isFillViewport = true
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
            build()
        }, matchWidth())
    }

    private fun card(topMargin: Int = 0, build: LinearLayout.() -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = shape(R.color.wb_surface, 12f, R.color.wb_outline_variant)
        build()
        layoutParams = lp(-1, -2, top = topMargin)
    }

    private fun tonalCard(topMargin: Int = 0, build: LinearLayout.() -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = shape(R.color.wb_surface_container, 12f)
        build()
        layoutParams = lp(-1, -2, top = topMargin)
    }

    private fun metric(title: String, value: String, note: String): View = card {
        addView(caption(title))
        addView(text(value, 25, true, R.color.wb_primary), top(4))
        addView(caption(note), top(2))
    }

    private fun modelCard(model: JSONObject): View = card {
        addView(row {
            addView(text(model.optString("name", model.optString("id")), 17, true), weighted())
            val regions = model.optJSONArray("regions") ?: JSONArray()
            val regionText = (0 until regions.length()).joinToString(" / ") { AccountRegion.from(regions.optString(it)).label }
            if (regionText.isNotBlank()) addView(chip(regionText, R.color.wb_surface_variant, R.color.wb_on_surface_variant))
            addView(chip(if (model.optBoolean("supports_images")) "多模态" else "文本", R.color.wb_primary_container, R.color.wb_primary))
        })
        addView(TextView(this@MainActivity).apply {
            text = model.optString("id")
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(color(R.color.wb_on_surface_variant))
        }, top(4))
        // 成本倍率来自上游 credits；0 倍率直接说「免费」，上游没标注就不显示这一行（不臆造）
        costChip(model)?.let { addView(row { addView(it) }, top(8)) }
        val capabilities = buildList {
            if (model.optBoolean("supports_tools")) add("工具调用")
            if (model.optBoolean("supports_images")) add("图像输入")
            model.optLong("context_length").takeIf { it > 0 }?.let { add("上下文 ${formatTokens(it)}") }
            model.optLong("max_output_tokens").takeIf { it > 0 }?.let { add("输出 ${formatTokens(it)}") }
        }
        val source = model.optString("catalog_source")
        val summary = if (capabilities.isEmpty()) "标准聊天模型" else capabilities.joinToString("  ·  ")
        // 官方目录里非 CLI 推荐的模型也全部列出，但要如实标注，避免用户误以为该模型在当前区域必然可用。
        val notes = buildList {
            if (model.has("cli_recommended") && !model.optBoolean("cli_recommended")) {
                add("官方目录已收录 · 未列入 CLI 推荐，调用若报错可改用同族模型")
            }
            if (source.isNotBlank()) add(source)
        }
        addView(caption(if (notes.isEmpty()) summary else summary + "\n" + notes.joinToString("\n")), top(10))
    }

    /** 成本倍率徽标：上游 credits（成本系数）。>0 显示「成本 x0.79」，0 显示「免费」；上游未标注返回 null。 */
    private fun costChip(model: JSONObject): View? {
        if (!model.has("credits") || model.isNull("credits")) return null
        val credits = model.optDouble("credits", Double.NaN)
        if (!credits.isFinite()) return null
        return if (credits <= 0.0) chip("免费", R.color.wb_success_container, R.color.wb_success)
        else chip("成本 x" + "%.2f".format(credits), R.color.wb_primary_container, R.color.wb_primary)
    }

    private fun eventCard(message: String): View = card {
        addView(row {
            addView(chip("完成", R.color.wb_success_container, R.color.wb_success))
            addView(text(message, 14, false), weighted(start = 10))
        })
    }

    private fun stateCard(title: String, detail: String, label: String, error: Boolean = false): View = card {
        addView(chip(label, if (error) R.color.wb_error_container else R.color.wb_surface_variant,
            if (error) R.color.wb_error else R.color.wb_on_surface_variant))
        addView(text(title, 18, true, if (error) R.color.wb_error else R.color.wb_on_surface), top(12))
        addView(caption(detail), top(6))
    }

    private fun menuRow(icon: String, title: String, subtitle: String, click: () -> Unit): View = row {
        isClickable = true
        isFocusable = true
        minimumHeight = dp(64)
        setPadding(0, dp(6), 0, dp(6))
        setOnClickListener { click() }
        addView(TextView(this@MainActivity).apply {
            text = icon
            gravity = Gravity.CENTER
            textSize = 21f
            setTextColor(color(R.color.wb_primary))
            background = shape(R.color.wb_primary_container, 12f)
        }, lp(dp(48), dp(48)))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(title, 16, true))
            addView(caption(subtitle), top(2))
        }, weighted(start = 12))
        addView(text("›", 28, false, R.color.wb_outline))
    }

    private fun row(top: Int = 0, build: LinearLayout.() -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        build()
        layoutParams = lp(-1, -2, top = top)
    }

    private fun text(value: String, size: Int = 15, bold: Boolean = false, colorRes: Int = R.color.wb_on_surface): TextView = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color(colorRes))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(0f, 1.12f)
    }

    private fun caption(value: String): TextView = text(value, 13, false, R.color.wb_on_surface_variant)

    private fun section(value: String): TextView = text(value, 16, true).apply {
        setPadding(0, dp(22), 0, dp(10))
    }

    private fun chip(value: String, backgroundRes: Int, textRes: Int): TextView = text(value, 12, true, textRes).apply {
        gravity = Gravity.CENTER
        minHeight = dp(32)
        setPadding(dp(12), 0, dp(12), 0)
        background = shape(backgroundRes, 16f)
    }

    private fun labelValue(label: String, value: String): View = row {
        addView(caption(label), lp(dp(92), -2))
        addView(text(value, 14).apply { setTextIsSelectable(true) }, weighted())
    }

    /**
     * 账号卡片里的「优先级」行：整行可点（48dp 触控目标），右侧显示当前值与它对选号的权重倍数。
     * 权重倍数与 NativeStore.accountWeight() 的 1.0 + max(0, priority) 保持一致，避免界面出现两套口径。
     */
    private fun priorityRow(priority: Int, onClick: () -> Unit): View = row {
        isClickable = true
        isFocusable = true
        minimumHeight = dp(48)
        setOnClickListener { onClick() }
        addView(caption("优先级"), lp(dp(92), -2))
        addView(text("$priority", 14), weighted())
        addView(chip("权重 ×${priority + 1}", R.color.wb_primary_container, R.color.wb_primary))
        addView(text("›", 22, false, R.color.wb_outline), lp(-2, -2, start = 8))
    }

    /** 步进按钮：48dp 圆形触控目标；禁用态只降透明度，避免布局跳动。 */
    private fun stepButton(label: String): TextView = text(label, 26, true, R.color.wb_primary).apply {
        gravity = Gravity.CENTER
        background = shape(R.color.wb_primary_container, 24f)
        isClickable = true
        isFocusable = true
    }

    private fun action(label: String, outlined: Boolean = false, full: Boolean = false, click: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        minHeight = dp(48)
        minWidth = dp(48)
        setPadding(dp(14), 0, dp(14), 0)
        setTextColor(color(if (outlined) R.color.wb_primary else R.color.wb_on_primary))
        background = if (outlined) shape(R.color.wb_surface, 24f, R.color.wb_outline) else shape(R.color.wb_primary, 24f)
        setOnClickListener { click() }
        if (full) layoutParams = lp(-1, dp(48))
    }

    /** 卡片内的轻量操作入口（如记录页「删除」）：文字样式按钮，仍保留 48dp 触控目标。 */
    private fun linkAction(label: String, colorRes: Int, click: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(color(colorRes))
        gravity = Gravity.CENTER
        minHeight = dp(48)
        minWidth = dp(48)
        setPadding(dp(14), 0, dp(14), 0)
        background = shape(R.color.wb_surface, 20f, R.color.wb_outline)
        setOnClickListener { click() }
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(color(R.color.wb_outline_variant))
        layoutParams = lp(-1, dp(1), start = 60)
    }

    private fun shape(fillRes: Int, radiusDp: Float, strokeRes: Int? = null): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color(fillRes))
        cornerRadius = dp(radiusDp).toFloat()
        strokeRes?.let { setStroke(dp(1), color(it)) }
    }

    private fun isCheckedInToday(date: String): Boolean = date == SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    private fun shortUid(uid: String): String = if (uid.length <= 16) uid else "${uid.take(8)}…${uid.takeLast(6)}"
    private fun accountDisplayName(item: JSONObject): String {
        val name = item.optString("nickname").takeIf { it.isNotBlank() }
            ?: "账号 ${shortUid(item.optString("uid"))}"
        val region = item.optString("region_label")
        return if (region.isBlank()) name else "$name · $region"
    }

    private fun formatTokens(value: Long): String = when {
        value >= 1_000_000 -> "%.1fM".format(value / 1_000_000.0)
        value >= 1_000 -> "${value / 1_000}K"
        else -> value.toString()
    }

    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()
    private fun color(res: Int) = getColor(res)
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun dp(value: Float) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun match() = FrameLayout.LayoutParams(-1, -1)
    private fun matchWidth() = ViewGroup.LayoutParams(-1, -2)
    private fun lp(width: Int, height: Int, start: Int = 0, top: Int = 0, end: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(width, height).apply { setMargins(dp(start), dp(top), dp(end), dp(bottom)) }
    private fun weighted(start: Int = 0) = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(start) }
    private fun top(value: Int) = lp(-1, -2, top = value)
    private fun bottom(value: Int) = lp(-1, -2, bottom = value)

    companion object {
        private const val REQ_AUTH = 1001
        /** 导出账号到用户选定位置（SAF 创建文档）。 */
        private const val REQ_EXPORT = 1002
        /** 优先级可调范围：0 = 不额外加权（权重 1.0）；20 已是权重 ×21，足够拉开账号间差距又不至于让滑块粒度失控。 */
        private const val PRIORITY_MIN = 0
        private const val PRIORITY_MAX = 20
        private var oauthHostRef: WeakReference<MainActivity>? = null

        /** 内置登录窗口关闭时回调：用户主动退出就不必再等这轮授权，避免几分钟后莫名弹出"登录等待超时"。 */
        fun notifyOAuthWindowClosed() {
            oauthHostRef?.get()?.onOAuthWindowClosed()
        }
    }
}
