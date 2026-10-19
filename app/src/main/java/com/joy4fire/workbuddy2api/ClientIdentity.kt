package com.joy4fire.workbuddy2api

/**
 * 官方客户端身份单一事实源。
 *
 * 为什么必须有这个文件：此前 UA 与头集合散落在 NativeCore / OAuthWebActivity /
 * AccountSync 三处，各自拼凑，结果主链路带着自曝 UA「Workbuddy2API-Android/1.0」发出去——
 * 同一账号上「拉模型是浏览器、发对话是第三方网关」，上游做一致性校验一眼就能识别。
 * 现在所有出网身份只从这里取，改一处即全局生效。
 *
 * 取值来源：官方 npm 包 @tencent-ai/codebuddy-code v2.150.0（完整解包于
 * .taixu-tmp/cli/package）。关键证据：
 *
 * 1) product.json 明写国际版身份：
 *      productName = "CodeBuddy"，platform = "CLI"，endpoint = "https://www.codebuddy.ai"
 *
 * 2) UA 拼接算法（UserAgentHttpInterceptor.buildUserAgent）：
 *      先 unshift(productName/productVersion)，再 unshift(platform/platformVersion)，
 *      最后 push(userAgentExtension)，join(" ")
 *    国际版 deploymentType = "SaaS"，不在 {Cloud-Hosted, Self-Hosted} 集合内，
 *    因此走 else 分支 → 结果为「CodeBuddy/<ver> CLI/<ver>」形态。
 *    注：platform 是后 unshift 的，故 join 后 CLI 段排在最前，
 *    这正是 incident-report 实测到的 `CLI/2.63.2 CodeBuddy/2.63.2` 结构。
 *
 * 3) CliClientInfoProvider.initClientInfo 取值：
 *      platform = config.platform || "CLI"
 *      productVersion = platformVersion = config.productVersion || lo.version
 *      pluginName = lo.name = "@tencent-ai/codebuddy-code"
 *
 * 4) bin/codebuddy 会把 CLIENT_INFO_PRODUCT_VERSION 注入为包版本号。
 */
object ClientIdentity {

    /**
     * 当前对接的官方 CLI 版本。
     *
     * 2026-10-19 更新为 2.169.0：MITM 抓包显示官方 CLI 实际发送的是 2.169.0，
     * 而此前固定为 2.150.0。版本号是渠道校验的可见特征之一，必须与真实客户端一致。
     */
    const val CLI_VERSION = "2.169.0"

    /** product.json 的 productName。 */
    const val PRODUCT_NAME = "CodeBuddy"

    /** product.json 的 platform，同时被 CliClientInfoProvider 当作 ideType 使用。 */
    const val PLATFORM = "CLI"

    /** lo.name，官方 npm 包名（pluginName 字段取值）。 */
    const val PLUGIN_NAME = "@tencent-ai/codebuddy-code"

    /** OpenAI Node SDK（stainless 生成）的版本号，官方 CLI 打包的即为 6.25.0。 */
    const val STAINLESS_PACKAGE_VERSION = "6.25.0"

    /** 见 stainlessHeaders() 注释：官方合法值之一，且是服务端 CLI 最合理的运行平台。 */
    const val STAINLESS_OS = "Linux"

    /** 见 stainlessHeaders() 注释：normalizeArch("aarch64") 的官方输出。 */
    const val STAINLESS_ARCH = "arm64"

    /** 真实的 Node LTS 版本号（官方取 process.version 原样）。 */
    const val STAINLESS_RUNTIME_VERSION = "v22.11.0"

    /**
     * 官方 CLI 的真实 UA。
     *
     * 对应 buildUserAgent 的 else 分支输出：`CodeBuddy/2.150.0 CLI/2.150.0`
     * 注意顺序：算法最后一句是 platform 段 unshift 到数组头部，因此 CLI 在前。
     * 实测证据（incident-report:52 记录的旧版本）为 `CLI/2.63.2 CodeBuddy/2.63.2`，
     * 与本实现的结构逐字一致，仅版本号不同。
     */
    val CLI_UA: String = "$PLATFORM/$CLI_VERSION $PRODUCT_NAME/$CLI_VERSION"

    /**
     * 浏览器 UA：仅用于**必须过 WAF 的计费/额度类接口**。
     *
     * 依据上游 billing.py:44-47 的注释与实现：网关 WAF 会拦非浏览器 UA 的计费请求
     * （返回 403/10085），故 upstream 侧对 billing 强制替换为 Chrome UA。
     * 主对话链路**绝不能**用它——credentials.py:96 主链路刻意保持插件身份，
     * 且 incident-report 已实测国际域 5 种 UA 全 403（含浏览器 UA），
     * 伪装浏览器不仅无收益，还会破坏插件协议识别。
     */
    const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    /**
     * 移动端浏览器 UA：OAuth 登录 WebView 与账号绑定类活动接口使用。
     *
     * 默认 WebView UA 带 `; wv` 标记会被风控识别并降级，故必须覆盖为真机 Chrome UA。
     */
    const val MOBILE_BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"

