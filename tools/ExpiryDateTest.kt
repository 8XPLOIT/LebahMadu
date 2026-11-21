import com.lebahmadu.app.ExpiryDate
import java.util.Calendar
import java.util.TimeZone

var failed = 0

fun check(name: String, actual: Long, expected: Long) {
    if (actual == expected) println("PASS  $name  -> $actual")
    else { println("FAIL  $name  -> got $actual, want $expected"); failed++ }
}

fun checkThrows(name: String, text: String) {
    try {
        val v = ExpiryDate.parse(text)
        println("FAIL  $name  -> accepted \"$text\" as $v"); failed++
    } catch (e: IllegalArgumentException) {
        println("PASS  $name  -> rejected \"$text\" (${e.message})")
    }
}

/** epoch ms 00:00 WIB pada tanggal yang diberikan. */
fun wib(y: Int, mo: Int, d: Int): Long {
    val c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Jakarta"))
    c.clear()
    c.set(y, mo - 1, d, 0, 0, 0)
    c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

fun main() {
    check("31/12/2099", ExpiryDate.parse("31/12/2099"), wib(2099, 12, 31))
    check("  31/12/2099 dengan spasi  ", ExpiryDate.parse("  31/12/2099 "), wib(2099, 12, 31))
    check("01/12/2099 (angka depan nol)", ExpiryDate.parse("01/12/2099"), wib(2099, 12, 1))
    check("1/12/2099 (satu digit)", ExpiryDate.parse("1/12/2099"), wib(2099, 12, 1))
    check("21/11/2026", ExpiryDate.parse("21/11/2026"), wib(2026, 11, 21))
    check("29/02/2096 (kabisat)", ExpiryDate.parse("29/02/2096"), wib(2096, 2, 29))
    check("format lama 2099-12-31", ExpiryDate.parse("2099-12-31"), wib(2099, 12, 31))
    check("format lama 2026-11-21", ExpiryDate.parse("2026-11-21"), wib(2026, 11, 21))

    checkThrows("31/02/2099 (tidak ada di kalender)", "31/02/2099")
    checkThrows("29/02/2100 (bukan kabisat)", "29/02/2100")
    checkThrows("32/01/2099", "32/01/2099")
    checkThrows("31/13/2099 (bulan 13)", "31/13/2099")
    checkThrows("12/2099 (kurang bagian)", "12/2099")
    checkThrows("31-12-2099 (strip, bukan titik dua)", "31-12-2099")
    checkThrows("2099/12/31 (garis miring urutan lain)", "2099/12/31")
    checkThrows("kosong", "")
    checkThrows("huruf", "besok")
    checkThrows("31/12/99 (tahun 2 digit)", "31/12/99")

    println(if (failed == 0) "\nSEMUA UJI LULUS" else "\nADA $failed UJI GAGAL")
    if (failed > 0) kotlin.system.exitProcess(1)
}
