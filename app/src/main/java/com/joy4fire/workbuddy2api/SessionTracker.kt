package com.joy4fire.workbuddy2api

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * 会话状态跟踪器 —— 让无状态反代在会话头上「像有状态的官方 CLI」。
 *
 * 为什么这是防风控最关键的一层（比 UA 更关键）：
 * 官方 CLI 的 X-Conversation-ID 来自 SessionImpl 实例，是【进程内长期复用的对象】。
 * 一个真实会话会挂几十条消息、持续几分钟到几小时、消息 ID 随时间单调递增。
 *
 * 而我们此前是无状态反代：每条请求都没有会话上下文。上游只要做一件极简单的事——
 * 统计「同一 Conversation-ID 下的消息数 / 消息间隔」——就能识别：
 * 一个从不携带会话上下文的 CLI 客户端，配上高频调用，正是 API 转售的典型特征。
 *
 * 会话生命周期（严格对齐官方 cell framework 的 session 语义）：
 *
 *   1) 默认 maxAge = 86,400,000ms（24 小时）。
 *      证据：dist/codebuddy.js 模块 83902 的默认 config：
 *        session: { autoCommit: true, maxAge: 864e5, sessionIdKey: "cell:sessionId", ... }
 *
 *   2) 但是【滑动过期】而非硬过期 —— 官方 SessionStrategyImpl.shouldSaveSession：
 *        if (sessionOptions.renew === true) {
 *            const {expire, maxAge} = session
 *            if (expire && maxAge && expire - Date.now() < maxAge / 2) return true
 *        }
 *      即剩余寿命降到一半（12 小时）以下时会重新 commit 续期 →
 *      持续使用的会话可以远超 24 小时存活，24h 实为【闲置上限】。
 *
 *    故本实现的轮换条件是「闲置超时」，而不是「创建满 24 小时」。
 *
 *   3) 换账号必须换会话 ID。这是硬红线：两个账号共享同一个 Conversation-ID，
 *      上游一关联即锁死。故状态按 accountKey 分桶。
 */
object SessionTracker {

    private const val PREFS = "cli_session"

    /** 官方默认 maxAge：24 小时（模块 83902）。 */
    private const val MAX_AGE_MS = 86_400_000L

    /**
     * 闲置轮换阈值。
     *
     * 取官方 maxAge（24h）作为闲置上限：一旦闲置超过 24 小时，官方那边会话也已
     * 判定过期（valid() 返回 false，会 create 新会话），故我们同步开启新会话。
     */
    private const val IDLE_ROTATE_MS = MAX_AGE_MS

    /**
     * 单会话最大消息数上限。
     *
     * 官方没有这个硬限制（会话可以无限长），但一个「消息数上千、间隔几百毫秒」的
     * 会话本身就是异常——真实用户不会这么用。超过后主动开新会话，让节奏更自然。
     * 取 200：远超正常人手交互量，但能挡住机器式的高频堆积。
     */
    private const val MAX_MESSAGES_PER_SESSION = 200

    /** 会话状态：一个 accountKey 对应一份。 */
    data class Session(
        val conversationId: String,
        val startedAt: Long,
        var lastActiveAt: Long,
        var messageCount: Int
    )

    private val cache = ConcurrentHashMap<String, Session>()

    /**
     * 取得（或创建）指定账号当前应使用的会话。
     *
     * 轮换条件（任一命中即开新会话）：
     *   1) 该账号还没有会话；
     *   2) 闲置时间超过 24 小时（对应官方会话过期语义）；
     *   3) 当前会话消息数超过上限。
     *
     * @param context 用于持久化，进程重启后会话 ID 不丢（否则每次重启都换 ID，更可疑）
     * @param accountKey 账号唯一键 —— 不同账号绝不共享会话 ID
     */
    @Synchronized
    fun sessionFor(context: Context, accountKey: String): Session {
        val now = System.currentTimeMillis()
        val existing = cache[accountKey] ?: restore(context, accountKey)

        if (existing != null &&
            now - existing.lastActiveAt < IDLE_ROTATE_MS &&
            existing.messageCount < MAX_MESSAGES_PER_SESSION
        ) {
            existing.lastActiveAt = now
            existing.messageCount++
            persist(context, accountKey, existing)
            return existing
        }

        // 需要开新会话：沿用官方语义 —— 新会话 = 新的 UUIDv4 会话 ID
        val fresh = Session(
            conversationId = UuidFactory.v4(),
            startedAt = now,
            lastActiveAt = now,
            messageCount = 1
        )
        cache[accountKey] = fresh
        persist(context, accountKey, fresh)
        return fresh
    }

    /**
     * 为一次请求生成消息级标识。
     *
     * 官方注入点：
     *   messageId = store?.messageId ?? uuid().replace(/-/g, "")
     * 其中 uuid() 即 v7 生成器（"nH": () => uuidv7），故为 32 位无连字符 hex。
     * X-Request-ID 与 X-Conversation-Message-ID 取同一个值（官方恒等）。
     */
    fun newMessageId(): String = UuidFactory.v7Hex()

    private fun prefKey(accountKey: String) = "sess_$accountKey"

    private fun persist(context: Context, accountKey: String, session: Session) {
        // 存成单行字符串，避免多条 prefs 写入不一致
        val payload = listOf(
            session.conversationId,
            session.startedAt.toString(),
            session.lastActiveAt.toString(),
            session.messageCount.toString()
        ).joinToString("|")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(prefKey(accountKey), payload).apply()
    }

    private fun restore(context: Context, accountKey: String): Session? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(prefKey(accountKey), null) ?: return null
        val parts = raw.split("|")
        if (parts.size != 4) return null
        return try {
            Session(
                conversationId = parts[0],
                startedAt = parts[1].toLong(),
                lastActiveAt = parts[2].toLong(),
                messageCount = parts[3].toInt()
            ).also { cache[accountKey] = it }
        } catch (e: NumberFormatException) {
            null
        }
    }
}
