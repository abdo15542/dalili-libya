package com.dalili.libya

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Saved places (home, work) and recent destinations, stored only on
 * the device in v4.0. The shape is deliberately server-ready: when an
 * account and sync arrive (document Phase 2), these same records are
 * what gets synced, so no app data model has to change then. */
data class SavedPlace(
    val kind: String, // "home" or "work"
    val name: String,
    val lat: Double,
    val lon: Double
)

data class RecentDestination(
    val name: String,
    val lat: Double,
    val lon: Double
)

object PlacesStore {
    private const val PREFS = "dalili_places"
    private const val KEY_SAVED = "saved_json"
    private const val KEY_RECENT = "recent_json"

    fun loadSaved(context: Context): List<SavedPlace> {
        return try {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SAVED, "[]") ?: "[]"
            val arr = JSONArray(raw)
            val out = mutableListOf<SavedPlace>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(SavedPlace(o.getString("kind"), o.getString("name"), o.getDouble("lat"), o.getDouble("lon")))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getSaved(context: Context, kind: String): SavedPlace? {
        return loadSaved(context).firstOrNull { it.kind == kind }
    }

    fun setSaved(context: Context, place: SavedPlace) {
        val current = loadSaved(context).filter { it.kind != place.kind }.toMutableList()
        current.add(place)
        val arr = JSONArray()
        for (p in current) {
            val o = JSONObject()
            o.put("kind", p.kind)
            o.put("name", p.name)
            o.put("lat", p.lat)
            o.put("lon", p.lon)
            arr.put(o)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SAVED, arr.toString()).apply()
    }

    fun loadRecent(context: Context): List<RecentDestination> {
        return try {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RECENT, "[]") ?: "[]"
            val arr = JSONArray(raw)
            val out = mutableListOf<RecentDestination>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(RecentDestination(o.getString("name"), o.getDouble("lat"), o.getDouble("lon")))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Most recent first, at most 5, no duplicates by name. */
    fun pushRecent(context: Context, dest: RecentDestination) {
        val current = loadRecent(context).filter { it.name != dest.name }.toMutableList()
        current.add(0, dest)
        val trimmed = current.take(5)
        val arr = JSONArray()
        for (d in trimmed) {
            val o = JSONObject()
            o.put("name", d.name)
            o.put("lat", d.lat)
            o.put("lon", d.lon)
            arr.put(o)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_RECENT, arr.toString()).apply()
    }
}
