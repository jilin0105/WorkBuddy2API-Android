package com.joy4fire.workbuddy2api

import org.json.JSONArray
import org.json.JSONObject

/**
 * 任务执行器 —— 移植自 WorkBuddy 1.2.7（a.C0049i2 的 17 条 case + a.n2 的派生任务）。
 *
 * 每个任务码对应一条「埋点链 + 状态确认 + 领奖」的完整流程。
 * 返回值统一为 JSONObject（task_code / status / message），便于 UI 直接渲染成结果列表。
 *
 * 参考 1.2.7 的任务总表（a.n2.f840d）：17 项，逐项对应下方 handler。
 */
class GrowthRunner(
    private val api: GrowthApi,
    private val tasks: GrowthTasks,
    private val events: GrowthEvents
) {
    private val reporter: GrowthTasks.Reporter? get() = currentReporter
    private var currentReporter: GrowthTasks.Reporter? = null

    /** 任务总表：code → (说明, 执行体)。1.2.7 `a.n2.f840d` 的完整映射。 */
    private val handlers: Map<String, Pair<String, (String) -> String>> = mapOf(
        "chat_5" to ("补报 5 条对话活跃事件" to ::chat5),
        "first_buddy" to ("前置上报 → 同意协议 → 领养第一只 Buddy" to ::firstBuddy),
        "Model_chat_GLM5.2" to ("接受任务 → glm-5.2 真实对话 → 对齐模型上报" to ::glm52),
        "RichMeow_Chat" to ("桌面指纹完整对话事件链（6 事件）" to ::richMeow),
        "Buddy_App" to ("进入 Buddy 应用五连事件" to ::buddyApp),
        "Buddy_App_QQ" to ("进入企鹅教师助手五连事件" to ::buddyApp),
        "automation_1" to ("定时任务创建成功事件" to ::automation),
        "Library_read" to ("资料库介绍阅读事件（web 域）" to ::libraryRead),
        "template_5" to ("使用模板创建任务事件组 ×5" to ::templates),
        "playbook_prompt" to ("灵感案例做同款发送 Prompt 事件组" to ::playbook),
        "create_canvas" to ("设计创意画布创建事件组" to ::canvas),
        "expert_5" to ("真实专家召唤 + 使用链 ×5" to { accountKey -> expertChain(accountKey, "agent", 5) }),
        "Expert_team_use_3" to ("真实专家团召唤 + 使用链 ×3" to { accountKey -> expertChain(accountKey, "team", 3) }),
        "Hp_Appearance" to ("设置主题 API + 皮肤生效事件" to ::appearance),
        "skill_1" to ("真实对话 + skill_info 技能加载事件" to ::skillLoad),
        "Expert_lighthouse" to ("轻量云专家召唤 + 使用链（mode=LOCAL）" to ::lighthouse),
        "black_cat" to ("夜猫子：23:00–08:00 窗口内补足 glm-5.2 对话" to ::blackCat)
    )

    /** 任务码列表（UI 用来渲染「可执行任务」清单）。 */
    val taskCodes: List<String> get() = handlers.keys.toList()

    fun describe(code: String): String = handlers[code]?.first ?: "未知任务"

    // ================================================================ 编排入口

    /**
     * 一键执行（1.2.7 `a.n2.l`：批量接受 → 逐个执行）。返回逐项结果。
     *
     * 顺序严格对齐 1.2.7：
     *   ① 先批量 accept 所有 pending 任务（不做这一步，后续埋点可能不计分）；
     *   ② 再按任务表逐个跑；
     *   ③ 每步之间 sleep 1050ms——这是 1.2.7 的经验值，太密会被判为机器行为。
     */
    fun runAll(accountKey: String, blocks: GrowthTasks.Reporter, only: Set<String>? = null): JSONArray {
        currentReporter = blocks
        val results = JSONArray()
        val snapshot = tasks.list(accountKey)

        // ① 批量接受：只接受「未领取、未锁定、且尚未 accepted/completed」的任务
        val pending = snapshot.filter { !it.claimed && !it.locked && !it.accepted }.map { it.code }
        if (pending.isNotEmpty()) {
            blocks.report("接受 ${pending.size} 个任务…")
            runCatching { tasks.accept(accountKey, pending) }
                .onSuccess { results.put(result("(批量接受)", "done", "已接受 ${pending.size} 个任务")) }
                .onFailure { results.put(result("(批量接受)", "error", "接受失败：${it.message}")) }
            api.sleep(1050)
        }

        // ② 逐个执行
        for ((code, entry) in handlers) {
            if (only != null && code !in only) continue
            val task = snapshot.firstOrNull { it.code == code }
            if (task == null) {
                results.put(result(code, "skipped", "该账号无此任务")); continue
            }
            if (task.accepted) {
                results.put(result(code, "skipped", "已完成（${task.progressText}）")); continue
            }
            blocks.report("$code…")
            // handlers 的 value 是 (说明, 执行体) 二元组，这里要的是执行体。
            val outcome = runCatching { entry.second(accountKey) }
            results.put(
                outcome.fold(
                    onSuccess = { result(code, "done", it) },
                    onFailure = { result(code, "error", it.message ?: "执行失败") }
                )
            )
            api.sleep(1050)
        }
        currentReporter = null
        return results
    }

    /** 执行单个任务码（UI 里点单条「执行」用）。 */
    fun run(accountKey: String, code: String, reporter: GrowthTasks.Reporter? = null): JSONObject {
        currentReporter = reporter
        return try {
            val entry = handlers[code] ?: return result(code, "error", "未知任务码")
            val task = tasks.list(accountKey).firstOrNull { it.code == code }
                ?: return result(code, "skipped", "该账号无此任务")
            if (task.claimed) return result(code, "skipped", "该任务已领取过奖励")
            result(code, "done", entry.second(accountKey))
        } catch (e: Exception) {
            result(code, "error", e.message ?: "执行失败")
        } finally { currentReporter = null }
    }

    // ================================================================ 17 条任务链

    /** chat_5：补报对话活跃事件（1.2.7 case 0）。 */
    private fun chat5(accountKey: String): String {
        val task = tasks.task(accountKey, "chat_5") ?: throw IllegalStateException("任务不存在")
        val target = if (task.target > 0) task.target else 5
        val need = target - task.current
        if (need <= 0) return "进度已达标，无需上报"
        var done = 0L
        for (i in 0 until need) {
            val conversationId = events.idPrefix("wb2api-chat5")
            api.reportMiniProgram(accountKey, listOf(events.chatRequestSend(conversationId, "deepseek-v4-flash", "deepseek-v4-flash")))
            done++
            if (i < need - 1) api.sleep(1050)
        }
        return "已补报 $done 条对话事件"
    }

    /** first_buddy：上报 → 同意协议 → 领养（1.2.7 case 15）。 */
    private fun firstBuddy(accountKey: String): String {
        val conversationId = events.idPrefix("wb2api-adopt")
        api.reportMiniProgram(accountKey, listOf(events.chatRequestSend(conversationId, "deepseek-v4-flash", "deepseek-v4-flash")))
        api.sleep(1050)
        runCatching { api.plugin(accountKey, "/activity/growth/buddy/agreement", "POST", JSONObject().put("agree", true)) }
            .onFailure { reporter?.report("协议已同意或跳过：${it.message}") }
        return try {
            api.plugin(accountKey, "/activity/growth/buddy/first", "POST", JSONObject())
            "已领取第一只 Buddy（+300 分 +8 能量）"
        } catch (e: Exception) {
            if (e.message?.contains("first_buddy task not completed yet", ignoreCase = true) == true) {
                "前置已上报，但领养门槛未过（上游要求当日活跃），请稍后重试"
            } else throw e
        }
    }

    /** Model_chat_GLM5.2：接受 → 真实 glm-5.2 对话 → 模型对齐上报（1.2.7 case 16）。 */
    private fun glm52(accountKey: String): String {
        runCatching { tasks.accept(accountKey, listOf("Model_chat_GLM5.2")) }
            .onFailure { reporter?.report("接受 Model_chat_GLM5.2 失败：${it.message}（继续走行为链路）") }
        api.sleep(1050)
        api.plugin(accountKey, "/v2/chat/completions", "POST", JSONObject()
            .put("model", "glm-5.2")
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hi，请回复一句话")))
            .put("stream", true))
        api.sleep(1050)
        val conversationId = events.idPrefix("wb2api-glm52")
        api.reportMiniProgram(accountKey, listOf(events.chatRequestSend(conversationId, "glm-5.2", "GLM-5.2")))
        return "已完成 glm-5.2 对话并上报"
    }

    /** RichMeow_Chat：桌面指纹完整 6 事件链（1.2.7 case 1）。 */
    private fun richMeow(accountKey: String): String {
        val stamp = System.currentTimeMillis()
        val conversationId = "wb2api-rm-$stamp"
        val requestId = "wb2api-rm-req-$stamp"
        val messageId = "req-$stamp-user"
        api.reportDesktop(accountKey, events.conversationChain(conversationId, requestId, messageId))
        return "已按桌面端指纹上报完整对话事件链（agent_task_created→chat_response）"
    }

    /** Buddy_App / Buddy_App_QQ：五连事件（1.2.7 case 2/3）。 */
    private fun buddyApp(accountKey: String): String {
        api.reportDesktop(accountKey, events.buddyAppChain())
        return "已上报 buddyapp 进入五连事件（同时覆盖 Buddy_App 与 Buddy_App_QQ）"
    }

    /** automation_1：定时任务创建事件（1.2.7 case 4）。 */
    private fun automation(accountKey: String): String {
        api.reportDesktop(accountKey, listOf(api.event("automated_task_create_suc",
            "name" to "wb2api 自动化", "source" to "manually",
            "modelId" to "fast-model", "modelIsThinking" to true,
            "connectorCount" to 0, "skills" to "", "skillCount" to 0,
            "scheduleType" to "once", "mode" to "LOCAL")))
        return "已上报定时任务创建事件"
    }

    /** Library_read：web 域阅读事件（1.2.7 case 5）。 */
    private fun libraryRead(accountKey: String): String {
        val pageUrl = "${GrowthApi.WEB_ORIGIN}/space/d/o0KWYeynteVv06UnAZqIFm"
        val id = api.machineId(accountKey, "webmachine")
        val account = NativeCore.loadAccount(api.appContextCompat(), accountKey)
        val uid = account?.uid.orEmpty()
        val event = JSONObject()
            .put("eventCode", "web_element_click")
            .put("timestamp", System.currentTimeMillis()).put("reportDelay", 0)
            .put("pageURL", pageUrl)
            .put("elementId", "library_doc_intro_click")
            .put("elementName", "WorkBuddy资料库介绍")
            .put("os", "Win32").put("arch", "").put("osVersion", "10.0")
            .put("userAgent", ClientIdentity.BROWSER_UA)
            .put("machineId", id).put("userId", uid).put("userNickname", account?.nickname ?: uid)
        api.reportWeb(accountKey, JSONArray().put(event), pageUrl)
        return "已上报资料库介绍阅读事件"
    }

    /** template_5：5 个模板事件组（1.2.7 case 6）。 */
    private fun templates(accountKey: String): String {
        var index = 0
        for ((id, name) in events.TEMPLATES) {
            index++
            val stamp = System.currentTimeMillis()
            val conversationId = "wb2api-tpl-$stamp-$index"
            val requestId = "wb2api-tpl-req-$stamp-$index"
            runCatching {
                api.reportDesktop(accountKey, events.templateChain(conversationId, requestId, id, name))
            }.onFailure { return "第 $index 组模板事件上报失败：${it.message}" }
            api.sleep(300)
        }
        return "已上报 template_used ×5"
    }

    /** playbook_prompt：灵感案例做同款（1.2.7 case 7）。 */
    private fun playbook(accountKey: String): String {
        val stamp = System.currentTimeMillis()
        val conversationId = "wb2api-pb-$stamp"
        val requestId = "wb2api-pb-req-$stamp"
        val name = "新产品上市 GTM 发布计划一页纸"
        val id = "pm-gtm-launch-plan"
        api.reportDesktop(accountKey, events.conversationChain(conversationId, requestId, "msg-pb").take(2) + listOf(
            api.event("web_element_click",
                "pageName" to "playbook_detail", "elementId" to "playbook_ctaClick",
                "elementName" to name, "source" to "discover"),
            api.event("playbook_cta_click",
                "id" to id, "name" to name, "type" to "document",
                "categoryId" to "", "categoryName" to "", "source" to "discover", "position" to 0),
            api.event("playbook_prompt_send",
                "id" to id, "name" to name, "type" to "document",
                "categoryId" to "", "categoryName" to "", "conversationId" to conversationId, "requestId" to requestId)
        ))
        return "已上报 playbook_cta_click + playbook_prompt_send"
    }

    /** create_canvas：设计创意画布（1.2.7 case 8）。 */
    private fun canvas(accountKey: String): String {
        val stamp = System.currentTimeMillis()
        val conversationId = "wb2api-canvas-$stamp"
        val requestId = "wb2api-canvas-req-$stamp"
        val chain = events.conversationChain(conversationId, requestId, "msg-canvas").take(2) + listOf(
            api.event("wbx_design_canvas_task_create",
                "conversationId" to conversationId, "requestId" to requestId,
                "source" to "summon_keyword", "cost" to 12000, "isSuccessful" to true),
            api.event("wbx_design_canvas_open",
                "conversationId" to conversationId, "requestId" to requestId,
                "id" to "ardot-file-${api.messageId(requestId).take(8)}",
                "source" to "summon_keyword", "type" to "page", "cost" to 13000, "isSuccessful" to true)
        )
        api.reportDesktop(accountKey, chain)
        return "已上报 wbx_design_canvas_task_create/open"
    }

    /**
     * expert_5 / Expert_team_use_3：真实专家召唤 + 使用链（1.2.7 case 9/10 → `a.n2.I`）。
     *
     * 「真实」的含义：先调专家市场列表拿到真实专家 ID，再用该 ID 发一次真实对话
     * （`a.n2.o`）拿服务端 requestId，最后用真 requestId 组装使用链。
     * 1.2.7 注释写明：自造的会话 ID 会被拒绝，必须用服务端回执。
     */
    private fun expertChain(accountKey: String, expertType: String, count: Int): String {
        val market = events.expertMarket(accountKey, expertType)
        if (market.length() == 0) throw IllegalStateException("专家市场列表为空")
        var success = 0
        var failed = 0
        var index = 0
        while (index < market.length() && success < count) {
            val expert = market.optJSONObject(index) ?: run { index++; null } ?: continue
            val expertId = expert.optString("expert_id")
            val expertName = expert.optString("display_name_zh")
            val expertTitle = expert.optString("profession_zh")
            val version = expert.optString("version")
            val category = expert.optJSONArray("categories")?.optString(0).orEmpty()
            index++
            val outcome = runCatching {
                api.reportDesktop(accountKey, events.expertSummonChain(expertId, expertName, expertTitle, expertType, version))
                val receipt = realConversation(accountKey, expertId)
                val chain = events.conversationChain(receipt.first, receipt.second, "msg-${api.messageId(receipt.second)}")
                api.reportDesktop(accountKey, chain + events.expertActualUse(
                    expertId, expertName, expertTitle, expertType, version, category, receipt.first, receipt.second, "craft"))
            }
            outcome.onSuccess { success++ }.onFailure {
                failed++
                reporter?.report("专家 $expertId 失败：${it.message}")
            }
            if (success < count && index < market.length()) api.sleep(6000)
        }
        if (success == 0) throw IllegalStateException("专家链全部失败（$failed 次），上游可能拒绝了自造会话")
        return "已对 $success 位真实专家完成召唤+使用链（类型 $expertType，失败 $failed）"
    }

    /**
     * 发一次真实对话并取回服务端 requestId（1.2.7 `a.n2.o`）。
     *
     * 关键点：requestId 必须从 SSE 流里回读服务端下发的那一个
     * （正则 `^(cmb-)?[0-9a-f]{32}$` 校验），不能用本地生成的——
     * 上游拿它关联「埋点里的 requestId」与「真实产生的对话」，对不上就是伪造。
     *
     * @return (conversationId, requestId)
     */
    private fun realConversation(accountKey: String, expertId: String): Pair<String, String> {
        val conversationId = events.idPrefix("wb2api-conv")
        val body = JSONObject()
            .put("model", "fast-model")
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", "You are a helpful assistant. 当前处于中文环境，使用简体中文回答。"))
                .put(JSONObject().put("role", "user").put("content", "1+1等于几？直接回答。")))
            .put("agent", "cli").put("temperature", 1).put("stream", true)
            .put("stream_options", JSONObject().put("include_usage", true))
        val extra = mutableMapOf(
            "Accept" to "text/event-stream",
            "X-Conversation-ID" to conversationId,
            "X-Request-ID" to System.nanoTime().toString(),
            "X-Agent-Intent" to "craft",
            "X-Agent-Type" to "main"
        )
        if (expertId.isNotBlank()) extra["X-Expert-Id"] = expertId
        val requestId = api.pluginStream(accountKey, "/v2/chat/completions", body, extra)
            ?: throw IllegalStateException("SSE 中未找到服务端 requestId（对话已发但未回执）")
        return conversationId to requestId
    }

    /** Hp_Appearance：设置主题 + 皮肤生效（1.2.7 case 11）。 */
    private fun appearance(accountKey: String): String {
        api.plugin(accountKey, "/v2/user-asset/appearance/set", "POST",
            JSONObject().put("kind", "theme").put("resource_key", "theme-tkmw7j"))
        api.sleep(2000)
        api.reportDesktop(accountKey, listOf(api.event("appearance_skin_apply",
            "action" to "apply", "source" to "settings_close",
            "id" to "theme-tkmw7j", "vipLevel" to 0, "series" to "", "type" to "unknown")))
        return "已设置主题并上报皮肤生效事件"
    }

    /** skill_1：真实对话 + skill_info 技能加载（1.2.7 case 12）。 */
    private fun skillLoad(accountKey: String): String {
        val receipt = realConversation(accountKey, "")
        val messageId = "msg-${api.messageId(receipt.second)}"
        val chain = events.conversationChain(receipt.first, receipt.second, messageId).map { event ->
            // 技能加载会以工具调用方式结束，finishReason 必须是 tool_calls 才自洽。
            if (event.optString("eventCode") == "chat_message_response") event.put("finishReason", "tool_calls") else event
        } + api.event("skill_info",
            "id" to "润泽小馆·日报撰写", "skillId" to "skill_2097350077599879168",
            "skillVersion" to "1.0.0", "toolStatus" to "success", "fileCount" to 56,
            "source" to "workbuddy-desktop",
            "conversationId" to receipt.first, "requestId" to receipt.second, "messageId" to messageId,
            "requestModelId" to "fast-model", "requestModelName" to "fast-model", "traceId" to receipt.second)
        api.reportDesktop(accountKey, chain)
        return "已上报真实对话 + skill_info 技能加载事件"
    }

    /** Expert_lighthouse：轻量云专家链（1.2.7 case 13）。 */
    private fun lighthouse(accountKey: String): String {
        val fallbackId = "ex_2cvvUZQhDyeJ"
        val market = runCatching { events.expertMarket(accountKey, "agent") }.getOrNull()
        var expert = market?.let { array ->
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                .firstOrNull { it.optString("expert_id") == fallbackId }
        }
        val expertId = expert?.optString("expert_id") ?: fallbackId
        val expertName = expert?.optString("display_name_zh").orEmpty().ifBlank { "腾讯轻量云专家" }
        val expertTitle = expert?.optString("profession_zh").orEmpty().ifBlank { "腾讯轻量云专家" }
        val version = expert?.optString("version").orEmpty().ifBlank { "1.0.2" }
        val expertType = expert?.optString("expert_type").orEmpty().ifBlank { "agent" }
        api.reportDesktop(accountKey, events.expertSummonChain(expertId, expertName, expertTitle, expertType, version))
        val receipt = realConversation(accountKey, expertId)
        val chain = events.conversationChain(receipt.first, receipt.second, "msg-${api.messageId(receipt.second)}").map { event ->
            if (event.optString("eventCode") == "agent_task_created") {
                event.put("has_expert", true).put("expert_id", expertId)
                    .put("expert_name", expertName).put("expert_industry_id", "")
            }
            event
        } + events.expertActualUse(expertId, expertName, expertTitle, expertType, version, "", receipt.first, receipt.second, "LOCAL")
            .put("type", "").put("cost", 0)
        api.reportDesktop(accountKey, chain)
        return "已上报轻量云专家召唤+使用链（真实对话 requestId）"
    }

    /** black_cat：夜猫子窗口内补足 glm-5.2 对话（1.2.7 case 14）。 */
    private fun blackCat(accountKey: String): String {
        if (!events.isNightWindow()) return "当前不在 23:00–08:00 计数窗口，行为不计分；可在 23 点后重试"
        val task = tasks.task(accountKey, "black_cat") ?: return "该账号无此任务"
        val need = task.target - task.current
        if (need <= 0) return "进度已达标，无需补足"
        var done = 0L
        for (i in 0 until need) {
            runCatching {
                api.plugin(accountKey, "/v2/chat/completions", "POST", JSONObject()
                    .put("model", "glm-5.2")
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "1+1等于几？直接回答。")))
                    .put("stream", true))
                api.reportMiniProgram(accountKey, listOf(events.chatRequestSend(events.idPrefix("wb2api-night"), "glm-5.2", "GLM-5.2")))
            }.onSuccess { done++ }.onFailure { return "已完成 $done/$need 次夜间对话后中断：${it.message}" }
            api.sleep(4000)
        }
        return "已完成 $done/$need 次夜间对话并上报"
    }

    // ================================================================ 结果构造

    private fun result(code: String, status: String, message: String): JSONObject =
        JSONObject().put("task_code", code).put("status", status).put("message", message)
}
