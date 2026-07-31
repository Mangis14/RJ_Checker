package io.github.mangis14.rjchecker

import android.content.Context

/** Spoj a miesto, ktore chce uzivatel sledovat. */
data class WatchedTrip(
    val date: String,
    val fromId: Long,
    val toId: Long,
    val fromName: String,
    val toName: String,
    val routeId: String,
    val departure: String,
    val coach: Int,
    val seat: Int,
)

/**
 * Ulozenie sledovaneho spoja a poslednej znamej obsadenosti susedov.
 *
 * Posledny stav je potrebny na to, aby notifikacia isla len pri skutocnej zmene
 * - bez neho by appka pri kazdom kole hlasila "zmenu", ktora sa nestala.
 */
class TripPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("rjseat", Context.MODE_PRIVATE)

    fun save(trip: WatchedTrip) {
        prefs.edit().apply {
            putString("date", trip.date)
            putLong("fromId", trip.fromId)
            putLong("toId", trip.toId)
            putString("fromName", trip.fromName)
            putString("toName", trip.toName)
            putString("routeId", trip.routeId)
            putString("departure", trip.departure)
            putInt("coach", trip.coach)
            putInt("seat", trip.seat)
        }.apply()
    }

    fun load(): WatchedTrip? {
        val date = prefs.getString("date", null) ?: return null
        val routeId = prefs.getString("routeId", null) ?: return null
        return WatchedTrip(
            date = date,
            fromId = prefs.getLong("fromId", 0),
            toId = prefs.getLong("toId", 0),
            fromName = prefs.getString("fromName", "") ?: "",
            toName = prefs.getString("toName", "") ?: "",
            routeId = routeId,
            departure = prefs.getString("departure", "") ?: "",
            coach = prefs.getInt("coach", 0),
            seat = prefs.getInt("seat", 0),
        )
    }

    fun clear() = prefs.edit().clear().apply()

    /** Posledny znamy stav susedov ako "31=false,33=true". */
    fun saveSnapshot(seats: Map<Int, Boolean>, freeInCoach: Int) {
        prefs.edit()
            .putString("snapshot", seats.entries.joinToString(",") { "${it.key}=${it.value}" })
            .putInt("snapshotFreeInCoach", freeInCoach)
            .putBoolean("hasSnapshot", true)
            .apply()
    }

    fun loadSnapshot(): Pair<Map<Int, Boolean>, Int>? {
        if (!prefs.getBoolean("hasSnapshot", false)) return null
        val raw = prefs.getString("snapshot", "") ?: ""
        val seats = raw.split(",").mapNotNull { part ->
            val (k, v) = part.split("=").takeIf { it.size == 2 } ?: return@mapNotNull null
            k.toIntOrNull()?.let { it to (v == "true") }
        }.toMap()
        return seats to prefs.getInt("snapshotFreeInCoach", 0)
    }
}
