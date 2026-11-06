package com.zenoue.playlistaudio

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.webkit.CookieManager
import java.io.File

/**
 * Lấy cookies YouTube từ WebView (sau khi người dùng đăng nhập trong app) và ghi ra cookies.txt
 * định dạng Netscape cho yt-dlp (--cookies). Đọc thẳng CSDL "Cookies" của WebView để có đủ
 * domain / path / secure / hạn dùng (CookieManager.getCookie chỉ trả name=value).
 */
object CookieStore {
    fun file(ctx: Context) = File(ctx.filesDir, "cookies.txt")

    fun hasCookies(ctx: Context) = file(ctx).let { it.exists() && it.length() > 0 }

    fun clear(ctx: Context) {
        file(ctx).delete()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    /** Nhập cookies.txt có sẵn (xuất từ trình duyệt / app khác). Trả về số dòng cookie YouTube. */
    fun import(ctx: Context, text: String): Int {
        val lines = text.lines().filter { l ->
            val f = l.split('\t')
            f.size >= 7 && !l.startsWith("#") || l.startsWith("#HttpOnly_")
        }
        val yt = lines.count { it.contains("youtube.com") }
        if (yt == 0) throw Exception("Không thấy cookie youtube.com trong nội dung (cần định dạng Netscape cookies.txt)")
        file(ctx).writeText("# Netscape HTTP Cookie File\n" + lines.joinToString("\n") + "\n")
        return yt
    }

    /** Ghi cookies.txt, trả về số cookie đã ghi. */
    fun export(ctx: Context): Int {
        CookieManager.getInstance().flush()
        val db = File(ctx.applicationInfo.dataDir).walkTopDown().firstOrNull { it.name == "Cookies" && it.isFile }
            ?: throw Exception("Không tìm thấy CSDL cookies của WebView")
        val lines = mutableListOf<String>()
        SQLiteDatabase.openDatabase(db.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { d ->
            d.rawQuery(
                "SELECT host_key, name, value, path, expires_utc, is_secure FROM cookies " +
                    "WHERE host_key LIKE '%youtube.com' OR host_key LIKE '%google.com'", null
            ).use { c ->
                while (c.moveToNext()) {
                    val host = c.getString(0).let { if (it.startsWith(".")) it else ".$it" }
                    val name = c.getString(1)
                    val value = c.getString(2) ?: ""
                    if (value.isEmpty()) continue
                    val path = c.getString(3) ?: "/"
                    // Chromium lưu hạn dùng theo micro giây kể từ 1601-01-01 -> đổi sang giây Unix
                    val exp = c.getLong(4).let { if (it == 0L) 0L else it / 1_000_000 - 11_644_473_600L }
                    val secure = if (c.getInt(5) == 1) "TRUE" else "FALSE"
                    lines += listOf(host, "TRUE", path, secure, exp.coerceAtLeast(0).toString(), name, value).joinToString("\t")
                }
            }
        }
        if (lines.none { it.contains("youtube.com") }) throw Exception("Chưa có cookies YouTube — hãy đăng nhập trước")
        file(ctx).writeText("# Netscape HTTP Cookie File\n# Tạo bởi Playlist Audio\n" + lines.joinToString("\n") + "\n")
        return lines.size
    }
}
