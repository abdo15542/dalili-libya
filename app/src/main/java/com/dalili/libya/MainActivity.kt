package com.dalili.libya

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.webkit.WebView
import android.webkit.WebViewClient
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
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var webView: WebView
    private var tts: TextToSpeech? = null
    private var routes: List<RouteInfo> = emptyList()
    private var selectedRoute: RouteInfo? = null
    private var is3d = false
    private var useCurrentLocation = false
    private var currentLat: Double? = null
    private var currentLon: Double? = null
    private val httpClient = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                refreshReportsOnMap()
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

        val spinnerReport: Spinner = findViewById(R.id.spinnerReportType)
        val reportTypes = listOf("زحام", "حادث", "صيانة", "إغلاق", "طريق ترابي")
        val reportAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, reportTypes)
        reportAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerReport.adapter = reportAdapter

        tts = TextToSpeech(this, this)

        findViewById<Button>(R.id.buttonLocation).setOnClickListener {
            requestLocation()
        }

        findViewById<Button>(R.id.buttonFind).setOnClickListener {
            val fromCity = LIBYAN_CITIES[spinnerFrom.selectedItemPosition]
            val toCity = LIBYAN_CITIES[spinnerTo.selectedItemPosition]
            val fromLat = if (useCurrentLocation && currentLat != null) currentLat!! else fromCity.lat
            val fromLon = if (useCurrentLocation && currentLon != null) currentLon!! else fromCity.lon
            val fromName = if (useCurrentLocation) "موقعي الحالي" else fromCity.name
            findViewById<TextView>(R.id.textStatus).text = "جارٍ حساب المسارات من $fromName إلى ${toCity.name}…"
            Thread {
                try {
                    val found = Routing.fetchRoutes(fromLon, fromLat, toCity.lon, toCity.lat)
                    runOnUiThread {
                        routes = found
                        selectedRoute = found.firstOrNull()
                        showRoutes(fromName, toCity.name)
                        selectedRoute?.let { showRouteOnMap(it) }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        findViewById<TextView>(R.id.textStatus).text = "تعذر حساب المسار: ${e.message}. تحقق من الاتصال بالإنترنت."
                    }
                }
            }.start()
        }

        findViewById<Button>(R.id.buttonVoice).setOnClickListener {
            val route = selectedRoute
            if (route == null) {
                Toast.makeText(this, "اختر مسارًا أولًا", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val text = if (route.steps.isNotEmpty()) route.steps.joinToString(". ") else "ابدأ القيادة على المسار المحدد."
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav")
        }

        findViewById<Button>(R.id.button3d).setOnClickListener {
            is3d = !is3d
            val pitch = if (is3d) 60 else 0
            webView.evaluateJavascript("window.setPitch($pitch);", null)
            Toast.makeText(this, if (is3d) "عرض ثلاثي الأبعاد مفعّل" else "عرض مسطح", Toast.LENGTH_SHORT).show()
        }

        findViewById<SeekBar>(R.id.seekPreview).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
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
            Toast.makeText(this, "تم حفظ البلاغ محليًا. بلاغ مستخدم، غير مؤكد رسميًا.", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.buttonFuel).setOnClickListener {
            val route = selectedRoute
            if (route == null || route.coordinates.isEmpty()) {
                Toast.makeText(this, "اختر مسارًا أولًا", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val mid = route.coordinates[route.coordinates.size / 2]
            findViewById<TextView>(R.id.textFuel).text = "جارٍ البحث عن محطات وقود قريبة…"
            Thread {
                try {
                    // Search only inside a box around the route midpoint, so results are
                    // actually near the route instead of anywhere in the world.
                    val lon = mid.first
                    val lat = mid.second
                    val url = "https://nominatim.openstreetmap.org/search?format=json&q=fuel&viewbox=${lon - 0.5},${lat + 0.5},${lon + 0.5},${lat - 0.5}&bounded=1&limit=5"
                    val request = Request.Builder().url(url).header("User-Agent", "DaliliLibya/1.0").build()
                    httpClient.newCall(request).execute().use { response ->
                        val body = response.body?.string() ?: "[]"
                        val arr = org.json.JSONArray(body)
                        val names = mutableListOf<String>()
                        for (i in 0 until arr.length()) {
                            names.add(arr.getJSONObject(i).optString("display_name"))
                        }
                        runOnUiThread {
                            findViewById<TextView>(R.id.textFuel).text =
                                if (names.isEmpty()) "لم يتم العثور على محطات وقود قريبة في بيانات OpenStreetMap."
                                else names.joinToString("\n• ", prefix = "محطات قريبة:\n• ")
                        }
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
            tts?.language = Locale("ar")
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    private fun showRoutes(fromName: String, toName: String) {
        val consumption = findViewById<EditText>(R.id.editConsumption).text.toString().toDoubleOrNull() ?: 8.0
        val price = findViewById<EditText>(R.id.editFuelPrice).text.toString().toDoubleOrNull() ?: 0.15
        val reportCount = ReportsStore.load(this).size
        val sb = StringBuilder()
        sb.append("من $fromName إلى $toName\n")
        val sortedByTime = routes.sortedBy { it.durationMin }
        val sortedByCost = routes.sortedBy { it.distanceKm }
        routes.forEachIndexed { index, route ->
            val fuel = Routing.fuelLitres(route.distanceKm, consumption)
            val cost = fuel * price
            val fastest = route == sortedByTime.firstOrNull()
            val cheapest = route == sortedByCost.firstOrNull()
            sb.append("\nالمسار ${index + 1}: ${"%.1f".format(route.distanceKm)} كم، ${route.durationMin.roundToInt()} دقيقة، وقود تقديري ${"%.1f".format(fuel)} لتر، تكلفة تقديرية ${"%.2f".format(cost)} دينار، درجة أمان تقديرية ${Routing.safetyScore(reportCount)}")
            if (fastest) sb.append(" — الأسرع")
            if (cheapest) sb.append(" — الأقل مسافة")
            if (index == 0) sb.append(" — المعروض على الخريطة")
        }
        sb.append("\n\nدرجة الأمان تقدير من نوع الطريق وبلاغات المستخدمين، وليست تصنيفًا رسميًا.")
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
            "لا توجد بلاغات محلية بعد."
        } else {
            reports.joinToString("\n") { r ->
                "• ${r.type} — ${fmt.format(Date(r.timeMillis))} — بلاغ مستخدم، غير مؤكد رسميًا."
            }
        }
        findViewById<TextView>(R.id.textReports).text = text
    }

    private fun refreshReportsOnMap() {
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
        if (requestCode == 1001 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            requestLocation()
        }
    }
}
