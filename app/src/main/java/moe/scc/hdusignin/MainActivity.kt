package moe.scc.hdusignin

import android.os.Bundle
import android.util.Log
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import moe.scc.hdusignin.logic.MoeWebViewClient

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var tvUrl: TextView
    private lateinit var btnSignIn: Button
    private lateinit var btnLogin: Button
    private lateinit var btnClearCookies: Button

    private val LOGIN_URL = "https://skl.hdu.edu.cn/api/login/dingtalk/auth?index=&code=0&authCode=0&state=0"
    private val SIGNIN_URL = "https://skl.hdu.edu.cn/#/sign/in"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        tvUrl = findViewById(R.id.tv_url)
        btnSignIn = findViewById(R.id.btn_signin)
        btnLogin = findViewById(R.id.btn_login)
        btnClearCookies = findViewById(R.id.btn_clear_cookies)

        setupWebView()

        btnLogin.setOnClickListener {
            updateUrlText("Loading Login...")
            webView.loadUrl(LOGIN_URL)
        }

        btnClearCookies.setOnClickListener {
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.widget.Toast.makeText(this, "Cookies Cleared", android.widget.Toast.LENGTH_SHORT).show()
            Log.i("HDUSignIn", "Cookies cleared by user")
        }

        btnSignIn.setOnClickListener {
            updateUrlText("Loading Sign In Page...")
            webView.loadUrl(SIGNIN_URL)
        }
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), 100)
        }
    }

    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.setGeolocationEnabled(true)
        settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        webView.webViewClient = MoeWebViewClient(
            onPageFinishedCallback = { url ->
                updateUrlText(url)
                val cookie = android.webkit.CookieManager.getInstance().getCookie(url)
                Log.d("HDUSignIn", "Cookie: $cookie")
            },
            onLog = { msg -> Log.i("HDUSignIn", msg) }
        )

        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, true, false)
            }
        }
    }

    private fun updateUrlText(text: String?) {
        runOnUiThread {
            tvUrl.text = text ?: ""
        }
    }
}
