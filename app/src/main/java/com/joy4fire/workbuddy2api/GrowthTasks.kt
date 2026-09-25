package com.joy4fire.workbuddy2api

import org.json.JSONArray
import org.json.JSONObject

/**
 * 成长中心任务编排 —— 移植自 WorkBuddy 1.2.7（a.n2 / a.C0049i2 / a.r2）。
 *
 * 结构说明：
 *   - [GrowthApi] 负责传输与身份；
 *   - 本文件负责业务动作（领任务 / 做任务 / 领奖 / 签到辅助功能）；
 *   - 每个公开方法都以 accountKey 为入口，内部完成「拉状态 → 决策 → 执行 → 回读校验」。
 *
 * 所有动作返回统一的进度回调 [Reporter]，让 UI 能实时显示当前在做什么
 * （对应 1.2.7 的 `l.p` / `C0073q1` 回调接口）。
 */
class GrowthTasks(private val api: GrowthApi) {

    /** 进度回调：UI 传进来即可实时展示（1.2.7 的 `a(String)` 语义）。 */
    fun interface Reporter {
        fun report(message: String)
    }

    // ================================================================ 数据模型

    /** 成长任务（1.2.7 `a.k2`，字段与状态机语义逐项保留）。 */
    data class Task(
        val code: String,
        val title: String,
        val description: String,
        val credit: Long,
        val energy: Long,
        val target: Long,
        val current: Long,
        val acceptStatus: String,
        val locked: Boolean,
        val claimed: Boolean,
        val jumpUrl: String
    ) {
        /** 进度达标且未领取 → 可领奖（1.2.7 `k2.a()`）。 */
        val claimable: Boolean get() = !claimed && target > 0 && current >= target

        /** 已接受或已完成（1.2.7 `k2.b()`）。 */
        val accepted: Boolean get() = claimed || acceptStatus == "accepted" || acceptStatus == "completed"

        /** 展示用进度文本（1.2.7 `k2.c()`）。 */
        val progressText: String
            get() = when {
                target > 0 -> "$current/$target"
                claimed -> "已完成"
                else -> statusLabel
            }

        /** 上游 status 的中文说明。这些任务多数没有可查询进度，只能按状态展示。 */
        val statusLabel: String
            get() = when (acceptStatus.lowercase()) {
                "available", "pending" -> "可执行"
                "accepted" -> "进行中"
                "completed" -> "已完成待领"
                "claimed", "done" -> "已领取"
                "locked" -> "未解锁"
                "" -> "可执行"
                else -> acceptStatus
            }

        /** 奖励文本：真实接口未下发奖励数额时明说"奖励以客户端为准"，不显示 +0 制造误解。 */
        val rewardText: String
            get() = when {
                credit > 0 && energy > 0 -> "+$credit 分 / +$energy 能量"
                credit > 0 -> "+$credit 分"
                energy > 0 -> "+$energy 能量"
                else -> "奖励以官方客户端为准"
            }
    }

    // ================================================================ 任务列表

