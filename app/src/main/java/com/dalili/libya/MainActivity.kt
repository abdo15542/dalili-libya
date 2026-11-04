package com.dalili.libya

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Dalili Libya v4.0 — rebuilt from scratch for the full project
 * document (Phase 1 complete, structured for the later phases).
 *
 * What is new against the patched v3.x line:
 * - Search-first home: search a place by name, pick from a results
 *   list, and it becomes the destination (previously only one result
 *   was ever used, and city spinners were the only starting point).
 * - Every route lists its road segments (road — distance), from the
 *   real OSRM step road names.
 * - Navigation reroutes automatically when the driver leaves the
 *   route (previously it only advanced steps and never recalculated).
 * - Saved places: home and work, stored on the device (tap routes,
 *   long-press saves the current destination).
 * - Reports with expiry (12 hours) and confirmations: a second
 *   independent report about the same place raises the count instead
 *   of duplicating the pin, and expired reports disappear from the
 *   map, the list and the safety count.
 *
 * What is deliberately NOT claimed: traffic colours (no reliable free
 * Libyan source — the map shows no fake traffic), a server, accounts,
 * notifications, a web app or an admin dashboard (document Phases 2+,
 * which need hosting decisions), and offline maps (no offline engine
 * yet — the map still needs internet). The data models are shaped so
 * those phases do not require an app rewrite.
 *
 * Proven pieces reused unchanged: Routing's OSRM client, the MapLibre
 * bundle and the index.html JavaScript bridge API.
 */
