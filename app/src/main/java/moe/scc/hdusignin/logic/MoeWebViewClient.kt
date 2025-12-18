package moe.scc.hdusignin.logic

import android.graphics.Bitmap
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient

class MoeWebViewClient(
    private val onPageFinishedCallback: (String) -> Unit,
    private val onLog: (String) -> Unit
) : WebViewClient() {

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        onLog("Page Started: $url")
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        onLog("Page Finished: $url")
        onPageFinishedCallback(url ?: "")
    }
}
