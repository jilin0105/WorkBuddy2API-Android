package com.joy4fire.workbuddy2api

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * 成长中心 Web 域 API 客户端 —— 移植自 WorkBuddy 1.2.7（a.n2 的 HTTP 骨架 + a.C0045h2.c/j/q）。
 *
 * 为什么需要独立客户端，而不是复用 NativeCore：
 * NativeCore 走的是 copilot.tencent.com 插件链路（CLI 身份 + X-Stainless 指纹），
 * 而成长中心/开学季这套接口 1.2.7 是走 www.workbuddy.cn 的【网页链路】——
 * 它要的是 `x-client-platform: web` + Origin/Referer + 浏览器 UA。
 * 把两种身份混在一条链路上，等于让同一个账号「一会是 CLI 插件、一会是网页」，
 * 这正是最容易被上游关联识别的一致性破绽。
 *
 * 本文件只做传输与身份，业务编排在 [GrowthTasks]。
 */
class GrowthApi(context: Context) {

    private val appContext = context.applicationContext
    private val store get() = NativeCore.store(appContext)

    // ---------------------------------------------------------------- 身份构造

    /** 账号身份快照：把 [NativeCore.Account] 里本文件需要的字段拍平，避免到处 optString。 */
    private class Identity(val account: NativeCore.Account) {
        val uid: String get() = account.uid
        val nickname: String get() = account.nickname
        val token: String get() = account.auth.optString("accessToken")
        val region: AccountRegion get() = account.region
        val domain: String get() = account.auth.optString("domain").ifBlank { account.region.defaultDomain }
        val enterprise: String
            get() = account.profile.optString("enterpriseId", account.auth.optString("enterpriseId"))
    }

    private fun identity(accountKey: String): Identity {
        val account = NativeCore.loadAccount(appContext, accountKey)
            ?: throw IOException("账号不存在：$accountKey")
        return Identity(account)
    }

    // ---------------------------------------------------------------- 头部构造

    /**
     * 成长中心请求头（1.2.7 `a.n2.c`）。
     *
     * `X-Domain` 必须回显该账号自己的 domain 而不是硬编码：多账号场景下
     * 不同账号挂在不同的企业域上，回错域等于把 A 账号的请求标成 B 账号的身份。
     */
    private fun headers(id: Identity): MutableMap<String, String> {
        val headers = mutableMapOf(
            "Authorization" to "Bearer ${id.token}",
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "User-Agent" to desktopUa(id),
            "X-CodeBuddy-Request" to "1",
            "Accept-Language" to if (id.region == AccountRegion.INTERNATIONAL) "en-US" else "zh-CN",
            "X-User-Id" to id.uid,
            "X-Domain" to id.domain
        )
        if (id.enterprise.isNotBlank()) {
            headers["X-Enterprise-Id"] = id.enterprise
            headers["X-Tenant-Id"] = id.enterprise
        }
        return headers
    }

    /** 网页链路请求头（领奖、开学季走这个）——多一组 Origin/Referer/x-client-platform。 */
    private fun webHeaders(id: Identity): MutableMap<String, String> = headers(id).apply {
        this["Accept"] = "application/json, text/plain, */*"
        this["Origin"] = WEB_ORIGIN
        this["Referer"] = "$WEB_ORIGIN/profile/growth-center"
        this["x-client-platform"] = "web"
    }

    /** 桌面端 UA（1.2.7 `a.n2.E`：WorkBuddy/5.5.4 ... CLI/2.137.1）。 */
    private fun desktopUa(id: Identity): String {
        val product = if (id.region == AccountRegion.INTERNATIONAL) "WorkBuddy AI" else "WorkBuddy"
        return "WorkBuddy/5.5.4 $product/5.5.4 CLI/2.137.1"
    }

    /**
     * CLI 身份请求头（用于 /v2/chat/completions 这类对话接口）。
     *
     * 与 [headers] 的区别只在身份标识：UA 与 X-IDE-* 取 CLI 而非 WorkBuddy。
     * 依据：抓包实测官方 CLI 调对话接口时发的是
     *   User-Agent: CLI/2.169.0 CodeBuddy/2.169.0
     *   X-Product: SaaS, X-IDE-Type/Name: CLI, X-IDE-Version: 2.169.0
     * 而桌面版 WorkBuddy 身份调同一接口会被渠道校验拒绝（400/11128）。
     */
    private fun headersForCli(id: Identity): MutableMap<String, String> {
        val headers = headers(id)
        headers["User-Agent"] = ClientIdentity.CLI_UA
        headers["X-Product"] = "SaaS"
        headers["X-Product-Version"] = ClientIdentity.CLI_VERSION
        headers["X-IDE-Type"] = "CLI"
        headers["X-IDE-Name"] = "CLI"
        headers["X-IDE-Version"] = ClientIdentity.CLI_VERSION
        headers["x-codebuddy-request"] = "1"
        ClientIdentity.stainlessHeaders().forEach { (k, v) -> headers[k] = v }
        return headers
    }

