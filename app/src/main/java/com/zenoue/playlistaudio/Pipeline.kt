package com.zenoue.playlistaudio

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.Locale

class CancelledByUser : Exception("Đã dừng")

/**
 * 1. Lấy danh sách video  2. Tải audio từng video
 * 3. ffmpeg: cắt đầu/cuối + tăng tốc -> mp3   4. Gộp mỗi N file   5. Lưu vào Download/PlaylistAudio
 */
class Pipeline(private val ctx: Context, private val p: Params) {

    @Volatile private var cancelled = false
    private val ffProcs = java.util.Collections.synchronizedSet(mutableSetOf<Process>())
    @Volatile private var pool: java.util.concurrent.ExecutorService? = null
    private val prefs = ctx.getSharedPreferences("pipeline", Context.MODE_PRIVATE)
    // Cache của yt-dlp (player JS + kết quả giải challenge). Thư viện mặc định TẮT cache
    // -> mỗi video phải tải lại player và chạy QuickJS giải lại từ đầu => rất chậm.
    private val ytCache = File(ctx.filesDir, "yt-dlp-cache")
    private val ytId = "playlistaudio-dl"
    private val work = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "work")

    private data class Entry(val index: Int, val id: String, val title: String)

    fun cancel() {
        cancelled = true
        runCatching { YoutubeDL.getInstance().destroyProcessById(ytId) }
        pool?.shutdownNow()
        synchronized(ffProcs) { ffProcs.forEach { it.destroy() } }
    }

    private fun checkCancel() { if (cancelled) throw CancelledByUser() }
    private fun log(s: String) = JobState.log(s)
    private fun status(s: String) { JobState.status.value = s; log("» $s") }
    private fun progress(f: Double) { JobState.progress.value = (f.coerceIn(0.0, 1.0) * 1000).toInt() }

    fun run(): List<String> {
        status("Khởi tạo (lần đầu hơi lâu)…")
        YoutubeDL.getInstance().init(ctx)
        FFmpeg.getInstance().init(ctx)

        // Chỉ kiểm tra cập nhật yt-dlp tối đa 1 lần / 12 giờ
        val lastUpdate = prefs.getLong("ytdlpUpdate", 0)
        if (System.currentTimeMillis() - lastUpdate > 12 * 3600_000L) {
            status("Cập nhật yt-dlp…")
            timed("Cập nhật yt-dlp") {
                try {
                    val r = YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.STABLE)
                    prefs.edit().putLong("ytdlpUpdate", System.currentTimeMillis()).apply()
                    log("yt-dlp ${YoutubeDL.getInstance().versionName(ctx)} ($r)")
                } catch (e: Exception) {
                    log("Không cập nhật được yt-dlp: ${e.message?.take(200)}")
                }
            }
        } else log("yt-dlp ${YoutubeDL.getInstance().versionName(ctx)}")
        checkCancel()

        // 1. Danh sách
        status("Đọc danh sách video…")
        val listReq = YoutubeDLRequest(p.url).apply {
            addOption("--flat-playlist")
            addOption("--yes-playlist")
            addOption("--ignore-errors")
            addOption("--print", "%(playlist_title|)s\t%(id)s\t%(title)s")
            addOption("-I", if (p.count > 0) "${p.from}:${p.from + p.count - 1}" else "${p.from}:")
        }
        listReq.addOption("--cache-dir", ytCache.absolutePath)
        val listOut = timed("Đọc danh sách") { YoutubeDL.getInstance().execute(listReq, ytId).out }
        var playlistTitle = ""
        val entries = listOut.lines().filter { it.contains('\t') }.mapIndexed { i, line ->
            val f = line.split('\t')
            if (playlistTitle.isEmpty()) playlistTitle = f[0]
            Entry(p.from + i, f[1], f.getOrElse(2) { f[1] })
        }
        if (entries.isEmpty()) throw Exception("Không tìm thấy video nào. Kiểm tra lại link.")
        log("Playlist: ${playlistTitle.ifBlank { "(video lẻ)" }} — ${entries.size} video")
        checkCancel()

        // Thư mục làm việc riêng cho mỗi bộ tham số -> chạy lại sẽ tiếp tục, không tải lại
        val key = Integer.toHexString("${p.url}|${p.trimStart}|${p.trimEnd}|${p.speed}|${p.mono}".hashCode())
        val dir = File(work, key).apply { mkdirs() }
        work.listFiles()?.filter { it.name != key }?.forEach { it.deleteRecursively() }

        // 2+3. Tải tuần tự; cắt + tăng tốc chạy song song trên các nhân CPU còn lại
        // (video sau được tải trong lúc video trước đang được xử lý).
        val total = entries.size
        val results = arrayOfNulls<File>(total)
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 3)
        val exec = java.util.concurrent.Executors.newFixedThreadPool(threads).also { pool = it }
        val futures = mutableListOf<java.util.concurrent.Future<*>>()
        val dlDone = java.util.concurrent.atomic.AtomicInteger()
        val procDone = java.util.concurrent.atomic.AtomicInteger()
        fun upd(partial: Double = 0.0) =
            progress((dlDone.get() + procDone.get() + partial) / (2.0 * total) * 0.9)
        fun stat() { JobState.status.value = "Đã tải ${dlDone.get()}/$total · Đã xử lý ${procDone.get()}/$total" }

        try {
            for ((n, e) in entries.withIndex()) {
                checkCancel()
                val tag = "[${n + 1}/$total]"
                val out = File(dir, String.format(Locale.US, "%04d.mp3", e.index))
                if (out.exists() && out.length() > 0) {
                    log("$tag Đã có sẵn: ${e.title}")
                    results[n] = out; dlDone.incrementAndGet(); procDone.incrementAndGet(); upd(); continue
                }
                val dl = try {
                    JobState.status.value = "$tag Đang tải: ${e.title}"
                    timed("$tag Tải") { download(e, dir) { f -> upd(f) } }
                } catch (ex: Exception) {
                    if (cancelled) throw CancelledByUser()
                    log("$tag LỖI tải, bỏ qua: ${ex.message?.lines()?.lastOrNull { it.isNotBlank() }?.take(300)}")
                    null
                }
                dlDone.incrementAndGet(); upd(); stat()
                if (dl == null) { procDone.incrementAndGet(); continue }
                val (raw, dur) = dl
                futures += exec.submit {
                    try {
                        processOne(tag, raw, dur, out)?.let { results[n] = it }
                    } catch (ex: Exception) {
                        if (!cancelled) log("$tag LỖI xử lý, bỏ qua: ${ex.message?.take(300)}")
                    } finally {
                        procDone.incrementAndGet(); upd(); stat()
                    }
                }
            }
            for (f in futures) { checkCancel(); runCatching { f.get() } }
        } finally {
            exec.shutdownNow()
        }
        checkCancel()
        val processed = entries.indices.mapNotNull { i -> results[i]?.let { entries[i].index to it } }
        if (processed.isEmpty()) throw Exception("Không xử lý được video nào")

        // 4+5. Gộp + lưu
        val groups = processed.chunked(p.group)
        val width = maxOf(2, groups.size.toString().length)
        val base = sanitize(playlistTitle.ifBlank { entries.first().title }).take(60)
        val saved = mutableListOf<String>()
        for ((g, chunk) in groups.withIndex()) {
            checkCancel()
            val first = chunk.first().first
            val last = chunk.last().first
            val name = "${base}_${String.format(Locale.US, "%0${width}d", g + 1)}_v$first-$last.mp3"
            status("Gộp ${g + 1}/${groups.size}: ${chunk.size} audio")
            val listFile = File(dir, "concat.txt")
            listFile.writeText(chunk.joinToString("") { "file '${it.second.absolutePath.replace("'", "'\\''")}'\n" })
            val merged = File(dir, "merged.mp3")
            timed("Gộp ${g + 1}") {
                ffmpeg(listOf("-f", "concat", "-safe", "0", "-i", listFile.absolutePath, "-c", "copy", merged.absolutePath))
            }
            saved += export(merged, name)
            merged.delete()
            progress(0.9 + 0.1 * (g + 1) / groups.size)
        }
        dir.deleteRecursively()
        progress(1.0)
        return saved
    }

    /** Cắt đầu/cuối + tăng tốc -> mp3. Trả về file kết quả hoặc null nếu bỏ qua. */
    private fun processOne(tag: String, raw: File, dur: Double, out: File): File? {
        val keep = dur - p.trimStart - p.trimEnd
        if (keep <= 1) {
            log("$tag Bỏ qua: video dài ${dur.toInt()}s, cắt xong không còn gì")
            raw.delete(); return null
        }
        val tmp = File(out.parentFile, out.name + ".tmp.mp3")
        timed("$tag Cắt + tăng tốc ${p.speed}x") {
            ffmpeg(
                listOf(
                    "-ss", fmt(p.trimStart), "-t", fmt(keep), "-i", raw.absolutePath,
                    "-vn", "-af", atempo(p.speed),
                    "-ac", if (p.mono) "1" else "2",
                    // compression_level 7 = chế độ nhanh của LAME, chất lượng vẫn tốt cho giọng nói
                    "-c:a", "libmp3lame", "-b:a", "${p.bitrateK}k", "-compression_level", "7",
                    tmp.absolutePath,
                ),
            )
        }
        tmp.renameTo(out)
        raw.delete()
        return out
    }

    private inline fun <T> timed(label: String, block: () -> T): T {
        val t0 = System.currentTimeMillis()
        val r = block()
        log(String.format(Locale.US, "%s: %.1fs", label, (System.currentTimeMillis() - t0) / 1000.0))
        return r
    }

    /** Tải audio tốt nhất (không chuyển đổi, để ffmpeg xử lý 1 lần). Trả về (file, thời lượng giây). */
    private fun download(e: Entry, dir: File, onProgress: (Double) -> Unit): Pair<File, Double> {
        val req = YoutubeDLRequest("https://www.youtube.com/watch?v=${e.id}").apply {
            addOption("-f", "bestaudio[ext=m4a]/bestaudio/best")
            addOption("--no-playlist")
            addOption("--cache-dir", ytCache.absolutePath)
            addOption("--no-mtime")
            addOption("--retries", "10")
            addOption("--fragment-retries", "10")
            addOption("-o", File(dir, "raw_${e.index}.%(ext)s").absolutePath)
            addOption("--no-simulate")
            addOption("--progress")  // --print bật chế độ quiet; vẫn cần dòng tiến độ
            addOption("--newline")
            addOption("--print", "after_move:RESULT\t%(duration)s\t%(filepath)s")
        }
        val resp = YoutubeDL.getInstance().execute(req, ytId) { pct, _, _ -> onProgress(pct / 100.0) }
        val line = resp.out.lines().lastOrNull { it.startsWith("RESULT\t") }
            ?: throw Exception("Không đọc được kết quả tải")
        val f = line.split('\t')
        val file = File(f[2])
        if (!file.exists()) throw Exception("Không thấy file đã tải: ${file.name}")
        val dur = f[1].toDoubleOrNull() ?: probeDuration(file)
        return file to dur
    }

    private fun libDir() = File(ctx.applicationInfo.nativeLibraryDir)

    private fun env(pb: ProcessBuilder) {
        val pk = File(ctx.noBackupFilesDir, "youtubedl-android/packages")
        pb.environment()["LD_LIBRARY_PATH"] =
            "${pk.absolutePath}/ffmpeg/usr/lib:${pk.absolutePath}/python/usr/lib"
        pb.environment()["TMPDIR"] = ctx.cacheDir.absolutePath
    }

    private fun probeDuration(file: File): Double {
        val pb = ProcessBuilder(File(libDir(), "libffmpeg.so").absolutePath, "-hide_banner", "-i", file.absolutePath)
            .redirectErrorStream(true)
        env(pb)
        val text = pb.start().let { pr -> pr.inputStream.bufferedReader().readText().also { pr.waitFor() } }
        val m = Regex("Duration: (\\d+):(\\d+):(\\d+(?:\\.\\d+)?)").find(text)
            ?: throw Exception("Không đọc được thời lượng")
        val (h, mi, s) = m.destructured
        return h.toDouble() * 3600 + mi.toDouble() * 60 + s.toDouble()
    }

    private fun ffmpeg(args: List<String>) {
        val cmd = listOf(File(libDir(), "libffmpeg.so").absolutePath, "-hide_banner", "-nostdin", "-y",
            "-loglevel", "error") + args
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        env(pb)
        val proc = pb.start()
        ffProcs += proc
        val text = try {
            proc.inputStream.bufferedReader().readText()
        } finally {
            proc.waitFor(); ffProcs -= proc
        }
        checkCancel()
        if (proc.exitValue() != 0) throw Exception("ffmpeg lỗi (${proc.exitValue()}): ${text.takeLast(400)}")
    }

    /** Lưu vào Download/PlaylistAudio (thấy được trong app Files / trình nghe nhạc). */
    private fun export(src: File, name: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PlaylistAudio")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("Không tạo được file trong Download")
            resolver.openOutputStream(uri)!!.use { o -> src.inputStream().use { it.copyTo(o) } }
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            @Suppress("DEPRECATION")
            val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PlaylistAudio")
            d.mkdirs()
            src.copyTo(File(d, name), overwrite = true)
        }
        log("Đã lưu: Download/PlaylistAudio/$name")
        return name
    }

    companion object {
        fun fmt(d: Double) = String.format(Locale.US, "%.3f", d)

        /** atempo an toàn: tách thành chuỗi hệ số trong [0.5, 2.0]. */
        fun atempo(speed: Double): String {
            var s = speed
            val parts = mutableListOf<Double>()
            while (s > 2.0) { parts += 2.0; s /= 2.0 }
            while (s < 0.5) { parts += 0.5; s /= 0.5 }
            parts += s
            return parts.joinToString(",") { "atempo=" + String.format(Locale.US, "%.6f", it) }
        }

        fun sanitize(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
            .replace(Regex("\\s+"), " ").trim().ifEmpty { "audio" }
    }
}
