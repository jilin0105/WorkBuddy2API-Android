package com.joy4fire.workbuddy2api

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 成长中心统一门面 —— UI 只跟这个对象交互，不直接碰 [GrowthApi] / [GrowthTasks] / [GrowthRunner]。
 *
 * 为什么需要门面：分层是给实现看的，UI 不该关心「这个动作走 web 域还是插件域」
 * 「这条链要不要先 accept」。门面把 [GrowthApi] 的构造（需要 Context）
 * 收在一处，UI 侧只传 accountKey。
 */
object GrowthFacade {

    private fun api(context: Context) = GrowthApi(context)

    private fun bundle(context: Context): Bundle {
        val api = api(context)
        val tasks = GrowthTasks(api)
        return Bundle(api, tasks, GrowthEvents(api), GrowthRunner(api, tasks, GrowthEvents(api)), GrowthCenter(api, tasks))
    }

    private class Bundle(
        val api: GrowthApi,
        val tasks: GrowthTasks,
        val events: GrowthEvents,
        val runner: GrowthRunner,
        val center: GrowthCenter
    )

    /** 所有可执行任务码（UI 渲染清单用）。 */
    fun taskCodes(context: Context): List<String> = bundle(context).runner.taskCodes

    fun describe(context: Context, code: String): String = bundle(context).runner.describe(code)

    /** 拉任务列表（含进度与状态）。 */
    fun list(context: Context, accountKey: String): List<GrowthTasks.Task> = bundle(context).tasks.list(accountKey)

    /** 一键做完所有任务（UI 主按钮）。 */
    fun runAll(context: Context, accountKey: String, reporter: GrowthTasks.Reporter, only: Set<String>? = null): JSONArray =
        bundle(context).runner.runAll(accountKey, reporter, only)

    /** 执行单个任务码。 */
    fun runOne(context: Context, accountKey: String, code: String, reporter: GrowthTasks.Reporter? = null): JSONObject =
        bundle(context).runner.run(accountKey, code, reporter)

    /** 领取单个任务奖励。 */
    fun claim(context: Context, accountKey: String, code: String): Pair<Long, Long> =
        bundle(context).tasks.claim(accountKey, code)

    /** 状态汇总：今天还能白拿什么。 */
    fun summary(context: Context, accountKey: String): JSONObject = bundle(context).center.summary(accountKey)

    /** 一键领每日福利（补签/礼包/补偿/连登/抽奖）。 */
    fun claimDaily(context: Context, accountKey: String, reporter: GrowthTasks.Reporter? = null): JSONObject =
        bundle(context).center.claimDaily(accountKey, reporter)

    /** 开学季一键。 */
    fun runSchool(context: Context, accountKey: String, reporter: GrowthTasks.Reporter? = null): JSONObject =
        bundle(context).center.runSchool(accountKey, reporter)

    /** 猫猫一键（领养/到站领奖/重新派出）。 */
    fun runBuddy(context: Context, accountKey: String, reporter: GrowthTasks.Reporter? = null): JSONObject =
        bundle(context).center.runBuddy(accountKey, reporter)

    /** 成长中心抽奖。 */
    fun lottery(context: Context, accountKey: String, reporter: GrowthTasks.Reporter? = null): JSONObject =
        bundle(context).center.lotteryDraw(accountKey, reporter)

    /** 开学季状态。 */
    fun schoolStatus(context: Context, accountKey: String): JSONObject = bundle(context).center.schoolStatus(accountKey)

    /** Buddy 信息（null 表示还没领养）。 */
    fun buddy(context: Context, accountKey: String): JSONObject? = bundle(context).center.buddy(accountKey)

    // ---------------------------------------------------------------- 提示词配置

    /** 读取提示词管线配置（缺省值来自 [PromptInjection.defaultSettings]）。 */
    fun promptSettings(context: Context): JSONObject {
        val stored = NativeCore.settings(context)
        val defaults = PromptInjection.defaultSettings()
        val result = JSONObject()
        defaults.keys().forEach { key ->
            result.put(key, if (stored.has(key)) stored.opt(key) else defaults.opt(key))
        }
        return result
    }

    /** 保存提示词管线配置（只写这四个键，不覆盖其它设置）。 */
    fun savePromptSettings(context: Context, values: JSONObject) {
        val payload = JSONObject()
        PromptInjection.CONFIG_KEYS.forEach { key -> if (values.has(key)) payload.put(key, values.opt(key)) }
        if (payload.length() > 0) NativeCore.saveSettings(context, payload)
    }

    /** 让 UI 能直接预览「当前设置会把这段文本清洗成什么」。 */
    fun previewSanitize(text: String): String = PromptInjection.sanitizeText(text)
}