    /**
     * 按接口用途选择 UA。
     *
     * @param purpose 该请求扮演的身份
     * @return 对应的 User-Agent 字符串
     */
    fun userAgentFor(purpose: Purpose): String = when (purpose) {
        Purpose.CLI -> CLI_UA
        Purpose.BROWSER -> BROWSER_UA
        Purpose.MOBILE_BROWSER -> MOBILE_BROWSER_UA
    }

    /** 请求扮演的身份类型。 */
    enum class Purpose {
        /** 主对话链路 / token 刷新 / 模型目录：官方 CLI 插件身份。 */
        CLI,

        /** 计费、额度、资源包：需过 WAF 的浏览器身份。 */
        BROWSER,

        /** OAuth 登录页与活动绑定：移动端浏览器身份。 */
        MOBILE_BROWSER
    }

    /**
     * 无鉴权标记头（模拟官方插件在登录前/插件握手阶段的请求形态）。
     *
     * 取值与 upstream oauth.py:25-30 的 _NO_AUTH 完全一致。
     * 这些头告诉上游「本次请求不带用户/企业/部门上下文」，
     * 是官方插件在 auth state / token 轮询阶段的标准行为。
     */
    fun noAuthHeaders(region: AccountRegion): MutableMap<String, String> = mutableMapOf(
        "X-No-Authorization" to "true",
        "X-No-User-Id" to "true",
        "X-No-Enterprise-Id" to "true",
        "X-No-Department-Info" to "true",
        "X-Domain" to region.defaultDomain,
        "User-Agent" to CLI_UA
    )

    /**
     * 识别头：标记本请求来自官方 CLI 插件。
     *
     * 官方 CLI 在头常量表里定义了 X-Product / X-Product-Version / X-IDE-Type /
     * X-IDE-Version，这些是上游识别客户端形态的直接依据。补齐它们，
     * 上游看到的才是「CodeBuddy CLI 插件」而不是「无产品标识的裸 HTTP 客户端」。
     */
    fun pluginHeaders(): Map<String, String> = mapOf(
        // 官方实际发的是 "SaaS"（2026-10-19 MITM 抓包实证），不是 productName "CodeBuddy"。
        // 这两个值语义不同：productName 是产品名，X-Product 是投放形态标识。
        "X-Product" to "SaaS",
        "X-Product-Version" to CLI_VERSION,
        "X-IDE-Type" to PLATFORM,
        "X-IDE-Name" to PLATFORM,
        "X-IDE-Version" to CLI_VERSION
    )

    /**
     * OpenAI Node SDK 的 X-Stainless-* 指纹头 —— 官方 CLI 每一笔对话请求都会自动带上。
     *
     * 为什么这一组头比 UA 还关键：官方 CLI 的对话请求走的是 OpenAI 官方 Node SDK
     * （stainless 生成，v6.25.0），SDK 在 buildHeaders 里【无条件】注入这组头
     * （证据：dist/lazy/335.13737b8c.js @114279）：
     *
     *   {"X-Stainless-Lang":"js","X-Stainless-Package-Version":"6.25.0",
     *    "X-Stainless-OS":normalizePlatform(process.platform??"unknown"),
     *    "X-Stainless-Arch":normalizeArch(process.arch??"unknown"),
     *    "X-Stainless-Runtime":"node",
     *    "X-Stainless-Runtime-Version":process.version??"unknown"}
     *   以及每条请求都有的 "X-Stainless-Retry-Count":String(retryCount)
     *
     * 我们此前已经发了 X-IDE-Type: CLI，却把这组头【一个都不发】——
     * 上游只要交叉比对「自称官方 CLI」与「没有任何 SDK 指纹」，就能立刻判定
     * 这不是官方 CLI 发出的请求。**补一半伪装比完全不补更可疑。**
     * 这组头纯属 HTTP 头层面，与 TLS 指纹无关，可以逐字节复刻。
     *
     * 取值说明（这几个值官方随用户机器而变，本身没有唯一正确答案）：
     *   - X-Stainless-OS   : 取 "Linux"。官方合法值集合为 {MacOS, Linux, Windows,
     *                        Android, iOS, FreeBSD, OpenBSD}（normalizePlatform @7203740）。
     *                        虽然 normalizePlatform("android") 会返回官方认可的 "Android"，
     *                        但真机 CLI 极少跑在 Android 上，发 "Android" 反而不自然。
     *   - X-Stainless-Arch : 取 "arm64"。官方合法值 {x32, x64, arm, arm64}
     *                        （normalizeArch @7203592），与本机架构一致。
     *   - X-Stainless-Runtime-Version : 取真实存在的 Node LTS 版本号。
     *                        官方该值直接来自 process.version（形如 "v22.11.0"），
     *                        发 "unknown" 反而可疑，故固定为一个真实版本串。
     *
     * 官方【不会】发的头，这里也一律不发：
     *   - X-Stainless-Timeout        仅当调用方显式传 timeout 时才有，官方未设
     *   - X-Stainless-Helper-Method  仅 .stream()/.runTools() 等 helper 内部使用
     *   - X-Stainless-Poll-Helper / X-Stainless-Custom-Poll-Interval  仅轮询场景
     *   - OpenAI-Organization / OpenAI-Project  默认 null，被 SDK 剔除
     */
    fun stainlessHeaders(): Map<String, String> = mapOf(
        "X-Stainless-Lang" to "js",
        "X-Stainless-Package-Version" to STAINLESS_PACKAGE_VERSION,
        "X-Stainless-OS" to STAINLESS_OS,
        "X-Stainless-Arch" to STAINLESS_ARCH,
        "X-Stainless-Runtime" to "node",
        "X-Stainless-Runtime-Version" to STAINLESS_RUNTIME_VERSION,
        "X-Stainless-Retry-Count" to "0"
    )

