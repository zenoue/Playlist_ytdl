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

    private val coverFile by lazy { File(filesDir, "custom_cover") }
    private val pickCover = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            contentResolver.openInputStream(uri)!!.use { i -> coverFile.outputStream().use { i.copyTo(it) } }
            prefs.edit().putBoolean("customCover", true).apply()
        }.onFailure { toast("Không đọc được ảnh") }
        showCover()
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
                        listOf(url, from, count, trimStart, trimEnd, speed, group, mono, findViewById<View>(R.id.coverPreview), findViewById<View>(R.id.coverClear)).forEach { v -> v.isEnabled = !it }
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
                coverPath = if (hasCustomCover()) coverFile.absolutePath else "",
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
            .putString("group", group.text.toString()).putBoolean("mono", mono.isChecked).apply()

        ContextCompat.startForegroundService(
            this, Intent(this, ProcessService::class.java).putExtras(params.toBundle())
        )
    }

    private fun hasCustomCover() = prefs.getBoolean("customCover", false) && coverFile.exists()

    private fun showCover() {
        val iv = findViewById<ImageView>(R.id.coverPreview)
        val custom = hasCustomCover()
        findViewById<TextView>(R.id.coverLabel).text = if (custom) "Ảnh bìa: ảnh riêng" else "Ảnh bìa: YouTube"
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
