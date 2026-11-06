package com.zenoue.playlistaudio

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Đăng nhập YouTube trong WebView, rồi lưu cookies cho yt-dlp. */
class LoginActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private var saving = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById<LinearLayout>(R.id.loginRoot)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom); insets
        }
        val title = findViewById<TextView>(R.id.loginTitle)
        web = findViewById(R.id.web)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, req: WebResourceRequest?) =
                req?.url?.scheme?.startsWith("http") != true
            override fun onPageFinished(view: WebView?, url: String?) {
                title.text = view?.title ?: ""
                // Cookies lưu lúc đang ở robots.txt sống lâu hơn (YouTube không xoay vòng cookies trên trang này)
                if (saving && url?.contains("robots.txt") == true) save()
            }
        }
        findViewById<Button>(R.id.loginDone).setOnClickListener {
            saving = true
            web.loadUrl("https://www.youtube.com/robots.txt")
        }
        web.loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fm.youtube.com%2F")
    }

    private fun save() = lifecycleScope.launch {
        saving = false
        val msg = withContext(Dispatchers.IO) {
            runCatching { "Đã lưu ${CookieStore.export(this@LoginActivity)} cookies" }
                .getOrElse { "Lỗi: ${it.message}" }
        }
        Toast.makeText(this@LoginActivity, msg, Toast.LENGTH_LONG).show()
        if (!msg.startsWith("Lỗi")) finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }
}
