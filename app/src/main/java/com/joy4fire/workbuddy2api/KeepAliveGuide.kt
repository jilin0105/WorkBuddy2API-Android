package com.joy4fire.workbuddy2api

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import java.util.Locale

/**
 * 后台保活引导 —— 从 MainActivity 抽出，供 Compose 界面复用。
 *
 * 为什么必须按厂商分开：各 ROM 对后台应用有独立于 AOSP「电池优化白名单」的管控层，
 * 且入口藏在设置深处（ColorOS 尤其激进：自启动、后台运行、耗电管理三处都要放开，
 * 缺一项锁屏后就会被回收）。统一的「请把本应用加入白名单」提示对用户没有任何帮助，
 * 必须给出「在设置里的哪一层、叫什么名字」。
 */
object KeepAliveGuide {

    /** 保活引导步骤。key 决定跳转目标，title/desc 是展示文案。 */
    data class Step(val key: String, val title: String, val desc: String)

    /** 识别当前 ROM 厂商。 */
    fun vendor(): String {
        val brand = (Build.BRAND + " " + Build.MANUFACTURER).lowercase(Locale.US)
        return when {
            brand.contains("oneplus") || brand.contains("oppo") || brand.contains("realme") -> "coloros"
            brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") -> "miui"
            brand.contains("huawei") || brand.contains("honor") -> "harmony"
            brand.contains("vivo") || brand.contains("iqoo") -> "funtouch"
            brand.contains("samsung") -> "oneui"
            else -> "generic"
        }
    }

    fun vendorLabel(): String = when (vendor()) {
        "coloros" -> "ColorOS / 一加"
        "miui" -> "MIUI / 红米"
        "harmony" -> "HarmonyOS / 华为"
        "funtouch" -> "OriginOS / vivo"
        "oneui" -> "One UI / 三星"
        else -> "当前设备"
    }

    /** 各厂商的保活步骤清单。desc 写清"在设置里的哪一层"，降低查找成本。 */
    fun steps(): List<Step> = when (vendor()) {
        "coloros" -> listOf(
            Step("autostart", "允许自启动", "设置 → 应用 → 自启动管理（或 手机管家 → 权限隐私 → 自启动）"),
            Step("background", "允许后台运行", "设置 → 应用 → 应用管理 → 本应用 → 耗电管理 → 允许后台运行"),
            Step("battery", "耗电管理设为「不限制」", "设置 → 应用 → 本应用 → 耗电管理 → 允许完全后台行为"),
            Step("lock", "在最近任务中加锁", "打开最近任务 → 下拉本应用卡片 → 点锁图标固定，避免一键清理时被清掉"),
            Step("battery_optimization", "忽略电池优化", "设置 → 电池 → 更多设置 → 电池优化 → 本应用 → 不优化")
        )
        "miui" -> listOf(
            Step("autostart", "开启自启动", "设置 → 应用设置 → 应用管理 → 本应用 → 自启动"),
            Step("battery", "省电策略设为「无限制」", "设置 → 应用设置 → 本应用 → 省电策略"),
            Step("lock", "在最近任务中加锁", "最近任务 → 长按本应用卡片 → 点锁图标"),
            Step("battery_optimization", "忽略电池优化", "设置 → 应用设置 → 特殊权限 → 电池优化 → 本应用")
        )
        "harmony" -> listOf(
            Step("autostart", "允许自启动", "设置 → 应用 → 应用启动管理 → 本应用 → 手动管理（三项全开）"),
            Step("battery", "允许后台活动", "设置 → 电池 → 更多电池设置 → 应用启动管理"),
            Step("lock", "锁定后台", "最近任务 → 下拉本应用卡片 → 点锁图标"),
            Step("battery_optimization", "忽略电池优化", "设置 → 应用 → 特殊访问权限 → 电池优化")
        )
        else -> listOf(
            Step("battery_optimization", "忽略电池优化", "设置 → 电池 → 电池优化 → 本应用 → 不优化"),
            Step("background", "允许后台活动", "设置 → 应用 → 本应用 → 电池 → 允许后台活动"),
            Step("lock", "在最近任务中加锁", "最近任务 → 本应用卡片 → 点锁图标固定")
        )
    }

