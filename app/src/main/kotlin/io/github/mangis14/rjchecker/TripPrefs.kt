package io.github.mangis14.rjchecker

import android.content.Context
import io.github.mangis14.rjchecker.core.SeatSnapshot

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

    /** Posledny znamy stav - bez neho by notifikacia isla pri kazdom kole. */
    fun saveSnapshot(snapshot: SeatSnapshot) {
        prefs.edit()
            .putString(
                "snapshot",
                snapshot.seats.entries.joinToString(",") { "${it.key}=${it.value}" },
            )
            .putInt("snapshotFreeInCoach", snapshot.freeInCoach)
            .putString("snapshotCoachFree", snapshot.coachFreeSeats.sorted().joinToString(","))
            .putBoolean("hasSnapshot", true)
            .apply()
    }

    fun loadSnapshot(): SeatSnapshot? {
        if (!prefs.getBoolean("hasSnapshot", false)) return null
        val seats = (prefs.getString("snapshot", "") ?: "")
            .split(",")
            .mapNotNull { part ->
                val kv = part.split("=").takeIf { it.size == 2 } ?: return@mapNotNull null
                kv[0].toIntOrNull()?.let { it to (kv[1] == "true") }
            }.toMap()
        val coachFree = (prefs.getString("snapshotCoachFree", "") ?: "")
            .split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet()
        return SeatSnapshot(
            seats = seats,
            freeInCoach = prefs.getInt("snapshotFreeInCoach", 0),
            coachFreeSeats = coachFree,
        )
    }
}
