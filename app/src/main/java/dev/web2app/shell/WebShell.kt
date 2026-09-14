package dev.web2app.shell

import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** Everything the shell renders. Hoisted out of the Activity so the UI is pure. */
data class ShellState(
    val progress: Int = 0,
    val refreshing: Boolean = false,
    val pageTitle: String = "",
    val selectedNavId: String? = null,
    val showExitDialog: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebShell(
    config: AppConfig,
    state: ShellState,
    webView: WebView,
    onRefresh: () -> Unit,
    onNavSelected: (AppConfig.NavItem) -> Unit,
    onExitConfirmed: () -> Unit,
    onExitDismissed: () -> Unit,
) {
    val nav = config.navigation
    // Below two destinations it is not navigation; the schema rejects it too, but the
    // app must not render a broken bar if an older config slips through.
    val showBottomNav = nav.bottomNavEnabled && nav.bottomNavItems.size >= 2

    Scaffold(
        topBar = {
            if (nav.topBarEnabled) {
                TopAppBar(title = {
                    Text(
                        text = when {
                            nav.showPageTitle && state.pageTitle.isNotEmpty() -> state.pageTitle
                            else -> nav.topBarTitle ?: stringResource(R.string.app_name)
                        },
                        maxLines = 1,
                    )
                })
            }
        },
        bottomBar = {
            if (showBottomNav) {
                val barStyle = nav.bottomNavStyle

                /*
                 * Every colour falls back to the Material default rather than to
                 * a constant. A config that never chose a colour must follow the
                 * theme — including dark mode — instead of freezing whatever
                 * palette happened to be current when it was written.
                 */
                val container = parseHexColor(barStyle.containerColor, NavigationBarDefaults.containerColor)
                val itemColors = NavigationBarItemDefaults.colors(
                    selectedIconColor = parseHexColor(
                        barStyle.selectedColor,
                        MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                    selectedTextColor = parseHexColor(
                        barStyle.selectedColor,
                        MaterialTheme.colorScheme.onSurface,
                    ),
                    indicatorColor = parseHexColor(
                        barStyle.indicatorColor,
                        MaterialTheme.colorScheme.secondaryContainer,
                    ),
                    unselectedIconColor = parseHexColor(
                        barStyle.unselectedColor,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    unselectedTextColor = parseHexColor(
                        barStyle.unselectedColor,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )

                NavigationBar(
                    containerColor = container,
                    tonalElevation = barStyle.elevationDp.dp,
                ) {
                    nav.bottomNavItems.forEach { item ->
                        NavigationBarItem(
                            selected = item.id == state.selectedNavId,
                            onClick = { onNavSelected(item) },
                            icon = { Icon(navIcon(item.icon), contentDescription = item.label) },
                            /*
                             * "never" passes no label at all, which is what
                             * produces an icon-only bar — alwaysShowLabel has no
                             * effect without one. The content description above
                             * still names every destination, so an icon-only bar
                             * stays usable with a screen reader.
                             */
                            label = if (barStyle.labels == "never") null else {
                                { Text(item.label, maxLines = 1) }
                            },
                            alwaysShowLabel = barStyle.labels != "selected",
                            colors = itemColors,
                        )
                    }
                }
            }
        },
    ) { insets ->
        // Scaffold hands back the insets for the bars it drew plus the system bars,
        // which is what keeps content clear of the status bar under mandatory
        // edge-to-edge at targetSdk 36.
        Box(Modifier.fillMaxSize().padding(insets)) {
            // When pull-to-refresh is off the WebView is hosted directly, so the
            // gesture detector never sits in front of the page's own scrolling.
            if (config.pullToRefresh) {
                PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    AndroidView(factory = { webView.detachFromParent() }, modifier = Modifier.fillMaxSize())
                }
            } else {
                AndroidView(factory = { webView.detachFromParent() }, modifier = Modifier.fillMaxSize())
            }

            /*
             * Loading indicator.
             *
             * All three styles are components Material 3 already provides, so
             * offering the choice costs the APK nothing — which is the rule for
             * anything a design template is allowed to change.
             *
             * "none" is a real option, not an omission: a site with its own
             * loading bar otherwise shows two, and a wall-mounted kiosk should
             * not flash a progress bar at the room on every navigation.
             */
            if (state.progress in 1..99 && config.theme.progressStyle != "none") {
                val tint = config.theme.progressColor
                    ?.let { parseHexColor(it, fallback = MaterialTheme.colorScheme.primary) }
                    ?: MaterialTheme.colorScheme.primary

                val thickness = config.theme.progressThicknessDp.dp

                if (config.theme.progressStyle == "circular") {
                    CircularProgressIndicator(
                        progress = { state.progress / 100f },
                        color = tint,
                        // Same dp means the ring's stroke here and the bar's
                        // height below — one control, two honest meanings.
                        strokeWidth = thickness,
                        trackColor = parseHexColor(
                            config.theme.progressTrackColor,
                            ProgressIndicatorDefaults.circularDeterminateTrackColor,
                        ),
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { state.progress / 100f },
                        color = tint,
                        trackColor = parseHexColor(
                            config.theme.progressTrackColor,
                            ProgressIndicatorDefaults.linearTrackColor,
                        ),
                        // LinearProgressIndicator has no thickness parameter;
                        // its height is whatever the modifier gives it.
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(thickness)
                            .align(Alignment.TopCenter),
                    )
                }
            }
        }
    }

    if (state.showExitDialog) {
        AlertDialog(
            onDismissRequest = onExitDismissed,
            text = { Text(stringResource(R.string.exit_confirm)) },
            confirmButton = { TextButton(onClick = onExitConfirmed) { Text(stringResource(android.R.string.ok)) } },
            dismissButton = { TextButton(onClick = onExitDismissed) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}


/**
 * AndroidView throws if handed a view that already has a parent, which happens
 * whenever the hosting branch changes or the composition restarts.
 */
private fun WebView.detachFromParent(): WebView = apply { (parent as? ViewGroup)?.removeView(this) }
