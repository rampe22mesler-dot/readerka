package org.readerka.ui

import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.readerka.R

fun translationUrl(text: String): String = Uri.Builder().scheme("https").authority("translate.google.com")
    .appendPath("").appendQueryParameter("sl", "auto").appendQueryParameter("tl", "ru")
    .appendQueryParameter("text", text).appendQueryParameter("op", "translate").build().toString()

/** The official translator runs in this dialog's own window, without leaving the PDF. */
@Composable fun TranslationOverlay(text: String, onDismiss: () -> Unit) {
    var progress by remember(text) { mutableIntStateOf(0) }
    var failed by remember(text) { mutableStateOf(false) }
    var browser by remember { mutableStateOf<WebView?>(null) }
    val url = remember(text) { translationUrl(text) }
    val configuration = LocalConfiguration.current
    val height = (configuration.screenHeightDp * .70f).coerceIn(280f, 600f).dp
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.96f).widthIn(max = 1200.dp).height(height).testTag("translation-overlay"),
            shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 8.dp) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.translation_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { failed = false; browser?.reload() }) { Icon(Icons.Default.Refresh, stringResource(R.string.retry_translation)) }
                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("close-translation")) { Icon(Outline.Close, stringResource(R.string.close)) }
                }
                Text(text, maxLines = 2, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("translation-source"))
                if (progress < 100 && !failed) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                if (failed) Text(stringResource(R.string.translation_connection_failed), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                AndroidView(factory = { context ->
                    WebView(context).apply {
                        tag = url
                        settings.apply {
                            // Google Translate's official interactive page requires JavaScript.
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            allowFileAccess = false
                            allowContentAccess = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            if (configuration.screenWidthDp >= 700)
                                userAgentString = userAgentString.replace(Regex("\\(Linux; Android[^)]*\\)"), "(X11; Linux x86_64)").replace("; wv", "").replace(" Mobile", "")
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) { progress = newProgress }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean = request.url.scheme != "https"
                            override fun onReceivedError(view: WebView?, request: WebResourceRequest, error: WebResourceError?) {
                                if (request.isForMainFrame) failed = true
                            }
                        }
                        browser = this
                        loadUrl(url)
                    }
                }, onRelease = { view ->
                    browser = null; view.stopLoading(); view.webChromeClient = null
                    view.webViewClient = WebViewClient(); view.destroy()
                }, modifier = Modifier.fillMaxWidth().weight(1f).testTag("translation-web"))
            }
        }
    }
}
