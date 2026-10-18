package com.joy4fire.workbuddy2api

import android.content.Context

/**
 * 本地 API 监听端口的唯一真相源。
 *
 * 【为什么需要这个文件】
 *   旧实现把端口写成 `ApiHostService.PORT = 8788` 的编译期常量，散落在 8 处引用里
 *   （通知文案、悬浮窗、Base URL 拼接、设置页展示、MainActivity 的两处复制地址）。
 *   一旦要改成可配置，常量就必须消失 —— 否则"显示 8758、实际监听 8788"这类
 *   不一致会以最难查的方式出现：界面看起来对，客户端连不上。
 *
 *   因此这里提供统一的读写入口，所有需要端口的代码都走它，
 *   任何地方都不允许再出现字面量端口号。
 *
 * 【为什么默认值与 NativeStore.DEFAULT_SETTINGS 保持一致】
 *   两处必须同值。Registry 是写入白名单（未登记的键会被静默丢弃），
 *   这里是运行时兜底（设置还没落库、或库读取异常时的取值）。
 *   若不同值，会出现"首次启动监听 A、保存后监听 B"的跳变。
 *   单元层面无法强绑定二者，故在两处各写明约束。
 */
object ServerPort {

    /** 设置表里的键名。 */
    const val KEY = "server_port"

    /** 默认端口。必须与 NativeStore.DEFAULT_SETTINGS["server_port"] 相同。 */
    const val DEFAULT = 8758

    /**
     * 允许的端口下限。
     *
     * 1024 以下属于特权端口：Android 上非 root 应用 bind 会直接抛
     * Permission denied，与其让用户填 80 然后在启动时炸掉，不如在输入阶段就挡住。
     */
    const val MIN = 1024

    /** 上限 65535，端口字段的物理上限。 */
    const val MAX = 65535

    /**
     * 校验并规范化用户输入。
     *
     * 返回 null 表示非法输入（调用方据此拒绝提交并提示）。
     * 之所以单独做一次 trim：用户从别处粘贴" 8758"这类带空白的值很常见，
     * 直接 toIntOrNull 会得到 null，误判成"填错了"。
     */
    fun normalize(raw: String?): Int? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        // 只接受纯数字：拒绝 "8758abc"、"+8758"、全角数字等 toIntOrNull 可能放过的形态。
        if (!text.all { it in '0'..'9' }) return null
        val value = text.toIntOrNull() ?: return null
        return value.takeIf { it in MIN..MAX }
    }

    /** 非法输入时的提示文案（UI 与保存失败共用一个口径）。 */
    fun invalidHint(): String = "端口需为 $MIN–$MAX 之间的整数"

    /**
     * 读取当前生效端口。
     *
     * 优先级：设置值 > 默认值。
     * 设置值非法（用户手工改过数据库、或旧版本遗留脏值）时回落到默认值，
     * 而不是抛异常 —— 端口读不出来不该让整个服务起不来。
     */
    fun current(context: Context): Int {
        val stored = runCatching { NativeCore.settings(context).optString(KEY) }.getOrNull()
        return normalize(stored) ?: DEFAULT
    }

    /**
     * 写入端口。
     *
     * 返回 true 表示已通过校验并提交写入；false 表示输入非法（未写入）。
     * 注意：这里不校验"端口是否被别的进程占用" —— 占用状态是随时变化的，
     * 唯一可靠的判定点是真正 bind 的那一刻，见 ApiHostService.serve() 的处理。
     */
    fun set(context: Context, raw: String?): Boolean {
        val value = normalize(raw) ?: return false
        runCatching { NativeCore.saveSettings(context, org.json.JSONObject().put(KEY, value.toString())) }
        return true
    }

    /** 拼出 OpenAI 兼容的 Base URL（界面展示与一键复制共用，避免各处手写）。 */
    fun baseUrl(port: Int = DEFAULT): String = "http://127.0.0.1:$port/v1"
}
