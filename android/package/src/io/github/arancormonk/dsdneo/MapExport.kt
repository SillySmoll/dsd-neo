// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.arancormonk.dsdneo

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** SIG-SENSE: position exports — KML for Google Earth, GPX for mapping apps, CSV for spreadsheets. */
object MapExport {
    enum class Format(val extension: String, val mime: String, val label: String) {
        KML("kml", "application/vnd.google-earth.kml+xml", "KML (Google Earth)"),
        GPX("gpx", "application/gpx+xml", "GPX (mapping apps)"),
        CSV("csv", "text/csv", "CSV (spreadsheet)"),
    }

    /** One radio's fixes, oldest first, and the talkgroup it was last heard on. */
    data class Track(val source: Long, val talkgroup: String, val fixes: List<PositionLog.Fix>)

    private fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    private fun xml(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun csv(s: String): String = if (s.any { it == ',' || it == '"' || it == '\n' }) {
        "\"" + s.replace("\"", "\"\"") + "\""
    } else {
        s
    }

    private fun coord(v: Double) = String.format(Locale.US, "%.6f", v)

    fun render(format: Format, tracks: List<Track>): String = when (format) {
        Format.KML -> kml(tracks)
        Format.GPX -> gpx(tracks)
        Format.CSV -> csvText(tracks)
    }

    private fun kml(tracks: List<Track>) = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>WTDW Tracker positions</name>\n")
        append("<Style id=\"radio\"><IconStyle><color>ff0000ff</color></IconStyle></Style>\n")
        append("<Style id=\"track\"><LineStyle><color>ffffc34f</color><width>3</width></LineStyle></Style>\n")
        for (t in tracks) {
            val last = t.fixes.lastOrNull() ?: continue
            val tg = if (t.talkgroup.isNotEmpty()) " · TG ${xml(t.talkgroup)}" else ""
            append("<Folder><name>Radio ${t.source}$tg</name>\n")
            append("<Placemark><name>${t.source}</name><styleUrl>#radio</styleUrl>")
            append("<TimeStamp><when>${iso(last.epochMillis)}</when></TimeStamp>")
            append("<description>Last report ${iso(last.epochMillis)}; ")
            append(String.format(Locale.US, "%.1f km/h, %d°", last.speedKph, last.heading)).append("</description>")
            append("<Point><coordinates>${coord(last.longitude)},${coord(last.latitude)},0</coordinates></Point></Placemark>\n")
            if (t.fixes.size > 1) {
                append("<Placemark><name>${t.source} track</name><styleUrl>#track</styleUrl><LineString><tessellate>1</tessellate><coordinates>")
                t.fixes.forEach { append(coord(it.longitude)).append(',').append(coord(it.latitude)).append(",0 ") }
                append("</coordinates></LineString></Placemark>\n")
            }
            append("</Folder>\n")
        }
        append("</Document></kml>\n")
    }

    private fun gpx(tracks: List<Track>) = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"WTDW Tracker\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        for (t in tracks) {
            val last = t.fixes.lastOrNull() ?: continue
            append("<wpt lat=\"${coord(last.latitude)}\" lon=\"${coord(last.longitude)}\"><time>${iso(last.epochMillis)}</time>")
            append("<name>${t.source}</name>")
            if (t.talkgroup.isNotEmpty()) append("<desc>TG ${xml(t.talkgroup)}</desc>")
            append("</wpt>\n")
        }
        for (t in tracks.filter { it.fixes.size > 1 }) {
            append("<trk><name>${t.source}</name><trkseg>\n")
            for (f in t.fixes) {
                append("<trkpt lat=\"${coord(f.latitude)}\" lon=\"${coord(f.longitude)}\"><time>${iso(f.epochMillis)}</time></trkpt>\n")
            }
            append("</trkseg></trk>\n")
        }
        append("</gpx>\n")
    }

    private fun csvText(tracks: List<Track>) = buildString {
        append("time_utc,radio,talkgroup,latitude,longitude,speed_kph,heading\n")
        tracks.flatMap { t -> t.fixes.map { t to it } }.sortedBy { it.second.epochMillis }.forEach { (t, f) ->
            append(iso(f.epochMillis)).append(',').append(t.source).append(',').append(csv(t.talkgroup)).append(',')
            append(coord(f.latitude)).append(',').append(coord(f.longitude)).append(',')
            append(String.format(Locale.US, "%.1f", f.speedKph)).append(',').append(f.heading).append('\n')
        }
    }
}