class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var webView: WebView
    private var tts: TextToSpeech? = null
    private var routes: List<RouteInfo> = emptyList()
    private var selectedRoute: RouteInfo? = null
    private var is3d = false
    private var isPreviewAnimating = false
    private var useCurrentLocation = false
    private var currentLat: Double? = null
    private var currentLon: Double? = null
    private var lastFromName: String = ""
    private var lastToName: String = ""
    private var tapLon: Double? = null
    private var tapLat: Double? = null
    private var lastFromLon: Double? = null
    private var lastFromLat: Double? = null
    private var lastToLon: Double? = null
    private var lastToLat: Double? = null
    private var travelMode: String = "driving"
    private var mapMode: String = "normal"
    private val httpClient = OkHttpClient()

    // Live navigation state.
    private var isNavigating = false
    private var navStepIndex = 0
    private var isFollowing = false
    private var lastNavLat: Double? = null
    private var lastNavLon: Double? = null
    private var lastNavBearing: Float = 0f
    private var locationManager: LocationManager? = null
    // Off-route detection: consecutive GPS fixes further than this
    // from the route polyline before a reroute is triggered.
    private var offRouteCount = 0
    private var isRerouting = false
    private val navListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            onNavLocation(location.latitude, location.longitude, if (location.hasBearing()) location.bearing else 0f)
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    // Place-search results: (short name, full name, lon, lat).
    private var placeResults: List<PlaceResult> = emptyList()
    private data class PlaceResult(val shortName: String, val fullName: String, val lon: Double, val lat: Double)

    private fun modeLabel(): String = when (travelMode) {
        "cycling" -> "بالدراجة"
        "walking" -> "مشيًا"
        else -> "بالسيارة"
    }

    private fun updateModeButtons() {
        val modes = listOf(
            Triple("driving", R.id.buttonModeCar, findViewById<Button>(R.id.buttonModeCar)),
            Triple("cycling", R.id.buttonModeBike, findViewById<Button>(R.id.buttonModeBike)),
            Triple("walking", R.id.buttonModeFoot, findViewById<Button>(R.id.buttonModeFoot))
        )
        for ((mode, _, button) in modes) {
            if (mode == travelMode) {
                button.setBackgroundResource(R.drawable.btn_primary_pill)
                button.setTextColor(android.graphics.Color.parseColor("#FFFFFF"))
            } else {
                button.setBackgroundResource(R.drawable.btn_secondary_pill)
                button.setTextColor(android.graphics.Color.parseColor("#0B6B4F"))
            }
        }
    }

    /** Origin for a new route when none was set yet: current GPS
     * location if available, otherwise the From city spinner. */
    private fun ensureFrom() {
        if (lastFromLon != null) return
        val fromCity = LIBYAN_CITIES[findViewById<Spinner>(R.id.spinnerFrom).selectedItemPosition]
        lastFromLat = if (useCurrentLocation && currentLat != null) currentLat!! else fromCity.lat
        lastFromLon = if (useCurrentLocation && currentLon != null) currentLon!! else fromCity.lon
        lastFromName = if (useCurrentLocation) "موقعي الحالي" else fromCity.name
    }

    /** One entry point for every "this point is the destination" path:
     * place-search pick, exact-place search, map tap, saved place. */
    private fun setDestinationAndRoute(lon: Double, lat: Double, name: String) {
        ensureFrom()
        lastToLon = lon
        lastToLat = lat
        lastToName = name
        PlacesStore.pushRecent(this, RecentDestination(name, lat, lon))
        refetchCurrentRoute()
    }

    private fun refetchCurrentRoute() {
        val flon = lastFromLon ?: return
        val flat = lastFromLat ?: return
        val tlon = lastToLon ?: return
        val tlat = lastToLat ?: return
        val fromName = lastFromName
        val toName = lastToName
        stopNavigation()
        findViewById<TextView>(R.id.textStatus).text = "جارٍ حساب المسار $fromName إلى $toName ${modeLabel()}…"
        Thread {
            try {
                val found = Routing.fetchRoutes(flon, flat, tlon, tlat, travelMode)
                runOnUiThread {
                    routes = found
                    selectedRoute = found.firstOrNull()
                    if (found.isEmpty()) {
                        findViewById<TextView>(R.id.textStatus).text = "لم يتم العثور على مسار ${modeLabel()}."
                        findViewById<TextView>(R.id.textRoutes).text = ""
                        findViewById<TextView>(R.id.textSegments).text = ""
                    } else {
                        showRoutes(fromName, toName)
                        selectedRoute?.let { showRouteOnMap(it) }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    findViewById<TextView>(R.id.textStatus).text = "تعذر حساب المسار: ${e.message}. تحقق من الاتصال بالإنترنت."
                }
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tts = TextToSpeech(this, this)

        webView = findViewById(R.id.webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onPreviewFinished() {
                runOnUiThread {
                    isPreviewAnimating = false
                    try { findViewById<Button>(R.id.buttonPreviewAnim).text = "تشغيل معاينة الرحلة المتحركة" } catch (e: Exception) {}
                }
            }
            @JavascriptInterface
            fun onDestinationPicked(lon: Double, lat: Double) {
                runOnUiThread {
                    setDestinationAndRoute(lon, lat, "نقطة مختارة على الخريطة")
                    Toast.makeText(this@MainActivity, "تم اختيار الوجهة من الخريطة — جارٍ حساب المسار إليها…", Toast.LENGTH_SHORT).show()
                }
            }
            @JavascriptInterface
            fun onMapTapped(lon: Double, lat: Double) {
                runOnUiThread {
                    tapLon = lon
                    tapLat = lat
                    findViewById<TextView>(R.id.textTapPoint).text =
                        "نقطة على الخريطة — خط العرض ${"%.6f".format(lat)}، خط الطول ${"%.6f".format(lon)}"
                    findViewById<View>(R.id.tapCard).visibility = View.VISIBLE
                    findViewById<View>(R.id.bottomCard).visibility = View.GONE
                }
            }
            @JavascriptInterface
            fun onMapDragged() {
                runOnUiThread {
                    if (isNavigating && isFollowing) {
                        isFollowing = false
                        try { findViewById<Button>(R.id.buttonRecenter).visibility = View.VISIBLE } catch (e: Exception) {}
                    }
                }
            }
        }, "DaliliBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                refreshReportsOnMap()
                selectedRoute?.let { showRouteOnMap(it) }
                // A reload resets the page's map-mode flags, so re-apply
                // the mode if it was not normal.
                when (mapMode) {
                    "satellite" -> webView.evaluateJavascript("window.setSatellite(true);", null)
                    "dark" -> webView.evaluateJavascript("window.setDark(true);", null)
                    "topo" -> webView.evaluateJavascript("window.setTopo(true);", null)
                }
            }
            @Suppress("DEPRECATION")
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                Toast.makeText(this@MainActivity, "تعذر تحميل صفحة الخريطة ($description). تحقق من الاتصال أو اضغط «إعادة تحميل الخريطة».", Toast.LENGTH_LONG).show()
            }
        }
        webView.loadUrl("file:///android_asset/www/index.html")

        // City spinners (default route Zliten → Tripoli, as before).
        val spinnerFrom: Spinner = findViewById(R.id.spinnerFrom)
        val spinnerTo: Spinner = findViewById(R.id.spinnerTo)
        val cityNames = LIBYAN_CITIES.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, cityNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerFrom.adapter = adapter
        spinnerTo.adapter = adapter
        spinnerFrom.setSelection(cityNames.indexOf("زليتن"))
        spinnerTo.setSelection(cityNames.indexOf("طرابلس"))

        // Report types (document §5).
        val reportTypes = listOf("حادث مروري", "ازدحام شديد", "إغلاق طريق", "أعمال صيانة", "حفرة أو تلف في الطريق", "تجمع مياه أو عائق", "خطر مؤقت على الطريق")
        val reportAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, reportTypes)
        reportAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        findViewById<Spinner>(R.id.spinnerReportType).adapter = reportAdapter

        setupSearch()
        setupDirections()
        setupModesAndMap()
        setupSheets()
        setupNavigationControls()
        setupDetails()
        updateSavedButtons()
        refreshReportsList()
        // Launch map-first, like a global map app: the map fills the
        // screen with only the slim search bar on top. The full
        // directions form is hidden until the user taps "بحث" — in
        // v4.0 it opened expanded and buried the map under a tall form,
        // which is the opposite of how a map app should open.
        findViewById<View>(R.id.topDetails).visibility = View.GONE
        findViewById<Button>(R.id.buttonToggleSearch).text = "بحث"
        findViewById<TextView>(R.id.textStatus).text = "ابحث عن مكان أو مدينة، أو اضغط «بحث» لاختيار وجهة ومسار."
    }

    // ---------- Search: place results list ----------

    private fun setupSearch() {
        findViewById<Button>(R.id.buttonSearchPlace).setOnClickListener {
            val query = findViewById<EditText>(R.id.editPlaceSearch).text.toString().trim()
            if (query.isEmpty()) {
                Toast.makeText(this, "اكتب اسم مكان أو مدينة أو عنوان أولًا", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            findViewById<TextView>(R.id.textStatus).text = "جارٍ البحث عن «$query»…"
            Thread {
                try {
                    val encoded = java.net.URLEncoder.encode(query, "UTF-8")
                    val url = java.net.URL("https://nominatim.openstreetmap.org/search?format=json&q=$encoded&countrycodes=ly&limit=5")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.setRequestProperty("User-Agent", "DaliliLibya/4.0")
                    conn.connectTimeout = 15000
                    conn.readTimeout = 15000
                    val body = conn.inputStream.bufferedReader().readText()
                    val arr = org.json.JSONArray(body)
                    val results = mutableListOf<PlaceResult>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val full = o.optString("display_name", query)
                        val short = full.split(",").take(2).joinToString(", ").trim()
                        results.add(PlaceResult(short.ifBlank { full }, full, o.getDouble("lon"), o.getDouble("lat")))
                    }
                    runOnUiThread {
                        placeResults = results
                        val listView = findViewById<ListView>(R.id.listPlaceResults)
                        if (results.isEmpty()) {
                            listView.visibility = View.GONE
                            findViewById<TextView>(R.id.textStatus).text = "لم يُعثر على «$query» في خريطة ليبيا."
                            Toast.makeText(this, "لم يُعثر على هذا المكان — جرّب اسم شارع أو حي مع اسم المدينة، أو اختر النقطة بالضغط على الخريطة.", Toast.LENGTH_LONG).show()
                        } else {
                            listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, results.map { it.shortName })
                            listView.visibility = View.VISIBLE
                            findViewById<TextView>(R.id.textStatus).text = "اختر مكانًا من النتائج ليكون وجهتك."
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        findViewById<TextView>(R.id.textStatus).text = "تعذر البحث عن المكان: ${e.message}. تحقق من الاتصال بالإنترنت."
                    }
                }
            }.start()
        }
        findViewById<ListView>(R.id.listPlaceResults).onItemClickListener =
            AdapterView.OnItemClickListener { _, _, position, _ ->
                val picked = placeResults.getOrNull(position) ?: return@OnItemClickListener
                findViewById<ListView>(R.id.listPlaceResults).visibility = View.GONE
                Toast.makeText(this, "الوجهة: ${picked.fullName}", Toast.LENGTH_LONG).show()
                setDestinationAndRoute(picked.lon, picked.lat, picked.fullName)
            }
    }

    // ---------- Directions ----------

    private fun setupDirections() {
        findViewById<Button>(R.id.buttonLocation).setOnClickListener { requestLocation() }

        findViewById<Button>(R.id.buttonFind).setOnClickListener {
            val fromCity = LIBYAN_CITIES[findViewById<Spinner>(R.id.spinnerFrom).selectedItemPosition]
            val toCity = LIBYAN_CITIES[findViewById<Spinner>(R.id.spinnerTo).selectedItemPosition]
            val fromLat = if (useCurrentLocation && currentLat != null) currentLat!! else fromCity.lat
            val fromLon = if (useCurrentLocation && currentLon != null) currentLon!! else fromCity.lon
            val fromName = if (useCurrentLocation) "موقعي الحالي" else fromCity.name
            lastFromName = fromName
            lastToName = toCity.name
            lastFromLon = fromLon
            lastFromLat = fromLat
            lastToLon = toCity.lon
            lastToLat = toCity.lat
            stopNavigation()
            findViewById<TextView>(R.id.textStatus).text = "جارٍ حساب المسارات من $fromName إلى ${toCity.name} ${modeLabel()}…"
            Thread {
                try {
                    val found = Routing.fetchRoutes(fromLon, fromLat, toCity.lon, toCity.lat, travelMode)
                    runOnUiThread {
                        routes = found
                        selectedRoute = found.firstOrNull()
                        if (found.isEmpty()) {
                            findViewById<TextView>(R.id.textStatus).text = "لم يتم العثور على مسار بين المدينتين."
                            findViewById<TextView>(R.id.textRoutes).text = ""
                            findViewById<TextView>(R.id.textSegments).text = ""
                        } else {
                            PlacesStore.pushRecent(this, RecentDestination(toCity.name, toCity.lat, toCity.lon))
                            showRoutes(fromName, toCity.name)
                            selectedRoute?.let { showRouteOnMap(it) }
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        findViewById<TextView>(R.id.textStatus).text = "تعذر حساب المسار: ${e.message}. تحقق من الاتصال بالإنترنت."
                    }
                }
            }.start()
        }

        findViewById<Button>(R.id.buttonSwap).setOnClickListener {
            val fromPos = findViewById<Spinner>(R.id.spinnerFrom).selectedItemPosition
            val toPos = findViewById<Spinner>(R.id.spinnerTo).selectedItemPosition
            findViewById<Spinner>(R.id.spinnerFrom).setSelection(toPos)
            findViewById<Spinner>(R.id.spinnerTo).setSelection(fromPos)
            Toast.makeText(this, "تم تبديل الاتجاه", Toast.LENGTH_SHORT).show()
        }

        // Exact place inside the destination (single best match).
        findViewById<Button>(R.id.buttonSearchDestination).setOnClickListener {
            val query = findViewById<EditText>(R.id.editDestinationSearch).text.toString().trim()
            if (query.isEmpty()) {
                Toast.makeText(this, "اكتب اسم شارع أو حي أو مكان أولًا", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            findViewById<TextView>(R.id.textStatus).text = "جارٍ البحث عن «$query»…"
            Thread {
                try {
                    val encoded = java.net.URLEncoder.encode(query, "UTF-8")
                    val url = java.net.URL("https://nominatim.openstreetmap.org/search?format=json&q=$encoded&countrycodes=ly&limit=1")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.setRequestProperty("User-Agent", "DaliliLibya/4.0")
                    conn.connectTimeout = 15000
                    conn.readTimeout = 15000
                    val body = conn.inputStream.bufferedReader().readText()
                    val arr = org.json.JSONArray(body)
                    if (arr.length() == 0) {
                        runOnUiThread {
                            findViewById<TextView>(R.id.textStatus).text = "لم يُعثر على «$query» في خريطة ليبيا."
                            Toast.makeText(this, "لم يُعثر على هذا المكان — جرّب اسم شارع أو حي مع اسم المدينة، أو اختر النقطة بالضغط على الخريطة.", Toast.LENGTH_LONG).show()
                        }
                        return@Thread
                    }
                    val first = arr.getJSONObject(0)
                    val dLat = first.getDouble("lat")
                    val dLon = first.getDouble("lon")
                    val dName = first.optString("display_name", query)
                    runOnUiThread {
                        Toast.makeText(this, "الوجهة الدقيقة: $dName", Toast.LENGTH_LONG).show()
                        setDestinationAndRoute(dLon, dLat, dName)
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        findViewById<TextView>(R.id.textStatus).text = "تعذر البحث عن المكان: ${e.message}. تحقق من الاتصال بالإنترنت."
                    }
                }
            }.start()
        }

        findViewById<Button>(R.id.buttonPickDestination).setOnClickListener {
            webView.evaluateJavascript("window.setPickDestination(true);", null)
            Toast.makeText(this, "اضغط الآن على النقطة الدقيقة في الخريطة لتكون وجهتك", Toast.LENGTH_LONG).show()
        }

        // Tapped-place card actions.
        findViewById<Button>(R.id.buttonUseTapDestination).setOnClickListener {
            val lon = tapLon
            val lat = tapLat
            if (lon == null || lat == null) {
                Toast.makeText(this, "اضغط على نقطة في الخريطة أولًا", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            findViewById<View>(R.id.tapCard).visibility = View.GONE
            setDestinationAndRoute(lon, lat, "نقطة مختارة على الخريطة")
        }
        findViewById<Button>(R.id.buttonCloseTap).setOnClickListener {
            findViewById<View>(R.id.tapCard).visibility = View.GONE
            webView.evaluateJavascript("window.clearTapMarker();", null)
            if (findViewById<View>(R.id.topDetails).visibility != View.VISIBLE) {
                findViewById<View>(R.id.bottomCard).visibility = View.VISIBLE
            }
        }

        // Saved places: tap routes, long-press saves the destination.
        setupSavedPlaceButton(R.id.buttonHomePlace, "home", "المنزل")
        setupSavedPlaceButton(R.id.buttonWorkPlace, "work", "العمل")
    }

    private fun setupSavedPlaceButton(buttonId: Int, kind: String, label: String) {
        val button = findViewById<Button>(buttonId)
        button.setOnClickListener {
            val saved = PlacesStore.getSaved(this, kind)
            if (saved == null) {
                Toast.makeText(this, "لا يوجد $label محفوظ بعد — اضغط مطولًا على زر $label لحفظ الوجهة الحالية", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "الوجهة: $label — ${saved.name}", Toast.LENGTH_SHORT).show()
                setDestinationAndRoute(saved.lon, saved.lat, saved.name)
            }
        }
        button.setOnLongClickListener {
            val dLon = lastToLon
            val dLat = lastToLat
            if (dLon == null || dLat == null) {
                Toast.makeText(this, "اختر وجهة أولًا ثم اضغط مطولًا لحفظها", Toast.LENGTH_SHORT).show()
            } else {
                PlacesStore.setSaved(this, SavedPlace(kind, lastToName, dLat, dLon))
                updateSavedButtons()
                Toast.makeText(this, "تم حفظ $label: $lastToName", Toast.LENGTH_SHORT).show()
            }
            true
        }
    }

    private fun updateSavedButtons() {
        try {
            findViewById<Button>(R.id.buttonHomePlace).text =
                if (PlacesStore.getSaved(this, "home") != null) "المنزل ✓" else "المنزل"
            findViewById<Button>(R.id.buttonWorkPlace).text =
                if (PlacesStore.getSaved(this, "work") != null) "العمل ✓" else "العمل"
        } catch (e: Exception) {}
    }

    // ---------- Travel modes and map modes ----------

    private fun setupModesAndMap() {
        findViewById<Button>(R.id.buttonModeCar).setOnClickListener {
            travelMode = "driving"; updateModeButtons(); refetchCurrentRoute()
        }
        findViewById<Button>(R.id.buttonModeBike).setOnClickListener {
            travelMode = "cycling"; updateModeButtons(); refetchCurrentRoute()
        }
        findViewById<Button>(R.id.buttonModeFoot).setOnClickListener {
            travelMode = "walking"; updateModeButtons(); refetchCurrentRoute()
        }
        updateModeButtons()

        fun setMapMode(mode: String) {
            val newMode = if (mode == mapMode) "normal" else mode
            mapMode = newMode
            when (newMode) {
                "satellite" -> webView.evaluateJavascript("window.setSatellite(true);", null)
                "dark" -> webView.evaluateJavascript("window.setDark(true);", null)
                "topo" -> webView.evaluateJavascript("window.setTopo(true);", null)
                else -> webView.evaluateJavascript("window.setSatellite(false);", null)
            }
            findViewById<Button>(R.id.buttonSatellite).text = if (newMode == "satellite") "العودة إلى الخريطة العادية" else "خريطة القمر الصناعي"
            findViewById<Button>(R.id.buttonDarkMap).text = if (newMode == "dark") "العودة إلى الخريطة العادية" else "خريطة داكنة"
            findViewById<Button>(R.id.buttonTopoMap).text = if (newMode == "topo") "العودة إلى الخريطة العادية" else "خريطة التضاريس"
            Toast.makeText(this, when (newMode) {
                "satellite" -> "خريطة القمر الصناعي مفعّلة — صور جوية حقيقية"
                "dark" -> "خريطة داكنة مفعّلة"
                "topo" -> "خريطة التضاريس مفعّلة — طرق وتضاريس"
                else -> "خريطة عادية"
            }, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.buttonSatellite).setOnClickListener { setMapMode("satellite") }
        findViewById<Button>(R.id.buttonDarkMap).setOnClickListener { setMapMode("dark") }
        findViewById<Button>(R.id.buttonTopoMap).setOnClickListener { setMapMode("topo") }

        findViewById<Button>(R.id.buttonReloadMap).setOnClickListener {
            webView.reload()
            Toast.makeText(this, "جارٍ إعادة تحميل الخريطة…", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.buttonShowRoute).setOnClickListener {
            val route = selectedRoute
            if (route == null) {
                Toast.makeText(this, "ابحث عن المسارات أولًا", Toast.LENGTH_SHORT).show()
            } else {
                showRouteOnMap(route)
                Toast.makeText(this, "تم عرض المسار كاملًا على الخريطة", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.button3d).setOnClickListener {
            // 3D needs the vector map: return to normal first if a
            // non-vector mode is on, keeping button texts in sync.
            if (mapMode != "normal") {
                mapMode = "normal"
                webView.evaluateJavascript("window.setSatellite(false);", null)
                findViewById<Button>(R.id.buttonSatellite).text = "خريطة القمر الصناعي"
                findViewById<Button>(R.id.buttonDarkMap).text = "خريطة داكنة"
                findViewById<Button>(R.id.buttonTopoMap).text = "خريطة التضاريس"
            }
            is3d = !is3d
            webView.evaluateJavascript("window.set3d($is3d);", null)
            Toast.makeText(this, if (is3d) "عرض ثلاثي الأبعاد مفعّل — انتقل إلى منطقة مبانٍ" else "عرض مسطح", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Sheets (search / details panels) ----------

    private fun setupSheets() {
        // The expanded search card and the bottom sheet are stacked in
        // one FrameLayout, so while the search panel is open the bottom
        // card is hidden entirely; it returns when search closes or a
        // route is found. The two panels are never open together.
        findViewById<Button>(R.id.buttonToggleSearch).setOnClickListener {
            val details = findViewById<View>(R.id.topDetails)
            val button = findViewById<Button>(R.id.buttonToggleSearch)
            if (details.visibility == View.VISIBLE) {
                details.visibility = View.GONE
                button.text = "بحث"
                findViewById<View>(R.id.bottomCard).visibility = View.VISIBLE
            } else {
                details.visibility = View.VISIBLE
                button.text = "إخفاء البحث"
                findViewById<View>(R.id.bottomCard).visibility = View.GONE
            }
        }
        findViewById<Button>(R.id.buttonToggleDetails).setOnClickListener {
            val details = findViewById<View>(R.id.scrollDetails)
            val button = findViewById<Button>(R.id.buttonToggleDetails)
            if (details.visibility == View.VISIBLE) {
                details.visibility = View.GONE
                button.text = "إظهار المزيد من التفاصيل"
            } else {
                details.visibility = View.VISIBLE
                button.text = "إخفاء التفاصيل"
                findViewById<View>(R.id.topDetails).visibility = View.GONE
                findViewById<Button>(R.id.buttonToggleSearch).text = "بحث"
            }
        }
    }

    // ---------- Navigation ----------

    private fun setupNavigationControls() {
        findViewById<Button>(R.id.buttonNavStop).setOnClickListener {
            stopNavigation()
            Toast.makeText(this, "تم إيقاف الملاحة", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.buttonRecenter).setOnClickListener {
            isFollowing = true
            findViewById<Button>(R.id.buttonRecenter).visibility = View.GONE
            val lat = lastNavLat
            val lon = lastNavLon
            if (lat != null && lon != null) {
                webView.evaluateJavascript("window.followUser($lon, $lat, $lastNavBearing);", null)
            }
        }
        findViewById<Button>(R.id.buttonVoice).setOnClickListener {
            val route = selectedRoute
            if (route == null) {
                Toast.makeText(this, "اختر مسارًا أولًا", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (isNavigating) {
                stopNavigation()
                Toast.makeText(this, "تم إيقاف الملاحة الصوتية", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (route.steps.isEmpty()) {
                Toast.makeText(this, "لا توجد تعليمات صوتية لهذا المسار", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startNavigation(route)
        }
        findViewById<Button>(R.id.buttonNextRoute).setOnClickListener {
            if (routes.isEmpty()) {
                Toast.makeText(this, "ابحث عن المسارات أولًا", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val currentIndex = routes.indexOf(selectedRoute)
            val nextIndex = if (currentIndex < 0) 0 else (currentIndex + 1) % routes.size
            selectedRoute = routes[nextIndex]
            showRoutes(lastFromName, lastToName)
            selectedRoute?.let { showRouteOnMap(it) }
            Toast.makeText(this, "المسار ${nextIndex + 1} معروض على الخريطة", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startNavigation(route: RouteInfo) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            val text = route.steps.joinToString(". ") { it.text }
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav")
            Toast.makeText(this, "بدون إذن الموقع، تمت قراءة التعليمات كاملة بدل الملاحة الحية.", Toast.LENGTH_LONG).show()
            return
        }
        isNavigating = true
        navStepIndex = 0
        isFollowing = true
        offRouteCount = 0
        try {
            findViewById<View>(R.id.navBar).visibility = View.VISIBLE
            findViewById<View>(R.id.topCard).visibility = View.GONE
            findViewById<View>(R.id.bottomCard).visibility = View.GONE
            findViewById<View>(R.id.tapCard).visibility = View.GONE
            findViewById<Button>(R.id.buttonRecenter).visibility = View.GONE
        } catch (e: Exception) {}
        speakStep(route, 0)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000, 10f, navListener)
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر بدء تتبع الموقع: ${e.message}", Toast.LENGTH_LONG).show()
        }
        findViewById<Button>(R.id.buttonVoice).text = "إيقاف الملاحة الصوتية"
    }

    private fun stopNavigation() {
        if (!isNavigating) return
        isNavigating = false
        isFollowing = false
        try { locationManager?.removeUpdates(navListener) } catch (e: Exception) {}
        tts?.stop()
        try {
            findViewById<Button>(R.id.buttonVoice).text = getString(R.string.voice_nav)
            findViewById<View>(R.id.navBar).visibility = View.GONE
            findViewById<View>(R.id.topCard).visibility = View.VISIBLE
            findViewById<View>(R.id.bottomCard).visibility = View.VISIBLE
            findViewById<Button>(R.id.buttonRecenter).visibility = View.GONE
        } catch (e: Exception) {}
    }

    private fun updateNavBanner(route: RouteInfo, stepIndex: Int) {
        try {
            val stepText = if (stepIndex in route.steps.indices) route.steps[stepIndex].text else "ابدأ الرحلة"
            findViewById<TextView>(R.id.textNavStep).text = stepText
            val nextTextView = findViewById<TextView>(R.id.textNavNext)
            if (stepIndex + 1 in route.steps.indices) {
                val next = route.steps[stepIndex + 1]
                val arrow = when (next.modifier) {
                    "left", "uturn" -> "↩"
                    "right" -> "↪"
                    "slight left" -> "↖"
                    "slight right" -> "↗"
                    "sharp left" -> "↩"
                    "sharp right" -> "↪"
                    else -> "↑"
                }
                nextTextView.text = "ثم $arrow ${next.text}"
                nextTextView.visibility = View.VISIBLE
            } else {
                nextTextView.visibility = View.GONE
            }
            var remKm = 0.0
            var remMin = 0.0
            for (i in stepIndex until route.steps.size) {
                remKm += route.steps[i].distanceKm
                remMin += route.steps[i].durationMin
            }
            if (remKm == 0.0 && remMin == 0.0) {
                remKm = route.distanceKm
                remMin = route.durationMin
            }
            val arrival = java.util.Calendar.getInstance()
            arrival.add(java.util.Calendar.MINUTE, remMin.roundToInt())
            val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            val remText = if (remKm < 1.0) "${(remKm * 1000).roundToInt()} متر" else "${"%.1f".format(remKm)} كم"
            var destText = ""
            val curLat = lastNavLat
            val curLon = lastNavLon
            val dLat = lastToLat
            val dLon = lastToLon
            if (curLat != null && curLon != null && dLat != null && dLon != null) {
                val d = distanceMetres(curLat, curLon, dLat, dLon)
                destText = if (d < 1000.0) " — الوجهة على بعد ${d.roundToInt()} متر" else " — الوجهة على بعد ${"%.1f".format(d / 1000.0)} كم"
            }
            findViewById<TextView>(R.id.textNavEta).text =
                "باقي $remText — ${remMin.roundToInt()} دقيقة — الوصول ${timeFmt.format(arrival.time)}$destText"
        } catch (e: Exception) {}
    }

    private fun speakStep(route: RouteInfo, index: Int) {
        if (index in route.steps.indices) {
            updateNavBanner(route, index)
            tts?.speak(route.steps[index].text, TextToSpeech.QUEUE_FLUSH, null, "nav$index")
        }
    }

    private fun onNavLocation(lat: Double, lon: Double, bearing: Float) {
        val route = selectedRoute ?: return
        if (!isNavigating) return
        lastNavLat = lat
        lastNavLon = lon
        lastNavBearing = bearing
        if (isFollowing) {
            webView.evaluateJavascript("window.followUser($lon, $lat, $bearing);", null)
        }
        // Arrival: within 100 metres of the exact destination point.
        val destLat = lastToLat
        val destLon = lastToLon
        if (destLat != null && destLon != null) {
            val distToDest = distanceMetres(lat, lon, destLat, destLon)
            if (distToDest <= 100.0) {
                val destName = lastToName
                stopNavigation()
                tts?.speak("وصلت إلى وجهتك: $destName", TextToSpeech.QUEUE_FLUSH, null, "arrived")
                Toast.makeText(this, "وصلت إلى وجهتك: $destName", Toast.LENGTH_LONG).show()
                findViewById<TextView>(R.id.textStatus).text = "وصلت إلى وجهتك: $destName"
                return
            }
        }
        // v4.0 rerouting: if the driver is further than 150 metres from
        // the route polyline on two consecutive fixes, the old route is
        // abandoned and a new one is calculated from the current GPS
        // position to the same destination — without stopping navigation
        // or showing the search sheets again.
        val distToRoute = Routing.distanceToRoute(lat, lon, route)
        if (distToRoute > 150.0) {
            offRouteCount++
        } else {
            offRouteCount = 0
        }
        if (offRouteCount >= 2 && !isRerouting) {
            offRouteCount = 0
            rerouteFromCurrent(lat, lon)
            return
        }
        updateNavBanner(route, navStepIndex)
        // Speak the next step when the user is within 200 metres of its manoeuvre.
        var next = navStepIndex + 1
        while (next in route.steps.indices) {
            val step = route.steps[next]
            if (step.lon == 0.0 && step.lat == 0.0) { next++; continue }
            val dist = distanceMetres(lat, lon, step.lat, step.lon)
            if (dist <= 200.0) {
                navStepIndex = next
                speakStep(route, next)
                return
            }
            break
        }
        if (navStepIndex >= route.steps.size - 1) {
            stopNavigation()
        }
    }

    private fun rerouteFromCurrent(lat: Double, lon: Double) {
        val tlon = lastToLon ?: return
        val tlat = lastToLat ?: return
        isRerouting = true
        lastFromLon = lon
        lastFromLat = lat
        lastFromName = "موقعي الحالي"
        Thread {
            try {
                val found = Routing.fetchRoutes(lon, lat, tlon, tlat, travelMode)
                runOnUiThread {
                    isRerouting = false
                    if (found.isEmpty()) {
                        Toast.makeText(this, "تعذر إعادة حساب المسار — أبقيت المسار القديم.", Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    routes = found
                    selectedRoute = found.firstOrNull()
                    val newRoute = selectedRoute ?: return@runOnUiThread
                    navStepIndex = 0
                    showRouteOnMap(newRoute)
                    updateNavBanner(newRoute, 0)
                    val firstText = if (newRoute.steps.isNotEmpty()) newRoute.steps[0].text else "ابدأ الرحلة"
                    tts?.speak("انحرفت عن المسار، تمت إعادة حساب الطريق. $firstText", TextToSpeech.QUEUE_FLUSH, null, "reroute")
                    Toast.makeText(this, "تمت إعادة حساب المسار من موقعك الحالي", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    isRerouting = false
                    Toast.makeText(this, "تعذر إعادة حساب المسار: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun distanceMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    // ---------- Route display ----------

    private fun showRoutes(fromName: String, toName: String) {
        val consumptionRaw = findViewById<EditText>(R.id.editConsumption).text.toString().toDoubleOrNull() ?: 8.0
        val consumption = if (consumptionRaw > 0) consumptionRaw else 8.0
        val priceRaw = findViewById<EditText>(R.id.editFuelPrice).text.toString().toDoubleOrNull() ?: 0.15
        val price = if (priceRaw >= 0) priceRaw else 0.15
        val reports = ReportsStore.active(this)
        val sb = StringBuilder()
        sb.append("من $fromName إلى $toName — ${modeLabel()}\n")
        val sortedByTime = routes.sortedBy { it.durationMin }
        val sortedByCost = routes.sortedBy { it.distanceKm }
        routes.forEachIndexed { index, route ->
            val fuel = Routing.fuelLitres(route.distanceKm, consumption)
            val cost = fuel * price
            val fastest = route == sortedByTime.firstOrNull()
            val cheapest = route == sortedByCost.firstOrNull()
            val minLat = route.coordinates.minOfOrNull { it.second } ?: 0.0
            val maxLat = route.coordinates.maxOfOrNull { it.second } ?: 0.0
            val minLon = route.coordinates.minOfOrNull { it.first } ?: 0.0
            val maxLon = route.coordinates.maxOfOrNull { it.first } ?: 0.0
            val nearCount = reports.count { it.lat in (minLat - 0.1)..(maxLat + 0.1) && it.lon in (minLon - 0.1)..(maxLon + 0.1) }
            val fuelText = if (travelMode == "driving") "، وقود تقديري ${"%.1f".format(fuel)} لتر، تكلفة تقديرية ${"%.2f".format(cost)} دينار" else "، بدون وقود"
            sb.append("\nالمسار ${index + 1}: ${"%.1f".format(route.distanceKm)} كم، ${route.durationMin.roundToInt()} دقيقة${fuelText}، درجة أمان تقديرية ${Routing.safetyScore(nearCount)}")
            if (fastest) sb.append(" — الأسرع")
            if (cheapest) sb.append(" — الأقل مسافة")
            val maxDist = routes.maxOfOrNull { it.distanceKm } ?: route.distanceKm
            if (cheapest && maxDist > route.distanceKm) {
                val saving = ((maxDist - route.distanceKm) / maxDist * 100).roundToInt()
                if (saving >= 1) sb.append(" — يوفّر الوقود بنسبة $saving%")
            }
            if (route == selectedRoute) sb.append(" — المعروض على الخريطة")
        }
        sb.append("\n\nدرجة الأمان تقدير من بلاغات قريبة من هذا المسار فقط، وليست تصنيفًا رسميًا.")
        try { findViewById<View>(R.id.tapCard).visibility = View.GONE } catch (e: Exception) {}
        findViewById<TextView>(R.id.textRoutes).text = sb.toString()
        // Road segments of the displayed route (document requirement).
        val shown = selectedRoute
        findViewById<TextView>(R.id.textSegments).text = if (shown == null) {
            ""
        } else {
            val segs = Routing.segments(shown)
            if (segs.isEmpty()) {
                "مقاطع الطريق: لا توجد أسماء طرق متاحة لهذا المسار في بيانات الخريطة."
            } else {
                "مقاطع الطريق (المسار المعروض):\n" + segs.joinToString("\n") { (road, km) -> "• $road — ${"%.1f".format(km)} كم" }
            }
        }
        findViewById<TextView>(R.id.textStatus).text = "تم العثور على ${routes.size} مسارًا — ${modeLabel()}."
        try {
            findViewById<View>(R.id.topDetails).visibility = View.GONE
            findViewById<Button>(R.id.buttonToggleSearch).text = "بحث"
            findViewById<View>(R.id.scrollDetails).visibility = View.GONE
            findViewById<Button>(R.id.buttonToggleDetails).text = "إظهار المزيد من التفاصيل"
            findViewById<View>(R.id.bottomCard).visibility = View.VISIBLE
        } catch (e: Exception) {}
    }

    private fun showRouteOnMap(route: RouteInfo) {
        val js = "window.setRoute(${route.geometryJson});"
        webView.evaluateJavascript(js, null)
        val dLon = lastToLon
        val dLat = lastToLat
        if (dLon != null && dLat != null) {
            webView.evaluateJavascript("window.setDestination($dLon, $dLat, ${org.json.JSONObject.quote(lastToName)});", null)
        }
        drawAllRoutes()
    }

    private fun drawAllRoutes() {
        try {
            if (routes.isEmpty()) return
            val selectedIndex = routes.indexOf(selectedRoute).takeIf { it >= 0 } ?: 0
            val geometries = routes.joinToString(",") { it.geometryJson }
            webView.evaluateJavascript("window.setRoutes([$geometries], $selectedIndex);", null)
            val labels = routes.map { r ->
                val mid = r.coordinates.getOrNull(r.coordinates.size / 2) ?: Pair(0.0, 0.0)
                "{lon:${mid.first},lat:${mid.second},text:'${r.durationMin.roundToInt()} د'}"
            }.joinToString(",")
            webView.evaluateJavascript("window.setRouteLabels([$labels]);", null)
        } catch (e: Exception) {}
    }

    // ---------- Reports, fuel, preview, location ----------

    private fun setupDetails() {
        findViewById<Button>(R.id.buttonReport).setOnClickListener {
            val route = selectedRoute
            if (route == null || route.coordinates.isEmpty()) {
                Toast.makeText(this, "اختر مسارًا أولًا", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val mid = route.coordinates[route.coordinates.size / 2]
            val type = findViewById<Spinner>(R.id.spinnerReportType).selectedItem.toString()
            ReportsStore.add(this, RoadReport(type, mid.second, mid.first, System.currentTimeMillis()))
            refreshReportsList()
            refreshReportsOnMap()
            Toast.makeText(this, "تم حفظ البلاغ محليًا على جهازك فقط. بلاغ مستخدم، غير مؤكد رسميًا، وينتهي بعد 12 ساعة إن لم يُؤكَّد.", Toast.LENGTH_LONG).show()
        }

        findViewById<Button>(R.id.buttonFuel).setOnClickListener {
            val route = selectedRoute
            if (route == null || route.coordinates.isEmpty()) {
                Toast.makeText(this, "اختر مسارًا أولًا", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            findViewById<TextView>(R.id.textFuel).text = "جارٍ البحث عن محطات وقود على طول المسار…"
            Thread {
                try {
                    val points = listOf(0.25, 0.5, 0.75).map { frac ->
                        val idx = (frac * (route.coordinates.size - 1)).toInt().coerceIn(0, route.coordinates.size - 1)
                        route.coordinates[idx]
                    }
                    val foundNames = linkedSetOf<String>()
                    for ((lon, lat) in points) {
                        val url = "https://nominatim.openstreetmap.org/search?format=json&q=fuel&viewbox=${lon - 0.5},${lat + 0.5},${lon + 0.5},${lat - 0.5}&bounded=1&limit=5"
                        val request = Request.Builder().url(url).header("User-Agent", "DaliliLibya/4.0").build()
                        httpClient.newCall(request).execute().use { response ->
                            val body = response.body?.string() ?: "[]"
                            val arr = org.json.JSONArray(body)
                            for (i in 0 until arr.length()) {
                                foundNames.add(arr.getJSONObject(i).optString("display_name"))
                            }
                        }
                    }
                    val names = foundNames.filter { it.isNotBlank() }.take(10)
                    runOnUiThread {
                        findViewById<TextView>(R.id.textFuel).text =
                            if (names.isEmpty()) "لم يتم العثور على محطات وقود قريبة من المسار في بيانات OpenStreetMap."
                            else names.joinToString("\n• ", prefix = "محطات على طول المسار:\n• ")
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        findViewById<TextView>(R.id.textFuel).text = "تعذر البحث عن محطات الوقود: ${e.message}"
                    }
                }
            }.start()
        }

        findViewById<Button>(R.id.buttonPreviewAnim).setOnClickListener {
            val route = selectedRoute
            if (route == null || route.coordinates.isEmpty()) {
                Toast.makeText(this, "ابحث عن المسارات أولًا", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            isPreviewAnimating = !isPreviewAnimating
            if (isPreviewAnimating) {
                webView.evaluateJavascript("window.startPreviewAnimation();", null)
                findViewById<Button>(R.id.buttonPreviewAnim).text = "إيقاف معاينة الرحلة المتحركة"
            } else {
                webView.evaluateJavascript("window.stopPreviewAnimation();", null)
                findViewById<Button>(R.id.buttonPreviewAnim).text = "تشغيل معاينة الرحلة المتحركة"
            }
        }

        findViewById<SeekBar>(R.id.seekPreview).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    webView.evaluateJavascript("window.stopPreviewAnimation();", null)
                    isPreviewAnimating = false
                    try { findViewById<Button>(R.id.buttonPreviewAnim).text = "تشغيل معاينة الرحلة المتحركة" } catch (e: Exception) {}
                    webView.evaluateJavascript("window.previewRoute(${progress / 100.0});", null)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun refreshReportsList() {
        val reports = ReportsStore.active(this)
        val now = System.currentTimeMillis()
        val text = if (reports.isEmpty()) {
            "لا توجد بلاغات سارية الآن. البلاغات محفوظة على جهازك فقط، وتنتهي بعد 12 ساعة."
        } else {
            reports.joinToString("\n") { r ->
                val ageMin = ((now - r.timeMillis) / 60000).toInt()
                val ageText = if (ageMin < 60) "قبل $ageMin دقيقة" else "قبل ${ageMin / 60} ساعة"
                val confText = if (r.confirmations > 1) "• تأكيد من ${r.confirmations} بلاغات مستقلة" else "• بلاغ واحد — يحتاج تأكيدًا"
                "• ${r.type} — $ageText $confText — بلاغ مستخدم، غير مؤكد رسميًا."
            }
        }
        findViewById<TextView>(R.id.textReports).text = text
    }

    private fun refreshReportsOnMap() {
        webView.evaluateJavascript("window.clearReports();", null)
        val reports = ReportsStore.active(this)
        for (r in reports) {
            val js = "window.addReport(${r.lon}, ${r.lat}, '${r.type}');"
            webView.evaluateJavascript(js, null)
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1001)
            return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val location = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        if (location != null) {
            currentLat = location.latitude
            currentLon = location.longitude
            useCurrentLocation = true
            findViewById<TextView>(R.id.textStatus).text = "تم تحديد موقعك الحالي كنقطة انطلاق."
            webView.evaluateJavascript("window.setMarker(${location.longitude}, ${location.latitude});", null)
        } else {
            Toast.makeText(this, "تعذر تحديد موقعك الحالي الآن. تأكد من تفعيل GPS.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            requestLocation()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("ar"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.language = Locale.getDefault()
            }
        }
    }

    override fun onDestroy() {
        stopNavigation()
        tts?.shutdown()
        super.onDestroy()
    }
}
