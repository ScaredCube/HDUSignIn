package moe.scc.newhdusignin
import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class CaptchaBridge(private val challenge: CaptchaChallenge) {
    @JavascriptInterface fun submit(parameter: String, userAgent: String) {
        if (parameter.isBlank() || parameter.length > 64000) {
            failed("官方验证码参数无效")
            return
        }
        challenge.ticket.complete(CaptchaTicket(parameter, userAgent.take(500)))
    }
    @JavascriptInterface fun failed(message: String) {
        challenge.ticket.completeExceptionally(IllegalStateException(message.take(160)))
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CaptchaDialog(challenge: CaptchaChallenge, onCancel: () -> Unit) {
    val context = LocalContext.current
    val html = remember { context.assets.open("captcha.html").bufferedReader().use { it.readText() } }
    var view by remember(challenge.id) { mutableStateOf<WebView?>(null) }
    LaunchedEffect(challenge.id, view) {
        val web = view ?: return@LaunchedEffect
        val reply = challenge.reply.await()
        withContext(Dispatchers.Main.immediate) {
            web.evaluateJavascript("window.completeNativeCaptcha && window.completeNativeCaptcha(${reply.captchaPassed},${reply.outcome == Outcome.SUCCESS})") {
                challenge.acknowledged.complete(Unit)
            }
        }
    }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 480.dp).fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(top = 24.dp)) {
                Text("安全验证", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 24.dp))
                Text(challenge.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                AndroidView(modifier = Modifier.fillMaxWidth().heightIn(min = 360.dp, max = 460.dp), factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(Color.WHITE)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        // This bridge only delivers a captcha ticket. It exposes no accounts, coordinates or arbitrary requests.
                        addJavascriptInterface(CaptchaBridge(challenge), "NativeCaptcha")
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                        }
                        loadDataWithBaseURL("https://skl.hdu.edu.cn/", html, "text/html", "UTF-8", null)
                        view = this
                    }
                }, onRelease = { web ->
                    web.removeJavascriptInterface("NativeCaptcha")
                    web.stopLoading()
                    web.destroy()
                })
                TextButton(onClick = onCancel, modifier = Modifier.padding(horizontal = 16.dp).align(androidx.compose.ui.Alignment.End)) {
                    Text("停止批量签到")
                }
            }
        }
    }
}
