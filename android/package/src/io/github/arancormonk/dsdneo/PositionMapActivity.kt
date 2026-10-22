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
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.text.SimpleDateFormat
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
import org.maplibre.android.offline.OfflineManager
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
 * embedded GPS, P25 LRRP) plotted from the decoder's position file, with tracks,
 * talkgroups and emergencies learned from the call status ([TalkerLog]), search,
 * follow-the-talker, track history, exports, reference overlays and radar.
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
    private lateinit var alertBanner: TextView
    private lateinit var historyBar: TextView
    private lateinit var followButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val statusIo: ExecutorService = Executors.newSingleThreadExecutor()
    private val netIo: ExecutorService = Executors.newFixedThreadPool(2)

    /** Radios inside the time window and talkgroup filter; what the count, list and Fit all use. */
    private var radios: List<PositionLog.Radio> = emptyList()
    /** Every radio the position file holds, for Find, Follow and alerts, whatever the window. */
    private var allRadios: List<PositionLog.Radio> = emptyList()
    private var allFixes: List<PositionLog.Fix> = emptyList()
    private var talkers: Map<Long, TalkerLog.Entry> = emptyMap()
    private var selectedSource: Long? = null
    private var fittedOnce = false
    private var polling = false

    private var baseMap = "osm"
    private var windowIndex = 2
    private var tgFilter: String? = null
    private val overlays = LinkedHashSet<String>()
    private var radar = "off"
    private var cacheMb = 512

    private var followTalker = false
    /** The talker the map last flew to, so one transmission is followed once. */
    private var followedSource: Long? = null

    private var historySource: Long? = null
    private var historyRangeMs = 0L

    /** Newest emergency already acted on; older ones never re-trigger the jump. */
    private var emergencySeenMs = -1L
    private var pendingFocus: Long? = null
    private var overlayGeneration = 0

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

    /** Re-fetches the URL-backed overlays (warnings, TFRs, drone areas) so they stay current. */
    private val overlayRefresh = object : Runnable {
        override fun run() {
            if (!polling) return
            val s = style
            if (s != null) {
                for (o in OVERLAYS) {
                    if (o.id in overlays && o.liveMinZoom == 0.0) {
                        runCatching { s.getSourceAs<GeoJsonSource>(o.id)?.setUri(o.url) }
                    }
                }
            }
            handler.postDelayed(this, OVERLAY_REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        loadPreferences()
        applyCacheSize()
        pendingFocus = focusFrom(intent)
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
        alertBanner = banner(ALERT_BG).apply {
            setOnClickListener { v ->
                (v.tag as? Long)?.let { id -> allRadios.firstOrNull { it.source == id }?.let { select(it, true); showRadio(it) } }
                visibility = View.GONE
            }
        }
        talkBanner = banner(TALK_BG).apply { setOnClickListener { selectedRadio()?.let { showRadio(it) } } }
        historyBar = banner(HISTORY_BG).apply { setOnClickListener { closeHistory() } }
        val topColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        topColumn.addView(topBar, LinearLayout.LayoutParams(MATCH, WRAP))
        for (b in listOf(alertBanner, talkBanner, historyBar)) {
            topColumn.addView(b, LinearLayout.LayoutParams(MATCH, WRAP).apply {
                leftMargin = dp(12); rightMargin = dp(12); topMargin = dp(8)
            })
        }
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
                .target(LatLng(36.3, -88.3)).zoom(9.0).build()
            readyMap.addOnMapClickListener { point -> onMapClick(readyMap, point) }
            readyMap.addOnCameraIdleListener { refreshLiveOverlays() }
            applyStyle()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        focusFrom(intent)?.let { id ->
            pendingFocus = id
            refreshPositions()
        }
    }

    private fun focusFrom(intent: Intent?): Long? =
        intent?.getLongExtra(TalkerLog.EXTRA_FOCUS_SOURCE, -1L)?.takeIf { it > 0 }

    // ---- UI ------------------------------------------------------------------------------

    private fun buildTopBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BAR)
        }
        bar.addView(pill("‹") { finish() })
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(6), 0)
        }
        titles.addView(TextView(this).apply {
            text = getString(R.string.map_title)
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
        })
        countText = TextView(this).apply {
            setTextColor(MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        titles.addView(countText)
        bar.addView(titles, LinearLayout.LayoutParams(0, WRAP, 1f))
        val gap = { LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6) } }
        bar.addView(pill("Find") { showFind() })
        windowButton = pill(WINDOWS[windowIndex].first) { cycleWindow() }
        bar.addView(windowButton, gap())
        bar.addView(pill("Layers") { showLayersMenu() }, gap())
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

    private fun banner(color: Int) = TextView(this).apply {
        setTextColor(TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.DEFAULT_BOLD
        background = panel(color)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        gravity = Gravity.CENTER
        visibility = View.GONE
    }

    private fun pill(label: String, onClick: () -> kotlin.Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        background = panel(PANEL, stroke = STROKE)
        minHeight = dp(40); minimumHeight = dp(40)
        minWidth = dp(44); minimumWidth = dp(44)
        setPadding(dp(10), 0, dp(10), 0)
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
        overlayGeneration++
        readyMap.setStyle(Style.Builder().fromJson(styleJson())) { loaded ->
            style = loaded
            installLayers(loaded)
            refreshPositions()
            updateMyLocationSource()
            refreshLiveOverlays()
        }
    }

    private fun rasterSource(url: String, maxZoom: Int = 19, attribution: String? = null): JSONObject =
        JSONObject().put("type", "raster").put("tiles", JSONArray().put(url)).put("tileSize", 256).put("maxzoom", maxZoom)
            .apply { if (attribution != null) put("attribution", attribution) }

    private fun styleJson(): String {
        val (tiles, attribution) = BASE_MAPS[baseMap] ?: BASE_MAPS.getValue("osm")
        val sources = JSONObject().put("base", rasterSource(tiles, 19, attribution))
        val layers = JSONArray()
            .put(JSONObject().put("id", "background").put("type", "background")
                .put("paint", JSONObject().put("background-color", "#0b0f14")))
            .put(JSONObject().put("id", "base").put("type", "raster").put("source", "base"))
        RADARS[radar]?.let { r ->
            val url = "https://opengeo.ncep.noaa.gov/geoserver/${r.workspace}/${r.layer}/ows?service=WMS&version=1.1.1" +
                "&request=GetMap&layers=${r.layer}&styles=&format=image/png&transparent=true&height=256&width=256" +
                "&srs=EPSG:3857&bbox={bbox-epsg-3857}"
            sources.put("radar", JSONObject().put("type", "raster").put("tiles", JSONArray().put(url)).put("tileSize", 256)
                .put("attribution", "NOAA/NWS radar"))
            layers.put(JSONObject().put("id", "radar").put("type", "raster").put("source", "radar")
                .put("paint", JSONObject().put("raster-opacity", 0.6)))
        }
        for (o in OVERLAYS) {
            if (o.id !in overlays) continue
            val data: Any = if (o.liveMinZoom > 0.0) JSONObject().put("type", "FeatureCollection").put("features", JSONArray()) else o.url
            sources.put(o.id, JSONObject().put("type", "geojson").put("data", data))
            val color: Any = if (o.id == "warnings") warningColorExpression() else o.color
            layers.put(JSONObject().put("id", "${o.id}-fill").put("type", "fill").put("source", o.id)
                .put("filter", geometryFilter("Polygon", "MultiPolygon"))
                .put("paint", JSONObject().put("fill-color", color).put("fill-opacity", o.opacity)))
            layers.put(JSONObject().put("id", "${o.id}-line").put("type", "line").put("source", o.id)
                .put("filter", geometryFilter("Polygon", "MultiPolygon", "LineString", "MultiLineString"))
                .put("paint", JSONObject().put("line-color", color).put("line-width", 1.5)))
            layers.put(JSONObject().put("id", "${o.id}-points").put("type", "circle").put("source", o.id)
                .put("filter", geometryFilter("Point", "MultiPoint"))
                .put("paint", JSONObject().put("circle-color", color).put("circle-radius", 4)
                    .put("circle-stroke-color", "#101416").put("circle-stroke-width", 1)))
            if (o.id == "address-tn") {
                layers.put(JSONObject().put("id", "${o.id}-labels").put("type", "symbol").put("source", o.id)
                    .put("layout", JSONObject().put("text-field", addressLabelExpression())
                        .put("text-font", JSONArray().put("Open Sans Regular")).put("text-size", 11)
                        .put("text-anchor", "top").put("text-offset", JSONArray().put(0).put(0.7)))
                    .put("paint", JSONObject().put("text-color", "#ffffff").put("text-halo-color", "#101416")
                        .put("text-halo-width", 2)))
            }
        }
        return JSONObject()
            .put("version", 8)
            .put("glyphs", "https://fonts.openmaptiles.org/{fontstack}/{range}.pbf")
            .put("sources", sources)
            .put("layers", layers)
            .toString()
    }

    private fun geometryFilter(vararg types: String): JSONArray {
        val list = JSONArray()
        types.forEach { list.put(it) }
        return JSONArray().put("match").put(JSONArray().put("geometry-type")).put(list).put(true).put(false)
    }

    private fun warningColorExpression(): JSONArray = JSONArray().put("match").put(JSONArray().put("get").put("prod_type"))
        .put("Tornado Warning").put("#f44336").put("Severe Thunderstorm Warning").put("#fdd835")
        .put("Flash Flood Warning").put("#2196f3").put("Flood Warning").put("#2196f3").put("#ffffff")

    private fun addressLabelExpression(): JSONArray {
        val concat = JSONArray().put("concat")
        listOf("AddNum_Pre", "Add_Number", "AddNum_Suf", "StNam_Full").forEachIndexed { i, field ->
            if (i > 0) concat.put(" ")
            concat.put(JSONArray().put("to-string").put(JSONArray().put("coalesce").put(JSONArray().put("get").put(field)).put("")))
        }
        return concat
    }

    private fun installLayers(s: Style) {
        val empty = FeatureCollection.fromFeatures(emptyList())
        for (id in listOf(SRC_TRACKS, SRC_UNITS, SRC_ME, SRC_HISTORY_LINE, SRC_HISTORY_PTS)) {
            s.addSource(GeoJsonSource(id, empty))
        }
        s.addLayer(LineLayer(LAYER_TRACKS, SRC_TRACKS).withProperties(
            PropertyFactory.lineColor(Color.parseColor("#4FC3F7")),
            PropertyFactory.lineWidth(2.5f),
            PropertyFactory.lineOpacity(0.7f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ))
        s.addLayer(LineLayer(LAYER_HISTORY_LINE, SRC_HISTORY_LINE).withProperties(
            PropertyFactory.lineColor(Color.parseColor("#B388FF")),
            PropertyFactory.lineWidth(3.5f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ))
        s.addLayer(CircleLayer(LAYER_HISTORY_PTS, SRC_HISTORY_PTS).withProperties(
            PropertyFactory.circleRadius(4.5f),
            PropertyFactory.circleColor(Color.WHITE),
            PropertyFactory.circleStrokeColor(Color.parseColor("#7C4DFF")),
            PropertyFactory.circleStrokeWidth(2f),
        ))
        s.addLayer(SymbolLayer(LAYER_HISTORY_LABELS, SRC_HISTORY_PTS).withProperties(
            PropertyFactory.textField(Expression.get("time")),
            PropertyFactory.textFont(arrayOf("Open Sans Regular")),
            PropertyFactory.textSize(11f),
            PropertyFactory.textColor(Color.WHITE),
            PropertyFactory.textHaloColor(Color.parseColor("#CC000000")),
            PropertyFactory.textHaloWidth(1.5f),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_LEFT),
            PropertyFactory.textOffset(arrayOf(0.8f, 0f)),
            PropertyFactory.textAllowOverlap(false),
        ))
        s.addLayer(CircleLayer(LAYER_ME, SRC_ME).withProperties(
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleColor(Color.parseColor("#2979FF")),
            PropertyFactory.circleStrokeColor(Color.WHITE),
            PropertyFactory.circleStrokeWidth(2.5f),
        ))
        s.addLayer(CircleLayer(LAYER_EMERGENCY, SRC_UNITS).withProperties(
            PropertyFactory.circleRadius(20f),
            PropertyFactory.circleColor(Color.parseColor("#55FF1744")),
            PropertyFactory.circleStrokeColor(Color.parseColor("#FF1744")),
            PropertyFactory.circleStrokeWidth(3f),
        ).withFilter(Expression.toBool(Expression.get("emergency"))))
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
                    Expression.toBool(Expression.get("emergency")), Expression.color(Color.parseColor("#FF1744")),
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

    // ---- Live overlays -------------------------------------------------------------------

    /** Bounding-box overlays (DoD, obstacles, Tennessee addresses and parcels) follow the view. */
    private fun refreshLiveOverlays() {
        val readyMap = map ?: return
        val s = style ?: return
        val zoom = readyMap.cameraPosition.zoom
        val bounds = runCatching { readyMap.projection.visibleRegion.latLngBounds }.getOrNull() ?: return
        val generation = overlayGeneration
        for (o in OVERLAYS) {
            if (o.id !in overlays || o.liveMinZoom <= 0.0) continue
            if (zoom < o.liveMinZoom) {
                runCatching { s.getSourceAs<GeoJsonSource>(o.id)?.setGeoJson(FeatureCollection.fromFeatures(emptyList())) }
                continue
            }
            val url = arcGisQuery(o.url, bounds)
            if (netIo.isShutdown) return
            runCatching {
                netIo.execute {
                    val json = fetch(url) ?: return@execute
                    handler.post {
                        if (generation != overlayGeneration || isDestroyed) return@post
                        runCatching { style?.getSourceAs<GeoJsonSource>(o.id)?.setGeoJson(json) }
                    }
                }
            }
        }
    }

    private fun arcGisQuery(base: String, b: LatLngBounds): String = Uri.parse(base).buildUpon()
        .appendQueryParameter("f", "geojson")
        .appendQueryParameter("where", "1=1")
        .appendQueryParameter("outFields", "*")
        .appendQueryParameter("returnGeometry", "true")
        .appendQueryParameter("outSR", "4326")
        .appendQueryParameter("geometry", "${b.longitudeWest},${b.latitudeSouth},${b.longitudeEast},${b.latitudeNorth}")
        .appendQueryParameter("geometryType", "esriGeometryEnvelope")
        .appendQueryParameter("inSR", "4326")
        .appendQueryParameter("spatialRel", "esriSpatialRelIntersects")
        .appendQueryParameter("resultRecordCount", "2000")
        .build().toString()

    private fun fetch(url: String): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "SIG-SENSE (DSD-neo)")
        try {
            if (c.responseCode !in 200..299) null else c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    // ---- Positions -----------------------------------------------------------------------

    private fun windowStart(): Long =
        WINDOWS[windowIndex].second.let { if (it <= 0L) 0L else System.currentTimeMillis() - it }

    private fun refreshPositions() {
        val since = windowStart()
        val context = applicationContext
        if (isDestroyed || io.isShutdown) return
        runCatching {
            io.execute {
                val fixes = PositionLog.readFixes(context)
                val loaded = PositionLog.units(fixes, since)
                val all = if (since == 0L) loaded else PositionLog.units(fixes, 0L)
                val known = TalkerLog.snapshot(context)
                handler.post { showPositions(fixes, loaded, all, known) }
            }
        }
    }

    private fun showPositions(
        fixes: List<PositionLog.Fix>,
        loaded: List<PositionLog.Radio>,
        all: List<PositionLog.Radio>,
        known: Map<Long, TalkerLog.Entry>,
    ) {
        if (isFinishing || isDestroyed) return
        allFixes = fixes
        allRadios = all
        talkers = known
        val filter = tgFilter
        radios = if (filter == null) loaded else loaded.filter { talkers[it.source]?.talkgroup == filter }
        renderRadios()
        if (historySource != null) renderHistory(fit = false)
        checkEmergencies()
        pendingFocus?.let { id ->
            pendingFocus = null
            val radio = allRadios.firstOrNull { it.source == id }
            if (radio != null) {
                select(radio, fly = true)
                showRadio(radio)
            } else {
                toast(getString(R.string.map_find_none, id.toString()))
            }
        }
        if (!fittedOnce && radios.isNotEmpty() && selectedSource == null) {
            fittedOnce = true
            fitAll(animate = false)
        }
    }

    private fun tgOf(source: Long): String = talkers[source]?.talkgroup.orEmpty()

    private fun isEmergency(source: Long, now: Long): Boolean {
        val e = talkers[source]?.emergencyMs ?: 0L
        return e > 0L && now - e < EMERGENCY_SHOW_MS
    }

    /** Draws the filtered radios, plus the selected one even when it falls outside them. */
    private fun renderRadios() {
        val loaded = radios
        val filter = tgFilter
        countText.text = buildString {
            append(when (loaded.size) {
                0 -> getString(R.string.map_count_none)
                1 -> getString(R.string.map_count_one)
                else -> getString(R.string.map_count_many, loaded.size)
            })
            if (filter != null) append(" · TG ").append(filter)
        }
        emptyBanner.visibility = if (loaded.isEmpty() && filter == null) View.VISIBLE else View.GONE
        val s = style ?: return
        val now = System.currentTimeMillis()
        val selected = selectedRadio()
        val shown = if (selected != null && loaded.none { it.source == selected.source }) loaded + selected else loaded
        val points = shown.map { radio ->
            val fix = radio.latest
            val tg = tgOf(radio.source)
            Feature.fromGeometry(Point.fromLngLat(fix.longitude, fix.latitude)).apply {
                addStringProperty("source", radio.source.toString())
                val tgPart = if (tg.isNotEmpty()) " · TG $tg" else ""
                addStringProperty("label", "${radio.source}$tgPart · ${ageText(now - fix.epochMillis)}")
                addBooleanProperty("fresh", now - fix.epochMillis < FRESH_MS)
                addBooleanProperty("selected", radio.source == selectedSource)
                addBooleanProperty("emergency", isEmergency(radio.source, now))
            }
        }
        val tracks = shown.filter { it.track.size > 1 && it.source != historySource }.map { radio ->
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

    private fun fitAll(animate: Boolean) {
        val readyMap = map ?: return
        val pts = radios.map { LatLng(it.latest.latitude, it.latest.longitude) }
        fitPoints(readyMap, pts, animate) || run { toast(getString(R.string.map_count_none)); false }
    }

    private fun fitPoints(readyMap: MapLibreMap, pts: List<LatLng>, animate: Boolean): Boolean {
        val update = when {
            pts.isEmpty() -> return false
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
        return true
    }

    // ---- Emergencies ---------------------------------------------------------------------

    private fun checkEmergencies() {
        val newest = talkers.values.maxOfOrNull { it.emergencyMs } ?: 0L
        if (emergencySeenMs < 0L) {
            // Opening the map: only emergencies from the last couple of minutes still demand attention.
            emergencySeenMs = talkers.values.filter { System.currentTimeMillis() - it.emergencyMs > EMERGENCY_FRESH_MS }
                .maxOfOrNull { it.emergencyMs } ?: 0L
        }
        if (newest <= emergencySeenMs) return
        val entry = talkers.values.filter { it.emergencyMs > emergencySeenMs }.maxByOrNull { it.emergencyMs } ?: return
        emergencySeenMs = newest
        val tg = if (entry.talkgroup.isNotEmpty()) getString(R.string.map_tg_suffix, entry.talkgroup) else ""
        alertBanner.text = getString(R.string.map_emergency_banner, entry.source.toString(), tg)
        alertBanner.tag = entry.source
        alertBanner.visibility = View.VISIBLE
        handler.postDelayed({ if (!isDestroyed) alertBanner.visibility = View.GONE }, EMERGENCY_BANNER_MS)
        allRadios.firstOrNull { it.source == entry.source }?.let { select(it, fly = true) }
    }

    // ---- Track history -------------------------------------------------------------------

    private fun chooseHistory(radio: PositionLog.Radio) {
        val labels = HISTORY_RANGES.map { it.first }.toTypedArray()
        dialog()
            .setTitle(getString(R.string.map_history_title, radio.source.toString()))
            .setItems(labels) { _, which ->
                historySource = radio.source
                historyRangeMs = HISTORY_RANGES[which].second
                select(radio, fly = false)
                renderHistory(fit = true)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun renderHistory(fit: Boolean) {
        val id = historySource ?: return
        val since = if (historyRangeMs <= 0L) 0L else System.currentTimeMillis() - historyRangeMs
        val fixes = allFixes.filter { it.source == id && it.epochMillis >= since }.sortedBy { it.epochMillis }
        val s = style
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        if (s != null) {
            val line = if (fixes.size > 1) {
                listOf(Feature.fromGeometry(LineString.fromLngLats(fixes.map { Point.fromLngLat(it.longitude, it.latitude) })))
            } else {
                emptyList()
            }
            val pts = fixes.map { f ->
                Feature.fromGeometry(Point.fromLngLat(f.longitude, f.latitude)).apply {
                    addStringProperty("time", timeFmt.format(Date(f.epochMillis)))
                    addNumberProperty("epoch", f.epochMillis)
                    addNumberProperty("speed", f.speedKph)
                    addNumberProperty("heading", f.heading)
                }
            }
            s.getSourceAs<GeoJsonSource>(SRC_HISTORY_LINE)?.setGeoJson(FeatureCollection.fromFeatures(line))
            s.getSourceAs<GeoJsonSource>(SRC_HISTORY_PTS)?.setGeoJson(FeatureCollection.fromFeatures(pts))
        }
        val range = HISTORY_RANGES.firstOrNull { it.second == historyRangeMs }?.first ?: ""
        historyBar.text = getString(R.string.map_history_bar, id.toString(), range, fixes.size)
        historyBar.visibility = View.VISIBLE
        renderRadios()
        if (fit) map?.let { fitPoints(it, fixes.map { f -> LatLng(f.latitude, f.longitude) }, animate = true) }
    }

    private fun closeHistory() {
        historySource = null
        historyBar.visibility = View.GONE
        val empty = FeatureCollection.fromFeatures(emptyList())
        style?.getSourceAs<GeoJsonSource>(SRC_HISTORY_LINE)?.setGeoJson(empty)
        style?.getSourceAs<GeoJsonSource>(SRC_HISTORY_PTS)?.setGeoJson(empty)
        renderRadios()
    }

    // ---- Taps and dialogs ----------------------------------------------------------------

    private fun onMapClick(readyMap: MapLibreMap, point: LatLng): Boolean {
        val screen: PointF = readyMap.projection.toScreenLocation(point)
        readyMap.queryRenderedFeatures(screen, LAYER_UNITS, LAYER_LABELS).firstOrNull()?.getStringProperty("source")?.let { src ->
            allRadios.firstOrNull { it.source.toString() == src }?.let { select(it, fly = false); showRadio(it) }
            return true
        }
        readyMap.queryRenderedFeatures(screen, LAYER_HISTORY_PTS).firstOrNull()?.let { f ->
            showHistoryPoint(f)
            return true
        }
        val overlayLayers = OVERLAYS.filter { it.id in overlays }.flatMap { listOf("${it.id}-points", "${it.id}-fill", "${it.id}-line") }
        if (overlayLayers.isNotEmpty()) {
            val hit = readyMap.queryRenderedFeatures(screen, *overlayLayers.toTypedArray()).firstOrNull()
            if (hit != null) {
                showOverlayFeature(hit)
                return true
            }
        }
        return false
    }

    private fun showHistoryPoint(f: Feature) {
        val obj = runCatching { JSONObject(f.toJson()) }.getOrNull() ?: return
        val props = obj.optJSONObject("properties") ?: return
        val coords = obj.optJSONObject("geometry")?.optJSONArray("coordinates")
        val time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(props.optLong("epoch")))
        val body = buildString {
            append(time).append('\n')
            if (coords != null) append(String.format(Locale.US, "%.6f, %.6f", coords.optDouble(1), coords.optDouble(0))).append('\n')
            append(String.format(Locale.US, "%.1f km/h · %d°", props.optDouble("speed"), props.optInt("heading")))
        }
        dialog().setTitle(getString(R.string.map_history_point, historySource?.toString() ?: ""))
            .setMessage(body).setPositiveButton(android.R.string.ok, null).show()
    }

    private fun showOverlayFeature(f: Feature) {
        val obj = runCatching { JSONObject(f.toJson()) }.getOrNull() ?: return
        val props = obj.optJSONObject("properties") ?: JSONObject()
        val lines = ArrayList<String>()
        var link: String? = null
        val keys = props.keys()
        while (keys.hasNext() && lines.size < 16) {
            val k = keys.next()
            val v = props.opt(k)
            if (v == null || v == JSONObject.NULL) continue
            val text = v.toString().trim()
            if (text.isEmpty() || text.length > 200) continue
            if (k.equals("url", ignoreCase = true) && text.startsWith("http")) { link = text; continue }
            if (k.lowercase(Locale.US) in setOf("objectid", "fid", "shape__area", "shape__length", "globalid")) continue
            lines.add("$k: $text")
        }
        val b = dialog().setTitle(R.string.map_overlay_details)
            .setMessage(if (lines.isEmpty()) getString(R.string.map_overlay_no_details) else lines.joinToString("\n"))
            .setNegativeButton(android.R.string.ok, null)
        link?.let { url ->
            b.setPositiveButton(R.string.map_open_link) { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }
        }
        b.show()
    }

    private fun showUnits() {
        if (radios.isEmpty()) { toast(getString(R.string.map_count_none)); return }
        val now = System.currentTimeMillis()
        val items = radios.map { r ->
            val tg = tgOf(r.source)
            val tgPart = if (tg.isNotEmpty()) "  TG $tg" else ""
            val emergency = if (isEmergency(r.source, now)) "  ⚠" else ""
            "${r.source}$tgPart   ${ageText(now - r.latest.epochMillis)} ago$emergency"
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
        val talker = talkers[radio.source]
        val body = buildString {
            append("Position: ").append(coords).append('\n')
            append("Last report: ").append(time)
            append(" (").append(ageText(System.currentTimeMillis() - fix.epochMillis)).append(" ago)\n")
            append("Speed: ").append(String.format(Locale.US, "%.1f km/h", fix.speedKph))
            append("   Heading: ").append(fix.heading).append("°\n")
            append("Reports in window: ").append(radio.reports)
            if (talker != null && talker.talkgroup.isNotEmpty()) {
                append("\nLast talkgroup: ").append(talker.talkgroup)
                append(" (").append(ageText(System.currentTimeMillis() - talker.lastHeardMs)).append(" ago)")
            }
            if (talker != null && talker.emergencyMs > 0L) {
                append("\nLast emergency: ").append(ageText(System.currentTimeMillis() - talker.emergencyMs)).append(" ago")
            }
            if (distance != null) append("\nDistance from you: ").append(distance)
        }
        dialog()
            .setTitle(getString(R.string.map_radio_title, radio.source.toString()))
            .setMessage(body)
            .setPositiveButton(R.string.map_history_button) { _, _ -> chooseHistory(radio) }
            .setNeutralButton(R.string.map_copy) { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("position", coords))
                toast(getString(R.string.map_copied))
            }
            .setNegativeButton(R.string.map_open_in_maps) { _, _ ->
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("geo:${fix.latitude},${fix.longitude}?q=${fix.latitude},${fix.longitude}(Radio%20${radio.source})")))
                }.onFailure { toast(getString(R.string.map_no_maps_app)) }
            }
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
                    closeHistory()
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

    // ---- Layers menu ---------------------------------------------------------------------

    private fun showLayersMenu() {
        val items = arrayOf(
            getString(R.string.map_menu_base, BASE_MAP_NAMES[baseMap] ?: baseMap),
            getString(R.string.map_menu_overlays, overlays.size),
            getString(R.string.map_menu_radar, RADARS[radar]?.label ?: getString(R.string.map_off)),
            getString(R.string.map_menu_tg, tgFilter?.let { "TG $it" } ?: getString(R.string.map_all)),
            getString(R.string.map_menu_export),
            getString(R.string.map_menu_cache, cacheMb),
        )
        dialog().setTitle(R.string.map_menu_title).setItems(items) { _, which ->
            when (which) {
                0 -> chooseBaseMap()
                1 -> chooseOverlays()
                2 -> chooseRadar()
                3 -> chooseTalkgroup()
                4 -> chooseExport()
                5 -> chooseCache()
            }
        }.setNegativeButton(android.R.string.cancel, null).show()
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

    private fun chooseOverlays() {
        val labels = OVERLAYS.map { o -> if (o.liveMinZoom > 0.0) "${o.label} (zoom in)" else o.label }.toTypedArray()
        val checked = OVERLAYS.map { it.id in overlays }.toBooleanArray()
        dialog()
            .setTitle(R.string.map_overlays_title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                overlays.clear()
                OVERLAYS.forEachIndexed { i, o -> if (checked[i]) overlays.add(o.id) }
                savePreferences()
                applyStyle()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseRadar() {
        val keys = listOf("off") + RADARS.keys
        val names = keys.map { RADARS[it]?.label ?: getString(R.string.map_off) }.toTypedArray()
        dialog()
            .setTitle(R.string.map_radar_title)
            .setSingleChoiceItems(names, keys.indexOf(radar).coerceAtLeast(0)) { d, which ->
                d.dismiss()
                if (keys[which] != radar) {
                    radar = keys[which]
                    savePreferences()
                    applyStyle()
                }
            }
            .show()
    }

    private fun chooseTalkgroup() {
        // Talkgroups of radios that have a position in the time window, busiest first.
        val since = windowStart()
        val counts = allRadios.filter { it.latest.epochMillis >= since }
            .mapNotNull { r -> tgOf(r.source).takeIf { it.isNotEmpty() } }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
        if (counts.isEmpty()) {
            toast(getString(R.string.map_tg_none))
            return
        }
        val keys = listOf<String?>(null) + counts.map { it.key }
        val names = listOf(getString(R.string.map_all)) + counts.map { getString(R.string.map_tg_item, it.key, it.value) }
        dialog()
            .setTitle(R.string.map_tg_title)
            .setSingleChoiceItems(names.toTypedArray(), keys.indexOf(tgFilter).coerceAtLeast(0)) { d, which ->
                d.dismiss()
                tgFilter = keys[which]
                savePreferences()
                refreshPositions()
            }
            .show()
    }

    private fun chooseCache() {
        val sizes = listOf(256, 512, 1024, 2048)
        val names = sizes.map { if (it >= 1024) "${it / 1024} GB" else "$it MB" }.toTypedArray()
        dialog()
            .setTitle(R.string.map_cache_title)
            .setSingleChoiceItems(names, sizes.indexOf(cacheMb).coerceAtLeast(0)) { d, which ->
                d.dismiss()
                cacheMb = sizes[which]
                savePreferences()
                applyCacheSize()
                toast(getString(R.string.map_cache_set, names[which]))
            }
            .setNeutralButton(R.string.map_cache_clear) { _, _ ->
                OfflineManager.getInstance(this).clearAmbientCache(object : OfflineManager.FileSourceCallback {
                    override fun onSuccess() { handler.post { toast(getString(R.string.map_cache_cleared)) } }
                    override fun onError(message: String) { handler.post { toast(message) } }
                })
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Map tiles already viewed stay on the phone up to this size, so the map works with no signal. */
    private fun applyCacheSize() {
        runCatching {
            OfflineManager.getInstance(this).setMaximumAmbientCacheSize(cacheMb.toLong() * 1024L * 1024L,
                object : OfflineManager.FileSourceCallback {
                    override fun onSuccess() {}
                    override fun onError(message: String) {}
                })
        }
    }

    // ---- Export --------------------------------------------------------------------------

    private fun chooseExport() {
        val formats = MapExport.Format.values()
        dialog()
            .setTitle(R.string.map_export_title)
            .setItems(formats.map { it.label }.toTypedArray()) { _, which -> export(formats[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun export(format: MapExport.Format) {
        val since = windowStart()
        val wanted = radios.map { it.source }.toSet()
        val tracks = allFixes.filter { it.epochMillis >= since && it.source in wanted }
            .groupBy { it.source }
            .map { (source, list) -> MapExport.Track(source, tgOf(source), list.sortedBy { it.epochMillis }) }
            .sortedBy { it.source }
        if (tracks.isEmpty()) {
            toast(getString(R.string.map_count_none))
            return
        }
        val context = applicationContext
        runCatching {
            io.execute {
                val result = runCatching {
                    val dir = File(context.cacheDir, "exports")
                    dir.mkdirs()
                    dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 3600_000L }?.forEach { it.delete() }
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                    val file = File(dir, "sigsense-positions-$stamp.${format.extension}")
                    file.writeText(MapExport.render(format, tracks))
                    file
                }
                handler.post {
                    val file = result.getOrNull()
                    if (file == null) {
                        toast(getString(R.string.map_export_failed))
                        return@post
                    }
                    runCatching {
                        val uri = FileProvider.getUriForFile(this, "$packageName.diagnostics", file)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = format.mime
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, file.name)
                            clipData = ClipData.newRawUri(file.name, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(send, getString(R.string.map_export_title)))
                    }.onFailure { toast(getString(R.string.map_export_failed)) }
                }
            }
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
                val id = if (lead == null) null else TalkerLog.radioIdFrom(src)
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
        val who = srcText.trim().ifBlank { id?.toString() ?: "?" }
        val tg = tgText.trim().ifBlank { "?" }
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
        tgFilter = p.getString(KEY_TG, null)?.takeIf { it.isNotEmpty() }
        overlays.clear()
        p.getStringSet(KEY_OVERLAYS, emptySet())?.filter { id -> OVERLAYS.any { it.id == id } }?.let { overlays.addAll(it) }
        radar = p.getString(KEY_RADAR, "off")?.takeIf { it == "off" || it in RADARS } ?: "off"
        cacheMb = p.getInt(KEY_CACHE, 512)
    }

    private fun savePreferences() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_BASE, baseMap).putInt(KEY_WINDOW, windowIndex)
            .putBoolean(KEY_FOLLOW, followTalker).putString(KEY_TG, tgFilter ?: "")
            .putStringSet(KEY_OVERLAYS, LinkedHashSet(overlays)).putString(KEY_RADAR, radar)
            .putInt(KEY_CACHE, cacheMb).apply()
    }

    // ---- Lifecycle -----------------------------------------------------------------------

    override fun onStart() { super.onStart(); mapView.onStart() }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        polling = true
        for (r in listOf(poll, talkerPoll)) {
            handler.removeCallbacks(r)
            handler.post(r)
        }
        handler.removeCallbacks(overlayRefresh)
        handler.postDelayed(overlayRefresh, OVERLAY_REFRESH_MS)
        if (hasLocationPermission()) startLocationUpdates()
    }

    override fun onPause() {
        polling = false
        handler.removeCallbacks(poll)
        handler.removeCallbacks(talkerPoll)
        handler.removeCallbacks(overlayRefresh)
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
        netIo.shutdownNow()
        mapView.onDestroy()
        super.onDestroy()
    }

    private data class Overlay(
        val id: String,
        val label: String,
        val url: String,
        val color: String,
        val opacity: Double,
        /** 0 = loaded once from [url]; otherwise queried for the visible area from this zoom up. */
        val liveMinZoom: Double,
    )

    private data class Radar(val label: String, val workspace: String, val layer: String)

    companion object {
        private const val POLL_MS = 3000L
        private const val TALKER_POLL_MS = 1000L
        private const val OVERLAY_REFRESH_MS = 5 * 60_000L
        private const val FRESH_MS = 2 * 60 * 1000L
        private const val EMERGENCY_SHOW_MS = 30 * 60_000L
        private const val EMERGENCY_FRESH_MS = 2 * 60_000L
        private const val EMERGENCY_BANNER_MS = 60_000L
        private const val REQ_LOCATION = 4101
        private const val PREFS = "sigsense_map"
        private const val KEY_BASE = "base"
        private const val KEY_WINDOW = "window"
        private const val KEY_FOLLOW = "follow_talker"
        private const val KEY_TG = "tg_filter"
        private const val KEY_OVERLAYS = "overlays"
        private const val KEY_RADAR = "radar"
        private const val KEY_CACHE = "cache_mb"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        private const val SRC_UNITS = "sig-units"
        private const val SRC_TRACKS = "sig-tracks"
        private const val SRC_ME = "sig-me"
        private const val SRC_HISTORY_LINE = "sig-history-line"
        private const val SRC_HISTORY_PTS = "sig-history-pts"
        private const val LAYER_UNITS = "sig-units-dots"
        private const val LAYER_LABELS = "sig-units-labels"
        private const val LAYER_TRACKS = "sig-tracks-lines"
        private const val LAYER_ME = "sig-me-dot"
        private const val LAYER_SELECTED = "sig-units-selected"
        private const val LAYER_EMERGENCY = "sig-units-emergency"
        private const val LAYER_HISTORY_LINE = "sig-history-line"
        private const val LAYER_HISTORY_PTS = "sig-history-pts"
        private const val LAYER_HISTORY_LABELS = "sig-history-labels"

        private val BG = Color.parseColor("#0B0F14")
        private val BAR = Color.parseColor("#E60B0F14")
        private val PANEL = Color.parseColor("#E61A2129")
        private val STROKE = Color.parseColor("#33FFFFFF")
        private val TEXT = Color.parseColor("#F2F5F8")
        private val MUTED = Color.parseColor("#9AA5B1")
        private val TALK_BG = Color.parseColor("#E6B71C1C")
        private val ALERT_BG = Color.parseColor("#F2D50000")
        private val HISTORY_BG = Color.parseColor("#E64527A0")
        private val FOLLOW_ON = Color.parseColor("#E6006064")
        private val FOLLOW_STROKE = Color.parseColor("#00E5FF")

        /** Label and how far back it reaches (0 = everything kept in the file). */
        private val WINDOWS = listOf(
            "30 min" to 30 * 60 * 1000L,
            "1 hr" to 60 * 60 * 1000L,
            "6 hr" to 6 * 60 * 60 * 1000L,
            "24 hr" to 24 * 60 * 60 * 1000L,
            "All" to 0L,
        )

        private val HISTORY_RANGES = listOf(
            "Last hour" to 60 * 60 * 1000L,
            "Last 6 hours" to 6 * 60 * 60 * 1000L,
            "Last 24 hours" to 24 * 60 * 60 * 1000L,
            "Everything saved" to 0L,
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

        private val RADARS = linkedMapOf(
            "pah" to Radar("Paducah (KPAH)", "kpah", "kpah_sr_bref"),
            "meg" to Radar("Memphis (KNQA)", "knqa", "knqa_sr_bref"),
            "conus" to Radar("US composite", "conus", "conus_bref_qcd"),
        )

        private val OVERLAYS = listOf(
            Overlay("warnings", "Weather warnings",
                "https://mapservices.weather.noaa.gov/eventdriven/rest/services/WWA/watch_warn_adv/MapServer/1/query?f=geojson&where=prod_type%20IN%20(%27Tornado%20Warning%27,%27Severe%20Thunderstorm%20Warning%27,%27Flash%20Flood%20Warning%27,%27Flood%20Warning%27)&outFields=prod_type,wfo,event,issuance,expiration,url&returnGeometry=true&outSR=4326",
                "#ffffff", 0.18, 0.0),
            Overlay("tfr", "FAA temporary flight restrictions",
                "https://tfr.faa.gov/geoserver/TFR/ows?service=WFS&version=1.1.0&request=GetFeature&typeName=TFR:V_TFR_LOC&maxFeatures=300&outputFormat=application/json&srsname=EPSG:4326",
                "#ef5350", 0.16, 0.0),
            Overlay("uas", "Drone (UAS) security restrictions",
                "https://services6.arcgis.com/ssFJjBXIUyZDrSYZ/arcgis/rest/services/Part_Time_National_Security_UAS_Flight_Restrictions/FeatureServer/0/query?f=geojson&where=1%3D1&outFields=*&returnGeometry=true&outSR=4326",
                "#ff5252", 0.16, 0.0),
            Overlay("dod", "DoD areas",
                "https://services6.arcgis.com/ssFJjBXIUyZDrSYZ/arcgis/rest/services/DoD_Mar_13/FeatureServer/0/query",
                "#ffa726", 0.16, 6.0),
            Overlay("obstacles", "Obstacles / towers",
                "https://services6.arcgis.com/ssFJjBXIUyZDrSYZ/arcgis/rest/services/Digital_Obstacle_File/FeatureServer/0/query",
                "#ffee58", 0.5, 10.0),
            Overlay("address-tn", "Tennessee 911 addresses",
                "https://services1.arcgis.com/YuVBSS7Y1of2Qud1/arcgis/rest/services/Tennessee_NG911_Address_Points/FeatureServer/0/query",
                "#42a5f5", 0.0, 16.0),
            Overlay("tn-parcels", "Tennessee parcels",
                "https://services1.arcgis.com/YuVBSS7Y1of2Qud1/arcgis/rest/services/Tennessee_Property_Boundaries_Public_Use/FeatureServer/0/query",
                "#2196f3", 0.04, 15.0),
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
