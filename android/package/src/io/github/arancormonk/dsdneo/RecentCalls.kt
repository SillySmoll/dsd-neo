// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.arancormonk.dsdneo

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * SIG-SENSE: instant replay. The engine saves each transmission as a per-call WAV in
 * [SigSenseSession.recentDir] (`-7 … -P`); this keeps only the newest few, each cut to at
 * most [MAX_SECONDS], and lists them for the map's Replay button.
 *
 * File names follow the engine's per-call naming (src/core/file/dsd_file.c):
 * `YYYYMMDD_HHMMSS_nnnnn_<sys>[_SVC_x]_GROUP|PRIVATE_TGT_<tg>_SRC_<src>.wav`, or
 * `YYYYMMDD_HHMMSS_nnnnn_NFM[_SVC_x].wav` for analog. Files still being written are
 * named `TEMP_…` and are left alone.
 */
object RecentCalls {
    private const val TAG = "SigSenseRecent"
    const val MAX_SECONDS = 30
    const val DEFAULT_KEEP = 10
    const val PREFS = "sigsense_map"
    const val KEY_KEEP = "replay_keep"

    data class Call(
        val file: File,
        val startMs: Long,
        val talkgroup: String,
        val source: String,
        val sourceId: Long?,
        val group: Boolean,
        val analog: Boolean,
        val durationMs: Long,
    )

    private val DIGITAL = Regex("""^(\d{8})_(\d{6})_\d+_.*?(GROUP|PRIVATE)_TGT_(.+?)_SRC_(.+?)\.wav$""")
    private val ANALOG = Regex("""^(\d{8})_(\d{6})_\d+_NFM(?:_SVC_(.*))?\.wav$""")
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    @JvmStatic
    fun keepCount(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_KEEP, DEFAULT_KEEP).coerceIn(1, 20)

    /** Finished recordings, newest first, at most [keepCount]. */
    @JvmStatic
    fun list(context: Context): List<Call> {
        val dir = SigSenseSession.recentDir(context)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".wav") && !f.name.startsWith("TEMP_") } ?: return emptyList()
        return files.mapNotNull { parse(it) }.sortedByDescending { it.startMs }.take(keepCount(context))
    }

    /** Trims long recordings and deletes all but the newest [keepCount]. Cheap; safe to call often. */
    @JvmStatic
    @Synchronized
    fun maintain(context: Context) {
        runCatching {
            val dir = SigSenseSession.recentDir(context)
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".wav") && !f.name.startsWith("TEMP_") } ?: return
            val keep = keepCount(context)
            val ordered = files.sortedByDescending { parse(it)?.startMs ?: it.lastModified() }
            ordered.drop(keep).forEach { it.delete() }
            ordered.take(keep).forEach { trim(it, MAX_SECONDS) }
            // Stray partial files from a crash: anything TEMP_ untouched for an hour.
            dir.listFiles { f -> f.name.startsWith("TEMP_") && System.currentTimeMillis() - f.lastModified() > 3600_000L }
                ?.forEach { it.delete() }
        }.onFailure { Log.w(TAG, "maintenance failed", it) }
    }

    internal fun parse(file: File): Call? {
        val name = file.name
        val zone = ZoneId.systemDefault()
        DIGITAL.find(name)?.let { m ->
            val start = runCatching {
                LocalDateTime.parse(m.groupValues[1] + m.groupValues[2], STAMP).atZone(zone).toInstant().toEpochMilli()
            }.getOrNull() ?: file.lastModified()
            val src = m.groupValues[5]
            val srcId = src.toLongOrNull()?.takeIf { it > 0 } ?: TalkerLog.radioIdFrom(src)
            return Call(file, start, m.groupValues[4], src, srcId, m.groupValues[3] == "GROUP", false, durationMs(file))
        }
        ANALOG.find(name)?.let { m ->
            val start = runCatching {
                LocalDateTime.parse(m.groupValues[1] + m.groupValues[2], STAMP).atZone(zone).toInstant().toEpochMilli()
            }.getOrNull() ?: file.lastModified()
            return Call(file, start, m.groupValues[3].replace('_', ' '), "", null, false, true, durationMs(file))
        }
        return null
    }

    private data class WavLayout(val byteRate: Int, val blockAlign: Int, val dataOffset: Long, val dataSize: Long)

    private fun layout(raf: RandomAccessFile): WavLayout? {
        if (raf.length() < 44) return null
        val head = ByteArray(12)
        raf.seek(0)
        raf.readFully(head)
        if (String(head, 0, 4, Charsets.US_ASCII) != "RIFF" || String(head, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var pos = 12L
        var byteRate = 0
        var blockAlign = 0
        val chunk = ByteArray(8)
        while (pos + 8 <= raf.length()) {
            raf.seek(pos)
            raf.readFully(chunk)
            val id = String(chunk, 0, 4, Charsets.US_ASCII)
            val size = le32(chunk, 4)
            if (id == "fmt ") {
                val fmt = ByteArray(16)
                raf.readFully(fmt)
                byteRate = le32(fmt, 8).toInt()
                blockAlign = (fmt[12].toInt() and 0xff) or ((fmt[13].toInt() and 0xff) shl 8)
            } else if (id == "data") {
                if (byteRate <= 0) return null
                val available = raf.length() - (pos + 8)
                // A recording still being written (or never finalized) may carry a zero or too-large size.
                val dataSize = if (size <= 0L || size > available) available else size
                return WavLayout(byteRate, blockAlign.coerceAtLeast(1), pos + 8, dataSize)
            }
            pos += 8 + size + (size and 1L)
        }
        return null
    }

    private fun le32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xff) or ((b[off + 1].toLong() and 0xff) shl 8) or
            ((b[off + 2].toLong() and 0xff) shl 16) or ((b[off + 3].toLong() and 0xff) shl 24)

    private fun writeLe32(raf: RandomAccessFile, at: Long, v: Long) {
        raf.seek(at)
        raf.write(byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(), ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte()))
    }

    internal fun durationMs(file: File): Long = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val l = layout(raf) ?: return 0L
            l.dataSize * 1000L / l.byteRate
        }
    }.getOrDefault(0L)

    /** Cuts the audio to [seconds], rewriting the RIFF and data sizes. No-op for shorter files. */
    internal fun trim(file: File, seconds: Int) {
        runCatching {
            RandomAccessFile(file, "rw").use { raf ->
                val l = layout(raf) ?: return
                var max = l.byteRate.toLong() * seconds
                max -= max % l.blockAlign
                if (l.dataSize <= max) return
                raf.setLength(l.dataOffset + max)
                writeLe32(raf, l.dataOffset - 4, max)
                writeLe32(raf, 4, raf.length() - 8)
            }
        }.onFailure { Log.w(TAG, "could not trim ${file.name}", it) }
    }
}
