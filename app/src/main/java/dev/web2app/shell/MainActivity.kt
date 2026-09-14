package dev.web2app.shell

import android.Manifest
import android.app.PictureInPictureParams
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
import android.os.Bundle
import android.os.Environment
import android.util.Rational
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
import androidx.core.content.FileProvider
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
    /** Where a camera capture was told to write, so its result can be handed back. */
    private var pendingCameraUpload: Uri? = null
    /** Posted callback that gives up on a page which never paints. */
    private var loadWatchdog: Runnable? = null
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

        /*
         * Inverted on purpose, and this was a real bug.
         *
         * APPEARANCE_LIGHT_STATUS_BARS is documented as "Changes the foreground
         * color for light status bars", and the flag it sets below API 30,
         * SYSTEM_UI_FLAG_LIGHT_STATUS_BAR, is "compatible with light status bar
         * backgrounds" — so true means a LIGHT BAR with DARK icons.
         *
         * The config field asks for light *icons*. Assigning it straight across
         * gave every app the opposite of what was chosen: dark icons on a dark
         * status bar, which is invisible.
         */
        controller.isAppearanceLightStatusBars = !config.theme.statusBarLightIcons
        controller.isAppearanceLightNavigationBars = !config.theme.navigationBarLightIcons
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

        /*
         * Desktop mode is the shorthand people expect: the desktop user agent
         * and a viewport wide enough for the layout it triggers. A custom user
         * agent still wins, because someone who set one meant it.
         */
        if (c.desktopMode) {
            if (c.userAgentMode != "custom") s.userAgentString = DESKTOP_UA
            s.useWideViewPort = true
            s.loadWithOverviewMode = true
        }

        /*
         * Cookies.
         *
         * Third-party cookies were hardcoded on, and the three configuration
         * flags did nothing. The default stays permissive because turning them
         * off silently breaks sign-in on a great many sites — an embedded
         * identity provider is a third-party cookie — but it is now a choice
         * rather than an accident.
         */
        CookieManager.getInstance().apply {
            setAcceptCookie(c.cookiesAccept)
            setAcceptThirdPartyCookies(view, c.cookiesAccept && c.cookiesThirdParty)
        }

        // 0 means "let the page decide", which is what setInitialScale expects.
        if (c.initialScale > 0) view.setInitialScale(c.initialScale)

        when (c.scrollbars) {
            "hidden" -> {
                view.isVerticalScrollBarEnabled = false
                view.isHorizontalScrollBarEnabled = false
            }
            // Drawn over the content rather than insetting it, so the page keeps
            // its full width.
            "overlay" -> view.scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        }

        /*
         * Long press starts text selection and raises WebView's action menu from
         * the same gesture, and the platform offers no way to separate them —
         * so this turns both off together, which is what the single config flag
         * now promises.
         */
        view.isLongClickable = c.longPressEnabled
        if (!c.longPressEnabled) view.setOnLongClickListener { true }

        /*
         * Software rendering is a deliberate escape hatch, not a tuning knob:
         * it costs video playback and smooth scrolling. It exists because a
         * handful of devices render some pages incorrectly with acceleration on.
         */
        if (!c.hardwareAcceleration) view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

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

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            // Only for real navigations: the offline page must not arm a watchdog
            // that would then replace the offline page.
            if (!url.startsWith(OFFLINE_ASSET)) startLoadWatchdog()
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            splashReady = true
            // Something is on screen, which is the thing the watchdog waits for.
            cancelLoadWatchdog()
        }

        override fun onPageFinished(view: WebView, url: String) {
            cancelLoadWatchdog()
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
                val intent = buildUploadIntent(fileChooserParams)
                if (intent == null) {
                    pendingFileCallback = null
                    return false
                }
                fileChooser.launch(intent)
                true
            } catch (e: ActivityNotFoundException) {
                pendingFileCallback = null
                pendingCameraUpload = null
                false
            }
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            // Refused before anything is asked of the user: a prompt from an
            // origin the project never allowed is not theirs to answer.
            if (!originMayRequestPermissions(request.origin?.toString())) {
                request.deny()
                return
            }
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
            if (!config.webview.geolocationEnabled || !originMayRequestPermissions(origin)) {
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

    /**
     * Builds the chooser a page's file input opens.
     *
     * Returns null when the configuration leaves nothing to offer, so the
     * caller can decline rather than launch an empty chooser.
     *
     * The camera is added as an initial intent rather than replacing the
     * picker: a page asking for a photo usually wants either, and offering only
     * one is a guess about the user's intent that we have no business making.
     */
    private fun buildUploadIntent(params: WebChromeClient.FileChooserParams): Intent? {
        val browse = if (config.downloads.uploadBrowse) params.createIntent() else null
        val capture = if (config.downloads.uploadCamera) createCameraIntent() else null

        return when {
            browse != null && capture != null ->
                Intent.createChooser(browse, getString(R.string.upload_chooser_title)).apply {
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(capture))
                }
            browse != null -> browse
            capture != null -> capture
            else -> null
        }
    }

    /**
     * A capture intent pointed at a file the camera app may write.
     *
     * ACTION_IMAGE_CAPTURE hands the photo back through a URI we supply, not in
     * the result, so the destination has to exist first and the permission has
     * to be granted on the intent itself. Null if the device has no camera app,
     * which is normal on emulators and some tablets.
     */
    private fun createCameraIntent(): Intent? {
        return try {
            val dir = File(getExternalFilesDir(null), "uploads").apply { mkdirs() }
            val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

            Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }.takeIf { it.resolveActivity(packageManager) != null }
                ?.also { pendingCameraUpload = uri }
        } catch (e: Exception) {
            // A missing camera, a provider misconfiguration, or no external
            // storage: the picker alone is still a working upload.
            pendingCameraUpload = null
            null
        }
    }

    private fun registerLaunchers() {
        fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = pendingFileCallback
            val captured = pendingCameraUpload
            pendingFileCallback = null
            pendingCameraUpload = null

            /*
             * A camera capture returns no data — the photo is at the URI we gave
             * it — so parseResult finds nothing and the page would receive an
             * empty selection. This supplies the URI in that case.
             */
            val value = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            val resolved = when {
                !value.isNullOrEmpty() -> value
                result.resultCode == RESULT_OK && captured != null -> arrayOf(captured)
                else -> value
            }

            // Must always deliver a value, even on cancel, or the page's file input
            // stays permanently disabled.
            callback?.onReceiveValue(resolved)
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
        val id = getSystemService(DownloadManager::class.java).enqueue(request)
        if (config.downloads.openAfterDownload) watchForCompletion(id)
        toast(R.string.download_started)
    }

    /**
     * Opens a download once it finishes, when the project asked for that.
     *
     * Registered per download and unregistered the moment it fires, rather than
     * kept alive for the life of the activity: a receiver that outlives its
     * reason is a leak, and this one has exactly one job.
     *
     * RECEIVER_NOT_EXPORTED because the broadcast is a system one we only
     * listen for — at targetSdk 36 an unspecified export flag is a crash, not a
     * warning.
     */
    private fun watchForCompletion(downloadId: Long) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != downloadId) return
                runCatching { unregisterReceiver(this) }

                val manager = getSystemService(DownloadManager::class.java)
                val uri = manager.getUriForDownloadedFile(downloadId) ?: return
                val type = manager.getMimeTypeForDownloadedFile(downloadId)

                val view = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, type ?: "*/*")
                    // The URI belongs to DownloadManager, so the viewer needs
                    // permission granted on the intent itself.
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                // Nothing installed that can open this type is normal, not an
                // error — the file is downloaded either way.
                runCatching { startActivity(view) }
            }
        }

        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    // ── state ─────────────────────────────────────────────────────────────────

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Whether an origin is allowed to raise a permission prompt.
     *
     * The app's own site always may. Anything else has to be listed, because the
     * alternative is that any frame the site embeds inherits the app's camera,
     * microphone and location — which the person who installed it never agreed
     * to and cannot see.
     *
     * Compared by host rather than by string: an origin arrives as
     * "https://example.com" from one callback and "https://example.com/" from
     * another, and a scheme or a trailing slash is not a security boundary.
     */
    private fun originMayRequestPermissions(origin: String?): Boolean {
        val host = origin?.let { runCatching { Uri.parse(it).host }.getOrNull() }?.lowercase()
            ?: return false

        val startHost = runCatching { Uri.parse(config.startUrl).host }.getOrNull()?.lowercase()
        if (host == startHost) return true

        return config.permissions.allowedOrigins.any { allowed ->
            val allowedHost = runCatching { Uri.parse(allowed).host }.getOrNull()?.lowercase()
                ?: allowed.lowercase()
            host == allowedHost
        }
    }

    /**
     * Starts the load watchdog.
     *
     * WebView exposes no load timeout, so "give up after N milliseconds" has to
     * be a posted callback. It is cancelled the moment anything is painted, so
     * a slow-but-working page is never interrupted — only a page that has shown
     * nothing at all by the deadline.
     */
    private fun startLoadWatchdog() {
        cancelLoadWatchdog()
        val timeout = config.webview.loadTimeoutMs
        if (timeout <= 0L) return

        loadWatchdog = Runnable {
            loadWatchdog = null
            // Only if still nothing is visible. A page that painted and then kept
            // loading images is working, not stuck.
            if (!splashReady) {
                webView.stopLoading()
                showOffline()
            }
        }.also { webView.postDelayed(it, timeout) }
    }

    private fun cancelLoadWatchdog() {
        loadWatchdog?.let { webView.removeCallbacks(it) }
        loadWatchdog = null
    }

    /**
     * Enters picture-in-picture when the user leaves during fullscreen video.
     *
     * Only while a custom view is showing, because that is the one moment
     * WebView tells us media is playing — there is no callback for inline
     * playback, so triggering on anything else would shrink the app into a
     * floating window for a page that was only being read.
     *
     * Wrapped because the system refuses in states we cannot always predict:
     * some devices and some users disable PiP entirely, and that refusal must
     * not take the app down on the way to the home screen.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!config.webview.pictureInPicture || customView == null) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return

        runCatching {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    // 16:9 unless the view says otherwise; the system clamps
                    // anything it considers extreme.
                    .setAspectRatio(Rational(16, 9))
                    .build()
            )
        }
    }

    private fun showOffline() {
        splashReady = true

        /*
         * Settings ride in the URL fragment.
         *
         * A fragment never leaves the device, needs no file access — which this
         * WebView has switched off — and is readable synchronously, so the page
         * never shows its default text and then visibly replaces it.
         *
         * JSONObject does the escaping, so a message containing quotes, angle
         * brackets or a newline cannot break out of the value; the page assigns
         * it with textContent, so it cannot become markup either.
         */
        val settings = JSONObject()
            .put("message", config.offline.message ?: JSONObject.NULL)
            .put("retry", config.offline.retryButton)
            .put("accent", config.theme.primaryColor)
            .toString()

        webView.loadUrl("$OFFLINE_ASSET#" + Uri.encode(settings))
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
        cancelLoadWatchdog()

        /*
         * Non-persistent cookies are cleared as the app goes away.
         *
         * WebView keeps cookies across launches by default, so "do not persist"
         * has to be an explicit erase. Done here rather than on pause, because
         * pausing happens every time the user glances at another app and losing
         * their session for that would be absurd.
         *
         * isFinishing distinguishes a real exit from a configuration change; a
         * rotation must not sign anybody out.
         */
        if (!config.webview.cookiesPersist && isFinishing) {
            CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }
        }

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
