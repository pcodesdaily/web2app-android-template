package dev.web2app.shell

import android.Manifest
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewClientCompat

/**
 * Hosts the WebView and owns everything with a lifecycle: the view itself, the
 * activity-result launchers, and the pending callbacks the WebView hands back.
 *
 * The WebView is an Activity field rather than a Compose `remember`, because its
 * state must survive configuration changes and process death through
 * save/restoreState. Compose renders the chrome around it via AndroidView.
 */
class MainActivity : ComponentActivity() {

    private lateinit var config: AppConfig
    private lateinit var webView: WebView

    private var state by mutableStateOf(ShellState())

    /** Held between launching a chooser/permission prompt and its result. */
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermissionRequest: PermissionRequest? = null
    private var pendingGeolocation: Pair<String, GeolocationPermissions.Callback>? = null

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var fullscreenContainer: FrameLayout? = null

    private lateinit var fileChooser: ActivityResultLauncher<Intent>
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    private var splashReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        // Edge-to-edge is mandatory at targetSdk 36; calling it explicitly also
        // back-ports the same behaviour below 36.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        config = AppConfig.load(this)
        applySystemBars()

        webView = WebView(this).also(::configureWebView)
        registerLaunchers()

        splash.setKeepOnScreenCondition { !splashReady }
        webView.postDelayed({ splashReady = true }, SPLASH_MAX_MS)

        if (savedInstanceState != null) webView.restoreState(savedInstanceState) else loadStart()