    /**
     * 会话/追踪/意图头 —— 对齐官方 buildConversationHeaders 的逐行语义。
     *
     * 为什么这些头比 UA 更关键：官方 CLI 的每一笔出站请求都自动携带一组
     * 【会话上下文标识】，上游据此可以把请求归入一个连续会话。
     * 一个「从不携带会话上下文」却高频调用的客户端，正是 API 转售的典型特征——
     * 这比 UA 更容易被统计识别，因为不需要任何指纹知识。
     *
     * 逐条对应官方实现（dist/codebuddy.js @11003334，0x00A7E4B6）：
     *
     *   let messageId = uuidv7().replace(/-/g, "")      → newMessageId（v7 32hex）
     *   h[X-Conversation-ID]         = s.id             → sessionId（UUIDv4 带连字符）
     *   h[X-Conversation-Request-ID] = s.conversationRequestId || ""  → 反代无此值，发空串
     *   h[X-Conversation-Message-ID] = messageId
     *   h[X-Request-ID]              = messageId        → 与 Message-ID【恒等】
     *   h[X-Agent-Intent]            = s.meta?.["codebuddy.ai/mode"] ?? "craft"
     *                                                    → 反代无 meta，取兜底 "craft"
     *   h[X-Agent-Type]              = resolveAgentType(s) → 无 teamContext / 无
     *                                    parentConversationId，故为 "main"
     *
     * 官方在该函数中【条件发送】的头，我们一律不发（反代场景不成立）：
     *   - X-Agent-Purpose        仅在 PERSONAL_AGENT_ROLE 或 session.agentPurpose 存在时发
     *   - X-Root-Request-ID      仅在 meta.rootRequestId 为非空字符串时发
     *   - X-Parent-Conversation-ID 仅在 meta.parentConversationId 有效时发
     *   - X-Session-ID           官方常量表中虽声明，但全 dist 无任何赋值/发送点
     *                            （仅出现在反向清空的白名单里），属死常量，绝不能发。
     *
     * @param conversationId 会话 ID（UUIDv4，带连字符），须按账号分别持久化
     * @param messageId 本条消息 ID（UUIDv7，32 位无连字符）
     */
    fun conversationHeaders(conversationId: String, messageId: String): Map<String, String> = mapOf(
        "X-Conversation-ID" to conversationId,
        "X-Conversation-Request-ID" to "",
        "X-Conversation-Message-ID" to messageId,
        "X-Request-ID" to messageId,
        "X-Agent-Intent" to "craft",
        "X-Agent-Type" to "main"
    )

