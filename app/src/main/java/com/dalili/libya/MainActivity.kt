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
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
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
    private val httpClient = OkHttpClient()

    // Live navigation state: real GPS tracking, not a slider simulation.
    private var isNavigating = false
    private var navStepIndex = 0
    private var locationManager: LocationManager? = null
    private val navListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            onNavLocation(location.latitude, location.longitude)
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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
        }, "DaliliBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                refreshReportsOnMap()
                selectedRoute?.let { showRouteOnMap(it) }
            }
        }
        webView.loadUrl("file:///android_asset/www/index.html")

        val spinnerFrom: Spinner = findViewById(R.id.spinnerFrom)
        val spinnerTo: Spinner = findViewById(R.id.spinnerTo)
        val cityNames = LIBYAN_CITIES.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, cityNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerFrom.adapter = adapter
        spinnerTo.adapter = adapter
        spinnerFrom.setSelection(cityNames.indexOf("زليتن"))
        spinnerTo.setSelection(cityNames.indexOf("طرابلس"))
        // If the user picks a city manually after using current location,
        // the city becomes the origin again instead of silently keeping GPS.
        spinnerFrom.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                useCurrentLocation = false
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val spinnerReport: Spinner = findViewById(R.id.spinnerReportType)
        val reportTypes = listOf("زحام", "حادث", "صيانة", "إغلاق", "طريق ترابي")
        val reportAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, reportTypes)
        reportAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerReport.adapter = reportAdapter

        tts = TextToSpeech(this, this)

        findViewById<Button>(R.id.buttonSwap).setOnClickListener {
            val fromPos = spinnerFrom.selectedItemPosition
            val toPos = spinnerTo.selectedItemPosition
            spinnerFrom.setSelection(toPos)
            spinnerTo.setSelection(fromPos)
            Toast.makeText(this, "تم تبديل الاتجاه", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.buttonLocation).setOnClickListener {
            requestLocation()
        }

        findViewById<Button>(R.id.buttonFind).setOnClickListener {
            val fromCity = LIBYAN_CITIES[spinnerFrom.selectedItemPosition]
            val toCity = LIBYAN_CITIES[spinnerTo.selectedItemPosition]
            val fromLat = if (useCurrentLocation && currentLat != null) currentLat!! else fromCity.lat
            val fromLon = if (useCurrentLocation && currentLon != null) currentLon!! else fromCity.lon
            val fromName = if (useCurrentLocation) "موقعي الحالي" else fromCity.name
            lastFromName = fromName
            lastToName = toCity.name
            stopNavigation()
            findViewById<TextView>(R.id.textStatus).text = "جارٍ حساب المسارات من $fromName إلى ${toCity.name}…"
            Thread {
                try {
                    val found = Routing.fetchRoutes(fromLon, fromLat, toCity.lon, toCity.lat)
                    runOnUiThread {
                        routes = found
                        selectedRoute = found.firstOrNull()
                        if (found.isEmpty()) {
                            findViewById<TextView>(R.id.textStatus).text = "لم يتم العثور على مسار بين المدينتين."
                            findViewById<TextView>(R.id.textRoutes).text = ""
                        } else {
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

        findViewById<Button>(R.id.button3d).setOnClickListener {
            is3d = !is3d
            // set3d() in index.html flies to a building area at zoom 16
            // with pitch 60 and re-adds the buildings layer. setPitch()
            // alone did nothing visible at country zoom, because the
            // buildings layer only renders at zoom 15+.
            webView.evaluateJavascript("window.set3d($is3d);", null)
            Toast.makeText(this, if (is3d) "عرض ثلاثي الأبعاد مفعّل — انتقل إلى منطقة مبانٍ" else "عرض مسطح", Toast.LENGTH_SHORT).show()
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
                    // Manual dragging overrides the automatic animation.
                    webView.evaluateJavascript("window.stopPreviewAnimation();", null)
                    isPreviewAnimating = false
                    try { findViewById<Button>(R.id.buttonPreviewAnim).text = "تشغيل معاينة الرحلة المتحركة" } catch (e: Exception) {}
                    webView.evaluateJavascript("window.previewRoute(${progress / 100.0});", null)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        findViewById<Button>(R.id.buttonReport).setOnClickListener {
            val route = selectedRoute
            if (route == null || route.coordinates.isEmpty()) {
                Toast.makeText(this, "اختر مسارًا أولًا", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val mid = route.coordinates[route.coordinates.size / 2]
            val type = spinnerReport.selectedItem.toString()
            ReportsStore.add(this, RoadReport(type, mid.second, mid.first, System.currentTimeMillis()))
            refreshReportsList()
            refreshReportsOnMap()
            Toast.makeText(this, "تم حفظ البلاغ محليًا على جهازك فقط. بلاغ مستخدم، غير مؤكد رسميًا.", Toast.LENGTH_SHORT).show()
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
                    // Search at three points along the real route, not only the midpoint.
                    val points = listOf(0.25, 0.5, 0.75).map { frac ->
                        val idx = (frac * (route.coordinates.size - 1)).toInt().coerceIn(0, route.coordinates.size - 1)
                        route.coordinates[idx]
                    }
                    val foundNames = linkedSetOf<String>()
                    for ((lon, lat) in points) {
                        val url = "https://nominatim.openstreetmap.org/search?format=json&q=fuel&viewbox=${lon - 0.5},${lat + 0.5},${lon + 0.5},${lat - 0.5}&bounded=1&limit=5"
                        val request = Request.Builder().url(url).header("User-Agent", "DaliliLibya/1.0").build()
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

        refreshReportsList()
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

    @SuppressLint("MissingPermission")
    private fun startNavigation(route: RouteInfo) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // No GPS: fall back to reading all Arabic instructions once.
            val text = route.steps.joinToString(". ") { it.text }
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav")
            Toast.makeText(this, "بدون إذن الموقع، تمت قراءة التعليمات كاملة بدل الملاحة الحية.", Toast.LENGTH_LONG).show()
            return
        }
        isNavigating = true
        navStepIndex = 0
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
        try { locationManager?.removeUpdates(navListener) } catch (e: Exception) {}
        tts?.stop()
        try { findViewById<Button>(R.id.buttonVoice).text = getString(R.string.voice_nav) } catch (e: Exception) {}
    }

    private fun speakStep(route: RouteInfo, index: Int) {
        if (index in route.steps.indices) {
            tts?.speak(route.steps[index].text, TextToSpeech.QUEUE_FLUSH, null, "nav$index")
        }
    }

    private fun onNavLocation(lat: Double, lon: Double) {
        val route = selectedRoute ?: return
        if (!isNavigating) return
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

    private fun distanceMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    private fun showRoutes(fromName: String, toName: String) {
        val consumptionRaw = findViewById<EditText>(R.id.editConsumption).text.toString().toDoubleOrNull() ?: 8.0
        val consumption = if (consumptionRaw > 0) consumptionRaw else 8.0
        val priceRaw = findViewById<EditText>(R.id.editFuelPrice).text.toString().toDoubleOrNull() ?: 0.15
        val price = if (priceRaw >= 0) priceRaw else 0.15
        val reports = ReportsStore.load(this)
        val sb = StringBuilder()
        sb.append("من $fromName إلى $toName\n")
        val sortedByTime = routes.sortedBy { it.durationMin }
        val sortedByCost = routes.sortedBy { it.distanceKm }
        routes.forEachIndexed { index, route ->
            val fuel = Routing.fuelLitres(route.distanceKm, consumption)
            val cost = fuel * price
            val fastest = route == sortedByTime.firstOrNull()
            val cheapest = route == sortedByCost.firstOrNull()
            // Safety is only from reports whose coordinates fall near this route.
            val minLat = route.coordinates.minOfOrNull { it.second } ?: 0.0
            val maxLat = route.coordinates.maxOfOrNull { it.second } ?: 0.0
            val minLon = route.coordinates.minOfOrNull { it.first } ?: 0.0
            val maxLon = route.coordinates.maxOfOrNull { it.first } ?: 0.0
            val nearCount = reports.count { it.lat in (minLat - 0.1)..(maxLat + 0.1) && it.lon in (minLon - 0.1)..(maxLon + 0.1) }
            sb.append("\nالمسار ${index + 1}: ${"%.1f".format(route.distanceKm)} كم، ${route.durationMin.roundToInt()} دقيقة، وقود تقديري ${"%.1f".format(fuel)} لتر، تكلفة تقديرية ${"%.2f".format(cost)} دينار، درجة أمان تقديرية ${Routing.safetyScore(nearCount)}")
            if (fastest) sb.append(" — الأسرع")
            if (cheapest) sb.append(" — الأقل مسافة")
            if (route == selectedRoute) sb.append(" — المعروض على الخريطة")
        }
        sb.append("\n\nدرجة الأمان تقدير من بلاغات قريبة من هذا المسار فقط، وليست تصنيفًا رسميًا.")
        findViewById<TextView>(R.id.textRoutes).text = sb.toString()
        findViewById<TextView>(R.id.textStatus).text = "تم العثور على ${routes.size} مسارًا."
    }

    private fun showRouteOnMap(route: RouteInfo) {
        val js = "window.setRoute(${route.geometryJson});"
        webView.evaluateJavascript(js, null)
    }

    private fun refreshReportsList() {
        val reports = ReportsStore.load(this)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val text = if (reports.isEmpty()) {
            "لا توجد بلاغات محلية بعد. البلاغات محفوظة على جهازك فقط."
        } else {
            reports.joinToString("\n") { r ->
                "• ${r.type} — ${fmt.format(Date(r.timeMillis))} — بلاغ مستخدم، غير مؤكد رسميًا."
            }
        }
        findViewById<TextView>(R.id.textReports).text = text
    }

    private fun refreshReportsOnMap() {
        webView.evaluateJavascript("window.clearReports();", null)
        val reports = ReportsStore.load(this)
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
        if (requestCode == 1001) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                requestLocation()
            } else {
                Toast.makeText(this, "تم رفض إذن الموقع، لذلك لا يمكن استخدام موقعك الحالي.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
