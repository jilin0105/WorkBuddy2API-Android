package com.joy4fire.workbuddy2api

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 提示词注入 + 客户端指纹清洗管线 —— 移植自 WorkBuddy 1.2.7（a.I2 / a.B2）。
 *
 * 为什么需要这一层：
 * 把本机网关伪装成官方 CLI 时，请求体里往往会残留【其它客户端】的身份痕迹——
 * Claude Code / Codex CLI 的模板句、Anthropic 的 billing 头、`cc_` 键值对。
 * 上游对这些指纹有明确的拦截规则（1.2.7 实测会在命中后返回 content blocked），
 * 命中之后整条链路就废了。本文件做两件事：
 *   1) 按配置注入统一的 system 提示词（custom / passthrough 两档，带降级词）；
 *   2) 从 messages 里剥掉已知的第三方客户端指纹。
 *
 * 与 1.2.7 的差异（有意为之，不是简化）：
 * 1.2.7 用「裸字符串 contains + 正则替换」做清洗，天然漏检大小写变体与头字段顺序变化。
 * 这里改成按【行 / 整段】匹配 + 大小写归一的正则，覆盖面更稳；
 * 同时保留 1.2.7 的替换语义（命中的模板句换成中性说法，而不是整段删除）。
 */
object PromptInjection {

    /** 1.2.7 的默认注入文本（a.C0045h2.Y 内联常量，逐字保留）。 */
    const val DEFAULT_PROMPT: String =
        "你是一名工程助手，帮助用户完成软件工程任务。以下原则指导你的行为。\n\n" +
            "## 核心立场\n" +
            "- 你的价值是让用户的工程目标更快达成，而非展示你自己的能力边界。\n" +
            "- 当用户的方向有更优解时，直接指出并给出替代方案；不必逢迎。\n" +
            "- 对不确定的事保持诚实：宁可说“我不确定，需要验证”，也不编造看似合理的答案。\n\n" +
            "## 语言与风格\n" +
            "- 跟随用户的提问语言：用户用中文则用中文，用英文则用英文。\n" +
            "- 简洁直接，不说废话；不用客套开场与总结，不重复用户已说过的内容。\n" +
            "- 技术术语精确，不为了通俗而牺牲准确性。\n\n" +
            "## 工程行为\n" +
            "- 先看代码再动手：理解上下文、既有模式与约定，避免破坏一致性。\n" +
            "- 最小改动：只改必要的部分，不做无关重构或风格统一。\n" +
            "- 改动后验证闭环：运行测试或构建确认结果，不假设“应该没问题”。\n" +
            "- 遇到不确定的边界，先确认再执行，不擅自扩大范围或假设需求。\n" +
            "- 修改共享代码前，先看它被谁依赖，避免连锁影响。\n\n" +
            "## 任务分解\n" +
            "- 复杂任务先拆步骤，按依赖顺序推进；每步可独立验证。\n" +
            "- 给出改动清单与影响面，让用户能判断是否继续。\n" +
            "- 失败时如实报告原因，给出下一步建议，不掩盖、不粉饰。\n\n" +
            "## 输出格式\n" +
            "- 用 Markdown 组织结构，代码块标注语言。\n" +
            "- 复杂度与任务匹配：简单问题一句话答完，复杂问题分步骤说明。\n" +
            "- 关键决策给出依据，不堆砌理由。\n" +
            "- 引用代码时用 `file:line` 形式，便于用户跳转。\n\n" +
            "## 边界\n" +
            "- 不臆造未给定的 API、字段或行为；不确定时如实说明并给出验证路径。\n" +
            "- 安全敏感操作（删除、覆盖、发布）先确认，除非已被明确授权。\n" +
            "- 错误与失败如实报告，不为了让结果“好看”而省略或美化。\n" +
            "- 保留对方案的质疑空间：如果用户的方案有明显问题，指出并提供更优替代。"

    /**
     * 降级提示词（1.2.7 的 useDegradedPrompt 分支）。
     *
     * 触发条件：上游返回 content blocked（通常是注入内容被指纹规则误伤），
     * 1.2.7 会切到这段极简英文词直到当天午夜，用一个「不像任何客户端」的
     * 通用助手身份继续服务，避免整段时间不可用。
     */
    const val DEGRADED_PROMPT: String =
        "You are a helpful assistant. Respond in the user's language, follow the user's instructions, and be direct and concise."

    /** reasoning_effort 档位 → 数值（1.2.7 的 a.I2.f418a 映射表，逐项保留）。 */
    private val EFFORT_LEVELS = mapOf(
        "off" to 0, "minimal" to 1, "low" to 2, "medium" to 3, "high" to 4, "xhigh" to 5, "max" to 6
    )

    // ---------------------------------------------------------------- 清洗规则

    /** 指纹清洗规则（标记词 / 正则 / 替换表），集中在 [PromptRules] 内维护。 */
    private val FINGERPRINT_MARKERS get() = PromptRules.FINGERPRINT_MARKERS

