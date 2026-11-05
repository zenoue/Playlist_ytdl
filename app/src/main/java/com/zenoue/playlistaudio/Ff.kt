package com.zenoue.playlistaudio

import android.content.Context
import java.io.File

/** Chạy ffmpeg đóng gói trong youtubedl-android (cần FFmpeg.init trước). */
object Ff {
    fun run(ctx: Context, args: List<String>) {
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libffmpeg.so").absolutePath
        val pb = ProcessBuilder(listOf(bin, "-hide_banner", "-nostdin", "-y", "-loglevel", "error") + args)
            .redirectErrorStream(true)
        val pk = File(ctx.noBackupFilesDir, "youtubedl-android/packages")
        pb.environment()["LD_LIBRARY_PATH"] = "${pk.absolutePath}/ffmpeg/usr/lib:${pk.absolutePath}/python/usr/lib"
        pb.environment()["TMPDIR"] = ctx.cacheDir.absolutePath
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        if (p.waitFor() != 0) throw Exception("ffmpeg lỗi: ${out.takeLast(300)}")
    }
}