    /**
     * 跳到对应的系统设置页。
     *
     * 厂商设置页的 component 名随 ROM 版本变化且不公开承诺稳定，因此用
     * 「候选列表 + 逐个尝试」，全部失败则回落本应用详情页 —— 保证任何设备上
     * 点下去都有反馈，不会静默无响应（原实现即如此，此处保持）。
     */
    fun open(context: Context, key: String) {
        if (key == "battery_optimization") {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(context.packageName)) return
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                )
            }.onFailure {
                runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    .onFailure { openAppDetails(context) }
            }
            return
        }
        // 「最近任务加锁」没有任何可跳转的公开入口，只能靠文字引导。
        if (key == "lock") return

        val candidates = when (key) {
            "autostart" -> when (vendor()) {
                "coloros" -> listOf(
                    "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity",
                    "com.oplus.safecenter/com.oplus.safecenter.permission.startup.StartupAppListActivity",
                    "com.coloros.safecenter/com.coloros.safecenter.startupapp.StartupAppListActivity"
                )
                "miui" -> listOf("com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity")
                "harmony" -> listOf(
                    "com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity",
                    "com.huawei.systemmanager/.appcontrol.activity.StartupAppControlActivity"
                )
                "funtouch" -> listOf("com.vivo.permissionmanager/.activity.BgStartUpManagerActivity")
                else -> emptyList()
            }
            "background" -> when (vendor()) {
                "coloros" -> listOf(
                    "com.oplus.battery/com.oplus.powermanager.fuelgaue.PowerUsageModelActivity",
                    "com.coloros.oppoguardelf/com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"
                )
                else -> emptyList()
            }
            "battery" -> when (vendor()) {
                "coloros" -> listOf(
                    "com.oplus.battery/com.oplus.powermanager.fuelgaue.PowerUsageModelActivity",
                    "com.coloros.oppoguardelf/com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"
                )
                "miui" -> listOf("com.miui.powerkeeper/com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                else -> emptyList()
            }
            else -> emptyList()
        }
        val intents = candidates.mapNotNull { ComponentName.unflattenFromString(it) }.map { Intent().setComponent(it) }
        intents.forEach { if (runCatching { context.startActivity(it) }.isSuccess) return }
        openAppDetails(context)
    }

    fun openAppDetails(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            )
        }
    }

    /**
     * 能自动判定的项做成检查清单；判定不了的项如实说明「系统未开放查询」。
     *
     * 精确闹钟单独强调：被关掉后看门狗只能降级为非精确闹钟，Doze 下可能延迟数小时，
     * 表现为「服务被杀后久久不恢复」——这是用户很难自行联想到的一项。
     *
     * @return (可判定项文本列表, 需手动确认的步骤)
     */
    fun statusCheck(context: Context, notificationsGranted: Boolean, channelDisabled: Boolean): Pair<List<String>, List<Step>> {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoringBattery = pm.isIgnoringBatteryOptimizations(context.packageName)
        val exactAlarm = if (Build.VERSION.SDK_INT >= 31) {
            runCatching {
                (context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager).canScheduleExactAlarms()
            }.getOrDefault(true)
        } else true
        val network = runCatching {
            (context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).activeNetwork != null
        }.getOrDefault(true)

        val lines = listOf(
            if (ignoringBattery) "✓ 已忽略电池优化" else "✗ 未加入电池优化白名单（必须开启）",
            if (notificationsGranted) "✓ 通知权限已授予" else "✗ 通知权限未授予，前台服务会被系统隐藏（必须开启）",
            if (!channelDisabled) "✓ 前台服务通知渠道正常" else "✗ 前台服务通知渠道被关闭，服务会被削弱",
            if (exactAlarm) "✓ 精确闹钟已允许（看门狗可准时唤醒）" else "✗ 精确闹钟被禁用，被杀后可能很久才恢复（必须开启）",
            if (network) "✓ 网络可用" else "✗ 当前无网络"
        )
        val manual = steps().filter { it.key == "autostart" || it.key == "background" || it.key == "battery" || it.key == "lock" }
        return lines to manual
    }

    /** 精确闹钟是否被禁用（用于决定要不要给「去开启」按钮）。 */
    fun exactAlarmDisabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        return runCatching {
            !(context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager).canScheduleExactAlarms()
        }.getOrDefault(false)
    }

    /** 申请精确闹钟权限。 */
    fun requestExactAlarm(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
            )
        }
    }

    /** 跳转本应用的前台服务通知渠道设置。 */
    fun openChannelSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    putExtra(Settings.EXTRA_CHANNEL_ID, "workbuddy_native_api")
                }
            )
        }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                )
            }
        }
    }

    /** 申请加入电池优化白名单。 */
    fun requestIgnoreBattery(context: Context) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(context.packageName)) return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            )
        }
    }

    /** 跳转通知权限设置（OAuth 等场景需要）。 */
    fun openNotificationSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                )
            }
        } else {
            openAppDetails(context)
        }
    }
}
