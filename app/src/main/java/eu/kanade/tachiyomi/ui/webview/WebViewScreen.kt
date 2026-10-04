package eu.kanade.tachiyomi.ui.webview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.util.AssistContentScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.webview.WebViewScreenContent
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.presentation.core.screens.LoadingScreen

class WebViewScreen(
    private val url: String,
    private val initialTitle: String? = null,
    private val sourceId: Long? = null,
    private val ehLogin: Boolean = false,
) : Screen(), AssistContentScreen {

    private var assistUrl: String? = null

    override fun onProvideAssistUrl() = assistUrl

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val viewModel =
            assistedMetroViewModel<WebViewViewModel, WebViewViewModel.Factory> { create(sourceId = sourceId) }

        val headers by viewModel.headers.collectAsState()
        val scope = rememberCoroutineScope()
        var importing by remember { mutableStateOf(false) }
        var awaitingEx by remember { mutableStateOf(false) }
        if (headers == null) {
            LoadingScreen()
            return
        }

        WebViewScreenContent(
            onNavigateUp = { navigator.pop() },
            initialTitle = initialTitle,
            url = url,
            headers = if (ehLogin) emptyMap() else headers.orEmpty(),
            defaultUserAgentProvider = viewModel::defaultUserAgentProvider,
            onUrlChange = { assistUrl = it },
            onShare = { viewModel.shareWebpage(context, it) },
            onOpenInBrowser = { viewModel.openInBrowser(context, it) },
            onClearCookies = viewModel::clearCookies,
            onPageFinished = { webView, pageUrl ->
                val host = pageUrl.toHttpUrlOrNull()?.host
                if (ehLogin && !importing && (host == "forums.e-hentai.org" || (awaitingEx && host in listOf("exhentai.org", "e-hentai.org")))) {
                    importing = true
                    scope.launch {
                        val result = viewModel.importEhLogin()
                        if (result.isSuccess) {
                            if (!awaitingEx) {
                                awaitingEx = true
                                webView.loadUrl("https://exhentai.org/")
                            } else {
                                navigator.pop()
                            }
                        }
                        importing = false
                    }
                }
            },
        )
    }
}