    // ---------------------------------------------------------------- 对外入口

    /**
     * 输入：完整请求体。副作用：就地改写 messages。
     *
     * @param customPrompt 自定义 system 提示词（留空则用 [DEFAULT_PROMPT]，传 null 表示不注入）
     * @param sanitize     是否清洗第三方客户端指纹
     */
    fun apply(body: JSONObject, settings: JSONObject) {
        val mode = settings.optString("prompt_mode", "custom").lowercase(Locale.US)
        val sanitize = settings.optString("sanitize_fingerprints", "1") !in setOf("0", "false")
        val degraded = settings.optBoolean("use_degraded_prompt", false)

        // 1) 先清洗：必须在注入之前做，否则刚注入的 system 也会被扫描一遍（虽无副作用，
        //    但 degraded 模式下注入的是英文通用词，被 `11128` 这类宽松规则命中会显得很怪）。
        if (sanitize) sanitizeFingerprints(body)

        // 2) 再注入。passthrough 且未降级 = 完全不碰 messages，保持调用方原样。
        if (mode != "custom" && !degraded) return
        val prompt = when {
            degraded -> DEGRADED_PROMPT
            else -> settings.optString("prompt_text").ifBlank { DEFAULT_PROMPT }
        }
        if (prompt.isBlank()) return
        injectSystem(body, prompt)
    }

