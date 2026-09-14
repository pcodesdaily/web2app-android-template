package dev.web2app.shell

import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
                NavigationBar {
                    nav.bottomNavItems.forEach { item ->
                        NavigationBarItem(
                            selected = item.id == state.selectedNavId,
                            onClick = { onNavSelected(item) },
                            icon = { Icon(navIcon(item.icon), contentDescription = item.label) },
                            label = { Text(item.label, maxLines = 1) },
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

            if (state.progress in 1..99) {
                LinearProgressIndicator(
                    progress = { state.progress / 100f },
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                )
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
