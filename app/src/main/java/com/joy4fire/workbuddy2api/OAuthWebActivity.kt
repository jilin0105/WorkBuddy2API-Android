package com.joy4fire.workbuddy2api

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.lang.ref.WeakReference

/**
 * App 内置授权窗口：用 WebView 承载上游登录页，整个登录/授权流程留在应用内部，不再跳系统浏览器。
 *
 * 上游登录是"设备授权流"——用户在这个窗口里登录并确认授权，MainActivity 依旧轮询
 * /v2/plugin/auth/token 取 token；轮询命中后调用 [dismissIfOpen] 自动关窗并刷新账号列表，
 * 因此这里不需要拦截回调 URL。
 */
class OAuthWebActivity : Activity() {

    companion object {
        const val EXTRA_AUTH_URL = "auth_url"
        const val EXTRA_REGION_LABEL = "region_label"
        /** true = 打开窗口前清空 WebView 登录态（切换账号用）；false = 复用会话（日常续期用）。 */
        const val EXTRA_CLEAR_SESSION = "clear_session"
        private const val TAG = "OAuthWeb"

        /** 登录页会做浏览器探测：默认 WebView UA 带 "; wv" 标记，容易被降级渲染或直接拒绝；
         *  伪装成同版本移动 Chrome，仍然由内置 WebView 承载，不会跳出应用。 */
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/125.0.0.0 Mobile Safari/537.36"

        /** 登录流程内可留在内置 WebView 的协议；其余（weixin://、mqq:// 等）才交给系统。 */
        private val IN_APP_SCHEMES = setOf("http", "https", "about", "blob", "data", "javascript")

        /** 主文档 body 长度：区分"真白屏"与"已渲染"。 */
        private const val PROBE_BODY_JS =
            "(function(){try{return (document.body && document.body.innerHTML.length)||0}catch(e){return -2}})()"

        /** 页面可见文本（含同源 iframe）：登录表单在 Keycloak iframe 里，只看主文档会误判白屏。 */
        private const val PROBE_TEXT_JS = """
            (function(){
              function grab(win){
                try {
                  var b = win.document.body;
                  return ((b && b.innerText) || '').replace(/\s+/g, ' ').trim().slice(0, 220);
                } catch (e) { return '[cross-origin]'; }
              }
              var out = [grab(window)];
              try { for (var i = 0; i < window.frames.length; i++) out.push('iframe' + i + ': ' + grab(window.frames[i])); } catch (e) {}
              return out.join(' || ');
            })()
        """

        private var current: WeakReference<OAuthWebActivity>? = null

        /** 轮询到授权成功后由 MainActivity 调用，自动关闭内置登录窗口。 */
        fun dismissIfOpen() {
            current?.get()?.finish()
        }
    }

