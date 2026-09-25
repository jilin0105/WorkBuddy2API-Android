package com.joy4fire.workbuddy2api

import java.util.Locale

/**
 * 展示用格式化工具 —— 从 MainActivity 抽出来，供 Compose 界面复用。
 *
 * 抽出来的原因：这些格式化原先散在 Activity 的私有方法里，Compose 界面拿不到；
 * 而它们承载了「读者能否一眼看懂数字」的关键，重复实现两份必然走样
 * （例如一个说 1.5 MB，一个说 1.50 MB）。
 */
object FormatKit {

    /** 字节数 → 人类可读（1024 进制，2 位小数到 MB，KB 取整）。 */
    fun bytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024)
        bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    /** Token 数 → 紧凑形式（万级以上折算，避免长数字撑破卡片）。 */
    fun tokens(value: Long): String = when {
        value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
        value >= 1_000 -> String.format(Locale.US, "%.1fk", value / 1_000.0)
        else -> value.toString()
    }

    /** 积分保留 1 位小数（上游给的是浮点，整数展示会丢精度）。 */
    fun credits(value: Double): String =
        if (value.isFinite()) String.format(Locale.US, "%.1f", value) else "—"

    /** UID 太长会把行挤爆，中段省略保留首尾。 */
    fun shortUid(uid: String): String =
        if (uid.length <= 12) uid else uid.take(6) + "…" + uid.takeLast(4)

    /**
     * 账号显示名：优先昵称，其次短 UID。
     * 很多账号没有昵称（只导入了 auth），此时显示空串会让整行看起来像坏掉了。
     */
    fun accountName(name: String?, uid: String?): String =
        name?.takeIf { it.isNotBlank() } ?: shortUid(uid.orEmpty()).ifBlank { "未命名账号" }

    /** 文件名安全化：去掉路径分隔符等非法字符，避免 SAF 拒绝或生成怪名。 */
    fun safeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_').take(40).ifBlank { "export" }
}
