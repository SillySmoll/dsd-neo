// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.arancormonk.dsdneo

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * SIG-SENSE: the decoder's LRRP position file (`-L`), shared by the decoder service that
 * turns it on and the map that reads it.
 *
 * Every position path in the engine (DMR LRRP, USBD/LIP, LOCN/NMEA, embedded GPS,
 * Harris, P25 LRRP) appends one tab-separated row, already gated on its CRC:
 *
 *     date  time  source  latitude  longitude  speed_kph  heading
 *
 * where the date is `YYYYMMDD` or `YYYY/MM/DD` and the time `HHMMSS` or `HH:MM:SS`,
 * both in the phone's local time.
 */
object PositionLog {
    const val KIND_LRRP = "LRRP"
    const val KIND_USBD = "USBD"
    const val KIND_GPS = "GPS"

    /** The engine's eighth column (SIG-SENSE builds) to a map category; rows without one count as GPS. */
    @JvmStatic
    fun kindOf(tag: String?): String = when (tag?.uppercase()) {
        "LRRP" -> KIND_LRRP
        "LIP", "USBD" -> KIND_USBD
        else -> KIND_GPS
    }

    /** Rows older than the tail this size are not read; the file only grows. */
    private const val MAX_TAIL_BYTES = 1L shl 20

    data class Fix(
        val source: Long,
        val latitude: Double,
        val longitude: Double,
        val speedKph: Double,
        val heading: Int,
        val epochMillis: Long,
        /** [KIND_LRRP], [KIND_USBD] (LIP, as USBD carries it) or [KIND_GPS] (NMEA, embedded GPS, others). */
        val kind: String = KIND_GPS,
    )

    data class Radio(
        val source: Long,
        val latest: Fix,
        /** Oldest first, ending with [latest]. */
        val track: List<Fix>,
        val reports: Int,
    )

    @JvmStatic
    fun file(context: Context): File = File(File(context.filesDir, "sigsense"), "positions.lrrp")

    /** Creates the directory so the engine's private fopen can create the file. */
    @JvmStatic
    fun prepare(context: Context): File? {
        val file = file(context)
        val dir = file.parentFile ?: return null
        if (!dir.isDirectory && !dir.mkdirs()) return null
        return file
    }

    @JvmStatic
    fun clear(context: Context): Boolean {
        val file = file(context)
        return !file.exists() || runCatching { RandomAccessFile(file, "rw").use { it.setLength(0) } }.isSuccess
    }

    fun readFixes(context: Context): List<Fix> {
        val file = file(context)
        if (!file.isFile) return emptyList()
        val text = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val length = raf.length()
                val start = (length - MAX_TAIL_BYTES).coerceAtLeast(0L)
                val bytes = ByteArray((length - start).toInt())
                raf.seek(start)
                raf.readFully(bytes)
                var text = String(bytes, Charsets.UTF_8)
                // A tail that starts mid-row drops the partial first line.
                if (start > 0) text = text.substringAfter('\n', "")
                text
            }
        }.getOrNull() ?: return emptyList()
        val zone = ZoneId.systemDefault()
        return text.lineSequence().mapNotNull { parseRow(it, zone) }.toList()
    }

    /** Groups fixes per source, newest unit first, keeping up to [trackPoints] per track. */
    fun units(fixes: List<Fix>, sinceMillis: Long, trackPoints: Int = 25): List<Radio> {
        return fixes.asSequence()
            .filter { it.epochMillis >= sinceMillis }
            .groupBy { it.source }
            .map { (source, list) ->
                val ordered = list.sortedBy { it.epochMillis }
                Radio(source, ordered.last(), ordered.takeLast(trackPoints), ordered.size)
            }
            .sortedByDescending { it.latest.epochMillis }
    }

    internal fun parseRow(line: String, zone: ZoneId): Fix? {
        val f = line.trim().split(Regex("\\s+"))
        if (f.size < 5) return null
        val date = parseDate(f[0]) ?: return null
        val time = parseTime(f[1]) ?: return null
        val source = f[2].toLongOrNull() ?: return null
        val lat = f[3].toDoubleOrNull() ?: return null
        val lon = f[4].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        if (lat == 0.0 && lon == 0.0) return null
        val speed = f.getOrNull(5)?.toDoubleOrNull() ?: 0.0
        val heading = f.getOrNull(6)?.toDoubleOrNull()?.toInt() ?: 0
        val epoch = LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()
        return Fix(source, lat, lon, speed, heading, epoch, kindOf(f.getOrNull(7)))
    }

    private fun parseDate(s: String): LocalDate? {
        val digits = s.filter { it.isDigit() }
        if (digits.length != 8) return null
        return runCatching {
            LocalDate.of(digits.substring(0, 4).toInt(), digits.substring(4, 6).toInt(), digits.substring(6, 8).toInt())
        }.getOrNull()
    }

    private fun parseTime(s: String): LocalTime? {
        val digits = s.filter { it.isDigit() }
        if (digits.length != 6) return null
        return runCatching {
            LocalTime.of(digits.substring(0, 2).toInt(), digits.substring(2, 4).toInt(), digits.substring(4, 6).toInt())
        }.getOrNull()
    }
}

/**
 * SIG-SENSE: decoder options every listening session gets. They go first on the command
 * line, so options the user typed (a -L, -P/-7/-w or --src-csv of their own) still win.
 *
 * - `-L` writes positions for the map ([PositionLog]).
 * - `-7 … -P` saves each transmission as a WAV for instant replay ([RecentCalls]).
 * - `--src-csv` labels radios with the names edited in the map ([RadioNames]).
 */
object SigSenseSession {
    @JvmStatic
    fun recentDir(context: Context): File = File(File(context.filesDir, "sigsense"), "recent")

    @JvmStatic
    fun namesFile(context: Context): File = File(File(context.filesDir, "sigsense"), "radio_names.csv")

    @JvmStatic
    fun args(context: Context, args: Array<String>): Array<String> {
        val extra = ArrayList<String>()
        PositionLog.prepare(context)?.let { extra += listOf("-L", it.absolutePath) }
        val has = { opt: String -> args.any { it == opt } }
        if (!has("-P") && !has("-7") && !has("-w")) {
            val dir = recentDir(context)
            if (dir.isDirectory || dir.mkdirs()) extra += listOf("-7", dir.absolutePath, "-P")
        }
        if (!has("--src-csv")) {
            val names = namesFile(context)
            if (names.isFile && names.length() > 0L) extra += listOf("--src-csv", names.absolutePath)
        }
        return extra.toTypedArray() + args
    }
}