    /**
     * 拉取成长任务列表（1.2.7 `a.n2.A` → GET /v2/activity/growth/tasks）。
     *
     * 进度字段有两套（顶层 current/target 与 progress.current/target），
     * 1.2.7 的取值优先级是「progress 里有一项非零就用 progress」——
     * 因为顶层那两个字段在部分任务上是恒为 0 的占位值，直接用会显示成「0/0」。
     */
    fun list(accountKey: String): List<Task> {
        val root = api.plugin(accountKey, "/v2/activity/growth/tasks")
        val tasks = root.optJSONArray("tasks") ?: return emptyList()
        val result = ArrayList<Task>(tasks.length())
        for (i in 0 until tasks.length()) {
            val item = tasks.optJSONObject(i) ?: continue
            // 字段名以真机实测响应为准（见下方注释），两者都兼容：
            //   实际：{"task_id":1,"code":"first_chat","title":"...","description":"...",
            //          "level_name":"养虾尝试","status":"available","url":""}
            //   1.2.7 反编译里读到的是 task_code / accept_status / jump_url 命名，
            //   属另一组端点（开学季）的字段；这里两个名字都试，避免任一侧改名后再次错位。
            val code = item.optString("code").ifBlank { item.optString("task_code") }
            val status = item.optString("status").ifBlank { item.optString("accept_status") }
            // 进度可能挂在顶层，也可能挂在 progress 对象里；progress 非零时优先。
            var current = item.optLong("current", 0)
            var target = item.optLong("target", 0)
            item.optJSONObject("progress")?.let { progress ->
                val pc = progress.optLong("current", 0)
                val pt = progress.optLong("target", 0)
                if (pt > 0 || pc > 0) { current = pc; target = pt }
            }
            result.add(
                Task(
                    code = code,
                    title = item.optString("title"),
                    description = item.optString("description"),
                    credit = item.optLong("reward_credit", 0),
                    energy = item.optLong("reward_energy", 0),
                    target = target,
                    current = current,
                    acceptStatus = status,
                    locked = item.optBoolean("locked"),
                    claimed = status == "claimed" || status == "done",
                    jumpUrl = item.optString("url").ifBlank { item.optString("jump_url") }
                )
            )
        }
        return result
    }

    fun task(accountKey: String, code: String): Task? = list(accountKey).firstOrNull { it.code == code }

    /** 批量接受任务（1.2.7 `a.n2.a` → POST /v2/activity/growth/tasks/accept）。 */
    fun accept(accountKey: String, codes: List<String>) {
        if (codes.isEmpty()) return
        api.plugin(accountKey, "/v2/activity/growth/tasks/accept", "POST",
            JSONObject().put("task_codes", JSONArray(codes)))
    }

    // ================================================================ 领奖

    /**
     * 领取单个任务奖励（1.2.7 `a.n2.j` → POST /activity/growth/tasks/{code}/claim）。
     *
     * already_claimed 返回 (0,0) 而不是抛错——重跑时这不是异常状态，
     * 把它当错误会让「一键做完」在第二次执行时整体失败。
     */
    fun claim(accountKey: String, code: String): Pair<Long, Long> {
        val encoded = java.net.URLEncoder.encode(code, "UTF-8")
        val data = api.web(accountKey, "/activity/growth/tasks/$encoded/claim", "POST", JSONObject())
        if (data.optBoolean("already_claimed")) return 0L to 0L
        return data.optLong("credit", 0) to data.optLong("energy", 0)
    }

    /**
     * 领奖并轮询确认（1.2.7 `a.n2.T` + `O`）。
     *
     * 为什么要轮询：上游计分是异步落库的，claim 立刻回 「已达标的判定」
     * 但列表接口可能还是旧值。1.2.7 的做法是 3 次 × 2.5 秒，
     * 只要看到「可领或已领」就算成功，避免用户看到「上报成功但进度没动」而重复点击。
     */
    fun claimAfterConfirm(accountKey: String, code: String, reporter: Reporter? = null): Boolean {
        repeat(3) {
            api.sleep(2500)
            val current = runCatching { task(accountKey, code) }.getOrNull()
            if (current != null && (current.claimable || current.claimed)) return true
        }
        reporter?.report("$code 计分未落定，可稍后重试")
        return false
    }

    /** 领奖（开学季链路，1.2.7 `a.n2.O`）——返回本次发放的抽奖次数。 */
    fun claimSchoolTask(accountKey: String, code: String): Int {
        val data = api.plugin(accountKey, "/portal/activity/school/tasks/$code/claim", "POST", JSONObject())
        return data.optInt("chance_granted", 0)
    }

    /** 新手礼包（1.2.7 `a.n2.i`）。 */
    fun claimGift(accountKey: String): Long =
        api.plugin(accountKey, "/billing/meter/claim-gift", "POST", JSONObject()).optLong("credit", 0)

    /** 活动补偿（1.2.7 `a.n2.h`）。 */
    fun claimCompensation(accountKey: String): Long =
        api.plugin(accountKey, "/billing/meter/claim-compensation", "POST", JSONObject()).optLong("credit", 0)
}
