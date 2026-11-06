package com.zenoue.playlistaudio

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Đăng nhập YouTube trong WebView rồi lưu cookies cho yt-dlp.
 * Google chặn đăng nhập trong WebView nếu nhận ra là WebView (chuỗi "; wv" trong User-Agent và header
 * X-Requested-With) -> dùng User-Agent Chrome máy tính và tắt header đó (nếu WebView hỗ trợ).
 * Cách khác: nhập file cookies.txt xuất từ trình duyệt / app khác, hoặc dán từ clipboard.
 */
class LoginActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private var saving = false
    private var desktop = true

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        val text = runCatching { contentResolver.openInputStream(uri)!!.bufferedReader().readText() }.getOrNull()
        importText(text)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById<LinearLayout>(R.id.loginRoot)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom); insets
        }
        val title = findViewById<TextView>(R.id.loginTitle)
        web = try {
            findViewById(R.id.web)
        } catch (e: Exception) {
            Toast.makeText(this, "Máy không có WebView: ${e.message}. Dùng \"Nhập file\".", Toast.LENGTH_LONG).show()
            return
        }
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(web.settings, emptySet())
        }
        applyUa()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, req: WebResourceRequest?) =
                req?.url?.scheme?.startsWith("http") != true
            override fun onPageFinished(view: WebView?, url: String?) {
                title.text = view?.title ?: url ?: ""
                // Lưu lúc đang ở robots.txt: YouTube không xoay vòng cookies ở trang này nên cookies dùng lâu hơn
                if (saving && url?.contains("robots.txt") == true) save()
            }
        }
        findViewById<Button>(R.id.loginDone).setOnClickListener {
            saving = true
            web.loadUrl("https://www.youtube.com/robots.txt")
        }
        findViewById<Button>(R.id.loginUa).setOnClickListener {
            desktop = !desktop; applyUa(); web.reload()
        }
        findViewById<Button>(R.id.loginFile).setOnClickListener { pickFile.launch(arrayOf("text/*", "*/*")) }
        findViewById<Button>(R.id.loginPaste).setOnClickListener {
            val cb = getSystemService(ClipboardManager::class.java)
            importText(cb.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString())
        }
        web.loadUrl(LOGIN_URL)
    }

    private fun applyUa() {
        web.settings.userAgentString = if (desktop) DESKTOP_UA
        else WebSettings.getDefaultUserAgent(this).replace("; wv", "")
        findViewById<Button>(R.id.loginUa).text = if (desktop) "Máy tính" else "Điện thoại"
    }

    private fun importText(text: String?) {
        if (text.isNullOrBlank()) return toast("Không đọc được nội dung")
        runCatching { CookieStore.import(this, text) }
            .onSuccess { toast("Đã nhập $it cookie YouTube"); finish() }
            .onFailure { toast("Lỗi: ${it.message}") }
    }

    private fun save() = lifecycleScope.launch {
        saving = false
        val r = withContext(Dispatchers.IO) { runCatching { CookieStore.export(this@LoginActivity) } }
        r.onSuccess { toast("Đã lưu $it cookies"); finish() }
            .onFailure { toast("Lỗi: ${it.message}") }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    companion object {
        const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        // Trang đăng nhập Google dành cho YouTube, xong quay về youtube.com
        const val LOGIN_URL = "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true" +
            "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Ddesktop%26next%3D%252F"
    }
}
