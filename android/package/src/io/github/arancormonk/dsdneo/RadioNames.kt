// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.arancormonk.dsdneo

import android.content.Context
import android.util.Log
import java.io.File

/**
 * SIG-SENSE: names the user gives radios ("Engine 4" for 61121), edited in the map.
 *
 * Stored as the decoder's source ID list (`id,name` with a header line, see
 * docs/csv-formats.md), so [SigSenseSession] can hand the same file to the engine with
 * `--src-csv` and the names show in call screens too. That format has no escaping, so
 * names are kept free of commas and line breaks and within its 49-byte limit.
 */
object RadioNames {
    private const val TAG = "SigSenseNames"
    private const val MAX_NAME_BYTES = 49

    private val names = LinkedHashMap<Long, String>()
    private var loaded = false

    @JvmStatic
    @Synchronized
    fun get(context: Context, id: Long): String? {
        ensureLoaded(context)
        return names[id]
    }

    @JvmStatic
    @Synchronized
    fun all(context: Context): Map<Long, String> {
        ensureLoaded(context)
        return LinkedHashMap(names)
    }

    /** Sets or, for a blank name, removes a radio's name. Returns the name stored. */
    @JvmStatic
    @Synchronized
    fun set(context: Context, id: Long, name: String): String? {
        ensureLoaded(context)
        val clean = clean(name)
        if (clean.isEmpty()) names.remove(id) else names[id] = clean
        save(context)
        return names[id]
    }

    @JvmStatic
    @Synchronized
    fun remove(context: Context, id: Long) {
        ensureLoaded(context)
        names.remove(id)
        save(context)
    }

    /** Adds or replaces names from `id,name` CSV text; a non-numeric first line is a header. Returns rows taken. */
    @JvmStatic
    @Synchronized
    fun importCsv(context: Context, text: String): Int {
        ensureLoaded(context)
        var count = 0
        for (line in text.lineSequence()) {
            val parts = line.split(',', ';', '\t')
            if (parts.size < 2) continue
            val id = parts[0].trim().toLongOrNull() ?: continue
            if (id <= 0L) continue
            val name = clean(parts[1])
            if (name.isEmpty()) continue
            names[id] = name
            count++
        }
        if (count > 0) save(context)
        return count
    }

    @JvmStatic
    @Synchronized
    fun exportCsv(context: Context): String {
        ensureLoaded(context)
        return render()
    }

    internal fun clean(name: String): String {
        var s = name.replace(Regex("[,\\r\\n\\t\\u0000]"), " ").replace(Regex("\\s+"), " ").trim()
        while (s.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) s = s.dropLast(1)
        return s.trim()
    }

    private fun render(): String = buildString {
        append("id,name\n")
        for ((id, name) in names.entries.sortedBy { it.key }) append(id).append(',').append(name).append('\n')
    }

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val f = SigSenseSession.namesFile(context)
            if (!f.isFile) return
            f.readLines().drop(1).forEach { line ->
                val comma = line.indexOf(',')
                if (comma <= 0) return@forEach
                val id = line.substring(0, comma).trim().toLongOrNull() ?: return@forEach
                val name = clean(line.substring(comma + 1).substringBefore(','))
                if (name.isNotEmpty()) names[id] = name
            }
        }.onFailure { Log.w(TAG, "could not read radio names", it) }
    }

    private fun save(context: Context) {
        runCatching {
            val f = SigSenseSession.namesFile(context)
            f.parentFile?.mkdirs()
            if (names.isEmpty()) {
                f.delete()
                return
            }
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(render())
            if (!tmp.renameTo(f)) {
                f.writeText(render())
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "could not save radio names", it) }
    }
}
