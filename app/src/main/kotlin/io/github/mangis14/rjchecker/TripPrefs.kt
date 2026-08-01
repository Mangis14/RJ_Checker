package io.github.mangis14.rjchecker

import android.content.Context
import io.github.mangis14.rjchecker.core.SeatSnapshot
import io.github.mangis14.rjchecker.core.WatchedTrip
import io.github.mangis14.rjchecker.core.WatchedTripCodec

/**
 * Cas odjazdu ako minuty od epochy, alebo null ak sa neda urcit.
 *
 * Podla toho sa rozhoduje, ci sledovanie este ma zmysel - bez toho by worker
 * tahal data aj tyzdne po skoncenej ceste.
 */
fun WatchedTrip.departureEpochMinutes(): Long? = runCatching {
    java.time.LocalDateTime.of(
        java.time.LocalDate.parse(date),
        java.time.LocalTime.parse(departure),
    ).atZone(java.time.ZoneId.systemDefault()).toEpochSecond() / 60
}.getOrNull()

/**
 * Ulozenie sledovanych spojov a poslednej znamej obsadenosti.
 *
 * Spojov moze byt viac naraz - cesta tam aj spat, pripadne dva kandidatske
 * vlaky. Kazdy ma vlastny snapshot pod vlastnym klucom, aby sa zmeny hlasili
 * nezavisle a prve kolo daneho spoja nehlasilo nic.
 */
class TripPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("rjseat", Context.MODE_PRIVATE)

    // --- sledovane spoje ------------------------------------------------

    fun trips(): List<WatchedTrip> = WatchedTripCodec.decode(prefs.getString(KEY_TRIPS, null))

    /** Prida spoj, alebo nahradi rovnaky (podla id). */
    fun addTrip(trip: WatchedTrip) {
        val updated = trips().filter { it.id != trip.id } + trip
        prefs.edit().putString(KEY_TRIPS, WatchedTripCodec.encode(updated)).apply()
    }

    fun removeTrip(id: String) {
        val remaining = trips().filter { it.id != id }
        prefs.edit()
            .putString(KEY_TRIPS, WatchedTripCodec.encode(remaining))
            .remove(snapshotKey(id))
            .apply()
    }

    fun isWatching(id: String): Boolean = trips().any { it.id == id }

    fun clearAll() = prefs.edit().clear().apply()

    // --- posledny znamy stav pre jeden spoj -----------------------------

    /** Bez ulozeneho stavu by notifikacia isla pri kazdom kole. */
    fun saveSnapshot(tripId: String, snapshot: SeatSnapshot) {
        prefs.edit().putString(
            snapshotKey(tripId),
            listOf(
                snapshot.seats.entries.joinToString(",") { "${it.key}=${it.value}" },
                snapshot.freeInCoach.toString(),
                snapshot.coachFreeSeats.sorted().joinToString(","),
                snapshot.emptyBays.joinToString(";"),
                snapshot.classFreeSeats.joinToString(";"),
            ).joinToString("|"),
        ).apply()
    }

    fun loadSnapshot(tripId: String): SeatSnapshot? {
        val raw = prefs.getString(snapshotKey(tripId), null) ?: return null
        val parts = raw.split("|")
        if (parts.size < 4) return null
        val seats = parts[0].split(",").mapNotNull { part ->
            val kv = part.split("=").takeIf { it.size == 2 } ?: return@mapNotNull null
            kv[0].toIntOrNull()?.let { it to (kv[1] == "true") }
        }.toMap()
        return SeatSnapshot(
            seats = seats,
            freeInCoach = parts[1].toIntOrNull() ?: 0,
            coachFreeSeats = parts[2].split(",").mapNotNull { it.trim().toIntOrNull() }.toSet(),
            emptyBays = parts[3].split(";").filter { it.isNotBlank() }.toSet(),
            classFreeSeats = parts.getOrNull(4)
                ?.split(";")?.filter { it.isNotBlank() }?.toSet() ?: emptySet(),
        )
    }

    /** Poradove cislo prebudenia - podla neho sa preskakuju kontroly. */
    fun nextTick(): Int {
        val tick = prefs.getInt("tick", 0)
        prefs.edit().putInt("tick", tick + 1).apply()
        return tick
    }

    private fun snapshotKey(tripId: String) = "snapshot-$tripId"

    private companion object {
        const val KEY_TRIPS = "trips"
    }
}
