package com.joy4fire.workbuddy2api.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.joy4fire.workbuddy2api.ApiHostService
import com.joy4fire.workbuddy2api.FormatKit
import com.joy4fire.workbuddy2api.NativeCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 界面状态与后端调用的唯一持有者 —— 所有页面都从这里取数据、发动作。
 *
 * 设计要点（与原纯 View 实现的关系）：
 *   原 MainActivity 用「render(page) 全量重建视图 + io 线程池 + main.post 回主线程」的模式。
 *   Compose 下等价物是「mutableStateOf 驱动重组」，因此这里把原先散在 Activity 里的
 *   数据缓存（accounts / modelCache / creditCache / checkinResults / events）集中成状态字段，
 *   业务调用仍然走 [NativeCore]，一行都没重写。
 *
 *   所有耗时调用都在 [scope]（IO 调度器）上执行，结果回写到 state 触发重组，
 *   因此每个动作都自带 loading/error 的落点，不需要页面各自管理。
 */
class AppState(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---------------------------------------------------------------- 基础状态

    /** 是否正在执行耗时操作（刷新额度、模型、签到等），用于按钮禁用与进度提示。 */
    var busy by mutableStateOf(false); private set

    /** 最近一次操作的结果提示（替代原 toast + events 双写）。 */
    var message by mutableStateOf<String?>(null); private set

    /** 操作日志（原 events，倒序展示，最多 50 条）。 */
    val events = mutableStateListOf<String>()

    var accounts by mutableStateOf<JSONArray>(JSONArray()); private set
    var models by mutableStateOf<JSONArray?>(null); private set
    var credits by mutableStateOf<JSONObject?>(null); private set
    var checkinResults by mutableStateOf<JSONArray?>(null); private set
    var usageSummary by mutableStateOf<JSONObject?>(null); private set
    var usageRecords by mutableStateOf<JSONArray>(JSONArray()); private set
    var usageTotal by mutableStateOf(0); private set
    var usageSeries by mutableStateOf<JSONArray>(JSONArray()); private set

    /** 上次成功拉取用量数据的时间戳，用于 [loadUsageIfStale] 判断是否需要重新查询。 */
    private var usageLoadedAt by mutableStateOf(0L)
    var apps by mutableStateOf<JSONArray>(JSONArray()); private set
    var settings by mutableStateOf<JSONObject>(JSONObject()); private set

    /** 当前选中的账号（成长中心等按账号操作的页面用）。 */
    var selectedAccount by mutableStateOf<String?>(null); private set

    /**
     * 网关服务是否运行中。
     *
     * 为什么放在这里而不是页面里直接读 `ApiHostService.running`：
     * 那是普通静态变量，读取不会建立 Compose 的订阅关系 ——
     * 点「启动服务」后即便服务已就绪，界面也不会重组，用户看到按钮一直停在
     * 「启动服务」，误以为"点了半天没反应"。放进 mutableStateOf 后由广播驱动重组。
     */
    var serviceRunning by mutableStateOf(ApiHostService.running); private set

    /** 服务状态变更时的附加说明（来自广播 message，可选）。 */
    var serviceMessage by mutableStateOf<String?>(null); private set

    /** 由宿主 Activity 在收到 ACTION_STATE 广播时调用。 */
    /**
     * 由宿主 Activity 在收到 ACTION_STATE 广播时调用。
     *
     * 必须同时清除 [startingUp]：否则「启动 → 停止 → 再启动」流程里，
     * 第一次启动把 startingUp 置 true 后，即便收到 running 广播也不会复位，
     * 概览页按钮的 `enabled = !startingUp` 就永久为 false，表现为
     * 「反复启停后按钮全变灰、再也停不掉服务」。
     */
    fun updateServiceState(running: Boolean, message: String? = null) {
        serviceRunning = running
        startingUp = false
        if (!message.isNullOrBlank()) serviceMessage = message
        bump()
    }

    /**
     * 请求重构信号：Compose 的 mutableStateOf 对 JSONArray/JSONObject 内部修改不敏感
     * （它们是可变对象，引用没变就不会触发重组）。凡是就地改过 JSON 数据后，
     * 必须 bump 一次 [revision] 让界面知道要重读。
     */
    var revision by mutableStateOf(0); private set

    fun dispose() = scope.cancel()

    // ---------------------------------------------------------------- 内部工具

    private fun bump() { revision++ }

    private fun record(text: String) {
        events.add(0, text)
        while (events.size > 50) events.removeAt(events.size - 1)
        message = text
    }

    /**
     * 统一的异步任务入口：负责 busy 置位、异常捕获、日志与状态落点。
     *
     * 为什么把 try/catch 收在这里：原实现每个动作都写一遍
     * runCatching + main.post + showError，十几处重复且漏一处就会崩界面。
     */
    private fun task(name: String, block: () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            val result = runCatching { block() }
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { record("$name：$it") },
                    onFailure = { record("$name 失败：${it.message ?: it.javaClass.simpleName}") }
                )
                busy = false
                bump()
            }
        }
    }

    fun clearMessage() { message = null }

    // ---------------------------------------------------------------- 加载

    /** 刷新所有页面依赖的数据（进入应用时调一次）。 */
    fun refreshAll() {
        loadAccounts()
        loadSettings()
        loadApps()
        loadUsage()
        loadModelsFromCache()
    }

    fun loadAccounts() {
        scope.launch {
            val list = runCatching { NativeCore.listAccounts(context) }.getOrDefault(JSONArray())
            val summary = runCatching { NativeCore.creditsSummary(context) }.getOrNull()
            withContext(Dispatchers.Main) {
                accounts = list
                credits = summary
                // JSONArray 不是 Collection，需手工遍历（它没有 none / firstOrNull）。
                val parsed = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                val current = selectedAccount
                if (current == null || parsed.none { it.optString("account_key", it.optString("uid")) == current }) {
                    val first = parsed.firstOrNull()
                    selectedAccount = first?.optString("account_key")?.takeIf { it.isNotBlank() }
                        ?: first?.optString("uid")
                }
                bump()
            }
        }
    }

    fun selectAccount(key: String) {
        selectedAccount = key
    }

    fun loadSettings() {
        scope.launch {
            val s = runCatching { NativeCore.settings(context) }.getOrDefault(JSONObject())
            withContext(Dispatchers.Main) { settings = s; bump() }
        }
    }

    fun loadApps() {
        scope.launch {
            val list = runCatching { NativeCore.listApps(context) }.getOrDefault(JSONArray())
            withContext(Dispatchers.Main) { apps = list; bump() }
        }
    }

    /** 模型目录：先用缓存填充，避免每次进来都是空页。 */
    fun loadModelsFromCache() {
        scope.launch {
            val cached = runCatching { NativeCore.modelsCached(context) }.getOrNull()
                ?: runCatching { NativeCore.store(context).getModelCache()?.optJSONArray("models") }.getOrNull()
            withContext(Dispatchers.Main) {
                if (cached != null && cached.length() > 0) models = cached
                bump()
            }
        }
    }

    fun loadUsage() {
        scope.launch {
            val summary = runCatching { NativeCore.usageSummary(context) }.getOrNull()
            val recent = runCatching { NativeCore.usageRecent(context, pageSize = 100) }.getOrNull()
            val series = runCatching { NativeCore.usageTimeseries(context) }.getOrDefault(JSONArray())
            withContext(Dispatchers.Main) {
                usageSummary = summary
                usageRecords = recent?.optJSONArray("records") ?: JSONArray()
                usageTotal = recent?.optInt("total") ?: 0
                usageSeries = series
                usageLoadedAt = System.currentTimeMillis()
                bump()
            }
        }
    }

    /**
     * 进入记录页时调用：只在数据「过期」时才重新拉取。
     *
     * 为什么需要它：此前 loadUsage 只在 refreshAll() 与删除操作后触发，
     * 切到记录页不会重新查询 —— 新产生的调用记录必须手动点刷新才看得到。
     *
     * 为什么不是每次进入都拉：记录页在 Tab 间来回切换很频繁，
     * 每次都查一次数据库（三条 SQL 且可能扫全表）没有必要。
     * 用 2 秒的短窗口：既能让用户立刻看到刚产生的记录，
     * 又能在快速来回切 Tab 时避免重复查询。
     */
    fun loadUsageIfStale(maxAgeMs: Long = 2_000) {
        if (System.currentTimeMillis() - usageLoadedAt < maxAgeMs) return
        loadUsage()
    }

    // ---------------------------------------------------------------- 服务

    /** 是否处于「已请求启动、尚未就绪」的中间态。 */
    var startingUp by mutableStateOf(false); private set

    /**
     * 启停网关服务。
     *
     * 关于"启动很久没反应"：服务启动时要做账号校验、模型预热等网络动作，
     * 期间是「已发出启动请求，但还没就绪」的中间态。原实现只在日志里输出，
     * 界面无从体现，用户会以为按钮失效。这里在请求发出后立刻把状态置为
     * 「启动中」，并由宿主在收到广播后切成最终状态。
     */
    fun toggleServer(activity: Activity?) {
        val target = activity ?: context as? Activity ?: return
        if (serviceRunning) {
            target.startService(Intent(target, ApiHostService::class.java).setAction(ApiHostService.ACTION_STOP))
            record("已请求停止本地 API")
        } else {
            if (NativeCore.loadAccount(context) == null) {
                message = "请先导入或登录 WorkBuddy 账号"
                return
            }
            startingUp = true
            val intent = Intent(target, ApiHostService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 26) target.startForegroundService(intent) else target.startService(intent)
            record("正在启动本地 API（需校验账号并预热模型，请稍候）")
            // 超时兜底：若 20 秒后仍未收到广播，说明启动失败，清掉"启动中"避免卡住。
            scope.launch {
                kotlinx.coroutines.delay(20_000)
                withContext(Dispatchers.Main) {
                    if (startingUp && !ApiHostService.running) {
                        startingUp = false
                        message = "启动超时：请检查通知权限与账号状态后重试"
                    }
                }
            }
        }
        bump()
    }

    /** 是否处于「已请求启动、尚未就绪」的中间态。 */

    // ---------------------------------------------------------------- 账号

    fun setAccountEnabled(accountKey: String, enabled: Boolean) = task(if (enabled) "启用账号" else "停用账号") {
        NativeCore.setAccountEnabled(context, accountKey, enabled)
        loadAccounts()
        if (enabled) "已启用" else "已停用"
    }

    fun setAccountPriority(accountKey: String, priority: Int) = task("设置优先级") {
        if (NativeCore.setAccountPriority(context, accountKey, priority)) {
            loadAccounts()
            "优先级已设为 $priority（选号权重 ×${priority + 1}）"
        } else "设置失败：账号已不存在"
    }

    fun deleteAccount(accountKey: String, label: String) = task("删除账号") {
        if (NativeCore.deleteAccount(context, accountKey)) {
            loadAccounts()
            "已删除 $label"
        } else "删除失败：账号已不存在"
    }

    fun refreshCredits() = task("刷新全部额度") {
        val response = NativeCore.refreshAllCredits(context)
        val summary = response.optJSONObject("summary") ?: JSONObject()
        val results = response.optJSONArray("results") ?: JSONArray()
        val failed = (0 until results.length()).count { !results.optJSONObject(it).optBoolean("ok") }
        credits = summary
        bump()
        "已刷新 ${results.length() - failed}/${results.length()} 个账号，合计剩余 " +
            "${FormatKit.credits(summary.optDouble("remain"))} / ${FormatKit.credits(summary.optDouble("total"))} 积分" +
            if (failed > 0) "，$failed 个失败" else ""
    }

    fun checkInAll() = task("全部账号签到") {
        val response = NativeCore.checkInAll(context)
        val results = response.optJSONArray("results") ?: JSONArray()
        checkinResults = results
        bump()
        val success = (0 until results.length()).count { results.optJSONObject(it).optBoolean("ok") }
        val failed = (0 until results.length()).count {
            val item = results.optJSONObject(it)
            !item.optBoolean("ok") && !item.optBoolean("skipped")
        }
        val skipped = results.length() - success - failed
        "签到完成：成功/已签 $success，失败 $failed" + if (skipped > 0) "，跳过 $skipped" else ""
    }

    fun refreshModels() = task("刷新模型") {
        val response = NativeCore.refreshAllModels(context)
        val data = response.optJSONArray("models") ?: JSONArray()
        val failures = response.optJSONArray("failures") ?: JSONArray()
        models = data
        bump()
        if (failures.length() == 0) "已加载 ${data.length()} 个模型（国内版 / 国际版均成功）"
        else "已加载 ${data.length()} 个模型；" + (0 until failures.length()).joinToString("；") {
            val item = failures.optJSONObject(it)
            "${item.optString("region_label")}：${item.optString("message")}"
        }
    }

    // ---------------------------------------------------------------- 记录 / 应用 / 设置

    fun deleteUsage(id: Long) = task("删除记录 #$id") {
        loadUsage()
        if (NativeCore.deleteUsage(context, id)) "已删除记录 #$id" else "这条记录已不存在"
    }

    fun clearUsage(total: Int) = task("清空记录") {
        val removed = NativeCore.clearUsage(context)
        loadUsage()
        if (removed > 0) "已清空 $removed 条记录（原有 $total 条）" else "没有可清空的记录"
    }

    fun createApp(name: String, note: String, region: com.joy4fire.workbuddy2api.AccountRegion, customKey: String?): JSONObject? {
        var created: JSONObject? = null
        task(if (created == null) "创建 API Key" else "保存 API Key") {
            created = NativeCore.createApp(context, name, note, region, customKey)
            loadApps()
            "已创建 ${created?.optString("name")}"
        }
        return created
    }

    fun updateApp(id: Long, name: String, note: String, region: com.joy4fire.workbuddy2api.AccountRegion, replacement: String?): JSONObject? {
        var saved: JSONObject? = null
        task("保存 API Key") {
            saved = NativeCore.updateApp(context, id, name, note, region, replacement)
            loadApps()
            "已保存 ${saved?.optString("name")}"
        }
        return saved
    }

    fun appKey(id: Long): String? = runCatching { NativeCore.getAppKey(context, id) }.getOrNull()

    fun toggleApp(id: Long, name: String, enabled: Boolean) = task(if (enabled) "启用应用" else "停用应用") {
        if (NativeCore.setAppEnabled(context, id, enabled)) {
            loadApps()
            if (enabled) "已启用 $name" else "已停用 $name"
        } else "状态更新失败，请重试"
    }

    fun deleteApp(id: Long, name: String) = task("删除应用") {
        if (NativeCore.deleteApp(context, id)) {
            loadApps()
            "已删除 $name"
        } else "删除失败，请重试"
    }

    fun saveSetting(key: String, value: String) {
        NativeCore.saveSettings(context, JSONObject().put(key, value))
        loadSettings()
    }

    fun clearWebViewCache() = task("清理 WebView 缓存") {
        NativeCore.clearWebViewCache(context)
        "已清理 WebView 缓存"
    }

    fun trimUsage() = task("截断超长记录") {
        val count = NativeCore.trimUsage(context)
        NativeCore.vacuum(context)
        loadUsage()
        "已处理 $count 条记录并回收空间"
    }

    fun cleanupUsage(days: Int) = task("清理过期记录") {
        val count = NativeCore.cleanupUsage(context, days)
        NativeCore.vacuum(context)
        loadUsage()
        "已清理 $count 条 $days 天前的记录并回收空间"
    }

    fun storageInfo(): JSONObject = runCatching { NativeCore.storageInfo(context) }.getOrDefault(JSONObject())

    fun storageBreakdown(): JSONObject = runCatching { NativeCore.storageBreakdown(context) }.getOrDefault(JSONObject())

    fun exportAccount(accountKey: String): String? = runCatching { NativeCore.exportAccount(context, accountKey) }.getOrNull()

    fun exportAllAccounts(): String? = runCatching { NativeCore.exportAllAccounts(context) }.getOrNull()

    /** 导入账号（SAF 选中的文件内容已读成字符串）。 */
    fun importAuth(raw: String) {
        task("导入账号") {
            runCatching { JSONObject(raw) }.fold(
                onSuccess = {
                    val account = NativeCore.saveAccount(context, it)
                    loadAccounts()
                    "账号 ${account.nickname} (${account.uid}) 已导入"
                },
                onFailure = {
                    val result = NativeCore.importAccounts(context, JSONArray(raw))
                    val imported = result.optJSONArray("imported") ?: JSONArray()
                    val rejected = result.optJSONArray("rejected") ?: JSONArray()
                    if (imported.length() == 0) {
                        throw IllegalStateException(rejected.optJSONObject(0)?.optString("error") ?: "认证文件为空")
                    }
                    loadAccounts()
                    "已导入 ${imported.length()} 个账号" + if (rejected.length() > 0) "，${rejected.length()} 个失败" else ""
                }
            )
        }
    }

    fun showMessage(text: String) { record(text) }

    /** 请求检查器（出网取证）相关。 */
    fun inspectorEnabled(): Boolean = com.joy4fire.workbuddy2api.RequestInspector.isEnabled(context)
    fun inspectorCount(): Int = com.joy4fire.workbuddy2api.RequestInspector.count(context)
    fun inspectorEntries(): JSONArray = com.joy4fire.workbuddy2api.RequestInspector.entries(context)
    fun inspectorExport(): String = com.joy4fire.workbuddy2api.RequestInspector.exportText(context)
    fun setInspectorEnabled(enabled: Boolean) {
        com.joy4fire.workbuddy2api.RequestInspector.setEnabled(context, enabled)
        bump()
    }
    fun clearInspector() {
        com.joy4fire.workbuddy2api.RequestInspector.clear(context)
        record("已清空取证记录")
    }

    /** 悬浮球（保活加固项）状态与开关。 */
    fun floatingEnabled(): Boolean = com.joy4fire.workbuddy2api.FloatingWindowKeeper.isEnabled(context)
    fun floatingPermitted(): Boolean = com.joy4fire.workbuddy2api.FloatingWindowKeeper.hasPermission(context)
    fun setFloating(enabled: Boolean, activity: Activity?) {
        val target = activity ?: context as? Activity
        val keeper = com.joy4fire.workbuddy2api.FloatingWindowKeeper
        if (enabled && !keeper.hasPermission(context)) {
            keeper.setEnabled(context, true)
            keeper.requestPermission(context)
            record("需授予「显示在其他应用上层」权限")
        } else {
            keeper.setEnabled(context, enabled)
            if (enabled) {
                if (target != null) keeper.show(target, ApiHostService.PORT)
                record("悬浮球已开启")
            } else {
                keeper.hide(context)
                record("悬浮球已关闭")
            }
        }
        bump()
    }
}
