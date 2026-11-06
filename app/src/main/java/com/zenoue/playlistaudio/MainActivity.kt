package com.zenoue.playlistaudio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import android.widget.ImageView
import android.graphics.BitmapFactory
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var url: EditText
    private lateinit var from: EditText
    private lateinit var count: EditText
    private lateinit var trimStart: EditText
    private lateinit var trimEnd: EditText
    private lateinit var speed: EditText
    private lateinit var group: EditText
    private lateinit var run: Button
    private lateinit var mono: android.widget.CheckBox
    private lateinit var copyMode: android.widget.CheckBox

    private val coverFile by lazy { File(filesDir, "custom_cover") }
    private val pickCover = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            contentResolver.openInputStream(uri)!!.use { i -> coverFile.outputStream().use { i.copyTo(it) } }
            prefs.edit().putBoolean("customCover", true).apply()
        }.onFailure { toast("Không đọc được ảnh") }
        showCover()
        findViewById<View>(R.id.savePick).setOnClickListener { if (!JobState.running.value) pickSave.launch(null) }
        findViewById<View>(R.id.saveClear).setOnClickListener {
            prefs.getString("saveTree", null)?.let { t ->
                runCatching { contentResolver.releasePersistableUriPermission(android.net.Uri.parse(t),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            }
            prefs.edit().remove("saveTree").remove("saveLabel").apply()
            showSave()
        findViewById<View>(R.id.cookieLogin).setOnClickListener {
            if (JobState.running.value) return@setOnClickListener toast("Đang chạy, dừng trước đã")
            runCatching { startActivity(Intent(this, LoginActivity::class.java)) }
                .onFailure { toast("Không mở được trang đăng nhập: ${it.message}") }
        }
        findViewById<View>(R.id.cookieClear).setOnClickListener {
            CookieStore.clear(this); showCookies()
        }
        }
        showSave()
    }

    private val pickSave = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            prefs.edit().putString("saveTree", uri.toString()).putString("saveLabel", treeLabel(uri)).apply()
        }.onFailure { toast("Không lấy được quyền ghi thư mục") }
        showSave()
    }

    private val prefs by lazy { getSharedPreferences("last", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        url = findViewById(R.id.url)
        from = findViewById(R.id.from)
        count = findViewById(R.id.count)
        trimStart = findViewById(R.id.trimStart)
        trimEnd = findViewById(R.id.trimEnd)
        speed = findViewById(R.id.speed)
        group = findViewById(R.id.group)
        run = findViewById(R.id.run)
        mono = findViewById(R.id.mono)
        copyMode = findViewById(R.id.copyMode)
        val status = findViewById<TextView>(R.id.status)
        val log = findViewById<TextView>(R.id.log)
        val progress = findViewById<LinearProgressIndicator>(R.id.progress)
        val scroll = findViewById<ScrollView>(R.id.logScroll)

        // Android 15 vẽ tràn viền: chừa chỗ cho thanh trạng thái / thanh điều hướng / bàn phím
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        url.setText(prefs.getString("url", ""))
        from.setText(prefs.getString("from", "1"))
        count.setText(prefs.getString("count", "10"))
        trimStart.setText(prefs.getString("trimStart", "10"))
        trimEnd.setText(prefs.getString("trimEnd", "30"))
        speed.setText(prefs.getString("speed", "1.25"))
        group.setText(prefs.getString("group", "10"))
        mono.isChecked = prefs.getBoolean("mono", true)
        copyMode.isChecked = prefs.getBoolean("copyMode", false)
        // Không mã hoá: giữ nguyên âm thanh gốc nên tốc độ / mono không áp dụng (chỉnh tốc độ trong app nghe)
        fun syncCopy() {
            val on = copyMode.isChecked
            speed.isEnabled = !on && !JobState.running.value
            mono.isEnabled = !on && !JobState.running.value
        }
        copyMode.setOnCheckedChangeListener { _, _ -> syncCopy() }
        syncCopy()
        handleShare(intent)
        findViewById<TextView>(R.id.version).text = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: ""
        findViewById<ImageView>(R.id.coverPreview).setOnClickListener {
            if (!JobState.running.value) pickCover.launch("image/*")
        }
        findViewById<View>(R.id.coverClear).setOnClickListener {
            prefs.edit().putBoolean("customCover", false).apply()
            coverFile.delete()
            showCover()
        }
        showCover()

        findViewById<Button>(R.id.openTag).setOnClickListener {
            startActivity(Intent(this, TagActivity::class.java).putExtra("link", url.text.toString().trim()))
        }

        run.setOnClickListener {
            if (JobState.running.value) {
                startService(Intent(this, ProcessService::class.java).setAction(ProcessService.ACTION_STOP))
            } else start()
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        if (Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 2)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { JobState.status.collect { status.text = it } }
                launch { JobState.progress.collect { progress.setProgressCompat(it, true) } }
                launch {
                    JobState.running.collect {
                        run.text = if (it) "Dừng" else "Bắt đầu"
                        listOf(url, from, count, trimStart, trimEnd, group, copyMode, findViewById<View>(R.id.coverPreview), findViewById<View>(R.id.coverClear), findViewById<View>(R.id.savePick), findViewById<View>(R.id.saveClear)).forEach { v -> v.isEnabled = !it }
                        speed.isEnabled = !it && !copyMode.isChecked
                        mono.isEnabled = !it && !copyMode.isChecked
                    }
                }
                launch {
                    JobState.log.collect {
                        log.text = it
                        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND || JobState.running.value) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        Regex("https?://\\S+").find(text)?.let { url.setText(it.value) }
    }

    private fun start() {
        val link = url.text.toString().trim()
        if (!link.startsWith("http")) return toast("Dán link YouTube trước")
        val params = try {
            Params(
                url = link,
                from = from.text.toString().ifBlank { "1" }.toInt().coerceAtLeast(1),
                count = count.text.toString().ifBlank { "0" }.toInt().coerceAtLeast(0),
                trimStart = Params.parseTime(trimStart.text.toString()),
                trimEnd = Params.parseTime(trimEnd.text.toString()),
                speed = speed.text.toString().replace(',', '.').ifBlank { "1" }.toDouble(),
                group = group.text.toString().ifBlank { "10" }.toInt().coerceAtLeast(1),
                mono = mono.isChecked,
                copyMode = copyMode.isChecked,
                coverPath = if (hasCustomCover()) coverFile.absolutePath else "",
                saveTree = prefs.getString("saveTree", "") ?: "",
                saveLabel = prefs.getString("saveLabel", null) ?: Params.DEFAULT_SAVE_LABEL,
            )
        } catch (e: Exception) {
            return toast("Giá trị nhập không hợp lệ")
        }
        if (params.speed < 0.5 || params.speed > 4) return toast("Tốc độ nên trong khoảng 0.5 – 4")
        if (params.trimStart < 0 || params.trimEnd < 0) return toast("Thời gian cắt không được âm")

        prefs.edit()
            .putString("url", link).putString("from", from.text.toString())
            .putString("count", count.text.toString()).putString("trimStart", trimStart.text.toString())
            .putString("trimEnd", trimEnd.text.toString()).putString("speed", speed.text.toString())
            .putString("group", group.text.toString()).putBoolean("mono", mono.isChecked).putBoolean("copyMode", copyMode.isChecked).apply()

        ContextCompat.startForegroundService(
            this, Intent(this, ProcessService::class.java).putExtras(params.toBundle())
        )
    }

    /** content://…/tree/primary%3AMusic%2FTruyen -> "Music/Truyen" */
    private fun treeLabel(uri: android.net.Uri): String {
        val id = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return uri.toString()
        val vol = id.substringBefore(':')
        val path = id.substringAfter(':', "")
        val root = if (vol == "primary") "Bộ nhớ trong" else "Thẻ nhớ"
        return if (path.isEmpty()) root else path
    }

    private fun showSave() {
        val custom = prefs.getString("saveTree", null) != null
        findViewById<TextView>(R.id.saveLabel).text =
            "Lưu vào: " + (if (custom) prefs.getString("saveLabel", "") else Params.DEFAULT_SAVE_LABEL)
        findViewById<View>(R.id.saveClear).visibility = if (custom) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        showCookies()
    }

    private fun showCookies() {
        val has = CookieStore.hasCookies(this)
        findViewById<TextView>(R.id.cookieLabel).text = if (has) "YouTube: đã đăng nhập ✓" else "YouTube: chưa đăng nhập"
        findViewById<View>(R.id.cookieClear).visibility = if (has) View.VISIBLE else View.GONE
        (findViewById<Button>(R.id.cookieLogin)).text = if (has) "Đăng nhập lại" else "Đăng nhập YouTube"
    }

    private fun hasCustomCover() = prefs.getBoolean("customCover", false) && coverFile.exists()

    private fun showCover() {
        val iv = findViewById<ImageView>(R.id.coverPreview)
        val custom = hasCustomCover()
        findViewById<TextView>(R.id.coverLabel).text = if (custom) "Ảnh riêng" else "Ảnh bìa"
        findViewById<View>(R.id.coverClear).visibility = if (custom) View.VISIBLE else View.GONE
        if (!custom) { iv.setImageResource(R.drawable.ic_image); return }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(coverFile.absolutePath, o)
        var sample = 1
        while (o.outWidth / sample > 256 || o.outHeight / sample > 256) sample *= 2
        iv.setImageBitmap(BitmapFactory.decodeFile(coverFile.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