        setContent {
            Web2AppTheme(config.theme) {
                BackHandler(enabled = true, onBack = ::handleBack)
                WebShell(
                    config = config,
                    state = state,
                    webView = webView,
                    onRefresh = { state = state.copy(refreshing = true); webView.reload() },
                    onNavSelected = { item ->
                        state = state.copy(selectedNavId = item.id)
                        webView.loadUrl(item.url)
                    },
                    onExitConfirmed = { state = state.copy(showExitDialog = false); finishWithSystemBack() },
                    onExitDismissed = { state = state.copy(showExitDialog = false) },
                )
            }
        }
    }

    private fun applySystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = config.theme.statusBarLightIcons
        if (config.theme.statusBarHidden) controller.hide(WindowInsetsCompat.Type.statusBars())
    }

    // ── webview ───────────────────────────────────────────────────────────────

    private fun configureWebView(view: WebView) {
        val c = config.webview
        val s = view.settings

        s.javaScriptEnabled = c.javaScriptEnabled
        s.domStorageEnabled = c.domStorage
        s.setSupportZoom(c.zoomEnabled)
        s.builtInZoomControls = c.zoomEnabled
        s.displayZoomControls = c.zoomControlsVisible
        s.textZoom = c.textZoom
        s.useWideViewPort = c.wideViewport
        s.loadWithOverviewMode = c.overviewMode
        s.mediaPlaybackRequiresUserGesture = c.autoplayRequiresGesture
        s.setSupportMultipleWindows(c.multiWindowEnabled)
        s.javaScriptCanOpenWindowsAutomatically = c.multiWindowEnabled
        s.setGeolocationEnabled(c.geolocationEnabled)
        s.safeBrowsingEnabled = c.safeBrowsing

        // Local file access stays off: the shell loads remote URLs, and leaving it on
        // widens the attack surface of any injected script for no gain. This does not
        // block file:///android_asset, which is how the offline page still loads.
        s.allowFileAccess = false
        s.allowContentAccess = false

        s.cacheMode = when (c.cacheMode) {
            "cache_else_network" -> WebSettings.LOAD_CACHE_ELSE_NETWORK
            "no_cache" -> WebSettings.LOAD_NO_CACHE
            "cache_only" -> WebSettings.LOAD_CACHE_ONLY
            else -> WebSettings.LOAD_DEFAULT
        }
        s.mixedContentMode = when (c.mixedContent) {
            "compat" -> WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            "always" -> WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            else -> WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        when (c.userAgentMode) {
            "desktop" -> s.userAgentString = DESKTOP_UA
            "custom" -> c.userAgentCustom?.let { s.userAgentString = it }
        }

        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)

        view.webViewClient = ShellWebViewClient()
        view.webChromeClient = ShellChromeClient()
        if (config.downloads.enabled) view.setDownloadListener(::startDownload)
    }

    private fun loadStart() {
        if (config.webview.offlineDetection && !isOnline()) showOffline() else webView.loadUrl(config.startUrl)
    }

    private inner class ShellWebViewClient : WebViewClientCompat() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val decision = LinkPolicy.decide(
                url = uri.toString(),
                host = uri.host?.lowercase(),
                scheme = uri.scheme?.lowercase(),
                startHost = Uri.parse(config.startUrl).host?.lowercase(),
                internalHosts = config.links.internalHosts,
                blocked = config.links.blockedPatterns,
                customSchemes = config.links.customSchemes,
                externalPolicy = config.links.externalPolicy,
            )
            return when (decision) {
                LinkPolicy.Decision.OPEN_INTERNAL -> false // let the WebView load it
                LinkPolicy.Decision.BLOCK -> true
                LinkPolicy.Decision.OPEN_EXTERNAL -> {
                    openExternally(uri)
                    true
                }
            }
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            splashReady = true
        }

        override fun onPageFinished(view: WebView, url: String) {
            state = state.copy(refreshing = false, pageTitle = view.title.orEmpty())
        }
    }

    private inner class ShellChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            state = state.copy(progress = newProgress)
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            state = state.copy(pageTitle = title.orEmpty())
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            if (!config.downloads.uploadsEnabled) return false
            // A chooser already open means the page asked twice; release the old
            // callback or that file input is dead for the rest of the session.
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = filePathCallback
            return try {
                fileChooser.launch(fileChooserParams.createIntent())
                true
            } catch (e: ActivityNotFoundException) {
                pendingFileCallback = null
                false
            }
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            val needed = request.resources.mapNotNull(::androidPermissionFor)
            if (needed.isEmpty()) {
                request.deny()
                return
            }
            val missing = needed.filter {
                ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isEmpty()) {
                request.grant(request.resources)
            } else {
                pendingPermissionRequest = request
                permissionLauncher.launch(missing.toTypedArray())
            }
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            if (pendingPermissionRequest == request) pendingPermissionRequest = null
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            if (!config.webview.geolocationEnabled) {
                callback.invoke(origin, false, false)
                return
            }
            val fine = Manifest.permission.ACCESS_FINE_LOCATION
            val coarse = Manifest.permission.ACCESS_COARSE_LOCATION
            val granted = ContextCompat.checkSelfPermission(this@MainActivity, fine) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this@MainActivity, coarse) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                callback.invoke(origin, true, false)
            } else {
                pendingGeolocation = origin to callback
                permissionLauncher.launch(arrayOf(fine, coarse))
            }
        }

        /**
         * Fullscreen video is added to the window's content view, outside the Compose
         * hierarchy, because the player expects a plain ViewGroup parent.
         */
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (!config.webview.allowFullscreen || customView != null) {
                callback.onCustomViewHidden()
                return
            }
            val container = FrameLayout(this@MainActivity)
            container.addView(
                view,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            addContentView(
                container,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            customView = view
            customViewCallback = callback
            fullscreenContainer = container
            WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        }

        override fun onHideCustomView() {
            fullscreenContainer?.let { (it.parent as? ViewGroup)?.removeView(it) }
            fullscreenContainer = null
            customView = null
            customViewCallback?.onCustomViewHidden()
            customViewCallback = null
            if (!config.theme.statusBarHidden) {
                WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // ── navigation ────────────────────────────────────────────────────────────

    /**
     * At targetSdk 36 predictive back is on by default: onBackPressed() is never
     * called and KEYCODE_BACK is never dispatched. Back must be handled through the
     * OnBackPressedDispatcher, which is what Compose's BackHandler registers with.
     */
    private fun handleBack() {
        when {
            customView != null -> webView.webChromeClient?.onHideCustomView()
            webView.canGoBack() && config.webview.backStackPolicy == "history" -> webView.goBack()
            config.webview.exitConfirm -> state = state.copy(showExitDialog = true)
            else -> finishWithSystemBack()
        }
    }

    /**
     * finish() rather than re-dispatching to onBackPressedDispatcher: BackHandler is
     * always enabled, so re-dispatching would land back in handleBack() forever.
     */
    private fun finishWithSystemBack() = finish()

    private fun openExternally(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            toast(R.string.no_app_for_link)
        }
    }

    // ── results ───────────────────────────────────────────────────────────────

    private fun registerLaunchers() {
        fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = pendingFileCallback
            pendingFileCallback = null
            // Must always deliver a value, even on cancel, or the page's file input
            // stays permanently disabled.
            callback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            )
        }

        permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            pendingPermissionRequest?.let { request ->
                pendingPermissionRequest = null
                if (grants.values.all { it }) request.grant(request.resources) else request.deny()
            }
            pendingGeolocation?.let { (origin, callback) ->
                pendingGeolocation = null
                callback.invoke(origin, grants.values.any { it }, false)
            }
        }
    }

    private fun androidPermissionFor(resource: String): String? = when (resource) {
        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
        else -> null // PROTECTED_MEDIA_ID and anything unknown are refused by default
    }

    // ── downloads ─────────────────────────────────────────────────────────────

    private fun startDownload(url: String, userAgent: String, disposition: String, mimeType: String, size: Long) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            toast(R.string.download_failed)
            return
        }
        val name = URLUtil.guessFileName(url, disposition, mimeType)
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setMimeType(mimeType)
            addRequestHeader("User-Agent", userAgent)
            // Session downloads fail without the page's cookies.
            CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
            // ponytail: app-private external dir needs no storage permission at
            // minSdk 24. Upgrade path: MediaStore insert into Downloads if users
            // need the file visible in the system Downloads app.
            setDestinationInExternalFilesDir(this@MainActivity, Environment.DIRECTORY_DOWNLOADS, name)
            setNotificationVisibility(
                if (config.downloads.showNotification) {
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                } else {
                    DownloadManager.Request.VISIBILITY_HIDDEN
                }
            )
        }
        getSystemService(DownloadManager::class.java).enqueue(request)
        toast(R.string.download_started)
    }

    // ── state ─────────────────────────────────────────────────────────────────

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun showOffline() {
        splashReady = true
        webView.loadUrl(OFFLINE_ASSET)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = null
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()

    private companion object {
        const val SPLASH_MAX_MS = 5_000L
        const val OFFLINE_ASSET = "file:///android_asset/offline.html"
        const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
