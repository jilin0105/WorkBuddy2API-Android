package com.joy4fire.workbuddy2api

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 悬浮窗保活。
 *
 * ## 它到底有没有用（先说清楚，不夸大）
 *
 * 悬浮窗用的是 `TYPE_APPLICATION_OVERLAY`，属于**可见窗口**。Android 的进程优先级
 * （`oom_adj`）会因此提升到 VISIBLE 档，在**内存不足（LMK）回收**时排在前台服务之后，
 * 确实能降低"内存一紧就被清掉"的概率。
 *
 * 但必须明确：**它挡不住厂商的强杀**。ColorOS / MIUI 的「一键清理」「速冻」「深度睡眠」
 * 走的是白名单机制——不在白名单里，窗口再多也照杀。所以悬浮窗只是**辅助手段**，
 * 真正的保命线是：电池优化白名单 + 自启动 + 后台运行 + 回收后自动重启（见 ApiHostService）。
 *
 * ## 为什么还值得做
 *
 * 部分 ROM 判定"速冻"时会参考"进程是否有可见窗口/前台服务"这一状态。有悬浮窗时，
 * 被判定为可速冻的概率会下降。属于"有收益但非决定性"的加固项，因此做成**可选开关**，
 * 默认关闭——毕竟它会常驻一个图标在屏幕上，属于以观感换存活率。
 */
object FloatingWindowKeeper {

    private const val TAG = "FloatingKeeper"
    private const val ACTION = "com.joy4fire.workbuddy2api.FLOATING"
    private const val PREFS = "native_service"
    private const val KEY_ENABLED = "floating_window_enabled"

    private var windowView: View? = null
    private var statusText: TextView? = null

    /** 权限是否已授予（授权页返回后需要重新查询，Android 不会回调通知）。 */
    fun hasPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true

    /** 用户是否在设置里开启了悬浮窗保活。 */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** 跳系统授权页；授权结果无法通过回调获知，返回后由界面重新调用 [hasPermission] 判断。 */
    fun requestPermission(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w(TAG, "open overlay settings failed", it) }
    }

    /**
     * 显示悬浮窗。无权限或未开启时静默跳过——保活是尽力而为的能力，
     * 不应因为一个小窗让主服务起不来。
     */
    fun show(context: Context, port: Int) {
        if (!isEnabled(context) || !hasPermission(context)) {
            Log.i(TAG, "skip show: enabled=${isEnabled(context)} permission=${hasPermission(context)}")
            return
        }
        if (windowView != null) {
            // 字段存在不代表窗口真的还在：进程被回收后对象会随进程一起消失，
            // 但服务在同一进程内重启时字段可能残留，这里用 isAttachedToWindow 做一次实证校验。
            if (windowView?.isAttachedToWindow == true) {
                updateStatus("运行中 · 端口 $port")
                return
            }
            Log.i(TAG, "stale window reference, rebuilding")
            windowView = null
            statusText = null
        }
        runCatching {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xE6202124.toInt())
                setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
            }
            val title = TextView(context).apply {
                text = "WorkBuddy API"
                setTextColor(0xFFE8EAED.toInt())
                textSize = 12f
            }
            statusText = TextView(context).apply {
                text = "运行中 · 端口 $port"
                setTextColor(0xFF8AB4F8.toInt())
                textSize = 11f
            }
            root.addView(title)
            root.addView(statusText)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                // Android 8.0 起必须用 APPLICATION_OVERLAY；旧版本用 PHONE 兜底。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                // NOT_FOCUSABLE：不抢输入焦点，避免影响用户正常操作其它应用。
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = dp(context, 12)
                y = dp(context, 120)
            }
            wm.addView(root, params)
            windowView = root
            Log.i(TAG, "floating window shown")
        }.onFailure { Log.w(TAG, "show floating window failed", it) }
    }

    /** 服务状态变化时刷新文案（例如端口或运行状态变化）。 */
    fun updateStatus(text: String) {
        statusText?.let { runCatching { it.text = text } }
    }

    /** 服务停止时移除窗口，避免残留一个"运行中"的假象。 */
    fun hide(context: Context) {
        val view = windowView ?: return
        runCatching {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeView(view)
        }.onFailure { Log.w(TAG, "remove floating window failed", it) }
        windowView = null
        statusText = null
    }

    /** 服务进程被杀后窗口会随之消失，这里用于判断"窗口是否还在"以做状态自检。 */
    fun isShowing(): Boolean = windowView != null

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
