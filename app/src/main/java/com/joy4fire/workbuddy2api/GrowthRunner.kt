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
    /**
     * 上游任务码 → 客户端实现。
     *
     * ⚠️ 键必须是【上游真实返回的 code】（2026-10 真机核对），不是旧版 1.2.7 的码。
     * 历史教训：此前整张表用的都是 1.2.7 时代的码（chat_5 / template_5 / skill_1 …），
     * 而上游早已改成 first_chat / template_used / skill_installed …，两者无一匹配，
     * 于是所有任务都落进"未适配"分支 —— 用户看到的就是「好多任务都失效了」。
     *
     * 说明：下面按【语义】把新码映射到已有实现。语义相近不代表上游判定口径完全相同
     * （任务码变更通常伴随计分规则调整），因此若某个任务执行后仍未达标，
     * 需要抓取该任务的真实埋点要求再对齐，不能默认一次映射就永久有效。
     */
    private val handlers: Map<String, Pair<String, (String) -> String>> = mapOf(
        // ---------------- 当前线上真实任务码 ----------------
        "first_chat" to ("完成一次对话（真实对话 + 活跃上报）" to ::firstChat),
        "skill_installed" to ("完成技能安装（真实对话 + skill_info 事件）" to ::skillLoad),
        "expert_summoned" to ("召唤专家（真实专家召唤 + 使用链）" to { key -> expertChain(key, "agent", 1) }),
        "template_used" to ("使用模板（template_used 事件组）" to ::templates),
        // wechat_linked「关联微信」需要真实的微信 OAuth 绑定，客户端无法伪造，
        // 故刻意不提供实现：让它走"未适配"分支明确告知，而不是假装成功。

        // ---------------- 旧版 1.2.7 任务码（保留兼容） ----------------
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
     * 一键执行全部任务。
     *
     * 【为什么改成遍历上游任务，而不是遍历 handlers 码表】
     * 旧实现是「for (code in handlers)」，也就是拿代码里硬编码的 17 个任务码
     * 去上游列表里逐个找。后果是单向的：
     *   · 上游新增任务 → 代码里没有 → 永远不执行（用户看到一堆任务没反应）
     *   · 上游改动/下架任务码 → 匹配不上 → 全部落到"该账号无此任务"分支，
     *     表现为「好多任务都失效了」，但真实原因是码表过期，不是账号没有任务。
     * 现在改为「以服务端返回的任务列表为准」逐个驱动：
     *   · 上游有什么就处理什么，新增任务无需改代码即可被发现；
     *   · 有适配实现的直接执行；
     *   · 没有适配实现的，明确标记为「未适配」并把任务码回显给用户，
     *     便于对照补充，而不是静默跳过。
     *
     * @param only 仅执行这些任务码（null 表示全部）
     */
    fun runAll(accountKey: String, blocks: GrowthTasks.Reporter, only: Set<String>? = null): JSONArray {
        currentReporter = blocks
        val results = JSONArray()
        val snapshot = tasks.list(accountKey)

        // 执行前记下积分余额。
        // 为什么需要它：上游的任务列表【不返回奖励数额】（字段实测只有
        // task_id/code/title/description/level_name/status/url），领奖接口也可能
        // 不给 credit。此时唯一能算出"这次到底加了多少分"的办法，
        // 就是对比本次执行前后的余额差额 —— 这也是 1.2.7 处理签到积分的同一思路。
        val creditsBefore = runCatching {
            NativeCore.refreshCredits(api.appContextCompat(), accountKey).optDouble("remain")
        }.getOrNull()

        // ① 批量接受：只接受「未领取、未锁定、且尚未 accepted/completed」的任务
        val pending = snapshot.filter { !it.claimed && !it.locked && !it.accepted }.map { it.code }
        if (pending.isNotEmpty()) {
            blocks.report("接受 ${pending.size} 个任务…")
            runCatching { tasks.accept(accountKey, pending) }
                .onSuccess { results.put(result("(批量接受)", "done", "已接受 ${pending.size} 个任务")) }
                .onFailure { results.put(result("(批量接受)", "error", "接受失败：${it.message}")) }
            api.sleep(1050)
        }

        // ② 以服务端任务列表为准逐个执行
        var unmapped = 0
        for (task in snapshot) {
            if (only != null && task.code !in only) continue
            val entry = handlers[task.code]
            if (entry == null) {
                // 未适配：不静默跳过，而是把【上游自己的任务标题】一并带出来 ——
                // 光有任务码（如 wechat_linked）用户看不出要做什么，
                // 带上标题（如"关联微信"）才能判断是"需要客户端交互"还是"实现缺失"。
                unmapped++
                val label = task.title.ifBlank { task.code }
                results.put(result(task.code, "unmapped", "「$label」尚未适配（上游有此任务，客户端暂无对应行为）"))
                continue
            }
            if (task.claimed) {
                results.put(result(task.code, "skipped", "已领取过奖励")); continue
            }
            blocks.report("${task.code}…")
            val outcome = runCatching { entry.second(accountKey) }
            val execMessage = outcome.getOrElse { it.message ?: "执行失败" }
            results.put(
                if (outcome.isSuccess) result(task.code, "done", execMessage)
                else result(task.code, "error", execMessage)
            )
            api.sleep(1050)

            // 执行成功后立刻尝试领奖，并把到账数额写进同一条结果。
            // 这样用户看到的「已完成」会直接带上 +N 积分，
            // 而不是要等到最后的统一领奖阶段（甚至等不到，因为那一步依赖重新拉列表）。
            if (outcome.isSuccess) {
                val claimed = runCatching { tasks.claimDetailed(accountKey, task.code) }.getOrNull()
                if (claimed != null && !claimed.alreadyClaimed) {
                    results.put(result(task.code, "claimed", "领奖：${claimed.describe()}"))
                } else if (claimed != null) {
                    results.put(result(task.code, "skipped", "领奖：${claimed.describe()}"))
                }
            }
        }

        // ③ 领奖阶段：执行完的行为可能有任务已达标，这里统一尝试领一次
        val claimable = runCatching { tasks.list(accountKey) }.getOrDefault(emptyList())
            .filter { it.claimable && (only == null || it.code in only) }
        for (task in claimable) {
            blocks.report("领取 ${task.code}…")
            val claimed = runCatching { tasks.claimDetailed(accountKey, task.code) }.getOrNull()
            if (claimed != null) {
                results.put(result(task.code, "claimed", claimed.describe()))
            }
            api.sleep(600)
        }

        if (unmapped > 0) {
            blocks.report("有 $unmapped 个上游任务尚未适配")
        }

        // 汇总本次执行的积分变化。
        // 用余额差额而不是累加各任务的 reward 字段：上游任务列表里没有该字段，
        // 领奖接口也可能不返回数额，只有差额是"确实到账了多少"的唯一可靠证据。
        val creditsAfter = runCatching {
            NativeCore.refreshCredits(api.appContextCompat(), accountKey).optDouble("remain")
        }.getOrNull()
        if (creditsBefore != null && creditsAfter != null) {
            val delta = creditsAfter - creditsBefore
            val text = when {
                delta > 0 -> "本次执行共增加 %.1f 积分（%.1f → %.1f）".format(delta, creditsBefore, creditsAfter)
                delta == 0.0 -> "本次执行未增加积分（当前剩余 %.1f）。任务可能按上游规则稍后计分，或需要对应的真实交互。".format(creditsAfter)
                else -> "本次余额减少 %.1f（%.1f → %.1f），通常是期间的调用消耗，不是任务扣分。".format(-delta, creditsBefore, creditsAfter)
            }
            results.put(result("(积分汇总)", if (delta > 0) "done" else "skipped", text))
            // 差额同步记入积分账本，让「积分详情」里也能看到这次任务的收益
            if (delta != 0.0) {
                runCatching { NativeCore.recordCredit(api.appContextCompat(), accountKey, "task", delta, "一键做任务") }
            }
        } else {
            results.put(result("(积分汇总)", "skipped", "额度查询失败，无法计算本次积分变化；可稍后在账号页「积分详情」查看"))
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

    // ================================================================ 任务链

    /**
     * first_chat「完成一次对话」：发一次真实对话并补报活跃事件。
     *
     * 为什么两件事都做：任务码由旧版的 chat_5 变为 first_chat，判定口径可能一并调整。
     * 「真实对话」满足"完成对话"的字面要求，「活跃上报」覆盖按埋点计分的可能，
     * 做全套比只做一件更不容易漏判。
     */
    private fun firstChat(accountKey: String): String {
        val receipt = realConversation(accountKey, "")
        api.reportMiniProgram(
            accountKey,
            listOf(events.chatRequestSend(receipt.first, "deepseek-v4-flash", "deepseek-v4-flash"))
        )
        return "已完成一次真实对话并上报（会话 ${receipt.first}）"
    }

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
