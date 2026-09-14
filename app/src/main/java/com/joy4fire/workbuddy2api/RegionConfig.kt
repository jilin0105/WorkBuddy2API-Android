package com.joy4fire.workbuddy2api

/** Upstream editions supported by the native gateway. */
enum class AccountRegion(
    val id: String,
    val label: String,
    val backend: String,
    val defaultDomain: String
) {
    DOMESTIC("domestic", "国内版", "https://copilot.tencent.com", "www.codebuddy.cn"),
    INTERNATIONAL("international", "国际版", "https://www.codebuddy.ai", "www.codebuddy.ai");

    companion object {
        fun from(value: String?): AccountRegion = entries.firstOrNull {
            it.id.equals(value?.trim(), ignoreCase = true)
        } ?: DOMESTIC

        fun fromStrict(value: String?): AccountRegion = entries.firstOrNull {
            it.id.equals(value?.trim(), ignoreCase = true)
        } ?: throw IllegalArgumentException("版本必须是 domestic 或 international")

        fun infer(explicit: String?, domain: String?): AccountRegion {
            if (!explicit.isNullOrBlank()) return from(explicit)
            val host = domain.orEmpty().lowercase()
            return if (host.contains("codebuddy.ai") || host.contains("workbuddy.ai")) INTERNATIONAL else DOMESTIC
        }
    }
}

data class OAuthSession(val region: AccountRegion, val state: String, val authUrl: String)
