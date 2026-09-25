package com.joy4fire.workbuddy2api

import org.json.JSONArray
import org.json.JSONObject

/**
 * 成长中心衍生模块 —— 移植自 WorkBuddy 1.2.7（a.n2.B/P/V/W/X + a.r2 的 c/j/k/m/n）。
 *
 * 覆盖四块 1.2.7 里独立成篇的能力：
 *   1) 每日福利：补签 / 新手礼包 / 活动补偿 / 连登档位兑换 / 成长中心抽奖；
 *   2) 开学季（school）：任务接受、达标领奖、转盘抽奖；
 *   3) Buddy 猫猫：领养、旅行派出与到站领奖；
 *   4) 状态汇总：把上面所有可领项聚合成一份「有什么可领」的清单（UI 首页直接渲染）。
 *
 * 与 1.2.7 一致的一点很重要：几乎每个动作都设计成【可重复执行且幂等安全】——
 * 已领过的会返回「已领取」而不是报错，这样「一键领取」按钮可以无脑连点。
 */
class GrowthCenter(private val api: GrowthApi, private val tasks: GrowthTasks) {

    // ================================================================ 1) 每日福利

    /**
     * 补签（1.2.7 `a.n2.B`）：查昨天是否漏签，有补签卡就自动补。
     *
     * 为什么先查 heatmap 再查 streak：heatmap 的 cells 里能给到「昨天分数为 0」
     * 这个事实，而 streak 只给当前连登状态。先确认漏签再消耗补签卡，
     * 避免无谓消耗（补签卡有上限，用错了就真没得补）。
     */
    fun makeup(accountKey: String): Boolean {
        val yesterday = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getDefault() }
            .format(java.util.Date(System.currentTimeMillis() - 86_400_000L))
        val heatmap = runCatching { api.plugin(accountKey, "/activity/growth/heatmap") }.getOrNull() ?: return false
        val cells = heatmap.optJSONArray("cells") ?: return false
        var missed = false
        for (i in 0 until cells.length()) {
            val cell = cells.optJSONObject(i) ?: continue
            val date = cell.optString("date")
            if (date.length >= 10 && date.take(10) == yesterday && cell.optInt("score", 0) == 0) { missed = true; break }
        }
        if (!missed) return false
        val streak = runCatching { api.plugin(accountKey, "/activity/growth/streak") }.getOrNull() ?: return false
        val cards = streak.optJSONObject("makeup_cards")?.optInt("balance", 0) ?: 0
        if (cards <= 0) return false
        return runCatching {
            api.plugin(accountKey, "/activity/growth/makeup-cards/use", "POST",
                JSONObject().put("target_date", yesterday))
            true
        }.getOrDefault(false)
    }

    /**
     * 连登档位兑换（1.2.7 `a.r2.k` 前半）。
     *
     * 档位是 7d/14d/28d，状态为「可领」（非 lock / 未解锁 / 空）时才兑换。
     * 每个档位用独立 client_token —— 上游用它做幂等去重，
     * 复用同一个 token 会让第二次兑换被静默忽略。
     */
    fun redeemTiers(accountKey: String): List<String> {
        val streak = runCatching { api.plugin(accountKey, "/activity/growth/streak") }.getOrNull() ?: return emptyList()
        val status = streak.optJSONObject("redemption_status") ?: return emptyList()
        val redeemed = ArrayList<String>()
        for (tier in TIERS) {
            val state = status.optString("tier_${tier}_status")
            if (!claimableState(state)) continue
            runCatching {
                api.plugin(accountKey, "/activity/growth/redeem", "POST",
                    JSONObject().put("tier", tier).put("client_token", api.clientToken()))
            }.onSuccess { redeemed.add(tier) }
        }
        return redeemed
    }

    /** 成长中心抽奖次数（1.2.7 `a.r2.g`）。 */
    fun lotteryChances(accountKey: String): Int =
        runCatching { api.plugin(accountKey, "/activity/growth/lottery/summary").optInt("chances", 0) }
            .getOrDefault(0)

    /**
     * 成长中心抽奖（1.2.7 `a.r2.k` 后半）：循环抽到次数耗尽，硬上限 20 次。
     *
     * 上限 20 是 1.2.7 的原值，同时出现在 a.n2.K 与 a.r2.k 两处——
     * 保留它是为了防止「chances 字段异常偏大导致死循环」。
     */
    fun lotteryDraw(accountKey: String, reporter: GrowthTasks.Reporter? = null): JSONObject {
        var done = 0
        var chances = lotteryChances(accountKey)
        while (chances > 0 && done < MAX_DRAW) {
            val outcome = runCatching {
                api.plugin(accountKey, "/activity/growth/lottery/draw", "POST",
                    JSONObject().put("client_token", api.clientToken()))
            }.getOrElse { break }
            done++
            reporter?.report("抽奖 ${outcome.optString("prize_code", "已抽")}")
            api.sleep(1000)
            chances = lotteryChances(accountKey)
        }
        return JSONObject().put("draws", done)
    }

    /** 一键领每日福利（1.2.7 `a.r2.k`）：补签 → 礼包 → 补偿 → 连登 → 抽奖。 */
    fun claimDaily(accountKey: String, reporter: GrowthTasks.Reporter? = null): JSONObject {
        val result = JSONObject().put("makeup", false).put("gift", 0L).put("compensation", 0L)
            .put("tiers", JSONArray()).put("draws", 0)
        reporter?.report("补签 / 礼包 / 补偿…")
        result.put("makeup", runCatching { makeup(accountKey) }.getOrDefault(false))
        result.put("gift", runCatching { tasks.claimGift(accountKey) }.getOrDefault(0L))
        result.put("compensation", runCatching { tasks.claimCompensation(accountKey) }.getOrDefault(0L))
        val tiers = runCatching { redeemTiers(accountKey) }.getOrDefault(emptyList())
        result.put("tiers", JSONArray(tiers))
        reporter?.report("成长中心抽奖…")
        result.put("draws", runCatching { lotteryDraw(accountKey, reporter).optInt("draws") }.getOrDefault(0))
        return result
    }

    // ================================================================ 2) 开学季

    /** 开学季状态（1.2.7 `a.n2.V` / `W`）：in_period + 任务进度 + 待领 + 转盘次数。 */
    fun schoolStatus(accountKey: String): JSONObject {
        val data = api.plugin(accountKey, "/portal/activity/school/tasks")
        val tasksArray = data.optJSONArray("tasks") ?: JSONArray()
        val parsed = JSONArray()
        val claimable = JSONArray()
        for (i in 0 until tasksArray.length()) {
            val item = tasksArray.optJSONObject(i) ?: continue
            val code = item.optString("task_code")
            val status = item.optString("status")
            val progress = item.optInt("progress", 0)
            val target = item.optInt("target_count", 0)
            val done = target > 0 && progress >= target
            parsed.put(JSONObject().put("code", code).put("status", status)
                .put("progress", progress).put("target", target).put("done", done))
            if (done && status != "claimed") claimable.put(code)
        }
        return JSONObject()
            .put("in_period", data.optBoolean("in_period", false))
            .put("tasks", parsed)
            .put("claimable", claimable)
            .put("chances", runCatching { schoolChances(accountKey) }.getOrDefault(0))
    }

    private fun schoolChances(accountKey: String): Int =
        api.plugin(accountKey, "/portal/activity/school/config")
            .optJSONObject("chance")?.optInt("balance", 0) ?: 0

    /**
     * 开学季一键：领取所有达标任务 + 转完所有转盘次数（1.2.7 `a.n2.P`）。
     */
    fun runSchool(accountKey: String, reporter: GrowthTasks.Reporter? = null): JSONObject {
        val status = schoolStatus(accountKey)
        if (!status.optBoolean("in_period")) {
            return JSONObject().put("skipped", true).put("message", "活动不在期")
        }
        val claimed = JSONArray()
        var gained = 0L
        val claimable = status.optJSONArray("claimable") ?: JSONArray()
        for (i in 0 until claimable.length()) {
            val code = claimable.optString(i)
            reporter?.report("领取 $code…")
            runCatching { tasks.claimSchoolTask(accountKey, code) }
                .onSuccess { claimed.put(JSONObject().put("code", code)) }
                .onFailure { reporter?.report("$code 领取跳过：${it.message}") }
        }
        // 转盘：每次 draw_uuid 必须全新
        var draws = 0
        var chances = runCatching { schoolChances(accountKey) }.getOrDefault(0)
        var credit = 0L
        while (chances > 0 && draws < MAX_DRAW) {
            reporter?.report("开学季转盘…")
            val outcome = runCatching {
                api.plugin(accountKey, "/portal/activity/school/wheel/draw", "POST",
                    JSONObject().put("draw_uuid", api.clientToken()))
            }.getOrNull() ?: break
            credit += outcome.optLong("credit_amount", 0)
            claimed.put(JSONObject().put("code", "(抽奖)").put("prize", outcome.optString("prize_code")))
            draws++
            api.sleep(2000)
            chances = runCatching { schoolChances(accountKey) }.getOrDefault(0)
        }
        return JSONObject().put("claimed", claimed).put("draws", draws).put("gained", gained + credit)
    }

    // ================================================================ 3) Buddy / 猫猫

    /** Buddy 信息（1.2.7 `a.r2.b`）。 */
    fun buddy(accountKey: String): JSONObject? =
        runCatching { api.plugin(accountKey, "/activity/growth/buddy/info").optJSONObject("buddy") }
            .getOrNull()?.takeIf { it.length() > 0 }

    /**
     * 领养 Buddy（1.2.7 `a.r2.a`）。
     *
     * 两步且第二步允许失败：先上报活跃（领养门槛），再同意协议、最后领养。
     * 门槛未过时会抛 "first_buddy task not completed yet"，这不是错误而是
     * 「明天再来」的信号，故单独识别并归一成正常返回值。
     */
    fun adopt(accountKey: String): JSONObject {
        if (buddy(accountKey) != null) return JSONObject().put("adopted", false).put("reason", "已有猫")
        val conversationId = eventsId("wb2api-adopt")
        runCatching {
            api.reportMiniProgram(accountKey, listOf(chatEvent(conversationId)))
        }
        api.sleep(1050)
        runCatching { api.plugin(accountKey, "/activity/growth/buddy/agreement", "POST", JSONObject().put("agree", true)) }
        return try {
            api.plugin(accountKey, "/activity/growth/buddy/first", "POST", JSONObject())
            JSONObject().put("adopted", true)
        } catch (e: Exception) {
            if (e.message?.contains("first_buddy task not completed yet", ignoreCase = true) == true) {
                JSONObject().put("adopted", false).put("reason", "领养门槛未达标（需先完成对话活跃上报）")
            } else throw e
        }
    }

    /** 旅行状态（1.2.7 `a.r2.n`）。 */
    fun travelStatus(accountKey: String): JSONObject {
        val data = api.plugin(accountKey, "/activity/growth/buddy/travel/status")
        return JSONObject()
            .put("state", data.optString("state", "idle"))
            .put("daily_limit_reached", data.optBoolean("daily_limit_reached"))
            .put("record_id", data.optLong("record_id"))
            .put("reward_credit", data.optLong("reward_credit"))
    }

    /**
     * 猫猫一键（1.2.7 `a.r2.m`）：领养 → 到站领奖 → 重新派出。
     *
     * 顺序不能反：必须先领到站奖励，再派出新的一段，否则当天的到站奖励会丢失。
     * location_id 固定 1（1.2.7 原值，只有一个可去地点）。
     */
    fun runBuddy(accountKey: String, reporter: GrowthTasks.Reporter? = null): JSONObject {
        val result = JSONObject()
        reporter?.report("领养 / 旅行…")
        runCatching { adopt(accountKey) }.onFailure { reporter?.report("领养跳过：${it.message}") }
        var travel = travelStatus(accountKey)
        if (travel.optString("state") == "arrived" && travel.optLong("record_id") > 0) {
            val recordId = travel.optLong("record_id")
            runCatching {
                val data = api.plugin(accountKey, "/activity/growth/buddy/travel/claim", "POST",
                    JSONObject().put("record_id", recordId))
                result.put("claimed", data.optLong("reward_credit", 0))
            }
            travel = travelStatus(accountKey)
        }
        if (!travel.optBoolean("daily_limit_reached") && travel.optString("state") != "traveling") {
            runCatching {
                api.plugin(accountKey, "/activity/growth/buddy/travel/depart", "POST", JSONObject().put("location_id", 1))
                result.put("departed", true)
            }
        }
        return result
    }

    // ================================================================ 4) 状态汇总

    /**
     * 汇总所有可领项（1.2.7 `a.r2.j`）。
     *
     * 这个方法的设计目标是「UI 一眼看到今天还能白拿什么」，因此每个子项都
     * 单独 try/catch：任何一项接口挂了都不影响其它项展示（1.2.7 用 errors 数组收集）。
     */
    fun summary(accountKey: String): JSONObject {
        val account = NativeCore.loadAccount(api.appContextCompat(), accountKey)
        val claimable = JSONArray()
        val errors = JSONArray()
        val result = JSONObject()
            .put("account_key", accountKey)
            .put("nickname", account?.nickname ?: accountKey)
            .put("region", account?.region?.id ?: "domestic")
            .put("region_label", account?.region?.label ?: "国内版")

        // 国际版无成长体系，直接返回（1.2.7 `a.r2.j` 同样短路）
        if (account?.region == AccountRegion.INTERNATIONAL) {
            claimable.put("国际版无成长体系（只有每日额度与试用加油包）")
            return result.put("international", true).put("claimable", claimable)
                .put("claimable_count", claimable.length()).put("errors", errors)
        }

        // 连登 / 补签卡
        var streakDays = 0
        var monthDays = 0
        var makeupCards = 0
        runCatching {
            val streak = api.plugin(accountKey, "/activity/growth/streak")
            streak.optJSONObject("streak")?.let {
                streakDays = it.optInt("days", 0); monthDays = it.optInt("month_total_days", 0)
            }
            makeupCards = streak.optJSONObject("makeup_cards")?.optInt("balance", 0) ?: 0
            val status = streak.optJSONObject("redemption_status")
            val tiers = status?.optJSONArray("tiers") ?: JSONArray()
            val ready = JSONArray()
            for (i in 0 until tiers.length()) {
                val tier = tiers.optJSONObject(i) ?: continue
                val key = tier.optString("tier")
                if (claimableState(status?.optString("tier_${key}_status"))) ready.put(key)
            }
            if (ready.length() > 0) claimable.put("连登档位兑换 ${(0 until ready.length()).joinToString("、") { ready.optString(it) }}")
            if (makeupCards > 0) claimable.put("补签卡 $makeupCards 张（有漏签时自动补）")
        }.onFailure { errors.put("连登：${it.message}") }
        result.put("streak_days", streakDays).put("month_total_days", monthDays).put("makeup_cards", makeupCards)

        // 抽奖
        runCatching { lotteryChances(accountKey) }
            .onSuccess { if (it > 0) { result.put("lottery_chances", it); claimable.put("成长中心抽奖 $it 次") } }
            .onFailure { errors.put("抽奖：${it.message}") }

        // Buddy
        runCatching { buddy(accountKey) }
            .onSuccess { result.put("buddy", it ?: JSONObject.NULL); if (it == null) claimable.put("领养 Buddy（+300 分，需先有对话活跃上报）") }
            .onFailure { errors.put("猫猫：${it.message}") }

        // 旅行
        runCatching { travelStatus(accountKey) }
            .onSuccess { travel ->
                result.put("travel", travel)
                if (travel.optString("state") == "arrived" && travel.optLong("record_id") > 0) {
                    claimable.put("旅行到站奖励 ${travel.optLong("reward_credit")} 分")
                } else if (!travel.optBoolean("daily_limit_reached") && travel.optString("state") != "traveling") {
                    claimable.put("今日旅行未派出（派出后可领到站奖励）")
                }
            }
            .onFailure { errors.put("旅行：${it.message}") }

        // 开学季
        runCatching { schoolStatus(accountKey) }
            .onSuccess { school ->
                result.put("school_in_period", school.optBoolean("in_period"))
                val ready = school.optJSONArray("claimable")?.length() ?: 0
                val chances = school.optInt("chances", 0)
                result.put("school_claimable", ready).put("school_chances", chances)
                if (school.optBoolean("in_period")) {
                    if (ready > 0) claimable.put("开学季任务 $ready 项已达标待领")
                    if (chances > 0) claimable.put("开学季转盘 $chances 次")
                }
            }
            .onFailure { errors.put("开学季：${it.message}") }

        // 成长任务
        runCatching { tasks.list(accountKey) }
            .onSuccess { list ->
                val ready = list.count { it.claimable }
                result.put("growth_total", list.size).put("growth_claimable", ready)
                    .put("growth_claimed", list.count { it.claimed })
                if (ready > 0) claimable.put("成长任务奖励 $ready 项")
            }
            .onFailure { errors.put("成长任务：${it.message}") }

        claimable.put("新手礼包 / 活动补偿（每次可尝试，已领过会自动跳过）")
        return result.put("claimable", claimable).put("claimable_count", claimable.length()).put("errors", errors)
    }

    // ================================================================ 工具

    /** 「可领」状态判定：空 / lock / 未解锁 都视为不可领（1.2.7 `a.r2.f`）。 */
    private fun claimableState(state: String?): Boolean {
        if (state.isNullOrBlank()) return false
        if (state.contains("lock", ignoreCase = true)) return false
        if (state.contains("未解锁")) return false
        return true
    }

    private fun eventsId(prefix: String) = "$prefix-${System.currentTimeMillis()}"

    private fun chatEvent(conversationId: String): JSONObject = api.event("chat_request_send",
        "timestamp" to System.currentTimeMillis(), "reportDelay" to 0, "mode" to "craft",
        "conversationId" to conversationId, "requestId" to conversationId,
        "inputLength" to 12, "requestModelId" to "deepseek-v4-flash", "requestModelName" to "deepseek-v4-flash",
        "isPlan" to false, "isAutoExecuteTerminal" to false, "isAutoModify" to false,
        "codebaseEnable" to false, "maxToken" to 0, "maxSteps" to 0, "temperature" to 0, "maxRetries" to 0,
        "mentionContexts" to api.array(), "knowledgeId" to api.array(), "knowledgeName" to api.array(),
        "codebaseId" to "", "mentionContextCount" to 0, "command" to "",
        "expertId" to "", "recommendId" to "", "skillId" to "", "skillCount" to 0, "totalCount" to 0,
        "fileUri" to "", "presentAt" to System.currentTimeMillis(), "traceId" to "",
        "rootRequestId" to conversationId, "parentConversationId" to conversationId,
        "agentName" to "default", "agentType" to "conversation")

    companion object {
        /** 连登档位（1.2.7 `a.r2.f881a`）。 */
        val TIERS = listOf("7d", "14d", "28d")

        /** 抽奖硬上限（1.2.7 多处出现的 20）。 */
        const val MAX_DRAW = 20
    }
}
