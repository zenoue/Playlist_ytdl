package com.zenoue.playlistaudio

import android.content.ContentValues
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Chỉ ghi lại ảnh bìa + tag ID3 cho 1 file mp3 có sẵn (-c copy, không mã hoá lại audio). */
class TagActivity : AppCompatActivity() {

    private var fileUri: Uri? = null
    private var imgUri: Uri? = null
    private lateinit var status: TextView

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        fileUri = uri
        loadTags(uri)
    }
    private val pickImg = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        imgUri = uri
        contentResolver.openInputStream(uri)?.use {
            findViewById<ImageView>(R.id.preview).setImageBitmap(BitmapFactory.decodeStream(it))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tag)
        val scroll = findViewById<ScrollView>(R.id.scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom); insets
        }
        status = findViewById(R.id.tagStatus)
        findViewById<EditText>(R.id.ytLink).setText(intent.getStringExtra("link") ?: "")
        findViewById<EditText>(R.id.ytIndex).setText("1")

        findViewById<Button>(R.id.pickFile).setOnClickListener { pickFile.launch(arrayOf("audio/*")) }
        findViewById<Button>(R.id.pickImg).setOnClickListener { pickImg.launch("image/*") }
        findViewById<RadioGroup>(R.id.coverSrc).setOnCheckedChangeListener { _, id ->
            findViewById<View>(R.id.ytBox).visibility = if (id == R.id.srcYt) View.VISIBLE else View.GONE
            findViewById<View>(R.id.pickImg).visibility = if (id == R.id.srcImg) View.VISIBLE else View.GONE
        }
        findViewById<Button>(R.id.write).setOnClickListener { write() }
    }

    private fun text(id: Int) = findViewById<EditText>(id).text.toString().trim()

    private fun loadTags(uri: Uri) {
        var name = "audio.mp3"
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0) ?: name
        }
        findViewById<TextView>(R.id.fileName).text = name
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(this, uri)
            fun set(id: Int, key: Int, def: String = "") =
                findViewById<EditText>(id).setText(r.extractMetadata(key)?.takeIf { it.isNotBlank() } ?: def)
            set(R.id.title, MediaMetadataRetriever.METADATA_KEY_TITLE, name.substringBeforeLast('.'))
            set(R.id.album, MediaMetadataRetriever.METADATA_KEY_ALBUM)
            set(R.id.artist, MediaMetadataRetriever.METADATA_KEY_ARTIST)
            set(R.id.track, MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
            r.embeddedPicture?.let {
                findViewById<ImageView>(R.id.preview).setImageBitmap(BitmapFactory.decodeByteArray(it, 0, it.size))
            }
        } catch (_: Exception) {
        } finally {
            runCatching { r.release() }
        }
    }

    private fun write() {
        val uri = fileUri ?: return run { status.text = "Chọn file mp3 trước" }
        val src = findViewById<RadioGroup>(R.id.coverSrc).checkedRadioButtonId
        if (src == R.id.srcImg && imgUri == null) return run { status.text = "Chọn ảnh trước" }
        val link = text(R.id.ytLink)
        if (src == R.id.srcYt && !link.startsWith("http")) return run { status.text = "Nhập link YouTube" }
        val idx = text(R.id.ytIndex).toIntOrNull() ?: 1
        val title = text(R.id.title); val album = text(R.id.album)
        val artist = text(R.id.artist); val track = text(R.id.track)
        val btn = findViewById<Button>(R.id.write)
        btn.isEnabled = false

        lifecycleScope.launch {
            val msg = withContext(Dispatchers.IO) {
                val dir = File(cacheDir, "tag").apply { deleteRecursively(); mkdirs() }
                try {
                    YoutubeDL.getInstance().init(applicationContext)
                    FFmpeg.getInstance().init(applicationContext)

                    val cover: File? = when (src) {
                        R.id.srcYt -> { say("Lấy thumbnail từ YouTube…"); fetchThumb(link, idx, dir) }
                        R.id.srcImg -> File(dir, "img").also { f ->
                            contentResolver.openInputStream(imgUri!!)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
                        }
                        else -> null
                    }?.let { raw ->
                        File(dir, "cover.jpg").also {
                            Ff.run(this@TagActivity, listOf("-i", raw.absolutePath, "-frames:v", "1",
                                "-vf", "scale=640:-2", "-q:v", "3", it.absolutePath))
                        }
                    }
                    cover?.let { c ->
                        withContext(Dispatchers.Main) {
                            findViewById<ImageView>(R.id.preview).setImageBitmap(BitmapFactory.decodeFile(c.absolutePath))
                        }
                    }

                    say("Đọc file…")
                    val input = File(dir, "in.mp3")
                    contentResolver.openInputStream(uri)!!.use { i -> input.outputStream().use { i.copyTo(it) } }

                    say("Ghi tag…")
                    val out = File(dir, "out.mp3")
                    val args = mutableListOf("-i", input.absolutePath)
                    if (cover != null) args += listOf("-i", cover.absolutePath, "-map", "0:a", "-map", "1:v")
                    else args += listOf("-map", "0")
                    args += listOf("-c", "copy", "-id3v2_version", "3",
                        "-metadata", "title=$title", "-metadata", "album=$album",
                        "-metadata", "artist=$artist", "-metadata", "track=$track")
                    if (cover != null) args += listOf("-disposition:v", "attached_pic",
                        "-metadata:s:v", "title=Album cover", "-metadata:s:v", "comment=Cover (front)")
                    args += out.absolutePath
                    Ff.run(this@TagActivity, args)

                    say("Lưu…")
                    try {
                        contentResolver.openOutputStream(uri, "wt")!!.use { o -> out.inputStream().use { it.copyTo(o) } }
                        "Xong! Đã ghi đè file gốc."
                    } catch (e: Exception) {
                        val name = "${title.ifBlank { "audio" }.replace(Regex("[\\\\/:*?\"<>|]"), " ")}.mp3"
                        saveNew(out, name)
                        "Không ghi đè được file gốc → đã lưu bản mới: Download/PlaylistAudio/$name"
                    }
                } catch (e: Exception) {
                    "Lỗi: ${e.message?.lines()?.lastOrNull { it.isNotBlank() }?.take(300)}"
                } finally {
                    dir.deleteRecursively()
                }
            }
            status.text = msg
            btn.isEnabled = true
        }
    }

    private suspend fun say(s: String) = withContext(Dispatchers.Main) { status.text = s }

    private fun fetchThumb(link: String, idx: Int, dir: File): File {
        val isList = link.contains("list=")
        val req = YoutubeDLRequest(link).apply {
            addOption("--skip-download")
            addOption("--write-thumbnail")
            addOption("--no-write-playlist-metafiles")
            addOption("--extractor-args", "youtube:player_client=visionos")
            addOption("--cache-dir", File(filesDir, "yt-dlp-cache").absolutePath)
            if (isList) { addOption("--yes-playlist"); addOption("-I", "$idx") } else addOption("--no-playlist")
            addOption("-o", "thumbnail:" + File(dir, "thumb.%(ext)s").absolutePath)
        }
        YoutubeDL.getInstance().execute(req, "tag-thumb")
        return dir.listFiles()?.firstOrNull { it.name.startsWith("thumb.") }
            ?: throw Exception("Không lấy được thumbnail")
    }

    private fun saveNew(src: File, name: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PlaylistAudio")
            }
            val u = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
            contentResolver.openOutputStream(u)!!.use { o -> src.inputStream().use { it.copyTo(o) } }
        } else {
            @Suppress("DEPRECATION")
            val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PlaylistAudio")
            d.mkdirs(); src.copyTo(File(d, name), overwrite = true)
        }
    }
}
