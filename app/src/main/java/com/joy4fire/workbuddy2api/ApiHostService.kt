package com.joy4fire.workbuddy2api

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import okhttp3.Response
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ApiHostService : Service() {
    /**
     * 有界工作线程池：原来用 newCachedThreadPool 无上限，突发的慢连接会瞬间堆出大量线程
     * （每线程约 1MB 栈），常驻服务被 LMK 优先回收——与保活目标直接冲突。
     * 上限 16 线程 + 64 队列已远超单机自用场景的实际并发，溢出时直接拒掉连接而不是拖垮进程。
     */
    private val workers = java.util.concurrent.ThreadPoolExecutor(
        2, 16, 60L, TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue(64),
        Executors.defaultThreadFactory(),
        java.util.concurrent.ThreadPoolExecutor.AbortPolicy()
    )
    private val maintenance = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var server: ServerSocket? = null
    // 常驻部分唤醒锁：Cached App Freezer 会跳过持有 wakelock 的进程（官方豁免行为），
    // 没有它，APP 退后台后会被 MIUI/原生冻结器冻结，本地反代端口仍在监听但进程不调度、请求全部挂起。
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        acquireWakeLock()
        startForeground(NOTIFICATION_ID, notification("正在启动原生 API…"))
        scheduleWatchdog()
        workers.execute { serve() }
        maintenance.scheduleWithFixedDelay({ runMaintenanceTick() }, 5, 60, TimeUnit.SECONDS)
    }

    /**
     * 看门狗：定时用 AlarmManager 检查服务是否还活着。
     * 一加/OPPO 的 ColorOS 会在锁屏、长时间待机或内存回收时直接干掉前台服务，
     * 且会无视 START_STICKY 不重启——只有 AlarmManager 的 setExactAndAllowWhileIdle
     * 能在 Doze 下把进程叫醒。每次唤醒时若服务已不在，就重新拉起来。
     * 用 15 分钟间隔：更频繁会被 ColorOS 判定为异常唤醒而拦截，反而更不可靠。
     */
    private fun scheduleWatchdog() {
        runCatching {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = watchdogIntent()
            val triggerAt = SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS
            // Android 12+ 即使用户授权过，也可能事后在设置里关掉"闹钟与提醒"，此时
            // setExactAndAllowWhileIdle 直接抛 SecurityException。先预检再决定用哪种，
            // 避免"精确失败→静默退化"，也便于在日志里暴露这一降级（Doze 下非精确可能延迟数小时）。
            val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            if (canExact) {
                runCatching { am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi) }
                    .onFailure { am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi) }
            } else {
                Log.w(TAG, "exact alarm not permitted; watchdog falls back to inexact (may be delayed in Doze)")
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
            Log.i(TAG, "watchdog scheduled in ${WATCHDOG_INTERVAL_MS / 60_000} min (exact=$canExact)")
        }.onFailure { Log.w(TAG, "watchdog schedule failed", it) }
    }

    private fun watchdogIntent(): PendingIntent = PendingIntent.getService(
        this, WATCHDOG_REQUEST,
        Intent(this, ApiHostService::class.java).setAction(ACTION_WATCHDOG),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /**
     * 用户从最近任务里划掉 App。ColorOS 默认会在这一刻连带杀掉前台服务；
     * stopWithTask="false" 只能挡住"随任务栈销毁"，挡不住系统主动清理。
     *
     * Android 12+ 禁止后台启动前台服务（ForegroundServiceStartNotAllowedException），
     * 这里直接调 startForegroundService 很可能被拦，"只打日志"等于划掉后永不恢复。
     * 因此失败时立刻挂一个 5 秒后的精确闹钟——闹钟唤醒属于系统回调，不受该限制，
     * 能拿到一次宝贵的"前台服务启动配额"把服务拉回来。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户主动停止过就不要再拉起，否则会出现"关了又自己开"。
        if (!shouldRun(applicationContext)) {
            Log.i(TAG, "task removed but user stopped service, skip restart")
            super.onTaskRemoved(rootIntent)
            return
        }
        Log.i(TAG, "task removed, restarting service")
        val restart = Intent(applicationContext, ApiHostService::class.java)
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(restart) else startService(restart)
        }.isSuccess
        if (!started) {
            // 被后台启动限制拦截：退化为精确闹钟，稍后由系统唤起服务。
            Log.w(TAG, "direct restart blocked, falling back to alarm")
            runCatching {
                val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val pi = PendingIntent.getService(
                    this, WATCHDOG_REQUEST + 1,
                    Intent(this, ApiHostService::class.java).setAction(ACTION_WATCHDOG),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val at = SystemClock.elapsedRealtime() + 5_000L
                runCatching { am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi) }
                    .onFailure { am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi) }
            }.onFailure { Log.w(TAG, "fallback alarm failed", it) }
        }
        scheduleWatchdog()
        super.onTaskRemoved(rootIntent)
    }

    /**
     * 获取部分唤醒锁（不计数模式，服务停止时统一释放）。
     *
     * 带 10 分钟超时而不是无限期持有：长时间死锁会被 ROM 的省电策略判为异常耗电目标，
     * 反而优先清理，与保活初衷相反；超时后由 60 秒一次的 maintenance tick 自动续期，
     * 既保持"始终持锁"，又避免留下无人回收的永久锁。
     */
    private fun acquireWakeLock() {
        // 已持有且未过期就跳过；带超时的锁过期后 isHeld 会变回 false，下次 tick 自动续上。
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:wakelock").apply {
                setReferenceCounted(false)
                acquire(WAKELOCK_TIMEOUT_MS)
            }
            Log.i(TAG, "wake lock acquired (freeze exemption active)")
        }.onFailure { Log.w(TAG, "wake lock acquire failed", it) }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopNow()
            return START_NOT_STICKY
        }
        // 只要服务被启动（无论来自用户点按、开机自启还是看门狗兜底），就记为"用户希望它运行",
        // 这样进程被杀后系统/闹钟再次拉起时，onTaskRemoved 的前置校验才会放行。
        runCatching { markShouldRun(applicationContext, true) }
        // 被看门狗闹钟唤醒时，服务可能刚被系统重建：确认前台通知与唤醒锁都在。
        runCatching {
            if (intent?.action == ACTION_WATCHDOG) Log.i(TAG, "watchdog fired, ensuring foreground")
            acquireWakeLock()
            startForeground(NOTIFICATION_ID, notification(if (running) "原生 API 运行中 · 端口 $PORT" else "正在启动原生 API…"))
        }.onFailure { Log.w(TAG, "foreground ensure failed", it) }
        // 每次进入都续期看门狗，防止闹钟一次性触发后再无兜底。
        scheduleWatchdog()
        return START_STICKY
    }

    private fun serve() {
        try {
            val socket = ServerSocket(PORT, 32, InetAddress.getByName("0.0.0.0"))
            server = socket
            running = true
            notifyState("原生 API 运行中 · 端口 $PORT（本机与局域网）")
            while (!socket.isClosed) {
                val client = socket.accept()
                // 线程池/队列已满时必须单独接住：否则拒绝异常会冒泡到外层 catch，
                // 直接终止整个 accept 循环（服务表面在跑，实际再收不到任何请求）。
                runCatching {
                    workers.execute {
                        client.use { socket ->
                            runCatching { handle(socket) }.onFailure { Log.w(TAG, "request failed", it) }
                        }
                    }
                }.onFailure {
                    Log.w(TAG, "worker pool saturated, dropping connection", it)
                    runCatching { client.close() }
                }
            }
        } catch (e: Exception) {
            if (running) notifyState("API 异常：${e.message}")
        } finally {
            running = false
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 300_000
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        val requestLine = readAsciiLine(input) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0].uppercase(Locale.US)
        val path = parts[1].substringBefore('?')
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = readAsciiLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase(Locale.US)] = line.substring(colon + 1).trim()
        }
        if (method == "OPTIONS") {
            writeHeaders(output, 204, "text/plain", 0)
            return
        }
        val declaredLength = headers["content-length"]?.toLongOrNull() ?: 0L
        if (declaredLength < 0 || declaredLength > MAX_BODY_BYTES) {
            json(output, 413, apiError("请求体过大", "invalid_request_error"))
            return
        }
        val bodyBytes = ByteArray(declaredLength.toInt())
        var offset = 0
        while (offset < bodyBytes.size) {
            val count = input.read(bodyBytes, offset, bodyBytes.size - offset)
            if (count < 0) throw EOFException("请求体提前结束")
            offset += count
        }
        val bodyText = String(bodyBytes, StandardCharsets.UTF_8)
        val startedAt = System.currentTimeMillis()

        val selectedApp = authorizedApp(headers)
        try {
            when {
                method == "GET" && path == "/health" -> json(output, 200, JSONObject().put("status", "ok").put("native", true)
                    .put("account", NativeCore.loadAccount(this)?.uid ?: JSONObject.NULL))
                selectedApp == null -> {
                    // 鉴权失败的请求同样留痕：否则客户端 Key 配错或应用被停用时，
                    // 记录页会永远空白，用户完全无法从记录里看到"请求其实来过并被拒绝"。
                    if (path.startsWith("/v1/")) {
                        logRejected(method, path, startedAt, "无效或缺失 API Key（应用不存在、已停用或 Key 不匹配）", null)
                    }
                    json(output, 401, apiError("无效或缺失 API Key", "authentication_error"))
                }
                method == "GET" && path == "/v1/models" -> {
                    val region = AccountRegion.fromStrict(selectedApp.optString("region"))
                    val started = System.currentTimeMillis()
                    val models = NativeCore.models(this, region)
                    Log.i(TAG, "GET /v1/models ${region.id} -> ${models.length()} models in ${System.currentTimeMillis() - started}ms")
                    json(output, 200, JSONObject().put("object", "list").put("region", region.id)
                        .put("data", models))
                }
                method == "POST" && path == "/v1/messages/count_tokens" -> {
                    val payload = parseBody(bodyText)
                    json(output, 200, JSONObject().put("input_tokens", ProtocolAdapters.estimateAnthropicTokens(payload)))
                }
                method == "POST" && path == "/v1/chat/completions" -> {
                    val payload = parseBody(bodyText)
                    if ((payload.optJSONArray("messages")?.length() ?: 0) == 0)
                        throw ClientError("messages is required")
                    proxy(output, socket, payload, Protocol.CHAT, payload.optBoolean("stream", false), selectedApp)
                }
                method == "POST" && path == "/v1/messages" -> {
                    val payload = parseBody(bodyText)
                    proxy(output, socket, ProtocolAdapters.anthropicToChat(payload), Protocol.ANTHROPIC,
                        if (payload.has("stream")) payload.optBoolean("stream") else true, selectedApp)
                }
                method == "POST" && path == "/v1/responses" -> {
                    val payload = parseBody(bodyText)
                    proxy(output, socket, ProtocolAdapters.responsesToChat(payload), Protocol.RESPONSES,
                        if (payload.has("stream")) payload.optBoolean("stream") else true, selectedApp)
                }
                else -> json(output, 404, apiError("未找到端点", "not_found"))
            }
        } catch (e: ClientError) {
            val message = e.message ?: "无效请求"
            // 参数校验发生在转发之前，鉴权已通过，可归因到具体应用，同样需要留痕。
            logRejected(method, path, startedAt, message, selectedApp)
            json(output, 400, apiError(message, "invalid_request_error"))
        } catch (e: JSONException) {
            logRejected(method, path, startedAt, "bad json", selectedApp)
            json(output, 400, apiError("bad json", "invalid_request_error"))
        } catch (e: IOException) {
            json(output, 503, apiError(e.message ?: "服务不可用", "upstream_error"))
        } catch (e: Exception) {
            Log.e(TAG, "gateway failure", e)
            json(output, 500, apiError("内部网关错误", "api_error"))
        }
    }

    private fun proxy(output: OutputStream, socket: Socket, chatBody: JSONObject, protocol: Protocol, stream: Boolean, app: JSONObject) {
        val model = chatBody.optString("model", "auto")
        val startedAt = System.currentTimeMillis()
        val inputSummary = inputText(chatBody)
        val collector = ChatStreamCollector(model)
        val converter: NativeStreamConverter? = when (protocol) {
            Protocol.ANTHROPIC -> AnthropicStreamConverter(model)
            Protocol.RESPONSES -> ResponsesStreamConverter(model)
            else -> null
        }
        val region = AccountRegion.fromStrict(app.optString("region"))
        var account: NativeCore.Account? = null
        var call: okhttp3.Call? = null
        var response: Response? = null
        var headersSent = false
        var writingClient = false
        try {
            // 选号与 token 刷新同样可能失败（无可用账号 / 凭证过期），必须放进 try 内，
            // 否则异常会直接冒泡成 503，使用记录里完全看不到这次调用。
            val upstream = NativeCore.upstreamRequest(this, chatBody, region)
            account = upstream.account
            call = NativeCore.http.newCall(upstream.request)
            response = call.execute()
            if (!response.isSuccessful) {
                val raw = response.body?.string().orEmpty()
                val errorBody = safeUpstreamError(raw, response.code)
                logUsage(protocol, model, startedAt, "error", "HTTP ${response.code}", null, inputSummary, "", "", app, account)
                json(output, response.code, errorBody)
                return
            }
            val source = response.body?.source() ?: throw IOException("上游响应为空")
            // Prefetch the first data event before committing HTTP 200, preserving upstream error semantics.
            val first = nextData(source)
            if (stream) {
                writeSseHeaders(output)
                headersSent = true
                var sawDone = false
                fun emit(data: String) {
                    collector.feed(data)
                    if (data == "[DONE]") sawDone = true
                    val event = when (protocol) {
                        Protocol.CHAT -> ProtocolAdapters.sanitizeChatData(data)?.let { "data: $it\n\n" }.orEmpty()
                        else -> converter?.feed(data).orEmpty()
                    }
                    if (event.isNotEmpty()) {
                        writingClient = true
                        writeUtf8(output, event)
                        writingClient = false
                    }
                }
                if (first != null) emit(first)
                while (true) emit(nextData(source) ?: break)
                val tail = when (protocol) {
                    Protocol.CHAT -> if (!sawDone) "data: [DONE]\n\n" else ""
                    else -> converter?.finish().orEmpty()
                }
                if (tail.isNotEmpty()) {
                    writingClient = true
                    writeUtf8(output, tail)
                    writingClient = false
                }
                logUsage(protocol, model, startedAt, "ok", "", collector.usage, inputSummary,
                    collector.outputText(), collector.reasoningText(), app, account)
            } else {
                fun collect(data: String) {
                    collector.feed(data)
                    converter?.feed(data)
                }
                if (first != null) collect(first)
                while (true) collect(nextData(source) ?: break)
                converter?.finish()
                val result = converter?.nonStreamResponse() ?: collector.response()
                json(output, 200, result)
                logUsage(protocol, model, startedAt, "ok", "", collector.usage, inputSummary,
                    collector.outputText(), collector.reasoningText(), app, account)
            }
        } catch (e: Exception) {
            val disconnected = headersSent && (writingClient || socket.isClosed || isDisconnect(e))
            logUsage(protocol, model, startedAt, if (disconnected) "aborted" else "error",
                if (disconnected) "client disconnected" else (e.message ?: e.javaClass.simpleName), collector.usage,
                inputSummary, collector.outputText(), collector.reasoningText(), app, account)
            if (!headersSent) throw if (e is IOException) e else IOException(e)
            if (!disconnected) runCatching { writeUtf8(output, streamError(protocol, e.message ?: "上游流异常")) }
        } finally {
            if (socket.isClosed) call?.cancel()
            response?.close()
        }
    }

    private fun nextData(source: BufferedSource): String? {
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: return null
            if (!line.startsWith("data:")) continue
            return line.substring(5).trim()
        }
        return null
    }

    private fun authorizedApp(headers: Map<String, String>): JSONObject? {
        val auth = headers["authorization"].orEmpty()
        val bearer = if (auth.startsWith("Bearer ", ignoreCase = true)) auth.substring(7).trim() else ""
        val key = bearer.ifBlank { headers["x-api-key"].orEmpty() }
        if (key.isBlank()) return null
        return NativeCore.authenticateApp(this, key)
    }

    private fun parseBody(text: String): JSONObject = try {
        JSONObject(text.ifBlank { "{}" })
    } catch (_: JSONException) {
        throw ClientError("bad json")
    }

    private fun safeUpstreamError(raw: String, status: Int): JSONObject {
        val parsed = runCatching { JSONObject(raw) }.getOrNull()
        if (parsed?.has("error") == true) return parsed
        val message = parsed?.optString("message")?.takeIf { it.isNotBlank() }
            ?: raw.take(500).takeIf { it.isNotBlank() }
            ?: "上游返回 HTTP $status"
        return apiError(message, "upstream_error")
    }

    private fun streamError(protocol: Protocol, message: String): String {
        val error = apiError(message, "upstream_error")
        return when (protocol) {
            Protocol.ANTHROPIC -> "event: error\ndata: ${JSONObject().put("type", "error").put("error", error.getJSONObject("error"))}\n\n"
            else -> "data: $error\n\ndata: [DONE]\n\n"
        }
    }

    private fun inputText(body: JSONObject): String {
        val result = StringBuilder()
        val messages = body.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            if (result.isNotEmpty()) result.append('\n')
            result.append(message.optString("role", "user")).append(": ")
            when (val content = message.opt("content")) {
                is String -> result.append(content)
                is JSONArray -> for (j in 0 until content.length()) {
                    val part = content.optJSONObject(j) ?: continue
                    when (part.optString("type")) {
                        "text" -> result.append(part.optString("text"))
                        "image_url" -> result.append("[image]")
                    }
                }
            }
        }
        return result.toString()
    }

    /**
     * 记录未进入转发链路的请求（鉴权失败、参数错误等）。
     * 这类请求过去完全不落库，是"记录页不显示调用记录"的主要原因之一。
     */
    private fun logRejected(method: String, path: String, startedAt: Long, message: String, app: JSONObject?) {
        val protocol = when (path) {
            "/v1/messages", "/v1/messages/count_tokens" -> Protocol.ANTHROPIC
            "/v1/responses" -> Protocol.RESPONSES
            else -> Protocol.CHAT
        }
        val record = JSONObject()
            .put("protocol", protocol.wireName)
            .put("model", "")
            .put("status", "error")
            .put("error", message)
            .put("latency_ms", System.currentTimeMillis() - startedAt)
            .put("input_content", "").put("output_content", "").put("reasoning_content", "")
            .put("input_tokens", 0).put("output_tokens", 0).put("total_tokens", 0)
            .put("ts", System.currentTimeMillis() / 1000.0)
            .put("account_uid", "")
            .put("account_region", app?.let { AccountRegion.fromStrict(it.optString("region")).id } ?: "")
            .put("app_name", app?.optString("name") ?: "")
        Log.i(TAG, "rejected $method $path: $message")
        runCatching { NativeCore.logUsage(this, record) }
            .onFailure { Log.w(TAG, "NativeStore usage log failed", it) }
    }

    private fun logUsage(
        protocol: Protocol, model: String, startedAt: Long, status: String, error: String,
        usage: JSONObject?, input: String, output: String, reasoning: String, app: JSONObject,
        account: NativeCore.Account?
    ) {
        val inputTokens = usage?.optLong("prompt_tokens", usage.optLong("input_tokens", 0)) ?: 0L
        val outputTokens = usage?.optLong("completion_tokens", usage.optLong("output_tokens", 0)) ?: 0L
        val record = JSONObject().put("protocol", protocol.wireName).put("model", model).put("status", status)
            .put("error", error).put("latency_ms", System.currentTimeMillis() - startedAt)
            .put("input_content", input).put("output_content", output).put("reasoning_content", reasoning)
            .put("input_tokens", inputTokens).put("output_tokens", outputTokens)
            .put("total_tokens", usage?.optLong("total_tokens", inputTokens + outputTokens) ?: inputTokens + outputTokens)
            .put("credits", upstreamCredits(usage))
            .put("ts", System.currentTimeMillis() / 1000.0)
            .put("account_uid", account?.uid ?: "")
            .put("account_region", account?.region?.id ?: app.optString("region").ifBlank { "domestic" })
            .put("app_name", app.optString("name"))
        // 原始 usage 一并打点：真机核对上游积分字段名时只需看 logcat，不必改代码重编。
        if (usage != null) Log.i(TAG, "upstream usage=$usage")
        Log.i(TAG, "usage=$record")
        runCatching { NativeCore.logUsage(this, record) }
            .onFailure { Log.w(TAG, "NativeStore usage log failed", it) }
    }

    /**
     * 解析上游 usage 中本次请求真实消耗的积分。
     *
     * 字段名以实测为准：腾讯 /v2/chat/completions 的积分字段是 `credit`（单数），
     * 与 Python 版 `credits=usage.get("credit") or 0` 完全一致；其余候选名用于兼容上游改版。
     * 缺失或非有限值（NaN/Infinity）时回落 0.0——NaN 会被 SQLite 写成 NULL，
     * 命中 usage_logs.credits 的 NOT NULL 约束，使整条记录写入失败。
     */
    private fun upstreamCredits(usage: JSONObject?): Double {
        if (usage == null) return 0.0
        for (key in CREDIT_KEYS) {
            val value = usage.optDouble(key, Double.NaN)
            if (value.isFinite() && value >= 0.0) return value
        }
        return 0.0
    }

    private fun json(output: OutputStream, status: Int, data: JSONObject) {
        val bytes = data.toString().toByteArray(StandardCharsets.UTF_8)
        writeHeaders(output, status, "application/json; charset=utf-8", bytes.size)
        output.write(bytes)
        output.flush()
    }

    private fun writeSseHeaders(output: OutputStream) {
        val text = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\n" +
            "Cache-Control: no-cache\r\nX-Accel-Buffering: no\r\nConnection: close\r\nAccess-Control-Allow-Origin: *\r\n\r\n"
        writeUtf8(output, text)
    }

    private fun writeHeaders(output: OutputStream, status: Int, contentType: String, length: Int) {
        val text = "HTTP/1.1 $status ${reason(status)}\r\nContent-Type: $contentType\r\nContent-Length: $length\r\n" +
            "Connection: close\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: Authorization, X-Api-Key, Content-Type, Anthropic-Version\r\n" +
            "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n\r\n"
        writeUtf8(output, text)
    }

    private fun writeUtf8(output: OutputStream, text: String) {
        output.write(text.toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    private fun readAsciiLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>(128)
        while (bytes.size <= MAX_HEADER_LINE) {
            val value = input.read()
            if (value < 0) return if (bytes.isEmpty()) null else String(bytes.toByteArray(), StandardCharsets.ISO_8859_1)
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes.add(value.toByte())
        }
        if (bytes.size > MAX_HEADER_LINE) throw ClientError("HTTP header line too long")
        return String(bytes.toByteArray(), StandardCharsets.ISO_8859_1)
    }

    private fun apiError(message: String, type: String): JSONObject = JSONObject().put("error",
        JSONObject().put("message", message).put("type", type).put("code", type))

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val left = a.toByteArray(StandardCharsets.UTF_8)
        val right = b.toByteArray(StandardCharsets.UTF_8)
        var diff = left.size xor right.size
        val length = maxOf(left.size, right.size)
        for (i in 0 until length) diff = diff or ((if (i < left.size) left[i].toInt() else 0) xor (if (i < right.size) right[i].toInt() else 0))
        return diff == 0
    }

    private fun isDisconnect(error: Throwable): Boolean {
        val message = error.message.orEmpty().lowercase(Locale.US)
        return message.contains("broken pipe") || message.contains("connection reset") || message.contains("socket closed")
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"
        413 -> "Payload Too Large"; 429 -> "Too Many Requests"; 500 -> "Internal Server Error"; 502 -> "Bad Gateway"
        503 -> "Service Unavailable"; 504 -> "Gateway Timeout"; else -> "Error"
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, ApiHostService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("WorkBuddy2API Native")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "停止", stop)
            .setOngoing(true)
            // CATEGORY_SERVICE：告诉系统这是长期运行的服务类通知，配合前台服务可降低被清理概率。
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setPriority(Notification.PRIORITY_DEFAULT)
            // Android 12+ 前台服务通知默认可延迟 10 秒显示；IMMEDIATE 让它立刻出现，
            // 避免"服务已在跑但通知栏迟迟看不到"而被部分 ROM 判定为隐形后台服务。
            .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            // 已存在时不覆盖：用户手动调整过的渠道设置（如重要性/声音）必须尊重，
            // 反复 createNotificationChannel 虽不会重置用户设置，但这里提前返回语义更清晰。
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                // 用 DEFAULT 而不是 LOW：ColorOS 会把低重要性通知视作可降级，进而弱化对应前台服务的优先级；
                // 开发者文档也建议前台服务用不低于 DEFAULT，避免被系统折叠成静默通知。
                val channel = NotificationChannel(CHANNEL_ID, "本地原生 API", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "常驻运行本地 API 网关，供其它应用调用"
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                manager.createNotificationChannel(channel)
            } else if (existing.importance == NotificationManager.IMPORTANCE_NONE) {
                // 渠道被关闭 = 前台服务通知不可见，系统可能据此弱化甚至清理服务，
                // 这正是"通知权限明明开了却还是被杀"的常见原因。不擅自改回用户设置，
                // 只记录日志，由界面侧引导用户重新打开（见 MainActivity 的保活自检）。
                Log.w(TAG, "notification channel disabled by user; foreground service may be deprioritized")
            }
        }
    }

    private fun notifyState(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).putExtra("message", text).putExtra("running", running))
    }

    private fun runMaintenanceTick() {
        runCatching {
            // 冻结自检：服务一旦被冻结，本循环也会停摆；解冻后（例如新请求唤醒进程）第一件事
            // 就是确保唤醒锁重新持有——系统在冻结/回收时可能释放 wakelock，丢失即再次暴露在
            // Cached App Freezer 之下，60 秒内必须补上。带超时的锁过期后 isHeld 变 false，这里自动续期。
            acquireWakeLock()
            val settings = NativeCore.settings(this)
            runAutomaticCheckin(settings)
            runAutomaticCredits(settings)
            runAutomaticModels(settings)
            runAutomaticUsageCleanup(settings)
            prewarmModels()
        }.onFailure { Log.w(TAG, "maintenance tick failed", it) }
    }

    /**
     * 每日自动清理过期使用记录。
     *
     * 过去清理只有界面上的两个手动按钮，绝大多数用户不会去点，导致记录无限累积——
     * 这是"装完才 1MB、用一阵子变成近 200MB"的直接原因。这里改为每天自动执行一次：
     * 按 settings.usage_retention_days（默认 30 天）删除过期记录，并在确实删到东西时
     * VACUUM 回收文件空间（否则 SQLite 只标记空闲页，系统里看到的体积不会变小）。
     */
    private fun runAutomaticUsageCleanup(settings: JSONObject) {
        val days = settings.optString("usage_retention_days", "30").toIntOrNull()?.coerceIn(1, 3650) ?: 30
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val prefs = getSharedPreferences("native_service", MODE_PRIVATE)
        if (prefs.getString("last_usage_cleanup", null) == today) return
        runCatching {
            val removed = NativeCore.cleanupUsage(this, days)
            // VACUUM 会重写整个库并长时间持有数据库锁，期间新记录写入会被阻塞，
            // 因此只在真的删掉记录、且空闲空间可观时才做，避免每天无谓地重写一次数据库。
            if (removed > 0) {
                val free = runCatching { NativeCore.storageInfo(this).optLong("free_bytes") }.getOrDefault(0L)
                if (free > VACUUM_MIN_FREE_BYTES) {
                    workers.execute { runCatching { NativeCore.vacuum(this) } }
                    Log.i(TAG, "usage vacuum scheduled, free=${free / 1024}KB")
                }
            }
            prefs.edit().putString("last_usage_cleanup", today).apply()
            Log.i(TAG, "usage cleanup done: removed=$removed retention=${days}d")
        }.onFailure { Log.w(TAG, "usage cleanup failed", it) }
        // WebView 磁盘缓存同样需要定期回收：登录页资源（JS/图片/字体）体量可观，
        // 且 WebView 自身不设容量上限。只在缓存体积确实可观时才动，避免每次白跑一遍扫描。
        runCatching {
            val breakdown = NativeCore.storageBreakdown(this)
            val cacheBytes = breakdown.optLong("webview") + breakdown.optLong("cache")
            if (cacheBytes > WEBVIEW_CACHE_CLEAN_THRESHOLD) {
                NativeCore.clearWebViewCache(this)
                Log.i(TAG, "webview cache cleared, was=${cacheBytes / 1024}KB")
            }
        }.onFailure { Log.w(TAG, "webview cache cleanup failed", it) }
    }

    /** 模型目录过期即后台预热，保证客户端 /v1/models 永远命中缓存，请求路径零网络等待。 */
    private fun prewarmModels() {
        runCatching {
            val accounts = NativeCore.listAccounts(this)
            val regions = (0 until accounts.length()).mapNotNull {
                accounts.optJSONObject(it)?.takeIf { item -> item.optBoolean("enabled", true) }
                    ?.let { item -> AccountRegion.from(item.optString("region")) }
            }.distinct()
            regions.forEach { NativeCore.prewarmModels(this, it) }
        }.onFailure { Log.w(TAG, "model prewarm failed", it) }
    }

    private fun runAutomaticCheckin(settings: JSONObject) {
        val now = java.util.Calendar.getInstance()
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val hours = settings.optString("checkin_hours", "9,21").split(',').mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 0..23 }.distinct().sorted()
        val slotHour = hours.lastOrNull { now.get(java.util.Calendar.HOUR_OF_DAY) >= it } ?: return
        val slot = "%s:%02d".format(Locale.US, today, slotHour)
        val prefs = getSharedPreferences("maintenance", Context.MODE_PRIVATE)
        val accounts = NativeCore.listAccounts(this)
        for (i in 0 until accounts.length()) {
            val account = accounts.getJSONObject(i)
            if (!account.optBoolean("enabled", true) || account.optString("last_checkin_date") == today ||
                AccountRegion.from(account.optString("region")) == AccountRegion.INTERNATIONAL) continue
            val accountKey = account.optString("account_key")
            val prefKey = "checkin_attempt_${NativeStore.sha256(accountKey).take(24)}"
            if (prefs.getString(prefKey, "") == slot) continue
            prefs.edit().putString(prefKey, slot).apply()
            runCatching { NativeCore.checkIn(this, accountKey) }
                .onSuccess { Log.i(TAG, "automatic check-in succeeded: ${account.optString("region")}") }
                .onFailure { Log.w(TAG, "automatic check-in failed: ${account.optString("region")}", it) }
        }
    }

    private fun runAutomaticCredits(settings: JSONObject) {
        val intervalMs = settings.optLong("credit_refresh_min", 30).coerceIn(1, 1440) * 60_000L
        val prefs = getSharedPreferences("maintenance", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("credits_last_attempt_ms", 0L) < intervalMs) return
        prefs.edit().putLong("credits_last_attempt_ms", now).apply()
        runCatching { NativeCore.refreshAllCredits(this) }
            .onFailure { Log.w(TAG, "automatic credits refresh failed", it) }
    }

    private fun runAutomaticModels(settings: JSONObject) {
        val hour = settings.optInt("model_refresh_hour", 6).coerceIn(0, 23)
        val now = java.util.Calendar.getInstance()
        if (now.get(java.util.Calendar.HOUR_OF_DAY) < hour) return
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val prefs = getSharedPreferences("maintenance", Context.MODE_PRIVATE)
        val accounts = NativeCore.listAccounts(this)
        val regions = (0 until accounts.length()).mapNotNull {
            accounts.optJSONObject(it)?.takeIf { item -> item.optBoolean("enabled", true) }
                ?.let { item -> AccountRegion.from(item.optString("region")) }
        }.distinct()
        regions.forEach { region ->
            val successKey = "models_success_${region.id}"
            val attemptKey = "models_attempt_${region.id}"
            if (prefs.getString(successKey, "") == today) return@forEach
            val nowMs = System.currentTimeMillis()
            if (nowMs - prefs.getLong(attemptKey, 0L) < 30 * 60_000L) return@forEach
            prefs.edit().putLong(attemptKey, nowMs).apply()
            runCatching { NativeCore.refreshModels(this, region) }
                .onSuccess { prefs.edit().putString(successKey, today).apply() }
                .onFailure { Log.w(TAG, "automatic models refresh failed: ${region.id}", it) }
        }
    }

    private fun stopNow() {
        running = false
        markShouldRun(applicationContext, false)
        cancelWatchdog()
        runCatching { server?.close() }
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).putExtra("running", false).putExtra("message", "本地 API 已停止"))
    }

    /** 用户主动停止后必须撤掉看门狗，否则 15 分钟后会被闹钟莫名拉起来（"关了又自己开了"）。 */
    private fun cancelWatchdog() {
        runCatching {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(watchdogIntent())
            Log.i(TAG, "watchdog cancelled")
        }.onFailure { Log.w(TAG, "watchdog cancel failed", it) }
    }

    override fun onDestroy() {
        running = false
        runCatching { server?.close() }
        releaseWakeLock()
        workers.shutdownNow()
        maintenance.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private enum class Protocol(val wireName: String) { CHAT("chat"), ANTHROPIC("anthropic"), RESPONSES("responses") }
    private class ClientError(message: String) : RuntimeException(message)

    companion object {
        const val PORT = 8788
        const val ACTION_STOP = "com.joy4fire.workbuddy2api.STOP"
        const val ACTION_STATE = "com.joy4fire.workbuddy2api.STATE"
        /** 看门狗闹钟动作：被它唤醒时只需确认前台服务与唤醒锁仍在。 */
        const val ACTION_WATCHDOG = "com.joy4fire.workbuddy2api.WATCHDOG"
        /** 看门狗轮询间隔 15 分钟：再密会被 ColorOS 判定异常唤醒，反而拦截得更狠。 */
        private const val WATCHDOG_INTERVAL_MS = 15 * 60_000L
        /** 唤醒锁超时 10 分钟：短于 maintenance 续期周期上限，过期后由 tick 自动续上。 */
        private const val WAKELOCK_TIMEOUT_MS = 10 * 60_000L
        /** 空闲空间超过此值才值得做 VACUUM：小于 2MB 时重写整库的代价超过回收收益。 */
        private const val VACUUM_MIN_FREE_BYTES = 2L * 1024 * 1024
        /** cache 类目录超过此值才清理 WebView 缓存，避免每天无谓地扫描+清理。 */
        private const val WEBVIEW_CACHE_CLEAN_THRESHOLD = 8L * 1024 * 1024
        private const val WATCHDOG_REQUEST = 8789
        @Volatile var running = false

        /** 用户意图：true = 用户希望服务处于运行状态。
         *  持久化它有两个用途：① 开机自启时判断该不该拉起（避免用户明明关过又被拉起）；
         *  ② onTaskRemoved 重启前校验，避免"关了又自己开"。 */
        private const val PREFS = "native_service"
        private const val KEY_SHOULD_RUN = "should_run"

        /** 记录/读取"用户是否希望服务运行"。启动服务时置 true，用户主动停止时置 false。 */
        fun markShouldRun(context: Context, value: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SHOULD_RUN, value).apply()
        }

        fun shouldRun(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SHOULD_RUN, false)

        /**
         * 通知渠道是否被用户关闭。前台服务通知不可见时，系统可能弱化甚至清理服务——
         * 这是"通知权限明明开着却仍被杀/被折叠"的常见盲区，界面侧据此给出重新开启的引导。
         */
        fun isChannelDisabled(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < 26) return false
            val channel = context.getSystemService(Context.NOTIFICATION_SERVICE)
                ?.let { it as? android.app.NotificationManager }
                ?.getNotificationChannel(CHANNEL_ID) ?: return false
            return channel.importance == android.app.NotificationManager.IMPORTANCE_NONE
        }
        private const val CHANNEL_ID = "workbuddy_native_api"
        private const val NOTIFICATION_ID = 8788
        private const val MAX_BODY_BYTES = 10L * 1024L * 1024L
        private const val MAX_HEADER_LINE = 16 * 1024
        private const val TAG = "ApiHostService"

        /**
         * 上游积分字段候选名（按优先级）。
         * `credit` 是腾讯 /v2/chat/completions 的实测字段名（Python 版同源解析），其余为上游改版预留。
         */
        private val CREDIT_KEYS = listOf("credit", "credits", "credit_cost", "credit_used", "used_credit", "total_credit", "cost", "points")
    }
}
