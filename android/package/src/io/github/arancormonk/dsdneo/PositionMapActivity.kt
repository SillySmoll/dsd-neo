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
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
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
import org.maplibre.android.style.layers.Layer
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
    private lateinit var playerBar: TextView
    /** Radio ID labels, drawn as Android views over the map so they never depend on map fonts. */
    private lateinit var labelLayer: FrameLayout
    private val labelViews = HashMap<Long, TextView>()
    private var labelRadios: List<PositionLog.Radio> = emptyList()
    private lateinit var replayPanel: LinearLayout
    private lateinit var replaySeek: SeekBar
    private lateinit var replayTimeText: TextView
    private lateinit var replayPlayButton: Button
    private lateinit var replaySpeedButton: Button

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
    /** Names the user gave radios ([RadioNames]). */
    private var names: Map<Long, String> = emptyMap()

    /** Timeline replay: the moment shown, or null when the map is live. */
    private var replayTime: Long? = null
    private var replayStart = 0L
    private var replayEnd = 0L
    private var replayPlaying = false
    private var replaySpeedIndex = 1
    private var replayBySource: Map<Long, List<PositionLog.Fix>> = emptyMap()

    /** Instant replay of recent transmissions. */
    private var player: MediaPlayer? = null
    private val playQueue = ArrayDeque<RecentCalls.Call>()
    private var selectedSource: Long? = null
    private var fittedOnce = false
    private var polling = false

    private var baseMap = "osm"
    private var windowIndex = 2
    private var tgFilter: String? = null
    /** Report types shown: LRRP, USBD (LIP) and other GPS. */
    private val kindsShown = linkedSetOf(PositionLog.KIND_LRRP, PositionLog.KIND_USBD, PositionLog.KIND_GPS)
    /** Radio ID filter as typed ("30500-30600, 61121"); empty shows every radio. */
    private var idFilterText = ""
    private var idRanges: List<LongRange> = emptyList()
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

    /** What went wrong building the map, for Layers → Map diagnostics. */
    private val mapProblems = ArrayList<String>()
    private var styleLoads = 0
    private var lastDotsSent = 0
    private var lastRenderSkipped = ""

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

    private val replayTick = object : Runnable {
        override fun run() {
            val t = replayTime ?: return
            if (!replayPlaying) return
            val next = t + REPLAY_TICK_MS * REPLAY_SPEEDS[replaySpeedIndex].second
            if (next >= replayEnd) {
                setReplayTime(replayEnd)
                setReplayPlaying(false)
                return
            }
            setReplayTime(next)
            handler.postDelayed(this, REPLAY_TICK_MS)
        }
    }

    private val playbackLimit = Runnable { playNext() }

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
        labelLayer = FrameLayout(this).apply { clipChildren = true }
        root.addView(labelLayer, FrameLayout.LayoutParams(MATCH, MATCH))

        topBar = buildTopBar()
        alertBanner = banner(ALERT_BG).apply {
            setOnClickListener { v ->
                (v.tag as? Long)?.let { id -> allRadios.firstOrNull { it.source == id }?.let { select(it, true); showRadio(it) } }
                visibility = View.GONE
            }
        }
        talkBanner = banner(TALK_BG).apply { setOnClickListener { selectedRadio()?.let { showRadio(it) } } }
        historyBar = banner(HISTORY_BG).apply { setOnClickListener { closeHistory() } }
        playerBar = banner(PLAYER_BG).apply { setOnClickListener { stopPlayback() } }
        val topColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        topColumn.addView(topBar, LinearLayout.LayoutParams(MATCH, WRAP))
        for (b in listOf(alertBanner, talkBanner, playerBar, historyBar)) {
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
        replayPanel = buildReplayPanel()
        val bottomColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bottomColumn.addView(buildLegend(), LinearLayout.LayoutParams(WRAP, WRAP).apply {
            leftMargin = dp(10); bottomMargin = dp(6)
        })
        bottomColumn.addView(replayPanel, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            leftMargin = dp(10); rightMargin = dp(10); bottomMargin = dp(6)
        })
        bottomColumn.addView(bottomBar, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(bottomColumn, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

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
            readyMap.addOnCameraIdleListener { refreshLiveOverlays(); positionLabels() }
            readyMap.addOnCameraMoveListener { positionLabels() }
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
        bar.addView(pill("Fit") { fitAll(animate = true) }, lp())
        bar.addView(pill("Me") { centerOnMe() }, lp())
        followButton = pill(followLabel()) { toggleFollow() }
        bar.addView(followButton, lp())
        bar.addView(pill("Replay") { showRecent() }, lp())
        applyFollowLook()
        return bar
    }

    /** The colour key: what each dot colour means. */
    private fun buildLegend(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = panel(PANEL, stroke = STROKE)
            setPadding(dp(10), dp(5), dp(10), dp(5))
        }
        val labels = listOf(getString(R.string.map_key_new), getString(R.string.map_key_stale), getString(R.string.map_key_old))
        labels.forEachIndexed { i, label ->
            row.addView(TextView(this).apply {
                text = "●"
                setTextColor(DOT_COLORS[i])
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { if (i > 0) leftMargin = dp(10) })
            row.addView(TextView(this).apply {
                text = label
                setTextColor(TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(4), 0, 0, 0)
            })
        }
        return row
    }

    private fun buildReplayPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panel(REPLAY_BG, stroke = STROKE)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            visibility = View.GONE
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        replayTimeText = TextView(this).apply {
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
        }
        top.addView(replayTimeText, LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(pill("✕") { stopTimeline() })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        replayPlayButton = pill("▶") { setReplayPlaying(!replayPlaying) }
        row.addView(replayPlayButton)
        replaySeek = SeekBar(this).apply {
            max = REPLAY_STEPS
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) setReplayTime(replayStart + (replayEnd - replayStart) * progress / REPLAY_STEPS, fromSeek = true)
                }
                override fun onStartTrackingTouch(bar: SeekBar) { setReplayPlaying(false) }
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        }
        row.addView(replaySeek, LinearLayout.LayoutParams(0, WRAP, 1f))
        replaySpeedButton = pill(REPLAY_SPEEDS[replaySpeedIndex].first) {
            replaySpeedIndex = (replaySpeedIndex + 1) % REPLAY_SPEEDS.size
            replaySpeedButton.text = REPLAY_SPEEDS[replaySpeedIndex].first
        }
        row.addView(replaySpeedButton)
        panel.addView(top)
        panel.addView(row)
        return panel
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
            styleLoads++
            try {
                installLayers(loaded)
            } catch (e: Throwable) {
                mapProblems += "setup: ${e.javaClass.simpleName}: ${e.message}"
            }
            if (mapProblems.isNotEmpty()) toast(getString(R.string.map_problem_toast))
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

    /** Adds [layer]; on failure records why and, when given, adds [fallback] instead. */
    private fun addLayerSafely(s: Style, layer: Layer, fallback: (() -> Layer)? = null) {
        try {
            s.addLayer(layer)
        } catch (e: Throwable) {
            mapProblems += "layer ${layer.id}: ${e.javaClass.simpleName}: ${e.message}"
            if (fallback != null) {
                try {
                    s.addLayer(fallback())
                    mapProblems += "layer ${layer.id}: plain fallback added"
                } catch (e2: Throwable) {
                    mapProblems += "fallback ${layer.id}: ${e2.javaClass.simpleName}: ${e2.message}"
                }
            }
        }
    }

    private fun installLayers(s: Style) {
        val empty = FeatureCollection.fromFeatures(emptyList())
        for (id in listOf(SRC_TRACKS, SRC_UNITS, SRC_ME, SRC_HISTORY_LINE, SRC_HISTORY_PTS) + DOT_SOURCES +
            listOf(SRC_SELECTED, SRC_EMERGENCY)) {
            try {
                if (s.getSource(id) == null) s.addSource(GeoJsonSource(id, empty))
            } catch (e: Throwable) {
                mapProblems += "source $id: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
        addLayerSafely(s, LineLayer(LAYER_TRACKS, SRC_TRACKS).withProperties(
            PropertyFactory.lineColor(Color.parseColor("#4FC3F7")),
            PropertyFactory.lineWidth(2.5f),
            PropertyFactory.lineOpacity(0.7f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ))
        addLayerSafely(s, LineLayer(LAYER_HISTORY_LINE, SRC_HISTORY_LINE).withProperties(
            PropertyFactory.lineColor(Color.parseColor("#B388FF")),
            PropertyFactory.lineWidth(3.5f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ))
        addLayerSafely(s, CircleLayer(LAYER_HISTORY_PTS, SRC_HISTORY_PTS).withProperties(
            PropertyFactory.circleRadius(4.5f),
            PropertyFactory.circleColor(Color.WHITE),
            PropertyFactory.circleStrokeColor(Color.parseColor("#7C4DFF")),
            PropertyFactory.circleStrokeWidth(2f),
        ))
        addLayerSafely(s, SymbolLayer(LAYER_HISTORY_LABELS, SRC_HISTORY_PTS).withProperties(
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
        addLayerSafely(s, CircleLayer(LAYER_ME, SRC_ME).withProperties(
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleColor(Color.parseColor("#2979FF")),
            PropertyFactory.circleStrokeColor(Color.WHITE),
            PropertyFactory.circleStrokeWidth(2.5f),
        ))
        // Rings and dots each draw from their own source with fixed colors, so how a dot looks never
        // depends on per-feature properties reaching the renderer (they did not on one device).
        addLayerSafely(s, CircleLayer(LAYER_EMERGENCY, SRC_EMERGENCY).withProperties(
            PropertyFactory.circleRadius(20f),
            PropertyFactory.circleColor(Color.parseColor("#55FF1744")),
            PropertyFactory.circleStrokeColor(Color.parseColor("#FF1744")),
            PropertyFactory.circleStrokeWidth(3f),
        ))
        addLayerSafely(s, CircleLayer(LAYER_SELECTED, SRC_SELECTED).withProperties(
            PropertyFactory.circleRadius(16f),
            PropertyFactory.circleColor(Color.parseColor("#3300E5FF")),
            PropertyFactory.circleStrokeColor(Color.parseColor("#00E5FF")),
            PropertyFactory.circleStrokeWidth(3f),
        ))
        // Oldest first, so newer dots sit on top where radios overlap.
        for (i in DOT_SOURCES.indices.reversed()) {
            addLayerSafely(s, CircleLayer(DOT_LAYERS[i], DOT_SOURCES[i]).withProperties(
                PropertyFactory.circleRadius(8f),
                PropertyFactory.circleColor(DOT_COLORS[i]),
                PropertyFactory.circleStrokeColor(Color.parseColor("#07111F")),
                PropertyFactory.circleStrokeWidth(2f),
            ))
        }
        if (DOT_LAYERS.any { s.getLayer(it) == null }) mapProblems += "a dots layer is missing after setup"
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
        c.setRequestProperty("User-Agent", "WTDW Tracker (DSD-neo)")
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
        val kinds = kindsShown.toSet()
        val ranges = idRanges
        runCatching {
            io.execute {
                val every = PositionLog.readFixes(context)
                // The map, list, replay and exports show only the chosen types and IDs; Find searches all.
                val fixes = every.filter { it.kind in kinds && idAllowed(ranges, it.source) }
                val loaded = PositionLog.units(fixes, since)
                val all = PositionLog.units(every, 0L)
                val known = TalkerLog.snapshot(context)
                val named = RadioNames.all(context)
                handler.post { names = named; showPositions(fixes, loaded, all, known) }
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
        if (replayTime != null) indexReplay()
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

    /** Draws the filtered radios (or, in timeline replay, where they were then), plus the selected one. */
    private fun renderRadios() {
        val loaded = radios
        val filter = tgFilter
        val t = replayTime
        countText.text = buildString {
            if (t != null) {
                append(getString(R.string.map_replay_count, clock(t)))
            } else {
                append(when (loaded.size) {
                    0 -> getString(R.string.map_count_none)
                    1 -> getString(R.string.map_count_one)
                    else -> getString(R.string.map_count_many, loaded.size)
                })
            }
            if (filter != null) append(" · TG ").append(filter)
            if (idRanges.isNotEmpty()) append(" · IDs ").append(idFilterText)
            if (kindsShown.size < 3) append(" · ").append(kindsShown.joinToString("+") { kindLabel(it) })
        }
        emptyBanner.visibility = if (t == null && loaded.isEmpty() && filter == null) View.VISIBLE else View.GONE
        val s = style
        if (s == null) {
            lastRenderSkipped = "style not loaded"
            return
        }
        lastRenderSkipped = ""
        val now = t ?: System.currentTimeMillis()
        val shown = if (t != null) {
            radiosAt(t)
        } else {
            val selected = selectedRadio()
            if (selected != null && loaded.none { it.source == selected.source }) loaded + selected else loaded
        }
        val buckets = List(DOT_SOURCES.size) { ArrayList<JSONObject>() }
        val selectedDots = ArrayList<JSONObject>()
        val emergencyDots = ArrayList<JSONObject>()
        val points = shown.map { radio ->
            val fix = radio.latest
            val tg = tgOf(radio.source)
            val age = now - fix.epochMillis
            val tgPart = if (tg.isNotEmpty()) " · TG $tg" else ""
            val feature = pointJson(fix.longitude, fix.latitude, JSONObject()
                .put("source", radio.source.toString())
                .put("label", "${displayName(radio.source)}$tgPart · ${ageText(age)}"))
            buckets[ageBucket(age)].add(feature)
            if (radio.source == selectedSource) selectedDots.add(feature)
            if (t == null && isEmergency(radio.source, now)) emergencyDots.add(feature)
            feature
        }
        val tracks = shown.filter { it.track.size > 1 && it.source != historySource }.map { radio ->
            lineJson(radio.track.map { it.longitude to it.latitude })
        }
        try {
            for (i in DOT_SOURCES.indices) {
                val src = s.getSourceAs<GeoJsonSource>(DOT_SOURCES[i])
                if (src == null) lastRenderSkipped = "dots source ${DOT_SOURCES[i]} missing" else src.setGeoJson(collectionJson(buckets[i]))
            }
            s.getSourceAs<GeoJsonSource>(SRC_UNITS)?.setGeoJson(collectionJson(points))
            s.getSourceAs<GeoJsonSource>(SRC_SELECTED)?.setGeoJson(collectionJson(selectedDots))
            s.getSourceAs<GeoJsonSource>(SRC_EMERGENCY)?.setGeoJson(collectionJson(emergencyDots))
            lastDotsSent = points.size
        } catch (e: Throwable) {
            lastRenderSkipped = "setGeoJson: ${e.javaClass.simpleName}: ${e.message}"
        }
        runCatching { s.getSourceAs<GeoJsonSource>(SRC_TRACKS)?.setGeoJson(collectionJson(tracks)) }
        updateLabels(shown)
    }

    // ---- Radio ID labels -----------------------------------------------------------------

    /** One label per radio: the ID in bold, and under it the name the user gave it, if any. */
    private fun updateLabels(shown: List<PositionLog.Radio>) {
        labelRadios = shown
        val keep = shown.map { it.source }.toSet()
        val gone = labelViews.keys.filter { it !in keep }
        for (id in gone) labelViews.remove(id)?.let { labelLayer.removeView(it) }
        for (radio in shown) {
            val id = radio.source
            val view = labelViews.getOrPut(id) {
                TextView(this).apply {
                    setTextColor(TEXT)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setShadowLayer(4f, 0f, 1f, Color.BLACK)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#B307111F"))
                        cornerRadius = dp(6).toFloat()
                    }
                    setPadding(dp(5), dp(1), dp(5), dp(2))
                    includeFontPadding = false
                    setOnClickListener {
                        allRadios.firstOrNull { it.source == id }?.let { r -> select(r, fly = false); showRadio(r) }
                    }
                }.also { labelLayer.addView(it, FrameLayout.LayoutParams(WRAP, WRAP)) }
            }
            val text = SpannableStringBuilder(id.toString())
            text.setSpan(StyleSpan(Typeface.BOLD), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            names[id]?.let { alias ->
                val start = text.length + 1
                text.append('\n').append(alias)
                text.setSpan(RelativeSizeSpan(0.9f), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (view.text.toString() != text.toString()) view.text = text
        }
        positionLabels()
        // New labels have no size until laid out; place them again once they do.
        labelLayer.post { positionLabels() }
    }

    /** Pins each label just right of its dot; called whenever the camera moves. */
    private fun positionLabels() {
        val readyMap = map ?: return
        if (!::labelLayer.isInitialized) return
        val w = labelLayer.width
        val h = labelLayer.height
        for (radio in labelRadios) {
            val view = labelViews[radio.source] ?: continue
            val p = runCatching { readyMap.projection.toScreenLocation(LatLng(radio.latest.latitude, radio.latest.longitude)) }.getOrNull()
            if (p == null || p.x < -dp(40) || p.y < -dp(40) || p.x > w + dp(40) || p.y > h + dp(40)) {
                view.visibility = View.INVISIBLE
                continue
            }
            view.visibility = View.VISIBLE
            val vh = if (view.height > 0) view.height else dp(18)
            view.translationX = p.x + dp(11)
            view.translationY = p.y - vh / 2f
        }
    }

    /** 0 = green (under 3 min), 1 = yellow (3–5 min), 2 = red (older than 5 min). */
    private fun ageBucket(ageMs: Long): Int = when {
        ageMs < GREEN_MS -> 0
        ageMs <= YELLOW_MS -> 1
        else -> 2
    }

    // GeoJSON goes to the map as text the engine parses itself; built through the Java Feature
    // objects, properties did not reach the renderer on a device.
    private fun pointJson(lon: Double, lat: Double, props: JSONObject): JSONObject = JSONObject()
        .put("type", "Feature")
        .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(lon).put(lat)))
        .put("properties", props)

    private fun lineJson(coords: List<Pair<Double, Double>>): JSONObject {
        val arr = JSONArray()
        coords.forEach { (lon, lat) -> arr.put(JSONArray().put(lon).put(lat)) }
        return JSONObject().put("type", "Feature")
            .put("geometry", JSONObject().put("type", "LineString").put("coordinates", arr))
            .put("properties", JSONObject())
    }

    private fun collectionJson(features: List<JSONObject>): String {
        val arr = JSONArray()
        features.forEach { arr.put(it) }
        return JSONObject().put("type", "FeatureCollection").put("features", arr).toString()
    }

    private fun showDiagnostics() {
        val s = style
        val layerIds = listOf(LAYER_TRACKS, LAYER_HISTORY_LINE, LAYER_HISTORY_PTS, LAYER_HISTORY_LABELS, LAYER_ME,
            LAYER_EMERGENCY, LAYER_SELECTED) + DOT_LAYERS
        val body = buildString {
            append("Style loaded: ").append(if (s != null) "yes ($styleLoads)" else "no").append('\n')
            append("Radios in window: ").append(radios.size).append(" · all: ").append(allRadios.size).append('\n')
            append("Dots sent to map: ").append(lastDotsSent).append('\n')
            if (lastRenderSkipped.isNotEmpty()) append("Last draw skipped: ").append(lastRenderSkipped).append('\n')
            map?.cameraPosition?.let { c ->
                append(String.format(Locale.US, "Camera: %.4f, %.4f z%.1f", c.target?.latitude ?: 0.0, c.target?.longitude ?: 0.0, c.zoom)).append('\n')
            }
            radios.firstOrNull()?.let { r ->
                append(String.format(Locale.US, "First radio: %d at %.5f, %.5f", r.source, r.latest.latitude, r.latest.longitude)).append('\n')
            }
            if (s != null) {
                append("Layers: ")
                append(layerIds.joinToString(", ") { id -> id.removePrefix("sig-") + if (s.getLayer(id) != null) " ✓" else " ✗" })
                append('\n')
                append("Dots source: ").append(if (s.getSource(SRC_UNITS) != null) "✓" else "✗").append('\n')
            }
            append("Problems: ").append(if (mapProblems.isEmpty()) "none" else mapProblems.joinToString("\n"))
        }
        dialog().setTitle(R.string.map_diagnostics_title).setMessage(body)
            .setPositiveButton(R.string.map_copy) { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("map diagnostics", body))
                toast(getString(R.string.map_copied))
            }
            .setNegativeButton(R.string.map_close, null).show()
    }

    private fun displayName(id: Long): String = names[id] ?: id.toString()

    private fun fullName(id: Long): String = names[id]?.let { "$it ($id)" } ?: id.toString()

    private fun clock(ms: Long): String = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(ms))

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
        alertBanner.text = getString(R.string.map_emergency_banner, fullName(entry.source), tg)
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
            val line = if (fixes.size > 1) listOf(lineJson(fixes.map { it.longitude to it.latitude })) else emptyList()
            val pts = fixes.map { f ->
                pointJson(f.longitude, f.latitude, JSONObject()
                    .put("time", timeFmt.format(Date(f.epochMillis)))
                    .put("epoch", f.epochMillis)
                    .put("speed", f.speedKph)
                    .put("heading", f.heading))
            }
            s.getSourceAs<GeoJsonSource>(SRC_HISTORY_LINE)?.setGeoJson(collectionJson(line))
            s.getSourceAs<GeoJsonSource>(SRC_HISTORY_PTS)?.setGeoJson(collectionJson(pts))
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
        val dotHits = readyMap.queryRenderedFeatures(screen, *DOT_LAYERS.toTypedArray())
        if (dotHits.isNotEmpty()) {
            // By the feature's own ID when it carries one, else the radio drawn nearest the tap.
            val src = runCatching { dotHits.first().getStringProperty("source") }.getOrNull()
            val radio = allRadios.firstOrNull { it.source.toString() == src } ?: nearestRadio(readyMap, screen)
            radio?.let { select(it, fly = false); showRadio(it) }
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

    private fun nearestRadio(readyMap: MapLibreMap, screen: PointF): PositionLog.Radio? {
        val candidates = if (replayTime != null) radiosAt(replayTime!!) else radios
        return candidates.minByOrNull { r ->
            val p = readyMap.projection.toScreenLocation(LatLng(r.latest.latitude, r.latest.longitude))
            (p.x - screen.x) * (p.x - screen.x) + (p.y - screen.y) * (p.y - screen.y)
        }
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
            "${fullName(r.source)}$tgPart   ${ageText(now - r.latest.epochMillis)} ago$emergency"
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
            append("Report type: ").append(kindLabel(fix.kind)).append('\n')
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
            .setTitle(getString(R.string.map_radio_title, fullName(radio.source)))
            .setMessage(body)
            .setPositiveButton(R.string.map_history_button) { _, _ -> chooseHistory(radio) }
            .setNeutralButton(R.string.map_more) { _, _ ->
                val actions = arrayOf(
                    getString(if (names.containsKey(radio.source)) R.string.map_rename else R.string.map_name_radio),
                    getString(R.string.map_copy),
                    getString(R.string.map_open_in_maps),
                )
                dialog().setTitle(fullName(radio.source)).setItems(actions) { _, which ->
                    when (which) {
                        0 -> editName(radio.source)
                        1 -> {
                            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("position", coords))
                            toast(getString(R.string.map_copied))
                        }
                        2 -> runCatching {
                            startActivity(Intent(Intent.ACTION_VIEW,
                                Uri.parse("geo:${fix.latitude},${fix.longitude}?q=${fix.latitude},${fix.longitude}(${Uri.encode(displayName(radio.source))})")))
                        }.onFailure { toast(getString(R.string.map_no_maps_app)) }
                    }
                }.show()
            }
            .setNegativeButton(R.string.map_close, null)
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
            getString(R.string.map_menu_types, if (kindsShown.size == 3) getString(R.string.map_all)
                else if (kindsShown.isEmpty()) getString(R.string.map_none) else kindsShown.joinToString(", ") { kindLabel(it) }),
            getString(R.string.map_menu_ids, if (idRanges.isEmpty()) getString(R.string.map_all) else idFilterText),
            getString(R.string.map_menu_tg, tgFilter?.let { "TG $it" } ?: getString(R.string.map_all)),
            getString(R.string.map_menu_names, names.size),
            getString(R.string.map_menu_timeline),
            getString(R.string.map_menu_export),
            getString(R.string.map_menu_cache, cacheMb),
            getString(R.string.map_menu_diagnostics),
        )
        dialog().setTitle(R.string.map_menu_title).setItems(items) { _, which ->
            when (which) {
                0 -> chooseBaseMap()
                1 -> chooseOverlays()
                2 -> chooseRadar()
                3 -> chooseKinds()
                4 -> chooseIdFilter()
                5 -> chooseTalkgroup()
                6 -> showNames()
                7 -> startTimeline()
                8 -> chooseExport()
                9 -> chooseCache()
                10 -> showDiagnostics()
            }
        }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun kindLabel(kind: String): String = when (kind) {
        PositionLog.KIND_LRRP -> "LRRP"
        PositionLog.KIND_USBD -> "USBD"
        else -> "GPS"
    }

    private fun chooseKinds() {
        val kinds = listOf(PositionLog.KIND_LRRP, PositionLog.KIND_USBD, PositionLog.KIND_GPS)
        val labels = arrayOf(getString(R.string.map_type_lrrp), getString(R.string.map_type_usbd), getString(R.string.map_type_gps))
        val checked = kinds.map { it in kindsShown }.toBooleanArray()
        dialog()
            .setTitle(R.string.map_types_title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                kindsShown.clear()
                kinds.forEachIndexed { i, k -> if (checked[i]) kindsShown.add(k) }
                savePreferences()
                refreshPositions()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseIdFilter() {
        val input = field(getString(R.string.map_ids_hint), idFilterText, numeric = false)
        input.inputType = InputType.TYPE_CLASS_TEXT
        val help = TextView(this).apply {
            text = getString(R.string.map_ids_help)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(8), 0, 0)
        }
        val b = dialog()
            .setTitle(R.string.map_ids_title)
            .setView(boxOf(input, help))
            .setPositiveButton(android.R.string.ok) { _, _ -> applyIdFilter(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
        if (idRanges.isNotEmpty()) b.setNeutralButton(R.string.map_ids_clear) { _, _ -> applyIdFilter("") }
        b.show()
        input.requestFocus()
    }

    private fun applyIdFilter(text: String) {
        val ranges = parseIdRanges(text)
        if (text.isNotBlank() && ranges.isEmpty()) {
            toast(getString(R.string.map_ids_invalid))
            return
        }
        idRanges = ranges
        idFilterText = if (ranges.isEmpty()) "" else text.trim().replace(Regex("\\s*,\\s*"), ", ")
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
                    val file = File(dir, "wtdw-positions-$stamp.${format.extension}")
                    file.writeText(MapExport.render(format, tracks))
                    file
                }
                handler.post {
                    val file = result.getOrNull()
                    if (file == null) {
                        toast(getString(R.string.map_export_failed))
                        return@post
                    }
                    shareFile(file, format.mime)
                }
            }
        }
    }

    private fun shareFile(file: File, mime: String) {
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.diagnostics", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                clipData = ClipData.newRawUri(file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, file.name))
        }.onFailure { toast(getString(R.string.map_export_failed)) }
    }

    private fun exportsDir(): File = File(cacheDir, "exports").apply { mkdirs() }

    // ---- Radio names ---------------------------------------------------------------------

    private fun showNames() {
        val all = RadioNames.all(this).entries.sortedBy { it.value.lowercase(Locale.getDefault()) }
        val items = all.map { "${it.value}  (${it.key})" }.toTypedArray()
        val b = dialog().setTitle(getString(R.string.map_names_title, all.size))
        if (items.isEmpty()) b.setMessage(R.string.map_names_empty) else b.setItems(items) { _, which -> editName(all[which].key) }
        b.setPositiveButton(R.string.map_names_add) { _, _ -> addName() }
            .setNeutralButton(R.string.map_names_import_export) { _, _ -> namesImportExport() }
            .setNegativeButton(R.string.map_close, null)
            .show()
    }

    private fun field(hint: String, value: String, numeric: Boolean): EditText = EditText(this).apply {
        this.hint = hint
        setText(value)
        setSingleLine()
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
    }

    private fun boxOf(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), 0)
        views.forEach { addView(it) }
    }

    private fun addName() {
        val idField = field(getString(R.string.map_names_id_hint), "", numeric = true)
        val nameField = field(getString(R.string.map_names_name_hint), "", numeric = false)
        dialog().setTitle(R.string.map_names_add).setView(boxOf(idField, nameField))
            .setPositiveButton(R.string.map_save) { _, _ ->
                val id = idField.text.toString().trim().toLongOrNull()
                val name = nameField.text.toString()
                if (id == null || id <= 0L || RadioNames.clean(name).isEmpty()) {
                    toast(getString(R.string.map_names_invalid))
                } else {
                    RadioNames.set(this, id, name)
                    namesChanged()
                }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun editName(id: Long) {
        val current = RadioNames.get(this, id).orEmpty()
        val nameField = field(getString(R.string.map_names_name_hint), current, numeric = false)
        val b = dialog().setTitle(getString(R.string.map_names_edit_title, id.toString())).setView(boxOf(nameField))
            .setPositiveButton(R.string.map_save) { _, _ ->
                RadioNames.set(this, id, nameField.text.toString())
                namesChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (current.isNotEmpty()) {
            b.setNeutralButton(R.string.map_names_delete) { _, _ ->
                RadioNames.remove(this, id)
                namesChanged()
            }
        }
        b.show()
        nameField.requestFocus()
    }

    private fun namesChanged() {
        names = RadioNames.all(this)
        renderRadios()
        toast(getString(R.string.map_names_saved))
    }

    private fun namesImportExport() {
        val actions = arrayOf(getString(R.string.map_names_import), getString(R.string.map_names_export))
        dialog().setTitle(R.string.map_names_import_export).setItems(actions) { _, which ->
            if (which == 0) {
                val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/vnd.ms-excel"))
                }
                runCatching { startActivityForResult(pick, REQ_IMPORT_NAMES) }.onFailure { toast(getString(R.string.map_names_import_failed)) }
            } else {
                val file = File(exportsDir(), "wtdw-radio-names.csv")
                runCatching { file.writeText(RadioNames.exportCsv(this)) }
                    .onSuccess { shareFile(file, "text/csv") }
                    .onFailure { toast(getString(R.string.map_export_failed)) }
            }
        }.setNegativeButton(android.R.string.cancel, null).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMPORT_NAMES || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val context = applicationContext
        runCatching {
            io.execute {
                val count = runCatching {
                    val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                    RadioNames.importCsv(context, text)
                }.getOrDefault(-1)
                handler.post {
                    if (count < 0) {
                        toast(getString(R.string.map_names_import_failed))
                    } else {
                        names = RadioNames.all(this)
                        renderRadios()
                        toast(getString(R.string.map_names_imported, count))
                    }
                }
            }
        }
    }

    // ---- Timeline replay -----------------------------------------------------------------

    private fun startTimeline() {
        val since = windowStart()
        val fixes = allFixes.filter { it.epochMillis >= since }
        if (fixes.isEmpty()) {
            toast(getString(R.string.map_count_none))
            return
        }
        closeHistory()
        talkBanner.visibility = View.GONE
        replayStart = fixes.minOf { it.epochMillis }
        replayEnd = maxOf(System.currentTimeMillis(), fixes.maxOf { it.epochMillis })
        replayTime = replayStart
        indexReplay()
        replayPanel.visibility = View.VISIBLE
        setReplayTime(replayStart)
        setReplayPlaying(true)
    }

    private fun indexReplay() {
        replayBySource = allFixes.filter { it.epochMillis >= replayStart }.groupBy { it.source }
            .mapValues { (_, list) -> list.sortedBy { it.epochMillis } }
    }

    private fun stopTimeline() {
        setReplayPlaying(false)
        replayTime = null
        replayPanel.visibility = View.GONE
        renderRadios()
    }

    private fun setReplayPlaying(playing: Boolean) {
        replayPlaying = playing && replayTime != null
        if (::replayPlayButton.isInitialized) replayPlayButton.text = if (replayPlaying) "⏸" else "▶"
        handler.removeCallbacks(replayTick)
        if (replayPlaying) {
            if ((replayTime ?: 0L) >= replayEnd) setReplayTime(replayStart)
            handler.postDelayed(replayTick, REPLAY_TICK_MS)
        }
    }

    private fun setReplayTime(t: Long, fromSeek: Boolean = false) {
        if (replayTime == null) return
        replayTime = t.coerceIn(replayStart, replayEnd)
        replayTimeText.text = getString(R.string.map_replay_time, clock(replayTime!!))
        if (!fromSeek && replayEnd > replayStart) {
            replaySeek.progress = ((replayTime!! - replayStart) * REPLAY_STEPS / (replayEnd - replayStart)).toInt()
        }
        renderRadios()
    }

    /** Each radio's last fix at or before [t], with its trail of the fixes before it. */
    private fun radiosAt(t: Long): List<PositionLog.Radio> = replayBySource.mapNotNull { (source, fixes) ->
        if (tgFilter != null && tgOf(source) != tgFilter) return@mapNotNull null
        var lo = 0
        var hi = fixes.size - 1
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (fixes[mid].epochMillis <= t) { idx = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (idx < 0) null else PositionLog.Radio(source, fixes[idx], fixes.subList(maxOf(0, idx - 9), idx + 1), idx + 1)
    }

    // ---- Instant replay ------------------------------------------------------------------

    private fun showRecent() {
        val context = applicationContext
        runCatching {
            io.execute {
                RecentCalls.maintain(context)
                val calls = RecentCalls.list(context)
                handler.post { if (!isDestroyed) showRecentList(calls) }
            }
        }
    }

    private fun callLabel(c: RecentCalls.Call): String {
        val time = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(c.startMs))
        val secs = ((c.durationMs + 500) / 1000).coerceAtMost(RecentCalls.MAX_SECONDS.toLong())
        val who = c.sourceId?.let { fullName(it) } ?: c.source.ifEmpty { getString(R.string.map_replay_analog) }
        val where = when {
            c.analog -> if (c.talkgroup.isNotEmpty()) " · ${c.talkgroup}" else ""
            c.group -> " → TG ${c.talkgroup}"
            else -> " → ${c.talkgroup}"
        }
        return "$time  $who$where  · ${secs}s"
    }

    private fun showRecentList(calls: List<RecentCalls.Call>) {
        val keep = RecentCalls.keepCount(this)
        val b = dialog().setTitle(getString(R.string.map_recent_title, calls.size))
        if (calls.isEmpty()) {
            b.setMessage(R.string.map_recent_empty)
        } else {
            b.setItems(calls.map { callLabel(it) }.toTypedArray()) { _, which ->
                playQueue.clear()
                play(calls[which])
            }
            b.setPositiveButton(R.string.map_recent_play_all) { _, _ ->
                playQueue.clear()
                playQueue.addAll(calls.reversed())
                playNext()
            }
        }
        b.setNeutralButton(getString(R.string.map_recent_keep, keep)) { _, _ -> chooseKeep() }
            .setNegativeButton(R.string.map_close, null)
            .show()
    }

    private fun chooseKeep() {
        val options = listOf(5, 10)
        val current = RecentCalls.keepCount(this)
        dialog().setTitle(R.string.map_recent_keep_title)
            .setSingleChoiceItems(options.map { getString(R.string.map_recent_keep_item, it) }.toTypedArray(),
                options.indexOf(current).coerceAtLeast(0)) { d, which ->
                d.dismiss()
                getSharedPreferences(RecentCalls.PREFS, MODE_PRIVATE).edit().putInt(RecentCalls.KEY_KEEP, options[which]).apply()
                showRecent()
            }.show()
    }

    private fun playNext() {
        val next = playQueue.removeFirstOrNull()
        if (next == null) {
            stopPlayback()
            return
        }
        play(next)
    }

    private fun play(call: RecentCalls.Call) {
        releasePlayer()
        if (!call.file.isFile) {
            playNext()
            return
        }
        val mp = MediaPlayer()
        player = mp
        runCatching {
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(call.file.absolutePath)
            mp.setOnCompletionListener { handler.post { playNext() } }
            mp.setOnErrorListener { _, _, _ -> handler.post { playNext() }; true }
            mp.prepare()
            mp.start()
        }.onFailure {
            toast(getString(R.string.map_recent_play_failed))
            releasePlayer()
            return
        }
        val remaining = if (playQueue.isEmpty()) "" else getString(R.string.map_recent_more, playQueue.size)
        playerBar.text = getString(R.string.map_recent_playing, callLabel(call), remaining)
        playerBar.visibility = View.VISIBLE
        handler.removeCallbacks(playbackLimit)
        handler.postDelayed(playbackLimit, RecentCalls.MAX_SECONDS * 1000L)
        call.sourceId?.let { id -> allRadios.firstOrNull { it.source == id }?.let { select(it, fly = true) } }
    }

    private fun releasePlayer() {
        handler.removeCallbacks(playbackLimit)
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
    }

    private fun stopPlayback() {
        playQueue.clear()
        releasePlayer()
        if (::playerBar.isInitialized) playerBar.visibility = View.GONE
    }

    // ---- Find ----------------------------------------------------------------------------

    private fun showFind() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
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
        val q = query.lowercase(Locale.getDefault())
        val matches = allRadios.filter {
            it.source.toString().contains(query) || (names[it.source]?.lowercase(Locale.getDefault())?.contains(q) == true)
        }
        when (matches.size) {
            0 -> toast(getString(R.string.map_find_none, query))
            1 -> { select(matches[0], fly = true); showRadio(matches[0]) }
            else -> {
                val now = System.currentTimeMillis()
                val items = matches.map { "${fullName(it.source)}   ${ageText(now - it.latest.epochMillis)} ago" }.toTypedArray()
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
        if (isFinishing || isDestroyed || !followTalker || replayTime != null) return
        if (!active) {
            // Between transmissions: the next key-up re-centres, even by the same radio.
            talkBanner.visibility = View.GONE
            followedSource = null
            return
        }
        val who = id?.let { names[it] }?.let { fullName(id) } ?: srcText.trim().ifBlank { id?.toString() ?: "?" }
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
        val features = if (me == null) emptyList() else listOf(pointJson(me.longitude, me.latitude, JSONObject()))
        s.getSourceAs<GeoJsonSource>(SRC_ME)?.setGeoJson(collectionJson(features))
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
        p.getStringSet(KEY_KINDS, null)?.let { saved ->
            kindsShown.clear()
            kindsShown.addAll(saved.filter { it in setOf(PositionLog.KIND_LRRP, PositionLog.KIND_USBD, PositionLog.KIND_GPS) })
        }
        idFilterText = p.getString(KEY_IDS, "") ?: ""
        idRanges = parseIdRanges(idFilterText)
    }

    private fun savePreferences() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_BASE, baseMap).putInt(KEY_WINDOW, windowIndex)
            .putBoolean(KEY_FOLLOW, followTalker).putString(KEY_TG, tgFilter ?: "")
            .putStringSet(KEY_OVERLAYS, LinkedHashSet(overlays)).putString(KEY_RADAR, radar)
            .putInt(KEY_CACHE, cacheMb)
            .putStringSet(KEY_KINDS, LinkedHashSet(kindsShown)).putString(KEY_IDS, idFilterText).apply()
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
        setReplayPlaying(false)
        stopPlayback()
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
        private const val KEY_KINDS = "report_kinds"
        private const val KEY_IDS = "id_filter"
        private const val REQ_IMPORT_NAMES = 4102
        /** Dot colours by age of the last report: green, then yellow, then red. */
        private const val GREEN_MS = 3 * 60_000L
        private const val YELLOW_MS = 5 * 60_000L
        private const val SRC_SELECTED = "sig-selected"
        private const val SRC_EMERGENCY = "sig-emergency"
        private val DOT_SOURCES = listOf("sig-dots-new", "sig-dots-stale", "sig-dots-old")
        private val DOT_LAYERS = listOf("sig-dots-new-layer", "sig-dots-stale-layer", "sig-dots-old-layer")
        private val DOT_COLORS = listOf(Color.parseColor("#00C853"), Color.parseColor("#FFD600"), Color.parseColor("#FF1744"))
        private const val REPLAY_TICK_MS = 200L
        private const val REPLAY_STEPS = 1000
        /** Label and how many seconds of history one second of playback covers. */
        private val REPLAY_SPEEDS = listOf("30×" to 30L, "120×" to 120L, "600×" to 600L, "3600×" to 3600L)
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        private const val SRC_UNITS = "sig-units"
        private const val SRC_TRACKS = "sig-tracks"
        private const val SRC_ME = "sig-me"
        private const val SRC_HISTORY_LINE = "sig-history-line"
        private const val SRC_HISTORY_PTS = "sig-history-pts"
        private const val LAYER_TRACKS = "sig-tracks-lines"
        private const val LAYER_ME = "sig-me-dot"
        private const val LAYER_SELECTED = "sig-units-selected"
        private const val LAYER_EMERGENCY = "sig-units-emergency"
        private const val LAYER_HISTORY_LINE = "sig-history-line-layer"
        private const val LAYER_HISTORY_PTS = "sig-history-pts-layer"
        private const val LAYER_HISTORY_LABELS = "sig-history-labels"

        private val BG = Color.parseColor("#07111F")
        private val BAR = Color.parseColor("#E607111F")
        private val PANEL = Color.parseColor("#E60C1A2E")
        private val STROKE = Color.parseColor("#33FFFFFF")
        private val TEXT = Color.parseColor("#E8EFF9")
        private val MUTED = Color.parseColor("#9FB3D1")
        private val TALK_BG = Color.parseColor("#E6B71C1C")
        private val ALERT_BG = Color.parseColor("#F2D50000")
        private val HISTORY_BG = Color.parseColor("#E64527A0")
        private val FOLLOW_ON = Color.parseColor("#E61458C4")
        private val FOLLOW_STROKE = Color.parseColor("#F5C242")
        private val PLAYER_BG = Color.parseColor("#E6006E3D")
        private val REPLAY_BG = Color.parseColor("#F00C1A2E")

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

        /** "30500-30600, 61121; 70000 - 70999" to ranges; anything unreadable is skipped. */
        @JvmStatic
        fun parseIdRanges(text: String): List<LongRange> = text.split(',', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { part ->
                val m = Regex("""^(\d{1,10})\s*(?:-|to|–)\s*(\d{1,10})$""").find(part)
                if (m != null) {
                    val a = m.groupValues[1].toLong()
                    val b = m.groupValues[2].toLong()
                    if (a <= b) a..b else b..a
                } else {
                    part.toLongOrNull()?.let { it..it }
                }
            }

        @JvmStatic
        fun idAllowed(ranges: List<LongRange>, id: Long): Boolean = ranges.isEmpty() || ranges.any { id in it }

        /** Called by name from decoder_host_android.cpp; keep in proguard-rules.pro. */
        @JvmStatic
        fun open(context: Context) {
            val intent = Intent(context, PositionMapActivity::class.java)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
