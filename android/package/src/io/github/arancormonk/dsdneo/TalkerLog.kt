// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.arancormonk.dsdneo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

/**
 * SIG-SENSE: what the position file does not say about a radio — the talkgroup it last
 * talked on and when it last declared an emergency — learned from the decoder's status
 * record, which [ScreenSupport.statusTick] hands over once a second while a session runs.
 *
 * Kept in memory for the map (same process) and saved to `files/sigsense/talkers.tsv` so
 * it survives restarts: `source \t talkgroup \t lastHeardMillis \t emergencyMillis`.
 */
object TalkerLog {
    private const val TAG = "SigSenseTalkers"
    private const val SAVE_EVERY_MS = 5_000L
    private const val HEARD_REFRESH_MS = 30_000L
    /** One emergency notification per radio in this long, however long the call. */
    private const val ALERT_HOLDOFF_MS = 2 * 60_000L
    private const val CHANNEL_ID = "sigsense_emergency"
    const val EXTRA_FOCUS_SOURCE = "io.github.arancormonk.dsdneo.FOCUS_SOURCE"

    data class Entry(val source: Long, val talkgroup: String, val lastHeardMs: Long, val emergencyMs: Long)

    private val entries = HashMap<Long, Entry>()
    private val lastAlert = HashMap<Long, Long>()
    private var loaded = false
    private var dirty = false
    private var lastSave = 0L
    private val writer = Executors.newSingleThreadExecutor()

    private val TRAILING_ID = Regex("""\((\d{1,10})\)\s*$""")

    /**
     * The radio ID in a status record's talker text: "30534", or "Alias (30534)" when the
     * radio has an alias. Null when the text carries no number (an alias with no ID).
     */
    @JvmStatic
    fun radioIdFrom(text: String): Long? {
        val t = text.trim()
        TRAILING_ID.find(t)?.let { return it.groupValues[1].toLongOrNull() }
        return if (t.isNotEmpty() && t.length <= 10 && t.all { it.isDigit() }) t.toLongOrNull()?.takeIf { it > 0 } else null
    }

    private fun file(context: Context) = File(File(context.filesDir, "sigsense"), "talkers.tsv")

    /** One status tick. Cheap: compares a couple of fields, saves at most every few seconds. */
    @JvmStatic
    @Synchronized
    fun observe(context: Context, status: DecoderStatus?) {
        if (status == null) return
        ensureLoaded(context)
        val now = System.currentTimeMillis()
        for (slot in status.slots) {
            if (slot.state != DecoderStatus.LINE_ACTIVE) continue
            val id = radioIdFrom(slot.srcText) ?: continue
            val tg = slot.tgText.trim().ifEmpty { if (slot.tgId != 0UL) slot.tgId.toString() else "" }
            val prev = entries[id]
            val emergencyMs = if (slot.emergency) now else prev?.emergencyMs ?: 0L
            val changed = prev == null || (tg.isNotEmpty() && prev.talkgroup != tg) ||
                now - prev.lastHeardMs > HEARD_REFRESH_MS || (slot.emergency && now - prev.emergencyMs > HEARD_REFRESH_MS)
            if (changed) {
                entries[id] = Entry(id, tg.ifEmpty { prev?.talkgroup ?: "" }, now, emergencyMs)
                dirty = true
            }
            if (slot.emergency) alert(context, id, slot.srcText.trim().ifEmpty { id.toString() }, tg, now)
        }
        if (dirty && now - lastSave > SAVE_EVERY_MS) save(context, now)
    }

    @JvmStatic
    @Synchronized
    fun snapshot(context: Context): Map<Long, Entry> {
        ensureLoaded(context)
        return HashMap(entries)
    }

    @JvmStatic
    @Synchronized
    fun clear(context: Context) {
        entries.clear()
        lastAlert.clear()
        loaded = true
        dirty = false
        val f = file(context)
        writer.execute { runCatching { f.delete() } }
    }

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val f = file(context)
            if (!f.isFile) return
            f.forEachLine { line ->
                val p = line.split('\t')
                if (p.size < 4) return@forEachLine
                val id = p[0].toLongOrNull() ?: return@forEachLine
                entries[id] = Entry(id, p[1], p[2].toLongOrNull() ?: 0L, p[3].toLongOrNull() ?: 0L)
            }
        }.onFailure { Log.w(TAG, "could not read talker log", it) }
    }

    private fun save(context: Context, now: Long) {
        dirty = false
        lastSave = now
        val text = buildString {
            for (e in entries.values) {
                append(e.source).append('\t').append(e.talkgroup.replace('\t', ' ').replace('\n', ' '))
                append('\t').append(e.lastHeardMs).append('\t').append(e.emergencyMs).append('\n')
            }
        }
        val f = file(context)
        writer.execute {
            runCatching {
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(f)) {
                    f.writeText(text)
                    tmp.delete()
                }
            }.onFailure { Log.w(TAG, "could not save talker log", it) }
        }
    }

    // ---- Emergency notification ----------------------------------------------------------

    private fun alert(context: Context, id: Long, who: String, tg: String, now: Long) {
        val last = lastAlert[id] ?: 0L
        if (now - last < ALERT_HOLDOFF_MS) return
        lastAlert[id] = now
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.emergency_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.emergency_channel_description)
                enableLights(true)
                lightColor = Color.RED
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)
            val open = Intent(context, PositionMapActivity::class.java)
                .putExtra(EXTRA_FOCUS_SOURCE, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pending = PendingIntent.getActivity(
                context, (id and 0x7fffffff).toInt(), open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val text = if (tg.isNotEmpty()) {
                context.getString(R.string.emergency_text_tg, who, tg)
            } else {
                context.getString(R.string.emergency_text, who)
            }
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_dsdneo)
                .setContentTitle(context.getString(R.string.emergency_title))
                .setContentText(text)
                .setCategory(Notification.CATEGORY_ALARM)
                .setColor(Color.RED)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()
            nm.notify(EMERGENCY_NOTIFICATION_BASE + (id % 100_000).toInt(), notification)
        }.onFailure { Log.w(TAG, "emergency notification failed", it) }
    }

    private const val EMERGENCY_NOTIFICATION_BASE = 4200_000
}
