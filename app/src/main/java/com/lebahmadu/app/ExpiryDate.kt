package com.lebahmadu.app

import java.util.Calendar
import java.util.TimeZone

/**
 * Parsing tanggal masa aktif kode login.
 *
 * Format utama : DD/MM/YYYY  (contoh: 31/12/2099)
 * Format lama  : YYYY-MM-DD  (contoh: 2099-12-31) masih diterima.
 *
 * Tanggal dibaca sebagai jam 00:00 WIB (Asia/Jakarta).
 * Melempar IllegalArgumentException kalau teksnya bukan tanggal nyata.
 */
object ExpiryDate {

    private val ZONE: TimeZone = TimeZone.getTimeZone("Asia/Jakarta")

    private val SLASH = Regex("""^(\d{1,2})/(\d{1,2})/(\d{4})$""")
    private val DASH = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})$""")

    /** epoch ms jam 00:00 WIB pada tanggal yang ditulis. */
    fun parse(text: String): Long {
        val t = text.trim()
        val slash = SLASH.matchEntire(t)
        val dash = if (slash == null) DASH.matchEntire(t) else null

        val day: Int
        val month: Int
        val year: Int
        when {
            slash != null -> {
                day = slash.groupValues[1].toInt()
                month = slash.groupValues[2].toInt()
                year = slash.groupValues[3].toInt()
            }
            dash != null -> {
                year = dash.groupValues[1].toInt()
                month = dash.groupValues[2].toInt()
                day = dash.groupValues[3].toInt()
            }
            else -> throw IllegalArgumentException(
                "format tanggal salah: \"$text\". Pakai DD/MM/YYYY, contoh 31/12/2099."
            )
        }

        if (month !in 1..12) throw IllegalArgumentException("bulan tidak valid: \"$text\"")
        if (day < 1 || day > daysInMonth(year, month)) {
            throw IllegalArgumentException("tanggal tidak ada di kalender: \"$text\"")
        }

        val cal = Calendar.getInstance(ZONE)
        cal.clear()
        cal.set(year, month - 1, day, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun daysInMonth(year: Int, month: Int): Int = when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        2 -> if (isLeap(year)) 29 else 28
        else -> 0
    }

    private fun isLeap(year: Int): Boolean = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
}