    // ---------------------------------------------------------------- 基础请求

    /**
     * 发起请求并解包业务信封（1.2.7 `a.n2.g`）。
     *
     * 解包规则必须严格对齐：
     *   1) HTTP 非 2xx → 抛 IOException，错误信息优先取 msg/message，兜底取 body 前 200 字符；
     *   2) body 含 code 且 non-zero → 抛业务错误；
     *   3) 依次尝试 data.data → data → root，取到第一个 JSONObject。
     *
     * 第 3 条是这套接口最大的坑：不同端点嵌的层数不一样
     * （growth/tasks 是 {data:{tasks}}，claim 是 {data:{credit}}，
     *  而 /v2/report 直接返回裸对象），硬编码一层必然漏。
     */
    fun callUrl(url: String, method: String, body: JSONObject?, headers: Map<String, String>): JSONObject {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> if (v.isNotEmpty()) builder.header(k, v) }
        val request = if (method.equals("GET", ignoreCase = true)) builder.get().build()
        else {
            // `__array` 是内部约定：JSONObject 只能序列化成对象，而 /v2/report
            // 要求请求体是裸数组。这里在发出去前把它还原成裸 JSONArray。
            val raw = body ?: JSONObject()
            val payload = raw.optJSONArray("__array")?.toString() ?: raw.toString()
            builder.method(method.uppercase(Locale.US), payload.toRequestBody(JSON)).build()
        }
        NativeCore.http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val parsed = runCatching { JSONObject(text) }.getOrNull()
            if (!response.isSuccessful) {
                val message = parsed?.optString("msg").orEmpty()
                    .ifBlank { parsed?.optString("message").orEmpty() }
                    .ifBlank { text.take(200) }
                throw IOException("HTTP ${response.code}: $message")
            }
            val root = parsed ?: throw IOException("返回非 JSON：${text.take(160)}")
            if (root.has("code") && root.optInt("code", -1) != 0) {
                throw IOException(root.optString("msg", root.optString("message", "上游业务错误")))
            }
            return root.optJSONObject("data")?.optJSONObject("data")
                ?: root.optJSONObject("data")
                ?: root
        }
    }

    /** 插件域（copilot.tencent.com / codebuddy.ai）请求。 */
    fun plugin(accountKey: String, path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        val id = identity(accountKey)
        return callUrl(id.region.backend + path, method, body, headers(id))
    }

    /** 网页域（www.workbuddy.cn）请求。 */
    fun web(accountKey: String, path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        val id = identity(accountKey)
        return callUrl(WEB_ORIGIN + path, method, body, webHeaders(id))
    }

    /** 上报埋点（1.2.7 `a.n2.F` 桌面链路：桌面指纹 + 机器标识）。 */
    fun reportDesktop(accountKey: String, events: List<JSONObject>) {
        if (events.isEmpty()) throw IOException("桌面事件为空")
        val id = identity(accountKey)
        val now = System.currentTimeMillis()
        val base = JSONObject()
            .put("timezone", "Asia/Shanghai").put("reportDelay", 2000)
            .put("userId", id.uid).put("username", id.nickname).put("userNickname", id.nickname)
            .put("product", "SaaS").put("releaseDate", 1789036585355L)
            .put("commit", "5f9692923c93033111c51ad7b003eb80204a9b75")
            .put("ideName", "WorkBuddy").put("ideType", "WorkBuddy").put("ideVersion", "5.5.6")
            .put("machineId", machineId(accountKey, "machine"))
            .put("sessionId", machineId(accountKey, "session"))
            .put("extName", "workbuddy-desktop").put("extVersion", "5.5.6")
            .put("os", "win32").put("arch", "x64").put("osVersion", "10.0.26220")
            .put("cpuCores", 20).put("memorySize", 24)
            .put("timestamp", now).put("presentAt", now)
        val payload = JSONArray()
        events.forEach { payload.put(merge(base, it)) }
        callUrl(id.region.backend + "/v2/report", "POST", JSONObject().put("__array", payload), reportHeaders(id))
    }

    /**
     * 上报埋点的小程序链路（1.2.7 `a.n2.G`）。
     *
     * 与桌面链路的区别只在身份字段：小程序没有 machineId 的真实来源，
     * 1.2.7 直接写死了一个 UUID。这里保留该行为——它的作用是让上游看到
     * 「一批结构完整的小程序事件」，字段缺失反而更容易被判为伪造。
     */
    fun reportMiniProgram(accountKey: String, events: List<JSONObject>) {
        if (events.isEmpty()) throw IOException("小程序事件为空")
        val id = identity(accountKey)
        val base = JSONObject()
            .put("timestamp", System.currentTimeMillis())
            .put("ideType", "WorkBuddy_MP").put("ideVersion", "2.4.0")
            .put("extName", "workbuddy-mp").put("extVersion", "2.4.0")
            .put("product", "SaaS").put("ideName", "wx_app_cloud").put("platform", "mini_program")
            .put("os", "windows").put("osVersion", "11").put("arch", "x64")
            .put("machineId", MINI_PROGRAM_MACHINE_ID)
            .put("timezone", "Asia/Shanghai")
            .put("userId", id.uid).put("userNickname", id.nickname)
        val payload = JSONArray()
        events.forEach { payload.put(merge(base, it)) }
        val headers = headers(id).apply {
            this["X-Client-Product"] = "workbuddy-mp"
            this["X-Client-Version"] = "2.4.0"
            this["X-Client-Platform"] = "mp-weixin"
            this["X-Platform"] = "wechatmp"
        }
        callUrl(id.region.backend + "/v2/report", "POST", JSONObject().put("__array", payload), headers)
    }

    /** 网页域上报（1.2.7 里资料库阅读走这条：web 身份 + 浏览器 UA + pageURL）。 */
    fun reportWeb(accountKey: String, events: JSONArray, pageUrl: String) {
        val id = identity(accountKey)
        callUrl(WEB_ORIGIN + "/v2/report", "POST", JSONObject().put("__array", events), mutableMapOf(
            "Authorization" to "Bearer ${id.token}",
            "Content-Type" to "application/json",
            "Accept" to "application/json, text/plain, */*",
            "x-client-platform" to "web",
            "Origin" to WEB_ORIGIN,
            "Referer" to pageUrl,
            "User-Agent" to ClientIdentity.BROWSER_UA,
            "X-User-Id" to id.uid
        ))
    }

    private fun reportHeaders(id: Identity) = headers(id).apply {
        this["Content-Type"] = "application/json;charset=UTF-8"
        this["Accept"] = "application/json, text/plain, */*"
        // 用 desktopUa 而不是硬编码字符串：国际版产品名是 "WorkBuddy AI"，
        // 写死 "WorkBuddy" 会让国际版账号带着国内版产品名出网，本身就是渠道校验的破绽。
        this["User-Agent"] = desktopUa(id)
        this["X-Request-ID"] = machineId(id.uid, "req") + (System.nanoTime() % 1_000_000)
    }

    // ---------------------------------------------------------------- 工具方法

    /**
     * 稳定机器标识（1.2.7 `a.n2.n`）。
     *
     * 算法：sha256("$purpose:$uid") 取前 18 字节，按 ":%02x" 拼接。
     * 为什么必须确定性而不是随机：同一账号的历史上报里 machineId 是一致的，
     * 每次换新 ID 会让上游看到「同一账号在一批设备之间跳跃」，比不带 ID 更可疑。
     */
    fun machineId(uid: String, purpose: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$purpose:$uid".toByteArray(Charsets.UTF_8))
        return digest.take(18).joinToString("") { ":%02x".format(it) }
    }

    /**
     * 幂等令牌（1.2.7 `a.n2.k` / `r2.e`）：小写 UUIDv4。
     * 抽奖、连登兑换这类接口要求每次请求一个全新 token，重复即视为重放。
     */
    fun clientToken(): String = UUID.randomUUID().toString().lowercase(Locale.US)

    /**
     * 会话 / 消息 ID 生成（1.2.7 `a.n2.o` / `p` 的入参来源）。
     * 官方 requestId 形如 `cmb-` + 32 位 hex，1.2.7 用正则 `^(cmb-)?[0-9a-f]{32}$` 校验服务端回执。
     */
    fun conversationId(prefix: String): String = "$prefix-${System.currentTimeMillis() / 1000}-${System.nanoTime() % 1000}"

    fun requestId(): String = "wb2api-" + UUID.randomUUID().toString().lowercase(Locale.US)

    /** 消息 ID：官方为 32 位无连字符 hex（1.2.7 `t.g.o0(8, requestId)` 的等价物）。 */
    fun messageId(seed: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    /** 暴露 application context 给上层做账号查询（避免各处再传 Context）。 */
    fun appContextCompat(): Context = appContext

    /**
     * 把诊断文本写到 /sdcard/Download/growth_diag.txt，供外部读取。
     *
     * 为什么走 MediaStore 而不是直接 File 写：targetSdk 36 下分区存储禁止应用直接写
     * Download 目录；MediaStore 是唯一免权限的合法通道。
     * 为什么需要它：Shizuku 侧 logcat 的 tag/keyword/package 过滤在本机全部失效，
     * 桥接又依赖无线 ADB，拿不到 App 日志。写文件是最可靠的旁路。
     */
    private fun dumpDiag(text: String) {
        runCatching {
            if (Build.VERSION.SDK_INT < 29) return
            val resolver = appContext.contentResolver
            val name = "growth_diag.txt"
            // 先删同名旧文件，避免 MediaStore 自动改名成 growth_diag (1).txt
            runCatching {
                resolver.delete(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(name)
                )
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }.onFailure { Log.w(TAG_DIAG, "dumpDiag failed", it) }
    }

    /**
     * SSE 流式请求，返回服务端下发的 requestId（1.2.7 `a.n2.o` 的核心）。
     *
     * 为什么要单独一个方法：普通 [callUrl] 假设响应是 JSON，而这条路径返回
     * `text/event-stream`。而且它的结局不是「拿到数据」而是「从流里捕获一个 ID」——
     * 官方在 SSE 的 chunk 里带 `"id":"<cmb->32位hex"`，必须边读边匹配，
     * 读到就立刻断开（1.2.7 就是这么做的：拿到就 close，不等流结束）。
     *
     * @return 匹配到的 requestId；未匹配到返回 null
     */
    fun pluginStream(accountKey: String, path: String, body: JSONObject, extraHeaders: Map<String, String> = emptyMap()): String? {
        val id = identity(accountKey)
        // 用 CLI 身份，不是桌面身份。
        //
        // 【为什么改】原来这里发的是 X-IDE-Type/Name = WorkBuddy + WorkBuddy/5.5.x 的 UA，
        // 即「伪装成桌面版去调 /v2/chat/completions」。实测（2026-10-19）该组合必被
        // 渠道校验拦下：400/11128 request illegal。官方桌面版从不以 WorkBuddy 身份
        // 调这个对话接口——它对对话接口用的是 CLI 身份（见 CodeBuddy.app 抓包）。
        // 真正能过校验的是 CLI 身份 + channelVerifyHeaders 那套完整头。
        val headers = headersForCli(id).apply {
            this["Accept"] = "text/event-stream"
            putAll(ClientIdentity.channelVerifyHeaders(
                conversationId = UUID.randomUUID().toString(),
                messageId = UUID.randomUUID().toString().replace("-", ""),
                conversationRequestId = UUID.randomUUID().toString().replace("-", "")
            ))
            putAll(extraHeaders)
        }
        val builder = Request.Builder().url(id.region.backend + path)
        headers.forEach { (k, v) -> if (v.isNotEmpty()) builder.header(k, v) }
        val request = builder.post(body.toString().toRequestBody(JSON)).build()
        // 【诊断】把实际要发出的内容打到 logcat（token 脱敏），用于排查 11-128：
        // 只有拿到 App 真实发出的 URL / 头 / 体，才能和 curl 成功样本逐字比对。
        val dump = headers.entries.joinToString("; ") { (k, v) ->
            when {
                k.equals("Authorization", true) -> "$k=Bearer***(${v.length})"
                else -> "$k=$v"
            }
        }
        Log.i(TAG_DIAG, "REQ ${request.url} | HEADERS[$dump] | BODY=${body.toString().take(400)}")
        dumpDiag(
            "== 请求 ==\nURL: ${request.url}\n" +
                "请求头(${headers.size}):\n" + headers.entries.joinToString("\n") { (k, v) ->
                    "  $k: " + if (k.equals("Authorization", true)) "Bearer***(${v.length})" else v
                } +
                "\n\n请求体:\n${body}\n"
        )
        NativeCore.http.newCall(request).execute().use { response ->
            Log.i(TAG_DIAG, "RESP ${response.code} protocol=${response.protocol} msg=${response.message}")
            if (!response.isSuccessful) {
                val errBody = response.body?.string().orEmpty()
                Log.w(TAG_DIAG, "ERRBODY ${errBody.take(500)}")
                dumpDiag(
                    "== 请求 ==\nURL: ${request.url}\n" +
                        "请求头(${headers.size}):\n" + headers.entries.joinToString("\n") { (k, v) ->
                            "  $k: " + if (k.equals("Authorization", true)) "Bearer***(${v.length})" else v
                        } +
                        "\n\n请求体:\n${body}\n\n== 响应 ==\nHTTP ${response.code} " +
                        "protocol=${response.protocol}\n$errBody\n"
                )
                throw IOException("桌面指纹对话失败 HTTP ${response.code}：${errBody.take(160)}")
            }
            val source = response.body?.source() ?: throw IOException("桌面指纹对话响应为空")
            val buffer = StringBuilder()
            while (buffer.length < MAX_SSE_CHARS) {
                val line = source.readUtf8Line() ?: break
                buffer.append(line)
                val index = buffer.indexOf("\"id\":\"")
                if (index >= 0) {
                    val rest = buffer.substring(index + 6)
                    val end = rest.indexOf('"')
                    if (end > 0) {
                        val candidate = rest.substring(0, end)
                        if (REQUEST_ID_PATTERN.matches(candidate)) return candidate
                    }
                }
            }
        }
        return null
    }

    /** 合并 base 与事件字段（事件字段覆盖 base，1.2.7 `a.n2.D`）。 */
    private fun merge(base: JSONObject, event: JSONObject): JSONObject {
        val merged = JSONObject(base.toString())
        event.keys().forEach { merged.put(it, event.opt(it)) }
        return merged
    }

    /**
     * 构造单条埋点事件（1.2.7 `a.n2.s`）。
     * null 值直接跳过——上游对 `"field": null` 与「字段不存在」的处理不同，
     * 官方 SDK 序列化时会丢掉 undefined，故这里对齐「不发 null」。
     */
    fun event(code: String, vararg fields: Pair<String, Any?>): JSONObject {
        val json = JSONObject().put("eventCode", code)
        fields.forEach { (key, value) -> if (value != null) json.put(key, value) }
        return json
    }

    fun array(vararg items: Any?): JSONArray = JSONArray().apply { items.forEach { if (it != null) put(it) } }

    /** 上报体包一层：把 JSONArray 作为裸数组发出（`callUrl` 只接受 JSONObject）。 */
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** 延迟工具（1.2.7 `a.n2.Y`）：节流用，不抛异常。 */
    fun sleep(ms: Long) = runCatching { Thread.sleep(ms) }

    /** 账号是否已可用（内部使用，供 UI 提前校验）。 */
    fun hasAccount(): Boolean = store.accountKeys().isNotEmpty()

    companion object {
        /** 诊断日志 TAG：排查上游拒绝（11-128 等）时按此过滤 logcat。 */
        private const val TAG_DIAG = "GrowthDiag"

        /** 成长中心 / 开学季的网页域（1.2.7 `a.C0045h2.j` 等处的硬编码域）。 */
        const val WEB_ORIGIN = "https://www.workbuddy.cn"

        /** 小程序链路的固定 machineId（1.2.7 原值）。 */
        const val MINI_PROGRAM_MACHINE_ID = "0655736a-607f-4d9d-b430-58176ee9a090"

        /** 服务端 requestId 形态（1.2.7 `a.n2.f838b`：^(cmb-)?[0-9a-f]{32}$）。 */
        val REQUEST_ID_PATTERN = Regex("^(cmb-)?[0-9a-f]{32}$")

        /** SSE 读取上限：防止上游一直吐流导致内存无界增长（1.2.7 用 1,000,000）。 */
        const val MAX_SSE_CHARS = 1_000_000

        /** 桌面端事件常用常量。 */
        const val BUDDY_ID = "cb_y5Dy46tPQGGWtueMxXbe"
        const val BUDDY_NAME = "企鹅教师助手"

        /**
         * 从响应体抽取裸 JSONArray（个别端点直接返回数组）。
         * 单独放这里是因为 [callUrl] 的返回类型是 JSONObject，数组场景需要另一条路径。
         */
        fun arrayField(root: JSONObject, key: String): JSONArray = root.optJSONArray(key) ?: JSONArray()
    }
}
