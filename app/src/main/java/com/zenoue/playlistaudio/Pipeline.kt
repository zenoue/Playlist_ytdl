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
        // aria2c: tải mỗi file bằng 16 kết nối song song (như tuỳ chọn aria2c của YTDLnis)
        val ariaOk = runCatching { com.yausername.aria2c.Aria2c.getInstance().init(ctx) }
            .onFailure { log("Không khởi tạo được aria2c, dùng trình tải thường: ${it.message?.take(120)}") }
            .isSuccess
        log(String.format(Locale.US, "Khởi tạo: %.1fs", secs(tAll)))
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
            addOption("--print", "%(playlist_title|)s\t%(id)s\t%(title)s\t%(playlist_uploader|)s")
            addOption("-I", if (p.count > 0) "${p.from}:${p.from + p.count - 1}" else "${p.from}:")
        }
        val listOut = ytExec(listReq, "list") { _, _ -> }.out
        var playlistTitle = ""
        var uploader = ""
        val entries = listOut.lines().filter { it.contains('\t') }.mapIndexed { i, line ->
            val f = line.split('\t')
            if (playlistTitle.isEmpty()) playlistTitle = f[0]
            if (uploader.isEmpty()) uploader = f.getOrElse(3) { "" }
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

        val raws = downloadAll(entries, dir, ariaOk)
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
            val cover = makeCover(dir, entries[vids.first()].index)
            val album = playlistTitle.ifBlank { entries[vids.first()].title }
            val args = mutableListOf("-f", "concat", "-safe", "0", "-i", listFile.absolutePath)
            if (cover != null) args += listOf("-i", cover.absolutePath, "-map", "0:a", "-map", "1:v")
            args += listOf("-c", "copy", "-id3v2_version", "3",
                "-metadata", "title=${String.format(Locale.US, "%0${width}d", g + 1)}. ${entries[vids.first()].title}" +
                    (if (vids.size > 1) " → ${entries[vids.last()].title}" else ""),
                "-metadata", "album=$album",
                "-metadata", "artist=${uploader.ifBlank { album }}",
                "-metadata", "track=${g + 1}/${groups.size}",
                "-metadata", "genre=Audiobook")
            if (cover != null) args += listOf("-disposition:v", "attached_pic",
                "-metadata:s:v", "title=Album cover", "-metadata:s:v", "comment=Cover (front)")
            args += merged.absolutePath
            try {
                ffmpeg(args)
            } catch (e: Exception) {
                if (cancelled || cover == null) throw e
                log("Không gắn được ảnh bìa, gộp không ảnh: ${e.message?.take(200)}")
                ffmpeg(listOf("-f", "concat", "-safe", "0", "-i", listFile.absolutePath, "-c", "copy", merged.absolutePath))
            }
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

    private fun downloadAll(entries: List<Entry>, dir: File, ariaOk: Boolean): List<Raw> {
        val total = entries.size
        val conc = minOf(MAX_PARALLEL_DOWNLOADS, total)
        val t0 = System.currentTimeMillis()
        val frac = ConcurrentHashMap<Int, Double>()
        val speed = ConcurrentHashMap<Int, Double>()       // bytes/s hiện tại của từng luồng
        val flowingSince = ConcurrentHashMap<Int, Long>()  // lúc luồng (lần thử hiện tại) bắt đầu có dữ liệu
        val slowSince = ConcurrentHashMap<Int, Long>()
        val restartReq = ConcurrentHashMap<Int, Double>()  // n -> tốc độ lúc bị đánh giá chậm
        val finishedRates = Collections.synchronizedList(mutableListOf<Double>())
        val done = AtomicInteger()
        val restarts = AtomicInteger()
        val results = arrayOfNulls<Raw>(total)
        var lastUi = 0L

        fun ui(force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastUi < 400) return
            lastUi = now
            val f = (done.get() + frac.values.sum()) / total
            progress(f * 0.45)
            JobState.status.value = "Tải ${done.get()}/$total · ${human(speed.values.sum())}/s · ${frac.size} luồng" +
                (if (restarts.get() > 0) " · tải lại ${restarts.get()}" else "")
        }

        // Canh gác: luồng nào bị YouTube bóp tốc độ (chậm hẳn so với các luồng khác) -> huỷ và tải lại.
        // yt-dlp tự nối tiếp file .part nên không mất phần đã tải.
        val watchdog = Executors.newSingleThreadScheduledExecutor().also { pools += it }
        watchdog.scheduleWithFixedDelay({
            try {
                val now = System.currentTimeMillis()
                val active = speed.filterKeys { frac.containsKey(it) }
                val ref = max(median(active.values.toList()), median(synchronized(finishedRates) { finishedRates.toList() }))
                if (ref <= 0) return@scheduleWithFixedDelay
                for ((n, sp) in active) {
                    val since = flowingSince[n] ?: continue
                    val fr = frac[n] ?: continue
                    val slow = now - since > THROTTLE_WARMUP_MS && fr < 0.8 && sp < ref * THROTTLE_RATIO
                    if (!slow) { slowSince.remove(n); continue }
                    val s0 = slowSince.getOrPut(n) { now }
                    if (now - s0 >= THROTTLE_HOLD_MS && !restartReq.containsKey(n)) {
                        restartReq[n] = sp
                        runCatching { YoutubeDL.getInstance().destroyProcessById("dl-$n") }
                    }
                }
            } catch (_: Exception) { }
        }, 1, 1, java.util.concurrent.TimeUnit.SECONDS)

        status("Tải $total video ($conc luồng song song)…")
        val pool = newPool(conc)
        val futures = entries.map { e ->
            pool.submit {
                if (cancelled) return@submit
                val n = e.n
                val tag = "[${n + 1}/$total]"
                val t1 = System.currentTimeMillis()
                try {
                    val cached = File(dir, "raw_${e.index}.done")
                    var raw = if (cached.exists()) {
                        val (d, path) = cached.readText().split('\t', limit = 2)
                        Raw(e, File(path), d.toDouble()).takeIf { it.file.exists() }
                    } else null
                    var attempt = 0
                    var useAria = ariaOk
                    while (raw == null) {
                        if (cancelled) throw CancelledByUser()
                        flowingSince.remove(n); slowSince.remove(n); speed.remove(n)
                        try {
                            raw = download(e, dir, useAria) { pct, line ->
                                frac[n] = pct
                                parseSpeed(line)?.let {
                                    speed[n] = it
                                    if (flowingSince.putIfAbsent(n, System.currentTimeMillis()) == null && attempt == 0)
                                        log(String.format(Locale.US, "%s Bắt đầu nhận dữ liệu sau %.1fs", tag, secs(t1)))
                                }
                                ui()
                            }
                        } catch (ex: Exception) {
                            val sp = restartReq.remove(n)
                            if (!cancelled && sp == null && useAria) {
                                useAria = false
                                log("$tag aria2c lỗi (${ex.message?.lines()?.lastOrNull { it.isNotBlank() }?.take(120)}) → dùng trình tải thường")
                                continue
                            }
                            if (cancelled || sp == null) throw ex
                            attempt++
                            restarts.incrementAndGet()
                            log("$tag Luồng bị bóp (${human(sp)}/s) → tải lại lần $attempt")
                            if (attempt >= MAX_RESTARTS) {
                                log("$tag Đã tải lại $attempt lần, để luồng này chạy tiếp")
                                // lần cuối: không cho canh gác huỷ nữa
                                flowingSince.remove(n); speed.remove(n)
                                raw = download(e, dir, useAria) { pct, line -> frac[n] = pct; parseSpeed(line)?.let { speed[n] = it }; ui() }
                            }
                        }
                    }
                    cached.writeText("${raw.duration}\t${raw.file.absolutePath}")
                    results[n] = raw
                    val sizeMb = raw.file.length() / 1048576.0
                    finishedRates += raw.file.length() / max(secs(t1), 0.1)
                    log(String.format(Locale.US, "%s Tải xong %.1fMB trong %.1fs: %s",
                        tag, sizeMb, secs(t1), e.title))
                } catch (ex: Exception) {
                    if (!cancelled) log("$tag LỖI tải, bỏ qua: ${ex.message?.lines()?.lastOrNull { it.isNotBlank() }?.take(300)}")
                } finally {
                    frac.remove(n); speed.remove(n); flowingSince.remove(n); slowSince.remove(n)
                    done.incrementAndGet(); ui(true)
                }
            }
        }
        futures.forEach { runCatching { it.get() } }
        pool.shutdown()
        watchdog.shutdownNow()
        checkCancel()
        val ok = results.filterNotNull()
        val mb = ok.sumOf { it.file.length() } / 1048576.0
        log(String.format(Locale.US, "Tải xong %d/%d video, %.0fMB trong %.1fs (TB %s/s, tải lại %d lần)",
            ok.size, total, mb, secs(t0), human(mb * 1048576 / max(secs(t0), 0.1)), restarts.get()))
        return ok
    }

    private fun download(e: Entry, dir: File, aria: Boolean, onProgress: (Double, String) -> Unit): Raw {
        val req = YoutubeDLRequest("https://www.youtube.com/watch?v=${e.id}").apply {
            // Mono 64k: chọn luồng audio bitrate thấp (~50-70k) -> tải và giải mã nhanh hơn
            addOption("-f", if (p.mono) "bestaudio[abr<=80]/bestaudio/best" else "bestaudio[ext=m4a]/bestaudio/best")
            addOption("--no-playlist")
            addOption("--cache-dir", ytCache.absolutePath)
            addOption("--no-mtime")
            addOption("--retries", "10")
            addOption("--fragment-retries", "10")
            addOption("--concurrent-fragments", "4")
            // yt-dlp gọi aria2c với -x16 -s16 --min-split-size 1M: 16 kết nối/ file,
            // vượt giới hạn tốc độ theo từng kết nối của YouTube
            if (aria) addOption("--downloader", "libaria2c.so")
            addOption("-o", File(dir, "raw_${e.index}.%(ext)s").absolutePath)
            addOption("--no-simulate")
            addOption("--progress")  // --print bật chế độ quiet; vẫn cần dòng tiến độ
            addOption("--newline")
            addOption("--print", "after_move:RESULT\t%(duration)s\t%(filepath)s")
            // Ảnh thumbnail làm ảnh bìa (lỗi thumbnail chỉ là cảnh báo, không làm hỏng việc tải)
            addOption("--write-thumbnail")
            addOption("-o", "thumbnail:" + File(dir, "thumb_${e.index}.%(ext)s").absolutePath)
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

    /** Thumbnail (webp/jpg) -> jpg 640px làm ảnh bìa. Không có thì trả null. */
    private fun makeCover(dir: File, index: Int): File? {
        val src = dir.listFiles()?.firstOrNull {
            it.name.startsWith("thumb_$index.") && !it.name.endsWith(".part")
        } ?: return null
        val out = File(dir, "cover_$index.jpg")
        if (out.exists()) return out
        return try {
            ffmpeg(listOf("-i", src.absolutePath, "-frames:v", "1", "-vf", "scale=640:-2", "-q:v", "3", out.absolutePath))
            out
        } catch (e: Exception) {
            if (cancelled) throw e
            log("Không tạo được ảnh bìa: ${e.message?.take(150)}")
            null
        }
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
        const val MAX_PARALLEL_DOWNLOADS = 10
        const val MAX_RESTARTS = 3
        const val THROTTLE_WARMUP_MS = 8_000L   // bỏ qua mấy giây đầu (tốc độ chưa ổn định)
        const val THROTTLE_HOLD_MS = 6_000L     // chậm liên tục bấy lâu mới tải lại
        const val THROTTLE_RATIO = 0.4          // chậm hơn 40% tốc độ chuẩn = bị bóp

        fun median(xs: List<Double>): Double {
            if (xs.isEmpty()) return 0.0
            val s = xs.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        }

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
        private val ariaSpeedRe = Regex("DL:([\\d.]+)([KMG]?i?B)")

        /** "[download]  23.5% of 48.29MiB at 2.31MiB/s ETA 00:16" -> bytes/s */
        fun parseSpeed(line: String): Double? {
            val m = speedRe.find(line) ?: ariaSpeedRe.find(line) ?: return null
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
