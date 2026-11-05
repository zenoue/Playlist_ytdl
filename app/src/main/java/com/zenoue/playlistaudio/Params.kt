package com.zenoue.playlistaudio

import android.os.Bundle

data class Params(
    val url: String,
    val from: Int,
    val count: Int,        // 0 = tới hết playlist
    val trimStart: Double, // giây
    val trimEnd: Double,   // giây
    val speed: Double,
    val group: Int,
    val bitrateK: Int = 128,
) {
    fun toBundle() = Bundle().apply {
        putString("url", url); putInt("from", from); putInt("count", count)
        putDouble("trimStart", trimStart); putDouble("trimEnd", trimEnd)
        putDouble("speed", speed); putInt("group", group); putInt("bitrateK", bitrateK)
    }

    companion object {
        fun from(b: Bundle) = Params(
            b.getString("url")!!, b.getInt("from"), b.getInt("count"),
            b.getDouble("trimStart"), b.getDouble("trimEnd"),
            b.getDouble("speed"), b.getInt("group"), b.getInt("bitrateK", 128),
        )

        /** "75", "1:15", "0:01:15" -> giây */
        fun parseTime(s: String): Double {
            val t = s.trim()
            if (t.isEmpty()) return 0.0
            return t.split(":").fold(0.0) { acc, part -> acc * 60 + part.trim().toDouble() }
        }
    }
}
