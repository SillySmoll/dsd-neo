// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.arancormonk.dsdneo

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * SIG-SENSE map: radios that reported a position (DMR LRRP, USBD/LIP, LOCN/NMEA,
 * embedded GPS, P25 LRRP) plotted from the decoder's position file, with short
 * tracks, a units list, base-map choice and the phone's own location.
 */
class PositionMapActivity : Activity() {

    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private var style: Style? = null

    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var countText: TextView
    private lateinit var windowButton: Button
    private lateinit var emptyBanner: TextView
    private lateinit var talkBanner: TextView
    private lateinit var followButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    /** Radios inside the time window; what the count, list and Fit all use. */
    private var radios: List<PositionLog.Radio> = emptyList()
    /** Every radio the position file holds, for Find and Follow, whatever the window. */
    private var allRadios: List<PositionLog.Radio> = emptyList()
    private var selectedSource: Long? = null

    private var followTalker = false
    /** The talker the map last flew to, so one transmission is followed once. */
    private var followedSource: Long? = null
    private val statusIo: ExecutorService = Executors.newSingleThreadExecutor()
    private var fittedOnce = false
    private var polling = false

    private var baseMap = "osm"
    private var windowIndex = 2

    private var myLocation: Location? = null
    private var locationManager: LocationManager? = null
    private val locationListener = LocationListener { location ->
        myLocation = location
        updateMyLocationSource()
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!polling) return
            refreshPositions()
            handler.postDelayed(this, POLL_MS)
        }
    }

    private val talkerPoll = object : Runnable {
        override fun run() {
            if (!polling) return
            if (followTalker) pollTalker()
            handler.postDelayed(this, TALKER_POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        loadPreferences()
        // Draw under the system bars on every API level; the insets listener pads the bars.
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT

        val root = FrameLayout(this)
        root.setBackgroundColor(BG)
        mapView = MapView(this)
        root.addView(mapView, FrameLayout.LayoutParams(MATCH, MATCH))

        topBar = buildTopBar()
        talkBanner = TextView(this).apply {
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            background = panel(TALK_BG)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            gravity = Gravity.CENTER
            visibility = View.GONE
            setOnClickListener { selectedRadio()?.let { showRadio(it) } }
        }
        val topColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        topColumn.addView(topBar, LinearLayout.LayoutParams(MATCH, WRAP))
        topColumn.addView(talkBanner, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            leftMargin = dp(12); rightMargin = dp(12); topMargin = dp(8)
        })
        root.addView(topColumn, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

        emptyBanner = TextView(this).apply {
            text = getString(R.string.map_empty)
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = panel(PANEL)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(emptyBanner, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER).apply {
            leftMargin = dp(24); rightMargin = dp(24)
        })

        bottomBar = buildBottomBar()
        root.addView(bottomBar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        root.setOnApplyWindowInsetsListener { _, insets ->
            val (top, bottom) = systemBarInsets(insets)
            topBar.setPadding(dp(8), top + dp(6), dp(8), dp(6))
            bottomBar.setPadding(dp(8), dp(6), dp(8), bottom + dp(10))
            insets
        }
        setContentView(root)

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { readyMap ->
            map = readyMap
            readyMap.uiSettings.isRotateGesturesEnabled = false
            readyMap.uiSettings.isAttributionEnabled = true
            readyMap.uiSettings.isLogoEnabled = false
            readyMap.cameraPosition = org.maplibre.android.camera.CameraPosition.Builder()
                .target(LatLng(39.5, -98.35)).zoom(3.0).build()
            readyMap.addOnMapClickListener { point -> onMapClick(readyMap, point) }
            applyStyle()
        }
    }

    // ---- UI ------------------------------------------------------------------------------

    private fun buildTopBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BAR)
        }
        bar.addView(pill("‹ Back") { finish() })
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(6), 0)
        }
        titles.addView(TextView(this).apply {
            text = getString(R.string.map_title)
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
        })
        countText = TextView(this).apply {
            setTextColor(MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        titles.addView(countText)
        bar.addView(titles, LinearLayout.LayoutParams(0, WRAP, 1f))
        bar.addView(pill("Find") { showFind() }, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) })
        windowButton = pill(WINDOWS[windowIndex].first) { cycleWindow() }
        bar.addView(windowButton)
        bar.addView(pill("Map") { chooseBaseMap() }, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6) })
        return bar
    }

    private fun buildBottomBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val lp = { LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(4); rightMargin = dp(4) } }
        bar.addView(pill("Units") { showUnits() }, lp())
        bar.addView(pill("Fit all") { fitAll(animate = true) }, lp())
        bar.addView(pill("Me") { centerOnMe() }, lp())
        followButton = pill(followLabel()) { toggleFollow() }
        bar.addView(followButton, lp())
        applyFollowLook()
        return bar
    }

    private fun pill(label: String, onClick: () -> kotlin.Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        background = panel(PANEL, stroke = STROKE)
        minHeight = dp(40); minimumHeight = dp(40)
        minWidth = dp(56); minimumWidth = dp(56)
        setPadding(dp(12), 0, dp(12), 0)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun panel(color: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(12).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun dialog(): AlertDialog.Builder =
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    // ---- Style ---------------------------------------------------------------------------

    private fun applyStyle() {
        val readyMap = map ?: return
        style = null
        readyMap.setStyle(Style.Builder().fromJson(styleJson(baseMap))) { loaded ->
            style = loaded
            installLayers(loaded)
            refreshPositions()
            updateMyLocationSource()
        }
    }

    private fun styleJson(base: String): String {
        val (tiles, attribution) = BASE_MAPS[base] ?: BASE_MAPS.getValue("osm")
        val source = JSONObject()
            .put("type", "raster")
            .put("tiles", JSONArray().put(tiles))
            .put("tileSize", 256)
            .put("maxzoom", 19)
            .put("attribution", attribution)
        return JSONObject()
            .put("version", 8)
            .put("glyphs", "https://fonts.openmaptiles.org/{fontstack}/{range}.pbf")
            .put("sources", JSONObject().put("base", source))
            .put("layers", JSONArray()
                .put(JSONObject().put("id", "background").put("type", "background")
                    .put("paint", JSONObject().put("background-color", "#0b0f14")))
                .put(JSONObject().put("id", "base").put("type", "raster").put("source", "base")))
            .toString()
    }

    private fun installLayers(s: Style) {
        s.addSource(GeoJsonSource(SRC_TRACKS, FeatureCollection.fromFeatures(emptyList())))
        s.addSource(GeoJsonSource(SRC_UNITS, FeatureCollection.fromFeatures(emptyList())))
        s.addSource(GeoJsonSource(SRC_ME, FeatureCollection.fromFeatures(emptyList())))

        s.addLayer(LineLayer(LAYER_TRACKS, SRC_TRACKS).withProperties(
            PropertyFactory.lineColor(Color.parseColor("#4FC3F7")),
            PropertyFactory.lineWidth(2.5f),
            PropertyFactory.lineOpacity(0.7f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ))
        s.addLayer(CircleLayer(LAYER_ME, SRC_ME).withProperties(
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleColor(Color.parseColor("#2979FF")),
            PropertyFactory.circleStrokeColor(Color.WHITE),
            PropertyFactory.circleStrokeWidth(2.5f),
        ))
        s.addLayer(CircleLayer(LAYER_SELECTED, SRC_UNITS).withProperties(
            PropertyFactory.circleRadius(16f),
            PropertyFactory.circleColor(Color.parseColor("#3300E5FF")),
            PropertyFactory.circleStrokeColor(Color.parseColor("#00E5FF")),
            PropertyFactory.circleStrokeWidth(3f),
        ).withFilter(Expression.toBool(Expression.get("selected"))))
        s.addLayer(CircleLayer(LAYER_UNITS, SRC_UNITS).withProperties(
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleColor(
                Expression.switchCase(
                    Expression.toBool(Expression.get("fresh")), Expression.color(Color.parseColor("#FF5252")),
                    Expression.color(Color.parseColor("#FFB300")),
                )
            ),
            PropertyFactory.circleStrokeColor(Color.parseColor("#101418")),
            PropertyFactory.circleStrokeWidth(2f),
        ))
        s.addLayer(SymbolLayer(LAYER_LABELS, SRC_UNITS).withProperties(
            PropertyFactory.textField(Expression.get("label")),
            PropertyFactory.textFont(arrayOf("Open Sans Regular")),
            PropertyFactory.textSize(12f),
            PropertyFactory.textColor(Color.WHITE),
            PropertyFactory.textHaloColor(Color.parseColor("#CC000000")),
            PropertyFactory.textHaloWidth(1.5f),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textOffset(arrayOf(0f, 1.0f)),
            PropertyFactory.textAllowOverlap(false),
        ))
    }

    // ---- Positions -----------------------------------------------------------------------

    private fun refreshPositions() {
        val since = WINDOWS[windowIndex].second.let { if (it <= 0L) 0L else System.currentTimeMillis() - it }
        val context = applicationContext
        if (isDestroyed || io.isShutdown) return
        runCatching {
            io.execute {
                val fixes = PositionLog.readFixes(context)
                val loaded = PositionLog.units(fixes, since)
                val all = if (since == 0L) loaded else PositionLog.units(fixes, 0L)
                handler.post { showPositions(loaded, all) }
            }
        }
    }

    private fun showPositions(loaded: List<PositionLog.Radio>, all: List<PositionLog.Radio>) {
        if (isFinishing || isDestroyed) return
        radios = loaded
        allRadios = all
        renderRadios()
        if (!fittedOnce && loaded.isNotEmpty() && selectedSource == null) {
            fittedOnce = true
            fitAll(animate = false)
        }
    }

    /** Draws the windowed radios, plus the selected one even when it reported before the window. */
    private fun renderRadios() {
        val loaded = radios
        countText.text = when (loaded.size) {
            0 -> getString(R.string.map_count_none)
            1 -> getString(R.string.map_count_one)
            else -> getString(R.string.map_count_many, loaded.size)
        }
        emptyBanner.visibility = if (loaded.isEmpty()) View.VISIBLE else View.GONE
        val s = style ?: return
        val now = System.currentTimeMillis()
        val selected = selectedRadio()
        val shown = if (selected != null && loaded.none { it.source == selected.source }) loaded + selected else loaded
        val points = shown.map { radio ->
            val fix = radio.latest
            Feature.fromGeometry(Point.fromLngLat(fix.longitude, fix.latitude)).apply {
                addStringProperty("source", radio.source.toString())
                addStringProperty("label", "${radio.source} · ${ageText(now - fix.epochMillis)}")
                addBooleanProperty("fresh", now - fix.epochMillis < FRESH_MS)
                addBooleanProperty("selected", radio.source == selectedSource)
            }
        }
        val tracks = shown.filter { it.track.size > 1 }.map { radio ->
            Feature.fromGeometry(LineString.fromLngLats(radio.track.map { Point.fromLngLat(it.longitude, it.latitude) }))
        }
        s.getSourceAs<GeoJsonSource>(SRC_UNITS)?.setGeoJson(FeatureCollection.fromFeatures(points))
        s.getSourceAs<GeoJsonSource>(SRC_TRACKS)?.setGeoJson(FeatureCollection.fromFeatures(tracks))
    }

    private fun selectedRadio(): PositionLog.Radio? {
        val id = selectedSource ?: return null
        return allRadios.firstOrNull { it.source == id }
    }

    /** Rings [radio] and, when [fly], brings it to the middle of the map. */
    private fun select(radio: PositionLog.Radio, fly: Boolean) {
        selectedSource = radio.source
        fittedOnce = true
        renderRadios()
        if (fly) {
            val readyMap = map ?: return
            val zoom = maxOf(readyMap.cameraPosition.zoom, 15.0)
            readyMap.animateCamera(
                CameraUpdateFactory.newLatLngZoom(LatLng(radio.latest.latitude, radio.latest.longitude), zoom), 700)
        }
    }

    // ---- Find ----------------------------------------------------------------------------

    private fun showFind() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.map_find_hint)
            setSingleLine()
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        val d = dialog()
            .setTitle(R.string.map_find_title)
            .setView(box)
            .setPositiveButton(R.string.map_find_go) { _, _ -> findRadio(input.text.toString().trim()) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        input.setOnEditorActionListener { _, _, _ ->
            d.dismiss(); findRadio(input.text.toString().trim()); true
        }
        d.show()
        input.requestFocus()
    }

    private fun findRadio(query: String) {
        if (query.isEmpty()) return
        val exact = query.toLongOrNull()?.let { id -> allRadios.firstOrNull { it.source == id } }
        if (exact != null) {
            select(exact, fly = true)
            showRadio(exact)
            return
        }
        val matches = allRadios.filter { it.source.toString().contains(query) }
        when (matches.size) {
            0 -> toast(getString(R.string.map_find_none, query))
            1 -> { select(matches[0], fly = true); showRadio(matches[0]) }
            else -> {
                val now = System.currentTimeMillis()
                val items = matches.map { "${it.source}   ${ageText(now - it.latest.epochMillis)} ago" }.toTypedArray()
                dialog()
                    .setTitle(getString(R.string.map_find_matches, matches.size, query))
                    .setItems(items) { _, which -> select(matches[which], fly = true); showRadio(matches[which]) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    // ---- Follow the talker ---------------------------------------------------------------

    private fun followLabel(): String = if (followTalker) "Follow ✓" else "Follow"

    private fun applyFollowLook() {
        followButton.text = followLabel()
        followButton.background = panel(if (followTalker) FOLLOW_ON else PANEL, stroke = if (followTalker) FOLLOW_STROKE else STROKE)
    }

    private fun toggleFollow() {
        followTalker = !followTalker
        followedSource = null
        applyFollowLook()
        savePreferences()
        if (followTalker) {
            toast(getString(R.string.map_follow_on))
            pollTalker()
        } else {
            talkBanner.visibility = View.GONE
        }
    }

    /** Reads who is talking from the decoder's status record, off the main thread. */
    private fun pollTalker() {
        if (isDestroyed || statusIo.isShutdown) return
        runCatching {
            statusIo.execute {
                val status = try {
                    DecoderStatus.parse(DsdNative.nativeNotificationStatus())
                } catch (_: Throwable) {
                    null
                }
                val lead = status?.leadSlot?.takeIf { it.state == DecoderStatus.LINE_ACTIVE }
                val src = lead?.srcText.orEmpty()
                val tg = lead?.tgText.orEmpty()
                val id = if (lead == null) null else radioIdFrom(src)
                handler.post { onTalker(lead != null, id, src, tg) }
            }
        }
    }

    private fun onTalker(active: Boolean, id: Long?, srcText: String, tgText: String) {
        if (isFinishing || isDestroyed || !followTalker) return
        if (!active) {
            // Between transmissions: the next key-up re-centres, even by the same radio.
            talkBanner.visibility = View.GONE
            followedSource = null
            return
        }
        val who = srcText.ifBlank { id?.toString() ?: "?" }
        val tg = tgText.ifBlank { "?" }
        val radio = id?.let { wanted -> allRadios.firstOrNull { it.source == wanted } }
        talkBanner.text = if (radio != null) {
            getString(R.string.map_talking, who, tg, ageText(System.currentTimeMillis() - radio.latest.epochMillis))
        } else {
            getString(R.string.map_talking_no_fix, who, tg)
        }
        talkBanner.visibility = View.VISIBLE
        if (radio != null && id != followedSource) {
            followedSource = id
            select(radio, fly = true)
        } else if (radio == null) {
            followedSource = id
        }
    }

    private fun fitAll(animate: Boolean) {
        val readyMap = map ?: return
        val pts = radios.map { LatLng(it.latest.latitude, it.latest.longitude) }
        val update = when {
            pts.isEmpty() -> { toast(getString(R.string.map_count_none)); return }
            pts.size == 1 -> CameraUpdateFactory.newLatLngZoom(pts[0], 14.0)
            else -> {
                val bounds = LatLngBounds.Builder().includes(pts).build()
                if (bounds.latitudeSpan < 0.002 && bounds.longitudeSpan < 0.002) {
                    CameraUpdateFactory.newLatLngZoom(bounds.center, 15.0)
                } else {
                    CameraUpdateFactory.newLatLngBounds(bounds, dp(64))
                }
            }
        }
        if (animate) readyMap.animateCamera(update, 600) else readyMap.moveCamera(update)
    }

    private fun onMapClick(readyMap: MapLibreMap, point: LatLng): Boolean {
        val screen: PointF = readyMap.projection.toScreenLocation(point)
        val hits = readyMap.queryRenderedFeatures(screen, LAYER_UNITS, LAYER_LABELS)
        val source = hits.firstOrNull()?.getStringProperty("source") ?: return false
        allRadios.firstOrNull { it.source.toString() == source }?.let { select(it, fly = false); showRadio(it) }
        return true
    }

    private fun showUnits() {
        if (radios.isEmpty()) { toast(getString(R.string.map_count_none)); return }
        val now = System.currentTimeMillis()
        val items = radios.map { r ->
            "${r.source}   ${ageText(now - r.latest.epochMillis)} ago   (${r.reports} report${if (r.reports == 1) "" else "s"})"
        }.toTypedArray()
        dialog()
            .setTitle(getString(R.string.map_units_title, radios.size))
            .setItems(items) { _, which ->
                val r = radios[which]
                select(r, fly = true)
                showRadio(r)
            }
            .setNeutralButton(R.string.map_clear_all) { _, _ -> confirmClear() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRadio(radio: PositionLog.Radio) {
        val fix = radio.latest
        val coords = String.format(Locale.US, "%.6f, %.6f", fix.latitude, fix.longitude)
        val time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(fix.epochMillis))
        val distance = myLocation?.let { me ->
            val out = FloatArray(1)
            Location.distanceBetween(me.latitude, me.longitude, fix.latitude, fix.longitude, out)
            if (out[0] >= 1000f) String.format(Locale.US, "%.2f km", out[0] / 1000f)
            else String.format(Locale.US, "%.0f m", out[0])
        }
        val body = buildString {
            append("Position: ").append(coords).append('\n')
            append("Last report: ").append(time)
            append(" (").append(ageText(System.currentTimeMillis() - fix.epochMillis)).append(" ago)\n")
            append("Speed: ").append(String.format(Locale.US, "%.1f km/h", fix.speedKph))
            append("   Heading: ").append(fix.heading).append("°\n")
            append("Reports in window: ").append(radio.reports)
            if (distance != null) append("\nDistance from you: ").append(distance)
        }
        dialog()
            .setTitle(getString(R.string.map_radio_title, radio.source.toString()))
            .setMessage(body)
            .setPositiveButton(R.string.map_open_in_maps) { _, _ ->
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("geo:${fix.latitude},${fix.longitude}?q=${fix.latitude},${fix.longitude}(Radio%20${radio.source})")))
                }.onFailure { toast(getString(R.string.map_no_maps_app)) }
            }
            .setNeutralButton(R.string.map_copy) { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("position", coords))
                toast(getString(R.string.map_copied))
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    private fun confirmClear() {
        dialog()
            .setTitle(R.string.map_clear_title)
            .setMessage(R.string.map_clear_body)
            .setPositiveButton(R.string.map_clear_confirm) { _, _ ->
                if (PositionLog.clear(this)) {
                    fittedOnce = false
                    selectedSource = null
                    followedSource = null
                    refreshPositions()
                } else {
                    toast(getString(R.string.map_clear_failed))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun cycleWindow() {
        windowIndex = (windowIndex + 1) % WINDOWS.size
        windowButton.text = WINDOWS[windowIndex].first
        savePreferences()
        refreshPositions()
    }

    private fun chooseBaseMap() {
        val keys = BASE_MAPS.keys.toList()
        val names = keys.map { BASE_MAP_NAMES[it] ?: it }.toTypedArray()
        dialog()
            .setTitle(R.string.map_base_title)
            .setSingleChoiceItems(names, keys.indexOf(baseMap).coerceAtLeast(0)) { d, which ->
                d.dismiss()
                if (keys[which] != baseMap) {
                    baseMap = keys[which]
                    savePreferences()
                    applyStyle()
                }
            }
            .show()
    }

    // ---- My location ---------------------------------------------------------------------

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun centerOnMe() {
        if (!hasLocationPermission()) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQ_LOCATION,
            )
            return
        }
        startLocationUpdates()
        val me = myLocation
        if (me == null) {
            toast(getString(R.string.map_locating))
            return
        }
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(me.latitude, me.longitude), 14.0), 600)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION && hasLocationPermission()) centerOnMe()
    }

    @Suppress("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasLocationPermission() || locationManager != null) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        locationManager = lm
        val providers = lm.getProviders(true)
        var best: Location? = null
        for (p in providers) {
            val l = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: continue
            if (best == null || l.time > best.time) best = l
        }
        if (best != null) { myLocation = best; updateMyLocationSource() }
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (p in providers) {
                runCatching { lm.requestLocationUpdates(p, 5000L, 5f, locationListener, Looper.getMainLooper()) }
            }
        }
    }

    private fun stopLocationUpdates() {
        locationManager?.let { lm -> runCatching { lm.removeUpdates(locationListener) } }
        locationManager = null
    }

    private fun updateMyLocationSource() {
        val s = style ?: return
        val me = myLocation
        val features = if (me == null) emptyList() else listOf(Feature.fromGeometry(Point.fromLngLat(me.longitude, me.latitude)))
        s.getSourceAs<GeoJsonSource>(SRC_ME)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    // ---- Helpers -------------------------------------------------------------------------

    private fun ageText(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m"
            s < 86400 -> "${s / 3600}h ${(s % 3600) / 60}m"
            else -> "${s / 86400}d"
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun systemBarInsets(insets: WindowInsets): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            i.top to i.bottom
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetTop to insets.systemWindowInsetBottom
        }
    }

    private fun loadPreferences() {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        baseMap = p.getString(KEY_BASE, baseMap)?.takeIf { it in BASE_MAPS } ?: "osm"
        windowIndex = p.getInt(KEY_WINDOW, windowIndex).coerceIn(0, WINDOWS.size - 1)
        followTalker = p.getBoolean(KEY_FOLLOW, false)
    }

    private fun savePreferences() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_BASE, baseMap).putInt(KEY_WINDOW, windowIndex)
            .putBoolean(KEY_FOLLOW, followTalker).apply()
    }

    // ---- Lifecycle -----------------------------------------------------------------------

    override fun onStart() { super.onStart(); mapView.onStart() }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        polling = true
        handler.removeCallbacks(poll)
        handler.post(poll)
        handler.removeCallbacks(talkerPoll)
        handler.post(talkerPoll)
        if (hasLocationPermission()) startLocationUpdates()
    }

    override fun onPause() {
        polling = false
        handler.removeCallbacks(poll)
        handler.removeCallbacks(talkerPoll)
        stopLocationUpdates()
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() { mapView.onStop(); super.onStop() }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        io.shutdownNow()
        statusIo.shutdownNow()
        mapView.onDestroy()
        super.onDestroy()
    }

    companion object {
        private const val POLL_MS = 3000L
        private const val TALKER_POLL_MS = 1000L
        private const val KEY_FOLLOW = "follow_talker"
        private const val FRESH_MS = 2 * 60 * 1000L
        private const val REQ_LOCATION = 4101
        private const val PREFS = "sigsense_map"
        private const val KEY_BASE = "base"
        private const val KEY_WINDOW = "window"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        private const val SRC_UNITS = "sig-units"
        private const val SRC_TRACKS = "sig-tracks"
        private const val SRC_ME = "sig-me"
        private const val LAYER_UNITS = "sig-units-dots"
        private const val LAYER_LABELS = "sig-units-labels"
        private const val LAYER_TRACKS = "sig-tracks-lines"
        private const val LAYER_ME = "sig-me-dot"
        private const val LAYER_SELECTED = "sig-units-selected"

        private val BG = Color.parseColor("#0B0F14")
        private val BAR = Color.parseColor("#E60B0F14")
        private val PANEL = Color.parseColor("#E61A2129")
        private val STROKE = Color.parseColor("#33FFFFFF")
        private val TEXT = Color.parseColor("#F2F5F8")
        private val MUTED = Color.parseColor("#9AA5B1")
        private val TALK_BG = Color.parseColor("#E6B71C1C")
        private val FOLLOW_ON = Color.parseColor("#E6006064")
        private val FOLLOW_STROKE = Color.parseColor("#00E5FF")

        private val TRAILING_ID = Regex("""\((\d{1,10})\)\s*$""")

        /**
         * The radio ID in a status record's talker text: "30534", or "Alias (30534)" when the
         * radio has an alias. Null when the text carries no number (an alias with no ID).
         */
        @JvmStatic
        internal fun radioIdFrom(text: String): Long? {
            val t = text.trim()
            TRAILING_ID.find(t)?.let { return it.groupValues[1].toLongOrNull() }
            return if (t.isNotEmpty() && t.length <= 10 && t.all { it.isDigit() }) t.toLongOrNull()?.takeIf { it > 0 } else null
        }

        /** Label and how far back it reaches (0 = everything kept in the file). */
        private val WINDOWS = listOf(
            "30 min" to 30 * 60 * 1000L,
            "1 hr" to 60 * 60 * 1000L,
            "6 hr" to 6 * 60 * 60 * 1000L,
            "24 hr" to 24 * 60 * 60 * 1000L,
            "All" to 0L,
        )

        private val BASE_MAPS = linkedMapOf(
            "osm" to ("https://tile.openstreetmap.org/{z}/{x}/{y}.png" to "© OpenStreetMap contributors"),
            "dark" to ("https://a.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png" to "© OpenStreetMap contributors © CARTO"),
            "satellite" to ("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}" to "Esri, Maxar, Earthstar Geographics"),
            "topo" to ("https://server.arcgisonline.com/ArcGIS/rest/services/World_Topo_Map/MapServer/tile/{z}/{y}/{x}" to "Esri"),
            "streets" to ("https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}" to "Esri"),
        )
        private val BASE_MAP_NAMES = mapOf(
            "osm" to "OpenStreetMap", "dark" to "Dark", "satellite" to "Satellite", "topo" to "Topo", "streets" to "Streets",
        )

        /** Called by name from decoder_host_android.cpp; keep in proguard-rules.pro. */
        @JvmStatic
        fun open(context: Context) {
            val intent = Intent(context, PositionMapActivity::class.java)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
