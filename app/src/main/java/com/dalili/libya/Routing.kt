package com.dalili.libya

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class StepInfo(
    val text: String,
    val lon: Double,
    val lat: Double
)

data class RouteInfo(
    val distanceKm: Double,
    val durationMin: Double,
    val geometryJson: String,
    val steps: List<StepInfo>,
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
            if (routes.length() == 0) return emptyList()
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
                val steps = mutableListOf<StepInfo>()
                val legs = r.optJSONArray("legs")
                if (legs != null) {
                    for (li in 0 until legs.length()) {
                        val legSteps = legs.getJSONObject(li).optJSONArray("steps")
                        if (legSteps != null) {
                            for (si in 0 until legSteps.length()) {
                                val s = legSteps.getJSONObject(si)
                                val name = s.optString("name")
                                val maneuver = s.optJSONObject("maneuver")
                                val type = maneuver?.optString("type") ?: ""
                                val modifier = maneuver?.optString("modifier") ?: ""
                                val loc = maneuver?.optJSONArray("location")
                                val lon = loc?.optDouble(0) ?: 0.0
                                val lat = loc?.optDouble(1) ?: 0.0
                                val text = arabicInstruction(type, modifier, name)
                                if (text.isNotBlank()) steps.add(StepInfo(text, lon, lat))
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

    // OSRM maneuver instructions are in English. Translate them to Arabic so
    // Arabic TextToSpeech reads a real Arabic instruction, not English text
    // read with an Arabic voice.
    fun arabicInstruction(type: String, modifier: String, roadName: String): String {
        val road = if (roadName.isNotBlank()) " على $roadName" else ""
        return when (type) {
            "depart" -> "ابدأ القيادة$road"
            "arrive" -> "لقد وصلت إلى وجهتك"
            "turn" -> when (modifier) {
                "left" -> "انعطف يسارًا$road"
                "right" -> "انعطف يمينًا$road"
                "slight left" -> "انعطف قليلًا يسارًا$road"
                "slight right" -> "انعطف قليلًا يمينًا$road"
                "sharp left" -> "انعطف بشدة يسارًا$road"
                "sharp right" -> "انعطف بشدة يمينًا$road"
                "uturn" -> "استدر$road"
                else -> "اتبع الطريق$road"
            }
            "continue" -> "استمر مباشرة$road"
            "roundabout", "rotary" -> "عند الدوار، اتبع الطريق$road"
            "merge" -> "اندمج في الطريق$road"
            "on ramp" -> "ادخل الطريق$road"
            "off ramp" -> "اخرج من الطريق$road"
            "fork" -> "عند التفرع، اتبع الطريق$road"
            "end of road" -> "عند نهاية الطريق، اتبع الطريق$road"
            "exit roundabout", "exit rotary" -> "اخرج من الدوار$road"
            else -> "اتبع الطريق$road"
        }
    }

    fun fuelLitres(distanceKm: Double, consumptionPer100Km: Double): Double {
        return distanceKm * consumptionPer100Km / 100.0
    }

    /** Heuristic only, counted from reports near this route, not all reports.
     *  Not an official safety rating. */
    fun safetyScore(nearReportCount: Int): Int {
        return (100 - nearReportCount * 15).coerceIn(20, 100)
    }
}
