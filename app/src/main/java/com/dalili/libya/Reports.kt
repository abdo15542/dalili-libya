package com.dalili.libya

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class RoadReport(
    val type: String,
    val lat: Double,
    val lon: Double,
    val timeMillis: Long,
    // How many separate reports were filed for the same place and type.
    // This is the local form of the document's verification rule:
    // independent reports about the same place raise confidence, and a
    // single report alone is never presented as confirmed.
    val confirmations: Int = 1
)

object ReportsStore {
    private const val PREFS = "dalili_reports"
    private const val KEY = "reports_json"

    // Temporary road reports expire: the document requires reports to
    // lose validity after a suitable period instead of living forever
    // as if they were current. 12 hours is the local rule until a
    // server can verify and extend them.
    const val EXPIRY_MILLIS: Long = 12L * 60L * 60L * 1000L

    fun isExpired(report: RoadReport, nowMillis: Long = System.currentTimeMillis()): Boolean {
        return nowMillis - report.timeMillis > EXPIRY_MILLIS
    }

    /** Only reports that have not expired. Expired ones are dropped
     * from display, from the map and from safety counts. */
    fun active(context: Context): List<RoadReport> {
        val now = System.currentTimeMillis()
        return load(context).filter { !isExpired(it, now) }
    }

    fun load(context: Context): List<RoadReport> {
        return try {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
            val arr = JSONArray(raw)
            val out = mutableListOf<RoadReport>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    RoadReport(
                        o.getString("type"),
                        o.getDouble("lat"),
                        o.getDouble("lon"),
                        o.getLong("time"),
                        o.optInt("confirmations", 1)
                    )
                )
            }
            out
        } catch (e: Exception) {
            // Corrupt stored data must never crash the app on start.
            emptyList()
        }
    }

    private fun save(context: Context, reports: List<RoadReport>) {
        val arr = JSONArray()
        for (r in reports) {
            val o = JSONObject()
            o.put("type", r.type)
            o.put("lat", r.lat)
            o.put("lon", r.lon)
            o.put("time", r.timeMillis)
            o.put("confirmations", r.confirmations)
            arr.put(o)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    /** Adds a report. If an active report of the same type already
     * exists within about 500 metres, this counts as an independent
     * confirmation of that report instead of a duplicate pin: its
     * confirmation count rises and its time refreshes. */
    fun add(context: Context, report: RoadReport) {
        val current = load(context).toMutableList()
        val now = System.currentTimeMillis()
        val idx = current.indexOfFirst { old ->
            old.type == report.type && !isExpired(old, now) &&
                distanceMetres(old.lat, old.lon, report.lat, report.lon) <= 500.0
        }
        if (idx >= 0) {
            val old = current[idx]
            current[idx] = old.copy(confirmations = old.confirmations + 1, timeMillis = report.timeMillis)
        } else {
            current.add(report)
        }
        save(context, current)
    }

    private fun distanceMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
