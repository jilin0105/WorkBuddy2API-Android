package com.joy4fire.workbuddy2api

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * 任务链构造器 —— 移植自 WorkBuddy 1.2.7（a.C0049i2 + a.n2 的 p/u/t/Z 等埋点构造函数）。
 *
 * 职责：为每个任务码产出【完整、结构自洽】的一批埋点事件。
 *
 * 为什么事件字段要这么多：上游对埋点的校验不是「事件名对了就计分」，
 * 而是看这组事件像不像一次真实交互——缺少 conversationId / requestId / traceId
 * 的链路会被判为无效埋点（1.2.7 的注释明确写了「上游可能拒绝了自造会话」）。
 * 因此这里逐字保留 1.2.7 的字段集合与取值。
 */
class GrowthEvents(private val api: GrowthApi) {

    // ---------------------------------------------------------------- 通用字段块

    /** chat 类事件的公共字段（1.2.7 `a.n2.p` 里反复出现的字段集合）。 */
    private fun chatBase(traceId: String, conversationId: String, messageId: String): Array<Pair<String, Any?>> = arrayOf(
        "isPlan" to false, "isAutoExecuteTerminal" to false, "isAutoModify" to false,
        "codebaseEnable" to false, "maxToken" to 0, "maxSteps" to 500,
        "temperature" to 0, "maxRetries" to 0,
        "mentionContexts" to api.array(), "knowledgeId" to api.array(), "knowledgeName" to api.array(),
        "codebaseId" to "", "mentionContextCount" to 0, "command" to "",
        "recommendId" to "", "skillId" to "", "skillCount" to 0, "totalCount" to 0,
        "traceId" to traceId, "rootRequestId" to traceId, "parentConversationId" to conversationId,
        "conversationId" to conversationId, "messageId" to messageId,
        "agentName" to "mp", "agentType" to "main",
        "codebuddy.session_id" to conversationId, "codebuddy.conversation_request_id" to traceId
    )

    /**
     * 完整对话事件链（1.2.7 `a.n2.p`，桌面链路用）。
     *
     * 6 个事件按真实对话的时间顺序排列：任务创建 → 用户发消息 → 请求发送
     * → 模型响应 → 消息状态 → 请求响应。缺任何一个都会被判为不完整链路。
     */
    fun conversationChain(conversationId: String, requestId: String, messageId: String): List<JSONObject> {
        val assistantMessageId = "$messageId-assistant"
        val now = System.currentTimeMillis()
        return listOf(
            api.event("agent_task_created",
                "source" to "LOCAL", "name" to "working", "task_target" to "local", "mode" to "craft",
                "requestModelId" to "fast-model", "requestModelName" to "fast-model",
                "has_repo" to false, "repo_type" to "none", "workspace_type" to "empty",
                "has_connector" to false, "connector_types" to api.array(),
                "has_mention" to false, "mention_types" to api.array(),
                "has_template" to false, "action" to "", "template_name" to "",
                "has_expert" to false, "expert_id" to "", "expert_name" to "", "expert_industry_id" to "",
                "has_skill" to false, "skill_names" to api.array(),
                "conversationId" to conversationId, "messageId" to messageId,
                "buddyId" to "", "buddyName" to ""),
            api.event("chat_message_send",
                "messageId" to assistantMessageId, "historyCount" to 0, "isContextTruncated" to false,
                "currentStepCount" to 1, "traceId" to requestId, "rootRequestId" to requestId,
                "parentConversationId" to conversationId, "agentName" to "cli", "agentType" to "main"),
            api.event("chat_request_send",
                "inputLength" to 24, *chatBase(requestId, conversationId, messageId)),
            api.event("chat_message_response",
                "messageId" to assistantMessageId, "responseModelId" to "fast-model",
                "inputToken" to 120, "outputToken" to 80, "totalToken" to 200,
                "cachedTokens" to 0, "cachedWriteTokens" to 0, "cachedMissTokens" to 0,
                "isSuccessful" to true, "messageErrorCode" to "", "finishReason" to "stop",
                "firstTokenAt" to now, "traceId" to requestId, "conversationId" to conversationId,
                "rootRequestId" to requestId, "parentConversationId" to conversationId,
                "agentName" to "cli", "agentType" to "main",
                "codebuddy.session_id" to conversationId, "codebuddy.conversation_request_id" to requestId),
            api.event("chat_message_status",
                "messageId" to assistantMessageId, "messageErrorCode" to "0",
                "traceId" to requestId, "rootRequestId" to requestId,
                "parentConversationId" to conversationId, "agentName" to "cli", "agentType" to "main"),
            api.event("chat_request_response",
                "mode" to "craft", "toolCallCount" to 0,
                "inputToken" to 120, "outputToken" to 80, "totalToken" to 200,
                "cachedTokens" to 0, "cachedWriteTokens" to 0, "cachedMissTokens" to 0,
                "isSuccessful" to true, "messageErrorCode" to "", "finishReason" to "stop",
                "rootRequestId" to requestId, "parentConversationId" to conversationId)
        )
    }

