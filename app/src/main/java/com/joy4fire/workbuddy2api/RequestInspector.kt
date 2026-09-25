package com.joy4fire.workbuddy2api

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 出网取证日志 —— 把「实际发给上游的头和体」原样留证，供与官方 CLI 逐项比对。
 *
 * 为什么需要它：
 * 静态比对（读官方源码 + 读本实现）只能证明「值写对了」，不能证明「真的发出去了」。
 * 本模块记录的是【临门一脚】的那份数据——即 upstreamRequest 交给 OkHttp 的最终
 * headers/body，中间不再有任何改写。这样导出文本即可与官方 CLI 的抓包逐字节对照。
 *
 * 设计取舍（重要）：
 *
 *  1) 敏感头【保形脱敏】而不是删除。
 *     Authorization / X-Api-Key / Cookie 这类值若原样落盘，等于把账号凭据写进
 *     一个「可一键复制」的文本里，风险远大于收益。但直接删掉又会让人分不清
 *     「这个头没发」和「发了但被隐藏」。故保留头名 + 保留长度与前后缀形态：
 *       Authorization: Bearer eyJhbGci...（共 812 字符，已脱敏）
 *     既能确认「头确实发了、格式确实是 Bearer」，又不泄露凭据本体。
 *
 *  2) body 完整保留（不截断）。
 *     比对的核心是【字段顺序与键名】——这正是官方 JSON.stringify 的字节序特征。
 *     若截断 body，恰好会破坏最该验证的东西。而 body 里确实含用户输入内容，
 *     故本模块默认【关闭】，需用户在设置页手动开启，用完可清空。
 *
 *  3) 环形缓冲 + 落盘。
 *     只留最近 N 条，避免长期开启把存储写满（对照 usage_logs 的体积问题）。
 *     用 SharedPreferences 存 JSON 数组：条目少（默认 20 条）、写入不频繁
 *     （只在开启时记录一笔），比新建一张表更轻。
 *
 *  4) 只记「对话出网」一条链路。
 *     即 upstreamRequest（走 headersForConversation 的那条）。token 刷新、拉模型、
 *     签到等辅助请求不带会话头，与本比对目标无关，记录它们只会稀释信噪比。
 */
object RequestInspector {

    private const val PREFS = "request_inspector"

    /** 保留条数上限。20 条足以覆盖「对比一轮请求」的需求。 */
    private const val MAX_ENTRIES = 20

    /** 开关状态；默认关闭，避免在用户不知情时长期留存请求内容。 */
    private const val KEY_ENABLED = "enabled"

    /** 已记录条目（JSON 数组，倒序：最新在前）。 */
    private const val KEY_ENTRIES = "entries"

    /**
     * 需要脱敏的头名（小写比较）。
     *
     * 判定标准：值本身即可直接用作凭据、或可据此定位到具体账号。
     * X-Domain / X-Enterprise-Id / X-Tenant-Id 不在此列——它们是【身份特征】，
     * 正是比对要看的东西；且它们不含密钥，泄露不构成账号接管。
     */
    private val SENSITIVE_HEADERS = setOf(
        "authorization", "x-api-key", "cookie", "set-cookie", "proxy-authorization"
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_ENTRIES).apply()
    }

    /** 已记录条数（不含内容，用于界面显示）。 */
    fun count(context: Context): Int = readEntries(context).length()

    fun entries(context: Context): org.json.JSONArray = readEntries(context)

    private fun readEntries(context: Context): org.json.JSONArray {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return org.json.JSONArray()
        return runCatching { org.json.JSONArray(raw) }.getOrElse { org.json.JSONArray() }
    }

    /**
     * 记录一笔出网请求。
     *
     * @param endpoint 目标 URL
     * @param method   HTTP 方法
     * @param headers  最终发出的头（有序，保持原样，仅对敏感值脱敏）
     * @param body    最终发出的请求体文本（已由调用方序列化，保证与出网字节一致）
     * @param accountKey 账号标识，用于区分「这笔是哪个号发的」
     */
    fun record(
        context: Context,
        endpoint: String,
        method: String,
        headers: Map<String, String>,
        body: String,
        accountKey: String = ""
    ) {
        if (!isEnabled(context)) return

        val entry = JSONObject()
        entry.put("ts", System.currentTimeMillis())
        entry.put("method", method)
        entry.put("url", endpoint)
        entry.put("account", accountKey)
        // 头按原顺序写入有序 JSON 对象：JSONObject 底层 LinkedHashMap，遍历序即插入序，
        // 这样导出文本的头顺序与出网顺序一致（顺序本身也是一处可比对的细节）。
        val hs = JSONObject()
        headers.forEach { (k, v) ->
            hs.put(k, if (k.lowercase(Locale.ROOT) in SENSITIVE_HEADERS) mask(v) else v)
        }
        entry.put("headers", hs)
        entry.put("body", body)

        val arr = readEntries(context)
        val next = org.json.JSONArray()
        next.put(entry)
        for (i in 0 until minOf(arr.length(), MAX_ENTRIES - 1)) next.put(arr.get(i))
        prefs(context).edit().putString(KEY_ENTRIES, next.toString()).apply()
    }

    /**
     * 保形脱敏：保留前后各 6 个字符与总长度，中间打码。
     *
     * 保留长度是有意的——「Bearer + JWT」的总长度本身就是格式特征，
     * 而截断成固定长度的掩码会丢掉这个信息。
     */
    private fun mask(value: String): String {
        val n = value.length
        if (n <= 14) return "（共 $n 字符，已脱敏）"
        val head = value.substring(0, 6)
        val tail = value.substring(n - 6)
        return "$head…$tail（共 $n 字符，已脱敏）"
    }

    /**
     * 导出为可读文本 —— 这是「一键复制」的内容，设计目标是能直接贴进 diff 工具。
     *
     * 输出格式刻意做成「近似原始 HTTP 报文」：
     *   ① 头部一段元信息（时间/账号/URL），便于人工定位
     *   ② METHOD / URL
     *   ③ 逐行 header（保持发送顺序）
     *   ④ 空行 + 原始 body 文本（不重新格式化 JSON！）
     *
     * 关于第 ④ 点：body 原样输出、绝不用 JSONObject.toString(2) 美化。
     * 因为比对的正是【键顺序与紧凑格式】，一旦缩进就会引入空格差异，
     * 反而让人怀疑顺序不一致。原样输出才能与抓包逐字节对照。
     */
    fun exportText(context: Context): String {
        val arr = readEntries(context)
        if (arr.length() == 0) return "（暂无记录）"

        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val sb = StringBuilder()
        sb.append("WorkBuddy2API 出网取证 · 共 ${arr.length()} 条\n")
        sb.append("导出时间：").append(fmt.format(Date())).append('\n')
        sb.append("=".repeat(72)).append("\n\n")

        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            sb.append("# ").append(i + 1).append("  ")
            sb.append(fmt.format(Date(e.optLong("ts"))))
            val acc = e.optString("account")
            if (acc.isNotBlank()) sb.append("  账号=").append(acc)
            sb.append('\n')

            sb.append(e.optString("method")).append(' ').append(e.optString("url")).append('\n')

            val hs = e.optJSONObject("headers")
            if (hs != null) {
                // keys() 的遍历顺序即插入顺序（LinkedHashMap），与出网顺序一致。
                val it = hs.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    sb.append(k).append(": ").append(hs.optString(k)).append('\n')
                }
            }
            sb.append('\n')
            sb.append(e.optString("body")).append('\n')
            sb.append("-".repeat(72)).append("\n\n")
        }
        return sb.toString()
    }
}