    /**
     * 网关渠道校验头 —— 2026-10-19 实测抓包后补充。
     *
     * 【为什么必须补】
     *   上游对 /v2/chat/completions 增加了「渠道校验」（channel verification），
     *   失败时返回 HTTP 400 + code 11128，displayTips 明说：
     *   「这次调用没通过渠道校验，可能不是官方客户端发出的」。
     *
     *   实测排除法（同一账号、同一出口 IP=广东电信）：
     *     · Python urllib / curl_cffi(chrome131) / curl_cffi(safari180_ios) / Node undici
     *       四种 TLS 栈全部 11128 → 不是 TLS 指纹问题
     *     · 补齐 Stainless 七件套仍 11128 → 不是缺 SDK 指纹
     *     · 官方 CLI 2.169.0 在同一台机器上成功 → 不是账号/IP 风控
     *   最终用 MITM 抓取官方 CLI 的真实出站请求，逐头比对得出本组缺失项。
     *
     * 【官方实际发送、我们此前缺失的头】（抓包证据：POST /v2/chat/completions）
     *     x-requested-with: XMLHttpRequest
     *     X-Agent-Purpose: conversation
     *     X-Agent-Runtime: other
     *     X-Private-Data: false
     *     x-codebuddy-request: 1
     *     X-Root-Request-ID: <与 X-Request-ID 同值>
     *     X-Conversation-Request-ID: <真实 UUIDv7，非空>
     *     baggage: codebuddy.session_id=...,codebuddy.conversation_request_id=...
     *     traceparent: 00-<32hex trace>-<16hex span>-01
     *     b3: <trace>-<span>-1-<parentSpan>
     *     X-B3-TraceId / X-B3-ParentSpanId / X-B3-SpanId / X-B3-Sampled
     *     X-Trace-ID: <同 traceId>
     *
     * 【与旧实现的差异】
     *   旧实现把 X-Conversation-Request-ID 写死为空串，注释称「反代无此值，发空串」。
     *   但抓包证明官方每笔请求都带真实 UUIDv7 —— 空串正是最显眼的破绽，故改为生成真实值。
     *
     * @param conversationId 会话 ID（UUIDv4，带连字符）
     * @param messageId 本条消息 ID（UUIDv7，32 位无连字符）
     * @param conversationRequestId 会话内请求 ID（UUIDv7，32 位无连字符）
     */
    fun channelVerifyHeaders(
        conversationId: String,
        messageId: String,
        conversationRequestId: String
    ): Map<String, String> {
        // traceId 官方为 32 位 hex（W3C 128-bit），spanId 为 16 位 hex（64-bit）
        val traceId = randomHex(32)
        val spanId = randomHex(16)
        val parentSpanId = randomHex(16)
        return mapOf(
            "x-requested-with" to "XMLHttpRequest",
            "X-Agent-Purpose" to "conversation",
            "X-Agent-Runtime" to "other",
            "X-Private-Data" to "false",
            "x-codebuddy-request" to "1",
            // 官方：X-Root-Request-ID 与 X-Request-ID 同值（本实现中即 messageId）
            "X-Root-Request-ID" to messageId,
            // 官方：真实 UUIDv7，绝非空串
            "X-Conversation-Request-ID" to conversationRequestId,
            // W3C Trace Context
            "traceparent" to "00-$traceId-$spanId-01",
            // B3 单头格式：traceid-spanid-sampled-parentspanid
            "b3" to "$traceId-$spanId-1-$parentSpanId",
            "X-B3-TraceId" to traceId,
            "X-B3-ParentSpanId" to parentSpanId,
            "X-B3-SpanId" to spanId,
            "X-B3-Sampled" to "1",
            "X-Trace-ID" to traceId,
            // OTel baggage：会话归因，官方逐笔携带
            "baggage" to
                "codebuddy.session_id=$conversationId," +
                "codebuddy.conversation_request_id=$conversationRequestId"
        )
    }

    /**
     * 仅链路追踪头（不含会话相关字段）。
     *
     * 为什么单独抽出来：成长任务（专家召唤 / RichMeow 等）走的是**桌面版 WorkBuddy**
     * 渠道，它有自己的 UA 与 X-IDE-* 取值，但**渠道校验（11128）是同一层网关逻辑**，
     * 同样要求 traceparent / b3 / baggage 这组追踪头。
     * 直接复用 [channelVerifyHeaders] 会把 CLI 的会话语义混进桌面链路，故拆成两档：
     *   - channelVerifyHeaders：CLI 对话（含 X-Conversation-* 全套）
     *   - tracingHeaders：桌面/非对话链路（只带追踪头）
     */
    fun tracingHeaders(): Map<String, String> {
        val traceId = randomHex(32)
        val spanId = randomHex(16)
        val parentSpanId = randomHex(16)
        return mapOf(
            "x-requested-with" to "XMLHttpRequest",
            "traceparent" to "00-$traceId-$spanId-01",
            "b3" to "$traceId-$spanId-1-$parentSpanId",
            "X-B3-TraceId" to traceId,
            "X-B3-ParentSpanId" to parentSpanId,
            "X-B3-SpanId" to spanId,
            "X-B3-Sampled" to "1",
            "X-Trace-ID" to traceId
        )
    }

    /** 生成 n 位小写十六进制串（用于 traceId / spanId）。 */
    private fun randomHex(n: Int): String {
        val bytes = ByteArray((n + 1) / 2)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }.take(n)
    }
}