    private var webView: WebView? = null
    private var statusText: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val authUrl = intent.getStringExtra(EXTRA_AUTH_URL)
        if (authUrl.isNullOrBlank()) {
            finish()
            return
        }
        val label = intent.getStringExtra(EXTRA_REGION_LABEL).orEmpty()
        current = WeakReference(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.wb_background))
            fitsSystemWindows = true
        }

        // 顶部标题栏：左侧标题，右侧关闭按钮（窗口内可随时退出登录）
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(14), dp(10))
        }
        bar.addView(TextView(this).apply {
            text = if (label.isBlank()) "内置登录" else "内置登录 · $label"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.wb_on_surface))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(TextView(this).apply {
            text = "关闭"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.wb_on_primary))
            gravity = Gravity.CENTER
            minWidth = dp(64)
            minHeight = dp(40)
            setPadding(dp(14), 0, dp(14), 0)
            background = pill(getColor(R.color.wb_primary))
            setOnClickListener { finish() }
        })
        root.addView(bar)

        // 状态行：实时显示加载进度/页面标题/错误，出问题时用户能直接看到原因
        val status = TextView(this).apply {
            text = "正在打开登录页…"
            textSize = 12f
            setTextColor(getColor(R.color.wb_on_surface_variant))
            setPadding(dp(18), 0, dp(18), dp(10))
        }
        statusText = status
        root.addView(status)

        root.addView(
            ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = true
                visibility = View.GONE
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3))
        )
        val progress = root.getChildAt(root.childCount - 1) as ProgressBar

        val web = WebView(this)
        configureWebView(web, progress)
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        webView = web

        setContentView(root)
        // 默认清空登录态：Google / GitHub 只要在 WebView 里留有上次的票据，
        // 再点对应按钮就会被静默授权到上次那个账号（上游页面的「退出」清不掉 IdP 的 Cookie），
        // 用户永远无法登录第二个账号。所以每次打开窗口都从干净会话开始。
        val clearSession = intent.getBooleanExtra(EXTRA_CLEAR_SESSION, true)
        Log.i(TAG, "load authUrl=$authUrl clearSession=$clearSession")
        if (clearSession) {
            // 清空是异步的：必须等 Cookie/存储真正清完再 loadUrl，
            // 否则加载请求可能抢在清理完成之前发出，又把旧票据带上去。
            resetWebSession(web) { web.loadUrl(authUrl) }
        } else {
            web.loadUrl(authUrl)
        }
    }

    /**
     * 清空 WebView 登录态，保证本次授权是全新会话。这是无差别清空，
     * 因此 Google、GitHub、微信/企微以及邮箱密码等所有登录方式都会一并回到未登录状态。
     *
     * 清三类状态，缺一不可：
     *  1) Cookie（removeAllCookies + removeSessionCookies + flush）：
     *     重点是 accounts.google.com 的 SID/HSID/SSID/LSID 等，只要它们还在，
     *     Google 就会静默返回同一个账号，根本不给账号选择的机会；
     *  2) WebStorage.deleteAllData：localStorage/IndexedDB，存放"上次用哪个账号"的缓存；
     *  3) WebView 缓存/历史/表单/SSL 预置：避免缓存的重定向页直接把旧账号带回来。
     * 全部完成后才回调 [onReady]，把 loadUrl 推迟到状态真正干净之后——
     * 这些清理大多是异步的，先 loadUrl 会被旧票据追上。
     */
    private fun resetWebSession(web: WebView, onReady: () -> Unit) {
        val cookies = CookieManager.getInstance()
        // removeAllCookies 是异步的：等回调表明清完再继续，最多等 2s 防止极端情况卡住界面。
        val latch = java.util.concurrent.CountDownLatch(1)
        cookies.removeAllCookies { latch.countDown() }
        runCatching { latch.await(2000, java.util.concurrent.TimeUnit.MILLISECONDS) }
        // 会话 Cookie（无过期时间的那些）走另一个入口，两边都清才干净。
        val sessionLatch = java.util.concurrent.CountDownLatch(1)
        cookies.removeSessionCookies { sessionLatch.countDown() }
        runCatching { sessionLatch.await(1000, java.util.concurrent.TimeUnit.MILLISECONDS) }
        cookies.flush()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(web, true)
        runCatching {
            android.webkit.WebStorage.getInstance().deleteAllData()
            web.clearCache(true)
            web.clearHistory()
            web.clearFormData()
            web.clearSslPreferences()
        }
        // 留一条可核对的日志：真机上若仍出现"秒登录旧账号"，用 logcat 看这里有没有残留。
        val leftover = runCatching { cookies.getCookie("https://accounts.google.com").orEmpty() }.getOrDefault("")
        Log.i(TAG, "web session reset done, google cookie leftover=${leftover.isBlank()}")
        onReady()
    }

    private fun configureWebView(web: WebView, progress: ProgressBar) {
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = false
            displayZoomControls = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // 登录是"用一次就走"的场景，缓存对本应用毫无价值，却会无上限堆在磁盘上：
            // 登录页来自 Google/GitHub/企微等站点，单次授权会拉取大量 JS、图片、字体，
            // WebView 把它们写进 app_webview 后不会自动回收（WebView 磁盘缓存无容量上限）。
            // 改为 LOAD_NO_CACHE：每次都回源，登录页本就不该被缓存复用（避免旧票据回流）。
            cacheMode = WebSettings.LOAD_NO_CACHE
            userAgentString = MOBILE_UA
        }
        // 第三方登录（企微/微信等）依赖 cookie 与 localStorage，必须放开
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                Log.i(TAG, "title=$title url=${view.url}")
                statusText?.text = title?.takeIf { it.isNotBlank() }?.let { "正在加载：$it" } ?: "正在加载登录页…"
            }

            /** 页面脚本会有浏览器探测/报错，控制台消息直接打进 logcat，便于排查白屏。 */
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                Log.i(TAG, "console[${msg.messageLevel()}] ${msg.message()} @${msg.sourceId()}:${msg.lineNumber()}")
                return true
            }

            /** 登录页会用 window.open 打开子步骤（如第三方授权）。
             *  把子窗口的 URL 重新塞回主 WebView 加载，避免弹出新窗口后登录态割裂。 */
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message
            ): Boolean {
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                Log.i(TAG, "onCreateWindow isDialog=$isDialog")
                val popup = WebView(this@OAuthWebActivity)
                popup.settings.javaScriptEnabled = true
                popup.settings.domStorageEnabled = true
                popup.settings.userAgentString = MOBILE_UA
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        v: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        val target = request.url
                        Log.i(TAG, "popup navigate=$target")
                        if (isInAppScheme(target)) {
                            view.loadUrl(target.toString())
                        } else {
                            openExternally(target)
                        }
                        return true
                    }
                }
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }
        }

        web.webViewClient = object : WebViewClient() {
            /** 一律自身承载 http(s)：这正是"内置登录"的核心，绝不把授权页甩给系统浏览器。 */
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val target = request.url
                Log.i(TAG, "navigate=$target")
                if (isInAppScheme(target)) return false
                openExternally(target)
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                Log.i(TAG, "onPageStarted $url")
            }

            override fun onPageFinished(view: WebView, url: String) {
                // body 长度能区分"真白屏"与"内容已渲染"：白屏会拿到 0
                view.evaluateJavascript(PROBE_BODY_JS) { len ->
                    Log.i(TAG, "onPageFinished $url title=${view.title} bodyHtml=$len")
                }
                // 登录表单在 Keycloak iframe 内，稍等再抓一次可见文本；拿到关键字即说明表单可用
                view.postDelayed({
                    if (isFinishing || isDestroyed) return@postDelayed
                    view.evaluateJavascript(PROBE_TEXT_JS) { text -> Log.i(TAG, "pageText=$text") }
                }, 3000)
                statusText?.text = "登录页已加载 · 完成后自动关闭窗口"
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                Log.w(TAG, "onReceivedError ${request.url} code=${error.errorCode} ${error.description}")
                statusText?.text = "加载出错：${error.description}（code ${error.errorCode}）"
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {
                Log.w(TAG, "onReceivedHttpError ${request.url} status=${errorResponse.statusCode}")
            }
        }
    }

    private fun isInAppScheme(uri: Uri): Boolean =
        uri.scheme?.lowercase() in IN_APP_SCHEMES

    /** 第三方 App 的私有 scheme（weixin://、mqq://…）必须交给系统，否则无法唤起对应客户端。 */
    private fun openExternally(uri: Uri) {
        Log.i(TAG, "openExternally $uri")
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // 登录是多步流程：先把返回键让给页面自身，页面到底了才关闭窗口
        val web = webView
        if (web != null && web.canGoBack()) web.goBack() else finish()
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        // 通知主界面结束本轮授权等待（成功自动关窗时已被 main 侧清空，不会误取消）
        MainActivity.notifyOAuthWindowClosed()
        webView?.let { web ->
            // 关窗前清掉本次授权产生的磁盘缓存：登录页资源（JS/图片/字体）体量可观，
            // 且下一次登录还会再拉一遍，留着只会白占空间。clearCache(true) 清磁盘部分。
            runCatching {
                web.clearCache(true)
                web.clearHistory()
                web.clearFormData()
                android.webkit.WebStorage.getInstance().deleteAllData()
            }
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        webView = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun pill(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(20).toFloat()
        setColor(color)
    }
}
