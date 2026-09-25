package com.joy4fire.workbuddy2api

/**
 * 指纹清洗规则表 —— 从 [PromptInjection] 拆出来单独放，只因为规则里的字面量
 * 含尖括号与反斜杠，集中在一个文件里便于逐条核对。
 *
 * 对应 WorkBuddy 1.2.7 的 `a.I2.f419b`（标记词）、`f420c`（billing 头行）、
 * `f421d`（cc_ 键值）、`f422e`（模板句替换表）。
 */
internal object PromptRules {

    /**
     * 命中即认为「这段文本疑似携带第三方客户端指纹」的标记词。
     *
     * 为什么要先做这层粗筛：真正的清洗是逐条正则替换，对每条消息都跑一遍
     * 开销不小，而绝大多数消息根本不含指纹。先用纯字符串包含做一次廉价判断，
     * 不命中就直接返回原文（1.2.7 也是这个顺序）。
     */
    val FINGERPRINT_MARKERS: List<String> = listOf(
        "x-anthropic-billing-header",
        "cc_",
        "You are Claude Code",
        "Anthropic's official CLI tool",
        "Main branch (",
        "You are a coding agent running in the Codex CLI",
        "github.com/anthropics/",
        "11-128"
    )

    /**
     * billing 头行：形如 `x-anthropic-billing-header: cc_version=2.1.1.abc; cc_entrypoint=cli;`
     * 整行（含换行）删除。
     */
    val RE_BILLING_HEADER = Regex("(?im)^[ \\t]*x-anthropic-billing-header[^\\n]*\\n?")

    /** cc_ 键值对整段（含尾部分号与空白）。 */
    val RE_CC_KV = Regex("(?i)\\bcc_[a-z0-9_]+=[^;\\n]*;?[ \\t]*")

    /**
     * 模板句替换表：命中即换成中性说法。
     *
     * 这里刻意保留「原文 → 原文」的结构而不是删掉整行：
     * 1.2.7 的语义是把这些句子换成不含客户端品牌的特征表达，
     * 从而既保留语义又不暴露来源。下面用等价的去品牌化写法。
     */
    val TEMPLATE_REPLACEMENTS: List<Pair<String, String>> = listOf(
        "You are Claude Code, Anthropic's official CLI tool for Claude" to
            "You are a helpful software engineering assistant",
        "You are a coding agent running in the Codex CLI tool, a terminal-based coding assistant." to
            "You are a helpful software engineering assistant running in a terminal.",
        "To provide feedback, users should report the issue at https://github.com/anthropics/claude-code/issues" to
            "To provide feedback, users should report the issue to the service provider.",
        "Default branch (you will usually use this for PRs)" to
            "Default branch (commonly used as the merge target)",
        "11-128" to "one-to-many"
    )
}
