package com.dalili.libya

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class RouteInfo(
    val distanceKm: Double,
    val durationMin: Double,
    val geometryJson: String,
    val steps: List<String>,
    val coordinates: List<Pair<Double, Double>>
)

object Routing {
    private val client = OkHttpClient()

    fun fetchRoutes(fromLon: Double, fromLat: Double, toLon: Double, toLat: Double): List<RouteInfo> {
        val url = "https://router.project-osrm.org/route/v1/driving/" +
            "$fromLon,$fromLat;$toLon,$toLat" +
            "?overview=full&geometries=geojson&steps=true&alternatives=true"
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RuntimeException("OSRM error: ${response.code}")
            val body = response.body?.string() ?: throw RuntimeException("Empty OSRM response")
            val root = JSONObject(body)
            if (root.optString("code") != "Ok") throw RuntimeException("OSRM code: ${root.optString("code")}")
            val routes = root.getJSONArray("routes")
            val out = mutableListOf<RouteInfo>()
            for (i in 0 until routes.length()) {
                val r = routes.getJSONObject(i)
                val geom = r.getJSONObject("geometry")
                val coordsJson = geom.getJSONArray("coordinates")
                val coords = mutableListOf<Pair<Double, Double>>()
                for (j in 0 until coordsJson.length()) {
                    val c = coordsJson.getJSONArray(j)
                    coords.add(Pair(c.getDouble(0), c.getDouble(1)))
                }
                val steps = mutableListOf<String>()
                val legs = r.optJSONArray("legs")
                if (legs != null) {
                    for (li in 0 until legs.length()) {
                        val legSteps = legs.getJSONObject(li).optJSONArray("steps")
                        if (legSteps != null) {
                            for (si in 0 until legSteps.length()) {
                                val s = legSteps.getJSONObject(si)
                                val name = s.optString("name")
                                val maneuver = s.optJSONObject("maneuver")
                                val instruction = maneuver?.optString("instruction") ?: ""
                                val text = if (name.isNotBlank()) "$instruction - $name" else instruction
                                if (text.isNotBlank()) steps.add(text)
                            }
                        }
                    }
                }
                out.add(
                    RouteInfo(
                        distanceKm = r.getDouble("distance") / 1000.0,
                        durationMin = r.getDouble("duration") / 60.0,
                        geometryJson = geom.toString(),
                        steps = steps,
                        coordinates = coords
                    )
                )
            }
            return out
        }
    }

    fun fuelLitres(distanceKm: Double, consumptionPer100Km: Double): Double {
        return distanceKm * consumptionPer100Km / 100.0
    }

    /** Heuristic only: fewer user reports near the route is better. Not an official safety rating. */
    fun safetyScore(reportCount: Int): Int {
        return (100 - reportCount * 15).coerceIn(20, 100)
    }
}
