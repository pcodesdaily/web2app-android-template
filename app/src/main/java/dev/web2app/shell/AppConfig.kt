package dev.web2app.shell

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads assets/config.json, written per build from the validated project config.
 *
 * ponytail: hand-rolled org.json instead of kotlinx.serialization — no Kotlin
 * compiler plugin, which AGP 9's built-in Kotlin does not yet document support
 * for, and no dependency. Upgrade path: switch to kotlinx.serialization once the
 * config outgrows the fields the shell actually reads.
 *
 * Every accessor takes a default matching packages/config-schema, so a config
 * missing a key behaves exactly as the schema says it should.
 */
data class AppConfig(
    val startUrl: String,
    val webview: WebViewConfig,
    val links: LinksConfig,
    val navigation: NavigationConfig,
    val theme: ThemeConfig,
    val downloads: DownloadsConfig,
    val offline: OfflineConfig,
    val permissions: PermissionsConfig,
    val pullToRefresh: Boolean,
) {
    /**
     * The page shown when there is no connection.
     *
     * The whole section was previously unparsed, so offline.html carried fixed
     * copy and both configuration fields did nothing.
     */
    data class OfflineConfig(
        /** Replaces the default line. Null keeps it. */
        val message: String?,
        /** Whether to offer a retry button at all. */
        val retryButton: Boolean,
    )

    data class WebViewConfig(
        val javaScriptEnabled: Boolean,
        val domStorage: Boolean,
        val cacheMode: String,
        val zoomEnabled: Boolean,
        val zoomControlsVisible: Boolean,
        val userAgentMode: String,
        val userAgentCustom: String?,
        val mixedContent: String,
        val autoplayRequiresGesture: Boolean,
        val allowFullscreen: Boolean,
        /**
         * Keep playing in a floating window when the user leaves the app.
         *
         * Only applies while a video is genuinely fullscreen — WebView gives no
         * signal that a page is playing inline media, so there is nothing
         * reliable to trigger on otherwise.
         */
        val pictureInPicture: Boolean,
        val multiWindowEnabled: Boolean,
        val popupPolicy: String,
        val textZoom: Int,
        val backStackPolicy: String,
        val exitConfirm: Boolean,
        val safeBrowsing: Boolean,
        val geolocationEnabled: Boolean,
        val wideViewport: Boolean,
        val overviewMode: Boolean,
        val offlineDetection: Boolean,
        /** CookieManager.setAcceptCookie. Off means the site cannot keep a session. */
        val cookiesAccept: Boolean,
        /** CookieManager.setAcceptThirdPartyCookies. Needed by most embedded logins. */
        val cookiesThirdParty: Boolean,
        /** When false, cookies are cleared as the app stops, so nothing survives a restart. */
        val cookiesPersist: Boolean,
        /** WebView.setInitialScale, as a percentage. 0 leaves the page to decide. */
        val initialScale: Int,
        /** "default", "hidden" or "overlay". */
        val scrollbars: String,
        /**
         * Long-press behaviour.
         *
         * Both schema flags collapse into this one. WebView starts text
         * selection and raises its action menu from the same gesture and offers
         * no way to separate them, so turning either off turns off both — which
         * is the honest reading of two switches the platform cannot tell apart.
         */
        val longPressEnabled: Boolean,
        /**
         * Desktop mode.
         *
         * A convenience that sets the desktop user agent and a wide viewport
         * together, which is what people mean by it. Distinct from
         * user_agent.mode, which changes only the header.
         */
        val desktopMode: Boolean,
        /** Software rendering when false. An escape hatch for rendering bugs. */
        val hardwareAcceleration: Boolean,
        /**
         * How long to wait for a page before giving up, in milliseconds.
         *
         * WebView has no load timeout of its own, so this is a watchdog: if the
         * page has not committed anything visible by then, the offline screen is
         * shown rather than leaving someone on a blank white rectangle with no
         * way to know whether it is still trying.
         */
        val loadTimeoutMs: Long,
    )

    /**
     * Which origins may ask for a device permission.
     *
     * Empty means the app's own site and nothing else. An embedded third-party
     * frame asking for the camera is the case this exists to refuse — the user
     * chose to install an app for one site, not to hand its permissions to
     * whatever that site happens to embed.
     */
    data class PermissionsConfig(val allowedOrigins: List<String>)

    data class LinksConfig(
        val internalHosts: List<String>,
        val externalPolicy: String,
        val blockedPatterns: List<String>,
        val customSchemes: List<String>,
    )

    /**
     * Icon as vector path data rather than a library reference, so any Material
     * Symbol or a customer's own SVG can be used without bundling an icon set.
     * viewBox is [minX, minY, width, height]; Material Symbols use [0,-960,960,960].
     */
    data class NavIcon(
        val name: String,
        val path: String?,
        val minX: Float,
        val minY: Float,
        val viewportWidth: Float,
        val viewportHeight: Float,
    )

    data class NavItem(val id: String, val label: String, val icon: NavIcon, val url: String)

    data class NavigationConfig(
        val topBarEnabled: Boolean,
        val topBarTitle: String?,
        val showPageTitle: Boolean,
        val bottomNavEnabled: Boolean,
        val bottomNavItems: List<NavItem>,
        val bottomNavStyle: BottomNavStyle,
    )

    /**
     * Bottom bar appearance.
     *
     * Colours are nullable rather than defaulted to a constant: null means "let
     * the Material theme decide", which is what most apps want and what keeps a
     * config that never set a colour from freezing today's palette into itself.
     */
    data class BottomNavStyle(
        val containerColor: String?,
        val selectedColor: String?,
        val unselectedColor: String?,
        val indicatorColor: String?,
        /** "always", "selected" or "never". Anything else behaves as "always". */
        val labels: String,
        val elevationDp: Int,
    )

    data class ThemeConfig(
        val mode: String,
        val primaryColor: String,
        val backgroundColor: String,
        val statusBarLightIcons: Boolean,
        val statusBarHidden: Boolean,
        /**
         * Null means leave the bar transparent, which is what edge-to-edge does.
         *
         * Window.setStatusBarColor is deprecated and, from targetSdk 35, the
         * documentation says the colour "will be transparent and cannot be
         * changed". The documented replacement is to draw a background behind
         * WindowInsets.Type.statusBars() — which is what the shell does.
         */
        val statusBarColor: String?,
        val navigationBarColor: String?,
        val navigationBarLightIcons: Boolean,
        /** "linear", "circular" or "none". Anything else is treated as "linear". */
        val progressStyle: String,
        /** Null means follow the primary colour, which is what most configs want. */
        val progressColor: String?,
        /** Null means Material's own track colour for the chosen indicator. */
        val progressTrackColor: String?,
        /** Bar height for linear, ring stroke width for circular. Material uses 4. */
        val progressThicknessDp: Float,
    )

    data class DownloadsConfig(
        val enabled: Boolean,
        val showNotification: Boolean,
        val uploadsEnabled: Boolean,
        val acceptMime: List<String>,
        /** Offers the camera alongside the file picker when a page asks for a file. */
        val uploadCamera: Boolean,
        /**
         * Whether the file picker is offered at all.
         *
         * Gallery and files collapse into one flag: Android's document picker
         * shows photos and documents together and offers no way to present one
         * without the other, so two switches would promise a distinction the
         * platform does not make.
         */
        val uploadBrowse: Boolean,
        /** Opens a finished download rather than only notifying. */
        val openAfterDownload: Boolean,
    )

    companion object {
        private const val ASSET = "config.json"

        fun load(context: Context): AppConfig {
            val root = JSONObject(context.assets.open(ASSET).bufferedReader().use { it.readText() })

            val source = root.obj("source")
            val webview = root.obj("webview")
            val links = root.obj("links")
            val navigation = root.obj("navigation")
            val theme = root.obj("theme")
            val downloads = root.obj("downloads")
            val offline = root.obj("offline")
            val uploads = downloads.obj("uploads")

            return AppConfig(
                // An archive source is served through WebViewAssetLoader; see MainActivity.
                startUrl = source.optString("url", ""),
                pullToRefresh = webview.optBoolean("pull_to_refresh", true),
                webview = WebViewConfig(
                    javaScriptEnabled = webview.optBoolean("javascript_enabled", true),
                    domStorage = webview.optBoolean("dom_storage", true),
                    cacheMode = webview.optString("cache_mode", "default"),
                    zoomEnabled = webview.obj("zoom").optBoolean("enabled", false),
                    zoomControlsVisible = webview.obj("zoom").optBoolean("controls_visible", false),
                    userAgentMode = webview.obj("user_agent").optString("mode", "default"),
                    userAgentCustom = webview.obj("user_agent").optStringOrNull("custom_string"),
                    mixedContent = webview.optString("mixed_content", "never"),
                    autoplayRequiresGesture = webview.obj("media").optBoolean("autoplay_requires_gesture", true),
                    pictureInPicture = webview.obj("media").optBoolean("picture_in_picture", false),
                    allowFullscreen = webview.obj("media").optBoolean("fullscreen", true),
                    multiWindowEnabled = webview.obj("multi_window").optBoolean("enabled", false),
                    popupPolicy = webview.obj("multi_window").optString("popup_policy", "same_webview"),
                    textZoom = webview.optInt("text_zoom", 100),
                    backStackPolicy = webview.obj("back_stack").optString("policy", "history"),
                    exitConfirm = webview.obj("back_stack").optBoolean("exit_confirm", true),
                    safeBrowsing = webview.optBoolean("safe_browsing", true),
                    geolocationEnabled = webview.optBoolean("geolocation_enabled", false),
                    wideViewport = webview.obj("viewport").optBoolean("wide_viewport", true),
                    overviewMode = webview.obj("viewport").optBoolean("overview_mode", true),
                    offlineDetection = webview.obj("network").optBoolean("offline_detection", true),
                    cookiesAccept = webview.obj("cookies").optBoolean("accept", true),
                    cookiesThirdParty = webview.obj("cookies").optBoolean("third_party", true),
                    cookiesPersist = webview.obj("cookies").optBoolean("persist", true),
                    initialScale = webview.obj("zoom").optInt("initial_scale", 0),
                    scrollbars = webview.optString("scrollbars", "default"),
                    longPressEnabled = webview.obj("long_press").let {
                        it.optBoolean("context_menu", true) && it.optBoolean("selection", true)
                    },
                    desktopMode = webview.optBoolean("desktop_mode", false),
                    hardwareAcceleration = webview.optBoolean("hardware_acceleration", true),
                    loadTimeoutMs = webview.obj("network").optLong("timeout_ms", 30000L),
                ),
                links = LinksConfig(
                    internalHosts = links.strings("internal_hosts"),
                    externalPolicy = links.optString("external_policy", "browser"),
                    blockedPatterns = links.strings("blocked_patterns"),
                    customSchemes = links.strings("custom_schemes"),
                ),
                navigation = navigation.run {
                    val topBar = obj("top_bar")
                    val bottomNav = obj("bottom_nav")
                    NavigationConfig(
                        topBarEnabled = topBar.optBoolean("enabled", false),
                        topBarTitle = topBar.optStringOrNull("title"),
                        showPageTitle = topBar.optBoolean("show_page_title", false),
                        bottomNavEnabled = bottomNav.optBoolean("enabled", false),
                        bottomNavItems = bottomNav.navItems("items"),
                        bottomNavStyle = bottomNav.obj("style").run {
                            BottomNavStyle(
                                containerColor = optStringOrNull("container_color"),
                                selectedColor = optStringOrNull("selected_color"),
                                unselectedColor = optStringOrNull("unselected_color"),
                                indicatorColor = optStringOrNull("indicator_color"),
                                labels = optString("labels", "always"),
                                elevationDp = optInt("elevation_dp", 3),
                            )
                        },
                    )
                },
                theme = ThemeConfig(
                    mode = theme.optString("mode", "system"),
                    primaryColor = theme.optString("primary_color", "#2563EB"),
                    backgroundColor = theme.optString("background_color", "#FFFFFF"),
                    statusBarLightIcons = theme.obj("status_bar").optBoolean("light_icons", false),
                    statusBarHidden = theme.obj("status_bar").optBoolean("hidden", false),
                    statusBarColor = theme.obj("status_bar").optString("color", "").ifEmpty { null },
                    navigationBarColor = theme.obj("navigation_bar").optString("color", "").ifEmpty { null },
                    navigationBarLightIcons = theme.obj("navigation_bar").optBoolean("light_icons", false),
                    progressStyle = theme.obj("progress").optString("style", "linear"),
                    // optString returns "" for a missing key, and an empty
                    // string here must mean "inherit", not "transparent".
                    progressColor = theme.obj("progress").optString("color", "").ifEmpty { null },
                    progressTrackColor = theme.obj("progress").optString("track_color", "").ifEmpty { null },
                    progressThicknessDp = theme.obj("progress").optDouble("thickness_dp", 4.0).toFloat(),
                ),
                permissions = PermissionsConfig(
                    allowedOrigins = root.obj("permissions").strings("allowed_origins"),
                ),
                offline = OfflineConfig(
                    message = offline.optString("message", "").ifEmpty { null },
                    retryButton = offline.optBoolean("retry_button", true),
                ),
                downloads = DownloadsConfig(
                    uploadCamera = downloads.obj("uploads").optBoolean("camera", true),
                    uploadBrowse = downloads.obj("uploads").let {
                        it.optBoolean("gallery", true) || it.optBoolean("files", true)
                    },
                    openAfterDownload = downloads.optBoolean("open_after_download", false),
                    enabled = downloads.optBoolean("enabled", true),
                    showNotification = downloads.optBoolean("show_notification", true),
                    uploadsEnabled = uploads.optBoolean("enabled", true),
                    acceptMime = uploads.strings("accept_mime"),
                ),
            )
        }

        /** Missing object reads as empty, so every nested default still applies. */
        private fun JSONObject.obj(key: String): JSONObject = optJSONObject(key) ?: JSONObject()

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

        /** Items missing a required field are dropped, never rendered half-built. */
        private fun JSONObject.navItems(key: String): List<NavItem> {
            val array = optJSONArray(key) ?: return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optStringOrNull("id") ?: return@mapNotNull null
                val url = o.optStringOrNull("url") ?: return@mapNotNull null
                NavItem(id, o.optString("label", id), o.navIcon(), url)
            }
        }

        /** Defaults match the Material Symbols viewBox the runner resolves against. */
        private fun JSONObject.navIcon(): NavIcon {
            val icon = obj("icon")
            val box = icon.optJSONArray("viewbox")
            fun box(i: Int, fallback: Float) =
                box?.takeIf { it.length() == 4 }?.optDouble(i)?.takeIf { !it.isNaN() }?.toFloat() ?: fallback
            // A zero or negative viewport would divide by zero when scaling.
            val width = box(2, 960f).takeIf { it > 0f } ?: 960f
            val height = box(3, 960f).takeIf { it > 0f } ?: 960f
            return NavIcon(
                name = icon.optString("name", "icon"),
                path = icon.optStringOrNull("path"),
                minX = box(0, 0f),
                minY = box(1, -960f),
                viewportWidth = width,
                viewportHeight = height,
            )
        }

        private fun JSONObject.strings(key: String): List<String> {
            val array: JSONArray = optJSONArray(key) ?: return emptyList()
            return (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotEmpty) }
        }
    }
}
