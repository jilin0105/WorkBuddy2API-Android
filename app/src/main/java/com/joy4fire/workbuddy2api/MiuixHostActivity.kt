package com.joy4fire.workbuddy2api

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.joy4fire.workbuddy2api.ui.AppShell
import com.joy4fire.workbuddy2api.ui.AppState

/**
 * 应用唯一入口 —— Miuix（Compose）界面。
 *
 * 本 Activity 只做三件事：托管 [AppShell]、持有 [AppState]、把服务状态广播转给 AppState。
 * 全部功能页面（概览/账号/模型/记录/应用/设置/成长 + 各二级页）都在 ui 包内，
 * 与 Activity 生命周期解耦，Activity 重建不丢页面状态（Tab/二级页用 rememberSaveable）。
 *
 * 与原 MainActivity 的关系：
 *   MainActivity 是迁移来源与参考实现，其页面已全部迁入本界面；
 *   源码保留在库中（未在清单注册为启动项），仅用于对照排查迁移差异。
 */
class MiuixHostActivity : ComponentActivity() {

    /**
     * 当前 AppState 引用。
     *
     * 为什么用字段持有：广播接收器在 onStart/onStop 注册，生命周期与 Composition 不同步，
     * 需要一个跨两者的引用把状态写进去。Composition 销毁时由 DisposableEffect 置空，
     * 避免持有已废弃的状态对象。
     */
    private var appState by mutableStateOf<AppState?>(null)

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val running = intent?.getBooleanExtra("running", false) == true
            val message = intent?.getStringExtra("message")
            // 写入 AppState（内部是 mutableStateOf），从而触发界面重组。
            // 早先只读 ApiHostService.running 这个静态变量，不建立 Compose 订阅，
            // 导致点「启动服务」后界面纹丝不动，看起来像没反应。
            appState?.updateServiceState(running, message)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val app = remember { AppState(applicationContext) }
            // 把实例交给广播接收器使用（含 Activity 重建后的新实例）。
            appState = app

            // 进入界面即加载一次全量数据，避免各页面首次切换时空白。
            androidx.compose.runtime.LaunchedEffect(Unit) { app.refreshAll() }

            DisposableEffect(Unit) {
                onDispose {
                    appState = null
                    app.dispose()
                }
            }

            AppShell(context = this, app = app)
        }
    }

    override fun onStart() {
        super.onStart()
        // Android 14（API 34）起，注册非系统广播必须显式声明导出性，否则抛
        // SecurityException 直接崩溃（本应用 targetSdk 36，必然触发）。
        //
        // 用 RECEIVER_NOT_EXPORTED：ACTION_STATE 是本应用服务用 setPackage(packageName)
        // 定向发出的内部广播，外部应用无权也不需要发它；声明为 NOT_EXPORTED 既是合规要求，
        // 也顺带堵掉了外部伪造状态广播的可能。
        //
        // 用 SDK_INT 分支而不是 ContextCompat：与既有代码风格一致，且免去引入 androidx.core。
        val filter = IntentFilter(ApiHostService.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stateReceiver, filter)
        }
        // 注册后立即同步一次当前状态，弥补在界面不可见期间错过的广播。
        appState?.updateServiceState(ApiHostService.running)
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(stateReceiver) }
    }
}