    /** 单条对话请求事件（1.2.7 `a.r2.h`，补报 chat_5 / 夜猫子用）。 */
    fun chatRequestSend(conversationId: String, modelId: String, modelName: String): JSONObject {
        val now = System.currentTimeMillis()
        return api.event("chat_request_send",
            "timestamp" to now, "reportDelay" to 0, "mode" to "craft",
            "conversationId" to conversationId, "requestId" to conversationId,
            "inputLength" to 12, "requestModelId" to modelId, "requestModelName" to modelName,
            *chatBase(conversationId, conversationId, conversationId))
    }

    /** 模板任务事件组（1.2.7 `a.n2.Z`）。 */
    fun templateChain(conversationId: String, requestId: String, templateId: String, templateName: String): List<JSONObject> = listOf(
        api.event("agent_task_created_with_template",
            "mode" to "working", "isCustomModel" to false, "id" to templateId, "name" to templateName,
            "requestId" to requestId, "conversationId" to conversationId, "messageId" to "msg-$templateId"),
        api.event("template_used", "template_id" to templateId, "task_mode" to "working")
    )

    /** buddyapp 五连事件（1.2.7 `a.n2.H`）。 */
    fun buddyAppChain(): List<JSONObject> {
        val id = "cb_y5Dy46tPQGGWtueMxXbe"
        val name = "企鹅教师助手"
        return listOf(
            api.event("buddyapp_discover_click"),
            api.event("buddyapp_show", "elementId" to id, "elementName" to name, "position" to 2),
            api.event("buddyapp_enter_click", "elementId" to id, "elementName" to name, "position" to 2, "isFirstPage" to "1"),
            api.event("buddyapp_auth_confirm_click", "elementId" to id, "elementName" to name),
            api.event("buddyapp_bindaccount_skip_click", "elementId" to id, "elementName" to name)
        )
    }

    /** 专家召唤链（1.2.7 `a.n2.u` + `t`）。 */
    fun expertSummonChain(expertId: String, expertName: String, expertTitle: String, expertType: String, version: String): List<JSONObject> {
        val type = expertType.ifBlank { "expert-all" }
        val ver = version.ifBlank { "1.0.0" }
        return listOf(
            api.event("web_element_click",
                "source" to expertId, "type" to type, "version" to ver,
                "elementId" to "expert_summon_click", "elementName" to "立即召唤",
                "pageURL" to "/C:/Program%20Files/WorkBuddy/resources/app.asar/renderer/index.html"),
            api.event("expert_summon_click",
                "id" to expertId, "name" to expertName, "expertTitle" to expertTitle,
                "type" to "expert-all", "position" to 0, "expertType" to expertType, "version" to ver, "mode" to "LOCAL"),
            api.event("expert_summoned",
                "id" to expertId, "name" to expertName, "expertTitle" to expertTitle, "type" to "expert-all")
        )
    }

    /** 专家实际使用事件（1.2.7 `a.n2.t`）。 */
    fun expertActualUse(
        expertId: String, expertName: String, expertTitle: String,
        expertType: String, version: String, category: String,
        conversationId: String, requestId: String, mode: String
    ): JSONObject = api.event("expert_actual_use",
        "id" to expertId, "name" to expertName, "expertTitle" to expertTitle,
        "type" to category.ifBlank { "expert-all" }, "expertType" to expertType,
        "source" to "builtin", "version" to version.ifBlank { "1.0.0" },
        "cost" to 9000, "characterCount" to 14,
        "conversationId" to conversationId, "requestId" to requestId,
        "messageId" to "msg-${api.messageId(requestId)}",
        "requestModelId" to "fast-model", "requestModelName" to "fast-model", "mode" to mode)

    /** 专家市场列表（1.2.7 `a.n2.C`）。 */
    fun expertMarket(accountKey: String, expertType: String): JSONArray {
        val body = JSONObject().put("page", 1).put("page_size", 20)
            .put("sort_by", "reco_rank").put("sort_order", "desc")
        if (expertType.isNotBlank()) body.put("expert_type", expertType)
        val data = api.plugin(accountKey, "/portal/operation-platform/market/expert/list", "POST", body)
        return data.optJSONArray("experts") ?: JSONArray()
    }

    /** 夜猫子窗口判定（1.2.7 `a.n2.y`）：23:00–08:00 才算有效计数。 */
    fun isNightWindow(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return !(hour in 8..22)
    }

    /** 桌面模板库的 5 个模板（1.2.7 `a.C0049i2` case 6 原值）。 */
    val TEMPLATES: List<Pair<String, String>> = listOf(
        "1" to "深度研究", "2" to "周报生成", "3" to "竞品分析", "4" to "活动策划", "5" to "代码评审"
    )

    /** 其它需要客户端交互、无公开接口的任务（1.2.7 `a.n2.m` 的行为链路）。 */
    fun unsupportedMessage(): String =
        "该任务需要客户端内交互（无对应接口），请在官方客户端操作"

    /** 本次事件链要用的时间友好 ID 前缀。 */
    fun idPrefix(prefix: String): String = "$prefix-${System.currentTimeMillis()}"

    /** 小写化辅助（与 1.2.7 的 Locale.US 行为一致）。 */
    fun lower(value: String): String = value.lowercase(Locale.US)
}
