package com.joy4fire.workbuddy2api

import java.security.SecureRandom

/**
 * 官方 CLI 的 UUID 生成器 1:1 复刻。
 *
 * 为什么必须单独实现：官方 CLI 在不同字段上用了【两种不同的 UUID 版本】，
 * 这一点极易被漏掉，而版本位是上游可直接观测的硬特征：
 *
 *   - X-Conversation-ID            → UUIDv4（带连字符，36 字符）
 *   - X-Conversation-Message-ID    → UUIDv7（去连字符，32 位 hex）
 *   - X-Request-ID                 → 与 Message-ID 同值（恒等）
 *
 * 证据（官方包 @tencent-ai/codebuddy-code v2.150.0）：
 *
 * 1) 会话 ID 走 v4 —— dist/codebuddy.js 模块 22075（SessionImpl）：
 *      let el = es(66657);
 *      this.id = (0, el.generateUUUID)();
 *    而模块 66657 的 generateUUUID 实现：
 *      if (typeof crypto.randomUUID === "function") return crypto.randomUUID(...)
 *      回退分支: ea[6] = (ea[6] & 15) | 0x64   // 0x64 → 版本 nibble = 4
 *                ea[8] = (ea[8] & 63) | 0x80   // variant = 10xx
 *      → 即标准 UUIDv4，输出 8-4-4-4-12 带连字符小写。
 *
 * 2) 消息 ID 走 v7 —— 别名 "nH": () => uuidv7（模块 12125）。
 *    会话头注入点：messageId = store?.messageId ?? uuid().replace(/-/g, "")
 *    → v7 且【去掉连字符】。
 *
 * 如果我们在 Android 上随手用 UUID.randomUUID() 填这两个字段，会同时踩两个坑：
 *   a) 第 13 位版本号：官方消息 ID 恒为 '7'，v4 恒为 '4' —— 一眼可辨；
 *   b) v7 前 48 位是毫秒时间戳，真实 CLI 的消息 ID 是单调递增的。
 *      随机 UUID 在同一会话内顺序杂乱，属可统计检测的异常。
 *
 * 实现说明：Android 的 JDK 不含 UUIDv7（那是 Java 21+ 的 API），
 * 故按官方 fromFieldsV7 的位布局自行构造，纯位运算、零新增依赖。
 */
object UuidFactory {

    private val random = SecureRandom()

    /** v7 生成器的现场状态：偏置时间戳 + 同毫秒内 42 位计数器。 */
    @Volatile
    private var timestampBiased: Long = 0L

    @Volatile
    private var counter: Long = 0L

    /** 官方 V7Generator 的计数器上限（counter > 0x3ffffffffff 时进位时间戳）。 */
    private const val COUNTER_MAX = 0x3FFFFFFFFFFL

    /** 官方 rollbackAllowance 默认值（10 秒），用于容忍时钟回拨。 */
    private const val ROLLBACK_ALLOWANCE = 10_000L

    /**
     * 生成带连字符的标准 UUIDv4（36 字符，小写）。
     *
     * 对应官方字段：X-Conversation-ID（= SessionImpl.id）。
     */
    fun v4(): String = java.util.UUID.randomUUID().toString()

    /**
     * 生成 32 位无连字符的 UUIDv7（小写 hex）。
     *
     * 位布局严格对齐官方 UUID.fromFieldsV7，注意官方的参数映射不是直觉的
     * 「高位截断」，而是把 42 位 counter 拆成 12 位 + 30 位两段：
     *
     *   官方调用：
     *     fromFieldsV7(tsBiased - 1,
     *                  trunc(counter / 0x40000000),      // → randA，≤ 12 位
     *                  counter & (0x40000000 - 1),       // → randB，30 位
     *                  random.nextUint32())              // → randC，32 位
     *
     *   字段写入：
     *     bytes[0..5]  = ts（48 位大端，即 tsBiased-1）
     *     bytes[6]     = 0x70 | (randA >>> 8)          → 版本 nibble = 7
     *     bytes[7]     = randA & 0xff
     *     bytes[8]     = 0x80 | (randB >>> 24)         → variant = 10xx
     *     bytes[9..11] = randB（低 24 位）
     *     bytes[12..15]= randC
     *
     * 对应官方字段：X-Conversation-Message-ID 与 X-Request-ID（两者同值且恒等）。
     */
    @Synchronized
    fun v7Hex(): String {
        val ts = System.currentTimeMillis()

        if (timestampBiased == 0L || ts > timestampBiased) {
            // 新毫秒（或首次）：官方 resetCounter() 用随机起点，避免固定的可预测初值。
            timestampBiased = ts
            counter = resetCounter()
        } else {
            // 同毫秒或时钟回拨：自增计数器。
            // 官方语义：仅在回拨幅度超过 rollbackAllowance 时才放弃生成，
            // 这里简化为始终递增计数器，保证单调性（对上游而言更「像」真实 CLI）。
            counter++
            if (counter > COUNTER_MAX) {
                timestampBiased++
                counter = resetCounter()
            }
        }

        val tsField = timestampBiased - 1
        val randA = counter / 0x40000000L           // 高段，≤ 12 位
        val randB = counter and 0x3FFFFFFFL         // 低 30 位
        val randC = random.nextInt().toLong() and 0xFFFFFFFFL  // 32 位随机

        val sb = StringBuilder(32)

        // bytes[0..5]：48 位时间戳，大端
        val t = tsField and 0xFFFFFFFFFFFFL
        sb.append(hex2((t ushr 40) and 0xFFL))
        sb.append(hex2((t ushr 32) and 0xFFL))
        sb.append(hex2((t ushr 24) and 0xFFL))
        sb.append(hex2((t ushr 16) and 0xFFL))
        sb.append(hex2((t ushr 8) and 0xFFL))
        sb.append(hex2(t and 0xFFL))

        // bytes[6..7]：版本位 0x7 加上 randA 的 12 位
        sb.append(hex2(0x70L or ((randA ushr 8) and 0x0FL)))
        sb.append(hex2(randA and 0xFFL))

        // bytes[8..11]：variant 10xx 加上 randB 的 30 位
        sb.append(hex2(0x80L or ((randB ushr 24) and 0x3FL)))
        sb.append(hex2((randB ushr 16) and 0xFFL))
        sb.append(hex2((randB ushr 8) and 0xFFL))
        sb.append(hex2(randB and 0xFFL))

        // bytes[12..15]：randC 的 32 位
        sb.append(hex2((randC ushr 24) and 0xFFL))
        sb.append(hex2((randC ushr 16) and 0xFFL))
        sb.append(hex2((randC ushr 8) and 0xFFL))
        sb.append(hex2(randC and 0xFFL))

        return sb.toString()
    }

    /**
     * 对应官方 V7Generator.resetCounter()：
     *   counter = random.nextUint32() * 1024 + (random.nextUint32() & 1023)
     *
     * 注意官方用的是无符号 32 位，故这里显式屏蔽符号位，避免 Kotlin 的
     * Int 负数污染 counter 的高位。
     */
    private fun resetCounter(): Long {
        val hi = random.nextInt().toLong() and 0xFFFFFFFFL
        val lo = random.nextInt().toLong() and 0x3FFL
        return hi * 1024L + lo
    }

    private fun hex2(v: Long): String {
        val s = java.lang.Long.toHexString(v and 0xFF)
        return if (s.length == 1) "0$s" else s
    }
}
