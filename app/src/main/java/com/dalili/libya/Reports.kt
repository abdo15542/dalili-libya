package com.dalili.libya

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class RoadReport(
    val type: String,
    val lat: Double,
    val lon: Double,
    val timeMillis: Long
)

object ReportsStore {
    private const val PREFS = "dalili_reports"
    private const val KEY = "reports_json"

    fun load(context: Context): List<RoadReport> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val out = mutableListOf<RoadReport>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(RoadReport(o.getString("type"), o.getDouble("lat"), o.getDouble("lon"), o.getLong("time")))
        }
        return out
    }

    fun add(context: Context, report: RoadReport) {
        val current = load(context).toMutableList()
        current.add(report)
        val arr = JSONArray()
        for (r in current) {
            val o = JSONObject()
            o.put("type", r.type)
            o.put("lat", r.lat)
            o.put("lon", r.lon)
            o.put("time", r.timeMillis)
            arr.put(o)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}
