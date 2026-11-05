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
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.max

class CancelledByUser : Exception("Đã dừng")

/**
 * Giai đoạn 1: tải song song tất cả video (nhiều tiến trình yt-dlp cùng lúc).
 * Giai đoạn 2: chia mỗi video thành nhiều đoạn, mã hoá song song trên TẤT CẢ nhân CPU
 *              (LAME chỉ dùng 1 nhân/tiến trình -> chạy N tiến trình = N nhân).
 * Giai đoạn 3: ghép các đoạn thành file gộp bằng `-c copy` (không mã hoá lại, mất vài giây).
 */
class Pipeline(private val ctx: Context, private val p: Params) {

    @Volatile private var cancelled = false
    private val ffProcs = Collections.synchronizedSet(mutableSetOf<Process>())
    private val ytIds = Collections.synchronizedSet(mutableSetOf<String>())
    private val pools = Collections.synchronizedList(mutableListOf<ExecutorService>())
    private val prefs = ctx.getSharedPreferences("pipeline", Context.MODE_PRIVATE)
    // Thư viện mặc định --no-cache-dir -> mỗi video phải giải lại challenge bằng QuickJS. Bật cache.
    private val ytCache = File(ctx.filesDir, "yt-dlp-cache")
    private val work = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "work")
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    private data class Entry(val n: Int, val index: Int, val id: String, val title: String)
    private data class Raw(val entry: Entry, val file: File, val duration: Double)
    private data class Seg(val raw: Raw, val part: Int, val start: Double, val len: Double, val out: File)

    fun cancel() {
        cancelled = true
        synchronized(pools) { pools.forEach { it.shutdownNow() } }
        synchronized(ytIds) { ytIds.toList() }.forEach { runCatching { YoutubeDL.getInstance().destroyProcessById(it) } }
        synchronized(ffProcs) { ffProcs.forEach { it.destroy() } }
    }

    private fun checkCancel() { if (cancelled) throw CancelledByUser() }
    private fun log(s: String) = JobState.log(s)
    private fun status(s: String) { JobState.status.value = s; log("» $s") }
    private fun progress(f: Double) { JobState.progress.value = (f.coerceIn(0.0, 1.0) * 1000).toInt() }
    private fun secs(t0: Long) = (System.currentTimeMillis() - t0) / 1000.0

    private fun newPool(n: Int) = Executors.newFixedThreadPool(n).also { pools += it }

    fun run(): List<String> {
        val tAll = System.currentTimeMillis()
        status("Khởi tạo (lần đầu hơi lâu)…")
        YoutubeDL.getInstance().init(ctx)
        FFmpeg.getInstance().init(ctx)
        log("CPU: $cores nhân")

        // Kiểm tra cập nhật yt-dlp tối đa 1 lần / 12 giờ
        if (System.currentTimeMillis() - prefs.getLong("ytdlpUpdate", 0) > 12 * 3600_000L) {
            status("Cập nhật yt-dlp…")
            try {
                val r = YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.STABLE)
                prefs.edit().putLong("ytdlpUpdate", System.currentTimeMillis()).apply()
                log("yt-dlp ${YoutubeDL.getInstance().versionName(ctx)} ($r)")
            } catch (e: Exception) {
                log("Không cập nhật được yt-dlp: ${e.message?.take(200)}")
            }
        } else log("yt-dlp ${YoutubeDL.getInstance().versionName(ctx)}")
        checkCancel()

        // ---------- Danh sách ----------
        status("Đọc danh sách video…")
        val t0 = System.currentTimeMillis()
        val listReq = YoutubeDLRequest(p.url).apply {
            addOption("--flat-playlist")
            addOption("--yes-playlist")
            addOption("--ignore-errors")
            addOption("--cache-dir", ytCache.absolutePath)
            addOption("--print", "%(playlist_title|)s\t%(id)s\t%(title)s")
            addOption("-I", if (p.count > 0) "${p.from}:${p.from + p.count - 1}" else "${p.from}:")
        }
        val listOut = ytExec(listReq, "list") { _, _ -> }.out
        var playlistTitle = ""
        val entries = listOut.lines().filter { it.contains('\t') }.mapIndexed { i, line ->
            val f = line.split('\t')
            if (playlistTitle.isEmpty()) playlistTitle = f[0]
            Entry(i, p.from + i, f[1], f.getOrElse(2) { f[1] })
        }
        if (entries.isEmpty()) throw Exception("Không tìm thấy video nào. Kiểm tra lại link.")
        log(String.format(Locale.US, "Playlist: %s — %d video (%.1fs)",
            playlistTitle.ifBlank { "(video lẻ)" }, entries.size, secs(t0)))
        checkCancel()

        // Thư mục theo link + định dạng tải: chạy lại sẽ dùng lại file đã tải
        val key = Integer.toHexString("${p.url}|${p.mono}".hashCode())
        val dir = File(work, key).apply { mkdirs() }
        work.listFiles()?.filter { it.name != key }?.forEach { it.deleteRecursively() }

        val raws = downloadAll(entries, dir)
        checkCancel()
        if (raws.isEmpty()) throw Exception("Không tải được video nào")

        val segs = encodeAll(raws, dir)
        checkCancel()

        // ---------- Gộp (copy, không mã hoá lại) + lưu ----------
        val byVideo = segs.groupBy { it.raw.entry.n }.toSortedMap()
        val videos = byVideo.keys.toList()
        val groups = videos.chunked(p.group)
        val width = max(2, groups.size.toString().length)
        val base = sanitize(playlistTitle.ifBlank { entries.first().title }).take(60)
        val saved = mutableListOf<String>()
        for ((g, vids) in groups.withIndex()) {
            checkCancel()
            val first = entries[vids.first()].index
            val last = entries[vids.last()].index
            val name = "${base}_${String.format(Locale.US, "%0${width}d", g + 1)}_v$first-$last.mp3"
            status("Gộp + lưu ${g + 1}/${groups.size}")
            val parts = vids.flatMap { v -> byVideo[v]!!.sortedBy { it.part } }
            val listFile = File(dir, "concat.txt")
            listFile.writeText(parts.joinToString("") { "file '${it.out.absolutePath.replace("'", "'\\''")}'\n" })
            val merged = File(dir, "merged.mp3")
            ffmpeg(listOf("-f", "concat", "-safe", "0", "-i", listFile.absolutePath, "-c", "copy", merged.absolutePath))
            saved += export(merged, name)
            merged.delete()
            progress(0.95 + 0.05 * (g + 1) / groups.size)
        }
        dir.deleteRecursively()
        progress(1.0)
        log(String.format(Locale.US, "TỔNG THỜI GIAN: %.1fs", secs(tAll)))
        return saved
    }

    // ================= Giai đoạn 1: tải song song =================

    private fun downloadAll(entries: List<Entry>, dir: File): List<Raw> {
        val total = entries.size
        val conc = minOf(4, total)
        val t0 = System.currentTimeMillis()
        val frac = ConcurrentHashMap<Int, Double>()
        val speed = ConcurrentHashMap<Int, Double>() // bytes/s
        val done = AtomicInteger()
        val results = arrayOfNulls<Raw>(total)
        var lastUi = 0L

        fun ui(force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastUi < 400) return
            lastUi = now
            val f = (done.get() + frac.values.sum()) / total
            progress(f * 0.45)
            JobState.status.value = "Tải ${done.get()}/$total · ${human(speed.values.sum())}/s · ${frac.size} luồng"
        }

        status("Tải $total video ($conc luồng song song)…")
        val pool = newPool(conc)
        val futures = entries.map { e ->
            pool.submit {
                if (cancelled) return@submit
                val tag = "[${e.n + 1}/$total]"
                val t1 = System.currentTimeMillis()
                try {
                    val cached = File(dir, "raw_${e.index}.done")
                    val raw = if (cached.exists()) {
                        val (d, path) = cached.readText().split('\t', limit = 2)
                        Raw(e, File(path), d.toDouble()).takeIf { it.file.exists() }
                    } else null
                    results[e.n] = raw ?: download(e, dir) { pct, line ->
                        frac[e.n] = pct
                        parseSpeed(line)?.let { speed[e.n] = it }
                        ui()
                    }.also { cached.writeText("${it.duration}\t${it.file.absolutePath}") }
                    val sizeMb = results[e.n]!!.file.length() / 1048576.0
                    log(String.format(Locale.US, "%s Tải xong %.1fMB trong %.1fs: %s",
                        tag, sizeMb, secs(t1), e.title))
                } catch (ex: Exception) {
                    if (!cancelled) log("$tag LỖI tải, bỏ qua: ${ex.message?.lines()?.lastOrNull { it.isNotBlank() }?.take(300)}")
                } finally {
                    frac.remove(e.n); speed.remove(e.n)
                    done.incrementAndGet(); ui(true)
                }
            }
        }
        futures.forEach { runCatching { it.get() } }
        pool.shutdown()
        checkCancel()
        val ok = results.filterNotNull()
        val mb = ok.sumOf { it.file.length() } / 1048576.0
        log(String.format(Locale.US, "Tải xong %d/%d video, %.0fMB trong %.1fs (TB %s/s)",
            ok.size, total, mb, secs(t0), human(mb * 1048576 / max(secs(t0), 0.1))))
        return ok
    }

    private fun download(e: Entry, dir: File, onProgress: (Double, String) -> Unit): Raw {
        val req = YoutubeDLRequest("https://www.youtube.com/watch?v=${e.id}").apply {
            // Mono 64k: chọn luồng audio bitrate thấp (~50-70k) -> tải và giải mã nhanh hơn
            addOption("-f", if (p.mono) "bestaudio[abr<=80]/bestaudio/best" else "bestaudio[ext=m4a]/bestaudio/best")
            addOption("--no-playlist")
            addOption("--cache-dir", ytCache.absolutePath)
            addOption("--no-mtime")
            addOption("--retries", "10")
            addOption("--fragment-retries", "10")
            addOption("--concurrent-fragments", "4")
            addOption("-o", File(dir, "raw_${e.index}.%(ext)s").absolutePath)
            addOption("--no-simulate")
            addOption("--progress")  // --print bật chế độ quiet; vẫn cần dòng tiến độ
            addOption("--newline")
            addOption("--print", "after_move:RESULT\t%(duration)s\t%(filepath)s")
        }
        val resp = ytExec(req, "dl-${e.n}") { pct, line -> onProgress(pct / 100.0, line) }
        val line = resp.out.lines().lastOrNull { it.startsWith("RESULT\t") }
            ?: throw Exception("Không đọc được kết quả tải")
        val f = line.split('\t')
        val file = File(f[2])
        if (!file.exists()) throw Exception("Không thấy file đã tải: ${file.name}")
        return Raw(e, file, f[1].toDoubleOrNull() ?: probeDuration(file))
    }

    private fun ytExec(req: YoutubeDLRequest, id: String, cb: (Double, String) -> Unit) =
        try {
            ytIds += id
            YoutubeDL.getInstance().execute(req, id) { pct, _, line -> cb(pct.toDouble(), line) }
        } catch (e: Exception) {
            if (cancelled) throw CancelledByUser()
            throw e
        } finally {
            ytIds -= id
        }

    // ================= Giai đoạn 2: mã hoá song song mọi nhân =================

    private fun encodeAll(raws: List<Raw>, dir: File): List<Seg> {
        // Chia đều khối lượng: mỗi nhân ~3 đoạn để nhân nào xong sớm lấy tiếp, không nhân nào ngồi chơi.
        val keeps = raws.associateWith { it.duration - p.trimStart - p.trimEnd }
        raws.filter { keeps[it]!! <= 1 }.forEach {
            log("[${it.entry.n + 1}] Bỏ qua: video dài ${it.duration.toInt()}s, cắt xong không còn gì")
        }
        val valid = raws.filter { keeps[it]!! > 1 }
        if (valid.isEmpty()) throw Exception("Không còn video nào sau khi cắt")
        val totalKeep = valid.sumOf { keeps[it]!! }
        val target = max(180.0, totalKeep / (cores * 3))
        val segs = valid.flatMap { r ->
            val keep = keeps[r]!!
            val k = max(1, ceil(keep / target).toInt())
            val len = keep / k
            (0 until k).map { j ->
                Seg(r, j, p.trimStart + j * len, len,
                    File(dir, String.format(Locale.US, "seg_%04d_%03d.mp3", r.entry.index, j)))
            }
        }
        val totalOut = segs.sumOf { it.len } / p.speed
        val doneSec = ConcurrentHashMap<Seg, Double>()
        val t0 = System.currentTimeMillis()
        var lastUi = 0L
        fun ui() {
            val now = System.currentTimeMillis()
            if (now - lastUi < 400) return
            lastUi = now
            val d = doneSec.values.sum()
            progress(0.45 + 0.5 * d / totalOut)
            JobState.status.value = String.format(Locale.US, "Cắt + tăng tốc: %.0f%% · %.0fx realtime · %d nhân",
                100 * d / totalOut, d / max(secs(t0), 0.1), cores)
        }

        status("Cắt + tăng tốc ${p.speed}x: ${valid.size} video → ${segs.size} đoạn trên $cores nhân…")
        val pool = newPool(cores)
        val futures = segs.sortedByDescending { it.len }.map { s ->
            pool.submit {
                if (cancelled) return@submit
                val tmp = File(s.out.parentFile, s.out.name + ".tmp.mp3")
                ffmpeg(
                    listOf(
                        "-threads", "1",
                        "-ss", fmt(s.start), "-t", fmt(s.len), "-i", s.raw.file.absolutePath,
                        "-vn", "-af", atempo(p.speed),
                        "-ac", if (p.mono) "1" else "2",
                        "-c:a", "libmp3lame", "-b:a", "${p.bitrateK}k",
                        "-compression_level", "7", // chế độ nhanh của LAME
                        "-progress", "pipe:1", "-nostats",
                        tmp.absolutePath,
                    )
                ) { outSec -> doneSec[s] = outSec; ui() }
                tmp.renameTo(s.out)
                doneSec[s] = s.len / p.speed
                ui()
            }
        }
        var err: Throwable? = null
        futures.forEach { f -> runCatching { f.get() }.onFailure { if (err == null) err = it.cause ?: it } }
        pool.shutdown()
        checkCancel()
        err?.let { throw Exception("Lỗi mã hoá: ${it.message}") }
        log(String.format(Locale.US, "Cắt + tăng tốc xong %.0f phút audio trong %.1fs (%.0fx realtime)",
            totalOut / 60, secs(t0), totalOut / max(secs(t0), 0.1)))
        valid.forEach { it.file.delete() }
        return segs
    }

    // ================= ffmpeg =================

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

    private fun ffmpeg(args: List<String>, onOutSec: (Double) -> Unit = {}) {
        val cmd = listOf(File(libDir(), "libffmpeg.so").absolutePath, "-hide_banner", "-nostdin", "-y",
            "-loglevel", "error") + args
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        env(pb)
        val proc = pb.start()
        ffProcs += proc
        val tail = ArrayDeque<String>()
        try {
            proc.inputStream.bufferedReader().forEachLine { line ->
                if (line.startsWith("out_time_us=")) {
                    line.substringAfter('=').toLongOrNull()?.let { onOutSec(it / 1e6) }
                } else if (!line.contains('=')) {
                    tail.addLast(line); if (tail.size > 6) tail.removeFirst()
                }
            }
        } finally {
            proc.waitFor(); ffProcs -= proc
        }
        checkCancel()
        if (proc.exitValue() != 0) throw Exception("ffmpeg lỗi (${proc.exitValue()}): ${tail.joinToString(" | ").take(400)}")
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

        private val speedRe = Regex("at\\s+~?\\s*([\\d.]+)\\s*([KMG]?i?B)/s")

        /** "[download]  23.5% of 48.29MiB at 2.31MiB/s ETA 00:16" -> bytes/s */
        fun parseSpeed(line: String): Double? {
            val m = speedRe.find(line) ?: return null
            val v = m.groupValues[1].toDoubleOrNull() ?: return null
            val mul = when (m.groupValues[2].firstOrNull()) {
                'K' -> 1024.0; 'M' -> 1048576.0; 'G' -> 1073741824.0; else -> 1.0
            }
            return v * mul
        }

        fun human(bps: Double): String = when {
            bps >= 1048576 -> String.format(Locale.US, "%.1f MB", bps / 1048576)
            bps >= 1024 -> String.format(Locale.US, "%.0f KB", bps / 1024)
            else -> String.format(Locale.US, "%.0f B", bps)
        }
    }
}