    /**
     * 把 [prompt] 作为唯一的 system 消息插到 messages 首位。
     *
     * 1.2.7 的语义：剔除原有 system / developer 消息，再在数组头部插入自定义 system。
     * 这样做的原因——上游对「多个 system 消息」的处理不可控，且我们想要的是
     * 【完全接管】system 段，而不是在别人的 system 后面追加（那等于把对方的
     * 身份描述也一起带上去，正是最容易被识别的组合）。
     */
    private fun injectSystem(body: JSONObject, prompt: String) {
        val messages = body.optJSONArray("messages") ?: run {
            body.put("messages", JSONArray().put(systemMessage(prompt)))
            return
        }
        val kept = JSONArray()
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            val role = message.optString("role").lowercase(Locale.US)
            if (role == "system" || role == "developer") continue
            kept.put(message)
        }
        val rebuilt = JSONArray().put(systemMessage(prompt))
        for (i in 0 until kept.length()) rebuilt.put(kept.opt(i))
        body.put("messages", rebuilt)
    }

    private fun systemMessage(prompt: String) = JSONObject().put("role", "system").put("content", prompt)

    /**
     * 清洗请求体里所有可能出现文本的字段。
     *
     * 覆盖面（对齐 1.2.7 `a.I2.i`）：messages[].content（字符串 / 分块数组）、
     * messages[].reasoning、tool_calls[].function.arguments。
     * arguments 是 JSON 字符串，里面同样可能夹带指纹，故一并清洗。
     */
    fun sanitizeFingerprints(body: JSONObject) {
        val messages = body.optJSONArray("messages") ?: return
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            if (message.has("content")) sanitizeField(message, "content")
            if (message.has("reasoning")) sanitizeField(message, "reasoning")
            val calls = message.optJSONArray("tool_calls") ?: continue
            for (j in 0 until calls.length()) {
                val fn = calls.optJSONObject(j)?.optJSONObject("function") ?: continue
                val args = fn.optString("arguments")
                if (args.isEmpty()) continue
                val cleaned = sanitizeText(args)
                if (cleaned != args) fn.put("arguments", cleaned)
            }
        }
    }

    /** 就地清洗某个字段：字符串直接清洗，分块数组则逐块清洗 text 字段。 */
    private fun sanitizeField(container: JSONObject, key: String) {
        when (val value = container.opt(key)) {
            is String -> {
                val cleaned = sanitizeText(value)
                if (cleaned != value) container.put(key, cleaned)
            }
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    val block = value.optJSONObject(i) ?: continue
                    val text = block.optString("text")
                    if (text.isEmpty()) continue
                    val cleaned = sanitizeText(text)
                    if (cleaned != text) block.put("text", cleaned)
                }
            }
        }
    }

    /**
     * 清洗一段文本。返回原串表示未命中任何规则（调用方据此避免无谓写回）。
     *
     * 顺序与 1.2.7 保持一致：先做模板句替换 → 再删 billing 头 → 再删 cc_ 键值。
     * 先替换后删除的原因：模板句里可能嵌着 billing 头，替换后整段更容易被正则一次吃干净。
     */
    fun sanitizeText(text: String): String {
        if (PromptRules.FINGERPRINT_MARKERS.none { text.contains(it) }) return text
        var out = text
        for ((from, to) in PromptRules.TEMPLATE_REPLACEMENTS) out = out.replace(from, to)
        if (PromptRules.RE_BILLING_HEADER.containsMatchIn(out)) out = out.replace(PromptRules.RE_BILLING_HEADER, "")
        if (out.contains("cc_", ignoreCase = true)) {
            // cc_ 键值可能连续出现，替换一次会漏（replace 只吃非重叠的首批），故循环到稳定。
            while (true) {
                val next = out.replace(PromptRules.RE_CC_KV, "")
                if (next == out) break
                out = next
            }
        }
        return out
    }

    /**
     * 把客户端给的 reasoning_effort 夹紧到模型实际支持的档位（1.2.7 `a.I2.e`）。
     *
     * 语义：在模型 supported_efforts 里找一个「不超过请求值且最接近」的档位；
     * 若请求值低于所有支持档位，则取最低档；找不到模型信息时原样保留。
     *
     * 为什么必须夹紧：上游对不支持的 effort 值会直接报错或静默降级，
     * 两者都会让客户端看到与预期不一致的行为。
     */
    fun clampReasoningEffort(body: JSONObject, model: String, supported: List<String>?) {
        val key = when {
            body.has("reasoning_effort") -> "reasoning_effort"
            body.has("reasoningEffort") -> "reasoningEffort"
            else -> return
        }
        if (supported.isNullOrEmpty() || model.isBlank()) return
        val requested = body.optString(key)
        val requestedLevel = EFFORT_LEVELS[requested.lowercase(Locale.US)] ?: return
        val normalized = supported.mapNotNull { name ->
            EFFORT_LEVELS[name.lowercase(Locale.US)]?.let { name to it }
        }
        if (normalized.isEmpty()) return
        val chosen = normalized.filter { it.second <= requestedLevel }.maxByOrNull { it.second }?.first
            ?: normalized.minByOrNull { it.second }?.first
        chosen?.let { if (!it.equals(requested, ignoreCase = true)) body.put(key, it) }
    }

    /**
     * 把 thinking 开关翻译成 reasoning_effort（1.2.7 `a.I2.c`）。
     *
     * 仅对 deepseek 系模型生效：它们用 `thinking.type = enabled/disabled` 表达思考开关，
     * 而 OpenAI 风格接口用 reasoning_effort。两者混用会导致思考链丢失或报错。
     */
    fun alignThinking(body: JSONObject, model: String, defaultEffort: String) {
        if (!model.lowercase(Locale.US).contains("deepseek")) return
        val thinking = body.optJSONObject("thinking")
        val type = thinking?.optString("type").orEmpty().trim()
        if (type.isNotEmpty()) {
            if (type.equals("disabled", ignoreCase = true)) {
                body.remove("reasoning_effort")
                body.remove("reasoningEffort")
            } else if (!body.has("reasoning_effort") && !body.has("reasoningEffort")) {
                body.put("reasoning_effort", defaultEffort.ifBlank { "high" })
            }
            return
        }
        if (thinking == null) body.put("thinking", JSONObject().put("type", "enabled"))
        else thinking.put("type", "enabled")
        if (!body.has("reasoning_effort") && !body.has("reasoningEffort")) {
            body.put("reasoning_effort", defaultEffort.ifBlank { "high" })
        }
    }

    /**
     * tool_choice 归一化（1.2.7 `a.I2.f`）。
     *
     * Anthropic 风格 `{"type":"function","function":{"name":"x"}}` 与字符串形式
     * 在上游的接受度不同，这里统一成上游认的形式；`none` 则连同 tools/functions 一起剔除
     * （上游对「声明了工具但禁止调用」的组合处理不一致，直接不发最稳）。
     */
    fun normalizeToolChoice(body: JSONObject) {
        if (!body.has("tool_choice")) return
        when (val choice = body.opt("tool_choice")) {
            is String -> {
                if (choice.equals("none", ignoreCase = true)) {
                    body.remove("tool_choice"); body.remove("tools"); body.remove("functions")
                }
            }
            is JSONObject -> {
                val type = choice.optString("type").lowercase(Locale.US)
                when (type) {
                    "none" -> {
                        body.remove("tool_choice"); body.remove("tools"); body.remove("functions")
                    }
                    "auto", "required" -> body.put("tool_choice", type)
                    "function" -> {
                        val name = choice.optJSONObject("function")?.optString("name")
                            ?.takeIf { it.isNotBlank() } ?: choice.optString("name")
                        body.put("tool_choice", name.ifBlank { "auto" })
                    }
                    else -> body.remove("tool_choice")
                }
            }
            else -> body.remove("tool_choice")
        }
    }

    /** 订阅式 system 提示词编辑入口用的配置项清单（UI 直接遍历渲染）。 */
    val CONFIG_KEYS = listOf("prompt_mode", "prompt_text", "sanitize_fingerprints", "use_degraded_prompt")

    /** 默认配置：与 1.2.7 完全一致（custom + 注入默认词 + 开清洗 + 不降级）。 */
    fun defaultSettings(): JSONObject = JSONObject()
        .put("prompt_mode", "custom")
        .put("prompt_text", DEFAULT_PROMPT)
        .put("sanitize_fingerprints", "1")
        .put("use_degraded_prompt", false)
}
