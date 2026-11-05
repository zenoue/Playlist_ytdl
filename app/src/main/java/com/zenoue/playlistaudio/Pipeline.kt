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
    @Volatile private var ffProc: Process? = null
    private val ytId = "playlistaudio-dl"
    private val work = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "work")

    private data class Entry(val index: Int, val id: String, val title: String)

    fun cancel() {
        cancelled = true
        runCatching { YoutubeDL.getInstance().destroyProcessById(ytId) }
        ffProc?.destroy()
    }

    private fun checkCancel() { if (cancelled) throw CancelledByUser() }
    private fun log(s: String) = JobState.log(s)
    private fun status(s: String) { JobState.status.value = s; log("» $s") }
    private fun progress(f: Double) { JobState.progress.value = (f.coerceIn(0.0, 1.0) * 1000).toInt() }

    fun run(): List<String> {
        status("Khởi tạo (lần đầu hơi lâu)…")
        YoutubeDL.getInstance().init(ctx)
        FFmpeg.getInstance().init(ctx)

        status("Cập nhật yt-dlp…")
        try {
            val r = YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.STABLE)
            log("yt-dlp ${YoutubeDL.getInstance().versionName(ctx)} ($r)")
        } catch (e: Exception) {
            log("Không cập nhật được yt-dlp: ${e.message?.take(200)}")
        }
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
        val listOut = YoutubeDL.getInstance().execute(listReq, ytId).out
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
        val key = Integer.toHexString("${p.url}|${p.trimStart}|${p.trimEnd}|${p.speed}|${p.bitrateK}".hashCode())
        val dir = File(work, key).apply { mkdirs() }
        work.listFiles()?.filter { it.name != key }?.forEach { it.deleteRecursively() }

        // 2+3. Tải + xử lý
        val processed = mutableListOf<Pair<Int, File>>()
        val total = entries.size
        for ((n, e) in entries.withIndex()) {
            checkCancel()
            val tag = "[${n + 1}/$total]"
            val out = File(dir, String.format(Locale.US, "%04d.mp3", e.index))
            if (out.exists() && out.length() > 0) {
                log("$tag Đã có sẵn: ${e.title}")
                processed += e.index to out; continue
            }
            try {
                status("$tag Tải: ${e.title}")
                val (raw, dur) = download(e, dir) { f -> progress((n + f * 0.6) / total * 0.9) }
                checkCancel()
                val keep = dur - p.trimStart - p.trimEnd
                if (keep <= 1) {
                    log("$tag Bỏ qua: video dài ${dur.toInt()}s, cắt xong không còn gì")
                    raw.delete(); continue
                }
                status("$tag Cắt + tăng tốc ${p.speed}x")
                val tmp = File(dir, out.name + ".tmp.mp3")
                ffmpeg(
                    listOf(
                        "-ss", fmt(p.trimStart), "-t", fmt(keep), "-i", raw.absolutePath,
                        "-vn", "-af", atempo(p.speed),
                        "-c:a", "libmp3lame", "-b:a", "${p.bitrateK}k", tmp.absolutePath,
                    ),
                    expectedSec = keep / p.speed,
                ) { f -> progress((n + 0.6 + f * 0.4) / total * 0.9) }
                tmp.renameTo(out)
                raw.delete()
                processed += e.index to out
                log("$tag Xong: ${e.title}")
            } catch (ex: CancelledByUser) {
                throw ex
            } catch (ex: Exception) {
                if (cancelled) throw CancelledByUser()
                log("$tag LỖI, bỏ qua video này: ${ex.message?.lines()?.lastOrNull { it.isNotBlank() }?.take(300)}")
            }
        }
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
            ffmpeg(listOf("-f", "concat", "-safe", "0", "-i", listFile.absolutePath, "-c", "copy", merged.absolutePath), 0.0) {}
            saved += export(merged, name)
            merged.delete()
            progress(0.9 + 0.1 * (g + 1) / groups.size)
        }
        dir.deleteRecursively()
        progress(1.0)
        return saved
    }

    /** Tải audio tốt nhất (không chuyển đổi, để ffmpeg xử lý 1 lần). Trả về (file, thời lượng giây). */
    private fun download(e: Entry, dir: File, onProgress: (Double) -> Unit): Pair<File, Double> {
        val req = YoutubeDLRequest("https://www.youtube.com/watch?v=${e.id}").apply {
            addOption("-f", "bestaudio[ext=m4a]/bestaudio/best")
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--retries", "10")
            addOption("--fragment-retries", "10")
            addOption("-o", File(dir, "raw_${e.index}.%(ext)s").absolutePath)
            addOption("--no-simulate")
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

    private val timeRe = Regex("time=(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)")

    private fun ffmpeg(args: List<String>, expectedSec: Double, onProgress: (Double) -> Unit) {
        val cmd = listOf(File(libDir(), "libffmpeg.so").absolutePath, "-hide_banner", "-nostdin", "-y") + args
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        env(pb)
        val proc = pb.start()
        ffProc = proc
        val tail = ArrayDeque<String>()
        val reader = proc.inputStream.reader()
        val sb = StringBuilder()
        while (true) {
            val c = reader.read()
            if (c == -1 || c == '\r'.code || c == '\n'.code) {
                val line = sb.toString(); sb.setLength(0)
                if (line.isNotBlank()) {
                    tail.addLast(line); if (tail.size > 8) tail.removeFirst()
                    if (expectedSec > 0) timeRe.find(line)?.destructured?.let { (h, m, s) ->
                        onProgress((h.toDouble() * 3600 + m.toDouble() * 60 + s.toDouble()) / expectedSec)
                    }
                }
                if (c == -1) break
            } else sb.append(c.toChar())
        }
        val code = proc.waitFor()
        ffProc = null
        checkCancel()
        if (code != 0) throw Exception("ffmpeg lỗi ($code): ${tail.joinToString(" | ").take(400)}")
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
